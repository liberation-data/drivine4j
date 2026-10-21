package org.drivine.query

import org.drivine.annotation.GraphView
import org.drivine.backend.CascadeDepthCheck
import org.drivine.backend.GraphStatement
import org.drivine.manager.CascadeType
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel

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
     * - [CascadeType.DELETE_ORPHAN] deletes the root, and each in-view fragment except those kept:
     *   one that a node outside the view refers to, and everything the view reaches from a kept
     *   one, so a kept message keeps its attachments. A kept node's parent is still deleted. The
     *   check reads each fragment's neighbours with a pattern comprehension, which only some
     *   engines support — callers gate this via grammar.supportsOrphanDelete.
     *
     * A recursive relationship is followed to its maxDepth and no further: [buildCascadeDepthChecks]
     * gives the reads that find a node below it, which the caller runs first.
     *
     * Every branch ends in a global aggregation so the query returns exactly one row (a count
     * of 0) even when the id matches nothing, satisfying getOne()'s single-row contract.
     */
    fun buildCascadeDeleteQuery(whereClause: String?, cascade: CascadeType): String {
        if (cascade != CascadeType.DELETE_ALL && cascade != CascadeType.DELETE_ORPHAN) {
            // NONE / PRESERVE: nothing to cascade into — keep the legacy root-only delete.
            return buildDeleteQuery(whereClause)
        }

        val rootFieldName = viewModel.rootFragment.fieldName
        val matchClause = cascadeRootMatch(whereClause)

        // Walk the view's declared relationships to discover every in-scope fragment alias.
        val optionalMatches = mutableListOf<String>()
        val nodeAliases = mutableListOf<String>()
        val followed = mutableListOf<RelationshipModel>()
        collectCascadeMatches(
            parentAlias = rootFieldName,
            model = viewModel,
            counter = intArrayOf(0),
            visitCounts = mapOf(viewModel.className to 1),
            optionalMatches = optionalMatches,
            nodeAliases = nodeAliases,
            followed = followed,
        )
        val optionalSection = if (optionalMatches.isNotEmpty()) "\n" + optionalMatches.joinToString("\n") else ""

        return when (cascade) {
            CascadeType.DELETE_ALL -> {
                val nodesExpr = (listOf("collect(DISTINCT $rootFieldName)") +
                    nodeAliases.map { "collect(DISTINCT $it)" }).joinToString(" + ")
                """
                    |$matchClause$optionalSection
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
                // A node one step of a followed relationship below a kept one is held by it.
                // Holders can chain through every target (a part of two sessions starts a second run of
                // levels), so the walk may take as many steps as there are targets. _found is what is kept,
                // and how many of it the step before had: each step reads holders among the newly kept only,
                // and once a step keeps nothing new the rest pass it through.
                val holders = followed.distinctBy { it.type to it.direction }
                    .joinToString(" + ") { "[ (_holder)${Directions.directionString(it)}(t) | _holder ]" }.ifEmpty { "[]" }
                // A node reached by two fields, or at two levels, is in the targets twice: reduce keeps it once.
                // The neighbours are read before anything is deleted, so a nested node's own children count as in the view.
                """
                    |$matchClause$optionalSection
                    |WITH collect(DISTINCT $rootFieldName) AS _cascadeRoots, $targetsExpr AS _cascadeTargets
                    |WITH _cascadeRoots, _cascadeRoots + _cascadeTargets AS _cascadeAll,
                    |     reduce(_seen = [], t IN _cascadeTargets | CASE WHEN t IN _seen OR t IN _cascadeRoots THEN _seen ELSE _seen + t END) AS _cascadeTargets
                    |WITH _cascadeRoots, _cascadeTargets, [t IN _cascadeTargets WHERE any(m IN [ (t)--(m) | m ] WHERE NOT m IN _cascadeAll)] AS _cascadeKept
                    |WITH _cascadeRoots, _cascadeTargets,
                    |     reduce(_found = [_cascadeKept, 0], _step IN range(1, size(_cascadeTargets)) | CASE WHEN _found[1] = size(_found[0]) THEN _found
                    |         ELSE [_found[0] + [t IN _cascadeTargets WHERE NOT t IN _found[0] AND any(_holder IN $holders WHERE _holder IN _found[0][_found[1]..])], size(_found[0])] END)[0] AS _cascadeKept
                    |WITH _cascadeRoots, [t IN _cascadeTargets WHERE NOT t IN _cascadeKept] AS _cascadeOrphans
                    |FOREACH (_cascadeRoot IN _cascadeRoots | DETACH DELETE _cascadeRoot)
                    |FOREACH (_cascadeOrphan IN _cascadeOrphans | DETACH DELETE _cascadeOrphan)
                    |RETURN size(_cascadeRoots) + size(_cascadeOrphans) AS deleted
                """.trimMargin()
            }
        }
    }

    /**
     * The reads that refuse a cascade which would stop short. A recursive relationship is followed
     * to its maxDepth, and a node one level further, related to a node the cascade deletes, would be
     * left behind with its edge detached, and with it everything it holds. Load can stop short
     * safely; delete can't. One read for each recursive relationship the cascade follows, each
     * counting the nodes it reaches at maxDepth + 1 that it does not reach within maxDepth, nor
     * starts from. Empty when the cascade follows none.
     *
     * @param whereClause the conditions the delete selects its roots by (without the WHERE keyword)
     */
    fun buildCascadeDepthChecks(whereClause: String?): List<CascadeDepthCheck> {
        val rootFieldName = viewModel.rootFragment.fieldName
        val matchClause = cascadeRootMatch(whereClause)
        val recursions = mutableListOf<CascadeRecursion>()
        collectCascadeMatches(
            parentAlias = rootFieldName,
            model = viewModel,
            counter = intArrayOf(0),
            visitCounts = mapOf(viewModel.className to 1),
            optionalMatches = mutableListOf(),
            nodeAliases = mutableListOf(),
            recursions = recursions,
        )
        return recursions.map { recursion ->
            val rel = recursion.rel
            val maxDepth = rel.maxDepth.coerceAtLeast(1)
            val beyond = Directions.varLengthDirectionString(rel, maxDepth + 1, minDepth = maxDepth + 1)
            val ancestors = recursion.ancestors.joinToString("") { "\n$it" }
            val statement = """
                |$matchClause$ancestors
                |OPTIONAL MATCH (${recursion.parentAlias})${Directions.varLengthDirectionString(rel, maxDepth)}(_within:${recursion.targetLabels})
                |WITH $rootFieldName, collect(DISTINCT ${recursion.parentAlias}) AS _parents, collect(DISTINCT _within) AS _withinNodes
                |WITH _parents, _parents + _withinNodes AS _cascaded
                |UNWIND _parents AS _parent
                |OPTIONAL MATCH (_parent)$beyond(_beyond:${recursion.targetLabels})
                |WHERE NOT _beyond IN _cascaded
                |RETURN count(DISTINCT _beyond) AS beyond
            """.trimMargin()
            CascadeDepthCheck(recursion.viewName, rel.fieldName, maxDepth, GraphStatement(statement))
        }
    }

    /** The match of the roots a cascading delete selects by [whereClause]: its depth checks read the same roots. */
    private fun cascadeRootMatch(whereClause: String?): String {
        val rootFragmentModel = viewModel.rootFragment
        val fragmentLabels = GraphTypeLabels.fragmentLabels(rootFragmentModel.fragmentType)
        if (fragmentLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for root fragment ${rootFragmentModel.fragmentType.name}. @GraphFragment must specify at least one label.")
        }
        val matchClause = "MATCH (${rootFragmentModel.fieldName}:${fragmentLabels.joinToString(":")})"
        return if (whereClause != null) "$matchClause\nWHERE $whereClause" else matchClause
    }

    /** A recursive relationship the cascade follows from [parentAlias], which [ancestors] match from the root. */
    private class CascadeRecursion(
        val viewName: String,
        val rel: RelationshipModel,
        val parentAlias: String,
        val targetLabels: String,
        val ancestors: List<String>,
    )

    /**
     * Recursively walks the relationships declared by [model] (starting from a node bound to
     * [parentAlias]), appending one `OPTIONAL MATCH` per declared relationship and recording the
     * target alias for each. Only relationships the view declares and writes are followed: a
     * read-only field is skipped, and edges that exist on target fragments but are absent from the
     * view are never traversed.
     *
     * Direction and maxDepth are honored: a self-referential relationship expands as a
     * variable-length path up to its maxDepth, and chain cycles into nested views terminate once a
     * view type has been entered maxDepth times (mirroring the load-query cycle detection).
     *
     * Each level of a self-referential relationship holds what the root does, as load reads it: the
     * nested view's other relationships are expanded once from the variable-length alias, which
     * stands for every level of it, so one expansion reaches them all.
     *
     * @param counter single-element array used as a shared monotonic alias counter across the walk
     * @param visitCounts how many times each view class has been entered, for chain-cycle cutoff
     * @param followRecursive false at the levels of a recursive relationship, whose own
     *   self-referential fields the variable-length path above already covers
     * @param ancestors the matches that bind [parentAlias] from the root
     * @param recursions where each recursive relationship followed is recorded, with its ancestors
     * @param followed where every relationship followed is recorded
     */
    private fun collectCascadeMatches(
        parentAlias: String,
        model: GraphViewModel,
        counter: IntArray,
        visitCounts: Map<String, Int>,
        optionalMatches: MutableList<String>,
        nodeAliases: MutableList<String>,
        followRecursive: Boolean = true,
        ancestors: List<String> = emptyList(),
        recursions: MutableList<CascadeRecursion> = mutableListOf(),
        followed: MutableList<RelationshipModel> = mutableListOf(),
    ) {
        // A read-only field (every path is one) is not the view's to delete: other code writes what it loads.
        model.relationships.filterNot { it.readOnly || (it.isRecursive && !followRecursive) }.forEach { rel ->
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
            followed.add(rel)

            if (rel.isRecursive) {
                recursions += CascadeRecursion(model.className, rel, parentAlias, targetLabelString, ancestors)
                // Every level holds the nested view's other relationships: expand them from the variable-length alias.
                collectCascadeMatches(
                    parentAlias = nodeAlias,
                    model = GraphViewModel.from(targetType),
                    counter = counter,
                    visitCounts = visitCounts,
                    optionalMatches = optionalMatches,
                    nodeAliases = nodeAliases,
                    followRecursive = false,
                    ancestors = ancestors + pattern,
                    recursions = recursions,
                    followed = followed,
                )
            } else if (targetType.getAnnotation(GraphView::class.java) != null) {
                // Recurse into nested views the view itself declares, respecting chain-cycle maxDepth.
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
                        ancestors = ancestors + pattern,
                        recursions = recursions,
                        followed = followed,
                    )
                }
            }
        }
    }
}
