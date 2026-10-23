package org.drivine.cypher.sql

import org.drivine.DrivineException
import org.drivine.cypher.CypherReader
import org.drivine.cypher.ast.Clause
import org.drivine.cypher.ast.Expression
import org.drivine.cypher.ast.Expression.Binary.Operator
import org.drivine.cypher.ast.NodePattern
import org.drivine.cypher.ast.Pattern
import org.drivine.cypher.ast.Projection
import org.drivine.cypher.ast.Query
import org.drivine.cypher.ast.RelationshipPattern
import org.drivine.cypher.ast.RemoveItem
import org.drivine.cypher.ast.SetItem
import org.drivine.model.Stamps
import org.slf4j.LoggerFactory

/**
 * Compiles a Cypher statement to SQL over a graph's tables, `<graph>_node` and `<graph>_edge`, so a
 * statement written for a graph engine runs on a SQL store and gives the same rows. What is the same on
 * each store is here; a store's own SQL, for JSON, lists, walks and paging, is its subclass's.
 *
 * - A `MATCH` is a join of node and relationship rows, its patterns' labels, types, directions and
 *   properties its conditions. Two relationships of one `MATCH` are two relationships, as in Cypher.
 * - A variable-length relationship, `*min..max`, is a recursive walk from the end of it that is pinned
 *   down, which takes no relationship twice. A variable on one is the list of its relationships.
 * - A path, `p = (…)`, is the ids of its nodes and relationships, read by `nodes`, `relationships` and
 *   `length`. It is not returned whole: each graph engine's driver gives a path as a value of its own.
 * - An `OPTIONAL MATCH` is a lateral join that keeps the row with nulls where nothing is found.
 * - A `WITH` is a query of its own, which the rest of the statement reads from.
 * - A projection that aggregates is grouped by what it holds outside its aggregates, as Cypher groups it.
 * - A value is JSON, so a comparison is JSON's and `'7'` is not `7`, and a missing property is null and
 *   meets no comparison. A map the statement builds keeps its keys in the order written, as a graph
 *   engine gives them.
 * - The statement returns one value a row, as drivine's statements are written to: the one item a
 *   `RETURN` has, or a list of its items when it has more, as drivine gives a row of several columns.
 *   A node is its properties, as drivine gives a node.
 *
 * A statement that writes (`CREATE`, `MERGE`, `SET`, `REMOVE`, `DELETE`) is a procedure, the store's
 * own, which runs as one statement and so is applied whole or not at all. Its rows are carried from
 * clause to clause as a JSON list, a node or a relationship in a row as its id, and each write is made
 * for one row after another, as Cypher makes it: a `MERGE` finds what an earlier row made. A read after
 * a write sees what it wrote. Its parameters are bound as one object, [Parameters.OBJECT].
 */
abstract class SqlCypherCompiler(graph: String) {

    protected val nodes = "${graph}_node"
    protected val edges = "${graph}_edge"
    private val logger = LoggerFactory.getLogger(SqlCypherCompiler::class.java)

    /** Whether the statement is a procedure, which declares no function of its own a query of it may call. */
    protected var procedural = false

    /** The functions a query of the statement calls, which it declares before it: see [declare]. */
    private val functions = mutableListOf<String>()

    /** Declares [function], a function's whole text, for the statement to call: a query's own, before it. */
    protected fun declare(function: String) {
        functions += function
    }

    /** [compiled] with the functions it calls declared before it. */
    private fun withFunctions(compiled: CompiledCypher): CompiledCypher =
        if (functions.isEmpty()) compiled else compiled.copy(sql = "WITH\n${functions.joinToString("\n")}\n${compiled.sql}")

    /** How many values [shared] has bound, to name each subquery it binds one in. */
    private var shares = 0

    /** Whether [shared] binds a long value: not in an expression that aggregates, whose aggregate a subquery would take from its group. */
    private var sharing = true

    /** The statement [cypher], with its parameters' values [parameters], as SQL. */
    fun compile(cypher: String, parameters: Map<String, Any?> = emptyMap()): CompiledCypher =
        compile(CypherReader.read(cypher), parameters)

    /** The statement [query], read already, with its parameters' values [parameters], as SQL: a query, or a procedure where it writes. */
    fun compile(query: Query, parameters: Map<String, Any?>): CompiledCypher {
        if (query.unions.isNotEmpty()) return withFunctions(union(query, parameters))
        if (query.clauses.none(::writes)) {
            val writer = Writer(Parameters(parameters, ::parameterJson, ::parameterCount))
            return withFunctions(CompiledCypher(writer.query(query), writer.parameters.values))
        }
        procedural = true
        // A procedure takes its parameters as one object, and reads each from it.
        val writer = Writer(
            Parameters(parameters, { nullIfJsonNull(propertyOf(PARAMS, it)) }, { number(propertyOf(PARAMS, it)) }, inObject = true)
        )
        val body = writer.procedure(query)
        return CompiledCypher(procedure(body), writer.parameters.values)
    }

    /**
     * [body] over [values], each read by name where one is long: bound once in a subquery, so a body that reads
     * a value more than once (to test its kind, then to read it) does not write it out again, and operations
     * nested in one another do not grow the statement exponentially. Short values are written where they are
     * read. Oracle groups by no subquery, so a projection that would group by a value so bound works it out first.
     */
    protected fun shared(vararg values: String, body: (List<String>) -> String): String {
        if (!sharing || values.all { it.length <= SHARED_ABOVE }) return body(values.toList())
        return bound(*values, body = body)
    }

    /** [body] over [values], each bound in a subquery and read by name whatever its length: see [shared]. */
    protected fun bound(vararg values: String, body: (List<String>) -> String): String {
        val alias = "sh${++shares}"
        val columns = values.withIndex().joinToString(", ") { (i, value) -> "$value AS v${i + 1}" }
        return "(SELECT ${body(values.indices.map { "$alias.v${it + 1}" })} FROM (SELECT $columns) $alias)"
    }

    /**
     * A `UNION`: each query's rows, in the order of the queries and each query's own order, as a graph engine
     * gives them; without `ALL`, each row once, where it is first given.
     */
    private fun union(query: Query, parameters: Map<String, Any?>): CompiledCypher {
        val parts = listOf(query.clauses) + query.unions.map { it.clauses }
        if (parts.any { part -> part.any(::writes) }) unsupported("A write in a UNION")
        if (query.unions.map { it.all }.distinct().size > 1) throw DrivineException("UNION and UNION ALL are not mixed in one statement, as in Cypher.")
        val names = parts.map { part -> (part.last() as Clause.Return).projection.items.map { it.name } }
        if (names.distinct().size > 1) {
            throw DrivineException("Each query of a UNION returns the same names, as in Cypher: ${names.joinToString(" and ") { it.joinToString(prefix = "(", postfix = ")") }}.")
        }
        val writer = Writer(Parameters(parameters, ::parameterJson, ::parameterCount))
        val rows = parts.mapIndexed { i, clauses ->
            "SELECT ${i + 1} AS part, $rowNumber AS n, q.result FROM (\n${indent(writer.query(Query(clauses)))}\n) q"
        }.joinToString("\nUNION ALL\n")
        val sql = if (query.unions.first().all) {
            "SELECT u.result FROM (\n${indent(rows)}\n) u\nORDER BY u.part, u.n"
        } else {
            // A row is given once, where it is first given: its rows are compared as JSON compares them.
            val key = comparable(Value.Json("u.result", ordered = true))
            "SELECT d.result FROM (\n${indent("SELECT $key AS result, MIN(u.part * 1000000000.0 + u.n) AS k FROM (\n${indent(rows)}\n) u\nGROUP BY $key")}\n) d\nORDER BY d.k"
        }
        return CompiledCypher(sql, writer.parameters.values)
    }

    // ----- A store's SQL -----

    /** A value [value] the statement writes, as JSON. */
    protected abstract fun literalJson(value: Any?): String

    /** The parameter bound as [marker], JSON text, as JSON. */
    protected abstract fun parameterJson(marker: String): String

    /** The parameter bound as [marker], a whole number, as one. */
    protected abstract fun parameterCount(marker: String): String

    /** A JSON value as what compares, groups and orders as JSON. */
    protected abstract fun comparable(value: Value.Json): String

    /** A SQL value [sql], a number, a text or a truth value, as JSON. */
    protected abstract fun scalar(sql: String): String

    /** The kind of the JSON [json]: `number`, `string`, `boolean`, `array`, `object` or `null`, as text. */
    protected abstract fun typeOf(json: String): String

    /** Whether the JSON [json] is null, or JSON's null: no value, as on a graph engine. */
    protected abstract fun isJsonNull(json: String): String

    /** The JSON [json], or null where it is JSON's null. */
    protected abstract fun nullIfJsonNull(json: String): String

    /** The JSON [json] as a number, where it is one. */
    protected abstract fun number(json: String): String

    /** Whether the JSON [json] is a whole number. */
    protected abstract fun integral(json: String): String

    /** The JSON [json] as text, where it is a string. */
    protected abstract fun text(json: String): String

    /** The JSON [json] as a condition, where it is `true` or `false`. */
    protected abstract fun boolOf(json: String): String

    /** The SQL number [sql] as a floating-point number. */
    protected abstract fun float(sql: String): String

    /** A list of [items], JSON each, nulls kept. */
    protected abstract fun jsonArray(items: List<String>): String

    /** A map of [entries], JSON each, its keys in the order given. */
    protected abstract fun jsonObject(entries: List<Pair<String, String>>): String

    /** The map [json] with [entries] set over it. */
    protected abstract fun withEntries(json: String, entries: List<Pair<String, String>>): String

    /** The value of [key] in the JSON map [json]; null where there is none. */
    protected abstract fun propertyOf(json: String, key: String): String

    /** The item of the JSON list [json] at the JSON [index], counted from the end where it is negative. */
    protected abstract fun element(json: String, index: String): String

    /**
     * The value of the JSON map [json] at the key the JSON [key] names; null where the store reads no key an
     * expression names, where such a key reads as null.
     */
    protected abstract fun keyed(json: String, key: String): String?

    /** Whether the JSON map [props] holds every entry of the JSON map [map]. */
    protected abstract fun containsAll(props: String, map: String): String

    /** Whether the labels [labels], a JSON list, hold [label]. */
    protected abstract fun hasLabel(labels: String, label: String): String

    /** Whether the labels [labels], a JSON list, hold each of [all]. */
    protected open fun hasLabels(labels: String, all: List<String>): String = all.joinToString(" AND ") { hasLabel(labels, it) }

    /**
     * A test an index answers, to stand beside the comparison of the [property], read as [propertyOf]
     * gives it, with the [value], a text or a truth value; null where there is none.
     */
    protected open fun indexedEquality(property: String, value: String): String? = null

    /** The items of the JSON list [json] as rows named [alias], with columns `item` and `item_no`, counted from 1. */
    protected abstract fun elements(json: String, alias: String): String

    /** The values [value] of a group, or of a query's rows ordered by [order], as a JSON list: empty where there are none. */
    protected abstract fun aggregateList(value: String, order: String? = null): String

    /**
     * `collect`: the values [value] of a group that are not null, as a JSON list, in the [order] of the rows
     * where they have one; once each with [distinct], in no order.
     */
    protected abstract fun collect(value: String, distinct: Boolean, order: String?): String

    /** `min` or `max` of the JSON values [value] of a group: of values of several kinds, as [kindOrder] orders them. */
    protected abstract fun extreme(value: String, min: Boolean): String

    /**
     * Where the JSON [json]'s kind comes among the others, as Cypher orders values of several kinds: a map,
     * a list, a text, a truth value, then a number. JSON orders them otherwise on each store.
     */
    protected fun kindOrder(json: String): String = shared(json) { (x) ->
        "CASE ${typeOf(x)} WHEN 'object' THEN 1 WHEN 'array' THEN 2 WHEN 'string' THEN 3 WHEN 'boolean' THEN 4 WHEN 'number' THEN 5 END"
    }

    /** `a + b` of the JSON values [a] and [b]: numbers added, lists joined, texts put together. */
    protected abstract fun add(a: String, b: String): String

    /** `a op b` of the JSON numbers [a] and [b], as JSON: [operator] is `+`, `-`, `*` or `/`. */
    protected open fun arithmetic(a: String, b: String, operator: String): String = scalar("(${number(a)} $operator ${number(b)})")

    /** The remainder of the SQL numbers [a] and [b]. */
    protected abstract fun modulo(a: String, b: String): String

    /** Whether the text [a] starts with the text [b]. */
    protected abstract fun startsWith(a: String, b: String): String

    /** Whether the text [a] ends with the text [b]. */
    protected abstract fun endsWith(a: String, b: String): String

    /** Whether the whole of the text [text] matches the regular expression [pattern], as `=~` matches it. */
    protected abstract fun matches(text: String, pattern: String): String

    /** `split`: the SQL text [text] cut at each [separator], a SQL text, as a JSON list of texts. */
    protected abstract fun split(text: String, separator: String): String

    /** The SQL text [text], its characters backwards. */
    protected abstract fun reverseText(text: String): String

    /** Whether the text [a] holds the text [b]. */
    protected abstract fun containsText(a: String, b: String): String

    /** A function of one argument [json] whose SQL is the store's own: `size`, `toLower`, `head`…; null when there is none. */
    protected abstract fun function(name: String, json: String): String?

    /** The `OFFSET` and `LIMIT` of a query, each a SQL whole number or null. */
    protected abstract fun page(skip: String?, limit: String?): List<String>

    /** The list of ids holding only [id]. */
    protected abstract fun ids(id: String): String

    /** The lists of ids [lists], one after another. */
    protected abstract fun joinIds(lists: List<String>): String

    /** The JSON list of ids [json] as the store's list of ids, in its order: null where the JSON is. */
    protected abstract fun storeIds(json: String): String

    /** The list of no ids. */
    protected abstract val noIds: String

    /** Whether the id [id] is among the list of ids [list]. */
    protected abstract fun idIn(id: String, list: String): String

    /** Whether two lists of ids share an id. */
    protected abstract fun idsOverlap(a: String, b: String): String

    /** How many ids the list [list] holds, as JSON. */
    protected abstract fun idCount(list: String): String

    /** The ids of the list [list] as rows named [alias], with columns `id` and `ord`, in its order. */
    protected abstract fun idRows(list: String, alias: String): String

    /** A walk of a variable-length relationship: see [walk]. */
    protected abstract fun walk(walk: Walk): Walked

    /**
     * A variable-length relationship's walk, from the node whose id is [from] to the one whose is [to],
     * as rows named [name]: by relationships named [edge] in [direction] that meet [conditions], to a depth
     * from [min] to [max] (any, if null). With [backwards] it is walked from the pattern's later node.
     * [seed], where there is one, is the condition the node walked from meets of its own, over a node row.
     */
    protected class Walk(
        val from: String,
        val to: String,
        val name: String,
        val step: String,
        val edge: String,
        val direction: RelationshipPattern.Direction,
        val min: Int,
        val max: Int?,
        val conditions: List<String>,
        val backwards: Boolean,
        val seed: ((String) -> String)?,
    )

    /**
     * What a walk is: its rows' [source], the [condition] that joins them to the pattern, the list of
     * the ids of the relationships each row took, [eids], and the list of the ids of the nodes it
     * reached after the pattern's earlier node, [nids], both in the pattern's order.
     */
    protected class Walked(val source: String, val condition: String, val eids: String, val nids: String)

    /**
     * `reduce`: the JSON list [list] folded from [initial], the JSON each step gives of the value so far and
     * the item, as [step] writes it, as JSON; null where the list is. [name] names what the fold reads from.
     * Where the step is worked out where it cannot read the row, it is [contextStep], which reads what the step
     * reads of the row, and the statement's parameters, from the JSON map [context] gives: asked for only there,
     * for a value such as a path is not JSON.
     */
    protected abstract fun reduce(
        name: String,
        initial: String,
        list: String,
        step: (accumulator: String, item: String) -> String,
        context: () -> String,
        contextStep: (accumulator: String, item: String, context: String) -> String,
    ): String

    /** The number of a row of a query, counted from 1 in the order the query gives its rows. */
    protected abstract val rowNumber: String

    /** `randomUUID()`: a new UUID, as SQL text in its usual form. */
    protected abstract fun uuid(): String

    /** `right`: the last [count] characters of the SQL text [text], or all of it where it has fewer. */
    protected abstract fun rightText(text: String, count: String): String

    /** `range`: the whole numbers from [from] to [to], [step] apart, SQL numbers each, as a JSON list. */
    protected abstract fun range(from: String, to: String, step: String): String

    /** `datetime().epochMillis` and `timestamp()`: the moment the statement runs, in milliseconds since 1970, as JSON. */
    protected abstract fun epochMillisNow(): String

    /** `datetime()`: the moment the statement runs, as JSON's text in ISO 8601, in UTC. */
    protected abstract fun isoNow(): String

    /** `date()`: the day the statement runs, in UTC, as JSON's text in ISO 8601. */
    protected abstract fun dateNow(): String

    /** `localdatetime()`: the moment the statement runs, in UTC and with no zone written, as JSON's text in ISO 8601. */
    protected abstract fun localNow(): String

    // ----- A store's procedure, for a statement that writes -----

    /**
     * The statement that runs the procedure whose statements are [body], each ending in `;`, and gives
     * the rows it returns, of one column, [returnRows]. The procedure declares the variables the companion
     * names ([PARAMS], [ROWS] …) and sets [PARAMS] from the object bound at [Parameters.OBJECT].
     */
    protected abstract fun procedure(body: List<String>): String

    /** The statements that return the rows of the query [query], whose one column is `result`. */
    protected abstract fun returnRows(query: String): List<String>

    /** The statement that fails the procedure with [message]. */
    protected abstract fun raise(message: String): String

    /** The id at [key] of the JSON map [json], or with no key the id [json] is, as the store's id. */
    protected abstract fun idOf(json: String, key: String?): String

    /** The JSON list [list] with the JSON [item] after its items. */
    protected abstract fun appended(list: String, item: String): String

    /** The JSON map [json] without the entries whose values are null. */
    protected abstract fun stripNulls(json: String): String

    /** The JSON map [props] with the entries of [map] set over it, an entry whose value is null removing its key. */
    protected abstract fun merged(props: String, map: String): String

    /** The labels [labels], a JSON list, without [label]. */
    protected abstract fun withoutLabel(labels: String, label: String): String

    /** One statement's compilation: its parameters, and the names it gives its rows and columns. */
    private inner class Writer(val parameters: Parameters) {
        private var counter = 0

        private fun alias(prefix: String) = "$prefix${++counter}"

        fun query(query: Query): String {
            var select = Select(emptyMap())
            for (clause in query.clauses) {
                if (clause is Clause.Return) return project(select, clause.projection, final = true).sql
                select = read(select, clause)
            }
            throw DrivineException("A Cypher statement ends with RETURN.")
        }

        // ----- Clauses -----

        /**
         * A reading clause over the rows [select] reads, and the rows the clauses after it read: a `WITH` is a query
         * of its own. [outer] is what a subquery reads from the row it is in.
         */
        private fun read(select: Select, clause: Clause, outer: Map<String, Value> = emptyMap()): Select = when (clause) {
            is Clause.Match -> select.also { match(it, clause) }
            is Clause.Unwind -> select.also { unwind(it, clause) }
            is Clause.With -> with(select, clause, outer)
            is Clause.Call -> select.also { call(it, clause) }
            else -> error("${clause::class.simpleName} reads nothing")
        }

        /** A `WITH`: a query of its own, which the rest of the statement reads from, as it reads [outer]. */
        private fun with(select: Select, with: Clause.With, outer: Map<String, Value> = emptyMap()): Select {
            val projected = project(select, with.projection, final = false)
            val name = alias("w")
            val next = Select(outer)
            next.source("(\n${indent(projected.sql)}\n) $name")
            projected.scope.forEach { (variable, value) -> next.bind(variable, value.columnsOf(name)) }
            projected.order.forEach { (column, descending) -> next.order += "$name.$column" to descending }
            with.where?.let { next.where += bool(expression(it, next.scope)) }
            return next
        }

        private fun match(select: Select, match: Clause.Match) {
            // A shortest path is the shortest of the paths that meet the WHERE, as Cypher finds it: the WHERE is met where they are found.
            val shortestWhere = match.where?.takeIf { match.patterns.last().shortest != null }
            val where = match.where.takeIf { shortestWhere == null }
            if (!match.optional) {
                patterns(select, match.patterns, shortestWhere)
                where?.let { select.where += bool(expression(it, select.scope)) }
                return
            }
            // What is found is read by a lateral query, joined to keep the row when it finds nothing.
            val inner = Select(select.scope)
            patterns(inner, match.patterns, shortestWhere)
            where?.let { inner.where += bool(expression(it, inner.scope)) }
            val found = inner.bound.filterKeys { it !in select.scope }
            if (found.isEmpty() && inner.sources.isEmpty()) return
            val name = alias("o")
            // Each variable found under a name of its own: a variable may be called what SQL will not take.
            val named = found.entries.map { (variable, value) -> Triple(variable, value, alias("v")) }
            val columns = named.flatMap { (_, value, column) -> value.columns(column) }.ifEmpty { listOf("1 AS one") }
            val text = listOf("SELECT ${columns.joinToString(", ")}") + inner.from() + listOfNotNull(inner.whereClause())
            select.join("LEFT JOIN LATERAL (\n${indent(text.joinToString("\n"))}\n) $name ON TRUE")
            named.forEach { (variable, value, column) -> select.bind(variable, value.columnsOf(name, column)) }
        }

        /**
         * The query a subquery is, for the row it is in, whose variables [scope] holds: its rows, or with no `RETURN`,
         * a row for each way its reading clauses are met.
         */
        private fun correlated(query: Query, scope: Map<String, Value>, what: String): String {
            if ((query.clauses + query.unions.flatMap { it.clauses }).any(::writes)) unsupported("A write in $what")
            if (query.unions.isNotEmpty()) {
                // Each query's rows as one value each, as a statement's are; without ALL, each once.
                val distinct = !query.unions.first().all
                val order = rowOrder
                try {
                    return (listOf(query.clauses) + query.unions.map { it.clauses }).joinToString(if (distinct) "\nUNION\n" else "\nUNION ALL\n") { clauses ->
                        var select = Select(scope)
                        clauses.dropLast(1).forEach { select = read(select, it, scope) }
                        val sql = project(select, (clauses.last() as Clause.Return).projection, final = true).sql
                        val result = if (distinct) comparable(Value.Json("q.result", ordered = true)) else "q.result"
                        "SELECT $result AS result FROM (\n${indent(sql)}\n) q"
                    }
                } finally {
                    rowOrder = order
                }
            }
            val order = rowOrder
            try {
                var select = Select(scope)
                for (clause in query.clauses) {
                    if (clause is Clause.Return) return project(select, clause.projection, final = false).sql
                    select = read(select, clause, scope)
                }
                return (listOf("SELECT 1") + from(select) + listOfNotNull(select.whereClause())).joinToString("\n")
            } finally {
                rowOrder = order
            }
        }

        /** `CALL { … }`: a lateral query for each row, what it returns bound as variables of the row. */
        private fun call(select: Select, call: Clause.Call) {
            if (call.query.clauses.last() !is Clause.Return) unsupported("A CALL { … } that returns nothing")
            if (call.query.clauses.any(::writes)) unsupported("A write in CALL { … }")
            val order = rowOrder
            val projected = if (call.query.unions.isEmpty()) calledPart(select, call.query.clauses, null, distinct = false) else calledUnion(select, call.query)
            rowOrder = order
            val name = alias("c")
            val text = "(\n${indent(projected.sql)}\n) $name"
            select.join(if (call.optional) "LEFT JOIN LATERAL $text ON TRUE" else "CROSS JOIN LATERAL $text")
            projected.scope.forEach { (variable, value) -> select.bind(variable, value.columnsOf(name)) }
            projected.order.forEach { (column, descending) -> select.order += "$name.$column" to descending }
        }

        /**
         * One query of a `CALL { … }`, for the row [select] reads: its returned items in the order [names] lists
         * them, where a `UNION` names them so, and kept distinct with [distinct].
         */
        private fun calledPart(select: Select, clauses: List<Clause>, names: List<String>?, distinct: Boolean): Projected {
            var inner = Select(select.scope)
            clauses.dropLast(1).forEach { inner = read(inner, it, select.scope) }
            val projection = (clauses.last() as Clause.Return).projection
            val items = names?.map { name -> projection.items.single { it.name == name } } ?: projection.items
            return project(inner, projection.copy(items = items, distinct = distinct || projection.distinct), final = false)
        }

        /** A `UNION` in a `CALL { … }`: each query's rows, the same items of the same kinds, matched by name as Cypher matches them. */
        private fun calledUnion(select: Select, query: Query): Projected {
            val parts = listOf(query.clauses) + query.unions.map { it.clauses }
            if (query.unions.map { it.all }.distinct().size > 1) throw DrivineException("UNION and UNION ALL are not mixed in one statement, as in Cypher.")
            val returns = parts.map { it.last() as Clause.Return }
            if (returns.any { it.projection.orderBy.isNotEmpty() }) unsupported("An ORDER BY in a query of a UNION in CALL { … }")
            val names = returns.first().projection.items.map { it.name }
            if (returns.any { r -> r.projection.items.map { it.name }.toSet() != names.toSet() }) throw DrivineException("Each query of a UNION returns the same names, as in Cypher.")
            val distinct = !query.unions.first().all
            val projected = parts.map { calledPart(select, it, names, distinct) }
            if (projected.map { p -> p.scope.values.map { it::class } }.distinct().size > 1) unsupported("A UNION in CALL { … } whose queries return values of different kinds")
            val sql = projected.joinToString(if (distinct) "\nUNION\n" else "\nUNION ALL\n") { "SELECT * FROM (\n${indent(it.sql)}\n) ${alias("q")}" }
            return Projected(sql, projected.first().scope, emptyList())
        }

        private fun unwind(select: Select, unwind: Clause.Unwind) {
            val list = expression(unwind.list, select.scope)
            val name = alias("u")
            // A list of nodes or relationships unwinds to them, as they are now, so a write reaches them; a null item is a null one.
            if (list is Value.Entities) {
                select.source(elements(list.ids, name))
                val row = alias(if (list.relationships) "e" else "n")
                if (list.relationships) {
                    select.join("LEFT JOIN $edges $row ON $row.eid = ${idOf("$name.item", null)}")
                    select.bind(unwind.variable, Value.Relationship("$row.eid", "$row.src", "$row.dst", "$row.type", "$row.props"))
                } else {
                    select.join("LEFT JOIN $nodes $row ON $row.nid = ${idOf("$name.item", null)}")
                    select.bind(unwind.variable, Value.Node("$row.nid", "$row.labels", "$row.props", "$row.stamp"))
                }
                select.order += "$name.item_no" to false
                return
            }
            // A value that is no list is one row, and null no rows, as in Cypher.
            val items = shared(comparable(json(list))) { (x) ->
                "CASE WHEN ${typeOf(x)} = 'array' THEN $x WHEN NOT ${isJsonNull(x)} THEN ${comparable(Value.Json(jsonArray(listOf(x)), ordered = true))} END"
            }
            select.source(elements(items, name))
            select.bind(unwind.variable, Value.Json("$name.item"))
            select.order += "$name.item_no" to false
        }

        /**
         * The relationships one part of a pattern matched: one, its id [eids], or with [many] those a
         * variable-length relationship walked, a list of ids. Of the [types] it may have; [nodes] is the
         * list of the ids of the nodes it reaches after the one it starts from.
         */
        private inner class Hop(val eids: String, val many: Boolean, val types: List<String>, val nodes: String) {
            /** That no relationship of this hop is one of [other]'s. */
            fun distinctFrom(other: Hop): String = when {
                !many && !other.many -> "$eids <> ${other.eids}"
                !many -> "NOT (${idIn(eids, other.eids)})"
                !other.many -> "NOT (${idIn(other.eids, eids)})"
                else -> "NOT (${idsOverlap(eids, other.eids)})"
            }
        }

        /** Binds the patterns of one `MATCH` into [select]: their relationships are distinct from one another. */
        /** [patterns] matched over the rows [select] reads; [shortestWhere] is met by the paths of the last, a shortest path, see [shortest]. */
        private fun patterns(select: Select, patterns: List<Pattern>, shortestWhere: Expression? = null) {
            val hops = mutableListOf<Hop>()
            patterns.forEachIndexed { i, pattern -> pattern(select, pattern, hops, shortestWhere.takeIf { i == patterns.lastIndex }) }
            for (i in hops.indices) for (j in i + 1 until hops.size) {
                val (a, b) = hops[i] to hops[j]
                // Relationships of types no relationship has both of cannot be one.
                if (a.types.isEmpty() || b.types.isEmpty() || a.types.intersect(b.types.toSet()).isNotEmpty()) select.where += a.distinctFrom(b)
            }
        }

        private fun pattern(select: Select, pattern: Pattern, hops: MutableList<Hop>, shortestWhere: Expression? = null) {
            if (pattern.shortest != null) return shortest(select, pattern, shortestWhere)
            // A node bound before the pattern, or given properties, is where a walk starts best.
            val pinned = pattern.nodes.map { node -> node.properties != null || node.variable?.let { select.scope[it] } != null }
            val before = select.scope
            val bound = pattern.nodes.map { node ->
                val existing = node.variable?.let { select.scope[it] }
                val value = when (existing) {
                    is Value.Node -> existing
                    null -> {
                        val name = alias("n")
                        select.source("$nodes $name")
                        Value.Node("$name.nid", "$name.labels", "$name.props", "$name.stamp").also { value -> node.variable?.let { select.bind(it, value) } }
                    }
                    else -> throw DrivineException("${node.variable} is not a node, and is used as one in a pattern.")
                }
                if (node.labels.isNotEmpty()) select.where += hasLabels(value.labels, node.labels)
                node.properties?.let { select.where += propertiesMatch(value.props, it, select.scope) }
                value
            }
            pattern.relationships.forEachIndexed { i, relationship ->
                if (relationship.range != null) {
                    // Walked from its far node when only that one is pinned: the same relationships, read backwards.
                    hops += if (pinned[i + 1] && !pinned[i]) {
                        walk(select, relationship.reversed(), bound[i + 1].nid, bound[i].nid, backwards = true, seed(pattern.nodes[i + 1], before))
                    } else {
                        walk(select, relationship, bound[i].nid, bound[i + 1].nid, backwards = false, seed(pattern.nodes[i], before))
                    }
                    return@forEachIndexed
                }
                if (relationship.variable != null && select.scope[relationship.variable] != null) unsupported("A relationship variable bound before its pattern")
                val name = alias("e")
                select.source("$edges $name")
                val value = Value.Relationship("$name.eid", "$name.src", "$name.dst", "$name.type", "$name.props")
                relationship.variable?.let { select.bind(it, value) }
                val from = bound[i].nid
                val to = bound[i + 1].nid
                select.where += when (relationship.direction) {
                    RelationshipPattern.Direction.OUTGOING -> "${value.src} = $from AND ${value.dst} = $to"
                    RelationshipPattern.Direction.INCOMING -> "${value.dst} = $from AND ${value.src} = $to"
                    // Either way, and a relationship from a node to itself once.
                    RelationshipPattern.Direction.EITHER -> "((${value.src} = $from AND ${value.dst} = $to) OR (${value.dst} = $from AND ${value.src} = $to))"
                }
                types(value.type, relationship.types)?.let { select.where += it }
                relationship.properties?.let { select.where += propertiesMatch(value.props, it, select.scope) }
                hops += Hop(value.eid, false, relationship.types, ids(to))
            }
            pattern.variable?.let { variable ->
                val mine = hops.takeLast(pattern.relationships.size)
                val nids = joinIds(listOf(ids(bound.first().nid)) + mine.map { it.nodes })
                val eids = mine.map { if (it.many) it.eids else ids(it.eids) }.let { if (it.isEmpty()) noIds else joinIds(it) }
                select.bind(variable, Value.Path(nids, eids))
            }
        }

        /**
         * `shortestPath(…)` or `allShortestPaths(…)`: the pattern's paths for the row, found in a lateral query, of
         * which the shortest between each two nodes it joins is kept, or every one of the least length. Its
         * relationship is a walk, so a path of it takes no relationship twice, as Cypher's shortest path takes none.
         * The paths are those that meet [where], the `WHERE` of its `MATCH`, as Cypher's shortest path is the least of those.
         */
        private fun shortest(select: Select, pattern: Pattern, where: Expression?) {
            val relationship = pattern.relationships.singleOrNull()
            if (relationship?.range == null) unsupported("A shortest path of anything but one variable-length relationship")
            val inner = Select(select.scope)
            val hops = mutableListOf<Hop>()
            // Its ends named, to find the shortest between each two of them where they were not bound before it.
            val ends = listOf(pattern.nodes.first().variable ?: SHORTEST_FROM, pattern.nodes.last().variable ?: SHORTEST_TO)
            pattern(inner, pattern.copy(nodes = listOf(pattern.nodes.first().copy(variable = ends[0]), pattern.nodes.last().copy(variable = ends[1])), shortest = null), hops)
            where?.let { inner.where += bool(expression(it, inner.scope)) }
            val found = inner.bound.filterKeys { it !in select.scope && it !in setOf(SHORTEST_FROM, SHORTEST_TO) }.entries.map { (variable, value) -> Triple(variable, value, alias("v")) }
            val length = number(idCount(hops.single().eids))
            val (from, to) = ends.map { (inner.scope.getValue(it) as Value.Node).nid }
            val columns = found.flatMap { (_, value, column) -> value.columns(column) } + "$length AS len" + "$from AS from_nid" + "$to AS to_nid"
            val paths = (listOf("SELECT ${columns.joinToString(", ")}") + inner.from() + listOfNotNull(inner.whereClause())).joinToString("\n")
            val name = alias("sp")
            val s = alias("s")
            val text = when (pattern.shortest) {
                Pattern.Shortest.ONE -> {
                    val ranked = "SELECT $s.*, ROW_NUMBER() OVER (PARTITION BY $s.from_nid, $s.to_nid ORDER BY $s.len) AS k FROM (\n${indent(paths)}\n) $s"
                    "SELECT * FROM (\n${indent(ranked)}\n) ${alias("s")}\nWHERE k = 1"
                }
                // Every path as short as the shortest.
                else -> {
                    val least = "SELECT $s.*, MIN($s.len) OVER (PARTITION BY $s.from_nid, $s.to_nid) AS least FROM (\n${indent(paths)}\n) $s"
                    "SELECT * FROM (\n${indent(least)}\n) ${alias("s")}\nWHERE len = least"
                }
            }
            select.join("CROSS JOIN LATERAL (\n${indent(text)}\n) $name")
            found.forEach { (variable, value, column) -> select.bind(variable, value.columnsOf(name, column)) }
        }

        /** That the relationship type [type] is one of [types]; null when any will do. */
        private fun types(type: String, types: List<String>): String? = when (types.size) {
            0 -> null
            1 -> "$type = ${literal(types.single())}"
            else -> "$type IN (${types.joinToString(", ") { literal(it) }})"
        }

        /**
         * What the node [node] meets of its own, before a walk from it, over a node row: its labels, and its
         * properties where their values do not depend on a row. Null for a node bound before its pattern,
         * which is found where it was bound, or with nothing of its own to meet.
         */
        private fun seed(node: NodePattern, before: Map<String, Value>): ((String) -> String)? {
            if (node.variable?.let { before[it] } != null) return null
            val properties = node.properties?.takeIf { !contains(it) { e -> e is Expression.Variable || e is Expression.Property } }
            if (node.labels.isEmpty() && properties == null) return null
            return { row ->
                (listOfNotNull(node.labels.takeIf { it.isNotEmpty() }?.let { hasLabels("$row.labels", it) }) +
                    listOfNotNull(properties?.let { propertiesMatch("$row.props", it, before) })).joinToString(" AND ")
            }
        }

        /**
         * A variable-length relationship, `-[:T*min..max]->`, from the node [from] to the node [to]: a walk
         * from [from] that takes a relationship no more than once, as Cypher takes it, to a depth from
         * `min` (1 if left out) to `max` (any if left out), its rows each way it reaches [to]. Walked
         * [backwards], from the pattern's later node, it keeps its nodes and relationships in the pattern's order.
         */
        private fun walk(select: Select, relationship: RelationshipPattern, from: String, to: String, backwards: Boolean, seed: ((String) -> String)?): Hop {
            val range = relationship.range!!
            val edge = alias("e")
            val conditions = listOfNotNull(
                types("$edge.type", relationship.types),
                relationship.properties?.let { propertiesMatch("$edge.props", it, select.scope) },
            )
            val walked = walk(
                Walk(from, to, alias("p"), alias("w"), edge, relationship.direction, range.min ?: 1, range.max, conditions, backwards, seed)
            )
            select.source(walked.source)
            select.where += walked.condition
            // A variable on it is the list of its relationships.
            relationship.variable?.let { select.bind(it, relationshipList(walked.eids)) }
            return Hop(walked.eids, true, relationship.types, walked.nids)
        }

        /** The nodes whose ids are the store's list [nids], in its order: null where the list is. */
        private fun nodeList(nids: String): Value.Entities = Value.Entities(idList(nids), relationships = false)

        /** The relationships whose ids are the store's list [eids], in its order: null where the list is. */
        private fun relationshipList(eids: String): Value.Entities = Value.Entities(idList(eids), relationships = true)

        /** The store's list of ids [list] as a JSON list of them, in its order: null where the list is. */
        private fun idList(list: String): String {
            val u = alias("u")
            return "CASE WHEN $list IS NOT NULL THEN (SELECT ${comparable(Value.Json(aggregateList(scalar("$u.id"), "$u.ord"), ordered = true))} FROM ${idRows(list, u)}) END"
        }

        /** The nodes or relationships [entities] lists, as a JSON list of their properties in its order: null where the list is. */
        private fun entitiesJson(entities: Value.Entities): String = entities.gathered ?: shared(entities.ids) { (ids) ->
            val u = alias("u")
            val row = alias(if (entities.relationships) "e" else "n")
            val (table, value) = if (entities.relationships) {
                edges to json(Value.Relationship("$row.eid", "$row.src", "$row.dst", "$row.type", "$row.props")).sql
            } else {
                nodes to json(Value.Node("$row.nid", "$row.labels", "$row.props", "$row.stamp")).sql
            }
            val id = if (entities.relationships) "eid" else "nid"
            "CASE WHEN $ids IS NOT NULL THEN (SELECT ${aggregateList(value, "$u.item_no")} FROM ${elements(ids, u)} LEFT JOIN $table $row ON $row.$id = ${idOf("$u.item", null)}) END"
        }

        private fun RelationshipPattern.reversed() = copy(
            direction = when (direction) {
                RelationshipPattern.Direction.OUTGOING -> RelationshipPattern.Direction.INCOMING
                RelationshipPattern.Direction.INCOMING -> RelationshipPattern.Direction.OUTGOING
                RelationshipPattern.Direction.EITHER -> RelationshipPattern.Direction.EITHER
            }
        )

        /** A pattern's properties: each key equal to its value, or every key of a parameter's map. */
        private fun propertiesMatch(props: String, properties: Expression, scope: Map<String, Value>): String = when (properties) {
            is Expression.MapLiteral -> properties.entries.joinToString(" AND ") { (key, value) ->
                equality(Value.Json(propertyOf(props, key)), expression(value, scope), value)
            }
            else -> containsAll(props, comparable(json(expression(properties, scope))))
        }

        // ----- A statement that writes -----

        /** The procedure's statements, in order. */
        private val body = mutableListOf<String>()

        /** How deep in loops and conditions the next statement is, for its indent. */
        private var depth = 0

        /** The variables a row holds, and what each is: what [ROWS] and [ROW] carry from clause to clause. */
        private val kinds = linkedMapOf<String, Kind>()

        /** The key each variable is held at in a row: a name of the compiler's, so any variable's name will do. */
        private val keys = hashMapOf<String, String>()

        /** The rows the last write left, as read from [ROWS], and how much was read of them then: nothing more is there to carry. */
        private var unread: Pair<Select, Int>? = null

        /** The statements of the procedure [query] is: its reads, a loop over its rows for each write, and what it returns. */
        fun procedure(query: Query): List<String> {
            var select = Select(emptyMap())
            for (clause in creates(query.clauses)) {
                if (clause is Clause.Return) returnRows(project(select, clause.projection, final = true).sql).forEach(::emit) else select = next(select, clause)
            }
            return body
        }

        /** [clause] over the rows [select] reads, written for them where it writes: the rows the clauses after it read. */
        private fun next(select: Select, clause: Clause): Select {
            if (!writes(clause)) return read(select, clause)
            carry(select)
            when (clause) {
                is Clause.Foreach -> foreach(clause)
                is Clause.Call -> callWriting(clause)
                else -> write(clause)
            }
            return rows()
        }

        /** [clauses] with each run of `CREATE`s one `CREATE` of all their patterns, which makes the same, in one loop over the rows. */
        private fun creates(clauses: List<Clause>): List<Clause> = clauses.fold(mutableListOf()) { merged, clause ->
            val last = merged.lastOrNull()
            if (clause is Clause.Create && last is Clause.Create) merged[merged.lastIndex] = Clause.Create(last.patterns + clause.patterns) else merged += clause
            merged
        }

        private fun emit(statement: String) {
            body += statement.prependIndent("    ".repeat(depth))
        }

        private fun assign(variable: String, sql: String) = emit("SELECT $sql INTO $variable;")

        private fun key(variable: String) = keys.getOrPut(variable) { "v${keys.size + 1}" }

        /** The rows [select] reads, each a JSON map of the variables it binds, into [ROWS]. */
        private fun carry(select: Select) {
            if (unread?.let { (rows, size) -> rows === select && size == select.size() } == true) return
            kinds.clear()
            val entries = select.scope.map { (variable, value) ->
                kinds[variable] = when (value) {
                    is Value.Node -> Kind.NODE
                    is Value.Relationship -> Kind.RELATIONSHIP
                    is Value.Json, is Value.Bool -> Kind.VALUE
                    is Value.Entities -> if (value.relationships) Kind.RELATIONSHIPS else Kind.NODES
                    is Value.Path -> Kind.PATH
                }
                key(variable) to held(value)
            }
            val list = comparable(Value.Json(aggregateList(jsonObject(entries), orderOf(select)), ordered = true))
            emit((listOf("SELECT $list INTO $ROWS") + from(select) + listOfNotNull(select.whereClause())).joinToString("\n") + ";")
        }

        /** A value as a row holds it: a node or a relationship as its id, anything else as JSON. */
        private fun held(value: Value): String = when (value) {
            is Value.Node -> scalar(value.nid)
            is Value.Relationship -> scalar(value.eid)
            is Value.Entities -> value.ids
            // A path as the JSON lists of its nodes' and relationships' ids.
            is Value.Path -> comparable(Value.Json(jsonObject(listOf("n" to idList(value.nids), "e" to idList(value.eids))), ordered = true))
            else -> comparable(json(value))
        }

        private fun from(select: Select): List<String> = select.from().ifEmpty { listOf("FROM (SELECT 1) one") }

        /** The order [select] reads its rows in, as an `ORDER BY` lists it: null where it has none. */
        private fun orderOf(select: Select): String? =
            select.order.joinToString(", ") { (term, descending) -> if (descending) "$term DESC" else term }.ifEmpty { null }

        /** The rows in [ROWS], as a query reads them. */
        private fun rows(): Select {
            val select = Select(emptyMap())
            val r = alias("r")
            select.source(elements(ROWS, r))
            select.order += "$r.item_no" to false
            bindRow(select, "$r.item")
            unread = select to select.size()
            return select
        }

        /**
         * The row in [ROW] alone, as a statement of a write reads it, with the variables [needed] bound: a row
         * may hold many, and each node or relationship bound is a join.
         */
        private fun row(needed: Set<String>?): Select = Select(emptyMap()).also { bindRow(it, ROW, needed) }

        /** Binds into [select] each variable the row [row] holds, of [needed] where given: a node or a relationship is read from its table, as it is now. */
        private fun bindRow(select: Select, row: String, needed: Set<String>? = null) {
            kinds.filterKeys { needed == null || it in needed }.forEach { (variable, kind) ->
                val key = key(variable)
                when (kind) {
                    Kind.NODE -> {
                        val n = alias("n")
                        select.join("LEFT JOIN $nodes $n ON $n.nid = ${idOf(row, key)}")
                        select.bind(variable, Value.Node("$n.nid", "$n.labels", "$n.props", "$n.stamp"))
                    }
                    Kind.RELATIONSHIP -> {
                        val e = alias("e")
                        select.join("LEFT JOIN $edges $e ON $e.eid = ${idOf(row, key)}")
                        select.bind(variable, Value.Relationship("$e.eid", "$e.src", "$e.dst", "$e.type", "$e.props"))
                    }
                    Kind.VALUE -> select.bind(variable, Value.Json(propertyOf(row, key)))
                    // A list or a path that is null is held as JSON's null, and read as SQL's.
                    Kind.NODES, Kind.RELATIONSHIPS -> select.bind(variable, Value.Entities(nullIfJsonNull(propertyOf(row, key)), kind == Kind.RELATIONSHIPS))
                    Kind.PATH -> select.bind(
                        variable,
                        Value.Path(storeIds(nullIfJsonNull(propertyOf(propertyOf(row, key), "n"))), storeIds(nullIfJsonNull(propertyOf(propertyOf(row, key), "e")))),
                    )
                }
            }
        }

        /**
         * [value], worked out over the row in [ROW], into [variable]: [expressions] are what it reads. A subquery among
         * them reads every variable of the row, for [variablesIn] names none of a subquery's.
         */
        private fun evaluate(variable: String, vararg expressions: Expression?, value: (Map<String, Value>) -> String) {
            val read = expressions.filterNotNull()
            val select = row(if (read.any { contains(it) { e -> e is Expression.Subquery } }) null else read.flatMap(::variablesIn).toSet())
            emit((listOf("SELECT ${value(select.scope)} INTO $variable") + from(select)).joinToString("\n") + ";")
        }

        /** One write: made for each row of [ROWS] in turn, and the rows it leaves put back there. */
        private fun write(clause: Clause) {
            assign(OUT, literalJson(emptyList<Any>()))
            val deleting = clause is Clause.Delete && !clause.detach
            if (deleting) assign(DOOMED, literalJson(emptyList<Any>()))
            val r = alias("r")
            emit("FOR $RECORD IN (SELECT $r.item FROM ${elements(ROWS, r)} ORDER BY $r.item_no) LOOP")
            depth++
            emit("$ROW := $RECORD.item;")
            when (clause) {
                is Clause.Create -> {
                    clause.patterns.forEach { create(it, merging = false) }
                    keep()
                }
                is Clause.Merge -> merge(clause)
                is Clause.Set -> {
                    clause.items.forEach(::set)
                    keep()
                }
                is Clause.Remove -> {
                    clause.items.forEach(::remove)
                    keep()
                }
                is Clause.Delete -> {
                    delete(clause)
                    keep()
                }
                else -> error("${clause::class.simpleName} reads")
            }
            depth--
            emit("END LOOP;")
            emit("$ROWS := $OUT;")
            if (deleting) {
                // A node is deleted when the clause has deleted the relationships it deletes: one it does not delete must not be there.
                val d = alias("d")
                val doomed = "SELECT ${idOf("$d.item", null)} FROM ${elements(DOOMED, d)}"
                emit("SELECT count(*) INTO $COUNT FROM $edges WHERE src IN ($doomed) OR dst IN ($doomed);")
                emit("IF $COUNT > 0 THEN")
                emit("    ${raise("A node is deleted that still has relationships: delete them first, or DETACH DELETE it.")}")
                emit("END IF;")
                emit("DELETE FROM $nodes WHERE nid IN ($doomed);")
            }
        }

        /**
         * A `CALL { … }` that writes, over the rows in [ROWS]: its clauses written for each row alone, in turn, as a
         * graph engine writes them, so what one row writes is there for the next to read. One that returns nothing
         * leaves the rows as it found them; one that returns adds what it returns to each row it gives.
         */
        private fun callWriting(call: Clause.Call) {
            if (call.optional) unsupported("An OPTIONAL CALL { … } that writes")
            if (call.query.unions.isNotEmpty()) unsupported("A UNION in CALL { … }")
            val ret = call.query.clauses.last() as? Clause.Return
            if (ret != null && ret.projection.items.any { contains(it.expression, ::isAggregate) }) unsupported("An aggregate in the RETURN of a CALL { … } that writes")
            if (nesting == NESTING) unsupported("A FOREACH or CALL { … } that writes nested more than $NESTING deep")
            val kept = "$SAVED${++nesting}"
            val gathered = "$GATHERED$nesting"
            val before = LinkedHashMap(kinds)
            emit("$kept := $ROWS;")
            if (ret != null) assign(gathered, literalJson(emptyList<Any>()))
            eachRow(kept) {
                var select = rows()
                for (clause in imported(call.query.clauses)) {
                    select = if (clause is Clause.Return) {
                        // What it returns joins what the row held.
                        val items = before.keys.map { Projection.Item(Expression.Variable(it), it) } + clause.projection.items
                        with(select, Clause.With(clause.projection.copy(star = false, items = items), null))
                    } else {
                        next(select, clause)
                    }
                }
                if (ret != null) {
                    // The rows this row gives, after those of the rows before it.
                    carry(select)
                    val (g, a, b) = listOf(alias("g"), alias("g"), alias("g"))
                    val both = "SELECT 1 AS part, $a.item_no, $a.item FROM ${elements(gathered, a)} UNION ALL SELECT 2 AS part, $b.item_no, $b.item FROM ${elements(ROWS, b)}"
                    emit("SELECT ${comparable(Value.Json(aggregateList("$g.item", "$g.part, $g.item_no"), ordered = true))} INTO $gathered FROM ($both) $g;")
                }
            }
            if (ret == null) {
                emit("$ROWS := $kept;")
                kinds.clear()
                kinds.putAll(before)
            } else {
                emit("$ROWS := $gathered;")
            }
            unread = null
            nesting--
        }

        /**
         * Writes [body] for each row of [rows], a variable of the procedure, alone in [ROWS] in turn, as a graph engine
         * writes a `FOREACH` or a `CALL { … }` for each row: what one row writes is there for the next to read.
         */
        private fun eachRow(rows: String, body: () -> Unit) {
            val r = alias("r")
            emit("FOR $RECORD IN (SELECT $r.item FROM ${elements(rows, r)} ORDER BY $r.item_no) LOOP")
            depth++
            assign(ROWS, appended(literalJson(emptyList<Any>()), "$RECORD.item"))
            unread = null
            body()
            depth--
            emit("END LOOP;")
        }

        /** [clauses] without an importing `WITH` of variables alone that opens them: every variable of the row is seen. */
        private fun imported(clauses: List<Clause>): List<Clause> {
            val first = clauses.firstOrNull() as? Clause.With ?: return clauses
            val projection = first.projection
            val importing = first.where == null && !projection.distinct && projection.orderBy.isEmpty() && projection.skip == null && projection.limit == null &&
                projection.items.all { (it.expression as? Expression.Variable)?.name == it.name }
            return if (importing) clauses.drop(1) else clauses
        }

        /** The parameters an expression reads from a map it is given, where it reads none bound: see [Expression.Reduce]. */
        private var givenParameters: Map<String, Value>? = null

        /** How deep in `FOREACH`es and writing `CALL`s the write being made is: each keeps the rows it puts back in a variable of its own depth. */
        private var nesting = 0

        /**
         * A `FOREACH` over the rows in [ROWS]: its list unwound over them, its clauses written for each row and item
         * alone, in turn, then the rows put back as they were, for a `FOREACH` leaves them as it found them.
         */
        private fun foreach(foreach: Clause.Foreach) {
            if (nesting == NESTING) unsupported("A FOREACH or CALL { … } that writes nested more than $NESTING deep")
            if (foreach.clauses.any { it is Clause.Call }) throw DrivineException("A CALL { … } is not written in a FOREACH, as in Cypher.")
            val saved = "$SAVED${++nesting}"
            val items = "$GATHERED$nesting"
            val before = LinkedHashMap(kinds)
            emit("$saved := $ROWS;")
            val unwound = rows()
            unwind(unwound, Clause.Unwind(foreach.list, foreach.variable))
            carry(unwound)
            emit("$items := $ROWS;")
            eachRow(items) {
                var select = rows()
                for (clause in creates(foreach.clauses)) select = next(select, clause)
            }
            emit("$ROWS := $saved;")
            kinds.clear()
            kinds.putAll(before)
            unread = null
            nesting--
        }

        /** The row in [ROW] kept, as a row of what the write leaves. */
        private fun keep() = assign(OUT, appended(OUT, ROW))

        /**
         * Makes [pattern] for the row in [ROW]: each node not bound before it, and each relationship, with the
         * row given the ids of those it names. With [merging], an undirected relationship is made from the
         * pattern's earlier node, as a `MERGE` makes it.
         */
        private fun create(pattern: Pattern, merging: Boolean) {
            if (pattern.variable != null) unsupported("A path variable in CREATE or MERGE")
            if (pattern.shortest != null) unsupported("A shortest path in CREATE")
            val ids = pattern.nodes.map { node ->
                when (node.variable?.let { kinds[it] }) {
                    Kind.NODE -> {
                        if (node.labels.isNotEmpty() || node.properties != null) {
                            throw DrivineException("${node.variable} is bound already, so a CREATE or MERGE makes no node of it with labels or properties.")
                        }
                        idOf(ROW, key(node.variable!!))
                    }
                    null -> {
                        evaluate(VALUE, node.properties) { scope -> written(node.properties, scope) }
                        emit(
                            "INSERT INTO $nodes (labels, props, stamp) VALUES (${literalJson(node.labels)}, ${stripNulls(unstamped(VALUE))}, ${stampOf(VALUE)}) " +
                                "RETURNING nid INTO $ID;"
                        )
                        made(node.variable, Kind.NODE)
                    }
                    else -> throw DrivineException("${node.variable} is not a node, and is used as one in a pattern.")
                }
            }
            pattern.relationships.forEachIndexed { i, relationship ->
                if (relationship.range != null) unsupported("A variable-length relationship in CREATE or MERGE")
                val type = relationship.types.singleOrNull() ?: throw DrivineException("A relationship that CREATE or MERGE makes has one type.")
                if (relationship.variable != null && relationship.variable in kinds) unsupported("A relationship variable bound before its pattern")
                val (src, dst) = when (relationship.direction) {
                    RelationshipPattern.Direction.OUTGOING -> ids[i] to ids[i + 1]
                    RelationshipPattern.Direction.INCOMING -> ids[i + 1] to ids[i]
                    RelationshipPattern.Direction.EITHER ->
                        if (merging) ids[i] to ids[i + 1] else throw DrivineException("A relationship that CREATE makes has a direction.")
                }
                evaluate(VALUE, relationship.properties) { scope -> written(relationship.properties, scope) }
                emit("INSERT INTO $edges (src, dst, type, props) VALUES ($src, $dst, ${literal(type)}, ${stripNulls(VALUE)}) RETURNING eid INTO $ID;")
                made(relationship.variable, Kind.RELATIONSHIP)
            }
        }

        /** The properties a pattern writes, as JSON: none where it gives none. */
        private fun written(properties: Expression?, scope: Map<String, Value>): String =
            properties?.let { comparable(json(expression(it, scope))) } ?: literalJson(emptyMap<String, Any>())

        /** The id in [ID] of what was made, held in the row at [variable]'s key, or at a key of its own for one of no variable. */
        private fun made(variable: String?, kind: Kind): String {
            val key = variable?.let(::key) ?: "a${++counter}"
            assign(ROW, withEntries(ROW, listOf(key to scalar(ID))))
            variable?.let { kinds[it] = kind }
            return idOf(ROW, key)
        }

        /**
         * A `MERGE` for the row in [ROW]: a row for each way its pattern is found, with `ON MATCH SET`
         * written to it, or where it is not found, the pattern made, with `ON CREATE SET`.
         */
        private fun merge(merge: Clause.Merge) {
            if (merge.pattern.variable != null) unsupported("A path variable in CREATE or MERGE")
            val select = row(variablesIn(merge.pattern))
            patterns(select, listOf(merge.pattern))
            val found = select.bound.filterKeys { it !in kinds }
            val before = LinkedHashMap(kinds)
            val match = listOf("SELECT ${withEntries(ROW, found.map { (variable, value) -> key(variable) to held(value) })} AS item") +
                from(select) + listOfNotNull(select.whereClause())
            emit("$FOUND := FALSE;")
            emit("FOR $MATCHED IN (\n${indent(match.joinToString("\n"))}\n) LOOP")
            depth++
            emit("$FOUND := TRUE;")
            emit("$ROW := $MATCHED.item;")
            found.forEach { (variable, value) -> kinds[variable] = if (value is Value.Node) Kind.NODE else Kind.RELATIONSHIP }
            merge.onMatch.forEach(::set)
            keep()
            depth--
            emit("END LOOP;")
            kinds.clear()
            kinds.putAll(before)
            emit("IF NOT $FOUND THEN")
            depth++
            create(merge.pattern, merging = true)
            merge.onCreate.forEach(::set)
            keep()
            depth--
            emit("END IF;")
        }

        /** The table [variable]'s node or relationship is a row of, its key column, and its id in [ROW]. */
        private fun target(variable: String, writing: String): Triple<String, String, String> = when (kinds[variable]) {
            Kind.NODE -> Triple(nodes, "nid", idOf(ROW, key(variable)))
            Kind.RELATIONSHIP -> Triple(edges, "eid", idOf(ROW, key(variable)))
            Kind.VALUE, Kind.NODES, Kind.RELATIONSHIPS, Kind.PATH -> throw DrivineException("$variable is not a node or a relationship, and $writing writes to one.")
            null -> throw DrivineException("$variable is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.")
        }

        private fun set(item: SetItem) {
            when (item) {
                is SetItem.Property -> {
                    val (table, id, row) = target(item.variable, "SET")
                    evaluate(VALUE, item.value) { scope -> comparable(json(expression(item.value, scope))) }
                    if (table == nodes && item.key == Stamps.PROPERTY) emit("UPDATE $nodes SET stamp = ${text(VALUE)} WHERE nid = $row;")
                    else emit("UPDATE $table SET props = ${merged("props", jsonObject(listOf(item.key to VALUE)))} WHERE $id = $row;")
                }
                is SetItem.Replace -> {
                    val (table, id, row) = target(item.variable, "SET")
                    evaluate(VALUE, item.value) { scope -> properties(expression(item.value, scope)) }
                    if (table == nodes) emit("UPDATE $nodes SET props = ${stripNulls(unstamped(orEmpty(VALUE)))}, stamp = ${stampOf(VALUE)} WHERE nid = $row;")
                    else emit("UPDATE $table SET props = ${stripNulls(orEmpty(VALUE))} WHERE $id = $row;")
                }
                is SetItem.Add -> {
                    val (table, id, row) = target(item.variable, "SET")
                    evaluate(VALUE, item.value) { scope -> properties(expression(item.value, scope)) }
                    if (table == nodes) {
                        // The stamp is kept where the map has no entry for it, and removed where its entry is null.
                        val stamp = "CASE WHEN ${propertyOf(VALUE, Stamps.PROPERTY)} IS NULL THEN stamp ELSE ${stampOf(VALUE)} END"
                        emit("UPDATE $nodes SET props = ${merged("props", unstamped(orEmpty(VALUE)))}, stamp = $stamp WHERE nid = $row;")
                    } else {
                        emit("UPDATE $table SET props = ${merged("props", orEmpty(VALUE))} WHERE $id = $row;")
                    }
                }
                is SetItem.Labels -> {
                    val (table, id, row) = target(item.variable, "SET")
                    if (table != nodes) throw DrivineException("${item.variable} is not a node, and a label is set on one.")
                    item.labels.forEach { label ->
                        emit("UPDATE $nodes SET labels = ${appended("labels", literalJson(label))} WHERE $id = $row AND NOT ${hasLabel("labels", label)};")
                    }
                }
            }
        }

        private fun remove(item: RemoveItem) {
            when (item) {
                is RemoveItem.Property -> {
                    val (table, id, row) = target(item.variable, "REMOVE")
                    if (table == nodes && item.key == Stamps.PROPERTY) emit("UPDATE $nodes SET stamp = NULL WHERE nid = $row;")
                    else emit("UPDATE $table SET props = ${merged("props", jsonObject(listOf(item.key to literalJson(null))))} WHERE $id = $row;")
                }
                is RemoveItem.Labels -> {
                    val (table, id, row) = target(item.variable, "REMOVE")
                    if (table != nodes) throw DrivineException("${item.variable} is not a node, and a label is removed from one.")
                    item.labels.forEach { label -> emit("UPDATE $nodes SET labels = ${withoutLabel("labels", label)} WHERE $id = $row;") }
                }
            }
        }

        /** What `SET n = …` and `SET n += …` write: a node's properties, its stamp among them, a relationship's, or a map. */
        private fun properties(value: Value): String = when (value) {
            is Value.Relationship -> value.props
            else -> comparable(json(value))
        }

        /** The JSON [json], or an empty map where it is null. */
        private fun orEmpty(json: String) = "COALESCE(${nullIfJsonNull(json)}, ${literalJson(emptyMap<String, Any>())})"

        /** The JSON map [json] with its entry for the stamp null: a node's stamp is a column of its row, and no property of its map. */
        private fun unstamped(json: String) = withEntries(json, listOf(Stamps.PROPERTY to literalJson(null)))

        /** The stamp the JSON map [json] gives a node, as the text its column holds. */
        private fun stampOf(json: String) = text(propertyOf(json, Stamps.PROPERTY))

        /**
         * A `DELETE` for the row in [ROW]: its relationships first, then its nodes, each with its relationships
         * where it is `DETACH`; without, a node is gathered in [DOOMED] and deleted once the rows are done.
         */
        private fun delete(delete: Clause.Delete) {
            val targets = delete.expressions.map { expression ->
                val variable = (expression as? Expression.Variable)?.name ?: unsupported("A DELETE of anything but a variable")
                variable to target(variable, "DELETE")
            }
            targets.filter { it.second.first == edges }.forEach { (_, target) -> emit("DELETE FROM $edges WHERE eid = ${target.third};") }
            targets.filter { it.second.first == nodes }.forEach { (_, target) ->
                if (delete.detach) emit("DELETE FROM $nodes WHERE nid = ${target.third};")
                else assign(DOOMED, appended(DOOMED, scalar(target.third)))
            }
        }

        // ----- Projection -----

        /**
         * What a `WITH` or a `RETURN` makes of the rows it reads: the [sql] of its query, what each item's name is
         * bound to among its columns, and the order of its rows, each term a column of the query and whether it descends.
         */
        inner class Projected(val sql: String, val scope: Map<String, Value>, val order: List<Pair<String, Boolean>>)

        /**
         * The query a `WITH` or a `RETURN` makes of [select]: its items as columns, grouped where it
         * aggregates, then ordered, skipped and limited. Rows it does not order, group or keep distinct
         * stay in the order [select] reads them, as a graph engine keeps them.
         */
        fun project(select: Select, projection: Projection, final: Boolean): Projected {
            val items = (if (projection.star) select.bound.keys.map { Projection.Item(Expression.Variable(it), it) } else emptyList()) + projection.items
            if (items.isEmpty()) throw DrivineException("A projection has at least one item.")
            heldFirst(select, projection, items)?.let { (held, keyed) -> return project(held, keyed, final) }
            if (final && items.size > 1) {
                logger.warn(
                    "The statement returns {} items ({}). Prefer returning a single map or value, such as RETURN {{ key1: val1, key2: val2 }}: " +
                        "on Oracle and Postgres it is returned as a list of its items, as drivine gives a row of several columns.",
                    items.size, items.joinToString { it.name },
                )
            }
            requireExplicitGrouping(items)
            rowOrder = orderOf(select)
            val compiled = items.map { item -> item.name to aggregating(item.expression) { expression(item.expression, select.scope) } }
            val aggregating = items.any { contains(it.expression, ::isAggregate) }
            // Where rows are compared (DISTINCT, GROUP BY), JSON is compared as what compares.
            val compared = projection.distinct || aggregating
            val columns = compiled.mapIndexed { i, (_, value) -> "c${i + 1}" to (if (compared && value is Value.Json && value.ordered) Value.Json(comparable(value)) else value) }
            val selectList = columns.flatMap { (column, value) -> value.columns(column) }.toMutableList()
            // An order is written over the items' names, and where nothing is grouped or kept distinct, the variables too.
            val name = alias("p")
            // Where rows are compared, an order term of the items that is no item is read over their columns, once compared:
            // a group is keyed by an item, and by no part of one.
            val itemColumns = items.zip(columns).associate { (item, pair) -> item.name to pair.second.columnsOf(name, pair.first) }
            val after = mutableMapOf<String, String>()
            val sorts = projection.orderBy.mapIndexed { i, sort ->
                val column = "s${i + 1}"
                // An order term that is an item, as written, is that item: so it may be ordered by where rows are compared.
                val item = items.indexOfFirst { it.expression == sort.expression }
                when {
                    item >= 0 -> selectList += "${aggregating(sort.expression) { sortable(compiled[item].second) }} AS $column"
                    compared && !contains(sort.expression, ::isAggregate) -> after[column] = sortable(expression(sort.expression, itemColumns))
                    // One that aggregates reads the rows it aggregates, as an item does.
                    else -> selectList += "${aggregating(sort.expression) { sortable(expression(sort.expression, select.scope + compiled.toMap())) }} AS $column"
                }
                column to sort.descending
            }.ifEmpty {
                if (compared) emptyList() else select.order.mapIndexed { i, (term, descending) ->
                    val name = "o${i + 1}"
                    selectList += "$term AS $name"
                    name to descending
                }
            }
            val groupBy = if (!aggregating) emptyList() else {
                val keys = mutableListOf<String>()
                items.zip(columns).forEach { (item, pair) ->
                    if (!contains(item.expression, ::isAggregate)) keys += pair.second.groupKeys()
                    else variablesOutsideAggregates(item.expression).forEach { variable -> select.scope[variable]?.let { keys += it.groupKeys() } }
                }
                keys.distinct()
            }
            val inner = buildList {
                add("SELECT ${if (projection.distinct) "DISTINCT " else ""}${selectList.joinToString(",\n       ")}")
                addAll(select.from())
                select.whereClause()?.let(::add)
                if (groupBy.isNotEmpty()) add("GROUP BY ${groupBy.joinToString(", ")}")
            }.joinToString("\n")
            val outerColumns = if (final) {
                val values = columns.map { (column, value) -> json(value.columnsOf(name, column)) }
                val result = if (values.size == 1) values.single().sql else jsonArray(values.map { it.sql })
                "$result AS result"
            } else {
                (columns.flatMap { (column, value) -> value.columns(column).map { "$name.${it.substringAfterLast(" AS ")}" } } +
                    sorts.map { (column, _) -> after[column]?.let { "$it AS $column" } ?: "$name.$column" }).joinToString(", ")
            }
            val outer = buildList {
                add("SELECT $outerColumns")
                add("FROM (\n${indent(inner)}\n) $name")
                if (sorts.isNotEmpty()) add("ORDER BY ${sorts.joinToString(", ") { (column, descending) -> "${after[column] ?: "$name.$column"}${if (descending) " DESC" else ""}" }}")
                addAll(page(projection.skip?.let(::count), projection.limit?.let(::count)))
            }.joinToString("\n")
            // What each item is bound to in the query that reads this one: its columns, which that query names.
            val scope = items.zip(columns).associate { (item, pair) -> item.name to pair.second.columnsOf(PENDING, pair.first) }
            return Projected(outer, scope, sorts)
        }

        /**
         * Where a projection that aggregates would group by an item whose SQL holds a subquery, which Oracle
         * groups by no subquery, or a parameter, each mention of which the driver binds as a marker of its own,
         * so the item in the select list is not the one grouped by, the rows [select] reads with each such item
         * worked out first, in a `WITH *` of its own, and the projection with those items read from there, as
         * variables a group is keyed by; null where there is none.
         */
        private fun heldFirst(select: Select, projection: Projection, items: List<Projection.Item>): Pair<Select, Projection>? {
            if (items.none { contains(it.expression, ::isAggregate) }) return null
            val held = items.filter { item ->
                !contains(item.expression, ::isAggregate) && (
                    contains(item.expression) { it is Expression.Parameter } ||
                        expression(item.expression, select.scope).groupKeys().any { it.contains("SELECT", ignoreCase = true) }
                    )
            }
            if (held.isEmpty()) return null
            // Named as no variable is, unless quoted so.
            val names = held.associate { it.expression to "#key${++counter}" }
            val with = Projection(false, true, held.map { Projection.Item(it.expression, names.getValue(it.expression)) }, emptyList(), null, null)
            fun keyed(expression: Expression) = names[expression]?.let(Expression::Variable) ?: expression
            val keyed = projection.copy(
                star = false,
                items = items.map { it.copy(expression = keyed(it.expression)) },
                orderBy = projection.orderBy.map { it.copy(expression = keyed(it.expression)) },
            )
            return with(select, Clause.With(with, null)) to keyed
        }

        /**
         * The order of the rows the projection being compiled reads, which `collect` gathers them in, as Cypher
         * gathers them: a group's rows are not read in the order of the query they come from.
         */
        private var rowOrder: String? = null

        /** [work], with no value bound in a subquery where [expression] aggregates: see [sharing]. */
        private fun <T> aggregating(expression: Expression, work: () -> T): T {
            if (!contains(expression, ::isAggregate)) return work()
            val was = sharing
            sharing = false
            try {
                return work()
            } finally {
                sharing = was
            }
        }

        /**
         * Refuses what Cypher refuses: an item that aggregates and names a variable outside its aggregates, where
         * that variable is not an item of its own, which a graph engine would have to group by without being told.
         */
        private fun requireExplicitGrouping(items: List<Projection.Item>) {
            val keys = items.filterNot { contains(it.expression, ::isAggregate) }.mapNotNull { (it.expression as? Expression.Variable)?.name }.toSet()
            items.filter { contains(it.expression, ::isAggregate) }.forEach { item ->
                val implicit = variablesOutsideAggregates(item.expression) - keys
                if (implicit.isNotEmpty()) {
                    throw DrivineException(
                        "${item.name} aggregates, and names ${implicit.joinToString()} outside its aggregates, which no other item is: " +
                            "Cypher would group by it without being told. Aggregate in a WITH first, " +
                            "as in WITH ${implicit.joinToString()}, collect(…) AS items RETURN { … }."
                    )
                }
            }
        }

        /** A `SKIP` or `LIMIT`: a whole number, or a parameter bound to one. */
        private fun count(expression: Expression): String = when (expression) {
            is Expression.Literal -> (expression.value as? Long)?.toString() ?: throw DrivineException("SKIP and LIMIT take a whole number, not ${expression.value}.")
            is Expression.Parameter -> parameters.count(expression.name)
            else -> unsupported("SKIP or LIMIT of an expression")
        }

        // ----- Expressions -----

        fun expression(expression: Expression, scope: Map<String, Value>): Value = when (expression) {
            is Expression.Literal -> Value.Json(literalJson(expression.value))
            is Expression.Parameter -> givenParameters?.get(expression.name) ?: Value.Json(parameters.json(expression.name))
            is Expression.Variable -> scope[expression.name] ?: throw DrivineException("${expression.name} is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.")
            is Expression.Property -> {
                val subject = expression.subject
                // `datetime().epochMillis`: the moment, as Cypher's statements stamp a write with it.
                if (subject is Expression.Function && subject.name.lowercase() == "datetime" && subject.arguments.isEmpty() && expression.key == "epochMillis") {
                    Value.Json(epochMillisNow())
                } else {
                    property(expression(subject, scope), expression.key)
                }
            }
            is Expression.HasLabels -> when (val subject = expression(expression.subject, scope)) {
                is Value.Node -> Value.Bool("(${hasLabels(subject.labels, expression.labels)})")
                else -> throw DrivineException("A label is tested of a node.")
            }
            is Expression.ListLiteral -> {
                val items = expression.items.map { expression(it, scope) }
                // A null written among nodes or relationships is an item of a list of them, as a node that is null is.
                val entities = items.filterIndexed { i, _ -> expression.items[i] != Expression.Literal(null) }
                fun ids(relationships: Boolean): Value.Entities {
                    val listed = items.map { item ->
                        when (item) {
                            is Value.Node -> scalar(item.nid)
                            is Value.Relationship -> scalar(item.eid)
                            else -> json(item).sql
                        }
                    }
                    return Value.Entities(comparable(Value.Json(jsonArray(listed), ordered = true)), relationships)
                }
                when {
                    entities.isNotEmpty() && entities.all { it is Value.Node } -> ids(relationships = false)
                    entities.isNotEmpty() && entities.all { it is Value.Relationship } -> ids(relationships = true)
                    else -> Value.Json(jsonArray(items.map { json(it).sql }), ordered = true)
                }
            }
            is Expression.MapLiteral -> Value.Json(jsonObject(expression.entries.map { (key, value) -> key to json(expression(value, scope)).sql }), ordered = true)
            is Expression.MapProjection -> mapProjection(expression, scope)
            is Expression.Binary -> binary(expression, scope)
            is Expression.Unary -> unary(expression, scope)
            is Expression.Index -> {
                val index = expression.index
                val subject = { comparable(json(expression(expression.subject, scope))) }
                val at = comparable(json(expression(index, scope)))
                // An index written, or bound to a parameter, is known: a text is the property of that name, a number an item.
                val known = when (index) {
                    is Expression.Literal -> index.value
                    is Expression.Parameter -> parameters.value(index.name)
                    // `-1` is a negated number.
                    is Expression.Unary -> (index.operand as? Expression.Literal)?.value?.takeIf { index.operator == Expression.Unary.Operator.NEGATE && it is Number }
                    else -> null
                }
                when (known) {
                    is String -> property(expression(expression.subject, scope), known)
                    is Number -> Value.Json(element(subject(), at))
                    else -> Value.Json(
                        shared(subject(), at) { (map, key) ->
                            // A store that reads no key an expression gives reads a list's item alone.
                            val keyed = keyed(map, key)?.let { " WHEN 'string' THEN $it" }.orEmpty()
                            "CASE ${typeOf(key)} WHEN 'number' THEN ${element(map, key)}$keyed END"
                        }
                    )
                }
            }
            is Expression.Slice -> unsupported("A slice of a list (list[from..to])")
            is Expression.Function -> function(expression, scope)
            Expression.CountStar -> Value.Json(scalar("count(*)"))
            is Expression.Case -> case(expression, scope)
            is Expression.ListComprehension -> {
                val list = comparable(json(expression(expression.list, scope)))
                val name = alias("x")
                val inner = scope + (expression.variable to Value.Json("$name.item"))
                val projection = expression.projection?.let { json(expression(it, inner)).sql } ?: "$name.item"
                val filter = expression.where?.let { " WHERE ${bool(expression(it, inner))}" }.orEmpty()
                // Of null, null, as in Cypher, and not an empty list.
                Value.Json(
                    shared(list) { (items) ->
                        "CASE WHEN NOT ${isJsonNull(items)} THEN (SELECT ${aggregateList(projection, "$name.item_no")} FROM ${elements(items, name)}$filter) END"
                    },
                    ordered = true,
                )
            }
            is Expression.Reduce -> {
                val initial = comparable(json(expression(expression.initial, scope)))
                val list = comparable(json(expression(expression.list, scope)))
                val own = setOf(expression.accumulator, expression.variable)
                // What the step reads of the row and of the parameters, for a store that works it out where it reads neither.
                val read = (variablesIn(expression.expression) - own).filter { it in scope }
                val given = parametersIn(expression.expression)
                val context = {
                    // A path is no JSON a function may be given.
                    if (read.any { scope.getValue(it) is Value.Path }) throw DrivineException("A path read in the step of reduce() does not run on Oracle: its function is given the row as JSON.")
                    comparable(Value.Json(jsonObject(read.map { it to json(scope.getValue(it)).sql } + given.map { "$$it" to parameters.json(it) }), ordered = true))
                }
                Value.Json(
                    reduce(
                        alias("f"), initial, list,
                        step = { accumulator, item ->
                            val inner = scope + mapOf(expression.accumulator to Value.Json(accumulator), expression.variable to Value.Json(item))
                            comparable(json(expression(expression.expression, inner)))
                        },
                        context = context,
                        contextStep = { accumulator, item, map ->
                            val inner = read.associateWith { Value.Json(propertyOf(map, it)) } +
                                mapOf(expression.accumulator to Value.Json(accumulator), expression.variable to Value.Json(item))
                            val was = givenParameters
                            givenParameters = given.associateWith { Value.Json(propertyOf(map, "$$it")) }
                            try {
                                comparable(json(expression(expression.expression, inner)))
                            } finally {
                                givenParameters = was
                            }
                        },
                    )
                )
            }
            is Expression.PatternComprehension -> {
                val inner = Select(scope)
                patterns(inner, listOf(expression.pattern))
                expression.where?.let { inner.where += bool(expression(it, inner.scope)) }
                val projected = expression(expression.projection, inner.scope)
                fun gathered(value: String) = "(" + (listOf("SELECT ${aggregateList(value)}") + inner.from() + listOfNotNull(inner.whereClause())).joinToString(" ") + ")"
                // A comprehension of nodes or relationships is a list of them, so a write to an item of it reaches them.
                when (projected) {
                    is Value.Node -> Value.Entities(comparable(Value.Json(gathered(scalar(projected.nid)), ordered = true)), false, gathered(json(projected).sql))
                    is Value.Relationship -> Value.Entities(comparable(Value.Json(gathered(scalar(projected.eid)), ordered = true)), true, gathered(json(projected).sql))
                    else -> Value.Json(gathered(json(projected).sql), ordered = true)
                }
            }
            is Expression.PatternPredicate -> exists(listOf(expression.pattern), null, scope)
            is Expression.Exists -> exists(expression.patterns, expression.where, scope)
            is Expression.Subquery -> when (expression.kind) {
                Expression.Subquery.Kind.EXISTS -> Value.Bool("EXISTS (\n${indent(correlated(expression.query, scope, "EXISTS { … }"))}\n)")
                Expression.Subquery.Kind.COUNT ->
                    Value.Json(scalar("(SELECT count(*) FROM (\n${indent(correlated(expression.query, scope, "COUNT { … }"))}\n) ${alias("q")})"))
            }
            is Expression.Quantifier -> {
                val list = comparable(json(expression(expression.list, scope)))
                val name = alias("q")
                val inner = scope + (expression.variable to Value.Json("$name.item"))
                val condition = expression.where?.let { bool(expression(it, inner)) } ?: "TRUE"
                val from = "FROM ${elements(list, name)}"
                Value.Bool(
                    when (expression.kind) {
                        Expression.Quantifier.Kind.ANY -> "EXISTS (SELECT 1 $from WHERE $condition)"
                        Expression.Quantifier.Kind.ALL -> "NOT EXISTS (SELECT 1 $from WHERE NOT ($condition))"
                        Expression.Quantifier.Kind.NONE -> "NOT EXISTS (SELECT 1 $from WHERE $condition)"
                        Expression.Quantifier.Kind.SINGLE -> "(SELECT count(*) $from WHERE $condition) = 1"
                    }
                )
            }
        }

        private fun exists(patterns: List<Pattern>, where: Expression?, scope: Map<String, Value>): Value.Bool {
            val inner = Select(scope)
            patterns(inner, patterns)
            where?.let { inner.where += bool(expression(it, inner.scope)) }
            return Value.Bool("EXISTS (${(listOf("SELECT 1") + inner.from() + listOfNotNull(inner.whereClause())).joinToString(" ")})")
        }

        private fun property(subject: Value, key: String): Value = when (subject) {
            is Value.Entities -> throw DrivineException("A list has no properties.")
            is Value.Node -> Value.Json(if (key == Stamps.PROPERTY) scalar(subject.stamp) else propertyOf(subject.props, key))
            is Value.Relationship -> Value.Json(propertyOf(subject.props, key))
            is Value.Json -> Value.Json(propertyOf(comparable(subject), key))
            is Value.Bool -> throw DrivineException("A truth value has no properties.")
            is Value.Path -> throw DrivineException("A path has no properties.")
        }

        private fun mapProjection(projection: Expression.MapProjection, scope: Map<String, Value>): Value {
            val subject = scope[projection.variable] ?: throw DrivineException("${projection.variable} is not defined.")
            val entries = projection.elements.filterNot { it is Expression.MapProjection.Element.All }.map { element ->
                when (element) {
                    is Expression.MapProjection.Element.Selected -> element.key to json(property(subject, element.key)).sql
                    is Expression.MapProjection.Element.Entry -> element.key to json(expression(element.value, scope)).sql
                    is Expression.MapProjection.Element.Variable -> element.name to json(scope[element.name] ?: throw DrivineException("${element.name} is not defined.")).sql
                    Expression.MapProjection.Element.All -> error("filtered")
                }
            }
            // A projection of null is null, as in Cypher, and not a map of nulls.
            fun present(map: String) = "CASE WHEN NOT (${isNull(subject)}) THEN $map END"
            if (projection.elements.none { it is Expression.MapProjection.Element.All }) return Value.Json(present(jsonObject(entries)), ordered = true)
            // Every property, then the entries over them.
            return Value.Json(present(withEntries(comparable(json(subject)), entries)))
        }

        private fun binary(binary: Expression.Binary, scope: Map<String, Value>): Value {
            if (binary.operator == Operator.AND || binary.operator == Operator.OR || binary.operator == Operator.XOR) {
                val left = bool(expression(binary.left, scope))
                val right = bool(expression(binary.right, scope))
                return Value.Bool(
                    when (binary.operator) {
                        Operator.AND -> "($left AND $right)"
                        Operator.OR -> "($left OR $right)"
                        // Each side whole: a condition may be several, and `a = b <> c` is no SQL.
                        else -> "(($left) <> ($right))"
                    }
                )
            }
            val left = expression(binary.left, scope)
            val right = expression(binary.right, scope)
            val a = comparable(json(left))
            val b = comparable(json(right))
            return when (binary.operator) {
                Operator.EQUALS -> Value.Bool(equality(left, right, binary.right))
                Operator.NOT_EQUALS -> Value.Bool("NOT (${equality(left, right, binary.right)})")
                // Values of two kinds are not ordered, as in Cypher: the comparison is null.
                Operator.LESS_THAN, Operator.GREATER_THAN, Operator.LESS_THAN_OR_EQUAL, Operator.GREATER_THAN_OR_EQUAL -> {
                    val operator = mapOf(
                        Operator.LESS_THAN to "<", Operator.GREATER_THAN to ">", Operator.LESS_THAN_OR_EQUAL to "<=", Operator.GREATER_THAN_OR_EQUAL to ">=",
                    ).getValue(binary.operator)
                    Value.Bool(shared(a, b) { (x, y) -> "CASE WHEN ${typeOf(x)} = ${typeOf(y)} AND ${typeOf(x)} IN ('number', 'string', 'boolean') THEN $x $operator $y END" })
                }
                Operator.ADD -> Value.Json(shared(a, b) { (x, y) -> add(x, y) })
                Operator.SUBTRACT -> Value.Json(shared(a, b) { (x, y) -> arithmetic(x, y, "-") })
                Operator.MULTIPLY -> Value.Json(shared(a, b) { (x, y) -> arithmetic(x, y, "*") })
                // A whole number divided by one is a whole number, as in Cypher.
                Operator.DIVIDE -> Value.Json(
                    shared(a, b) { (x, y) -> "CASE WHEN ${integral(x)} AND ${integral(y)} THEN ${scalar("trunc(${number(x)} / ${number(y)})")} ELSE ${arithmetic(x, y, "/")} END" }
                )
                Operator.MODULO -> Value.Json(scalar(modulo(number(a), number(b))))
                Operator.POWER -> Value.Json(scalar(float("power(${number(a)}, ${number(b)})")))
                Operator.IN -> {
                    val i = alias("i")
                    Value.Bool("EXISTS (SELECT 1 FROM ${elements(b, i)} WHERE $i.item = $a)")
                }
                Operator.STARTS_WITH -> Value.Bool(shared(text(a), text(b)) { (x, y) -> startsWith(x, y) })
                Operator.MATCHES -> Value.Bool(matches(text(a), text(b)))
                Operator.ENDS_WITH -> Value.Bool(shared(text(a), text(b)) { (x, y) -> endsWith(x, y) })
                Operator.CONTAINS -> Value.Bool(containsText(text(a), text(b)))
                else -> error("logical operators are handled above")
            }
        }

        /**
         * Whether two values are equal: as JSON, so `'7'` is not `7` and `1` is `1.0`. A node is equal to a
         * node by its row. Where a property is compared with [written], a text or a truth value the statement
         * is given, a test an index answers stands beside it, where the store has one: such a value's text is
         * equal where its JSON is, and a number's is not (`1` and `1.0`).
         */
        private fun equality(left: Value, right: Value, written: Expression? = null): String {
            if (left is Value.Node && right is Value.Node) return "${left.nid} = ${right.nid}"
            if (left is Value.Relationship && right is Value.Relationship) return "${left.eid} = ${right.eid}"
            val a = comparable(json(left))
            val b = comparable(json(right))
            val given = written?.let(::given)
            val indexed = if (given is String || given is Boolean) indexedEquality(a, b) else null
            // Null is equal to nothing, itself included: the comparison is null, as in Cypher. A value the statement
            // is given that is not null needs no test of its own, and a property that is not there is SQL's null.
            val equal = if (given != null) "$a = $b" else shared(a, b) { (x, y) -> "CASE WHEN NOT ${isJsonNull(x)} AND NOT ${isJsonNull(y)} THEN $x = $y END" }
            return if (indexed != null) "($indexed AND $equal)" else equal
        }

        /** The value [expression] is where the statement gives it: written, or bound to a parameter or a parameter's key; null where it is not given, or is null. */
        private fun given(expression: Expression): Any? = when (expression) {
            is Expression.Literal -> expression.value
            is Expression.Parameter -> parameters.value(expression.name)
            is Expression.Property -> (expression.subject as? Expression.Parameter)?.let { (parameters.value(it.name) as? Map<*, *>)?.get(expression.key) }
            else -> null
        }

        private fun unary(unary: Expression.Unary, scope: Map<String, Value>): Value {
            val operand = expression(unary.operand, scope)
            return when (unary.operator) {
                Expression.Unary.Operator.NOT -> Value.Bool("(NOT (${bool(operand)}))")
                // Taken from 0, so a floating-point number stays one where the store keeps the kind apart.
                Expression.Unary.Operator.NEGATE -> Value.Json(shared(comparable(json(operand))) { (x) -> arithmetic(literalJson(0L), x, "-") })
                Expression.Unary.Operator.IS_NULL -> Value.Bool("(${isNull(operand)})")
                Expression.Unary.Operator.IS_NOT_NULL -> Value.Bool("(NOT ${isNull(operand)})")
            }
        }

        private fun isNull(value: Value): String = when (value) {
            is Value.Entities -> "${value.ids} IS NULL"
            is Value.Node -> "${value.nid} IS NULL"
            is Value.Relationship -> "${value.eid} IS NULL"
            is Value.Bool -> "(${value.sql}) IS NULL"
            is Value.Path -> "${value.nids} IS NULL"
            // A property stored as JSON's null is no property, as on a graph engine.
            is Value.Json -> isJsonNull(comparable(value))
        }

        private fun case(case: Expression.Case, scope: Map<String, Value>): Value {
            val subject = case.subject?.let { expression(it, scope) }
            val alternatives = case.alternatives.joinToString(" ") { (key, value) ->
                val condition = if (subject != null) equality(subject, expression(key, scope)) else bool(expression(key, scope))
                "WHEN $condition THEN ${comparable(json(expression(value, scope)))}"
            }
            val otherwise = case.otherwise?.let { " ELSE ${comparable(json(expression(it, scope)))}" }.orEmpty()
            return Value.Json("CASE $alternatives$otherwise END")
        }

        private fun function(function: Expression.Function, scope: Map<String, Value>): Value {
            val arguments = function.arguments.map { expression(it, scope) }
            fun one(): Value = arguments.singleOrNull() ?: throw DrivineException("${function.name}() takes one argument.")
            val distinct = if (function.distinct) "DISTINCT " else ""
            return when (val name = function.name.lowercase()) {
                "properties" -> when (val subject = one()) {
                    is Value.Node, is Value.Relationship -> json(subject)
                    else -> subject
                }
                "labels" -> (one() as? Value.Node)?.let { Value.Json(it.labels) } ?: throw DrivineException("labels() takes a node.")
                "type" -> (one() as? Value.Relationship)?.let { Value.Json(scalar(it.type)) } ?: throw DrivineException("type() takes a relationship.")
                "id", "elementid" -> when (val subject = one()) {
                    is Value.Node -> Value.Json(scalar(subject.nid))
                    is Value.Relationship -> Value.Json(scalar(subject.eid))
                    else -> throw DrivineException("${function.name}() takes a node or a relationship.")
                }
                "length" -> (one() as? Value.Path)?.let { Value.Json(idCount(it.eids)) } ?: throw DrivineException("length() takes a path.")
                "nodes" -> (one() as? Value.Path)?.let { nodeList(it.nids) } ?: throw DrivineException("nodes() takes a path.")
                "relationships" -> (one() as? Value.Path)?.let { relationshipList(it.eids) } ?: throw DrivineException("relationships() takes a path.")
                "coalesce" -> Value.Json("COALESCE(${arguments.joinToString(", ") { nullIfJsonNull(comparable(json(it))) }})")
                "count" -> Value.Json(scalar("count($distinct${countable(one())})"))
                "collect" -> when (val gathered = one()) {
                    // Nodes or relationships are gathered as their ids, so a list of them is still of them.
                    is Value.Node -> Value.Entities(gatheredIds("CASE WHEN ${gathered.nid} IS NOT NULL THEN ${scalar(gathered.nid)} END", function.distinct), false, collected(gathered, function.distinct))
                    is Value.Relationship -> Value.Entities(gatheredIds("CASE WHEN ${gathered.eid} IS NOT NULL THEN ${scalar(gathered.eid)} END", function.distinct), true, collected(gathered, function.distinct))
                    else -> Value.Json(collected(gathered, function.distinct), ordered = true)
                }
                "sum" -> Value.Json(scalar("COALESCE(sum($distinct${number(comparable(json(one())))}), 0)"))
                "avg" -> Value.Json(scalar(float("avg($distinct${number(comparable(json(one())))})")))
                "min", "max" -> Value.Json(extreme(comparable(json(one())), min = name == "min"))
                "range" -> {
                    if (arguments.size !in 2..3) throw DrivineException("range() takes a start, an end and a step.")
                    val (from, to) = arguments.map { number(comparable(json(it))) }
                    Value.Json(range(from, to, arguments.getOrNull(2)?.let { number(comparable(json(it))) } ?: "1"))
                }
                "split" -> {
                    if (arguments.size != 2) throw DrivineException("split() takes a text and a separator.")
                    val (text, separator) = arguments.map { text(comparable(json(it))) }
                    Value.Json(split(text, separator))
                }
                "reverse" -> Value.Json(shared(comparable(json(one()))) { (x) ->
                    val e = alias("e")
                    "CASE ${typeOf(x)} WHEN 'string' THEN ${scalar(reverseText(text(x)))} " +
                        "WHEN 'array' THEN (SELECT ${comparable(Value.Json(aggregateList("$e.item", "$e.item_no DESC"), ordered = true))} FROM ${elements(x, e)}) END"
                })
                "tail" -> Value.Json(shared(comparable(json(one()))) { (x) ->
                    val e = alias("e")
                    "CASE WHEN ${typeOf(x)} = 'array' THEN (SELECT ${aggregateList("$e.item", "$e.item_no")} FROM ${elements(x, e)} WHERE $e.item_no > 1) END"
                })
                // A text is read as Cypher reads it, its case and the spaces around it aside; a number is true but for 0.
                "toboolean" -> Value.Json(shared(comparable(json(one()))) { (x) ->
                    "CASE WHEN ${typeOf(x)} = 'boolean' THEN $x " +
                        "WHEN ${typeOf(x)} = 'number' THEN CASE WHEN ${number(x)} = 0 THEN ${literalJson(false)} ELSE ${literalJson(true)} END " +
                        "WHEN LOWER(TRIM(${text(x)})) = 'true' THEN ${literalJson(true)} WHEN LOWER(TRIM(${text(x)})) = 'false' THEN ${literalJson(false)} END"
                })
                "isempty" -> Value.Json(shared(comparable(json(one()))) { (x) ->
                    val size = function("size", x)!!
                    scalar("CASE WHEN ${typeOf(x)} = 'object' THEN $x = ${literalJson(emptyMap<String, Any>())} WHEN ${typeOf(x)} IN ('array', 'string') THEN ${number(size)} = 0 END")
                })
                // A float, as Cypher gives these; round as Java rounds, half up.
                "round" -> Value.Json(
                    if (arguments.size == 2) scalar(float("ROUND(${number(comparable(json(arguments[0])))}, ${whole(arguments[1])})"))
                    else scalar(float("FLOOR(${number(comparable(json(one())))} + 0.5)"))
                )
                "floor", "ceil", "sqrt" -> Value.Json(scalar(float("${name.uppercase()}(${number(comparable(json(one())))})")))
                "sign" -> Value.Json(scalar("SIGN(${number(comparable(json(one())))})"))
                "ltrim", "rtrim" -> Value.Json(scalar("${name.uppercase()}(${text(comparable(json(one())))})"))
                "randomuuid" -> if (arguments.isEmpty()) Value.Json(scalar(uuid())) else throw DrivineException("randomUUID() takes no argument.")
                // A position is counted from 0, as Cypher counts it, and SQL from 1.
                "substring" -> {
                    if (arguments.size !in 2..3) throw DrivineException("substring() takes a text, a start and a length.")
                    val (text, start) = textAndCount(arguments)
                    Value.Json(scalar("SUBSTR($text, $start + 1${arguments.getOrNull(2)?.let { ", ${whole(it)}" }.orEmpty()})"))
                }
                "left", "right" -> {
                    if (arguments.size != 2) throw DrivineException("${function.name}() takes a text and a length.")
                    val (text, count) = textAndCount(arguments)
                    Value.Json(scalar(if (name == "left") "SUBSTR($text, 1, $count)" else rightText(text, count)))
                }
                "replace" -> {
                    if (arguments.size != 3) throw DrivineException("replace() takes a text, what to replace, and what with.")
                    val (text, search, replacement) = arguments.map { text(comparable(json(it))) }
                    Value.Json(scalar("REPLACE($text, $search, $replacement)"))
                }
                "date" -> if (arguments.isEmpty()) Value.Json(dateNow()) else one()
                "localdatetime" -> if (arguments.isEmpty()) Value.Json(localNow()) else one()
                "timestamp" -> if (arguments.isEmpty()) Value.Json(epochMillisNow()) else throw DrivineException("timestamp() takes no argument.")
                // A moment is its ISO text, as a store keeps it; one given is taken as it is.
                "datetime" -> if (arguments.isEmpty()) Value.Json(isoNow()) else one()
                else -> {
                    // A function the store has no SQL for is named before its arguments are counted.
                    if (function(name, "NULL") == null) unsupported("The function ${function.name}()")
                    val argument = comparable(json(one()))
                    Value.Json(shared(argument) { (x) -> function(name, x)!! })
                }
            }
        }

        /** A function's text, its first argument, and a whole number, its second. */
        private fun textAndCount(arguments: List<Value>): Pair<String, String> = text(comparable(json(arguments[0]))) to whole(arguments[1])

        /** A value as a SQL whole number. */
        private fun whole(value: Value): String = "CAST(${number(comparable(json(value)))} AS INTEGER)"

        /** The values [value] of a group, gathered as `collect` gathers them, as JSON. */
        private fun collected(value: Value, distinct: Boolean) = collect(collectable(value, distinct), distinct, rowOrder)

        /** The ids [id] of a group, gathered as `collect` gathers them, as JSON that compares. */
        private fun gatheredIds(id: String, distinct: Boolean) = comparable(Value.Json(collect(id, distinct, rowOrder), ordered = true))

        /** What `count` counts of a value: a node by its row, anything else by its value, nulls not counted. */
        private fun countable(value: Value): String = when (value) {
            is Value.Node -> value.nid
            is Value.Relationship -> value.eid
            is Value.Path -> value.nids
            is Value.Entities -> value.ids
            else -> nullIfJsonNull(comparable(json(value)))
        }

        /**
         * What `collect` gathers of a value: nulls are left out, as Cypher's `collect` leaves them out, JSON's null
         * among them, as a list's item or a map's entry may be. Gathered once each, with [distinct], a value is what
         * compares: Postgres compares no `json` a map or a list is built as.
         */
        private fun collectable(value: Value, distinct: Boolean): String = when {
            value is Value.Node -> "CASE WHEN ${value.nid} IS NOT NULL THEN ${json(value).sql} END"
            value is Value.Json && !value.ordered -> nullIfJsonNull(value.sql)
            else -> if (distinct) comparable(json(value)) else json(value).sql
        }

        // ----- Conversions -----

        /** A value as JSON, for a projection or a comparison. A node is its properties, its stamp among them, as drivine gives a node. */
        fun json(value: Value): Value.Json = when (value) {
            is Value.Json -> value
            is Value.Bool -> Value.Json(scalar(value.sql))
            is Value.Node -> Value.Json(nodeJson(value))
            is Value.Relationship -> Value.Json("CASE WHEN ${value.eid} IS NOT NULL THEN ${value.props} END")
            is Value.Entities -> Value.Json(entitiesJson(value), ordered = true)
            // Each graph engine's driver gives a path as a value of its own, so a path is given by its parts.
            is Value.Path -> throw DrivineException("A path is not a value drivine gives the same on each store: use nodes(p), relationships(p) and length(p).")
        }

        /** A value as a condition: a boolean as itself, JSON's `true` and `false` as theirs, anything else as null. */
        fun bool(value: Value): String = when (value) {
            is Value.Bool -> value.sql
            is Value.Json -> boolOf(comparable(value))
            else -> throw DrivineException("A node, a relationship or a path is not a condition.")
        }

        /**
         * What an order sorts a value by: JSON's order for a value, a node by its properties. JSON's null, as a map
         * or a list the statement builds holds it, is SQL's null, which comes last, as a graph engine orders null.
         */
        private fun sortable(value: Value): String = when (value) {
            is Value.Bool -> value.sql
            else -> nullIfJsonNull(comparable(json(value)))
        }
    }

    /** A node as JSON: its properties, its stamp among them, as drivine gives a node; null where there is no node. */
    protected abstract fun nodeJson(node: Value.Node): String

    /** The rows a part of a statement reads: its sources and joins, its conditions, and the variables it binds. */
    private class Select(private val outer: Map<String, Value>) {
        val sources = mutableListOf<String>()
        val where = mutableListOf<String>()
        val bound = linkedMapOf<String, Value>()

        /** The order its rows are read in, where it has one: each term, and whether it descends. */
        val order = mutableListOf<Pair<String, Boolean>>()

        /** What an expression of this part sees: the variables bound around it, then its own. */
        val scope: Map<String, Value> get() = outer + bound

        fun bind(variable: String, value: Value) {
            bound[variable] = value
        }

        fun source(text: String) {
            sources += if (sources.isEmpty()) "FROM $text" else "CROSS JOIN $text"
        }

        fun join(text: String) {
            if (sources.isEmpty()) sources += "FROM (SELECT 1) one"
            sources += text
        }

        fun from(): List<String> = sources

        fun whereClause(): String? = where.takeIf { it.isNotEmpty() }?.joinToString("\n  AND ", "WHERE ")

        /** How much this part reads: it reads more when a clause adds to it. */
        fun size(): Int = sources.size + where.size + bound.size
    }

    /** What a variable a row holds is. */
    private enum class Kind { NODE, RELATIONSHIP, VALUE, NODES, RELATIONSHIPS, PATH }

    protected companion object {
        /** Stands for the name of the query a `WITH` is read from, before it is named. */
        const val PENDING = "__with__"

        fun literal(text: String) = "'${text.replace("'", "''")}'"

        fun indent(text: String) = text.prependIndent("    ")

        fun unsupported(construct: String): Nothing = throw DrivineException("$construct is Cypher, and does not run on Oracle or Postgres yet.")

        /** Whether [clause] writes, which makes its statement a procedure. */
        fun writes(clause: Clause): Boolean =
            clause is Clause.Create || clause is Clause.Merge || clause is Clause.Set || clause is Clause.Remove || clause is Clause.Delete ||
                clause is Clause.Foreach || (clause is Clause.Call && (clause.query.clauses + clause.query.unions.flatMap { it.clauses }).any(::writes))

        // The variables of a procedure.

        /** The parameters, a JSON map. */
        const val PARAMS = "cy_params"

        /** The rows, a JSON list of maps, each variable a row holds at its key. */
        const val ROWS = "cy_rows"

        /** The rows a write leaves, as it makes them. */
        const val OUT = "cy_out"

        /** The row a write is making, a JSON map. */
        const val ROW = "cy_row"

        /** A JSON value a write has worked out. */
        const val VALUE = "cy_value"

        /** The id of the node or the relationship a write has made. */
        const val ID = "cy_id"

        /** A count. */
        const val COUNT = "cy_count"

        /** Whether a `MERGE` found its pattern. */
        const val FOUND = "cy_found"

        /**
         * The rows a `FOREACH`, or a `CALL { … }` that writes, is written over: what it puts back once its clauses
         * are written, where it returns nothing. Followed by its depth, from 1 to [NESTING].
         */
        const val SAVED = "cy_saved"

        /**
         * The rows a `CALL { … }` that writes and returns gives, or the rows and items a `FOREACH` writes for, each
         * one alone, followed by its depth, from 1 to [NESTING].
         */
        const val GATHERED = "cy_gathered"

        /** The names of a shortest path's ends where its pattern gives none, which no statement can write unquoted: see [Writer.shortest]. */
        const val SHORTEST_FROM = " shortest from"
        const val SHORTEST_TO = " shortest to"

        /** How deep `FOREACH`es and writing `CALL`s nest. */
        const val NESTING = 3

        /** The nodes a `DELETE` deletes once its rows are done, a JSON list of ids. */
        const val DOOMED = "cy_doomed"

        /** The record of a write's loop over the rows: its `item` is a row. */
        const val RECORD = "cy_record"

        /** The record of a `MERGE`'s loop over what it found: its `item` is a row. */
        const val MATCHED = "cy_matched"

        /** How long a value [shared] binds may be written where it is read, in characters. */
        const val SHARED_ABOVE = 200

        val AGGREGATES = setOf("count", "collect", "sum", "avg", "min", "max")

        fun isAggregate(expression: Expression): Boolean =
            expression == Expression.CountStar || (expression is Expression.Function && expression.name.lowercase() in AGGREGATES)

        /** Whether [expression] holds an expression [test] holds of, outside a nested query of its own. */
        fun contains(expression: Expression, test: (Expression) -> Boolean): Boolean {
            if (test(expression)) return true
            return children(expression).any { contains(it, test) }
        }

        /** The variables [expression] names outside its aggregates: what an aggregating projection is grouped by. */
        fun variablesOutsideAggregates(expression: Expression): Set<String> = when {
            isAggregate(expression) -> emptySet()
            expression is Expression.Variable -> setOf(expression.name)
            expression is Expression.MapProjection -> setOf(expression.variable) + children(expression).flatMap(::variablesOutsideAggregates)
            else -> children(expression).flatMap(::variablesOutsideAggregates).toSet()
        }

        /**
         * Every variable [expression] names, its nested queries' and comprehensions' among them: a name a
         * comprehension binds of its own may be among them too, which costs no more than a variable read
         * that is not needed.
         */
        fun variablesIn(expression: Expression): Set<String> = when (expression) {
            is Expression.Variable -> setOf(expression.name)
            is Expression.MapProjection -> setOf(expression.variable) +
                expression.elements.filterIsInstance<Expression.MapProjection.Element.Variable>().map { it.name } + children(expression).flatMap(::variablesIn)
            is Expression.ListComprehension -> listOfNotNull(expression.list, expression.where, expression.projection).flatMap(::variablesIn).toSet()
            is Expression.Quantifier -> listOfNotNull(expression.list, expression.where).flatMap(::variablesIn).toSet()
            is Expression.Reduce -> listOf(expression.initial, expression.list, expression.expression).flatMap(::variablesIn).toSet()
            is Expression.PatternComprehension -> variablesIn(expression.pattern) + listOfNotNull(expression.where, expression.projection).flatMap(::variablesIn)
            is Expression.PatternPredicate -> variablesIn(expression.pattern)
            is Expression.Exists -> expression.patterns.flatMap(::variablesIn).toSet() + (expression.where?.let(::variablesIn) ?: emptySet())
            else -> children(expression).flatMap(::variablesIn).toSet()
        }

        /** Every parameter [expression] names, in any expression it holds. */
        fun parametersIn(expression: Expression): Set<String> = when (expression) {
            is Expression.Parameter -> setOf(expression.name)
            is Expression.ListComprehension -> listOfNotNull(expression.list, expression.where, expression.projection).flatMap(::parametersIn).toSet()
            is Expression.Quantifier -> listOfNotNull(expression.list, expression.where).flatMap(::parametersIn).toSet()
            is Expression.Reduce -> listOf(expression.initial, expression.list, expression.expression).flatMap(::parametersIn).toSet()
            is Expression.PatternComprehension -> listOfNotNull(expression.where, expression.projection).flatMap(::parametersIn).toSet()
            else -> children(expression).flatMap(::parametersIn).toSet()
        }

        /** Every variable [pattern] names, of its parts or in their properties. */
        fun variablesIn(pattern: Pattern): Set<String> =
            pattern.nodes.flatMap { listOfNotNull(it.variable) + (it.properties?.let(::variablesIn) ?: emptySet()) }.toSet() +
                pattern.relationships.flatMap { listOfNotNull(it.variable) + (it.properties?.let(::variablesIn) ?: emptySet()) }

        fun children(expression: Expression): List<Expression> = when (expression) {
            is Expression.Property -> listOf(expression.subject)
            is Expression.HasLabels -> listOf(expression.subject)
            is Expression.ListLiteral -> expression.items
            is Expression.MapLiteral -> expression.entries.map { it.second }
            is Expression.MapProjection -> expression.elements.mapNotNull { (it as? Expression.MapProjection.Element.Entry)?.value }
            is Expression.Binary -> listOf(expression.left, expression.right)
            is Expression.Unary -> listOf(expression.operand)
            is Expression.Index -> listOf(expression.subject, expression.index)
            is Expression.Slice -> listOfNotNull(expression.subject, expression.from, expression.to)
            is Expression.Function -> expression.arguments
            is Expression.Case -> listOfNotNull(expression.subject, expression.otherwise) + expression.alternatives.flatMap { listOf(it.first, it.second) }
            is Expression.ListComprehension -> listOf(expression.list)
            is Expression.Reduce -> listOf(expression.initial, expression.list)
            is Expression.Quantifier -> listOf(expression.list)
            else -> emptyList()
        }
    }

    // ----- Columns of a value -----

    /** The select-list entries that carry this value out of a query, under [name]. */
    private fun Value.columns(name: String): List<String> = when (this) {
        is Value.Json -> listOf("$sql AS $name")
        is Value.Entities -> listOf("$ids AS $name")
        is Value.Bool -> listOf("$sql AS $name")
        is Value.Node -> listOf("$nid AS ${name}_nid", "$labels AS ${name}_labels", "$props AS ${name}_props", "$stamp AS ${name}_stamp")
        is Value.Relationship -> listOf("$eid AS ${name}_eid", "$src AS ${name}_src", "$dst AS ${name}_dst", "$type AS ${name}_type", "$props AS ${name}_props")
        is Value.Path -> listOf("$nids AS ${name}_nids", "$eids AS ${name}_eids")
    }

    /**
     * This value, read from the columns [columns] gave it under [name] in the query named [alias]. Without
     * a [name], a value whose columns were named for a query not yet named ([PENDING]) is read from [alias].
     */
    private fun Value.columnsOf(alias: String, name: String? = null): Value {
        if (name == null) return withAlias(alias)
        val prefix = "$alias."
        val base = name
        return when (this) {
            is Value.Json -> Value.Json("$prefix$base", ordered)
            is Value.Entities -> Value.Entities("$prefix$base", relationships)
            is Value.Bool -> Value.Bool("$prefix$base")
            is Value.Node -> Value.Node("$prefix${base}_nid", "$prefix${base}_labels", "$prefix${base}_props", "$prefix${base}_stamp")
            is Value.Relationship -> Value.Relationship("$prefix${base}_eid", "$prefix${base}_src", "$prefix${base}_dst", "$prefix${base}_type", "$prefix${base}_props")
            is Value.Path -> Value.Path("$prefix${base}_nids", "$prefix${base}_eids")
        }
    }

    /** This value with [PENDING] read as [alias]. */
    private fun Value.withAlias(alias: String): Value = when (this) {
        is Value.Json -> copy(sql = sql.replace(PENDING, alias))
        is Value.Entities -> copy(ids = ids.replace(PENDING, alias), gathered = gathered?.replace(PENDING, alias))
        is Value.Bool -> copy(sql = sql.replace(PENDING, alias))
        is Value.Node -> Value.Node(nid.replace(PENDING, alias), labels.replace(PENDING, alias), props.replace(PENDING, alias), stamp.replace(PENDING, alias))
        is Value.Relationship -> Value.Relationship(
            eid.replace(PENDING, alias), src.replace(PENDING, alias), dst.replace(PENDING, alias), type.replace(PENDING, alias), props.replace(PENDING, alias),
        )
        is Value.Path -> Value.Path(nids.replace(PENDING, alias), eids.replace(PENDING, alias))
    }

    /** What a projection that aggregates is grouped by, to keep this value: a node by its columns. */
    private fun Value.groupKeys(): List<String> = when (this) {
        is Value.Json -> listOf(sql)
        is Value.Entities -> listOf(ids)
        is Value.Bool -> listOf(sql)
        is Value.Node -> listOf(nid, labels, props, stamp)
        is Value.Relationship -> listOf(eid, src, dst, type, props)
        is Value.Path -> listOf(nids, eids)
    }
}
