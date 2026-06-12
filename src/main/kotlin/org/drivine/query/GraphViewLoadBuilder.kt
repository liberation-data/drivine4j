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

        // Build the WITH projection first — it accumulates the prologs/bridge variables the
        // prolog section then reads.
        val withSections = assembler.projectionSections()
        val whereSection = assembler.whereSection(whereClause, assembler.requiredRelationshipChecks())
        val prologSection = prologSection(rootFieldName)

        val withClause = "\n\nWITH\n" + withSections.joinToString(",\n\n")

        val returnClause = """

RETURN {
${assembler.valueFieldEntries("    ").joinToString(",\n")}
} AS result"""

        val orderBySection = if (orderByClause != null) "\nORDER BY $orderByClause" else ""

        return matchClause + prologSection + whereSection + withClause + returnClause + orderBySection
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
     * The `CALL { }` prolog section emitted between MATCH and WHERE. When bridge variables exist
     * (from filtered existence checks on openCypher), a `WITH` carries the root and those variables
     * into WHERE scope.
     */
    private fun prologSection(rootFieldName: String): String {
        if (context.prologs.isEmpty()) return ""
        val prologs = "\n" + context.prologs.joinToString("\n")
        return if (context.bridgeVariables.isNotEmpty()) {
            "$prologs\nWITH $rootFieldName, ${context.bridgeVariables.joinToString(", ")}"
        } else {
            prologs
        }
    }
}