package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.backend.oracle.OracleSql.hasLabels
import org.drivine.model.RelationshipModel

/** One step from a node to the nodes it is related to: the relationship's type and direction, and the labels the far node carries. */
internal class Step(val type: String, val direction: Direction, val labels: List<String>, val maxDepth: Int = 1)

/**
 * The row sources a statement over an [OracleGraph] reads from: the nodes of some labels, and the
 * nodes reached from a node by a path. Each is a `GRAPH_TABLE` over the graph, whose `MATCH` is the
 * pattern Cypher would write, with `IS node` and `IS edge` where Cypher has a label and a type, since
 * both are data here. A node is named in the pattern as its row is in the statement.
 *
 * A nested `GRAPH_TABLE` cannot name the row it is nested in, so a traversal returns the node it
 * starts from as `src`, and is joined to that row outside.
 */
internal class OracleRowSources(private val graph: OracleGraph, private val scope: OracleStatementScope) {

    /** The nodes that carry [labels], as rows named [alias]. */
    fun nodes(labels: List<String>, alias: String): GraphTable =
        graphTable(OracleSql.node(alias, hasLabels(alias, labels, scanned = !scope.narrowed)), GraphTable.node(alias), alias)

    /** A `GRAPH_TABLE` over the graph that matches [pattern] and can give [columns], as rows named [alias]. */
    fun graphTable(pattern: String, columns: List<GraphTable.Column>, alias: String): GraphTable =
        GraphTable(graph.name, pattern, columns, alias)

    /**
     * The nodes reached by [steps] from the node [from], as rows named [alias]: `src` is the node the
     * path starts from, which the pattern calls what the statement calls that node's row. A single
     * step also gives the relationship, as `eid` and `eprops`. A step with a depth above one is taken
     * from one to that many times, and a node may then be reached more than once.
     */
    fun reached(from: String, steps: List<Step>, alias: String): GraphTable {
        val single = steps.size == 1 && steps.single().maxDepth == 1
        // A path of several steps is a line to a step, so each is read as the hop it is.
        val hop = if (steps.size == 1) " " else "\n        "
        val pattern = buildString {
            append(OracleSql.node(from))
            steps.forEachIndexed { i, step ->
                append(hop).append(OracleSql.arrow(if (steps.size == 1) "e" else "e${i + 1}", step.type, step.direction))
                if (step.maxDepth > 1) append("{1,${step.maxDepth}}")
                val node = if (i == steps.lastIndex) alias else "m${i + 1}"
                append(" ").append(OracleSql.node(node, hasLabels(node, step.labels)))
            }
        }
        val relationship = if (single) listOf(GraphTable.Column("eid", "e.eid"), GraphTable.Column("eprops", "e.props")) else emptyList()
        return graphTable(pattern, listOf(GraphTable.Column("src", "$from.nid")) + relationship + GraphTable.node(alias), alias)
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
     * The rows named [row] reached from the row [parent] by [steps], and of those the ones that meet
     * [predicates]: the query a relationship field, an aggregate or a test of one is, once it says
     * what it selects of them.
     */
    fun related(select: String, parent: String, steps: List<Step>, row: String, predicates: List<String> = emptyList(), tail: List<String> = emptyList()): Query =
        Query(select, reached(parent, steps, row), listOf("$row.src = $parent.nid") + predicates, tail)

    /** Whether some node reached from the row [parent] by [steps] meets [predicates], which name the reached node's row as [row]. */
    fun exists(parent: String, steps: List<Step>, row: String, predicates: List<String> = emptyList()): String =
        "EXISTS ${related("1", parent, steps, row, predicates).nested()}"
}
