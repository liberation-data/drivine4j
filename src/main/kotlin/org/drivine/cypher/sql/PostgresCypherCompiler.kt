package org.drivine.cypher.sql

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.DecimalNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.drivine.cypher.ast.RelationshipPattern
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * Compiles a Cypher statement to SQL over a graph's tables on Postgres: see [SqlCypherCompiler].
 *
 * A value is `jsonb`, which compares, groups and orders as JSON. A map or a list the statement builds is
 * `json`, which keeps its keys in the order written, and is cast to `jsonb` where it is compared. A list
 * of ids is an array of `bigint`. A statement that writes carries its rows as `jsonb`, so a map carried
 * past a write has its keys in `jsonb`'s order.
 */
class PostgresCypherCompiler(graph: String) : SqlCypherCompiler(graph) {

    override fun literalJson(value: Any?) = "CAST(${literal(written(value))} AS jsonb)"

    override fun parameterJson(marker: String, text: String?) = "CAST($marker AS jsonb)"

    override fun parameterText(value: Any?): String = written(value)

    /**
     * [value] as JSON text, each float in it that is a whole number written out in full with a fraction, `10000000.0`: as
     * Java writes it, `1.0E7`, `jsonb` reads it as the whole number 10000000, which is then read back as an integer.
     */
    private fun written(value: Any?): String {
        val tree = Neo4jObjectMapper.instance.valueToTree<JsonNode>(value)
        return Neo4jObjectMapper.instance.writer().with(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN).writeValueAsString(wholeFloats(tree))
    }

    /**
     * [node] with each float in it that is a whole number a decimal of one place, `10000000.0`, from its shortest decimal: the
     * double's exact value, `999999999999999983222784.0` for `1e24`, is no number Postgres works out or reads as that float.
     */
    private fun wholeFloats(node: JsonNode): JsonNode = when {
        (node.isDouble || node.isFloat) && node.doubleValue().let { it.isFinite() && it == Math.rint(it) } ->
            DecimalNode(BigDecimal.valueOf(node.doubleValue()).setScale(1))
        node is ObjectNode -> node.apply { properties().toList().forEach { (key, value) -> set<JsonNode>(key, wholeFloats(value)) } }
        node is ArrayNode -> node.apply { (0 until size()).forEach { set(it, wholeFloats(get(it))) } }
        else -> node
    }

    override fun parameterCount(marker: String) = "CAST($marker AS bigint)"

    override fun comparable(value: Value.Json) = if (value.ordered) "CAST(${value.sql} AS jsonb)" else value.sql

    override fun scalar(sql: String) = "to_jsonb($sql)"

    override fun typeOf(json: String) = "jsonb_typeof($json)"

    override fun isJsonNull(json: String) = "COALESCE(jsonb_typeof($json), 'null') = 'null'"

    override fun nullIfJsonNull(json: String) = "NULLIF($json, 'null'::jsonb)"

    override fun number(json: String) = shared(json) { (x) -> "CASE WHEN jsonb_typeof($x) = 'number' THEN CAST($x #>> '{}' AS numeric) END" }

    override fun integral(json: String) = shared(json) { (x) -> "(jsonb_typeof($x) = 'number' AND ($x #>> '{}') ~ '^-?[0-9]+$')" }

    override fun text(json: String) = shared(json) { (x) -> "CASE WHEN jsonb_typeof($x) = 'string' THEN $x #>> '{}' END" }

    override fun boolOf(json: String) = shared(json) { (x) -> "CASE WHEN jsonb_typeof($x) = 'boolean' THEN CAST($x AS boolean) END" }

    /**
     * A `numeric` with a fraction, so a whole one is written `2.0`, as a graph engine writes a float, and stays one in arithmetic;
     * without the zeros its scale leaves after its digits (`trim_scale`), so `avg` of whole numbers is `1999.0`, not
     * `1999.0000000000000000`.
     */
    override fun float(sql: String) = "(trim_scale(CAST($sql AS numeric)) + 0.0)"

    /**
     * As any store works it out, in `numeric`, and a quotient without the zeros `numeric`'s division leaves after its digits:
     * `4.0 / 2` is `2.0`, not `2.0000000000000000`. Postgres works a float out exactly, so a result may differ from a double's
     * in the last digits: `0.1 * 3` is `0.3`, where Cypher gives `0.30000000000000004` (#188).
     */
    override fun arithmetic(a: String, b: String, operator: String) =
        if (operator == "/") scalar(float("(${number(a)} / ${number(b)})")) else super.arithmetic(a, b, operator)

    override fun jsonArray(items: List<String>) = spliced(items, MOST_ARGUMENTS) { "json_build_array(${it.joinToString(", ")})" }

    override fun jsonObject(entries: List<Pair<String, String>>) =
        spliced(entries, MOST_ARGUMENTS / 2) { part -> "json_build_object(${part.joinToString(", ") { (key, value) -> "${literal(key)}, $value" }})" }

    /**
     * What [build] writes of [items], where they are at most [most]; past them, as Postgres takes no more than 100 arguments
     * to a function (#332), what it writes of each part of them, spliced as text into the text one call would write of
     * them all: the first part without its closing bracket, the last without its opening one, and those between without
     * either, joined as `json_build_array` and `json_build_object` join their items. It stays `json`, so the keys of a map
     * keep their order, where `jsonb`'s `||` would sort them.
     */
    private fun <T> spliced(items: List<T>, most: Int, build: (List<T>) -> String): String {
        if (items.size <= most) return build(items)
        val parts = items.chunked(most)
        return parts.withIndex().joinToString(" || ', ' || ", "CAST(", " AS json)") { (i, part) ->
            val text = "CAST(${build(part)} AS text)"
            when (i) {
                0 -> "left($text, -1)"
                parts.lastIndex -> "right($text, -1)"
                else -> "right(left($text, -1), -1)"
            }
        }
    }

    override fun withEntries(json: String, entries: List<Pair<String, String>>) = "($json || CAST(${jsonObject(entries)} AS jsonb))"

    override fun propertyOf(json: String, key: String) = "($json -> ${literal(key)})"

    override fun element(json: String, index: String) = "($json -> CAST($index #>> '{}' AS int))"

    override fun keyed(json: String, key: String) = "$json -> ($key #>> '{}')"

    override fun containsAll(props: String, map: String) = "$props @> $map"

    override fun hasLabel(labels: String, label: String) = "$labels @> ${literal(Neo4jObjectMapper.instance.writeValueAsString(listOf(label)))}"

    /** The property's text, as a range index or a uniqueness constraint reads it with `->>`, compared with the value's. */
    override fun indexedEquality(property: String, value: String): String? =
        PROPERTY_READ.matchEntire(property)?.let { "(${it.groupValues[1]} ->> ${it.groupValues[2]}) = ($value #>> '{}')" }

    /** The JSON [json]'s text, where it is a text: a property's read with `->>`, which costs less than `#>>`. */
    private fun textOf(json: String) = PROPERTY_READ.matchEntire(json)?.let { "(${it.groupValues[1]} ->> ${it.groupValues[2]})" } ?: "($json #>> '{}')"

    override fun elements(json: String, alias: String) = "jsonb_array_elements($json) WITH ORDINALITY $alias (item, item_no)"

    override fun itemAt(json: String, index: Int) = "($json -> $index)"

    override fun listSize(json: String) = "jsonb_array_length($json)"

    override fun holdsNull(json: String) = "jsonb_path_exists($json, '$.** ? (@ == null)')"

    /**
     * The pairs of [x]'s and [y]'s items, and of their items', at any depth, walked together: a list's by place, a map's by key, where
     * the two are lists or maps both. A place or a key that one has and the other has not pairs a value with SQL's null. Each
     * one's items and keys are arrays unnested, as [listKey]'s are, so the planner's estimate brings in no JIT.
     */
    override fun nullsEqual(x: String, y: String, equals: (String, String) -> String): String {
        fun entries(json: String, alias: String) =
            "SELECT CAST(${alias}e.n AS text) AS k, ${alias}e.v FROM unnest(ARRAY(SELECT jsonb_array_elements(CASE WHEN jsonb_typeof($json) = 'array' THEN $json END))) WITH ORDINALITY ${alias}e (v, n) " +
                "UNION ALL SELECT ${alias}o.key, $json -> ${alias}o.key FROM unnest(ARRAY(SELECT jsonb_object_keys(CASE WHEN jsonb_typeof($json) = 'object' THEN $json END))) ${alias}o (key)"
        val pair = "CASE WHEN eqw.a IS NULL OR eqw.b IS NULL THEN FALSE WHEN jsonb_typeof(eqw.a) = 'null' OR jsonb_typeof(eqw.b) = 'null' THEN NULL " +
            "WHEN jsonb_typeof(eqw.a) <> jsonb_typeof(eqw.b) THEN FALSE WHEN jsonb_typeof(eqw.a) IN ('array', 'object') THEN TRUE ELSE ${equals("eqw.a", "eqw.b")} END"
        return "(WITH RECURSIVE eqw (a, b) AS (SELECT $x, $y UNION ALL " +
            "SELECT eqp.a, eqp.b FROM eqw CROSS JOIN LATERAL (SELECT eqa.v AS a, eqb.v AS b FROM (${entries("eqw.a", "eqa")}) eqa FULL JOIN (${entries("eqw.b", "eqb")}) eqb ON eqa.k = eqb.k) eqp " +
            "WHERE jsonb_typeof(eqw.a) = jsonb_typeof(eqw.b) AND jsonb_typeof(eqw.a) IN ('array', 'object')) " +
            "SELECT CASE WHEN bool_or(NOT eqt.v) THEN FALSE WHEN bool_or(eqt.v IS NULL) THEN NULL ELSE TRUE END FROM (SELECT $pair AS v FROM eqw) eqt)"
    }

    override fun aggregateList(value: String, order: String?) = "COALESCE(json_agg($value${order?.let { " ORDER BY $it" }.orEmpty()}), '[]'::json)"

    override fun collect(value: String, order: String?) =
        "COALESCE(json_agg($value${order?.let { " ORDER BY $it" }.orEmpty()}) FILTER (WHERE $value IS NOT NULL), '[]'::json)"

    override fun joinedTexts(text: String, order: String) = "string_agg($text, '' ORDER BY $order)"

    override fun distinctItems(list: String, temporal: Boolean) =
        "(SELECT COALESCE(json_agg(d.item ORDER BY d.first), '[]'::json) FROM (SELECT min(e.item_no) AS first, e.item FROM ${elements(list, "e")} GROUP BY e.item) d)"

    override fun firstOfEach(list: String, identities: String) =
        "(SELECT ${aggregateList("v.item", "v.item_no")} FROM ${elements(list, "v")} " +
            "JOIN (SELECT min(e.item_no) AS first FROM ${elements(identities, "e")} GROUP BY e.item) f ON f.first = v.item_no)"

    /**
     * Postgres has no min or max of jsonb, which orders: the first of the values in order is the least. Texts are first ordered
     * by their code points ([codePointText]), as Cypher orders them, where `jsonb` orders them by the database's collation.
     */
    override fun extreme(value: String, min: Boolean, key: String?): String {
        val direction = if (min) "ASC" else "DESC"
        val order = listOfNotNull(kindOrder(value), codePointText(value).takeIf { key == null }, key ?: value).joinToString(", ") { "$it $direction" }
        return "(array_agg($value ORDER BY $order) FILTER (WHERE COALESCE(jsonb_typeof($value), 'null') <> 'null'))[1]"
    }

    /**
     * The JSON [json]'s text, where it is one, by its code points, as Cypher compares texts: `jsonb`, and text of the database's
     * collation, compare them as the locale does (`'a' < 'B'`, and `'John Wick'` after `'Johnny Mnemonic'`). The `C` collation
     * compares a text's bytes in the database's encoding: in a UTF-8 database, as `initdb` and Postgres's images make one, they
     * order as its code points do; in another, as that encoding's bytes do. Null where [json] is not a text.
     */
    private fun codePointText(json: String) = "(CASE WHEN jsonb_typeof($json) = 'string' THEN ${textOf(json)} END) COLLATE \"C\""

    override fun bytewise(text: String) = "($text) COLLATE \"C\""

    /** Texts by their code points ([codePointText]); values of any other kind as `jsonb` compares them. */
    override fun ordered(x: String, operator: String, y: String) =
        "CASE WHEN jsonb_typeof($x) = 'string' THEN ${textOf(x)} COLLATE \"C\" $operator ${textOf(y)} ELSE $x $operator $y END"

    /**
     * The pairs of [x]'s and [y]'s items, and of their items' where both are lists, at any depth, walked together by place, as
     * [nullsEqual] walks them, each with its place, a list of where it is in each list it is in. A place that one list has
     * and the other has not pairs an item with SQL's null: the list without it is the shorter. The pair first by place that is
     * not equal decides; none, the two are equal.
     */
    override fun listOrder(x: String, y: String): String {
        fun items(json: String, alias: String) = "SELECT ${alias}e.v, ${alias}e.n FROM unnest(ARRAY(SELECT jsonb_array_elements($json))) WITH ORDINALITY ${alias}e (v, n)"
        val kind = "jsonb_typeof(low.a)"
        val unequal = "low.a IS NULL OR low.b IS NULL OR $kind <> jsonb_typeof(low.b) OR $kind = 'null' " +
            "OR $kind <> 'array' AND (low.a <> low.b OR $kind = 'object' AND ${holdsNull("low.a")})"
        val order = "CASE WHEN low.a IS NULL THEN -1 WHEN low.b IS NULL THEN 1 WHEN $kind <> jsonb_typeof(low.b) OR $kind NOT IN ('number', 'string', 'boolean') THEN NULL " +
            "WHEN ${ordered("low.a", "<", "low.b")} THEN -1 ELSE 1 END"
        return "(WITH RECURSIVE low (a, b, place) AS (SELECT $x, $y, CAST(ARRAY[] AS bigint[]) UNION ALL " +
            "SELECT lop.a, lop.b, low.place || lop.n FROM low CROSS JOIN LATERAL (SELECT loa.v AS a, lob.v AS b, COALESCE(loa.n, lob.n) AS n " +
            "FROM (${items("low.a", "loa")}) loa FULL JOIN (${items("low.b", "lob")}) lob ON loa.n = lob.n) lop " +
            "WHERE jsonb_typeof(low.a) = 'array' AND jsonb_typeof(low.b) = 'array') " +
            "SELECT CASE WHEN COUNT(*) = 0 THEN 0 ELSE MIN(los.o) END FROM (SELECT $order AS o FROM low WHERE $unequal ORDER BY low.place LIMIT 1) los)"
    }

    /**
     * A value as `jsonb` orders it, but a text after the other texts by its code points ([codePointText]): a row of the value, each
     * text in it the empty text, which comes where texts come among the other kinds, then its text by its code points, null for
     * any other kind. A row of nulls, a null's, comes after every other ascending, and before them descending, as null does.
     */
    override fun sortKey(json: String) = shared(json) { (x) ->
        "ROW(CASE WHEN jsonb_typeof($x) = 'string' THEN '\"\"'::jsonb ELSE ${nullIfJsonNull(x)} END, ${codePointText(x)})"
    }

    override fun add(a: String, b: String, given: Pair<Any?, Any?>) =
        "CASE WHEN jsonb_typeof($a) = 'number' AND jsonb_typeof($b) = 'number' THEN to_jsonb(${number(a)} + ${number(b)}) " +
            "WHEN jsonb_typeof($a) = 'array' AND jsonb_typeof($b) = 'array' THEN $a || $b " +
            "WHEN jsonb_typeof($a) = 'array' THEN $a || jsonb_build_array($b) " +
            "WHEN jsonb_typeof($b) = 'array' THEN jsonb_build_array($a) || $b " +
            "WHEN jsonb_typeof($a) IN ('string', 'number') AND jsonb_typeof($b) IN ('string', 'number') THEN to_jsonb(${summedText(a, given)} || ${summedText(b, given.second to given.first)}) END"

    /** The side [json] of a `+` whose sides the statement gives as [given], this side first, as text: see [summedText]. */
    private fun summedText(json: String, given: Pair<Any?, Any?>) = summedText(json, given.first, given.second, { "($it #>> '{}')" }, ::asText)

    /**
     * The JSON [json], a text, a number or a truth value, as `toString` gives it: a float, a `numeric` with a fraction, without
     * the zeros `numeric`'s scale leaves after its digits (`trim_scale`), as Java writes a double: with no exponent from 10^-3 up
     * to 10^7, a point and a digit after it at least, `2.0`, and else as `1.0E7`, `1.2E-4`. Postgres works a float out in
     * `numeric`, exactly, so its digits may differ from a double's in the last places: `0.1 * 3` is `0.3`.
     */
    private fun asText(json: String) = shared(json) { (x) ->
        val exact = "trim_scale(CAST($x #>> '{}' AS numeric))"
        "CASE WHEN jsonb_typeof($x) = 'number' AND NOT ${integral(x)} THEN ${shared(exact) { (n) -> floatText(n) }} ELSE $x #>> '{}' END"
    }

    /** The `numeric` [n], a float without trailing zeros, as Java writes a double: see [asText]. */
    private fun floatText(n: String): String {
        // `to_char` writes 17 digits and the exponent, ` 1.0000000000000000e+07`: the digits without the zeros after them, a point and one at least.
        val scientific = "to_char($n, '9.9999999999999999EEEE')"
        val mantissa = "regexp_replace(rtrim(btrim(split_part($scientific, 'e', 1)), '0'), '\\.$', '.0')"
        return "CASE WHEN $n = 0 THEN '0.0' WHEN abs($n) >= 0.001 AND abs($n) < 10000000 THEN to_char($n, '$PLAIN_FLOAT') " +
            "ELSE $mantissa || 'E' || CAST(split_part($scientific, 'e', 2) AS int) END"
    }

    /**
     * A list as `jsonb` that Postgres orders as Cypher orders lists, item by item: each item, from the first, a list of three, of
     * where its kind comes ([itemRank]), the item (a text by its code points, as [codePoints] writes it), and what follows it so
     * written, the end an empty list. `jsonb` orders the
     * shorter of two lists first, and every list here but the last is of three. An item that is a list is followed by its own
     * items, at any depth, then by its end, which comes before any item. Any other value sorts as itself, as before. Only a
     * value the statement makes a list sorts so: the planner costs the walk at thousands a row, which brings in its JIT for a
     * sort of more than a few dozen rows.
     */
    override fun listSortKey(json: String) = shared(json) { (x) ->
        "CASE WHEN jsonb_typeof($x) = 'array' THEN CAST(${listKey(x)} AS jsonb) ELSE ${nullIfJsonNull(x)} END"
    }

    /**
     * The list [list] as [sortKey] writes it, as text: its items and the items of each that is a list, all the way down, by
     * their places, each list held in it as its kind, then its items, then its end, ranked 0.
     */
    private fun listKey(list: String): String {
        // Each list's items as an array unnested: the planner takes an array it cannot see for 10 items, and a set-returning
        // function's for 100, which it multiplies through the walk, to a cost 150 times as high.
        fun items(json: String) = "unnest(ARRAY(SELECT jsonb_array_elements($json)))"
        val items = "SELECT ARRAY[ke.item_no] AS place, ke.item FROM ${items(list)} WITH ORDINALITY ke (item, item_no) " +
            "UNION ALL SELECT kn.place || kc.item_no, kc.item FROM kn, " +
            "${items("CASE WHEN jsonb_typeof(kn.item) = 'array' THEN kn.item ELSE '[]'::jsonb END")} WITH ORDINALITY kc (item, item_no)"
        val ends = "SELECT kn.place || CAST(jsonb_array_length(kn.item) + 1 AS bigint), 0, 'null' FROM kn WHERE jsonb_typeof(kn.item) = 'array'"
        val tokens = "SELECT kn.place, ${itemRank("kn.item")} AS rank, CASE jsonb_typeof(kn.item) WHEN 'array' THEN 'null' " +
            "WHEN 'string' THEN ${codePoints("(kn.item #>> '{}')")} ELSE CAST(kn.item AS text) END AS item FROM kn UNION ALL $ends"
        return "(WITH RECURSIVE kn (place, item) AS ($items) " +
            "SELECT COALESCE(string_agg('[' || kt.rank || ', ' || kt.item || ', ', '' ORDER BY kt.place), '') || '[]' || repeat(']', COUNT(*)::int) FROM ($tokens) kt)"
    }

    /**
     * The text [text] as JSON text that `jsonb` orders as Cypher orders texts, by their code points, where `jsonb` orders a text by
     * the database's collation: a list of a number and what follows it. The number is 0, a point, and the bits of the UTF-8 bytes
     * of the text's first [CODE_POINT_PREFIX] characters, which order as its code points do; a text that is the start of another
     * comes first, as the other's next byte has a bit set. What follows is an empty list, or one of the rest of the text, which
     * `jsonb` orders by the collation: past [CODE_POINT_PREFIX] characters, where a number would have more places than Postgres
     * holds. Of strings, not a subquery a character: the planner costs a subquery for each item, and so brings in its JIT for
     * the sort, and `jsonb` compares lists nested a character deep ten times slower.
     */
    private fun codePoints(text: String): String {
        val bits = "CAST(CAST('x' || encode(convert_to(left($text, $CODE_POINT_PREFIX), 'UTF8'), 'hex') AS varbit) AS text)"
        return "'[' || CASE WHEN $text = '' THEN '0' ELSE '0.' || $bits END || ', ' || " +
            "CASE WHEN length($text) > $CODE_POINT_PREFIX THEN '[' || CAST(to_jsonb(substr($text, ${CODE_POINT_PREFIX + 1})) AS text) || ']' ELSE '[]' END || ']'"
    }

    override fun idTokens(ids: String, key: String) = "CAST(regexp_replace(CAST($ids AS text), '(-?[0-9]+)', ${literal("{\"$key\": \\1}")}, 'g') AS jsonb)"

    override fun modulo(a: String, b: String) = "($a % $b)"

    override fun startsWith(a: String, b: String) = "starts_with($a, $b)"

    override fun endsWith(a: String, b: String) = shared(b) { (x) -> "right($a, length($x)) = $x" }

    override fun containsText(a: String, b: String) = "strpos($a, $b) > 0"

    override fun function(name: String, json: String): String? = when (name) {
        "keys" -> shared(json) { (x) -> "CASE WHEN jsonb_typeof($x) = 'object' THEN (SELECT COALESCE(jsonb_agg(k ORDER BY k), '[]'::jsonb) FROM jsonb_object_keys($x) k) END" }
        "size" -> "CASE jsonb_typeof($json) WHEN 'array' THEN to_jsonb(jsonb_array_length($json)) WHEN 'string' THEN to_jsonb(length($json #>> '{}')) END"
        "tolower" -> "to_jsonb(lower(${text(json)}))"
        "toupper" -> "to_jsonb(upper(${text(json)}))"
        "trim" -> "to_jsonb(btrim(${text(json)}))"
        "tostring" -> "to_jsonb(${asText(json)})"
        "tointeger" -> "to_jsonb(trunc(${numeric(json)}))"
        "tofloat" -> "to_jsonb(${float(numeric(json))})"
        "head" -> "($json -> 0)"
        "last" -> "($json -> -1)"
        "abs" -> "to_jsonb(abs(${number(json)}))"
        else -> null
    }

    /** The JSON [json] as a `numeric`, where it is a number or a text that writes one: null for any other text, as Cypher converts it. */
    private fun numeric(json: String) = shared(json) { (x) ->
        "CASE WHEN jsonb_typeof($x) = 'number' OR ($x #>> '{}') ~ ${literal(NUMERIC_TEXT)} THEN CAST($x #>> '{}' AS numeric) END"
    }

    override fun page(skip: String?, limit: String?) = listOfNotNull(skip?.let { "OFFSET $it" }, limit?.let { "LIMIT $it" })

    override fun ids(id: String) = "ARRAY[$id]"

    override fun joinIds(lists: List<String>) = lists.joinToString(" || ", "(", ")")

    override fun storeIds(json: String) = shared(json) { (ids) ->
        "CASE WHEN $ids IS NOT NULL THEN ARRAY(SELECT CAST(x.id AS bigint) FROM jsonb_array_elements_text($ids) WITH ORDINALITY x (id, ord) ORDER BY x.ord) END"
    }

    override val noIds = "CAST(ARRAY[] AS bigint[])"

    override fun idIn(id: String, list: String) = "$id = ANY ($list)"

    override fun idsOverlap(a: String, b: String) = "$a && $b"

    override fun idCount(list: String) = "to_jsonb(cardinality($list))"

    override fun idRows(list: String, alias: String) = "unnest($list) WITH ORDINALITY $alias (id, ord)"

    /** A lateral recursive query from the node walked from, which reads its row. */
    override fun walk(walk: Walk): Walked {
        val w = walk.step
        val (joins, reached, distinct) = steps(walk, "$w.nid")
        val eids = walk.hops.map { "${it.edge}.eid" }
        val conditions = eids.map { "$it <> ALL ($w.eids)" } + distinct + listOfNotNull(walk.max?.let { "$w.depth < $it" }) + walk.conditions
        val text = listOf(
            "WITH RECURSIVE $w (nid, depth, nids, eids) AS (",
            "    SELECT ${walk.from}, 0, $noIds, $noIds",
            "    UNION ALL",
            // Walked backwards, each node it leaves goes first, so its nodes end with the one it started from.
            if (walk.backwards) "    SELECT ${reached.single()}, $w.depth + 1, $w.nid || $w.nids, ${eids.single()} || $w.eids"
            else "    SELECT ${reached.last()}, $w.depth + 1, $w.nids || ARRAY[${reached.joinToString(", ")}], $w.eids || ARRAY[${eids.joinToString(", ")}]",
            "    FROM $w",
            joins.joinToString("\n") { "    $it" },
            "    WHERE ${conditions.joinToString(" AND ")}",
            ")",
            "SELECT nid, nids, eids FROM $w WHERE depth >= ${walk.min}",
        ).joinToString("\n")
        return Walked("LATERAL (\n${indent(text)}\n) ${walk.name}", "${walk.name}.nid = ${walk.to}", "${walk.name}.eids", "${walk.name}.nids")
    }

    override fun nodeJson(node: Value.Node) =
        "CASE WHEN ${node.nid} IS NOT NULL THEN ${node.props} || jsonb_strip_nulls(jsonb_build_object(${literal(Stamps.PROPERTY)}, ${node.stamp})) END"

    /** A recursive query, a row for each item taken, that reads the row it is in, as Postgres's may. */
    override fun reduce(
        name: String,
        initial: String,
        list: String,
        step: (accumulator: String, item: String) -> String,
        context: () -> String,
        contextStep: (accumulator: String, item: String, context: String) -> String,
    ): String = shared(list) { (items) -> fold(name, initial, items, step) }

    private fun fold(name: String, initial: String, list: String, step: (accumulator: String, item: String) -> String): String {
        val text = listOf(
            "WITH RECURSIVE $name (i, acc) AS (",
            "    SELECT 0, CAST($initial AS jsonb)",
            "    UNION ALL",
            "    SELECT $name.i + 1, CAST(${step("$name.acc", "($list -> $name.i)")} AS jsonb) FROM $name WHERE $name.i < jsonb_array_length($list)",
            ")",
            "SELECT acc FROM $name ORDER BY i DESC LIMIT 1",
        ).joinToString("\n")
        return "CASE WHEN jsonb_typeof($list) = 'array' THEN (\n${indent(text)}\n) END"
    }

    override fun range(from: String, to: String, step: String) =
        "(SELECT COALESCE(jsonb_agg(g ORDER BY o), '[]'::jsonb) FROM generate_series(CAST($from AS bigint), CAST($to AS bigint), CAST($step AS bigint)) WITH ORDINALITY r (g, o))"

    override val rowNumber = "ROW_NUMBER() OVER ()"

    /**
     * Postgres pulls a subquery of no table up into the query that reads it, and writes each of its values out
     * again where it is read: values bound in one another so grow exponentially as it plans, and a duration added
     * to a date read from the store took the server's memory. `OFFSET 0` keeps the subquery apart.
     */
    override fun boundSelect(columns: String) = "SELECT $columns OFFSET 0"

    override val temporalSql = PostgresTemporalSql(sharedList)

    /**
     * Flags the pattern opens with, `(?i)`, are put before the anchors, where Postgres reads them, after `p`: a dot
     * matches no line break, as Java's does, unless the pattern says `(?s)`. The anchors are `\A` and `\Z`, the text's
     * start and end, which `(?m)` leaves as they are: it lets the pattern's own `^` and `$` match at a line break.
     * Postgres's `s` and `m` each undo the other, so the two together end in `w`, which keeps both.
     */
    override fun matches(text: String, pattern: String) =
        "($text ~ regexp_replace(regexp_replace($pattern, '^(\\(\\?([a-z]+)\\))?(.*)\$', '(?p\\2)\\\\A(\\3)\\\\Z'), " +
            "'^\\(\\?([a-z]*(s[a-z]*m|m[a-z]*s)[a-z]*)\\)', '(?\\1w)'))"

    /**
     * An empty separator cuts the text at each character, as Cypher's does: `string_to_array` does so for a null one.
     * The empty text is a list of itself, where `string_to_array` gives an empty one.
     */
    override fun split(text: String, separator: String) = shared(text, separator) { (t, s) ->
        "CASE WHEN $s IS NOT NULL THEN CASE WHEN $t = '' THEN jsonb_build_array($t) ELSE to_jsonb(string_to_array($t, NULLIF($s, ''))) END END"
    }

    override fun reverseText(text: String) = "reverse($text)"

    override fun uuid() = "CAST(gen_random_uuid() AS text)"

    /**
     * Worked out as a `double precision`, as Neo4j works it out as a Java double, and written back as the text of that
     * double: a cast to `numeric` would keep 15 of its digits. Postgres fails where Neo4j gives NaN or an infinity,
     * `ln(0)`, `sqrt(-1)`, `asin(2)`, `exp(1000)` and `1 / tan(0)` among them, but for an exponent too small for a double,
     * which Neo4j gives as 0.0, and Postgres would fail on as an underflow.
     */
    override fun math(name: String, numbers: List<String>): String {
        val doubles = numbers.map { "CAST($it AS double precision)" }
        val x = doubles[0]
        val result = when (name) {
            "exp" -> shared(x) { (d) -> "CASE WHEN $d < $EXP_UNDERFLOW THEN 0 ELSE exp($d) END" }
            "log" -> "ln($x)"
            "log10" -> "log($x)"
            "cot" -> "(1 / tan($x))"
            "haversin" -> "((1 - cos($x)) / 2)"
            // By the ratio Java multiplies by, where Postgres divides by its inverse: see [RADIANS_TO_DEGREES].
            "degrees" -> "($x * CAST($RADIANS_TO_DEGREES AS double precision))"
            "atan2" -> "atan2($x, ${doubles[1]})"
            else -> "$name($x)"
        }
        return float("CAST($result AS text)")
    }

    override fun rightText(text: String, count: String) = "right($text, $count)"

    /**
     * A function of the session's own, `pg_temp`, made for the procedure and called: Postgres runs a block
     * that returns rows or takes parameters no other way. It is named for its text, so a statement run
     * again replaces it with itself.
     */
    override fun procedure(program: List<ProcedureStep>): String {
        val body = statements(program)
        val text = (listOf(
            "#variable_conflict use_column",
            "DECLARE",
            "    $ROWS jsonb;",
            "    $OUT jsonb;",
            "    $ROW jsonb;",
            "    $VALUE jsonb;",
            "    $ID bigint;",
            "    $COUNT bigint;",
            "    $FOUND boolean;",
            "    $DOOMED jsonb;",
            "    $RECORD record;",
            "    $MATCHED record;",
        ) + (1..NESTING).flatMap { listOf("    $SAVED$it jsonb;", "    $GATHERED$it jsonb;", "    $CALLER$it jsonb;") } + listOf(
            "BEGIN",
            indent(body.joinToString("\n")),
            "END",
        )).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val function = "pg_temp.drivine_cypher_${digest.take(24)}"
        // The body is quoted with dollars, by a tag no text in it holds: a text of `$cy$` would end it early.
        val quote = (sequenceOf("\$cy\$") + generateSequence(1) { it + 1 }.map { "\$cy$it\$" }).first { it !in text }
        return "CREATE OR REPLACE FUNCTION $function($PARAMS jsonb) RETURNS SETOF json LANGUAGE plpgsql AS $quote\n$text\n$quote;\n" +
            "SELECT cy.result FROM $function(CAST(\$${Parameters.OBJECT} AS jsonb)) AS cy (result)"
    }

    override fun returnRows(query: String) = listOf("RETURN QUERY SELECT CAST(cy_q.result AS json) FROM (\n${indent(query)}\n) cy_q;")

    /**
     * Places past [MOST_PLACES] change no double, the least of which is near 4.9E-324, and Postgres's `numeric` overflows
     * on a precision as large as a billion: so a precision is held to them, as Neo4j gives the number back. A CASE, not
     * `LEAST`, which passes over a null where a null precision rounds to null.
     */
    override fun roundPlaces(number: String, precision: String) = super.roundPlaces(number, heldPlaces(precision))

    override fun roundTo(number: String, precision: String, mode: RoundingMode) = super.roundTo(number, heldPlaces(precision), mode)

    private fun heldPlaces(precision: String) = shared(precision) { (p) -> "CASE WHEN $p > $MOST_PLACES THEN $MOST_PLACES ELSE $p END" }

    /**
     * A text that is no whole number, cast to one: Postgres fails with the text in its message. Joined by `concat`, which
     * is stable, not immutable, so the planner does not work out a failure of constants alone before any row, as it
     * works out an immutable `||` and cast in a branch of a CASE no row takes: `round(1.5, -2)` over no rows.
     */
    override fun failure(message: String, value: String) = "CAST(concat(${literal("$message ")}, $value) AS integer)"

    override fun raise(message: String) = "RAISE EXCEPTION USING MESSAGE = ${literal(message)};"

    override fun idOf(json: String, key: String?) = if (key != null) "CAST($json ->> ${literal(key)} AS bigint)" else "CAST($json #>> '{}' AS bigint)"

    override fun appended(list: String, item: String) = "($list || jsonb_build_array($item))"

    override fun stripNulls(json: String) = "jsonb_strip_nulls(CAST($json AS jsonb))"

    override fun merged(props: String, map: String) = "jsonb_strip_nulls($props || CAST($map AS jsonb))"

    override fun withoutLabel(labels: String, label: String) = "($labels - ${literal(label)})"

    private companion object {
        /** A property read, `(n1.props -> 'name')`: the row's column, and the key. */
        val PROPERTY_READ = Regex("""^\(([\w.]+) -> ('(?:[^']|'')*')\)$""")

        /**
         * The characters of a text in a list that [codePoints] orders by their code points: at most 4 bytes each, 32 bits, which
         * are 16,000 places, and Postgres holds 16,383 after a number's point.
         */
        const val CODE_POINT_PREFIX = 500

        /** The most places `round()` rounds to: past them, a double, the least of which is near 4.9E-324, has no digit. */
        const val MOST_PLACES = 400

        /** A text Postgres reads as a `numeric`, spaces around it allowed. */
        const val NUMERIC_TEXT = """^\s*[+-]?([0-9]+\.?[0-9]*|\.[0-9]+)([eE][+-]?[0-9]+)?\s*$"""

        /**
         * Below it, `exp` is nearer 0 than the least double, half of 2^-1074, and Java gives 0.0: ln(2^-1075) is
         * -745.13321910194120…, and this is the least double Java's `exp` gives more than 0.0 for.
         */
        const val EXP_UNDERFLOW = "-745.1332191019411"
    }
}
