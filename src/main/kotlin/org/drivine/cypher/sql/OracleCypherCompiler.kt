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
 * number the statement writes, or a parameter's that is a whole number, is a `BINARY_DOUBLE`, and stays one. A text Oracle reads out of JSON is at
 * most 4,000 bytes, and an empty one is null, as Oracle's empty text is. `reduce` is a function the query
 * declares, and does not run in a statement that writes, in the step of a `reduce`, or where its step reads a path.
 *
 * A list of ids is text, each id between commas (`,1,2,`): Oracle has no array, and a recursive query
 * takes no `JSON` that changes from row to row. A recursive query reads no row outside it, so a walk
 * starts from every node that meets what the node walked from meets of its own, and carries the node
 * it started from.
 */
class OracleCypherCompiler(graph: String) : SqlCypherCompiler(graph) {

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

    override fun scalar(sql: String) = "JSON_SCALAR($sql)"

    /** A floating-point number is a number, as JSON has it. */
    override fun typeOf(json: String) = shared(json) { (x) -> "DECODE(${kind(x)}, 'double', 'number', 'float', 'number', ${kind(x)})" }

    override fun isJsonNull(json: String) = "NVL(${kind(json)}, 'null') = 'null'"

    override fun nullIfJsonNull(json: String) = shared(json) { (x) -> "CASE WHEN ${kind(x)} <> 'null' THEN $x END" }

    override fun number(json: String) = shared(json) { (x) -> "CASE WHEN ${typeOf(x)} = 'number' THEN JSON_VALUE($x, '$' RETURNING NUMBER) END" }

    /** A whole number, and no floating-point number, as Cypher has an integer. */
    override fun integral(json: String) = shared(json) { (x) ->
        "(${kind(x)} = 'number' AND JSON_VALUE($x, '$' RETURNING NUMBER) = TRUNC(JSON_VALUE($x, '$' RETURNING NUMBER)))"
    }

    override fun text(json: String) = shared(json) { (x) -> "CASE WHEN ${kind(x)} = 'string' THEN JSON_VALUE($x, '$') END" }

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

    override fun aggregateList(value: String, order: String?) =
        "COALESCE(JSON_ARRAYAGG($value${order?.let { " ORDER BY $it" }.orEmpty()} NULL ON NULL RETURNING JSON), JSON('[]'))"

    /** `JSON_ARRAYAGG` leaves nulls out. */
    override fun collect(value: String, order: String?) =
        "COALESCE(JSON_ARRAYAGG($value${order?.let { " ORDER BY $it" }.orEmpty()} RETURNING JSON), JSON('[]'))"

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

    /** In `BINARY_DOUBLE` where either is one, so a float stays a float, as in Cypher: `NUMBER` keeps no such kind. */
    override fun arithmetic(a: String, b: String, operator: String) =
        "CASE WHEN ${kind(a)} = 'double' OR ${kind(b)} = 'double' " +
            "THEN JSON_SCALAR(TO_BINARY_DOUBLE(${number(a)}) $operator TO_BINARY_DOUBLE(${number(b)})) " +
            "ELSE JSON_SCALAR(${number(a)} $operator ${number(b)}) END"

    override fun add(a: String, b: String, given: Pair<Any?, Any?>) =
        "CASE WHEN ${typeOf(a)} = 'number' AND ${typeOf(b)} = 'number' THEN ${arithmetic(a, b, "+")} " +
            "WHEN ${kind(a)} = 'array' AND ${kind(b)} = 'array' THEN JSON_QUERY(JSON_ARRAY($a, $b RETURNING JSON), '\$[*][*]' RETURNING JSON WITH ARRAY WRAPPER) " +
            "WHEN ${kind(a)} = 'array' THEN JSON_TRANSFORM($a, APPEND '\$' = $b) " +
            "WHEN ${kind(b)} = 'array' THEN JSON_TRANSFORM($b, INSERT '\$[0]' = $a) " +
            "WHEN ${typeOf(a)} IN ('string', 'number') AND ${typeOf(b)} IN ('string', 'number') THEN JSON_SCALAR(${summedText(a, given)} || ${summedText(b, given.second to given.first)}) END"

    /** The side [json] of a `+` whose sides the statement gives as [given], this side first, as text: see [summedText]. */
    private fun summedText(json: String, given: Pair<Any?, Any?>) = summedText(json, given.first, given.second, { "JSON_VALUE($it, '\$')" }, ::asText)

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
     * By a function the query declares (see [reduce]), which walks the two together: Oracle's recursive queries take no `JSON`.
     * A procedure's queries call no function it declares, nor do a `reduce`'s step's: there, two values equal as JSON are
     * null, as each holds a null, and any other two false, as before null was compared item by item: `[null] = [1]` is false
     * there, where Cypher's is null.
     */
    override fun nullsEqual(x: String, y: String, equals: (String, String) -> String): String {
        if (procedural || folding) return "CASE WHEN ${equals(x, y)} THEN NULL ELSE FALSE END"
        if (!nullsCompared) {
            nullsCompared = true
            declare(
                listOf(
                    "FUNCTION $NULLS_EQUAL(cy_x JSON, cy_y JSON) RETURN NUMBER IS",
                    "    FUNCTION eq(a JSON_ELEMENT_T, b JSON_ELEMENT_T) RETURN NUMBER IS",
                    "        r NUMBER := 1;",
                    "        e NUMBER;",
                    "        la JSON_ARRAY_T;",
                    "        lb JSON_ARRAY_T;",
                    "        oa JSON_OBJECT_T;",
                    "        ob JSON_OBJECT_T;",
                    "        ks JSON_KEY_LIST;",
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
                    "        IF a.is_String AND b.is_String THEN RETURN CASE WHEN a.to_String = b.to_String THEN 1 ELSE 0 END; END IF;",
                    "        IF a.is_Boolean AND b.is_Boolean THEN RETURN CASE WHEN a.to_Boolean = b.to_Boolean THEN 1 ELSE 0 END; END IF;",
                    "        RETURN 0;",
                    "    END;",
                    "BEGIN",
                    "    RETURN eq(JSON_ELEMENT_T.parse(JSON_SERIALIZE(cy_x RETURNING CLOB)), JSON_ELEMENT_T.parse(JSON_SERIALIZE(cy_y RETURNING CLOB)));",
                    "END;",
                ).joinToString("\n")
            )
        }
        return "($NULLS_EQUAL($x, $y) = 1)"
    }

    /** Whether the query declares [NULLS_EQUAL]: see [nullsEqual]. */
    private var nullsCompared = false

    override fun modulo(a: String, b: String) = "MOD($a, $b)"

    override fun startsWith(a: String, b: String) = shared(b) { (x) -> "(SUBSTR($a, 1, LENGTH($x)) = $x)" }

    override fun endsWith(a: String, b: String) = shared(b) { (x) -> "(SUBSTR($a, -LENGTH($x)) = $x)" }

    override fun containsText(a: String, b: String) = "(INSTR($a, $b) > 0)"

    override fun function(name: String, json: String): String? = when (name) {
        "size" -> "CASE ${kind(json)} WHEN 'array' THEN JSON_SCALAR(JSON_VALUE($json, '\$.size()' RETURNING NUMBER)) WHEN 'string' THEN JSON_SCALAR(LENGTH(JSON_VALUE($json, '\$'))) END"
        "tolower" -> "JSON_SCALAR(LOWER(${text(json)}))"
        "toupper" -> "JSON_SCALAR(UPPER(${text(json)}))"
        "trim" -> "JSON_SCALAR(TRIM(${text(json)}))"
        "tostring" -> "JSON_SCALAR(${asText(json)})"
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

    /** The text as JSON writes it, with each separator as JSON writes it made the end of one string and the start of the next. */
    override fun split(text: String, separator: String) = shared(text, separator) { (t, s) ->
        val written = "JSON_SERIALIZE(JSON_SCALAR($t))"
        val separatorWritten = "SUBSTR(JSON_SERIALIZE(JSON_SCALAR($s)), 2, LENGTH(JSON_SERIALIZE(JSON_SCALAR($s))) - 2)"
        "CASE WHEN $t IS NOT NULL AND $s IS NOT NULL THEN JSON('[' || REPLACE($written, $separatorWritten, '\",\"') || ']') END"
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

    override fun roundPlaces(number: String, precision: String) = pastItsDigits(number, precision) { n, p -> super.roundPlaces(n, p) }

    override fun roundTo(number: String, precision: String, mode: RoundingMode) = pastItsDigits(number, precision) { n, p -> super.roundTo(n, p, mode) }

    /**
     * [round] of [number] to [precision] places, or [number] itself where those places are past its digits: Oracle's NUMBER
     * stops near 10^126, so `ROUND(n, 400)`, and the scale `10^p` a mode rounds by, overflow (ORA-01426) where Neo4j gives
     * the number back. Neo4j rounds a double, of [DOUBLE_DIGITS] significant digits at most, so rounding it to places past
     * its last digit changes nothing; a number Oracle holds to more digits is a double again once rounded. Oracle works
     * out only the branch of a CASE a row takes, so the overflowing one is never reached. Short of its digits, a tiny number
     * may still take more than [MOST_PLACES] places, as 1.0E-120 to 130 does: it is rounded moved that many places fewer
     * to the right, then moved back, which is exact in decimal.
     */
    private fun pastItsDigits(number: String, precision: String, round: (String, String) -> String) = shared(number, precision) { (n, p) ->
        val shift = "POWER(10, GREATEST($p - $MOST_PLACES, 0))"
        "CASE WHEN $n = 0 OR $p >= $DOUBLE_DIGITS - FLOOR(LOG(10, ABS($n))) THEN $n ELSE ${round("($n * $shift)", "($p - GREATEST($p - $MOST_PLACES, 0))")} / $shift END"
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

    private companion object {
        /** The cursor a procedure returns its rows by. */
        const val CURSOR = "cy_cursor"

        /** The key of a double in Oracle's extended JSON. */
        const val DOUBLE = "\$numberDouble"

        /** The function a query declares that compares two values that may hold a null: see [nullsEqual]. */
        const val NULLS_EQUAL = "cy_nulls_equal"

        /** How many significant digits a double holds at most, as Neo4j rounds one. */
        const val DOUBLE_DIGITS = 17

        /** The most places [pastItsDigits] rounds to, whose scale `10^p` is well inside the NUMBER range. */
        const val MOST_PLACES = 100
    }
}
