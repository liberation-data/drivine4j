package org.drivine.query

import org.drivine.query.grammar.CypherGrammar

/**
 * Generates the Cypher **load** query for a single `@GraphView`: a `MATCH` of the root fragment,
 * the shared projection (delegated to [GraphViewProjectionAssembler]), and the `RETURN { … } AS
 * result` map, with an optional `ORDER BY`.
 *
 * This builder owns only the load-specific composition — the `MATCH` head, the `CALL { }` prolog
 * wiring, and the RETURN shape. The projection of the root fragment and every relationship lives in
 * the assembler, shared with [GraphViewVectorSearchBuilder]. One instance is created per build (see
 * [GraphViewQueryBuilder]); all mutable per-build state lives in the injected [BuildContext].
 */
internal class GraphViewLoadBuilder(
    viewModel: org.drivine.model.GraphViewModel,
    grammar: CypherGrammar,
    private val context: BuildContext,
) {

    private val assembler = GraphViewProjectionAssembler(viewModel, grammar, context)

    /**
     * Builds a Cypher query to load a GraphView with its root fragment and relationships.
     *
     * The query structure:
     * 1. MATCH the root fragment node with optional WHERE clause
     * 2. WITH the root node, collect relationships using pattern comprehension
     * 3. RETURN the assembled object
     * 4. Optional ORDER BY clause
     *
     * For non-nullable, non-collection relationships, the assembler adds EXISTS checks to the WHERE
     * clause to filter out root nodes that don't have the required relationships.
     *
     * @param whereClause Optional WHERE clause conditions (without the WHERE keyword)
     * @param orderByClause Optional ORDER BY clause (without the ORDER BY keywords)
     * @return The generated Cypher query
     */
    fun build(whereClause: String?, orderByClause: String?): String {
        val rootFieldName = assembler.rootFieldName
        val matchClause = "MATCH ($rootFieldName:${assembler.matchLabelString()})"

        // The prologs the where clause brought are all the context holds yet. Building the WITH
        // projection adds its own: a sorted collection, a path, a count, a nested view.
        val wherePrologs = context.prologs.toList()
        val whereBridgeVariables = context.bridgeVariables.toList()
        val withSections = assembler.projectionSections()
        val projectionPrologs = context.prologs.drop(wherePrologs.size)
        val projectionBridgeVariables = context.bridgeVariables.drop(whereBridgeVariables.size)

        // The WHERE stands before the projection's prologs, so each is computed for the roots the
        // load keeps and no others, and the WHERE follows the MATCH, or the WITH of the where
        // clause's own prologs, as it did before there were any. Memgraph reads a pattern in the
        // WHERE of a WITH as true for every row, so a pattern check must not be carried past one.
        val whereSection = assembler.whereSection(whereClause, assembler.requiredRelationshipPatternChecks())
        val prologSection = prologSection(rootFieldName, wherePrologs, whereBridgeVariables)
        val projectionPrologSection = projectionPrologSection(rootFieldName, projectionPrologs, projectionBridgeVariables)

        val withClause = "\n\nWITH\n" + withSections.joinToString(",\n\n")

        val returnClause = """

RETURN {
${assembler.valueFieldEntries("    ").joinToString(",\n")}
} AS result"""

        val orderBySection = if (orderByClause != null) "\nORDER BY $orderByClause" else ""

        return matchClause + prologSection + whereSection + projectionPrologSection + withClause + returnClause + orderBySection
    }

    /**
     * Builds a count query for this view: the same MATCH + WHERE the load query uses (so
     * required-relationship `EXISTS` checks filter roots identically), but returning `count(root)`
     * instead of the projected view. Relationships are never expanded into the MATCH — they are
     * WHERE-clause existence predicates — so each root is counted once.
     */
    fun buildCount(whereClause: String?): String {
        val rootFieldName = assembler.rootFieldName
        val matchClause = "MATCH ($rootFieldName:${assembler.matchLabelString()})"
        val whereSection = assembler.whereSection(whereClause, assembler.requiredRelationshipChecks())
        val prologSection = prologSection(rootFieldName)

        return "$matchClause$prologSection$whereSection\nRETURN count($rootFieldName) AS count"
    }

    /**
     * The `CALL { }` prolog section emitted between MATCH and WHERE: the prologs of the where clause
     * itself. When bridge variables exist (from filtered existence checks on openCypher), a `WITH`
     * carries the root and those variables into WHERE scope.
     */
    private fun prologSection(
        rootFieldName: String,
        prologs: List<String> = context.prologs,
        bridgeVariables: List<String> = context.bridgeVariables,
    ): String {
        if (prologs.isEmpty()) return ""
        val section = "\n" + prologs.joinToString("\n")
        return if (bridgeVariables.isNotEmpty()) {
            "$section\nWITH $rootFieldName, ${bridgeVariables.joinToString(", ")}"
        } else {
            section
        }
    }

    /**
     * The prologs of the projection, emitted after the WHERE. A required path is known to be there
     * only once its prolog has run, so its check follows them, in the `WHERE` of a `WITH` that
     * carries the root and what the prologs computed: a null check on a value, which every engine
     * reads there.
     */
    private fun projectionPrologSection(rootFieldName: String, prologs: List<String>, bridgeVariables: List<String>): String {
        val pathChecks = assembler.requiredPathChecks()
        if (prologs.isEmpty()) {
            check(pathChecks.isEmpty()) { "A required path has no prolog to compute it" }
            return ""
        }
        val section = "\n" + prologs.joinToString("\n")
        if (pathChecks.isEmpty()) return section
        return "$section\nWITH ${(listOf(rootFieldName) + bridgeVariables).joinToString(", ")}\nWHERE " +
            pathChecks.joinToString("\n  AND ")
    }
}
