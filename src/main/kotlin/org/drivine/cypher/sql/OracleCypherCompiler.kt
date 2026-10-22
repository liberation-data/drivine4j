package org.drivine.cypher.sql

import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleSql
import org.drivine.cypher.ast.RelationshipPattern
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps

/**
 * Compiles a Cypher statement to SQL over a graph's tables on Oracle: see [SqlCypherCompiler].
 *
 * A value is Oracle's `JSON`, which compares, groups and orders as JSON and keeps a map's keys in the
 * order written. Oracle keeps a number as a number, so a stored `2.0` reads as `2`; a floating-point
 * number the statement writes is a `BINARY_DOUBLE`, and stays one. A text Oracle reads out of JSON is at
 * most 4,000 bytes, and an empty one is null, as Oracle's empty text is.
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

    override fun parameterJson(marker: String) = "JSON($marker)"

    override fun parameterCount(marker: String) = "CAST($marker AS NUMBER)"

    override fun comparable(value: Value.Json) = value.sql

    override fun scalar(sql: String) = "JSON_SCALAR($sql)"

    /** A floating-point number is a number, as JSON has it. */
    override fun typeOf(json: String) = "DECODE(${kind(json)}, 'double', 'number', 'float', 'number', ${kind(json)})"

    override fun isJsonNull(json: String) = "NVL(${kind(json)}, 'null') = 'null'"

    override fun nullIfJsonNull(json: String) = "CASE WHEN ${kind(json)} <> 'null' THEN $json END"

    override fun number(json: String) = "CASE WHEN ${typeOf(json)} = 'number' THEN JSON_VALUE($json, '$' RETURNING NUMBER) END"

    /** A whole number, and no floating-point number, as Cypher has an integer. */
    override fun integral(json: String) =
        "(${kind(json)} = 'number' AND JSON_VALUE($json, '$' RETURNING NUMBER) = TRUNC(JSON_VALUE($json, '$' RETURNING NUMBER)))"

    override fun text(json: String) = "CASE WHEN ${kind(json)} = 'string' THEN JSON_VALUE($json, '$') END"

    override fun boolOf(json: String) = "CASE WHEN ${kind(json)} = 'boolean' THEN JSON_VALUE($json, '$' RETURNING BOOLEAN) END"

    override fun float(sql: String) = "TO_BINARY_DOUBLE($sql)"

    override fun jsonArray(items: List<String>) =
        if (items.isEmpty()) "JSON('[]')" else "JSON_ARRAY(${items.joinToString(", ")} NULL ON NULL RETURNING JSON)"

    override fun jsonObject(entries: List<Pair<String, String>>) =
        if (entries.isEmpty()) "JSON('{}')"
        else "JSON_OBJECT(${entries.joinToString(", ") { (key, value) -> "${literal(key)} VALUE $value" }} NULL ON NULL RETURNING JSON)"

    override fun withEntries(json: String, entries: List<Pair<String, String>>) =
        if (entries.isEmpty()) json else "JSON_TRANSFORM($json, ${entries.joinToString(", ") { (key, value) -> "SET ${OracleSql.path(key)} = $value" }})"

    override fun propertyOf(json: String, key: String) = "JSON_QUERY($json, ${OracleSql.path(key)} RETURNING JSON)"

    /** Counted from the end where negative: a JSON path counts from the start. */
    override fun element(json: String, index: String): String {
        val at = "JSON_VALUE($index, '$' RETURNING NUMBER)"
        return "JSON_QUERY($json, '\$[\$i]' PASSING CASE WHEN $at < 0 THEN JSON_VALUE($json, '\$.size()' RETURNING NUMBER) + $at ELSE $at END AS \"i\" RETURNING JSON)"
    }

    /** A JSON path names a key as it is written, so a key an expression gives is not read. */
    override fun keyed(json: String, key: String): String? = null

    /** A merge of [map] over [props] that changes nothing: each of its entries is there, with an equal value. */
    override fun containsAll(props: String, map: String) = "JSON_EQUAL(JSON_MERGEPATCH($props, $map RETURNING JSON), $props)"

    override fun hasLabel(labels: String, label: String) = "JSON_EXISTS($labels, ${literal("\$[*]?(@ == ${quoted(label)})")})"

    override fun elements(json: String, alias: String) = "JSON_TABLE($json, '\$[*]' COLUMNS (item_no FOR ORDINALITY, item JSON PATH '\$')) $alias"

    override fun aggregateList(value: String, order: String?) =
        "COALESCE(JSON_ARRAYAGG($value${order?.let { " ORDER BY $it" }.orEmpty()} NULL ON NULL RETURNING JSON), JSON('[]'))"

    /**
     * `JSON_ARRAYAGG` leaves nulls out, and takes no `DISTINCT`: distinct values are gathered as their text,
     * which `LISTAGG` keeps once each.
     */
    override fun collect(value: String, distinct: Boolean) =
        if (distinct) "JSON('[' || LISTAGG(DISTINCT JSON_SERIALIZE($value), ',') WITHIN GROUP (ORDER BY JSON_SERIALIZE($value)) || ']')"
        else "COALESCE(JSON_ARRAYAGG($value RETURNING JSON), JSON('[]'))"

    override fun extreme(value: String, min: Boolean) = "${if (min) "MIN" else "MAX"}(${nullIfJsonNull(value)})"

    /** In `BINARY_DOUBLE` where either is one, so a float stays a float, as in Cypher: `NUMBER` keeps no such kind. */
    override fun arithmetic(a: String, b: String, operator: String) =
        "CASE WHEN ${kind(a)} = 'double' OR ${kind(b)} = 'double' " +
            "THEN JSON_SCALAR(TO_BINARY_DOUBLE(${number(a)}) $operator TO_BINARY_DOUBLE(${number(b)})) " +
            "ELSE JSON_SCALAR(${number(a)} $operator ${number(b)}) END"

    override fun add(a: String, b: String) =
        "CASE WHEN ${typeOf(a)} = 'number' AND ${typeOf(b)} = 'number' THEN ${arithmetic(a, b, "+")} " +
            "WHEN ${kind(a)} = 'array' AND ${kind(b)} = 'array' THEN JSON_QUERY(JSON_ARRAY($a, $b RETURNING JSON), '\$[*][*]' RETURNING JSON WITH ARRAY WRAPPER) " +
            "WHEN ${kind(a)} = 'array' THEN JSON_TRANSFORM($a, APPEND '\$' = $b) " +
            "WHEN ${kind(b)} = 'array' THEN JSON_TRANSFORM($b, INSERT '\$[0]' = $a) " +
            "WHEN ${typeOf(a)} IN ('string', 'number') AND ${typeOf(b)} IN ('string', 'number') THEN JSON_SCALAR(JSON_VALUE($a, '\$') || JSON_VALUE($b, '\$')) END"

    override fun modulo(a: String, b: String) = "MOD($a, $b)"

    override fun startsWith(a: String, b: String) = "(SUBSTR($a, 1, LENGTH($b)) = $b)"

    override fun endsWith(a: String, b: String) = "(SUBSTR($a, -LENGTH($b)) = $b)"

    override fun containsText(a: String, b: String) = "(INSTR($a, $b) > 0)"

    override fun function(name: String, json: String): String? = when (name) {
        "size" -> "CASE ${kind(json)} WHEN 'array' THEN JSON_SCALAR(JSON_VALUE($json, '\$.size()' RETURNING NUMBER)) WHEN 'string' THEN JSON_SCALAR(LENGTH(JSON_VALUE($json, '\$'))) END"
        "tolower" -> "JSON_SCALAR(LOWER(${text(json)}))"
        "toupper" -> "JSON_SCALAR(UPPER(${text(json)}))"
        "trim" -> "JSON_SCALAR(TRIM(${text(json)}))"
        "tostring" -> "JSON_SCALAR(JSON_VALUE($json, '\$'))"
        "tointeger" -> "JSON_SCALAR(TRUNC(TO_NUMBER(JSON_VALUE($json, '\$') DEFAULT NULL ON CONVERSION ERROR)))"
        "tofloat" -> "JSON_SCALAR(TO_BINARY_DOUBLE(JSON_VALUE($json, '\$') DEFAULT NULL ON CONVERSION ERROR))"
        "head" -> "JSON_QUERY($json, '\$[0]' RETURNING JSON)"
        "last" -> "JSON_QUERY($json, '\$[last]' RETURNING JSON)"
        "abs" -> "JSON_SCALAR(ABS(${number(json)}))"
        "keys" -> throw DrivineException("keys() does not run on Oracle: its JSON paths read no map's keys.")
        else -> null
    }

    override fun page(skip: String?, limit: String?) = listOfNotNull(skip?.let { "OFFSET $it ROWS" }, limit?.let { "FETCH NEXT $it ROWS ONLY" })

    override fun ids(id: String) = "(',' || $id || ',')"

    override fun joinIds(lists: List<String>) = "(${lists.first()}${lists.drop(1).joinToString("") { " || SUBSTR($it, 2)" }})"

    override val noIds = "','"

    override fun idIn(id: String, list: String) = "INSTR($list, ',' || $id || ',') > 0"

    override fun idsOverlap(a: String, b: String) = "EXISTS (SELECT 1 FROM ${idRows(a, "ov")} WHERE INSTR($b, ',' || ov.id || ',') > 0)"

    override fun idCount(list: String) = "JSON_SCALAR(REGEXP_COUNT($list, ',') - 1)"

    override fun idRows(list: String, alias: String) =
        "JSON_TABLE(JSON('[' || TRIM(BOTH ',' FROM $list) || ']'), '\$[*]' COLUMNS (ord FOR ORDINALITY, id NUMBER PATH '\$')) $alias"

    /** A recursive query from each node that meets the seed, each row carrying the node it started from. */
    override fun walk(walk: Walk): Walked {
        val w = walk.step
        val e = walk.edge
        val n = "${walk.name}_n"
        val (join, far) = when (walk.direction) {
            RelationshipPattern.Direction.OUTGOING -> "$e.src = $w.nid" to "$e.dst"
            RelationshipPattern.Direction.INCOMING -> "$e.dst = $w.nid" to "$e.src"
            // Either way, and a relationship from a node to itself once.
            RelationshipPattern.Direction.EITHER -> "($e.src = $w.nid OR $e.dst = $w.nid)" to "CASE WHEN $e.src = $w.nid THEN $e.dst ELSE $e.src END"
        }
        val conditions = listOf("INSTR($w.eids, ',' || $e.eid || ',') = 0") + listOfNotNull(walk.max?.let { "$w.depth < $it" }) + walk.conditions
        val text = listOf(
            "WITH $w (src, nid, depth, nids, eids) AS (",
            "    SELECT $n.nid, $n.nid, 0, CAST(',' AS VARCHAR2(4000)), CAST(',' AS VARCHAR2(4000))",
            "    FROM $nodes $n${walk.seed?.let { " WHERE ${it(n)}" }.orEmpty()}",
            "    UNION ALL",
            // Walked backwards, each node it leaves goes first, so its nodes end with the one it started from.
            if (walk.backwards) "    SELECT $w.src, $far, $w.depth + 1, ',' || $w.nid || $w.nids, ',' || $e.eid || $w.eids"
            else "    SELECT $w.src, $far, $w.depth + 1, $w.nids || $far || ',', $w.eids || $e.eid || ','",
            "    FROM $w JOIN $edges $e ON $join",
            "    WHERE ${conditions.joinToString(" AND ")}",
            ")",
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

    override fun epochMillisNow(): String {
        val since = "(SYS_EXTRACT_UTC(SYSTIMESTAMP) - TIMESTAMP '1970-01-01 00:00:00')"
        return "JSON_SCALAR(TRUNC((EXTRACT(DAY FROM $since) * 86400 + EXTRACT(HOUR FROM $since) * 3600 + " +
            "EXTRACT(MINUTE FROM $since) * 60 + EXTRACT(SECOND FROM $since)) * 1000))"
    }

    override fun isoNow() = "JSON_SCALAR(TO_CHAR(SYS_EXTRACT_UTC(SYSTIMESTAMP), 'YYYY-MM-DD\"T\"HH24:MI:SS.FF3\"Z\"'))"

    /** An anonymous block, which returns its rows as an implicit result. */
    override fun procedure(body: List<String>) = listOf(
        "DECLARE",
        "    $PARAMS JSON := JSON(\$${Parameters.OBJECT});",
        "    $ROWS JSON;",
        "    $OUT JSON;",
        "    $ROW JSON;",
        "    $VALUE JSON;",
        "    $ID NUMBER;",
        "    $COUNT NUMBER;",
        "    $FOUND BOOLEAN;",
        "    $DOOMED JSON;",
        "    $CURSOR SYS_REFCURSOR;",
        "BEGIN",
        indent(body.joinToString("\n")),
        "END;",
    ).joinToString("\n")

    override fun returnRows(query: String) = listOf("OPEN $CURSOR FOR\n${indent(query)};", "DBMS_SQL.RETURN_RESULT($CURSOR);")

    override fun raise(message: String) = "RAISE_APPLICATION_ERROR(-20000, ${literal(message)});"

    override fun idOf(json: String, key: String?) = "JSON_VALUE($json, ${key?.let { OracleSql.path(it) } ?: "'$'"} RETURNING NUMBER)"

    override fun appended(list: String, item: String) = "JSON_TRANSFORM($list, APPEND '\$' = $item)"

    override fun stripNulls(json: String) = "JSON_MERGEPATCH(JSON('{}'), $json RETURNING JSON)"

    override fun merged(props: String, map: String) = "JSON_MERGEPATCH($props, $map RETURNING JSON)"

    override fun withoutLabel(labels: String, label: String) = "JSON_TRANSFORM($labels, REMOVE ${literal("\$[*]?(@ == ${quoted(label)})")})"

    /** The kind of the JSON [json] as Oracle names it, a floating-point number by its own name. */
    private fun kind(json: String) = "JSON_VALUE($json, '\$.type()')"

    /** [text] as JSON writes a string. */
    private fun quoted(text: String) = "\"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private companion object {
        /** The cursor a procedure returns its rows by. */
        const val CURSOR = "cy_cursor"
    }
}
