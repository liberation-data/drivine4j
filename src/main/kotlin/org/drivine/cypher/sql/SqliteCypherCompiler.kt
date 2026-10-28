package org.drivine.cypher.sql

import org.drivine.backend.sqlite.SqliteFunctions
import org.drivine.backend.sqlite.SqliteSql
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps
import java.math.RoundingMode

/**
 * Compiles a Cypher statement to SQL over a graph's tables on SQLite: see [SqlCypherCompiler]. A statement it
 * compiles is read with its text values as JSON: the connection gives it `/* drivine:json */` to open with.
 *
 * - **A value** is JSON text, which SQLite's JSON functions read: SQLite has no JSON type, and a value that passes
 *   through a subquery is text again, so a value put into a list or a map is read as JSON there with `json(…)`.
 * - **What Cypher does with values** that SQLite's JSON does not, functions Drivine registers on each connection do
 *   (see [SqliteFunctions]): values compare as Cypher compares them, `1` equal to `1.0` and a map to a map of the
 *   same entries in any order, and lists and texts are added. A value orders by the SQL value `->>` reads of it.
 * - **Temporal values** are worked out by [SqliteTemporalSql].
 * - **A lateral query** (an `OPTIONAL MATCH`, a `CALL { … }`, a shortest path, a walk) is not one SQLite joins: its
 *   rows are gathered as a JSON list of maps in a subquery, which may read the row it is in, and that list is joined
 *   with `json_each`, whose argument may read the row too. Each column is read back out of the map.
 * - **A list of ids** is text, each id between commas (`,1,2,`), as on Oracle.
 *
 * A statement that writes is a procedure, as on the other stores, which a function Drivine registers carries out:
 * see [procedure].
 */
class SqliteCypherCompiler(graph: String) : SqlCypherCompiler(graph) {

    override fun literalJson(value: Any?) = literal(Neo4jObjectMapper.instance.writeValueAsString(value))

    override fun parameterJson(marker: String, text: String?) = marker

    override fun parameterCount(marker: String) = "CAST($marker AS INTEGER)"

    /** A map or a list the statement builds with its maps' keys in order, so two of the same entries group and are kept once as one. */
    override fun comparable(value: Value.Json) = if (value.ordered) "${SqliteFunctions.CANONICAL}(${value.sql})" else value.sql

    /** Null as SQL's null, where `json_quote` writes it as JSON's. */
    override fun scalar(sql: String) = "NULLIF(json_quote($sql), 'null')"

    /** SQLite has no failing expression of its own: Drivine's function fails with the text. */
    override fun failure(message: String, value: String) = "${SqliteFunctions.FAIL}(${literal("$message ")} || $value)"

    override fun typeOf(json: String) =
        "(CASE json_type($json) WHEN 'integer' THEN 'number' WHEN 'real' THEN 'number' WHEN 'text' THEN 'string' WHEN 'true' THEN 'boolean' " +
            "WHEN 'false' THEN 'boolean' WHEN 'array' THEN 'array' WHEN 'object' THEN 'object' WHEN 'null' THEN 'null' END)"

    override fun isJsonNull(json: String) = "COALESCE(json_type($json), 'null') = 'null'"

    override fun nullIfJsonNull(json: String) = "NULLIF($json, 'null')"

    override fun number(json: String) = shared(json) { (x) -> "CASE WHEN json_type($x) IN ('integer', 'real') THEN $x ->> '$' END" }

    override fun integral(json: String) = "(json_type($json) = 'integer')"

    override fun text(json: String) = shared(json) { (x) -> "CASE WHEN json_type($x) = 'text' THEN $x ->> '$' END" }

    override fun boolOf(json: String) = "(CASE json_type($json) WHEN 'true' THEN 1 WHEN 'false' THEN 0 END)"

    override fun boolJson(sql: String) = "(CASE ($sql) WHEN 1 THEN 'true' WHEN 0 THEN 'false' END)"

    override fun float(sql: String) = "($sql + 0.0)"

    /**
     * SQLite takes no more than 100 arguments to a function (#332): past them, the first 100 items are a `json_array`, and
     * the rest are appended to it in parts, each by a `json_insert` of as many as it takes.
     */
    override fun jsonArray(items: List<String>): String =
        items.drop(MOST_ARGUMENTS).chunked((MOST_ARGUMENTS - 1) / 2).fold("json_array(${items.take(MOST_ARGUMENTS).joinToString(", ") { "json($it)" }})") { list, part ->
            "json_insert($list, ${part.joinToString(", ") { "'$[#]', json($it)" }})"
        }

    /** Past 50 entries, as for [jsonArray], the rest are set over the first 50 by [withEntries]. */
    override fun jsonObject(entries: List<Pair<String, String>>) =
        withEntries("json_object(${entries.take(MOST_ARGUMENTS / 2).joinToString(", ") { (key, value) -> "${literal(key)}, json($value)" }})", entries.drop(MOST_ARGUMENTS / 2))

    /** In parts, as for [jsonArray]: a `json_set` of as many entries as it takes, each set over the last. */
    override fun withEntries(json: String, entries: List<Pair<String, String>>) =
        entries.chunked((MOST_ARGUMENTS - 1) / 2).fold(json) { map, part ->
            "json_set($map, ${part.joinToString(", ") { (key, value) -> "${path(key)}, json($value)" }})"
        }

    override fun propertyOf(json: String, key: String) = "($json -> ${path(key)})"

    /** Counted from the end where negative, as SQLite's `->` counts an index. */
    override fun element(json: String, index: String) = "($json -> CAST($index ->> '$' AS INTEGER))"

    override fun keyed(json: String, key: String) = "($json -> ('$.' || json_quote($key ->> '$')))"

    override fun containsAll(props: String, map: String) = "${SqliteFunctions.CONTAINS}($props, $map)"

    /** The test a partial index over the label's nodes is made with: see [SqliteSql.hasLabel]. */
    override fun hasLabel(labels: String, label: String) = SqliteSql.holdsLabel(labels, label)

    /**
     * The property's text, as a range index or a uniqueness constraint reads it (see [SqliteSql.propertyText]), compared
     * with the value's: a string's text, or a truth value's JSON.
     */
    override fun indexedEquality(property: String, value: String): String? =
        Regex("""^\(([\w.]+) -> ('(?:[^']|'')*')\)$""").matchEntire(property)?.let {
            "${SqliteSql.propertyText(it.groupValues[1], it.groupValues[2])} = (CASE json_type($value) WHEN 'text' THEN $value ->> '$' ELSE $value END)"
        }

    override fun elements(json: String, alias: String) = "json_each($json) AS $alias"

    /** `json_each` gives a string, a number or a truth value as the SQL value it is, and a list or a map as JSON. */
    override fun item(alias: String) =
        "(CASE $alias.type WHEN 'true' THEN 'true' WHEN 'false' THEN 'false' WHEN 'null' THEN 'null' WHEN 'array' THEN $alias.value " +
            "WHEN 'object' THEN $alias.value ELSE json_quote($alias.value) END)"

    override fun itemNo(alias: String) = "($alias.key + 1)"

    override fun jsonEquals(x: String, y: String) = "${SqliteFunctions.EQUAL}($x, $y)"

    /** [SqliteFunctions.EQUAL] compares a value that holds a null as Cypher does, null where no item or entry differs. */
    override fun holdsNull(json: String): String? = null

    override fun nullsEqual(x: String, y: String, equals: (String, String) -> String) = equals(x, y)

    /** A text, a number or a truth value the statement gives is compared without the function that compares any two values. */
    override fun givenEquals(x: String, y: String, given: Any) = when (given) {
        is String -> shared(x, y) { (a, b) -> "(json_type($a) = 'text' AND ($a ->> '$') = ($b ->> '$'))" }
        is Number -> shared(x, y) { (a, b) -> "(json_type($a) IN ('integer', 'real') AND ($a ->> '$') = ($b ->> '$'))" }
        is Boolean -> "(json_type($x) = json_type($y))"
        else -> jsonEquals(x, y)
    }

    override fun ordered(x: String, operator: String, y: String) = "(($x ->> '$') $operator ($y ->> '$'))"

    /** A list as [SqliteFunctions.SORT_KEY] writes it, which orders as Cypher orders lists; any other value as the SQL value `->>` reads. */
    override fun sortKey(json: String) = shared(json) { (x) -> "CASE WHEN json_type($x) = 'array' THEN ${SqliteFunctions.SORT_KEY}($x) ELSE ($x ->> '$') END" }

    /**
     * Where the kind comes, then the SQL value `->>` reads, which orders a number before a text, and a truth value, 1 or 0, among
     * the numbers. The first term is a text: the kind's rank, a list's followed by itself as [SqliteFunctions.SORT_KEY] writes
     * it, so a list is ordered by it; and null for null. The value is read once more, not twice, as [sortKey] reads it, and
     * each term is a value JSON holds, as a `CALL { … }` carries it out.
     */
    override fun openSortKey(json: String) = shared(json) { (x) ->
        "CASE json_type($x) WHEN 'object' THEN '1' WHEN 'array' THEN '2' || ${SqliteFunctions.SORT_KEY}($x) WHEN 'text' THEN '3' " +
            "WHEN 'true' THEN '4' WHEN 'false' THEN '4' WHEN 'integer' THEN '5' WHEN 'real' THEN '5' END"
    }.let { rank -> listOf(rank, shared(json) { (x) -> "($x ->> '$')" }) }

    /** SQLite puts null first when ascending, and Cypher last. */
    override fun orderTerm(term: String, descending: Boolean) = if (descending) "$term DESC NULLS FIRST" else "$term NULLS LAST"

    /** SQLite divides one whole number by another to a whole number, toward zero. */
    override fun wholeQuotient(a: String, b: String) = "($a / $b)"

    override fun aggregateList(value: String, order: String?) = "json_group_array(json($value)${order?.let { " ORDER BY $it" }.orEmpty()})"

    override fun collect(value: String, order: String?) =
        "json_group_array(json($value)${order?.let { " ORDER BY $it" }.orEmpty()}) FILTER (WHERE $value IS NOT NULL)"

    override fun joinedTexts(text: String, order: String) = "group_concat($text, '' ORDER BY $order)"

    override fun distinctItems(list: String, temporal: Boolean) =
        "(SELECT json_group_array(json(d.item) ORDER BY d.first) FROM (SELECT min(${itemNo("e")}) AS first, ${item("e")} AS item " +
            "FROM ${elements(list, "e")} GROUP BY ${SqliteFunctions.CANONICAL}(${item("e")})) d)"

    /** Each item read at its place: `json_each` is no table a join can look a place up in, so a join reads it whole for each. */
    override fun firstOfEach(list: String, identities: String) =
        "(SELECT json_group_array(json($list -> ('\$[' || f.first || ']')) ORDER BY f.first) " +
            "FROM (SELECT min(e.key) AS first FROM ${elements(identities, "e")} GROUP BY ${SqliteFunctions.CANONICAL}(${item("e")})) f)"

    /** The first of the values in order, the nulls left out: SQLite's `min` and `max` order no JSON. */
    override fun extreme(value: String, min: Boolean, key: String?): String {
        val direction = if (min) "ASC" else "DESC"
        return "(json_group_array(json($value) ORDER BY ${kindOrder(value)} $direction, ${key ?: "($value ->> '$')"} $direction) FILTER (WHERE NOT ${isJsonNull(value)}) -> 0)"
    }

    override fun idTokens(ids: String, key: String) = shared(ids) { (x) ->
        "CASE WHEN $x IS NOT NULL THEN (SELECT json_group_array(CASE WHEN t.type = 'null' THEN json('null') ELSE json_object(${literal(key)}, t.value) END ORDER BY t.key) FROM json_each($x) t) END"
    }

    override fun add(a: String, b: String, given: Pair<Any?, Any?>) = "${SqliteFunctions.ADD}($a, $b)"

    /** `%` of two whole numbers, and of any other two `mod`, which keeps the fraction: SQLite's `%` takes whole numbers alone. */
    override fun modulo(a: String, b: String) = shared(a, b) { (x, y) -> "CASE WHEN typeof($x) = 'integer' AND typeof($y) = 'integer' THEN $x % $y ELSE mod($x, $y) END" }

    override fun startsWith(a: String, b: String) = shared(a, b) { (x, y) -> "CASE WHEN $x IS NOT NULL AND $y IS NOT NULL THEN substr($x, 1, length($y)) = $y END" }

    override fun endsWith(a: String, b: String) =
        shared(a, b) { (x, y) -> "CASE WHEN $x IS NOT NULL AND $y IS NOT NULL THEN ($y = '' OR substr($x, -length($y)) = $y) END" }

    override fun containsText(a: String, b: String) = "(instr($a, $b) > 0)"

    override fun function(name: String, json: String): String? = when (name) {
        "keys" -> shared(json) { (x) -> "CASE WHEN json_type($x) = 'object' THEN (SELECT json_group_array(k.key ORDER BY k.key) FROM json_each($x) k) END" }
        "size" -> shared(json) { (x) -> "CASE json_type($x) WHEN 'array' THEN json_quote(json_array_length($x)) WHEN 'text' THEN json_quote(length($x ->> '$')) END" }
        "tolower" -> scalar("${SqliteFunctions.LOWER}(${text(json)})")
        "toupper" -> scalar("${SqliteFunctions.UPPER}(${text(json)})")
        "trim" -> scalar("trim(${text(json)}, $WHITESPACE)")
        // A string is itself; anything else is the text of its JSON.
        "tostring" -> shared(json) { (x) ->
            "CASE json_type($x) WHEN 'text' THEN $x WHEN 'null' THEN NULL WHEN 'real' THEN json_quote(${SqliteFunctions.FLOAT_TEXT}($x ->> '$')) ELSE json_quote('' || $x) END"
        }
        "tointeger" -> shared(json) { (x) ->
            "CASE json_type($x) WHEN 'integer' THEN $x WHEN 'real' THEN json_quote(CAST($x ->> '$' AS INTEGER)) " +
                "WHEN 'text' THEN CASE WHEN trim($x ->> '$') REGEXP '^[+-]?[0-9]+$' THEN json_quote(CAST(trim($x ->> '$') AS INTEGER)) " +
                "WHEN ($x ->> '$') REGEXP ${literal(NUMERIC_TEXT)} THEN json_quote(CAST(CAST(trim($x ->> '$') AS REAL) AS INTEGER)) END END"
        }
        "tofloat" -> shared(json) { (x) ->
            "CASE json_type($x) WHEN 'integer' THEN json_quote(CAST($x ->> '$' AS REAL)) WHEN 'real' THEN $x " +
                "WHEN 'text' THEN CASE WHEN ($x ->> '$') REGEXP ${literal(NUMERIC_TEXT)} THEN json_quote(CAST(trim($x ->> '$') AS REAL)) END END"
        }
        "head" -> "($json -> 0)"
        "last" -> "($json -> -1)"
        "abs" -> scalar("abs(${number(json)})")
        else -> null
    }

    override fun page(skip: String?, limit: String?) =
        if (skip == null && limit == null) emptyList() else listOf("LIMIT ${limit ?: "-1"}${skip?.let { " OFFSET $it" }.orEmpty()}")

    override fun ids(id: String) = "(',' || $id || ',')"

    override fun joinIds(lists: List<String>) = "(${lists.first()}${lists.drop(1).joinToString("") { " || substr($it, 2)" }})"

    override fun storeIds(json: String) = shared(json) { (ids) ->
        "CASE WHEN $ids IS NOT NULL THEN (SELECT ',' || COALESCE(group_concat(x.value, ',' ORDER BY x.key) || ',', '') FROM json_each($ids) x) END"
    }

    override val noIds = "','"

    override fun idIn(id: String, list: String) = "instr($list, ',' || $id || ',') > 0"

    override fun idsOverlap(a: String, b: String) = "EXISTS (SELECT 1 FROM ${idRows(a, "ov")} WHERE instr($b, ',' || ${rowId("ov")} || ',') > 0)"

    override fun idCount(list: String) = shared(list) { (l) -> "json_quote(length($l) - length(replace($l, ',', '')) - 1)" }

    override fun idRows(list: String, alias: String) = "json_each('[' || trim($list, ',') || ']') AS $alias"

    override fun rowId(alias: String) = "$alias.value"

    override fun rowOrd(alias: String) = "($alias.key + 1)"

    /**
     * A JSON list of maps, a map for each row of [query], gathered in a subquery that may read the row it is in, and
     * joined as `json_each` gives its items: SQLite joins no lateral query.
     */
    override fun lateral(query: String, name: String, optional: Boolean, columns: List<Pair<String, Boolean>>): String {
        val rows = gathered(query, name, columns)
        return if (optional) "LEFT JOIN $rows ON TRUE" else "CROSS JOIN $rows"
    }

    /** The rows of [query], of the columns [columns], as `json_each` gives the JSON list of maps they are gathered in, named [name]: see [lateral]. */
    private fun gathered(query: String, name: String, columns: List<Pair<String, Boolean>>): String {
        val entries = columns.joinToString(", ") { (column, json) -> "${literal(column)}, ${if (json) "json(q.$column)" else "q.$column"}" }
        return "json_each((SELECT json_group_array(json_object($entries)) FROM (\n${indent(query)}\n) q)) AS $name"
    }

    /** JSON's null, which a column of SQL's null is in the map, as SQL's null again: a list of nodes that is null is not the list `[null]`. */
    override fun lateralColumn(name: String, column: String, json: Boolean) = if (json) "NULLIF($name.value -> '$.$column', 'null')" else "($name.value ->> '$.$column')"

    /**
     * A recursive query from the node walked from, which reads its row, its rows gathered as [lateral] gathers a query's:
     * each the node a walk reached, and the ids of the nodes and relationships it took to get there.
     */
    override fun walk(walk: Walk): Walked {
        val w = walk.step
        val (joins, reached, distinct) = steps(walk, "$w.nid")
        val eids = walk.hops.map { "${it.edge}.eid" }
        val conditions = eids.map { "instr($w.eids, ',' || $it || ',') = 0" } + distinct + listOfNotNull(walk.max?.let { "$w.depth < $it" }) + walk.conditions
        val text = listOf(
            "WITH RECURSIVE $w (nid, depth, nids, eids) AS (",
            "    SELECT ${walk.from}, 0, ',', ','",
            "    UNION ALL",
            // Walked backwards, each node it leaves goes first, so its nodes end with the one it started from.
            if (walk.backwards) "    SELECT ${reached.single()}, $w.depth + 1, ',' || $w.nid || $w.nids, ',' || ${eids.single()} || $w.eids"
            else "    SELECT ${reached.last()}, $w.depth + 1, $w.nids${reached.joinToString("") { " || $it || ','" }}, $w.eids${eids.joinToString("") { " || $it || ','" }}",
            "    FROM $w",
            joins.joinToString("\n") { "    $it" },
            "    WHERE ${conditions.joinToString(" AND ")}",
            ")",
            "SELECT nid, nids, eids FROM $w WHERE depth >= ${walk.min}",
        ).joinToString("\n")
        val name = walk.name
        val rows = gathered(text, name, listOf("nid" to false, "nids" to false, "eids" to false))
        return Walked(rows, "${lateralColumn(name, "nid", json = false)} = ${walk.to}", lateralColumn(name, "eids", json = false), lateralColumn(name, "nids", json = false))
    }

    override fun nodeJson(node: Value.Node) =
        "CASE WHEN ${node.nid} IS NOT NULL THEN CASE WHEN ${node.stamp} IS NULL THEN ${node.props} ELSE json_set(${node.props}, ${path(Stamps.PROPERTY)}, ${node.stamp}) END END"

    /** A recursive query, a row for each item taken, that reads the row it is in, as SQLite's may. */
    override fun reduce(
        name: String,
        initial: String,
        list: String,
        step: (accumulator: String, item: String) -> String,
        context: () -> String,
        contextStep: (accumulator: String, item: String, context: String) -> String,
    ): String = shared(list) { (items) ->
        val text = listOf(
            "WITH RECURSIVE $name (i, acc) AS (",
            "    SELECT 0, $initial",
            "    UNION ALL",
            "    SELECT $name.i + 1, ${step("$name.acc", "($items -> $name.i)")} FROM $name WHERE $name.i < json_array_length($items)",
            ")",
            "SELECT acc FROM $name ORDER BY i DESC LIMIT 1",
        ).joinToString("\n")
        "CASE WHEN json_type($items) = 'array' THEN (\n${indent(text)}\n) END"
    }

    /** A recursive query, a row for each number: SQLite's `generate_series` is an extension its build leaves out. */
    override fun range(from: String, to: String, step: String) = shared(from, to, step) { (first, last, by) ->
        val within = { g: String -> "CASE WHEN $by > 0 THEN $g <= CAST($last AS INTEGER) ELSE $g >= CAST($last AS INTEGER) END" }
        val text = listOf(
            "WITH RECURSIVE r (g, o) AS (",
            "    SELECT CAST($first AS INTEGER), 1",
            "    UNION ALL",
            "    SELECT r.g + CAST($by AS INTEGER), r.o + 1 FROM r WHERE ${within("r.g + CAST($by AS INTEGER)")}",
            ")",
            "SELECT json_group_array(r.g ORDER BY r.o) FROM r WHERE ${within("r.g")}",
        ).joinToString("\n")
        // A step of 0 would never end, and Cypher refuses it.
        "CASE WHEN CAST($by AS INTEGER) <> 0 THEN (\n${indent(text)}\n) END"
    }

    override val rowNumber = "ROW_NUMBER() OVER ()"

    override fun uuid() = "${SqliteFunctions.UUID}()"

    /** Materialized, as SQLite otherwise flattens the query into the one that reads it, and makes a `randomUUID()` again for each read. */
    override fun once(query: String) = "WITH once AS MATERIALIZED (\n${indent(query)}\n)\nSELECT * FROM once"

    /**
     * By Java's `Math`, as Neo4j works these out: see [org.drivine.backend.sqlite.SqliteMathFunction]. SQLite's own give
     * null where Neo4j gives NaN or negative infinity.
     */
    override fun math(name: String, numbers: List<String>) = "${SqliteFunctions.MATH}(${literal(name)}, ${numbers.joinToString(", ")})"

    /** In decimal, as Neo4j rounds: see [org.drivine.backend.sqlite.SqliteRoundFunction]. SQLite's numbers are binary, and 2.345 is below 2.345 there. */
    override fun roundTo(number: String, precision: String, mode: RoundingMode) = "${SqliteFunctions.ROUND}($number, $precision, ${literal(mode.name)})"

    /**
     * Half up, as Neo4j rounds by two arguments, by Drivine's function as a mode rounds: SQLite's `ROUND` keeps no more
     * than 15 places of a fraction, so 1.0E-100 to 120 places would be 0.
     */
    override fun roundPlaces(number: String, precision: String) = roundTo(number, precision, RoundingMode.HALF_UP)

    /** Half toward +infinity, by Drivine's function as a mode rounds: half up above 0, half down below. */
    override fun roundWhole(number: String) = shared(number) { (n) ->
        "CASE WHEN $n < 0 THEN ${roundTo(n, "0", RoundingMode.HALF_DOWN)} ELSE ${roundTo(n, "0", RoundingMode.HALF_UP)} END"
    }

    override fun rightText(text: String, count: String) = shared(text, count) { (t, n) ->
        "CASE WHEN $t IS NULL OR $n IS NULL THEN NULL WHEN $n >= length($t) THEN $t WHEN $n > 0 THEN substr($t, -$n) ELSE '' END"
    }

    override val temporalSql = SqliteTemporalSql(sharedList)

    /**
     * The whole of the text matches, as `=~` asks: `REGEXP` finds a match anywhere. It ends at `\z`, the text's end,
     * where Java's `$` would match before a line break that ends the text too.
     */
    override fun matches(text: String, pattern: String) = "($text REGEXP ('^(?:' || $pattern || ')\\z'))"

    override fun split(text: String, separator: String) = "${SqliteFunctions.SPLIT}($text, $separator)"

    override fun reverseText(text: String) = "${SqliteFunctions.REVERSE}($text)"

    /**
     * The procedure's steps carried out one by one by the function Drivine registers on each connection, under a savepoint
     * of its own, so the statement is applied whole or not at all (see `SqliteCypherFunction`): SQLite has no procedural
     * language. The steps are given as JSON, each with its SQL, in which each variable of the procedure it reads is a
     * numbered parameter, `?1`, and the variables bound to them in turn. The rows it returns are JSON's, in a list.
     */
    override fun procedure(program: List<ProcedureStep>): String {
        val steps = Neo4jObjectMapper.instance.writeValueAsString(mapOf("steps" to program.map(::step)))
        val c = "cy"
        return "SELECT ${item(c)} AS result FROM json_each(${SqliteFunctions.CYPHER}(${literal(steps)}, \$${Parameters.OBJECT})) AS $c ORDER BY $c.key"
    }

    /** [step] as the function that carries it out reads it. */
    private fun step(step: ProcedureStep): Map<String, Any?> = when (step) {
        is ProcedureStep.Select -> sql("select", "SELECT ${step.expression}${step.from}") + ("variable" to step.variable)
        is ProcedureStep.Let -> sql("select", "SELECT ${step.expression}") + ("variable" to step.variable)
        is ProcedureStep.Loop -> sql("loop", step.query) + mapOf("record" to "${step.record}.item", "body" to step.body.map(::step))
        is ProcedureStep.If -> sql("if", "SELECT CASE WHEN ${step.condition} THEN 1 ELSE 0 END") + ("body" to step.body.map(::step))
        is ProcedureStep.Raise -> mapOf("op" to "raise", "message" to step.message)
        is ProcedureStep.Execute -> sql("execute", step.statement) + ("variable" to step.into)
        is ProcedureStep.Return -> sql("return", step.query)
    }

    /** The step [op] of the SQL [sql], each variable it reads a numbered parameter, and the variables, in the parameters' order. */
    private fun sql(op: String, sql: String): Map<String, Any?> {
        val variables = mutableListOf<String>()
        val written = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                // A literal or a quoted name is read past whole: a text may hold what reads as a variable.
                c == '\'' || c == '"' -> {
                    var end = i + 1
                    while (end < sql.length) {
                        if (sql[end] != c) end++ else if (end + 1 < sql.length && sql[end + 1] == c) end += 2 else break
                    }
                    written.append(sql, i, minOf(end + 1, sql.length))
                    i = end + 1
                }
                (c.isLetter() || c == '_') && (i == 0 || !(sql[i - 1].isLetterOrDigit() || sql[i - 1] == '_' || sql[i - 1] == '.')) -> {
                    var end = i
                    while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_')) end++
                    var name = sql.substring(i, end)
                    if (name in RECORDS && sql.startsWith(".item", end) && (end + 5 >= sql.length || !(sql[end + 5].isLetterOrDigit() || sql[end + 5] == '_'))) {
                        name += ".item"
                        end += 5
                    }
                    if (name in VARIABLES || name in RECORDS.map { "$it.item" }) {
                        if (name !in variables) variables += name
                        written.append('?').append(variables.indexOf(name) + 1)
                    } else {
                        written.append(name)
                    }
                    i = end
                }
                else -> written.append(c).also { i++ }
            }
        }
        return mapOf("op" to op, "sql" to written.toString(), "variables" to variables)
    }

    override fun idOf(json: String, key: String?) = "CAST($json ->> ${key?.let(::path) ?: "'$'"} AS INTEGER)"

    override fun appended(list: String, item: String) = "json_insert($list, '$[#]', json($item))"

    /** A merge patch onto an empty map leaves out each entry whose value is null. */
    override fun stripNulls(json: String) = "json_patch('{}', $json)"

    override fun merged(props: String, map: String) = "json_patch($props, $map)"

    override fun withoutLabel(labels: String, label: String) =
        "(SELECT json_group_array(l.value ORDER BY l.key) FROM json_each($labels) l WHERE l.value <> ${literal(label)})"

    /** The path of the key [key] of a map, as SQLite's JSON functions read one: a key of any characters, in quotes. */
    private fun path(key: String) = literal("$.${SqliteSql.quoted(key)}")

    private companion object {
        /** The variables of a procedure: see [SqlCypherCompiler.ROWS] and the rest. */
        val VARIABLES = setOf(PARAMS, ROWS, OUT, ROW, VALUE, ID, COUNT, FOUND, DOOMED) + (1..NESTING).flatMap { listOf("$SAVED$it", "$GATHERED$it", "$CALLER$it") }

        /** The records of a procedure's loops, whose `item` a step reads. */
        val RECORDS = setOf(RECORD, MATCHED)

        /** A text SQLite reads as a number, spaces around it allowed. */
        const val NUMERIC_TEXT = """^\s*[+-]?([0-9]+\.?[0-9]*|\.[0-9]+)([eE][+-]?[0-9]+)?\s*$"""

        /** The characters `trim` takes off a text's ends, as Cypher's does: SQLite's own takes spaces alone. */
        const val WHITESPACE = "char(32, 9, 10, 11, 12, 13)"
    }
}
