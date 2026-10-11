package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.backend.oracle.OracleSql.hasLabels
import org.drivine.model.RelationshipModel

/** One step from a node to the nodes it is related to: the relationship's type and direction, and the labels the far node carries. */
internal class Step(val type: String, val direction: Direction, val labels: List<String>, val maxDepth: Int = 1)

/**
 * The row sources a statement over an [OracleGraph] reads from: the nodes of some labels, and the
 * nodes reached from any node by a path. Each is a `GRAPH_TABLE` over the graph. A nested
 * `GRAPH_TABLE` cannot name the row it is nested in, so a traversal returns the node it starts from as
 * `src`, and is joined to that row outside.
 */
internal class OracleRowSources(private val graph: OracleGraph, private val scope: OracleStatementScope) {

    /** The nodes that carry [labels], as rows named [alias]. */
    fun nodes(labels: List<String>, alias: String): String =
        graphTable("(v IS node${hasLabels("v", labels, scanned = !scope.narrowed)?.let { " WHERE $it" }.orEmpty()})", OracleSql.columns("v"), alias)

    /** A `GRAPH_TABLE` over the graph that matches [pattern] and gives [columns], as rows named [alias]. */
    fun graphTable(pattern: String, columns: String, alias: String): String =
        "GRAPH_TABLE (${graph.name}\n    MATCH $pattern\n    COLUMNS ($columns)) $alias"

    /**
     * The nodes reached by [steps], as rows named [alias]: `src` is the node the path starts from.
     * A single step also gives the relationship, as `eid` and `eprops`. A step with a depth above one
     * is taken from one to that many times, and a node may then be reached more than once.
     */
    fun reached(steps: List<Step>, alias: String): String {
        val single = steps.size == 1 && steps.single().maxDepth == 1
        val pattern = buildString {
            append("(a IS node)")
            steps.forEachIndexed { i, step ->
                append(" ").append(OracleSql.arrow("e$i", step.type, step.direction))
                if (step.maxDepth > 1) append("{1,${step.maxDepth}}")
                val node = if (i == steps.lastIndex) "b" else "m$i"
                append(" ($node IS node").append(hasLabels(node, step.labels)?.let { " WHERE $it" }.orEmpty()).append(")")
            }
        }
        val relationship = if (single) "e0.eid AS eid, e0.props AS eprops, " else ""
        return graphTable(pattern, "a.nid AS src, $relationship${OracleSql.columns("b")}", alias)
    }

    /**
     * The steps that reach the nodes of [relationship]: for a path, one for each hop, the far node of
     * each but the last carrying the hop's intermediate label; otherwise one step to [maxDepth], with
     * [targetLabels] on the node it ends at.
     */
    fun steps(relationship: RelationshipModel, targetLabels: List<String>, maxDepth: Int = 1): List<Step> =
        if (relationship.isPath) {
            relationship.hops.mapIndexed { i, hop ->
                Step(hop.type, hop.direction, if (i == relationship.hops.lastIndex) targetLabels else listOfNotNull(hop.intermediateLabel))
            }
        } else {
            listOf(Step(relationship.type, relationship.direction, targetLabels, maxDepth))
        }

    /**
     * Whether some node reached from [parent] by [steps] meets [predicate], which names the reached
     * node's row as [row]; a row named here when the caller gave none.
     */
    fun exists(parent: String, steps: List<Step>, row: String?, predicate: String? = null): String {
        val alias = row ?: scope.next("t")
        return "EXISTS (SELECT 1 FROM ${reached(steps, alias)} WHERE $alias.src = $parent.nid${predicate?.let { " AND $it" }.orEmpty()})"
    }
}
