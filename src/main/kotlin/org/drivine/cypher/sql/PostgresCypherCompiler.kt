package org.drivine.cypher.sql

import org.drivine.cypher.ast.RelationshipPattern
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps

/**
 * Compiles a Cypher statement to SQL over a graph's tables on Postgres: see [SqlCypherCompiler].
 *
 * A value is `jsonb`, which compares, groups and orders as JSON. A map or a list the statement builds is
 * `json`, which keeps its keys in the order written, and is cast to `jsonb` where it is compared. A list
 * of ids is an array of `bigint`.
 */
class PostgresCypherCompiler(graph: String) : SqlCypherCompiler(graph) {

    override fun literalJson(value: Any?) = "CAST(${literal(Neo4jObjectMapper.instance.writeValueAsString(value))} AS jsonb)"

    override fun parameterJson(marker: String) = "CAST($marker AS jsonb)"

    override fun parameterCount(marker: String) = "CAST($marker AS bigint)"

    override fun comparable(value: Value.Json) = if (value.ordered) "CAST(${value.sql} AS jsonb)" else value.sql

    override fun scalar(sql: String) = "to_jsonb($sql)"

    override fun typeOf(json: String) = "jsonb_typeof($json)"

    override fun isJsonNull(json: String) = "COALESCE(jsonb_typeof($json), 'null') = 'null'"

    override fun nullIfJsonNull(json: String) = "NULLIF($json, 'null'::jsonb)"

    override fun number(json: String) = "CASE WHEN jsonb_typeof($json) = 'number' THEN CAST($json #>> '{}' AS numeric) END"

    override fun integral(json: String) = "(jsonb_typeof($json) = 'number' AND ($json #>> '{}') ~ '^-?[0-9]+$')"

    override fun text(json: String) = "CASE WHEN jsonb_typeof($json) = 'string' THEN $json #>> '{}' END"

    override fun boolOf(json: String) = "CASE WHEN jsonb_typeof($json) = 'boolean' THEN CAST($json AS boolean) END"

    /** A `numeric` with a fraction, so a whole one is written `2.0`, as a graph engine writes a float, and stays one in arithmetic. */
    override fun float(sql: String) = "(CAST($sql AS numeric) + 0.0)"

    override fun jsonArray(items: List<String>) = "json_build_array(${items.joinToString(", ")})"

    override fun jsonObject(entries: List<Pair<String, String>>) =
        "json_build_object(${entries.joinToString(", ") { (key, value) -> "${literal(key)}, $value" }})"

    override fun withEntries(json: String, entries: List<Pair<String, String>>) = "($json || CAST(${jsonObject(entries)} AS jsonb))"

    override fun propertyOf(json: String, key: String) = "($json -> ${literal(key)})"

    override fun element(json: String, index: String) = "$json -> CAST($index #>> '{}' AS int)"

    override fun keyed(json: String, key: String) = "$json -> ($key #>> '{}')"

    override fun containsAll(props: String, map: String) = "$props @> $map"

    override fun hasLabel(labels: String, label: String) = "$labels @> ${literal(Neo4jObjectMapper.instance.writeValueAsString(listOf(label)))}"

    /** The property's text, as a range index or a uniqueness constraint reads it with `->>`, compared with the value's. */
    override fun indexedEquality(property: String, value: String): String? =
        Regex("""^\(([\w.]+) -> ('(?:[^']|'')*')\)$""").matchEntire(property)?.let { "(${it.groupValues[1]} ->> ${it.groupValues[2]}) = ($value #>> '{}')" }

    override fun elements(json: String, alias: String) = "jsonb_array_elements($json) WITH ORDINALITY $alias (item, item_no)"

    override fun aggregateList(value: String, order: String?) = "COALESCE(json_agg($value${order?.let { " ORDER BY $it" }.orEmpty()}), '[]'::json)"

    override fun collect(value: String, distinct: Boolean) =
        "COALESCE(json_agg(${if (distinct) "DISTINCT " else ""}$value) FILTER (WHERE $value IS NOT NULL), '[]'::json)"

    // Postgres has no min or max of jsonb, which orders: the first of the values in order is the least.
    override fun extreme(value: String, min: Boolean) =
        "(array_agg($value ORDER BY $value ${if (min) "ASC" else "DESC"}) FILTER (WHERE COALESCE(jsonb_typeof($value), 'null') <> 'null'))[1]"

    override fun add(a: String, b: String) =
        "CASE WHEN jsonb_typeof($a) = 'number' AND jsonb_typeof($b) = 'number' THEN to_jsonb(${number(a)} + ${number(b)}) " +
            "WHEN jsonb_typeof($a) = 'array' AND jsonb_typeof($b) = 'array' THEN $a || $b " +
            "WHEN jsonb_typeof($a) = 'array' THEN $a || jsonb_build_array($b) " +
            "WHEN jsonb_typeof($b) = 'array' THEN jsonb_build_array($a) || $b " +
            "WHEN jsonb_typeof($a) IN ('string', 'number') AND jsonb_typeof($b) IN ('string', 'number') THEN to_jsonb(($a #>> '{}') || ($b #>> '{}')) END"

    override fun modulo(a: String, b: String) = "($a % $b)"

    override fun startsWith(a: String, b: String) = "starts_with($a, $b)"

    override fun endsWith(a: String, b: String) = "right($a, length($b)) = $b"

    override fun containsText(a: String, b: String) = "strpos($a, $b) > 0"

    override fun function(name: String, json: String): String? = when (name) {
        "keys" -> "(SELECT COALESCE(jsonb_agg(k ORDER BY k), '[]'::jsonb) FROM jsonb_object_keys($json) k)"
        "size" -> "CASE jsonb_typeof($json) WHEN 'array' THEN to_jsonb(jsonb_array_length($json)) WHEN 'string' THEN to_jsonb(length($json #>> '{}')) END"
        "tolower" -> "to_jsonb(lower(${text(json)}))"
        "toupper" -> "to_jsonb(upper(${text(json)}))"
        "trim" -> "to_jsonb(btrim(${text(json)}))"
        "tostring" -> "to_jsonb($json #>> '{}')"
        "tointeger" -> "to_jsonb(trunc(CAST(NULLIF($json #>> '{}', '') AS numeric)))"
        "tofloat" -> "to_jsonb(${float("NULLIF($json #>> '{}', '')")})"
        "head" -> "($json -> 0)"
        "last" -> "($json -> -1)"
        "abs" -> "to_jsonb(abs(${number(json)}))"
        else -> null
    }

    override fun page(skip: String?, limit: String?) = listOfNotNull(skip?.let { "OFFSET $it" }, limit?.let { "LIMIT $it" })

    override fun ids(id: String) = "ARRAY[$id]"

    override fun joinIds(lists: List<String>) = lists.joinToString(" || ", "(", ")")

    override val noIds = "CAST(ARRAY[] AS bigint[])"

    override fun idIn(id: String, list: String) = "$id = ANY ($list)"

    override fun idsOverlap(a: String, b: String) = "$a && $b"

    override fun idCount(list: String) = "to_jsonb(cardinality($list))"

    override fun idRows(list: String, alias: String) = "unnest($list) WITH ORDINALITY $alias (id, ord)"

    /** A lateral recursive query from the node walked from, which reads its row. */
    override fun walk(walk: Walk): Walked {
        val w = walk.step
        val e = walk.edge
        val (join, far) = when (walk.direction) {
            RelationshipPattern.Direction.OUTGOING -> "$e.src = $w.nid" to "$e.dst"
            RelationshipPattern.Direction.INCOMING -> "$e.dst = $w.nid" to "$e.src"
            // Either way, and a relationship from a node to itself once.
            RelationshipPattern.Direction.EITHER -> "($e.src = $w.nid OR $e.dst = $w.nid)" to "CASE WHEN $e.src = $w.nid THEN $e.dst ELSE $e.src END"
        }
        val conditions = listOf("$e.eid <> ALL ($w.eids)") + listOfNotNull(walk.max?.let { "$w.depth < $it" }) + walk.conditions
        val text = listOf(
            "WITH RECURSIVE $w (nid, depth, nids, eids) AS (",
            "    SELECT ${walk.from}, 0, $noIds, $noIds",
            "    UNION ALL",
            // Walked backwards, each node it leaves goes first, so its nodes end with the one it started from.
            if (walk.backwards) "    SELECT $far, $w.depth + 1, $w.nid || $w.nids, $e.eid || $w.eids"
            else "    SELECT $far, $w.depth + 1, $w.nids || $far, $w.eids || $e.eid",
            "    FROM $w JOIN $edges $e ON $join",
            "    WHERE ${conditions.joinToString(" AND ")}",
            ")",
            "SELECT nid, nids, eids FROM $w WHERE depth >= ${walk.min}",
        ).joinToString("\n")
        return Walked("LATERAL (\n${indent(text)}\n) ${walk.name}", "${walk.name}.nid = ${walk.to}", "${walk.name}.eids", "${walk.name}.nids")
    }

    override fun nodeJson(node: Value.Node) =
        "CASE WHEN ${node.nid} IS NOT NULL THEN ${node.props} || jsonb_strip_nulls(jsonb_build_object(${literal(Stamps.PROPERTY)}, ${node.stamp})) END"
}
