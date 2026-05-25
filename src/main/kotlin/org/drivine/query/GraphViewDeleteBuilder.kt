package org.drivine.query

import org.drivine.annotation.GraphView
import org.drivine.manager.CascadeType
import org.drivine.model.GraphViewModel

/**
 * Generates DELETE Cypher for a single @GraphView — both the legacy root-only delete and the
 * cascade-aware variants. The view is the cascade boundary: traversal follows only the
 * relationships the view declares, never edges that merely exist on the target fragments.
 */
internal class GraphViewDeleteBuilder(
    private val viewModel: GraphViewModel,
) {

    /**
     * Builds a root-only DETACH DELETE for the view's root fragment. DETACH drops the root's
     * relationships but leaves every related node intact.
     *
     * @param whereClause Optional WHERE clause conditions (without the WHERE keyword)
     * @param prologs Optional `CALL { }` prologs to emit between MATCH and WHERE
     * @param bridgeVariables Variables a WITH must carry from the prologs into WHERE scope
     */
    fun buildDeleteQuery(
        whereClause: String?,
        prologs: List<String> = emptyList(),
        bridgeVariables: List<String> = emptyList(),
    ): String {
        val rootFragmentModel = viewModel.rootFragment
        val rootFieldName = rootFragmentModel.fieldName

        val fragmentLabels = GraphTypeLabels.fragmentLabels(rootFragmentModel.fragmentType)
        if (fragmentLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for root fragment ${rootFragmentModel.fragmentType.name}. @GraphFragment must specify at least one label.")
        }

        val labelString = fragmentLabels.joinToString(":")
        val matchClause = "MATCH ($rootFieldName:$labelString)"

        val prologSection = if (prologs.isNotEmpty()) {
            val prologBlock = "\n" + prologs.joinToString("\n")
            if (bridgeVariables.isNotEmpty()) {
                "$prologBlock\nWITH $rootFieldName, ${bridgeVariables.joinToString(", ")}"
            } else {
                prologBlock
            }
        } else {
            ""
        }

        val whereSection = if (whereClause != null) {
            "\nWHERE $whereClause"
        } else {
            ""
        }

        return """
            |$matchClause$prologSection$whereSection
            |DETACH DELETE $rootFieldName
            |RETURN count(*) AS deleted
        """.trimMargin()
    }

    /**
     * Builds a cascade-aware DELETE query for a GraphView, scoped by the view's shape.
     *
     * The view is the cascade boundary: only nodes reachable through the view's declared
     * @GraphRelationships are deleted. Relationships that exist on the target fragments in
     * the database but are *not* declared by the view are never traversed — DETACH DELETE
     * merely drops the edges to nodes outside the view, leaving those nodes intact.
     *
     * - [CascadeType.NONE] (and [CascadeType.PRESERVE], which is meaningless for delete)
     *   fall through to the root-only [buildDeleteQuery] — byte-for-byte the legacy behavior.
     * - [CascadeType.DELETE_ALL] collects the root plus every reachable in-view fragment and
     *   DETACH DELETEs all of them.
     * - [CascadeType.DELETE_ORPHAN] deletes the root, then deletes each in-view fragment only
     *   if it has no relationships left once the root (and the rest of the view) is gone.
     *   The orphan check uses a pattern-comprehension degree probe after the root delete, which
     *   only some engines support — callers gate this via grammar.supportsOrphanDelete.
     *
     * Every branch ends in a global aggregation so the query returns exactly one row (a count
     * of 0) even when the id matches nothing, satisfying getOne()'s single-row contract.
     */
    fun buildCascadeDeleteQuery(whereClause: String?, cascade: CascadeType): String {
        if (cascade != CascadeType.DELETE_ALL && cascade != CascadeType.DELETE_ORPHAN) {
            // NONE / PRESERVE: nothing to cascade into — keep the legacy root-only delete.
            return buildDeleteQuery(whereClause)
        }

        val rootFragmentModel = viewModel.rootFragment
        val rootFieldName = rootFragmentModel.fieldName

        val fragmentLabels = GraphTypeLabels.fragmentLabels(rootFragmentModel.fragmentType)
        if (fragmentLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for root fragment ${rootFragmentModel.fragmentType.name}. @GraphFragment must specify at least one label.")
        }
        val labelString = fragmentLabels.joinToString(":")
        val matchClause = "MATCH ($rootFieldName:$labelString)"
        val whereSection = if (whereClause != null) "\nWHERE $whereClause" else ""

        // Walk the view's declared relationships to discover every in-scope fragment alias.
        val optionalMatches = mutableListOf<String>()
        val nodeAliases = mutableListOf<String>()
        collectCascadeMatches(
            parentAlias = rootFieldName,
            model = viewModel,
            counter = intArrayOf(0),
            visitCounts = mapOf(viewModel.className to 1),
            optionalMatches = optionalMatches,
            nodeAliases = nodeAliases,
        )
        val optionalSection = if (optionalMatches.isNotEmpty()) "\n" + optionalMatches.joinToString("\n") else ""

        return when (cascade) {
            CascadeType.DELETE_ALL -> {
                val nodesExpr = (listOf("collect(DISTINCT $rootFieldName)") +
                    nodeAliases.map { "collect(DISTINCT $it)" }).joinToString(" + ")
                """
                    |$matchClause$whereSection$optionalSection
                    |WITH $nodesExpr AS _cascadeNodes
                    |UNWIND _cascadeNodes AS _cascadeNode
                    |WITH DISTINCT _cascadeNode
                    |DETACH DELETE _cascadeNode
                    |RETURN count(_cascadeNode) AS deleted
                """.trimMargin()
            }
            else -> { // DELETE_ORPHAN
                val targetsExpr = if (nodeAliases.isEmpty()) "[]"
                    else nodeAliases.joinToString(" + ") { "collect(DISTINCT $it)" }
                """
                    |$matchClause$whereSection$optionalSection
                    |WITH collect(DISTINCT $rootFieldName) AS _cascadeRoots, $targetsExpr AS _cascadeTargets
                    |FOREACH (_cascadeRoot IN _cascadeRoots | DETACH DELETE _cascadeRoot)
                    |WITH _cascadeRoots, [t IN _cascadeTargets WHERE size([ (t)--() | 1 ]) = 0] AS _cascadeOrphans
                    |FOREACH (_cascadeOrphan IN _cascadeOrphans | DETACH DELETE _cascadeOrphan)
                    |RETURN size(_cascadeRoots) + size(_cascadeOrphans) AS deleted
                """.trimMargin()
            }
        }
    }

    /**
     * Recursively walks the relationships declared by [model] (starting from a node bound to
     * [parentAlias]), appending one `OPTIONAL MATCH` per declared relationship and recording the
     * target alias for each. Only relationships the view declares are followed; edges that exist
     * on target fragments but are absent from the view are never traversed.
     *
     * Direction and maxDepth are honored: a self-referential relationship expands as a
     * variable-length path up to its maxDepth, and chain cycles into nested views terminate once a
     * view type has been entered maxDepth times (mirroring the load-query cycle detection).
     *
     * @param counter single-element array used as a shared monotonic alias counter across the walk
     * @param visitCounts how many times each view class has been entered, for chain-cycle cutoff
     */
    private fun collectCascadeMatches(
        parentAlias: String,
        model: GraphViewModel,
        counter: IntArray,
        visitCounts: Map<String, Int>,
        optionalMatches: MutableList<String>,
        nodeAliases: MutableList<String>,
    ) {
        model.relationships.forEach { rel ->
            val targetType = if (rel.isRelationshipFragment) rel.targetNodeType!! else rel.elementType
            val targetLabels = GraphTypeLabels.labelsForType(targetType)
            if (targetLabels.isEmpty()) return@forEach
            val targetLabelString = targetLabels.joinToString(":")

            val nodeAlias = "_cnode${counter[0]++}"
            val pattern = if (rel.isRecursive) {
                val maxDepth = rel.maxDepth.coerceAtLeast(1)
                "OPTIONAL MATCH ($parentAlias)${Directions.varLengthDirectionString(rel, maxDepth)}($nodeAlias:$targetLabelString)"
            } else {
                "OPTIONAL MATCH ($parentAlias)${Directions.directionString(rel)}($nodeAlias:$targetLabelString)"
            }
            optionalMatches.add(pattern)
            nodeAliases.add(nodeAlias)

            // Recurse into nested views the view itself declares, respecting chain-cycle maxDepth.
            // Recursive (self-referential) relationships are already covered by the variable-length
            // path above, so they are not expanded again here.
            if (!rel.isRecursive && targetType.getAnnotation(GraphView::class.java) != null) {
                val className = targetType.name
                val visited = visitCounts.getOrDefault(className, 0)
                if (visited < rel.maxDepth) {
                    val nestedModel = GraphViewModel.from(targetType)
                    collectCascadeMatches(
                        parentAlias = nodeAlias,
                        model = nestedModel,
                        counter = counter,
                        visitCounts = visitCounts + (className to visited + 1),
                        optionalMatches = optionalMatches,
                        nodeAliases = nodeAliases,
                    )
                }
            }
        }
    }
}