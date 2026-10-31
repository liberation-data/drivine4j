package org.drivine.backend.sqlite

import org.drivine.annotation.Direction
import org.drivine.backend.sql.Derived
import org.drivine.backend.sql.Query
import org.drivine.backend.sql.RowSource
import org.drivine.backend.sql.StatementScope
import org.drivine.backend.sql.Step
import org.drivine.backend.sql.Table
import org.drivine.backend.sql.under

/**
 * The row sources a statement over a [SqliteGraph] reads from: the nodes of the node table, and
 * the nodes reached from a node by a path. SQLite has no graph pattern, so a path is the joins
 * Cypher's pattern stands for: from a node, over a row of the edge table of the step's type, to the
 * node at its far end, and on. A step taken to a depth is a recursive query that walks it.
 *
 * Every node a path reaches is a row of the node table: `nid`, `labels`, `props` and `stamp`, under
 * the name the statement gives it, so a predicate on it reads as one on the root does. The node it
 * starts from is `src`.
 */
internal class SqliteRowSources(private val graph: SqliteGraph, private val scope: StatementScope) {

    /** The nodes of the graph, as rows named [alias]: the roots of a statement, which says what labels they carry. */
    fun nodes(alias: String): RowSource = Table(graph.nodes, alias)

    /**
     * The nodes reached by [steps] from the node [anchor] names, as rows named [alias]: `src` is the
     * `nid` of the node the path starts from. A single step also gives the relationship, as `eid` and
     * `eprops`. A step taken to a depth is taken from its least depth to its most, never over one
     * relationship twice, and a node may then be reached more than once. The nodes reached are those
     * that meet [where] beside the last step's labels. [extra] are further columns, each a name and
     * what gives it, which may read any node of the path: the last as [alias], one before it as
     * [stepNode] names it.
     *
     * @param anchor the nodes the path starts from, as the test it makes of the column that holds the
     *   `nid` of each: `= parent_.nid` for the row of the statement it is reached from, a correlation
     *   that keeps a walk to a depth to that node's relationships, or `IN (…)` for a set of them. Null
     *   reads the path from every node, which the statement then narrows by `src`.
     */
    fun reached(
        anchor: Anchor?, steps: List<Step>, alias: String, where: List<String> = emptyList(), extra: List<Pair<String, String>> = emptyList(),
    ): RowSource {
        require(steps.isNotEmpty()) { "A path has at least one step." }
        val walked = steps.singleOrNull()?.takeIf { it.maxDepth > 1 }
        return when {
            walked != null -> walk(anchor, walked, alias, where, extra)
            steps.all { it.maxDepth == 1 } -> hops(anchor, steps, alias, where, extra)
            else -> {
                require(extra.isEmpty()) { "A path that takes a step to a depth among others gives no columns but its nodes'." }
                chained(anchor, steps, alias, where)
            }
        }
    }

    /** The nodes a path starts from, as the test it makes of the column that holds a start's `nid`. */
    fun interface Anchor {
        fun test(nid: String): String

        companion object {
            /** The one node of the row [parent]. */
            fun row(parent: String) = Anchor { "$it = $parent.nid" }

            /** The nodes whose `nid` the query [nids] selects. */
            fun among(nids: String) = Anchor { "$it IN (\n    ${nids.under()})" }
        }
    }

    /** The name [reached] gives the node step [i] of [steps] reaches: [alias] for the last, `m1`, `m2`… before it. */
    fun stepNode(steps: List<Step>, i: Int, alias: String): String = if (i == steps.lastIndex) alias else "m${i + 1}"

    /** Steps of one hop each, as one join of an edge and a node to each. */
    private fun hops(anchor: Anchor?, steps: List<Step>, alias: String, where: List<String>, extra: List<Pair<String, String>>): RowSource {
        val single = steps.size == 1
        val joins = StringBuilder()
        val tests = mutableListOf<String>()
        steps.forEachIndexed { i, step ->
            val edge = if (single) "e" else "e${i + 1}"
            val node = stepNode(steps, i, alias)
            val ends = Ends.of(step.direction, edge)
            if (i == 0) {
                joins.append(edges(step.direction, edge))
                anchor?.let { tests += it.test(ends.near) }
            } else {
                joins.append("\nJOIN ").append(edges(step.direction, edge)).append(" ON ${ends.near} = ${stepNode(steps, i - 1, alias)}.nid")
            }
            joins.append("\nJOIN ${graph.nodes} $node ON $node.nid = ${ends.far}")
            tests += "$edge.type = ${SqliteSql.literal(step.type)}"
            SqliteSql.hasLabels(node, step.labels)?.let { tests += it }
        }
        // Cypher walks a relationship once in a path: two hops of one type are two relationships.
        steps.indices.forEach { i ->
            (i + 1..steps.lastIndex).filter { steps[it].type == steps[i].type }.forEach { j -> tests += "e${i + 1}.eid <> e${j + 1}.eid" }
        }
        tests += where
        val start = Ends.of(steps.first().direction, if (single) "e" else "e1").near
        val columns = listOf("$start AS src") +
            (if (single) listOf("e.eid", "e.props AS eprops") else emptyList()) +
            SqliteSql.NODE_COLUMNS.map { "$alias.$it" } + extra.map { (name, expression) -> "$expression AS $name" }
        return Derived(Query(columns.joinToString(", "), Joined(joins.toString()), tests).text(), alias)
    }

    /**
     * One step taken from its least depth to its most, as a recursive query: each way from the node it
     * starts from, a relationship at a time, with the relationships it has taken so far, none of which
     * it takes again. A node reached by several ways is a row for each. SQLite has no arrays, so the
     * relationships taken are the text of their ids, each between commas, which a step looks in.
     */
    private fun walk(anchor: Anchor?, step: Step, alias: String, where: List<String>, extra: List<Pair<String, String>>): RowSource {
        val walk = scope.row("walk")
        val type = SqliteSql.literal(step.type)
        val first = Ends.of(step.direction, "e")
        val next = Ends.of(step.direction, "e")
        val text = listOf(
            "WITH RECURSIVE $walk (src, nid, depth, eids) AS (",
            "    SELECT ${first.near}, ${first.far}, 1, ',' || e.eid || ','",
            "    FROM ${edges(step.direction, "e")}",
            "    WHERE e.type = $type${anchor?.let { " AND ${it.test(first.near)}" }.orEmpty()}",
            "    UNION ALL",
            "    SELECT w.src, ${next.far}, w.depth + 1, w.eids || e.eid || ','",
            "    FROM $walk w JOIN ${edges(step.direction, "e")} ON ${next.near} = w.nid",
            "    WHERE e.type = $type AND w.depth < ${step.maxDepth} AND instr(w.eids, ',' || e.eid || ',') = 0",
            ")",
            Query(
                (listOf("w.src") + SqliteSql.NODE_COLUMNS.map { "$alias.$it" } + extra.map { (name, expression) -> "$expression AS $name" }).joinToString(", "),
                Joined("$walk w\nJOIN ${graph.nodes} $alias ON $alias.nid = w.nid"),
                listOfNotNull("w.depth >= ${step.minDepth}", SqliteSql.hasLabels(alias, step.labels)) + where,
            ).text(),
        ).joinToString("\n")
        return Derived(text, alias)
    }

    /**
     * Steps of which one or more is taken to a depth, as a cascading delete follows a field that holds
     * views of their own class below others: each step reads the nodes the one before it reached,
     * a hop as a join and a step to a depth as a walk from them. The rows are the nodes the last step
     * reaches, each with `src`, the node the path started from.
     */
    private fun chained(anchor: Anchor?, steps: List<Step>, alias: String, where: List<String>): RowSource {
        var reached: RowSource? = null
        steps.forEachIndexed { i, step ->
            val name = if (i == steps.lastIndex) alias else scope.row("step")
            val from = reached
            val start = if (from == null) anchor else Anchor { "$it IN (SELECT ${from.alias}.nid FROM ${from.reading(emptyList())})" }
            val tests = if (i == steps.lastIndex) where else emptyList()
            val one = reached(start, listOf(step), name, tests)
            // The path's start, carried through each step.
            reached = if (from == null) one else Derived(
                Query("${from.alias}.src, ${SqliteSql.columns(name)}", Joined("${from.reading(emptyList())}\nJOIN ${one.reading(emptyList())} ON $name.src = ${from.alias}.nid")).text(),
                name,
            )
        }
        return reached!!
    }

    /**
     * The rows named [row] reached from the row [parent] by [steps]: the query a relationship field or
     * an aggregate is, once it says what it selects of them.
     */
    fun related(select: String, parent: String, steps: List<Step>, row: String, tail: List<String> = emptyList()): Query =
        Query(select, reached(Anchor.row(parent), steps, row), emptyList(), tail)

    /** Whether some node reached from the row [parent] by [steps] meets [predicates], which name the reached node's row as [row]. */
    fun exists(parent: String, steps: List<Step>, row: String, predicates: List<String> = emptyList()): String =
        "EXISTS ${Query("1", reached(Anchor.row(parent), steps, row, predicates)).nested()}"

    /**
     * The edge table as a step in [direction] reads it, named [edge]. A step either way reads each
     * relationship from both of its ends, and one from a node to itself once.
     */
    private fun edges(direction: Direction, edge: String): String = when (direction) {
        Direction.UNDIRECTED ->
            "(SELECT eid, src AS near, dst AS far, type, props FROM ${graph.edges} UNION ALL SELECT eid, dst, src, type, props FROM ${graph.edges} WHERE src <> dst) $edge"
        else -> "${graph.edges} $edge"
    }

    /** The columns of the edge [edge] at the end a step leaves from and the end it arrives at. */
    private class Ends(val near: String, val far: String) {
        companion object {
            fun of(direction: Direction, edge: String): Ends = when (direction) {
                Direction.OUTGOING -> Ends("$edge.src", "$edge.dst")
                Direction.INCOMING -> Ends("$edge.dst", "$edge.src")
                Direction.UNDIRECTED -> Ends("$edge.near", "$edge.far")
            }
        }
    }

    /** Tables joined as [text] says, which name their own rows. */
    private class Joined(private val text: String) : RowSource {
        override val alias: String get() = ""
        override fun reading(readers: List<String>): String = text.under()
    }
}
