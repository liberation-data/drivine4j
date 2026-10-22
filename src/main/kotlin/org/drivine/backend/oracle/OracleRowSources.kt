package org.drivine.backend.oracle

import org.drivine.backend.sql.Query
import org.drivine.backend.sql.Step
import org.drivine.backend.oracle.OracleSql.hasLabels
import org.drivine.model.RelationshipModel

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

    /** The nodes that carry [labels], as rows named [alias]: the roots of a statement. */
    fun nodes(labels: List<String>, alias: String): GraphTable =
        graphTable(OracleSql.node(alias, scope.rootHasLabels(alias, labels)), GraphTable.node(alias), alias)

    /** A `GRAPH_TABLE` over the graph that matches [pattern] and can give [columns], as rows named [alias]. */
    fun graphTable(pattern: String, columns: List<GraphTable.Column>, alias: String): GraphTable =
        GraphTable(graph.name, pattern, columns, alias)

    /**
     * The nodes reached by [steps] from the node [from], as rows named [alias]: `src` is the node the
     * path starts from, which the pattern calls what the statement calls that node's row. A single
     * step also gives the relationship, as `eid` and `eprops`. A step with a depth above one is taken
     * from its least depth to that many times, and a node may then be reached more than once. The nodes reached
     * are those that meet [where], which the pattern tests them for beside the last step's labels.
     * [extra] are further columns, which may read any node of the pattern: the last as [alias], one
     * before it as [stepNode] names it.
     */
    fun reached(
        from: String, steps: List<Step>, alias: String, where: List<String> = emptyList(), extra: List<GraphTable.Column> = emptyList(),
    ): GraphTable {
        val single = steps.size == 1 && steps.single().maxDepth == 1
        // A path of several steps is a line to a step, so each is read as the hop it is.
        val hop = if (steps.size == 1) " " else "\n        "
        val pattern = buildString {
            append(OracleSql.node(from))
            steps.forEachIndexed { i, step ->
                append(hop).append(OracleSql.arrow(if (steps.size == 1) "e" else "e${i + 1}", step.type, step.direction))
                if (step.maxDepth > 1) append("{${step.minDepth},${step.maxDepth}}")
                val node = stepNode(steps, i, alias)
                val met = listOfNotNull(hasLabels(node, step.labels)) + if (i == steps.lastIndex) where else emptyList()
                append(" ").append(OracleSql.node(node, met.takeIf { it.isNotEmpty() }?.joinToString(" AND ")))
            }
        }
        val relationship = if (single) listOf(GraphTable.Column("eid", "e.eid"), GraphTable.Column("eprops", "e.props")) else emptyList()
        return graphTable(pattern, listOf(GraphTable.Column("src", "$from.nid")) + relationship + GraphTable.node(alias) + extra, alias)
    }

    /** The name [reached] gives the node step [i] of [steps] reaches: [alias] for the last, `m1`, `m2`… before it. */
    fun stepNode(steps: List<Step>, i: Int, alias: String): String = if (i == steps.lastIndex) alias else "m${i + 1}"

    /** The steps that reach the nodes of [relationship]: see [Step.reaching]. */
    fun steps(relationship: RelationshipModel, targetLabels: List<String>, maxDepth: Int = 1): List<Step> =
        Step.reaching(relationship, targetLabels, maxDepth)

    /**
     * The rows named [row] reached from the row [parent] by [steps]: the query a relationship field or
     * an aggregate is, once it says what it selects of them.
     */
    fun related(select: String, parent: String, steps: List<Step>, row: String, tail: List<String> = emptyList()): Query =
        Query(select, reached(parent, steps, row), listOf("$row.src = $parent.nid"), tail)

    /**
     * Whether some node reached from the row [parent] by [steps] meets [where] and [predicates], which
     * name the reached node's row as [row].
     *
     * [where] is what is asked of the reached node alone, and is tested in the pattern, not on the
     * rows it gives: a test of a property that has an index, made on those rows, ends the server
     * process of Oracle Free 23.26 (`ORA-07445` in `kkosjt`, which the client sees as `ORA-03113`).
     * [predicates] are those a pattern cannot hold, which are the ones written with a subquery
     * (`ORA-40988`): a query of another pattern, or a test against a list. Left on the rows, those
     * were not seen to end it.
     */
    fun exists(parent: String, steps: List<Step>, row: String, where: List<String> = emptyList(), predicates: List<String> = emptyList()): String =
        "EXISTS ${Query("1", reached(parent, steps, row, where), listOf("$row.src = $parent.nid") + predicates).nested()}"
}
