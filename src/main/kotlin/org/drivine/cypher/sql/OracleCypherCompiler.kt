package org.drivine.cypher.sql

import java.math.RoundingMode
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleSql
import org.drivine.cypher.ast.RelationshipPattern
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Compiles a Cypher statement to SQL over a graph's tables on Oracle: see [SqlCypherCompiler].
 *
 * A value is Oracle's `JSON`, which compares, groups and orders as JSON and keeps a map's keys in the
 * order written. Oracle keeps a number as a number, so a stored `2.0` reads as `2`; a floating-point
 * number the statement writes, or a parameter's that is a whole number, is a `BINARY_DOUBLE`, and stays one. A text Oracle reads out of JSON is a
 * `VARCHAR2` of at most 4,000 bytes, and one longer is read as a CLOB where it is measured, cut, changed or searched (see [texts]), but for
 * `reverse()`, which still gives null. An empty text is null, as Oracle's empty text is. `reduce` is a function the query
 * declares, and does not run in a statement that writes, in the step of a `reduce`, or where its step reads a path.
 *
 * A list of ids is text, each id between commas (`,1,2,`): Oracle has no array, and a recursive query
 * takes no `JSON` that changes from row to row. A recursive query reads no row outside it, so a walk
 * starts from every node that meets what the node walked from meets of its own, and carries the node
 * it started from.
 *
 * @param nullsEqualStored whether two values that may hold a null are compared by the function the store keeps
 * beside the graph's tables, [nullsEqualName]: see [nullsEqual].
 */
class OracleCypherCompiler(graph: String, private val nullsEqualStored: Boolean = true) : SqlCypherCompiler(graph) {

    /** The function the store keeps that compares two values that may hold a null: see [nullsEqual]. */
    private val storedNullsEqual = nullsEqualName(graph)

    /**
     * Whether the statement compiled calls [nullsEqualName]'s function, which a store made by an earlier version, or by a
     * user who may not make a function, does not have: compiled again with `nullsEqualStored = false`, it calls none.
     */
    var callsStoredNullsEqual = false
        private set

    override fun literalJson(value: Any?) =
        if (value is Double) "JSON_SCALAR(TO_BINARY_DOUBLE(${literal(value.toString())}))"
        else "JSON(${literal(Neo4jObjectMapper.instance.writeValueAsString(value))})"

    /**
     * A text that holds an extended JSON double is read as extended JSON, and only one: else a map of the parameter's whose
     * key is `${'$'}numberDouble` would be read as a double.
     */
    override fun parameterJson(marker: String, text: String?) = if (text != null && text in extended) "JSON($marker EXTENDED)" else "JSON($marker)"

    /**
     * The parameters' texts that hold a float as Oracle's extended JSON writes one: see [parameterText]. Kept by identity, as
     * a map parameter `{"${'$'}numberDouble": "2.0"}` is written as the same text as the float 2.0.
     */
    private val extended: MutableSet<String> = Collections.newSetFromMap(IdentityHashMap())

    /** The names of a procedure's parameters that hold a float as Oracle's extended JSON writes one: see [propertyOf]. */
    private val extendedParameters = mutableSetOf<String>()

    /**
     * A float that is a whole number, `2.0`, written as Oracle's extended JSON writes a double, `{"${'$'}numberDouble": "2.0"}`, which
     * `JSON(… EXTENDED)` reads as one: Oracle reads JSON's `2.0` as the number 2, a whole number, as it reads `2`. Not in a
     * value that holds a map with a key as extended JSON's are, `{"${'$'}numberLong": "5"}`, which `JSON(… EXTENDED)` would read as
     * the value it writes: such a value is written as JSON is, its floats that are whole numbers read as whole numbers. A
     * statement that writes binds all its parameters as one text, and reads as extended JSON only those of them with such
     * a double in them: see [propertyOf].
     */
    override fun parameterText(value: Any?): String {
        val tree = Neo4jObjectMapper.instance.valueToTree<JsonNode>(value)
        if (keyedAsExtended(tree)) return Neo4jObjectMapper.instance.writeValueAsString(tree)
        var doubled = false
        val written = doubles(tree) { doubled = true }
        // A procedure's parameters are one map: those of its entries with a double in them are read as extended JSON.
        if (doubled && written is ObjectNode) extendedParameters += written.properties().filter { keyedAsExtended(it.value) }.map { it.key }
        return Neo4jObjectMapper.instance.writeValueAsString(written).also { if (doubled) extended += it }
    }

    /** Whether [node] holds a map with a key that starts with `$`, as extended JSON's keys do. */
    private fun keyedAsExtended(node: JsonNode): Boolean =
        (node is ObjectNode && node.fieldNames().asSequence().any { it.startsWith("$") }) || node.any(::keyedAsExtended)

    /**
     * [node] with each float in it that is a whole number written as extended JSON's double, calling [doubled] for each. A
     * `BigDecimal` is no float: it stays the number it is, every digit kept.
     */
    private fun doubles(node: JsonNode, doubled: () -> Unit): JsonNode = when {
        (node.isDouble || node.isFloat) && node.doubleValue().let { it.isFinite() && it == Math.rint(it) } -> {
            doubled()
            JsonNodeFactory.instance.objectNode().put(DOUBLE, node.doubleValue().toString())
        }
        node is ObjectNode -> node.apply { properties().toList().forEach { (key, value) -> set<JsonNode>(key, doubles(value, doubled)) } }
        node is ArrayNode -> node.apply { (0 until size()).forEach { set(it, doubles(get(it), doubled)) } }
        else -> node
    }

    override fun parameterCount(marker: String) = "CAST($marker AS NUMBER)"

    override fun comparable(value: Value.Json) = value.sql

    /** A CLOB that [texts] gives its body is null where it is empty, as a `VARCHAR2` is, and as Oracle's empty text is. */
    override fun scalar(sql: String) = if (long) "JSON_SCALAR(CASE WHEN LENGTH($sql) > 0 THEN $sql END)" else "JSON_SCALAR($sql)"

    /** A floating-point number is a number, as JSON has it. */
    override fun typeOf(json: String) = shared(json) { (x) -> "DECODE(${kind(x)}, 'double', 'number', 'float', 'number', ${kind(x)})" }

    override fun isJsonNull(json: String) = "NVL(${kind(json)}, 'null') = 'null'"

    override fun nullIfJsonNull(json: String) = shared(json) { (x) -> "CASE WHEN ${kind(x)} <> 'null' THEN $x END" }

    /**
     * Where the kind comes, as [kindOrder] ranks them and null for null, then the value as `JSON` orders it, which is Cypher's
     * order within a kind: `JSON` orders a number before a text, a truth value after both, and a map and a list last. A null
     * is ranked null, so the value needs no [nullIfJsonNull], which would read it again: as fast as [sortKey] over 100,000 rows.
     */
    override fun openSortKey(json: String) = shared(json) { (x) ->
        "DECODE(${kind(x)}, 'object', 1, 'array', 2, 'string', 3, 'boolean', 4, 'number', 5, 'double', 5, 'float', 5)"
    }.let { rank -> listOf(rank, json) }

    override fun number(json: String) = shared(json) { (x) -> "CASE WHEN ${typeOf(x)} = 'number' THEN JSON_VALUE($x, '$' RETURNING NUMBER) END" }

    /** A whole number, and no floating-point number, as Cypher has an integer. */
    override fun integral(json: String) = shared(json) { (x) ->
        "(${kind(x)} = 'number' AND JSON_VALUE($x, '$' RETURNING NUMBER) = TRUNC(JSON_VALUE($x, '$' RETURNING NUMBER)))"
    }

    /** A `VARCHAR2`, which holds 4,000 bytes: a longer text is null. See [texts], which reads one as a CLOB. */
    override fun text(json: String) = shared(json) { (x) -> "CASE WHEN ${kind(x)} = 'string' THEN JSON_VALUE($x, '$') END" }

    /** The JSON [json] as a CLOB, where it is a string: a text of any length, slower to read than [text]'s `VARCHAR2`. */
    private fun longText(json: String) = shared(json) { (x) -> "CASE WHEN ${kind(x)} = 'string' THEN JSON_VALUE($x, '$' RETURNING CLOB) END" }

    /**
     * [body] over the texts as [text] reads them, `VARCHAR2`s, and where it gives null, over them as CLOBs (#298). A text
     * past 4,000 bytes is null as a `VARCHAR2`, where Neo4j measures it, cuts it and searches it whole. [body] gives null
     * where a text it is given is null, and where what it gives would not fit in a `VARCHAR2`: see [replaceText] and
     * [split]. A short text is not read as a CLOB, which is slower: `COALESCE` reads its second value only where the first
     * is null. [body] knows which it is given by [long].
     */
    override fun texts(vararg jsons: String, body: (List<String>) -> String) = shared(*jsons) { xs ->
        // Within a body given CLOBs, as CLOBs alone.
        if (long) return@shared body(xs.map { textOf(it, clob = true) })
        val read = body(xs.map { textOf(it, clob = false) })
        long = true
        val whole = try { body(xs.map { textOf(it, clob = true) }) } finally { long = false }
        "COALESCE($read, $whole)"
    }

    /** The JSON [json] as text, where it is a string: a CLOB where [clob], else as [text] reads it; a property by its own path (see [direct]). */
    private fun textOf(json: String, clob: Boolean) = direct(if (clob) longText(json) else text(json), json)

    /** The properties and the path of [json] where it is a property as [propertyOf] reads it, `JSON_QUERY(props, '$.a' RETURNING JSON)`. */
    private fun propertyRead(json: String): Pair<String, String>? =
        PROPERTY_READ.matchEntire(json)?.takeIf { balanced(it.groupValues[1]) }?.let { it.groupValues[1] to it.groupValues[2] }

    /** Whether [sql]'s parentheses outside its literals close each other: so it is the one argument, not two calls. */
    private fun balanced(sql: String): Boolean {
        var depth = 0
        var quoted = false
        for (c in sql) {
            when {
                c == '\'' -> quoted = !quoted
                quoted -> {}
                c == '(' -> depth++
                c == ')' -> if (--depth < 0) return false
            }
        }
        return depth == 0 && !quoted
    }

    /** Whether the texts [texts] gives its body are CLOBs: see [scalar], [startsWith], [endsWith], [split] and [replaceText], which read them otherwise. */
    private var long = false

    override fun boolOf(json: String) = shared(json) { (x) -> "CASE WHEN ${kind(x)} = 'boolean' THEN JSON_VALUE($x, '$' RETURNING BOOLEAN) END" }

    override fun float(sql: String) = "TO_BINARY_DOUBLE($sql)"

    override fun jsonArray(items: List<String>) =
        if (items.isEmpty()) "JSON('[]')" else "JSON_ARRAY(${items.joinToString(", ")} NULL ON NULL RETURNING JSON)"

    override fun jsonObject(entries: List<Pair<String, String>>) =
        if (entries.isEmpty()) "JSON('{}')"
        else "JSON_OBJECT(${entries.joinToString(", ") { (key, value) -> "${literal(key)} VALUE $value" }} NULL ON NULL RETURNING JSON)"

    override fun withEntries(json: String, entries: List<Pair<String, String>>) =
        if (entries.isEmpty()) json else "JSON_TRANSFORM($json, ${entries.joinToString(", ") { (key, value) -> "SET ${OracleSql.path(key)} = $value" }})"

    /**
     * A procedure's parameter is read from the text its parameters are bound as, each where it is read, and one that holds
     * a double as extended JSON (see [parameterText]) from its own text, as extended JSON. Oracle crashes the session
     * (ORA-07445 `ksmem_malloc`, then ORA-00600 `kghfrempty:ds`) where a statement run more than once reads a value past
     * 32 KB from JSON it parsed, a `JSON` variable or `JSON(… EXTENDED)` of all the parameters, and not from the text.
     */
    override fun propertyOf(json: String, key: String) =
        if (json == PARAMS && key in extendedParameters) "JSON(JSON_QUERY($PARAMS, ${OracleSql.path(key)} RETURNING CLOB) EXTENDED)"
        else "JSON_QUERY($json, ${OracleSql.path(key)} RETURNING JSON)"

    /**
     * A property returned or passed on by a `RETURN` or a `WITH`, read through its text: only the key is kept
     * (`JSON_TRANSFORM … KEEP`), written out as extended JSON, then read back from that text as extended JSON, so a float
     * that is a whole number stays a float.
     *
     * **A workaround for an Oracle server bug (#255), to be removed once Oracle fixes it.** Seen on Oracle 23.26.3
     * (gvenzl/oracle-free 23). Where a statement takes a string value past about 32 KB from a node's properties with
     * `JSON_QUERY(props, '$.a' RETURNING JSON)` over two or more rows, as the compiled SQL does and then feeds it on
     * (an outer `SELECT`, an `ORDER BY`, a `JSON_ARRAY`), the long value is written over the server process's heap and
     * the process dies: the client sees ORA-03113 (end-of-file on communication channel), and the alert log shows
     * ORA-00600 `[17114]` and `[kghfrh1]`, and ORA-07445 in `kghalo`, `kghsrch_new` or `kxsFreePages`, faulting at
     * `0x78787878787878`, the bytes of the string. One row doesn't crash it, nor 30,000 characters, nor a long list,
     * nor a map that holds the long string. A plain-SQL repro is in `docs/oracle-long-json-string-crash.md`.
     *
     * It costs about 2.4 to 3.8 times the plain read on short values: 100,000 rows of `RETURN n.name` took 493 ms against
     * 147 ms. Cheaper forms were tried and rejected: each crashed, read a CLOB column back as text (5.0 as 5), or, as a
     * `CASE` on `$.a.type()` or on the value's length that reads the properties twice, made Oracle drop the row's label
     * test and return nodes it had ruled out. Beside a variable-length walk the plain read is kept: Oracle has closed
     * the session over this form in the more involved statements a walk writes. Only an item that is the property itself
     * is read so: a property inside an item (`[n.a]`, `collect(n.a)`) is read as [propertyOf] reads it, and isn't worked
     * around. Read so, a text past 32 KB still can't be sorted by, grouped by or made `DISTINCT`: Oracle refuses it
     * (ORA-40180), as it does any JSON that large, and the session stays open. A write's block is worked around too, where
     * it takes a text from the row it is writing: see [rowValue].
     *
     * To remove it, delete this override, so the item is read as [propertyOf] reads it, `JSON_QUERY … RETURNING JSON`,
     * once an Oracle release no longer crashes on the repro, and `CypherWriteOracleTest`'s "a property past 32 KB read on
     * two nodes" test still passes without it. `OracleItemPropertyTest`, which pins this SQL, goes with it.
     */
    override fun itemProperty(props: String, key: String) =
        "JSON(JSON_QUERY(JSON_SERIALIZE(JSON_TRANSFORM($props, KEEP ${OracleSql.path(key)}) RETURNING CLOB EXTENDED), ${OracleSql.path(key)} RETURNING CLOB) EXTENDED)"

    /**
     * A value that a write takes from the row it is writing, to work out what it writes (`SET n.a = t.a`, `CREATE (:X {a:
     * t.a})`, `MERGE … SET n.a = t.a`, where `t` is an item of an unwound parameter): a text read with one path from the
     * row written out as text, anything else with that path from the row as JSON, as before.
     *
     * **A workaround for the Oracle bug of [itemProperty] (#309), to be removed with it.** The block crashed the session where
     * it took a text past 32 KB from the row, a `JSON` variable, with `JSON_QUERY(JSON_QUERY(cy_row, '$.v1' RETURNING JSON),
     * '$.a' RETURNING JSON)`, for each of two or more rows. Read as [itemProperty] reads a property, through
     * `JSON_TRANSFORM(cy_row, KEEP …)`, it crashed too; written out whole, `JSON_SERIALIZE(cy_row …)`, it didn't. A text is
     * read from text that isn't extended JSON, which has no float to keep; anything else is read as JSON, as before, so a map
     * of the row with a key as extended JSON's are (`{"${'$'}numberLong": "5"}`) stays a map. A list or a map that holds a long
     * text is so read as JSON, which didn't crash: `SET n += t` sets such a map whole. The `CASE` on the value's type, which
     * in a read made Oracle drop a label test (see [itemProperty]), doesn't here: it reads the row variable, one value for the
     * whole statement, after the rows were matched, and a labelled pattern in a subquery of that statement keeps its test
     * (`CypherWriteContract`'s "an unwound item's text … written only to the nodes a labelled pattern matches").
     *
     * To remove it, delete this override, so the value is read as [propertyOf] reads each key, once an Oracle release no
     * longer crashes on the repro in `docs/oracle-long-json-string-crash.md`, and `CypherWriteOracleTest`'s "a text past
     * 32 KB set from an unwound parameter" test still passes without it.
     */
    override fun rowValue(keys: List<String>): String {
        val path = OracleSql.path(keys)
        return "CASE WHEN JSON_VALUE($ROW, ${OracleSql.path(keys, ".type()")}) = 'string' " +
            "THEN JSON(JSON_QUERY(JSON_SERIALIZE($ROW RETURNING CLOB), $path RETURNING CLOB)) ELSE JSON_QUERY($ROW, $path RETURNING JSON) END"
    }

    /**
     * A value in a parameter that is a map, `${'$'}p.text`, read with one path from the text the parameter is bound as, and
     * not from the parameter read as JSON first: Oracle crashed the session (#256) where a statement read a value from a
     * parameter past 32 KB, taken as JSON, for each of two rows. Read as extended JSON where the parameter is (see
     * [parameterText]) and the value holds such a double.
     */
    override val parameterPath = { marker: String, text: String?, keys: List<String>, value: Any? ->
        val readsExtended = if (marker == PARAMS) keys.first() in extendedParameters else text != null && text in extended
        val path = OracleSql.path(keys)
        if (readsExtended && doubled(value)) "JSON(JSON_QUERY($marker, $path RETURNING CLOB) EXTENDED)" else "JSON_QUERY($marker, $path RETURNING JSON)"
    }

    /** Whether [value] holds a float that is a whole number, as extended JSON's double: see [doubles]. */
    private fun doubled(value: Any?): Boolean {
        var doubled = false
        doubles(Neo4jObjectMapper.instance.valueToTree(value)) { doubled = true }
        return doubled
    }

    /** Counted from the end where negative: a JSON path counts from the start. */
    override fun element(json: String, index: String) = shared(json, index) { (list, i) ->
        val at = "JSON_VALUE($i, '$' RETURNING NUMBER)"
        "JSON_QUERY($list, '\$[\$i]' PASSING CASE WHEN $at < 0 THEN JSON_VALUE($list, '\$.size()' RETURNING NUMBER) + $at ELSE $at END AS \"i\" RETURNING JSON)"
    }

    /** A JSON path names a key as it is written, so a key an expression gives is not read: `map[key]` is null, where `list[i]` reads its item. */
    override fun keyed(json: String, key: String): String? = null

    /** A merge of [map] over [props] that changes nothing: each of its entries is there, with an equal value. */
    override fun containsAll(props: String, map: String) = shared(props) { (p) -> "JSON_EQUAL(JSON_MERGEPATCH($p, $map RETURNING JSON), $p)" }

    override fun hasLabel(labels: String, label: String) = "JSON_EXISTS($labels, ${literal("\$[*]?(@ == ${OracleSql.quoted(label)})")})"

    /**
     * The first label as the index over labels answers it, and the rest by the labels' text: when two tests
     * of the labels are answered by that index, Oracle (23.26) finds no node the open transaction has written
     * and not yet committed.
     */
    override fun hasLabels(labels: String, all: List<String>) =
        (listOf(hasLabel(labels, all.first())) + all.drop(1).map { OracleSql.holdsLabel(labels, it) }).joinToString(" AND ")

    /**
     * A list that holds a subquery is read from a lateral view first: a subquery in `JSON_TABLE`'s argument
     * reads no table beside it in the `FROM`.
     */
    override fun elements(json: String, alias: String): String {
        val items = "COLUMNS (item_no FOR ORDINALITY, item JSON PATH '\$')) $alias"
        if (!json.contains("SELECT", ignoreCase = true)) return "JSON_TABLE($json, '\$[*]' $items"
        return "LATERAL (SELECT $json AS list) ${alias}_l CROSS JOIN JSON_TABLE(${alias}_l.list, '\$[*]' $items"
    }

    /**
     * Each item read by its place, from as many rows as the list has items, as [range] makes them: Oracle 23 takes many
     * seconds, or minutes, to parse a `JSON_TABLE` over a list of temporal values worked out with temporal arithmetic, item
     * by item in a comprehension (#331), and neither a query timeout nor a kill ends the parse. `JSON_QUERY` reads the same
     * list in moments, at about ten times the cost of `JSON_TABLE` for a short list, so a list of other values is read by
     * [elements]. The list is worked out once, in a lateral view of its own, and its places counted in another: where they
     * are counted over the list's own view, or the list is written out again where it is read, Oracle reads an `EXISTS` over
     * its items with the wrong row's columns. It reads as `JSON_TABLE`'s `$[*]` does: no rows for null, and one for a value
     * that is no list.
     */
    override fun temporalElements(json: String, alias: String): String {
        val (list, place) = "${alias}_l" to "${alias}_r"
        val size = "JSON_VALUE($list.list, '\$.size()' RETURNING NUMBER)"
        return "LATERAL (SELECT /*+ NO_MERGE */ $json AS list FROM DUAL) $list CROSS JOIN " +
            "LATERAL (SELECT $place.n AS item_no, JSON_QUERY($list.list, '\$[\$i]' PASSING $place.n - 1 AS \"i\" RETURNING JSON) AS item " +
            "FROM (SELECT LEVEL n FROM DUAL CONNECT BY LEVEL <= GREATEST(NVL($size, 0), 1)) $place WHERE $place.n <= $size) $alias"
    }

    override fun aggregateList(value: String, order: String?) =
        "COALESCE(JSON_ARRAYAGG($value${order?.let { " ORDER BY $it" }.orEmpty()} NULL ON NULL RETURNING JSON), JSON('[]'))"

    /** `JSON_ARRAYAGG` leaves nulls out. */
    override fun collect(value: String, order: String?) =
        "COALESCE(JSON_ARRAYAGG($value${order?.let { " ORDER BY $it" }.orEmpty()} RETURNING JSON), JSON('[]'))"

    /** At most 4,000 bytes, as a `VARCHAR2`: the rest is left off, where it would fail the statement. */
    override fun joinedTexts(text: String, order: String) = "LISTAGG($text ON OVERFLOW TRUNCATE '' WITHOUT COUNT) WITHIN GROUP (ORDER BY $order)"

    /**
     * Each item where it is first among those equal to it as `JSON` compares them, kept as it is: not by its text, which is
     * at most 4,000 bytes and keeps a map's keys in the order written, and not read back from it, which would read a float
     * that is a whole number, `2.0`, as the number 2.
     */
    override fun distinctItems(list: String) =
        "(SELECT COALESCE(JSON_ARRAYAGG(d.item ORDER BY d.item_no RETURNING JSON), JSON('[]')) " +
            "FROM (SELECT e.item_no, e.item, ROW_NUMBER() OVER (PARTITION BY e.item ORDER BY e.item_no) AS n " +
            "FROM ${elements(list, "e")}) d WHERE d.n = 1)"

    override fun firstOfEach(list: String, identities: String) =
        "(SELECT ${aggregateList("v.item", "v.item_no")} FROM ${elements(list, "v")} JOIN (SELECT d.item_no FROM (SELECT e.item_no, " +
            "ROW_NUMBER() OVER (PARTITION BY e.item ORDER BY e.item_no) AS n FROM ${elements(identities, "e")}) d WHERE d.n = 1) f ON f.item_no = v.item_no)"

    /** Of the values of the kind that comes first, or last, as [kindOrder] orders them; by [key] first where there is one. */
    override fun extreme(value: String, min: Boolean, key: String?): String {
        val direction = if (min) "ASC" else "DESC"
        return "${if (min) "MIN" else "MAX"}(${nullIfJsonNull(value)}) KEEP (DENSE_RANK FIRST ORDER BY ${kindOrder(value)} $direction NULLS LAST${key?.let { ", $it $direction NULLS LAST" }.orEmpty()})"
    }

    /**
     * A float, `BINARY_DOUBLE`, where any value summed is one, a double or a number with a fraction, as in Cypher: `NUMBER`
     * keeps no such kind, so a sum of floats that is whole, 0.5 + 1.0 + 0.5, would be the integer 2. Floats are summed as
     * doubles, as Neo4j sums them: as `NUMBER`s, 1e300 would be dropped, and 0.1 + 0.2 would be 0.3. Nothing is bound under
     * an aggregate, so each value is written out where it is read: each read is one path that tests the value's kind
     * itself, a text not summed and a number with a fraction written with a point, or with a negative exponent, `1E-41`,
     * as Oracle writes one of more than 40 digits, so a sum reads it three times, as [number] alone does. A path compares
     * no item to another, `@ != @.floor()` failing with ORA-40597, so a fraction is told by how it is written.
     */
    override fun sum(json: String, distinct: String): String {
        fun summed(type: String) = "sum(${distinct}JSON_VALUE($json, '\$?(@.type() != \"string\")' RETURNING $type))"
        val fraction = "@.string() has substring \".\" || @.string() has substring \"E-\""
        val float = "MAX(JSON_VALUE($json, '\$?(@.type() == \"double\" || (@.type() == \"number\" && ($fraction)))')) IS NOT NULL"
        return "CASE WHEN $float THEN JSON_SCALAR(${summed("BINARY_DOUBLE")}) ELSE JSON_SCALAR(COALESCE(${summed("NUMBER")}, 0)) END"
    }

    /** In `BINARY_DOUBLE` where either is one, so a float stays a float, as in Cypher: `NUMBER` keeps no such kind. */
    override fun arithmetic(a: String, b: String, operator: String) =
        "CASE WHEN ${kind(a)} = 'double' OR ${kind(b)} = 'double' " +
            "THEN JSON_SCALAR(TO_BINARY_DOUBLE(${number(a)}) $operator TO_BINARY_DOUBLE(${number(b)})) " +
            "ELSE JSON_SCALAR(${number(a)} $operator ${number(b)}) END"

    /** A side that is a property is read from the properties by its own path: see [direct]. */
    override fun add(a: String, b: String, given: Pair<Any?, Any?>) = direct(direct(
        "CASE WHEN ${typeOf(a)} = 'number' AND ${typeOf(b)} = 'number' THEN ${arithmetic(a, b, "+")} " +
            "WHEN ${kind(a)} = 'array' AND ${kind(b)} = 'array' THEN JSON_QUERY(JSON_ARRAY($a, $b RETURNING JSON), '\$[*][*]' RETURNING JSON WITH ARRAY WRAPPER) " +
            "WHEN ${kind(a)} = 'array' THEN JSON_TRANSFORM($a, APPEND '\$' = $b) " +
            "WHEN ${kind(b)} = 'array' THEN JSON_TRANSFORM($b, INSERT '\$[0]' = $a) " +
            "WHEN ${typeOf(a)} IN ('string', 'number') AND ${typeOf(b)} IN ('string', 'number') THEN ${textSum(a, b, given)} END",
        a), b)

    /**
     * [sql] with each value it reads out of the JSON [json] with `JSON_VALUE(json, '$…')` read from the properties by the
     * property's own path instead, where [json] is a property as [propertyOf] reads it, `JSON_QUERY(props, '$.a' RETURNING
     * JSON)`. Oracle closed the session (ORA-03113) on statements, one row each, that took a text past 32 KB out of a
     * node's properties so and read it as a CLOB or put it together with another, and a later statement in the session lost
     * its rows: the memory corruption of #255. A value used whole, as a list a sum joins, still takes the property out.
     * It costs about a quarter more on short texts: 100,000 rows of `size(n.name)` took 208 ms against 162 ms, and of
     * `toUpper(n.name)` 196 ms against 164 ms.
     */
    private fun direct(sql: String, json: String): String {
        val (props, path) = propertyRead(json) ?: return sql
        return sql.replace("JSON_VALUE($json, '\$", "JSON_VALUE($props, ${path.dropLast(1)}")
    }

    /**
     * Two texts put together, as `VARCHAR2`s where both are read so and their sum fits in one, 4,000 bytes; else as a CLOB
     * (#298), where a text past 4,000 bytes was left out as null, and a longer sum failed. Whether they fit is told from the
     * sides' own texts, short to read: a number may be written otherwise ([asText]), though in at most [NUMBER_TEXT] bytes.
     * A text the statement gives is as long as it is, and not read for each row.
     */
    private fun textSum(a: String, b: String, given: Pair<Any?, Any?>): String {
        val swapped = given.second to given.first
        fun bytes(json: String, given: Any?) = (given as? String)?.toByteArray(Charsets.UTF_8)?.size?.toString() ?: "LENGTHB(JSON_VALUE($json, '\$'))"
        val fits = "${bytes(a, given.first)} + ${bytes(b, given.second)} <= ${SHORT_TEXT - 2 * NUMBER_TEXT}"
        val short = "${summedText(a, given, short = true)} || ${summedText(b, swapped, short = true)}"
        val whole = "TO_CLOB(${summedText(a, given, short = false)}) || ${summedText(b, swapped, short = false)}"
        // Two empty texts are null together, as Oracle's empty text is, and not an empty CLOB.
        val empty = "JSON_VALUE($a, '\$.length()') = 0 AND JSON_VALUE($b, '\$.length()') = 0"
        return "CASE WHEN $fits THEN JSON_SCALAR($short) WHEN $empty THEN NULL ELSE JSON_SCALAR($whole) END"
    }

    /**
     * The side [json] of a `+` whose sides the statement gives as [given], this side first, as text: see [summedText]. A text
     * as a `VARCHAR2` where [short], null past 4,000 bytes, and else as a CLOB; a number's text is short.
     */
    private fun summedText(json: String, given: Pair<Any?, Any?>, short: Boolean) =
        summedText(json, given.first, given.second, { if (short) "JSON_VALUE($it, '\$')" else "JSON_VALUE($it, '\$' RETURNING CLOB)" }) {
            if (short) asText(it) else "CASE WHEN ${kind(it)} = 'string' THEN JSON_VALUE($it, '\$' RETURNING CLOB) ELSE TO_CLOB(${asText(it)}) END"
        }

    /**
     * The JSON [json], a text, a number or a truth value, as `toString` gives it: a float (a double, or a number with a
     * fraction) as Java writes a double, from the shortest decimal Oracle writes it as, with no exponent from 10^-3 up to
     * 10^7, and else as `1.0E7`, `1.2E-4`; NaN and the infinities by Java's names. A double past a `NUMBER`'s range,
     * `1e300`, is written from Oracle's own text of it, its shortest digits, `1E+300`.
     */
    private fun asText(json: String) = shared(json) { (x) ->
        val written = "JSON_VALUE($x, '\$')"
        val float = "${kind(x)} = 'double' OR (${kind(x)} = 'number' AND NOT ${integral(x)})"
        val n = "TO_NUMBER($written DEFAULT NULL ON CONVERSION ERROR)"
        val double = "JSON_VALUE($x, '\$' RETURNING BINARY_DOUBLE)"
        // Past a NUMBER's range, which reads 1e-200 as 0, the double's own text, with a point where it has none: `1E-310`.
        // TO_CHAR of the double writes 15 digits, so 1e-310 as 9.99999999999997E-311.
        val scientific = scientific("NVL(TO_CHAR(NULLIF($n, 0), 'FM9.9999999999999999EEEE'), REGEXP_REPLACE($written, '^(-?[0-9]+)E', '\\1.0E'))")
        "CASE WHEN ${kind(x)} = 'double' AND $written IN ('Nan', 'Inf', '-Inf') THEN DECODE($written, 'Nan', 'NaN', 'Inf', 'Infinity', '-Infinity') " +
            "WHEN $float THEN CASE WHEN $double = 0 THEN '0.0' WHEN ABS($n) >= 0.001 AND ABS($n) < 10000000 THEN TO_CHAR($n, '$PLAIN_FLOAT') ELSE $scientific END " +
            "ELSE $written END"
    }

    /** `collect(CASE WHEN … THEN [r] ELSE [m.title] END)` fails with ORA-00979, or closes the session: see `casesFirst`. */
    override val aggregatesCaseSubqueries = false

    /** `min` and `max` of lists grouped and ordered by [listSortKey] close the session (ORA-03113): `JSON` orders them by itself. */
    override val extremesByListKey = false

    /** `TO_CHAR`'s scientific notation, `1.E+07`, `-1.2E-04`, as Java's: `1.0E7`, `-1.2E-4`. */
    private fun scientific(text: String) = "REGEXP_REPLACE(REPLACE($text, '.E', '.0E'), 'E\\+?(-?)0*([0-9])', 'E\\1\\2')"

    /** `JSON_EQUAL`, which compares values larger than `=` does, with ORA-40180 past about 32 KB. */
    override fun identityEquals(x: String, y: String) = "JSON_EQUAL($x, $y)"

    override fun idTokens(ids: String, key: String) = "JSON(REGEXP_REPLACE(JSON_SERIALIZE($ids RETURNING CLOB), '(-?[0-9]+)', ${literal("{\"$key\": \\1}")}))"

    override fun itemAt(json: String, index: Int) = "JSON_QUERY($json, '\$[$index]' RETURNING JSON)"

    override fun listSize(json: String) = "JSON_VALUE($json, '\$.size()' RETURNING NUMBER)"

    /**
     * A null as JSON's text writes one in a list or a map, its texts and keys taken out first, so a text that holds a null's
     * text (`"a,null]"`) does not say so: Oracle's JSON path walks no descendants. Read as a `VARCHAR2`, faster than as a
     * `CLOB`, which a value whose text is past 4,000 bytes is read as instead. It is exact, as a procedure's [nullsEqual] needs.
     */
    override fun holdsNull(json: String): String {
        fun nulls(text: String) = "REGEXP_LIKE(REGEXP_REPLACE($text, '\"([^\"\\\\]|\\\\.)*\"', '\"\"'), '[[,:]null[],}]')"
        val short = "JSON_SERIALIZE($json RETURNING VARCHAR2(4000) NULL ON ERROR)"
        return "($short IS NOT NULL AND ${nulls(short)} OR $short IS NULL AND ${nulls("JSON_SERIALIZE($json RETURNING CLOB)")})"
    }

    /**
     * Two values written as the same JSON text are null, as one holds a null and so its pair in the other does: that needs
     * no walk. The text is read as a `VARCHAR2`, as [holdsNull] reads it; a value past 4,000 bytes has none, and `=` and
     * `JSON_EQUAL` fail on one past about 32 KB (ORA-40180), so such a value is walked. Any other two are walked together by a
     * function, as Oracle's recursive queries take no `JSON`: the store's own ([nullsEqualName]), which the graph is made
     * with, so a procedure's queries and a `reduce`'s step's call it too. Where the store has none (see
     * [callsStoredNullsEqual]), a query declares it (see [reduce]); a procedure's queries call no function it declares, nor
     * do a `reduce`'s step's, so there two equal as JSON are null and any other two false, as before null was compared item
     * by item: `[null] = [1]` is false there, where Cypher's is null.
     */
    override fun nullsEqual(x: String, y: String, equals: (String, String) -> String): String {
        if (!nullsEqualStored && (procedural || folding)) return "CASE WHEN ${equals(x, y)} THEN NULL ELSE FALSE END"
        val walked = if (nullsEqualStored) {
            callsStoredNullsEqual = true
            "($storedNullsEqual($x, $y) = 1)"
        } else {
            if (!nullsCompared) {
                nullsCompared = true
                declare(nullsEqualFunction(NULLS_EQUAL))
            }
            "($NULLS_EQUAL($x, $y) = 1)"
        }
        fun text(json: String) = "JSON_SERIALIZE($json RETURNING VARCHAR2(4000) NULL ON ERROR)"
        return "CASE WHEN ${text(x)} = ${text(y)} THEN NULL ELSE $walked END"
    }

    /** Whether the query declares [NULLS_EQUAL]: see [nullsEqual]. */
    private var nullsCompared = false

    override fun modulo(a: String, b: String) = "MOD($a, $b)"

    /** CLOBs, which `=` does not compare (ORA-22848), by `DBMS_LOB.COMPARE`: see [texts]. */
    override fun startsWith(a: String, b: String) = shared(b) { (x) ->
        if (long) "(DBMS_LOB.COMPARE($a, $x, LENGTH($x), 1, 1) = 0)" else "(SUBSTR($a, 1, LENGTH($x)) = $x)"
    }

    override fun endsWith(a: String, b: String) =
        if (long) shared(a, b) { (t, x) -> "(LENGTH($t) >= LENGTH($x) AND DBMS_LOB.COMPARE($t, $x, LENGTH($x), LENGTH($t) - LENGTH($x) + 1, 1) = 0)" }
        else shared(b) { (x) -> "(SUBSTR($a, -LENGTH($x)) = $x)" }

    /** Of CLOBs too (see [texts]), though `INSTR` looks for no text past 32 KB (ORA-22828). */
    override fun containsText(a: String, b: String) = "(INSTR($a, $b) > 0)"

    /** Of a property, read from the properties by its own path: see [direct]. */
    override fun function(name: String, json: String): String? = functionOf(name, json)?.let { direct(it, json) }

    private fun functionOf(name: String, json: String): String? = when (name) {
        // A text past 4,000 bytes, null as a VARCHAR2, is measured as a CLOB (#298).
        "size" -> "CASE ${kind(json)} WHEN 'array' THEN JSON_SCALAR(JSON_VALUE($json, '\$.size()' RETURNING NUMBER)) " +
            "WHEN 'string' THEN JSON_SCALAR(COALESCE(LENGTH(JSON_VALUE($json, '\$')), LENGTH(JSON_VALUE($json, '\$' RETURNING CLOB)))) END"
        "tolower" -> texts(json) { (t) -> scalar("LOWER($t)") }
        "toupper" -> texts(json) { (t) -> scalar("UPPER($t)") }
        "trim" -> texts(json) { (t) -> scalar("TRIM($t)") }
        // A text past 4,000 bytes, which asText reads as null, is read as a CLOB.
        "tostring" -> "COALESCE(JSON_SCALAR(${asText(json)}), JSON_SCALAR(${longText(json)}))"
        "tointeger" -> "JSON_SCALAR(TRUNC(TO_NUMBER(JSON_VALUE($json, '\$') DEFAULT NULL ON CONVERSION ERROR)))"
        "tofloat" -> "JSON_SCALAR(TO_BINARY_DOUBLE(JSON_VALUE($json, '\$') DEFAULT NULL ON CONVERSION ERROR))"
        "head" -> "JSON_QUERY($json, '\$[0]' RETURNING JSON)"
        "last" -> "JSON_QUERY($json, '\$[last]' RETURNING JSON)"
        "abs" -> "CASE WHEN ${kind(json)} = 'double' THEN JSON_SCALAR(ABS(TO_BINARY_DOUBLE(${number(json)}))) ELSE JSON_SCALAR(ABS(${number(json)})) END"
        "keys" -> keys(json)
        else -> null
    }

    /**
     * A JSON path reads no map's keys, so they are read from the map's data guide: each path it lists one step
     * from the top is a key, written `$.name`, or `$."…"` as JSON writes a string where the name is any other.
     * The map is bound first: the data guide's query reads a column of a row outside it, and not an expression of one.
     * Null has no keys, and gives null.
     */
    private fun keys(json: String): String = bound(json) { (map) -> "CASE WHEN ${kind(map)} = 'object' THEN ${keysOf(map)} END" }

    private fun keysOf(json: String): String {
        val key = """CASE WHEN SUBSTR(d.p, 3, 1) = '"' THEN JSON_VALUE(JSON(SUBSTR(d.p, 3)), '${'$'}') ELSE SUBSTR(d.p, 3) END"""
        val guide = """JSON_TABLE((SELECT JSON_DATAGUIDE($json, 2) FROM DUAL), '${'$'}[*]' COLUMNS (p VARCHAR2(4000) PATH '${'$'}."o:path"')) d"""
        val topLevel = """REGEXP_LIKE(d.p, '^\${'$'}\.(\w+|"([^"\\]|\\.)*")${'$'}')"""
        return "(SELECT COALESCE(JSON_ARRAYAGG(g.k ORDER BY g.k RETURNING JSON), JSON('[]')) FROM (SELECT $key AS k FROM $guide WHERE $topLevel) g)"
    }

    override fun page(skip: String?, limit: String?) = listOfNotNull(skip?.let { "OFFSET $it ROWS" }, limit?.let { "FETCH NEXT $it ROWS ONLY" })

    override fun ids(id: String) = "(',' || $id || ',')"

    override fun joinIds(lists: List<String>) = "(${lists.first()}${lists.drop(1).joinToString("") { " || SUBSTR($it, 2)" }})"

    override fun storeIds(json: String) = shared(json) { (ids) ->
        val listed = "LISTAGG(x.id, ',') WITHIN GROUP (ORDER BY x.ord)"
        "CASE WHEN $ids IS NOT NULL THEN (SELECT NVL2($listed, ',' || $listed || ',', ',') FROM JSON_TABLE($ids, '\$[*]' COLUMNS (ord FOR ORDINALITY, id NUMBER PATH '\$')) x) END"
    }

    override val noIds = "','"

    override fun idIn(id: String, list: String) = "INSTR($list, ',' || $id || ',') > 0"

    override fun idsOverlap(a: String, b: String) = "EXISTS (SELECT 1 FROM ${idRows(a, "ov")} WHERE INSTR($b, ',' || ov.id || ',') > 0)"

    override fun idCount(list: String) = "JSON_SCALAR(REGEXP_COUNT($list, ',') - 1)"

    override fun idRows(list: String, alias: String) =
        "JSON_TABLE(JSON('[' || TRIM(BOTH ',' FROM $list) || ']'), '\$[*]' COLUMNS (ord FOR ORDINALITY, id NUMBER PATH '\$')) $alias"

    /** Oracle's recursive queries read nothing of the row they are in. */
    override val walksReadRow = false

    /** A recursive query from each node that meets the seed, each row carrying the node it started from. */
    override fun walk(walk: Walk): Walked {
        val w = walk.step
        val n = "${walk.name}_n"
        val (joins, reached, distinct) = steps(walk, "$w.nid")
        val eids = walk.hops.map { "${it.edge}.eid" }
        val conditions = eids.map { "INSTR($w.eids, ',' || $it || ',') = 0" } + distinct + listOfNotNull(walk.max?.let { "$w.depth < $it" }) + walk.conditions
        // Oracle adapts a step's plan as the statement runs, and a second session that runs the statement then finds no
        // rows where a walk either way goes around a cycle (#182): no plan adapts.
        val select = "SELECT /*+ NO_ADAPTIVE_PLAN */"
        val text = listOf(
            "WITH $w (src, nid, depth, nids, eids) AS (",
            "    SELECT $n.nid, $n.nid, 0, CAST(',' AS VARCHAR2(4000)), CAST(',' AS VARCHAR2(4000))",
            "    FROM $nodes $n${walk.seed?.let { " WHERE ${it(n)}" }.orEmpty()}",
            "    UNION ALL",
            // Walked backwards, each node it leaves goes first, so its nodes end with the one it started from.
            if (walk.backwards) "    $select $w.src, ${reached.single()}, $w.depth + 1, ',' || $w.nid || $w.nids, ',' || ${eids.single()} || $w.eids"
            else "    $select $w.src, ${reached.last()}, $w.depth + 1, $w.nids${reached.joinToString("") { " || $it || ','" }}, $w.eids${eids.joinToString("") { " || $it || ','" }}",
            "    FROM $w",
            joins.joinToString("\n") { "    $it" },
            "    WHERE ${conditions.joinToString(" AND ")}",
            // A walk may come back to a node it left, which Oracle takes for a cycle unless told what one is: a step
            // takes no relationship twice, so no row's relationships are an earlier row's.
            ") CYCLE eids SET looped TO 'Y' DEFAULT 'N'",
            "SELECT src, nid, nids, eids FROM $w WHERE depth >= ${walk.min}",
        ).joinToString("\n")
        return Walked(
            "(\n${indent(text)}\n) ${walk.name}",
            "${walk.name}.src = ${walk.from} AND ${walk.name}.nid = ${walk.to}",
            "${walk.name}.eids",
            "${walk.name}.nids",
        )
    }

    override fun nodeJson(node: Value.Node) =
        "CASE WHEN ${node.nid} IS NOT NULL THEN JSON_TRANSFORM(${node.props}, SET ${OracleSql.path(Stamps.PROPERTY)} = ${node.stamp} IGNORE ON NULL) END"

    /**
     * A function the query declares, `WITH FUNCTION`, that folds the list: Oracle's recursive queries read nothing
     * of the row they are in. The step reads what it reads of the row from the map it is given. A procedure's
     * queries call no function it declares, so a statement that writes takes no `reduce`; nor does the step of one,
     * whose queries are its function's own.
     */
    override fun reduce(
        name: String,
        initial: String,
        list: String,
        step: (accumulator: String, item: String) -> String,
        context: () -> String,
        contextStep: (accumulator: String, item: String, context: String) -> String,
    ): String {
        if (procedural) throw DrivineException("reduce() does not run on Oracle in a statement that writes: its block calls no function of its own from SQL.")
        if (folding) throw DrivineException("reduce() does not run on Oracle in the step of a reduce(): its function calls no function of the query's from SQL.")
        val function = "cy_$name"
        val given = context()
        folding = true
        val folded = try {
            contextStep("cy_value", "cy_step.item", "cy_context")
        } finally {
            folding = false
        }
        declare(
            listOf(
                "FUNCTION $function(cy_initial JSON, cy_list JSON, cy_context JSON) RETURN JSON IS",
                "    cy_value JSON := cy_initial;",
                "    cy_kind VARCHAR2(20);",
                "BEGIN",
                "    SELECT JSON_VALUE(cy_list, '\$.type()') INTO cy_kind FROM DUAL;",
                "    IF cy_kind IS NULL OR cy_kind <> 'array' THEN RETURN NULL; END IF;",
                "    FOR cy_step IN (SELECT s.item FROM JSON_TABLE(cy_list, '\$[*]' COLUMNS (item_no FOR ORDINALITY, item JSON PATH '\$')) s ORDER BY s.item_no) LOOP",
                indent("    SELECT $folded INTO cy_value FROM DUAL;"),
                "    END LOOP;",
                "    RETURN cy_value;",
                "END;",
            ).joinToString("\n")
        )
        return "$function($initial, $list, $given)"
    }

    /** Whether the step of a `reduce` is being written, as its function's own: see [reduce]. */
    private var folding = false

    /** As many rows as the range has numbers, from a hierarchical query of one row. */
    override fun range(from: String, to: String, step: String) = shared(from, to, step) { (first, last, by) ->
        val count = "FLOOR(($last - $first) / $by) + 1"
        "(SELECT COALESCE(JSON_ARRAYAGG($first + (r.n - 1) * $by ORDER BY r.n RETURNING JSON), JSON('[]')) " +
            "FROM (SELECT LEVEL n FROM DUAL CONNECT BY LEVEL <= GREATEST($count, 1)) r WHERE r.n <= $count)"
    }

    override val temporalSql = OracleTemporalSql(sharedList) { procedural }

    /**
     * Oracle merges a view of no table into the subquery that reads it, and where that subquery is bound in
     * another and the row reads another property, the merged query reads the value as null: each is kept apart.
     */
    override fun boundSelect(columns: String) = "SELECT /*+ NO_MERGE */ $columns"

    /**
     * Oracle merges a `CALL { … }` query into the row's query that joins it, and a value bound in that row from what
     * the query returns is then read as null (`CALL { WITH n RETURN n.day AS d } RETURN d + duration('P1D')`).
     */
    override fun called(query: String) = "SELECT /*+ NO_MERGE */ * FROM (\n${indent(query)}\n)"

    /**
     * Flags the pattern opens with, `(?i)`, are Oracle's match parameter, Java's `s` its `n`. The whole of the text
     * matches, as `=~` asks: it starts at `\A` and ends at `\z`, the text's start and end, which `(?m)` leaves as they are,
     * and Oracle's `$` also matches before a line break that ends the text. Under `(?m)`, Oracle's own `^` and `$` match
     * at a line break only where nothing in the pattern reads past it: `x$\n^ab` matches no text.
     */
    override fun matches(text: String, pattern: String) = shared(pattern) { (p) ->
        val flags = "REGEXP_SUBSTR($p, '^\\(\\?([a-z]+)\\)', 1, 1, NULL, 1)"
        "REGEXP_LIKE($text, '\\A(' || REGEXP_REPLACE($p, '^\\(\\?[a-z]+\\)', '') || ')\\z', NVL(REPLACE($flags, 's', 'n'), 'c'))"
    }

    /**
     * The text as JSON writes it, with each separator as JSON writes it made the end of one string and the start of the next.
     * A `VARCHAR2` is cut off past 4,000 bytes where `REPLACE` makes it longer, so a text is split as one only where what it
     * is made cannot be longer, each of its bytes written as six at most (`\u0001`), and else as a CLOB: see [texts].
     */
    override fun split(text: String, separator: String) = shared(text, separator) { (t, s) ->
        // A separator past 4,000 bytes is a CLOB too, and written as one (ORA-40478).
        fun serialized(x: String) = if (long) "JSON_SERIALIZE(JSON_SCALAR($x) RETURNING CLOB)" else "JSON_SERIALIZE(JSON_SCALAR($x))"
        val written = serialized(t)
        val separatorWritten = "SUBSTR(${serialized(s)}, 2, LENGTH(${serialized(s)}) - 2)"
        val fits = if (long) "" else " AND LENGTHB($t) <= ${(SHORT_TEXT - 2) / 6}"
        "CASE WHEN $t IS NOT NULL AND $s IS NOT NULL$fits THEN JSON('[' || REPLACE($written, $separatorWritten, '\",\"') || ']') END"
    }

    /**
     * A `VARCHAR2` is cut off past 4,000 bytes where `REPLACE` makes it longer: as one only where it cannot be, as each
     * [search] made [replacement] grows the text by their ratio at most, and else null, so [texts] replaces in a CLOB. Their
     * lengths are worked out here where the statement gives them, and not for each row.
     */
    override fun replaceText(text: String, search: String, replacement: String, searchBytes: Int?, replacementBytes: Int?): String {
        val replaced = "REPLACE($text, $search, $replacement)"
        if (long) return replaced
        if (searchBytes != null && replacementBytes != null) {
            return if (replacementBytes <= searchBytes) replaced else "CASE WHEN LENGTHB($text) * $replacementBytes <= ${SHORT_TEXT * searchBytes} THEN $replaced END"
        }
        val (s, r) = (searchBytes?.toString() ?: "LENGTHB($search)") to (replacementBytes?.toString() ?: "LENGTHB($replacement)")
        return "CASE WHEN $r <= $s OR LENGTHB($text) * $r <= $SHORT_TEXT * $s THEN $replaced END"
    }

    /** Oracle has no reverse of a text that it documents: its characters, read from the last. */
    override fun reverseText(text: String) = shared(text) { (t) ->
        "(SELECT LISTAGG(SUBSTR($t, LEVEL, 1)) WITHIN GROUP (ORDER BY LEVEL DESC) FROM DUAL CONNECT BY LEVEL <= LENGTH($t))"
    }

    /** `ROW_NUMBER()` takes an order of its own; `ROWNUM` counts the rows as the query gives them. */
    override val rowNumber = "ROWNUM"

    /** Oracle's GUID, in a UUID's form: unique, though not random as a version 4 UUID is. */
    override fun uuid() = "LOWER(REGEXP_REPLACE(RAWTOHEX(SYS_GUID()), '(.{8})(.{4})(.{4})(.{4})(.{12})', '\\1-\\2-\\3-\\4-\\5'))"

    /**
     * Worked out as a `NUMBER`, then made the nearest double: Oracle fails where Neo4j gives NaN or an infinity, `LN(0)`,
     * `SQRT(-1)`, `ASIN(2)` and `1 / TAN(0)` among them, and where a `NUMBER` overflows, past 1e126, as `EXP(300)` does.
     * One too small for a `NUMBER`, below 1e-130, is 0, as `EXP(-1000)` is in Java, and `EXP(-300)` is not.
     * A `BINARY_DOUBLE` would give NaN or an infinity, which JSON holds neither of.
     */
    override fun math(name: String, numbers: List<String>): String {
        val x = numbers[0]
        return float(
            when (name) {
                "log" -> "LN($x)"
                "log10" -> "LOG(10, $x)"
                "cot" -> "(1 / TAN($x))"
                // In doubles from the one nearest the cosine, as Java works it out: near 0, that loses digits a NUMBER keeps.
                "haversin" -> "((1 - TO_BINARY_DOUBLE(COS($x))) / 2)"
                // In doubles, by the ratio Java multiplies by: see [RADIANS_TO_DEGREES].
                "degrees" -> "(TO_BINARY_DOUBLE($x) * $RADIANS_TO_DEGREES)"
                "radians" -> "(TO_BINARY_DOUBLE($x) * $DEGREES_TO_RADIANS)"
                // Oracle overflows at ATAN2(0, 0), which Java gives as 0.0: a NUMBER has no negative zero to give π for.
                "atan2" -> shared(x, numbers[1]) { (y, z) -> "CASE WHEN $y = 0 AND $z = 0 THEN 0 ELSE ATAN2($y, $z) END" }
                else -> "${name.uppercase()}($x)"
            }
        )
    }

    /** SUBSTR from the end gives null where it is asked for more than there is. */
    override fun rightText(text: String, count: String) =
        shared(text, count) { (t, n) -> "CASE WHEN $n >= LENGTH($t) THEN $t WHEN $n > 0 THEN SUBSTR($t, -$n) END" }

    /** An anonymous block, which returns its rows as an implicit result. */
    override fun procedure(program: List<ProcedureStep>) = (listOf(
        "DECLARE",
        // Text, read as JSON where each parameter is read: see [propertyOf].
        "    $PARAMS CLOB := \$${Parameters.OBJECT};",
        "    $ROWS JSON;",
        "    $OUT JSON;",
        "    $ROW JSON;",
        "    $VALUE JSON;",
        "    $ID NUMBER;",
        "    $COUNT NUMBER;",
        "    $FOUND BOOLEAN;",
        "    $DOOMED JSON;",
        "    $CURSOR SYS_REFCURSOR;",
        "    ${OracleTemporalSql.NOW} TIMESTAMP;",
    ) + (1..NESTING).flatMap { listOf("    $SAVED$it JSON;", "    $GATHERED$it JSON;", "    $CALLER$it JSON;") } + listOf(
        "BEGIN",
        // The clock is read once, by a query: see [OracleTemporalSql.now].
        "    SELECT ${OracleTemporalSql.CLOCK} INTO ${OracleTemporalSql.NOW} FROM DUAL;",
        indent(statements(program).joinToString("\n")),
        "END;",
    )).joinToString("\n")

    override fun returnRows(query: String) = listOf("OPEN $CURSOR FOR\n${indent(query)};", "DBMS_SQL.RETURN_RESULT($CURSOR);")

    /**
     * Oracle's `SIGN` gives 0 for a positive number under 1.0E-128, from 1.0E-130 up, which NUMBER holds and compares
     * as greater than 0, and is right from 1.0E-128 up, for a negative number, 0, and null. So the number is held to
     * between -1 and 1, where a thousand times it cannot overflow, and that is signed, past 1.0E-128 where it is not 0:
     * [number] is read once, so a sign of a sign does not write it out twice at each level.
     */
    override fun signOf(number: String) = "SIGN(GREATEST(LEAST($number, 1), -1) * 1000)"

    override fun roundPlaces(number: String, precision: String) = pastItsDigits(number, precision) { n, p -> super.roundPlaces(n, p) }

    override fun roundTo(number: String, precision: String, mode: RoundingMode) = pastItsDigits(number, precision) { n, p -> super.roundTo(n, p, mode) }

    /**
     * [round] of [number] to [precision] places, or [number] itself where those places are past its digits: Oracle's NUMBER
     * stops near 10^126, so `ROUND(n, 400)`, and the scale `10^p` a mode rounds by, overflow (ORA-01426) where Neo4j gives
     * the number back. Neo4j rounds a double, of [DOUBLE_DIGITS] significant digits at most, so rounding it to places past
     * its last digit changes nothing; a number Oracle holds to more digits is a double again once rounded. Oracle works
     * out only the branch of a CASE a row takes, so the overflowing one is never reached. A null precision makes the test
     * null, so the row takes the rounding, which gives null. The digits are compared in decimal, not by `LOG(10, ABS(n))`,
     * which fails (ORA-01428) on 0 and on the least numbers NUMBER holds, near 1.0E-130; zero is given back where
     * `10^(17 - p)` underflows to it, and rounded to 0 short of that.
     * Short of its digits, a tiny number may still take more than [MOST_PLACES] places, as 1.0E-120 to 130 does: it is
     * rounded moved that many places fewer to the right, then moved back, which is exact in decimal.
     */
    private fun pastItsDigits(number: String, precision: String, round: (String, String) -> String) = shared(number, precision) { (n, p) ->
        val past = "GREATEST($p - $MOST_PLACES, 0)"
        val shift = "POWER(10, $past)"
        "CASE WHEN ABS($n) >= POWER(10, $DOUBLE_DIGITS - $p) THEN $n ELSE ${round("($n * $shift)", "($p - $past)")} / $shift END"
    }

    /** A text that is no number, read as one: Oracle fails with ORA-01722, whose ORA-03302 detail gives the text. */
    override fun failure(message: String, value: String) = "TO_NUMBER(${literal("$message ")} || TO_CHAR($value))"

    override fun raise(message: String) = "RAISE_APPLICATION_ERROR(-20000, ${literal(message)});"

    override fun idOf(json: String, key: String?) = "JSON_VALUE($json, ${key?.let { OracleSql.path(it) } ?: "'$'"} RETURNING NUMBER)"

    override fun appended(list: String, item: String) = "JSON_TRANSFORM($list, APPEND '\$' = $item)"

    override fun stripNulls(json: String) = "JSON_MERGEPATCH(JSON('{}'), $json RETURNING JSON)"

    override fun merged(props: String, map: String) = "JSON_MERGEPATCH($props, $map RETURNING JSON)"

    override fun withoutLabel(labels: String, label: String) = "JSON_TRANSFORM($labels, REMOVE ${literal("\$[*]?(@ == ${OracleSql.quoted(label)})")})"

    /** The kind of the JSON [json] as Oracle names it, a floating-point number by its own name. */
    private fun kind(json: String) = "JSON_VALUE($json, '\$.type()')"

    companion object {
        /** The cursor a procedure returns its rows by. */
        private const val CURSOR = "cy_cursor"

        /** A property as [propertyOf] reads it, its properties and its path, a literal whose quotes are doubled: see [propertyRead]. */
        val PROPERTY_READ = Regex("""JSON_QUERY\((.+), ('\$(?:[^']|'')*') RETURNING JSON\)""")

        /** The most bytes a `VARCHAR2` holds in SQL, as a database is made by default (`MAX_STRING_SIZE = STANDARD`). */
        const val SHORT_TEXT = 4000

        /** The most bytes [asText] writes a number in: Java's text of a double, `-2.2250738585072014E-308`, and some to spare. */
        const val NUMBER_TEXT = 32

        /** The key of a double in Oracle's extended JSON. */
        private const val DOUBLE = "\$numberDouble"

        /** The function a query declares that compares two values that may hold a null, where the store keeps none: see [nullsEqual]. */
        private const val NULLS_EQUAL = "cy_nulls_equal"

        /** How many significant digits a double holds at most, as Neo4j rounds one. */
        private const val DOUBLE_DIGITS = 17

        /** The most places [pastItsDigits] rounds to, whose scale `10^p` is well inside the NUMBER range. */
        private const val MOST_PLACES = 100

        /** The function the store keeps beside the graph [graph]'s tables that compares two values that may hold a null: see [nullsEqual]. */
        internal fun nullsEqualName(graph: String) = "${graph}_nulls_equal"

        /**
         * The function [name], which compares two JSON values as Cypher compares values that may hold a null: 1 where
         * they are equal, 0 where they differ, and null where a pair of their items holds a null and no pair differs. It
         * walks the two together with `JSON_ELEMENT_T`, list item by list item and map key by map key, at any depth. A
         * query declares it with `WITH FUNCTION`; the store keeps it, made with the graph, as `CREATE OR REPLACE` of the same
         * text (see `OracleGraph`), which Drivine for TypeScript and for Python make byte for byte alike.
         *
         * The store's copy is part of the store layout the three libraries share (see AGENTS.md): it is
         * `CREATE OR REPLACE ` and this text with [name] as [nullsEqualName] gives it, the graph's name as written and then
         * `_nulls_equal`, its lines joined by `\n` with no newline after the last (the `//` comment below is not in it).
         * Changing a byte of it changes the layout for all three, so it changes in all three together.
         */
        internal fun nullsEqualFunction(name: String): String = listOf(
            "FUNCTION $name(cy_x JSON, cy_y JSON) RETURN NUMBER IS",
            "    FUNCTION eq(a JSON_ELEMENT_T, b JSON_ELEMENT_T) RETURN NUMBER IS",
            "        r NUMBER := 1;",
            "        e NUMBER;",
            "        la JSON_ARRAY_T;",
            "        lb JSON_ARRAY_T;",
            "        oa JSON_OBJECT_T;",
            "        ob JSON_OBJECT_T;",
            "        ks JSON_KEY_LIST;",
            "        too_long EXCEPTION;",
            "        PRAGMA EXCEPTION_INIT(too_long, -40478);",
            "    BEGIN",
            "        IF a.is_Null OR b.is_Null THEN RETURN NULL; END IF;",
            "        IF a.is_Array AND b.is_Array THEN",
            "            la := TREAT(a AS JSON_ARRAY_T);",
            "            lb := TREAT(b AS JSON_ARRAY_T);",
            "            IF la.get_Size <> lb.get_Size THEN RETURN 0; END IF;",
            "            FOR i IN 0 .. la.get_Size - 1 LOOP",
            "                e := eq(la.get(i), lb.get(i));",
            "                IF e = 0 THEN RETURN 0; END IF;",
            "                IF e IS NULL THEN r := NULL; END IF;",
            "            END LOOP;",
            "            RETURN r;",
            "        END IF;",
            "        IF a.is_Object AND b.is_Object THEN",
            "            oa := TREAT(a AS JSON_OBJECT_T);",
            "            ob := TREAT(b AS JSON_OBJECT_T);",
            "            IF oa.get_Size <> ob.get_Size THEN RETURN 0; END IF;",
            "            ks := oa.get_Keys;",
            "            FOR i IN 1 .. ks.COUNT LOOP",
            "                IF NOT ob.has(ks(i)) THEN RETURN 0; END IF;",
            "                e := eq(oa.get(ks(i)), ob.get(ks(i)));",
            "                IF e = 0 THEN RETURN 0; END IF;",
            "                IF e IS NULL THEN r := NULL; END IF;",
            "            END LOOP;",
            "            RETURN r;",
            "        END IF;",
            "        IF a.is_Number AND b.is_Number THEN RETURN CASE WHEN a.to_Number = b.to_Number THEN 1 ELSE 0 END; END IF;",
            // As VARCHAR2s, about 3.5 times as fast as CLOBs; as CLOBs where one is past 32 KB, which to_String
            // raises on (ORA-40478).
            "        IF a.is_String AND b.is_String THEN",
            "            BEGIN",
            "                RETURN CASE WHEN a.to_String = b.to_String THEN 1 ELSE 0 END;",
            "            EXCEPTION WHEN too_long THEN",
            "                RETURN CASE WHEN DBMS_LOB.COMPARE(a.to_Clob, b.to_Clob) = 0 THEN 1 ELSE 0 END;",
            "            END;",
            "        END IF;",
            "        IF a.is_Boolean AND b.is_Boolean THEN RETURN CASE WHEN a.to_Boolean = b.to_Boolean THEN 1 ELSE 0 END; END IF;",
            "        RETURN 0;",
            "    END;",
            "BEGIN",
            "    RETURN eq(JSON_ELEMENT_T.parse(JSON_SERIALIZE(cy_x RETURNING CLOB)), JSON_ELEMENT_T.parse(JSON_SERIALIZE(cy_y RETURNING CLOB)));",
            "END;",
        ).joinToString("\n")
    }
}
