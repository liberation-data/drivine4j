package org.drivine.oracle

import org.drivine.manager.PersistenceManager
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import java.util.UUID

/**
 * Runs the Cypher the cross-engine tests use for their fixtures against a graph kept by Oracle: the
 * `CREATE` a test seeds with, the statement another writer changes a node with, and the queries a
 * test reads back what was stored. It is a test's tool and reads only what those statements use:
 *
 * ```
 * CREATE (a:Human {id: 'ada'})-[:KNOWS {since: 2020}]->(:Human {id: 'bob'})
 * MATCH (c:Claim {id: 'c1'}), (h:Human) CREATE (c)-[:MENTIONS]->(h) SET c.text = 'x'
 * MATCH (c:Claim {id: 'c1'}) DETACH DELETE c
 * MATCH (a)-[r]->(b) WHERE type(r) = $type RETURN toString(a.id) + '->' + b.id
 * ```
 *
 * A statement is worked out over the whole graph read into memory, and what it changed is written
 * back. A test's graph is a handful of nodes.
 */
internal class MiniCypher(private val pm: PersistenceManager, private val text: String, private val parameters: Map<String, Any?>) {

    private class Node(var nid: Long?, val labels: MutableList<String>, val properties: MutableMap<String, Any?>, var stamp: String?) {
        var changed = false
    }

    private class Edge(var eid: Long?, val from: Node, val to: Node, val type: String, val properties: MutableMap<String, Any?>) {
        var changed = false
    }

    /** A node of a pattern: the variable it binds, and what a node must have to match. */
    private class NodePattern(val variable: String?, val labels: List<String>, val properties: Map<String, Any?>)

    private class EdgePattern(val variable: String?, val type: String?, val properties: Map<String, Any?>, val outgoing: Boolean, val incoming: Boolean)

    private class Pattern(val nodes: List<NodePattern>, val edges: List<EdgePattern>)

    private val graph = requireNotNull(pm.graphName)
    private val nodes = mutableListOf<Node>()
    private val edges = mutableListOf<Edge>()
    private val deleted = mutableListOf<Node>()
    private var at = 0

    /** The rows the statement returns; empty for one that returns nothing. */
    fun run(): List<Any?> {
        load()
        var rows = listOf<Map<String, Any?>>(emptyMap())
        var returned: List<Any?>? = null
        skipSpace()
        while (at < text.length) {
            when {
                word("MATCH") -> {
                    val patterns = patterns()
                    val where = if (word("WHERE")) at.also { condition(emptyMap(), evaluate = false) } else null
                    rows = rows.flatMap { row -> patterns.fold(listOf(row)) { matched, pattern -> matched.flatMap { match(pattern, it) } } }
                    if (where != null) {
                        val end = at
                        rows = rows.filter { row -> at = where; (condition(row) == true).also { at = end } }
                    }
                }
                word("CREATE") -> patterns().let { patterns -> rows = rows.map { row -> patterns.fold(row) { bound, pattern -> create(pattern, bound) } } }
                word("SET") -> {
                    val start = at
                    rows.forEach { row -> at = start; do assign(row) while (take(',')) }
                    if (rows.isEmpty()) do assign(emptyMap(), evaluate = false) while (take(','))
                }
                word("REMOVE") -> {
                    val variable = name()!!
                    expect('.')
                    val key = name()!!
                    rows.forEach { row -> write(row.getValue(variable), key, null) }
                }
                word("DETACH") -> {
                    check(word("DELETE"))
                    val variable = name()!!
                    rows.forEach { row -> delete(row.getValue(variable) as Node) }
                }
                word("UNWIND") -> {
                    val lists = each(rows) { row, evaluate -> expression(row, evaluate) }
                    check(word("AS"))
                    val variable = name()!!
                    rows = rows.zip(lists).flatMap { (row, list) -> (list as? Collection<*>).orEmpty().map { row + (variable to it) } }
                }
                word("RETURN") -> {
                    skipSpace()
                    if (text.startsWith("count(", at)) {
                        at += "count(".length
                        returned = listOf(each(rows) { row, evaluate -> condition(row, evaluate) }.count { it != null }.toLong())
                        expect(')')
                        if (word("AS")) name()
                    } else {
                        val values = each(rows) { row, evaluate -> condition(row, evaluate) }
                        if (word("AS")) name()
                        returned = if (word("ORDER")) {
                            check(word("BY"))
                            val keys = each(rows) { row, evaluate -> expression(row, evaluate) }
                            val descending = word("DESC")
                            word("ASC")
                            val ordered = values.zip(keys).sortedWith { a, b ->
                                // An absent key sorts last, as the graph engines sort a null.
                                if (a.second == null || b.second == null) compareValues(a.second == null, b.second == null) else order(a.second!!, b.second!!)
                            }.map { it.first }
                            if (descending) ordered.reversed() else ordered
                        } else {
                            values
                        }
                    }
                }
                else -> fail("a clause")
            }
            take(';')
            skipSpace()
        }
        store()
        return returned.orEmpty()
    }

    /** What the expression at this point gives for each of [rows]; it is read past once when there are none. */
    private fun each(rows: List<Map<String, Any?>>, read: (Map<String, Any?>, Boolean) -> Any?): List<Any?> {
        val start = at
        if (rows.isEmpty()) read(emptyMap(), false)
        return rows.map { row -> at = start; read(row, true) }
    }

    // ----- Matching and changing the graph in memory -----

    private fun match(pattern: Pattern, row: Map<String, Any?>): List<Map<String, Any?>> {
        fun fits(node: Node, wanted: NodePattern, bound: Map<String, Any?>): Boolean =
            node !in deleted && (wanted.variable?.let(bound::get)?.let { it === node } ?: true) &&
                node.labels.containsAll(wanted.labels) && wanted.properties.all { (key, value) -> same(read(node, key), value) }

        var partial = nodes.filter { fits(it, pattern.nodes[0], row) }.map { node -> bind(row, pattern.nodes[0].variable, node) to node }
        pattern.edges.forEachIndexed { i, wanted ->
            val next = pattern.nodes[i + 1]
            partial = partial.flatMap { (bound, from) ->
                edges.filter { edge ->
                    (wanted.type == null || edge.type == wanted.type) && wanted.properties.all { (key, value) -> same(edge.properties[key], value) } &&
                        ((wanted.outgoing && edge.from === from) || (wanted.incoming && edge.to === from))
                }.flatMap { edge ->
                    // An undirected pattern takes a relationship from either end.
                    listOfNotNull(edge.to.takeIf { wanted.outgoing && edge.from === from }, edge.from.takeIf { wanted.incoming && edge.to === from })
                        .filter { fits(it, next, bound) }
                        .map { far -> bind(bind(bound, wanted.variable, edge), next.variable, far) to far }
                }
            }
        }
        return partial.map { it.first }
    }

    private fun bind(row: Map<String, Any?>, variable: String?, value: Any): Map<String, Any?> = if (variable == null) row else row + (variable to value)

    private fun create(pattern: Pattern, row: Map<String, Any?>): Map<String, Any?> {
        var bound = row
        val made = pattern.nodes.map { wanted ->
            (wanted.variable?.let(bound::get) as? Node) ?: Node(null, wanted.labels.toMutableList(), wanted.properties.toMutableMap(), null).also { node ->
                nodes += node
                bound = bind(bound, wanted.variable, node)
            }
        }
        pattern.edges.forEachIndexed { i, wanted ->
            val (from, to) = if (wanted.outgoing) made[i] to made[i + 1] else made[i + 1] to made[i]
            edges += Edge(null, from, to, requireNotNull(wanted.type), wanted.properties.toMutableMap()).also { bound = bind(bound, wanted.variable, it) }
        }
        return bound
    }

    private fun delete(node: Node) {
        deleted += node
        edges.removeAll { it.from === node || it.to === node }
    }

    private fun read(element: Any?, key: String): Any? = when (element) {
        is Node -> if (key == Stamps.PROPERTY) element.stamp else element.properties[key]
        is Edge -> element.properties[key]
        else -> null
    }

    private fun write(element: Any?, key: String, value: Any?) {
        when (element) {
            is Node -> {
                if (key == Stamps.PROPERTY) element.stamp = value as String? else if (value == null) element.properties.remove(key) else element.properties[key] = value
                element.changed = true
            }
            is Edge -> {
                if (value == null) element.properties.remove(key) else element.properties[key] = value
                element.changed = true
            }
        }
    }

    /** Whether a stored value is the one a pattern names: a number is equal whatever its width. */
    private fun same(stored: Any?, wanted: Any?): Boolean =
        if (stored is Number && wanted is Number) stored.toDouble() == wanted.toDouble() else stored == wanted

    // ----- Reading the statement -----

    private fun patterns(): List<Pattern> = buildList { do add(pattern()) while (take(',')) }

    private fun pattern(): Pattern {
        val nodes = mutableListOf(nodePattern())
        val edges = mutableListOf<EdgePattern>()
        while (true) {
            skipSpace()
            val incoming = text.startsWith("<-", at)
            if (!incoming && !text.startsWith("-", at)) break
            at += if (incoming) 2 else 1
            var variable: String? = null
            var type: String? = null
            var properties = emptyMap<String, Any?>()
            if (take('[')) {
                variable = name()
                if (take(':')) type = name()
                if (peek() == '{') properties = map()
                expect(']')
            }
            expect('-')
            val outgoing = take('>')
            // Neither arrow: the relationship is taken either way.
            edges += EdgePattern(variable, type, properties, outgoing = outgoing || !incoming, incoming = incoming || !outgoing)
            nodes += nodePattern()
        }
        return Pattern(nodes, edges)
    }

    private fun nodePattern(): NodePattern {
        expect('(')
        val variable = name()
        val labels = mutableListOf<String>()
        while (take(':')) labels += name() ?: fail("a label")
        val properties = if (peek() == '{') map() else emptyMap()
        expect(')')
        return NodePattern(variable, labels, properties)
    }

    private fun map(): Map<String, Any?> {
        expect('{')
        val entries = linkedMapOf<String, Any?>()
        if (!take('}')) {
            do {
                val key = name() ?: fail("a property name")
                expect(':')
                entries[key] = expression(emptyMap())
            } while (take(','))
            expect('}')
        }
        return entries
    }

    private fun assign(row: Map<String, Any?>, evaluate: Boolean = true) {
        val variable = name() ?: fail("a variable")
        if (peek() == ':') {
            // `SET n:Label`: the labels are added to the node.
            while (take(':')) {
                val label = name() ?: fail("a label")
                (row[variable] as? Node)?.takeIf { evaluate && label !in it.labels }?.let { it.labels += label; it.changed = true }
            }
            return
        }
        expect('.')
        val key = name() ?: fail("a property name")
        expect('=')
        val value = expression(row, evaluate)
        if (evaluate) write(row[variable], key, value)
    }

    /** Comparisons joined by `AND`. */
    private fun condition(row: Map<String, Any?>, evaluate: Boolean = true): Any? {
        var value = comparison(row, evaluate)
        while (word("AND")) {
            val right = comparison(row, evaluate)
            value = value == true && right == true
        }
        return value
    }

    private fun comparison(row: Map<String, Any?>, evaluate: Boolean): Any? {
        val left = expression(row, evaluate)
        if (word("IS")) {
            val not = word("NOT")
            check(word("NULL"))
            return (left == null) != not
        }
        if (word("IN")) {
            val list = expression(row, evaluate)
            return (list as? Collection<*>)?.any { same(left, it) } ?: false
        }
        skipSpace()
        val operator = listOf("<>", ">=", "<=", "=", ">", "<").firstOrNull { text.startsWith(it, at) } ?: return left
        at += operator.length
        val right = expression(row, evaluate)
        return when {
            !evaluate -> null
            operator == "=" -> same(left, right)
            left == null || right == null -> false
            operator == "<>" -> !same(left, right)
            operator == ">=" -> order(left, right) >= 0
            operator == "<=" -> order(left, right) <= 0
            operator == ">" -> order(left, right) > 0
            else -> order(left, right) < 0
        }
    }

    /** How two values order: numbers by size, moments by time whether held as text or not, anything else as text. */
    private fun order(a: Any, b: Any): Int {
        if (a is Number && b is Number) return a.toDouble().compareTo(b.toDouble())
        val (x, y) = moment(a) to moment(b)
        return if (x != null && y != null) x.compareTo(y) else a.toString().compareTo(b.toString())
    }

    private fun moment(value: Any): java.time.Instant? = when (value) {
        is java.time.Instant -> value
        is java.time.ZonedDateTime -> value.toInstant()
        is java.time.OffsetDateTime -> value.toInstant()
        is String -> runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
        else -> null
    }

    /** Terms joined by `+`. Without [evaluate] the expression is read past, and gives nothing. */
    private fun expression(row: Map<String, Any?>, evaluate: Boolean = true): Any? {
        var value = term(row, evaluate)
        while (take('+')) {
            val right = term(row, evaluate)
            value = when {
                value == null || right == null -> null
                value is Number && right is Number -> if (value is Long && right is Long) value + right else value.toDouble() + right.toDouble()
                else -> value.toString() + right.toString()
            }
        }
        return value
    }

    private fun term(row: Map<String, Any?>, evaluate: Boolean): Any? {
        skipSpace()
        val c = text.getOrNull(at) ?: fail("a value")
        return when {
            c == '\'' || c == '"' -> string(c)
            c == '$' -> { at++; parameters[name()] }
            c == '[' -> {
                at++
                val items = mutableListOf<Any?>()
                if (!take(']')) {
                    do items += expression(row, evaluate) while (take(','))
                    expect(']')
                }
                items
            }
            c == '{' -> {
                at++
                val entries = linkedMapOf<String, Any?>()
                if (!take('}')) {
                    do {
                        val key = name() ?: fail("a key")
                        expect(':')
                        entries[key] = condition(row, evaluate)
                    } while (take(','))
                    expect('}')
                }
                entries
            }
            c == '-' || c.isDigit() -> {
                val start = at++
                while (at < text.length && (text[at].isDigit() || text[at] in ".eE")) at++
                text.substring(start, at).let { it.toLongOrNull() ?: it.toDouble() }
            }
            else -> {
                val word = name() ?: fail("a value")
                when {
                    take('(') -> {
                        val arguments = mutableListOf<Any?>()
                        if (!take(')')) {
                            do arguments += expression(row, evaluate) while (take(','))
                            expect(')')
                        }
                        if (evaluate) call(word, arguments) else null
                    }
                    take('.') -> name()!!.let { key -> if (evaluate) read(row[word], key) else null }
                    take('[') -> expression(row, evaluate).also { expect(']') }.let { key -> if (evaluate) read(row[word], key.toString()) else null }
                    word == "true" -> true
                    word == "false" -> false
                    word == "null" -> null
                    else -> row[word]
                }
            }
        }
    }

    private fun call(function: String, arguments: List<Any?>): Any? = when (function) {
        "toString" -> arguments[0]?.toString()
        "coalesce" -> arguments.firstOrNull { it != null }
        "type" -> (arguments[0] as? Edge)?.type
        "labels" -> (arguments[0] as? Node)?.labels
        // The stamp is a property on the graph engines, and a node that has one lists it.
        "keys" -> when (val element = arguments[0]) {
            is Node -> element.properties.keys.toList() + listOfNotNull(Stamps.PROPERTY.takeIf { element.stamp != null })
            is Edge -> element.properties.keys.toList()
            else -> null
        }
        "left" -> (arguments[0] as String?)?.take((arguments[1] as Number).toInt())
        "right" -> (arguments[0] as String?)?.takeLast((arguments[1] as Number).toInt())
        "replace" -> (arguments[0] as String?)?.replace(arguments[1] as String, arguments[2] as String)
        "randomUUID" -> UUID.randomUUID().toString()
        // A function that wraps one value, as an engine's vector or temporal constructor does: the value itself.
        else -> arguments.single()
    }

    private fun string(quote: Char): String {
        at++
        val out = StringBuilder()
        while (text[at] != quote) {
            if (text[at] == '\\') at++
            out.append(text[at++])
        }
        at++
        return out.toString()
    }

    /** An identifier, plain or in backticks; null when there is none here. */
    private fun name(): String? {
        skipSpace()
        if (text.getOrNull(at) == '`') {
            val end = text.indexOf('`', at + 1)
            return text.substring(at + 1, end).also { at = end + 1 }
        }
        val start = at
        while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
        return text.substring(start, at).ifEmpty { null }
    }

    private fun word(keyword: String): Boolean {
        skipSpace()
        val end = at + keyword.length
        if (!text.startsWith(keyword, at) || text.getOrNull(end)?.let { it.isLetterOrDigit() || it == '_' } == true) return false
        at = end
        return true
    }

    private fun skipSpace() {
        while (at < text.length && text[at].isWhitespace()) at++
    }

    private fun peek(): Char? {
        skipSpace()
        return text.getOrNull(at)
    }

    private fun take(c: Char): Boolean = (peek() == c).also { if (it) at++ }

    private fun expect(c: Char) {
        if (!take(c)) fail("'$c'")
    }

    private fun fail(expected: String): Nothing =
        throw IllegalArgumentException("Expected $expected at $at: ${text.substring(at.coerceAtMost(text.length)).take(40)}… in: $text")

    // ----- The graph, read from the store and written back -----

    private fun sql(statement: String, vararg bound: Pair<String, Any?>) = QuerySpecification.withStatement(statement.trimIndent()).bind(mapOf(*bound))

    @Suppress("UNCHECKED_CAST")
    private fun load() {
        val byId = mutableMapOf<Long, Node>()
        pm.query(sql("SELECT JSON_OBJECT('nid' VALUE nid, 'labels' VALUE labels, 'props' VALUE props, 'stamp' VALUE stamp RETURNING JSON) FROM ${graph}_node ORDER BY nid"))
            .forEach { row ->
                row as Map<String, Any?>
                val node = Node((row["nid"] as Number).toLong(), (row["labels"] as List<String>).toMutableList(), (row["props"] as Map<String, Any?>).toMutableMap(), row["stamp"] as String?)
                nodes += node
                byId[node.nid!!] = node
            }
        pm.query(sql("SELECT JSON_OBJECT('eid' VALUE eid, 'src' VALUE src, 'dst' VALUE dst, 'type' VALUE type, 'props' VALUE props RETURNING JSON) FROM ${graph}_edge ORDER BY eid"))
            .forEach { row ->
                row as Map<String, Any?>
                edges += Edge(
                    (row["eid"] as Number).toLong(), byId.getValue((row["src"] as Number).toLong()), byId.getValue((row["dst"] as Number).toLong()),
                    row["type"] as String, (row["props"] as Map<String, Any?>).toMutableMap(),
                )
            }
    }

    private fun store() {
        deleted.forEach { node -> node.nid?.let { pm.execute(sql("DELETE FROM ${graph}_node WHERE nid = \$nid", "nid" to it)) } }
        nodes.filter { it !in deleted }.forEach { node ->
            if (node.nid == null) {
                node.nid = pm.query(
                    sql(
                        """
                        DECLARE
                            v_nid NUMBER;
                            v_rows SYS_REFCURSOR;
                        BEGIN
                            INSERT INTO ${graph}_node (labels, props, stamp) VALUES (${'$'}labels, ${'$'}props, ${'$'}stamp) RETURNING nid INTO v_nid;
                            OPEN v_rows FOR SELECT v_nid FROM dual;
                            DBMS_SQL.RETURN_RESULT(v_rows);
                        END;
                        """,
                        "labels" to node.labels.sorted(), "props" to node.properties, "stamp" to node.stamp,
                    )
                ).single().let { (it as Number).toLong() }
            } else if (node.changed) {
                pm.execute(
                    sql(
                        "UPDATE ${graph}_node SET labels = \$labels, props = \$props, stamp = \$stamp WHERE nid = \$nid",
                        "labels" to node.labels.sorted(), "props" to node.properties, "stamp" to node.stamp, "nid" to node.nid,
                    )
                )
            }
        }
        edges.forEach { edge ->
            if (edge.eid == null) {
                pm.execute(
                    sql(
                        "INSERT INTO ${graph}_edge (src, dst, type, props) VALUES (\$src, \$dst, \$type, \$props)",
                        "src" to edge.from.nid, "dst" to edge.to.nid, "type" to edge.type, "props" to edge.properties,
                    )
                )
            } else if (edge.changed) {
                pm.execute(sql("UPDATE ${graph}_edge SET props = \$props WHERE eid = \$eid", "props" to edge.properties, "eid" to edge.eid))
            }
        }
    }

    companion object {
        /** Whether [statement] is one of a test's Cypher fixtures, and not a statement in the store's own language. */
        fun reads(statement: String): Boolean = FIXTURE.containsMatchIn(statement)

        /** A statement that opens with a pattern: `CREATE INDEX` is the store's own. */
        private val FIXTURE = Regex("^\\s*(MATCH|CREATE)\\s*\\(")
    }
}
