package org.drivine.test

import org.drivine.test.MiniCypherAst.Clause
import org.drivine.test.MiniCypherAst.Comparison
import org.drivine.test.MiniCypherAst.Direction
import org.drivine.test.MiniCypherAst.Expression
import org.drivine.test.MiniCypherAst.Pattern
import org.drivine.test.MiniCypherAst.SetItem
import org.drivine.test.MiniCypherAst.Statement
import org.drivine.test.MiniCypherGraph.Edge
import org.drivine.test.MiniCypherGraph.Element
import org.drivine.test.MiniCypherGraph.Node
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** What the variables of a statement are bound to at one point of it: nodes, relationships and plain values, by name. */
private typealias Row = Map<String, Any?>

/**
 * Works a parsed [Statement] out over a [graph] in memory. A statement opens with one row that
 * binds nothing, and each clause in turn makes the next rows of the ones it is given: a `MATCH`
 * multiplies them, a `WHERE` drops some, a `CREATE` binds what it made in each. What the last
 * `RETURN` gave is the statement's result.
 *
 * It reads no text. Every clause and expression there is has its branch in [apply] and [value],
 * and the compiler holds both to that. [text] is the statement, for the few refusals that depend
 * on what the graph holds and so cannot be the parser's.
 *
 * Values are Kotlin's own: a `Long` or a `Double` for a number, a `String`, a `Boolean`, a `List`,
 * a `Map`, a [Node] or an [Edge], and null. A comparison knows true and false alone: one with a
 * null is false, but for `=`, which holds for two nulls.
 */
internal class MiniCypherEvaluator(private val graph: MiniCypherGraph, private val parameters: Map<String, Any?>, private val text: String) {

    /** A node of a pattern with its properties worked out. */
    private class NodeShape(val variable: String?, val labels: List<String>, val properties: Map<String, Any?>)

    private class EdgeShape(val variable: String?, val type: String?, val properties: Map<String, Any?>, val direction: Direction)

    /**
     * A pattern with the values of its properties worked out, which is done once for a clause and
     * not for each row: no variable is named in them, and a `randomUUID()` there is one value for
     * every row.
     */
    private class Shape(val nodes: List<NodeShape>, val edges: List<EdgeShape>)

    private var returned = emptyList<Any?>()

    /** The rows [statement] returns; empty for one that returns nothing. The graph is left as the statement changed it. */
    fun run(statement: Statement): List<Any?> {
        statement.clauses.fold(listOf<Row>(emptyMap())) { rows, clause -> apply(clause, rows) }
        return returned
    }

    /** The rows [clause] makes of [rows]. */
    private fun apply(clause: Clause, rows: List<Row>): List<Row> = when (clause) {
        is Clause.Match -> {
            val shapes = clause.patterns.map(::shape)
            val matched = rows.flatMap { row -> shapes.fold(listOf(row)) { found, shape -> found.flatMap { match(shape, it) } } }
            if (clause.where == null) matched else matched.filter { value(clause.where, it) == true }
        }
        is Clause.Create -> {
            val shapes = clause.patterns.map(::shape)
            rows.map { row -> shapes.fold(row) { bound, shape -> create(shape, bound) } }
        }
        is Clause.Merge -> {
            // What the pattern describes is found, and made where it is not there.
            val shape = shape(clause.pattern)
            rows.flatMap { row -> match(shape, row).ifEmpty { listOf(create(shape, row)) } }
        }
        is Clause.Set -> rows.onEach { row -> clause.items.forEach { set(it, row) } }
        is Clause.Remove -> rows.onEach { row -> (row.getValue(clause.variable) as? Element)?.set(clause.key, null) }
        is Clause.DetachDelete -> rows.onEach { row ->
            val node = row.getValue(clause.variable) as? Node
                ?: throw IllegalArgumentException("DETACH DELETE takes a node, and ${clause.variable} is not bound to one: $text")
            graph.delete(node)
        }
        is Clause.Unwind -> rows.flatMap { row -> (value(clause.list, row) as? Collection<*>).orEmpty().map { row + (clause.variable to it) } }
        is Clause.With -> {
            val kept = rows.map { row -> row.filterKeys { it in clause.variables } }
            if (clause.where == null) kept else kept.filter { value(clause.where, it) == true }
        }
        is Clause.Return -> rows.also { returned = returned(clause, rows) }
        is Clause.ReturnCount -> rows.also { returned = listOf(rows.count { value(clause.counted, it) != null }.toLong()) }
    }

    private fun set(item: SetItem, row: Row) {
        when (item) {
            is SetItem.Property -> value(item.value, row).let { given -> (row.getValue(item.variable) as? Element)?.set(item.key, given) }
            is SetItem.Labels -> (row.getValue(item.variable) as? Node)?.let { node -> item.labels.forEach(node::label) }
        }
    }

    private fun returned(clause: Clause.Return, rows: List<Row>): List<Any?> {
        val values = rows.map { value(clause.value, it) }
        val order = clause.order ?: return values
        val keys = rows.zip(values).map { (row, given) -> value(order.key, if (clause.alias == null) row else row + (clause.alias to given)) }
        val ordered = values.zip(keys).sortedWith { a, b ->
            // An absent key sorts last, as the graph engines sort a null.
            if (a.second == null || b.second == null) compareValues(a.second == null, b.second == null) else order(a.second!!, b.second!!)
        }.map { it.first }
        return if (order.descending) ordered.reversed() else ordered
    }

    // ----- Patterns -----

    private fun shape(pattern: Pattern): Shape {
        fun worked(properties: Map<String, Expression>) = properties.mapValues { value(it.value, emptyMap()) }
        return Shape(
            pattern.nodes.map { NodeShape(it.variable, it.labels, worked(it.properties)) },
            pattern.edges.map { EdgeShape(it.variable, it.type, worked(it.properties), it.direction) },
        )
    }

    /** [row], once for each way [shape] is found in the graph with what the row has bound already, with what the shape names bound. */
    private fun match(shape: Shape, row: Row): List<Row> {
        fun fits(node: Node, wanted: NodeShape, bound: Row): Boolean =
            (wanted.variable?.let(bound::get)?.let { it === node } ?: true) &&
                node.labels.containsAll(wanted.labels) && wanted.properties.all { (key, value) -> same(node[key], value) }

        var partial = graph.nodes.filter { fits(it, shape.nodes[0], row) }.map { node -> bind(row, shape.nodes[0].variable, node) to node }
        shape.edges.forEachIndexed { i, wanted ->
            val next = shape.nodes[i + 1]
            val outgoing = wanted.direction != Direction.INCOMING
            val incoming = wanted.direction != Direction.OUTGOING
            partial = partial.flatMap { (bound, from) ->
                graph.edges.filter { edge ->
                    (wanted.type == null || edge.type == wanted.type) && wanted.properties.all { (key, value) -> same(edge[key], value) }
                }.flatMap { edge ->
                    // An undirected pattern takes a relationship from either end.
                    listOfNotNull(edge.to.takeIf { outgoing && edge.from === from }, edge.from.takeIf { incoming && edge.to === from })
                        .filter { fits(it, next, bound) }
                        .map { far -> bind(bind(bound, wanted.variable, edge), next.variable, far) to far }
                }
            }
        }
        return partial.map { it.first }
    }

    private fun bind(row: Row, variable: String?, value: Any): Row = if (variable == null) row else row + (variable to value)

    /**
     * Makes what [shape] describes, and gives [row] with what it names bound. A node the row has
     * bound is joined as it is. What a `CREATE` cannot make the parser has refused; a `MERGE`
     * comes here only for a row its pattern is not found for, and is refused here.
     */
    private fun create(shape: Shape, row: Row): Row {
        var bound = row
        val made = shape.nodes.map { wanted ->
            val already = wanted.variable?.let(bound::get) as? Node
            if (already != null) {
                // A node bound before is joined, and not described again, as Cypher has it.
                require(wanted.labels.isEmpty() && wanted.properties.isEmpty()) {
                    "The variable ${wanted.variable} is already bound, and cannot be given labels or properties again: $text"
                }
                already
            } else {
                graph.node(wanted.labels, wanted.properties).also { bound = bind(bound, wanted.variable, it) }
            }
        }
        shape.edges.forEachIndexed { i, wanted ->
            require(wanted.direction != Direction.EITHER) { "A relationship that is created needs a direction: $text" }
            val type = requireNotNull(wanted.type) { "A relationship that is created needs a type: $text" }
            val (from, to) = if (wanted.direction == Direction.OUTGOING) made[i] to made[i + 1] else made[i + 1] to made[i]
            bound = bind(bound, wanted.variable, graph.relate(from, to, type, wanted.properties))
        }
        return bound
    }

    // ----- Expressions -----

    /** What [expression] gives for [row]. */
    private fun value(expression: Expression, row: Row): Any? = when (expression) {
        is Expression.Literal -> expression.value
        is Expression.Parameter -> parameters[expression.name]
        is Expression.Variable -> row.getValue(expression.name)
        is Expression.Property -> read(row.getValue(expression.variable), expression.key)
        is Expression.Lookup -> value(expression.key, row).let { key -> read(row.getValue(expression.variable), key.toString()) }
        is Expression.ListOf -> expression.items.map { value(it, row) }
        is Expression.MapOf -> expression.entries.mapValues { value(it.value, row) }
        is Expression.Call -> expression.function(expression.arguments.map { value(it, row) })
        is Expression.Plus -> plus(value(expression.left, row), value(expression.right, row))
        is Expression.Compare -> compare(expression.comparison, value(expression.left, row), value(expression.right, row))
        is Expression.IsNull -> (value(expression.operand, row) == null) != expression.negated
        is Expression.StartsWith -> {
            val whole = value(expression.value, row)
            val prefix = value(expression.prefix, row)
            whole is String && prefix is String && whole.startsWith(prefix)
        }
        is Expression.In -> {
            val sought = value(expression.value, row)
            (value(expression.list, row) as? Collection<*>)?.any { same(sought, it) } ?: false
        }
        is Expression.And -> {
            val left = value(expression.left, row)
            val right = value(expression.right, row)
            left == true && right == true
        }
        is Expression.Case -> {
            val branch = expression.branches.firstOrNull { value(it.condition, row) == true }
            when {
                branch != null -> value(branch.value, row)
                expression.otherwise != null -> value(expression.otherwise, row)
                else -> null
            }
        }
    }

    /** The property [key] of a node, a relationship or a map; null for a null, as Cypher reads it. */
    private fun read(element: Any?, key: String): Any? = when (element) {
        is Element -> element[key]
        is Map<*, *> -> element[key]
        null -> null
        else -> throw IllegalArgumentException("Expected a node, a relationship or a map to read $key of, and found $element: $text")
    }

    /** A sum of two numbers, whole when both are; the two as text joined when either is no number; null when either is null. */
    private fun plus(left: Any?, right: Any?): Any? = when {
        left == null || right == null -> null
        left is Number && right is Number -> if (left.isIntegral() && right.isIntegral()) left.toLong() + right.toLong() else left.toDouble() + right.toDouble()
        else -> left.toString() + right.toString()
    }

    /** Whether a number holds a whole number: one read back from the store's JSON is an `Integer`, not a `Long`. */
    private fun Number.isIntegral(): Boolean = this is Long || this is Int || this is Short || this is Byte

    private fun compare(comparison: Comparison, left: Any?, right: Any?): Boolean = when (comparison) {
        Comparison.EQUAL -> same(left, right)
        Comparison.NOT_EQUAL -> left != null && right != null && !same(left, right)
        Comparison.AT_LEAST -> ordered(left, right) { it >= 0 }
        Comparison.AT_MOST -> ordered(left, right) { it <= 0 }
        Comparison.GREATER -> ordered(left, right) { it > 0 }
        Comparison.LESS -> ordered(left, right) { it < 0 }
    }

    /** Whether [holds] of how the two order; never of a null, which orders with nothing. */
    private fun ordered(left: Any?, right: Any?, holds: (Int) -> Boolean): Boolean = left != null && right != null && holds(order(left, right))

    /** Whether a stored value is the one a pattern names: a number is equal whatever its width. */
    private fun same(stored: Any?, wanted: Any?): Boolean =
        if (stored is Number && wanted is Number) stored.toDouble() == wanted.toDouble() else stored == wanted

    /** How two values order: numbers by size, moments by time whether held as text or not, anything else as text. */
    private fun order(a: Any, b: Any): Int {
        if (a is Number && b is Number) return a.toDouble().compareTo(b.toDouble())
        val (x, y) = moment(a) to moment(b)
        return if (x != null && y != null) x.compareTo(y) else a.toString().compareTo(b.toString())
    }

    private fun moment(value: Any): Instant? = when (value) {
        is Instant -> value
        is ZonedDateTime -> value.toInstant()
        is OffsetDateTime -> value.toInstant()
        is String -> runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        else -> null
    }
}
