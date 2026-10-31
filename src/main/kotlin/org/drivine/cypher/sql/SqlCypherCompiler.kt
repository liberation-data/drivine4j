package org.drivine.cypher.sql

import com.fasterxml.jackson.databind.JsonNode
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
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps
import org.slf4j.LoggerFactory
import java.math.RoundingMode

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

    /** Declares [function], a function's whole text, for the statement to call: a query's own, before it; once, however often it is declared. */
    protected fun declare(function: String) {
        if (function !in functions) functions += function
    }

    /** [compiled] with the functions it calls declared before it. */
    private fun withFunctions(compiled: CompiledCypher): CompiledCypher =
        if (functions.isEmpty()) compiled else compiled.copy(sql = "WITH\n${functions.joinToString("\n")}\n${compiled.sql}")

    /** The query [query], a statement of its own that opens with `SELECT`, with what the store's planner is told of it: nothing here. */
    protected open fun planned(query: String): String = query

    /**
     * Whether a value's identity is carried out of a query as a column of its own (see [Value.Json.identity]): not where the
     * queries of a `UNION` in `CALL { … }` would give different columns, which compare by their properties instead.
     */
    private var identities = true

    /** Whether the store aggregates a `CASE` whose result holds a subquery: see `casesFirst`. */
    protected open val aggregatesCaseSubqueries = true

    /**
     * Whether `min` and `max` of a list the statement makes order it by [listSortKey]: not where the store's JSON orders
     * lists item by item already, as Oracle's does, where a grouped `KEEP` ordered by that key closes the session (ORA-03113).
     */
    protected open val extremesByListKey = true

    /** How many values [shared] has bound, to name each subquery it binds one in. */
    private var shares = 0

    /**
     * Whether [shared] binds a long value: not in an expression that aggregates, whose aggregate a subquery would take
     * from its group, nor in a term a projection beside a variable-length walk sorts by that is none of its items (see `project`).
     */
    private var sharing = true

    /** How many times [shared] has bound its values, which an aggregate's argument it would bind in is worked out first for: see `sharedFirst`. */
    private var sharedBindings = 0

    /** The statement [cypher], with its parameters' values [parameters], as SQL. */
    fun compile(cypher: String, parameters: Map<String, Any?> = emptyMap()): CompiledCypher =
        compile(CypherReader.read(cypher), parameters)

    /** The statement [query], read already, with its parameters' values [parameters], as SQL: a query, or a procedure where it writes. */
    fun compile(query: Query, parameters: Map<String, Any?>): CompiledCypher {
        // A variable used as two kinds, or made where it is bound already, is refused for that, before what is not compiled yet.
        VariableKinds.check(query)
        // An aggregate in a WHERE too.
        Aggregates(::isAggregate, ::children).check(query)
        if (query.unions.isNotEmpty()) return withFunctions(union(query, parameters))
        if (query.clauses.none(::writes)) {
            val writer = Writer(Parameters(parameters, ::parameterJson, ::parameterCount, text = ::parameterText, asPath = parameterPath))
            return withFunctions(CompiledCypher(planned(writer.query(query)), writer.parameters.values))
        }
        procedural = true
        // A procedure takes its parameters as one object, and reads each from it.
        val writer = Writer(
            Parameters(
                parameters, { name, _ -> nullIfJsonNull(propertyOf(PARAMS, name)) }, { number(propertyOf(PARAMS, it)) }, inObject = true, text = ::parameterText,
                asPath = parameterPath?.let { read -> { name, _, keys, value -> read(PARAMS, null, listOf(name) + keys, value) } },
            )
        )
        // The parameters' text is written before the procedure's body is: on Oracle, writing it is what says whether the
        // body reads it as extended JSON.
        val values = writer.parameters.values
        val body = writer.procedure(query)
        return CompiledCypher(procedure(body), values)
    }

    /**
     * [body] over [values], each read by name where one is long: bound once in a subquery, so a body that reads
     * a value more than once (to test its kind, then to read it) does not write it out again, and operations
     * nested in one another do not grow the statement exponentially. Short values are written where they are
     * read. Oracle groups by no subquery, so a projection that would group by a value so bound works it out first.
     */
    protected fun shared(vararg values: String, body: (List<String>) -> String): String {
        if (!binds(*values)) return body(values.toList())
        return sharedBound(*values, body = body)
    }

    /** [body] over [values] [bound] as [shared] binds them, and counted so: see [sharedBindings]. */
    protected fun sharedBound(vararg values: String, body: (List<String>) -> String): String {
        sharedBindings++
        return bound(*values, body = body)
    }

    /** Whether [shared] binds [values]: where one is long, and values are bound here at all. */
    protected fun binds(vararg values: String) = sharing && values.any { it.length > SHARED_ABOVE }

    /** [body] over the sides [a] and [b] of a `+`, as [shared] binds them: see Oracle's. */
    protected open fun sharedSum(a: String, b: String, body: (List<String>) -> String): String = shared(a, b, body = body)

    /** [body] over the sides [a] and [b] of a comparison, an `IN` among them, as [shared] binds them: see Oracle's. */
    protected open fun sharedCompared(a: String, b: String, body: (List<String>) -> String): String = shared(a, b, body = body)

    /** [shared], of a list of values, as [Temporals] and a store's [TemporalSql] take it. */
    protected val sharedList: (List<String>, (List<String>) -> String) -> String = { values, body -> shared(*values.toTypedArray(), body = body) }

    /** [body] over [values], each bound in a subquery and read by name whatever its length: see [shared]. */
    protected fun bound(vararg values: String, body: (List<String>) -> String): String {
        val alias = "sh${++shares}"
        val columns = values.withIndex().joinToString(", ") { (i, value) -> "$value AS v${i + 1}" }
        return "(SELECT ${body(values.indices.map { "$alias.v${it + 1}" })} FROM (${boundSelect(columns)}) $alias)"
    }

    /**
     * The subquery [bound] binds its values in, of the columns [columns]: kept apart from the query that reads it,
     * where a store would merge the two, so the planner does not write each value out again where it is read.
     */
    protected open fun boundSelect(columns: String) = "SELECT $columns"

    /**
     * The join of the query [query], which reads the row it is joined to, as the rows named [name]; with [optional], one
     * that keeps the row where it gives none, its columns null. [columns] names its columns, each and whether it holds
     * JSON, which [lateralColumn] reads.
     */
    protected open fun lateral(query: String, name: String, optional: Boolean, columns: List<Pair<String, Boolean>>): String =
        if (optional) "LEFT JOIN LATERAL (\n${indent(query)}\n) $name ON TRUE" else "CROSS JOIN LATERAL (\n${indent(query)}\n) $name"

    /** The column [column] of the rows named [name] that a [lateral] join gives, which holds JSON where [json]. */
    protected open fun lateralColumn(name: String, column: String, json: Boolean) = "$name.$column"

    /**
     * The query of a `CALL { … }`, [query], as the row that reads what it returns joins it: kept apart from that
     * row's query, where a store would merge the two and read a value bound in the row wrongly.
     */
    protected open fun called(query: String) = query

    /**
     * The query [query] that makes a value anew for each row, `randomUUID()`, kept apart from the query that reads it, where a
     * store would merge the two and make the value again for each read: a `WITH`'s, and a projection's sorted by it.
     */
    protected open fun once(query: String) = called(query)

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
        val writer = Writer(Parameters(parameters, ::parameterJson, ::parameterCount, text = ::parameterText, asPath = parameterPath))
        // Every query's names, before any is compiled: a name made in one is no variable of a later one's.
        writer.give(query)
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
        return CompiledCypher(planned(sql), writer.parameters.values)
    }

    // ----- A store's SQL -----

    /** A value [value] the statement writes, as JSON. */
    protected abstract fun literalJson(value: Any?): String

    /** The parameter bound as [marker], the JSON text [text], as JSON. */
    protected abstract fun parameterJson(marker: String, text: String?): String

    /**
     * Where a store reads a value in a parameter that is a map, `${'$'}p.text`, straight from the text bound as [marker], the
     * JSON text [text] (in a procedure, from [PARAMS], with the parameter's name first among [keys]): the value at [keys],
     * [value] as the caller gave it, as JSON. Null where the store reads the parameter as JSON, then each key of it.
     */
    protected open val parameterPath: ((marker: String, text: String?, keys: List<String>, value: Any?) -> String)? = null

    /** The JSON text a parameter's [value] is bound as. */
    protected open fun parameterText(value: Any?): String = Neo4jObjectMapper.instance.writeValueAsString(value)

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

    /**
     * [body] over the texts of the JSON values [jsons], each read as [text] reads it where it is a string. A store whose
     * text read from JSON holds only so much reads a longer one otherwise: see Oracle's.
     */
    protected open fun texts(vararg jsons: String, body: (List<String>) -> String): String = body(jsons.map(::text))

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

    /**
     * The property [key] of the node or relationship whose properties are [props], where it is an item of a `RETURN` or a
     * `WITH`, returned or passed on: as [propertyOf] reads it, but on a store that must read such an item apart.
     */
    protected open fun itemProperty(props: String, key: String): String = propertyOf(props, key)

    /**
     * The value at [keys], each of a map in the one before, in the row a write is making ([ROW]), where a statement of the
     * write reads it to work out what it writes: as [propertyOf] reads each key, but on a store that must read it apart.
     */
    protected open fun rowValue(keys: List<String>): String = keys.fold(ROW, ::propertyOf)

    /**
     * The value of [key] in the JSON [json], read from an item of a list the query unwinds (see [Value.Json.fromList]), where
     * it is an item of a `RETURN` or a `WITH`, returned or passed on: as [propertyOf] reads it, but on a store that must read
     * such an item apart.
     */
    protected open fun itemValue(json: String, key: String): String = propertyOf(json, key)

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

    /** The items of the JSON list [json] as rows named [alias], whose item is [item] and whose place in the list, counted from 1, [itemNo]. */
    protected abstract fun elements(json: String, alias: String): String

    /** [elements] of the JSON list [json] that the statement makes hold temporal values at any depth, as Oracle reads them apart: see its own. */
    protected open fun temporalElements(json: String, alias: String) = elements(json, alias)

    /** [temporalElements] of the JSON list [json] where its items are [temporal] values or lists of them, else [elements]. */
    protected fun elements(json: String, alias: String, temporal: Boolean) = if (temporal) temporalElements(json, alias) else elements(json, alias)

    /** The item, as JSON, of the row named [alias] that [elements] gives. */
    protected open fun item(alias: String) = "$alias.item"

    /** The place in its list, counted from 1, of the row named [alias] that [elements] gives. */
    protected open fun itemNo(alias: String) = "$alias.item_no"

    /** The SQL texts [text] of a group's rows put together in the [order] of the rows, with nothing between them: null where there are none. */
    protected abstract fun joinedTexts(text: String, order: String): String

    /** The SQL text [text] ordered by its bytes, whatever the database's collation: as it is where the store orders texts so. */
    protected open fun bytewise(text: String) = text

    /**
     * What orders the JSON list [list] as Cypher orders it, item by item, where every item is a zoned value of [kind] or null, or with
     * [depth] 2, a list of them or null: each item's moment in UTC as a text of one width, null as `~`, which comes after them, and a list
     * held in it as its own items so written; each list then `!`, its end, which comes before any item, so a list that starts another
     * comes first, as Cypher puts it, and the empty list is no null. Null where the list is.
     */
    private fun momentsKey(list: String, kind: Temporal, depth: Int = 1): String = shared(list) { (x) ->
        fun key(list: String, depth: Int): String {
            val alias = "mk$depth"
            val item = item(alias)
            val itemKey = if (depth == 1) temporalSql.sortable(temporals.moment(text(item), kind)) else key(item, depth - 1)
            val joined = joinedTexts("CASE WHEN ${isJsonNull(item)} THEN '~' ELSE $itemKey END", itemNo(alias))
            return "(SELECT COALESCE($joined, '') || '!' FROM ${temporalElements(list, alias)})"
        }
        "CASE WHEN NOT ${isJsonNull(x)} THEN ${bytewise(key(x, depth))} END"
    }

    /** Whether the JSON values [x] and [y], neither of them null, are equal, as Cypher compares them: see [Writer.equality]. */
    protected open fun jsonEquals(x: String, y: String) = "$x = $y"

    /**
     * Whether the identities [x] and [y], neither of them null, are equal: see [Writer.identityOf]. A list of a few thousand
     * relationships' tokens is larger than Oracle's `=` compares, so Oracle compares them otherwise.
     */
    protected open fun identityEquals(x: String, y: String) = jsonEquals(x, y)

    /**
     * Whether the JSON value [json], not null, holds a null in a list or a map, at any depth: exactly, as an `IN` takes a
     * value that holds one to be equal to no item. Null where the store's [jsonEquals] is already null for a value that holds one,
     * as Cypher's comparison is, so no test is needed.
     */
    protected abstract fun holdsNull(json: String): String?

    /**
     * Whether the JSON values [x] and [y], neither of them null, are equal, as Cypher compares values that may hold a null: item
     * by item and key by key, at any depth, false where a pair differs (a list's length, a map's keys, a value's kind among
     * them), else null where a pair holds a null, else true. Called where [holdsNull] says one of them holds one; [equals]
     * compares two that hold none, as [jsonEquals] or [identityEquals] does.
     */
    protected abstract fun nullsEqual(x: String, y: String, equals: (String, String) -> String): String

    /** The item at [index], counted from 0, of the JSON list [json]: null where it has none. */
    protected open fun itemAt(json: String, index: Int) = element(json, literalJson(index.toLong()))

    /** How many items the JSON list [json] holds, as a SQL number. */
    protected open fun listSize(json: String) = number(function("size", json)!!)

    /**
     * Whether the JSON values [x] and [y] are equal, as Cypher compares values that may hold a null: null where either is null,
     * and as [nullsEqual] compares them where one holds one; else as [equals] compares them.
     */
    private fun nullableEquals(x: String, y: String, equals: (String, String) -> String): String = shared(x, y) { (a, b) ->
        val held = listOfNotNull(holdsNull(a), holdsNull(b))
        val equal = if (held.isEmpty()) equals(a, b) else "CASE WHEN ${held.joinToString(" OR ")} THEN ${nullsEqual(a, b, equals)} ELSE ${equals(a, b)} END"
        "CASE WHEN NOT ${isJsonNull(a)} AND NOT ${isJsonNull(b)} THEN $equal END"
    }

    /** Whether the JSON value [x] is equal to [y], the JSON of [given], a value the statement gives that is not null. */
    protected open fun givenEquals(x: String, y: String, given: Any) = "$x = $y"

    /**
     * Whether [item], an item of a list whose items the statement writes or is given, [items], each a text, a number, a truth
     * value or null, is equal to the JSON value [value], as an `IN` tests it: null or false where it is not.
     */
    protected open fun givenItemEquals(item: String, value: String, items: List<Any?>) = jsonEquals(item, value)

    /** Whether the JSON values [x] and [y], of one kind, a number, a text or a truth value, stand as [operator] says (`<`, `>=`…). */
    protected open fun ordered(x: String, operator: String, y: String) = "$x $operator $y"

    /**
     * Where the JSON list [x] comes beside the JSON list [y], neither of them null, as Cypher orders two lists in a comparison
     * (`<`, `>=` …), as a SQL number: -1 before, 0 equal, 1 after. Item by item from the first, at any depth: the first pair
     * that is not equal decides, and a list that the other starts with comes before it. Null where that pair is not ordered:
     * a null in it, values of two kinds, or two maps that differ, which Cypher orders and no store does here yet. A number,
     * a text (by its code points) and a truth value are ordered among their own kind, as [ordered] orders them.
     */
    protected abstract fun listOrder(x: String, y: String): String

    /** A SQL truth value [sql] as JSON's `true` or `false`, or null. */
    protected open fun boolJson(sql: String) = scalar(sql)

    /** What an order sorts the JSON value [json] by: JSON's null as SQL's, which comes last, as a graph engine orders null. */
    protected open fun sortKey(json: String) = nullIfJsonNull(json)

    /**
     * What an order sorts the JSON value [json] by where the statement leaves its kind open, a term after another: values of
     * several kinds as Cypher orders their kinds ([kindOrder]), null last, and those of one kind among themselves. As [sortKey]
     * where a store does not override it: Postgres's takes `jsonb`'s order of kinds, a number before a truth value, which is
     * not yet Cypher's (#307). Oracle's and SQLite's JSON orders put a number before a text, so their terms are where the kind
     * comes, then the value. A value the statement makes of one kind sorts by [sortKey] alone.
     */
    protected open fun openSortKey(json: String): List<String> = listOf(sortKey(json))

    /**
     * A whole number that fails the statement when it is worked out, with [message] and the SQL value [value] it names:
     * what a compiled statement does where Neo4j fails on a value it reads. No store works it out before a row that
     * needs it, even where [value] is a constant, as a store may work out a failing expression of constants alone. But
     * in a WHERE, one that reads nothing of a row is tested before any row on Postgres, which tests such a condition
     * once, first: as it does any expression that fails, such as `1 / 0`.
     */
    protected abstract fun failure(message: String, value: String): String

    /** What an order sorts the JSON value [json] by where the statement makes it a list ([Value.Json.list]): as [sortKey] where the store orders lists as Cypher does. */
    protected open fun listSortKey(json: String) = sortKey(json)

    /** The term [term] of an `ORDER BY`, ascending or [descending]: null after every value ascending, and before them descending. */
    protected open fun orderTerm(term: String, descending: Boolean) = if (descending) "$term DESC" else term

    /** The whole number of times the SQL whole number [b] goes into [a], short of a fraction. */
    protected open fun wholeQuotient(a: String, b: String) = "trunc($a / $b)"

    /** The values [value] of a group, or of a query's rows ordered by [order], as a JSON list: empty where there are none. */
    protected abstract fun aggregateList(value: String, order: String? = null): String

    /** `collect`: the values [value] of a group that are not null, as a JSON list, in the [order] of the rows where they have one. */
    protected abstract fun collect(value: String, order: String?): String

    /**
     * The items of the JSON list [list] once each, where each is first: what `collect(DISTINCT …)` keeps of what a `WITH`
     * before it collected, for a store dedupes no aggregate in the query that makes it. Where [temporal], the statement makes its
     * list hold temporal values at any depth, and its items are read as [temporalElements] reads them.
     */
    protected abstract fun distinctItems(list: String, temporal: Boolean = false): String

    /**
     * The items of the JSON list [list] whose item in [identities], a list as long, is first among those equal to it, as
     * [distinctItems] finds them: what `collect(DISTINCT …)` keeps of what holds a node or a relationship, by its identity.
     * Worked out in one pass, where comparing each identity with those before it took seconds over a few thousand.
     */
    protected abstract fun firstOfEach(list: String, identities: String): String

    /**
     * `min` or `max` of the JSON values [value] of a group: of values of several kinds, as [kindOrder] orders them; of one
     * kind, as JSON orders them, or by [key] where there is one, what orders them.
     */
    protected abstract fun extreme(value: String, min: Boolean, key: String? = null): String

    /**
     * `sum` of the JSON values [json] of a group, [distinct] each once where it is `DISTINCT `: 0 where there are none.
     * A store whose numbers keep whether they are floats sums them as they are: a float where any is one, as in Cypher.
     */
    protected open fun sum(json: String, distinct: String) = scalar("COALESCE(sum($distinct${number(json)}), 0)")

    /**
     * Whether a `sum` of the SQL numbers [numbers], [distinct] each once where it is `DISTINCT `, is past a long's
     * range (#339): on a store that sums integers exactly, the sum is a double there, as Neo4j's is. Neo4j goes on as a
     * double from the row where a long would overflow, so its answer follows the order the rows come in:
     * `[9223372036854775807, 1, -1]` is a double there, where the sum here, back in range, is a long. SQL keeps no
     * order, and the total is told by the sum the statement works out anyway, so no value is read again.
     */
    protected fun passesLong(numbers: String, distinct: String) =
        "sum($distinct$numbers) NOT BETWEEN ${Long.MIN_VALUE} AND ${Long.MAX_VALUE}"

    /**
     * Where the JSON [json]'s kind comes among the others, as Cypher orders values of several kinds: a map,
     * a list, a text, a truth value, then a number. JSON orders them otherwise on each store.
     */
    protected fun kindOrder(json: String): String = shared(json) { (x) ->
        "CASE ${typeOf(x)} WHEN 'object' THEN 1 WHEN 'array' THEN 2 WHEN 'string' THEN 3 WHEN 'boolean' THEN 4 WHEN 'number' THEN 5 END"
    }

    /**
     * The JSON list of ids [ids] with each id the map `{[key]: id}`, a node's or a relationship's identity (see `identityOf`), and
     * null where the list is: by its text, so it reads no aggregate in a subquery, as Oracle reads none.
     */
    protected abstract fun idTokens(ids: String, key: String): String

    /** Where the kind of the JSON [json], an item of a list, comes among the others as Cypher orders them: as [kindOrder], null last. */
    protected fun itemRank(json: String): String = "COALESCE(${kindOrder(json)}, 6)"

    /**
     * `a + b` of the JSON values [a] and [b]: numbers added, lists joined, texts put together. [given] is what the statement
     * gives each as, where it does, or [MadeText] where it makes it a text without giving it: see [summedText].
     */
    protected abstract fun add(a: String, b: String, given: Pair<Any?, Any?> = null to null): String

    /** A side of a `+` the statement makes a text, or null, though it does not give it: see [Value.Json.text]. */
    protected object MadeText

    /**
     * The text the JSON [json], a side of a `+` that may put a number and a text together, is written as there: by [written],
     * as `toString` writes it, a float as Java writes a double. Where the statement gives it a number, [given], it is that
     * number's text, worked out here; and where it gives the other side, [other], a number, or this side a text, it is its own
     * text, [plain], for a side is put together with a number only where it is a text. So is a side the statement makes a
     * text ([MadeText]), as `'x' + toString(i)`. So only a side the statement gives or makes neither way, which may be a float,
     * is written as `toString` writes it, which is long: `c.name + 1` compiles as it did before floats were so written.
     * NaN and the infinities count as given neither way: Postgres binds them as texts, so a number may be added to one as text.
     */
    protected fun summedText(json: String, given: Any?, other: Any?, plain: (String) -> String, written: (String) -> String): String = when {
        isGivenNumber(given) -> literal(given.toString())
        given is String || given === MadeText || isGivenNumber(other) -> plain(json)
        else -> written(json)
    }

    /** Whether [value], given by the statement, is a number JSON holds as one: NaN and the infinities it holds as none. */
    private fun isGivenNumber(value: Any?) = value is Long || value is Int || (value is Double && value.isFinite())

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

    /**
     * `replace`: the SQL text [text] with each [search] in it made [replacement]; [searchBytes] and [replacementBytes] are
     * how long they are in UTF-8, where the statement gives them.
     */
    protected open fun replaceText(text: String, search: String, replacement: String, searchBytes: Int? = null, replacementBytes: Int? = null) =
        "REPLACE($text, $search, $replacement)"

    /** The SQL text [text], its characters backwards. */
    protected abstract fun reverseText(text: String): String

    /** The SQL text [text], its characters backwards, as JSON: see Oracle's, which reverses a text past 4,000 bytes otherwise. */
    protected open fun reversedText(text: String) = scalar(reverseText(text))

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

    /** The ids of the list [list] as rows named [alias], whose id is [rowId] and whose place, counted from 1, [rowOrd], in its order. */
    protected abstract fun idRows(list: String, alias: String): String

    /** The id of the row named [alias] that [idRows] gives. */
    protected open fun rowId(alias: String) = "$alias.id"

    /** The place in its list, counted from 1, of the row named [alias] that [idRows] gives. */
    protected open fun rowOrd(alias: String) = "$alias.ord"

    /**
     * Whether a walk reads the row it is in: a condition of a quantified path pattern may then read a variable bound
     * outside it.
     */
    protected open val walksReadRow = true

    /** A walk of a variable-length relationship or a quantified path pattern: see [walk]. */
    protected abstract fun walk(walk: Walk): Walked

    /**
     * A walk from the node whose id is [from] to the one whose is [to], as rows named [name], to a depth from
     * [min] to [max] (any, if null). Each step takes the [hops] in turn, from the node the step is at, and meets
     * [conditions]: one hop for a variable-length relationship, a hop for each relationship of a quantified path
     * pattern's. A node the conditions read is the row [nodes] names, at its place among the hops' nodes: the
     * node the step is at first, then the far node of each hop. With [backwards] a walk of one hop is walked from
     * the pattern's later node. [seed], where there is one, is the condition the node walked from meets of its own,
     * over a node row.
     */
    protected class Walk(
        val from: String,
        val to: String,
        val name: String,
        val step: String,
        val hops: List<Hop>,
        val nodes: List<String?>,
        val min: Int,
        val max: Int?,
        val conditions: List<String>,
        val backwards: Boolean,
        val seed: ((String) -> String)?,
    ) {
        init {
            require(nodes.size == hops.size + 1) { "A walk's step has one node more than it has hops." }
            require(!backwards || hops.size == 1) { "A walk of more than one hop a step is walked forwards." }
        }

        /** A relationship a step takes, the row [edge], in [direction] from the node the step is at. */
        class Hop(val edge: String, val direction: RelationshipPattern.Direction)
    }

    /**
     * The condition that the relationship row [edge] leaves the node whose id is [at] in [direction], and the id of
     * the node it reaches.
     */
    protected fun leaves(edge: String, direction: RelationshipPattern.Direction, at: String): Pair<String, String> = when (direction) {
        RelationshipPattern.Direction.OUTGOING -> "$edge.src = $at" to "$edge.dst"
        RelationshipPattern.Direction.INCOMING -> "$edge.dst = $at" to "$edge.src"
        // Either way, and a relationship from a node to itself once.
        RelationshipPattern.Direction.EITHER -> "($edge.src = $at OR $edge.dst = $at)" to "CASE WHEN $edge.src = $at THEN $edge.dst ELSE $edge.src END"
    }

    /**
     * The joins of a walk's step from the node whose id is [at]: each hop's relationship, and each node a condition
     * reads. Gives them, the ids of the nodes the hops reach, in turn, and that no two relationships of the step are one.
     */
    protected fun steps(walk: Walk, at: String): Triple<List<String>, List<String>, List<String>> {
        val joins = mutableListOf<String>()
        val reached = mutableListOf<String>()
        walk.nodes.first()?.let { joins += "JOIN $nodes $it ON $it.nid = $at" }
        walk.hops.forEachIndexed { i, hop ->
            val (join, far) = leaves(hop.edge, hop.direction, reached.lastOrNull() ?: at)
            joins += "JOIN $edges ${hop.edge} ON $join"
            reached += far
            walk.nodes[i + 1]?.let { joins += "JOIN $nodes $it ON $it.nid = $far" }
        }
        val distinct = walk.hops.flatMapIndexed { i, a -> walk.hops.drop(i + 1).map { b -> "${a.edge}.eid <> ${b.edge}.eid" } }
        return Triple(joins, reached, distinct)
    }

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

    /**
     * One of openCypher's functions of numbers, [name], one of [MATH_FUNCTIONS] or `atan2`, of [numbers], the store's
     * SQL numbers, as a SQL float: null where one is null. Neo4j works these out as Java's doubles, and gives NaN or
     * an infinity for some (`log(0)`, `sqrt(-1)`, `exp(1000)`, `cot(0)`), which JSON holds neither of: there, the store fails.
     */
    protected abstract fun math(name: String, numbers: List<String>): String

    /**
     * `round(x)`, and `round(x, 0)`: the SQL number [number] to a whole number, half toward +infinity, as Java's `Math.round`
     * rounds. Worked out in decimal: a store whose numbers are decimal takes this, one whose numbers are binary its own,
     * where 0.49999999999999994 + 0.5 is 1.
     */
    protected open fun roundWhole(number: String): String = "FLOOR($number + 0.5)"

    /** `round(x, p)` of two arguments: [number] to [precision] places, half away from zero, as SQL's `ROUND` rounds. */
    protected open fun roundPlaces(number: String, precision: String): String = "ROUND($number, $precision)"

    /** The sign of the SQL number [number]: 1, -1 or 0, as SQL's `SIGN` gives it, and null where it is null. */
    protected open fun signOf(number: String): String = "SIGN($number)"

    /**
     * `round(value, precision, mode)`: the SQL number [number] rounded to [precision] decimal places by [mode], as a SQL
     * number. Worked out in decimal, as Neo4j works it out with Java's `BigDecimal`, so 2.345 is halfway between 2.34 and
     * 2.35: a store whose numbers are decimal (`numeric`, `NUMBER`) takes this, one whose numbers are binary its own.
     */
    protected open fun roundTo(number: String, precision: String, mode: RoundingMode): String = shared(number, precision) { (n, p) ->
        val scale = "POWER(CAST(10 AS NUMERIC), $p)"
        val scaled = "(ABS($n) * $scale)"
        // Rounded as a magnitude, then given the number's sign: UP, DOWN and the HALF_ modes round toward or away from 0.
        val magnitude = when (mode) {
            RoundingMode.UP -> "CEIL($scaled)"
            RoundingMode.DOWN -> "FLOOR($scaled)"
            RoundingMode.HALF_UP -> "FLOOR($scaled + 0.5)"
            RoundingMode.HALF_DOWN -> "CEIL($scaled - 0.5)"
            RoundingMode.HALF_EVEN -> "CASE WHEN $scaled - FLOOR($scaled) = 0.5 THEN FLOOR($scaled) + MOD(FLOOR($scaled), 2) ELSE FLOOR($scaled + 0.5) END"
            RoundingMode.CEILING, RoundingMode.FLOOR, RoundingMode.UNNECESSARY -> null
        }
        when (mode) {
            RoundingMode.CEILING -> "(CEIL($n * $scale) / $scale)"
            RoundingMode.FLOOR -> "(FLOOR($n * $scale) / $scale)"
            else -> "(${signOf(n)} * $magnitude / $scale)"
        }
    }

    /**
     * Fails where a `CALL` returns or yields a variable of [returned] that is bound around it, in [bound], as Neo4j fails:
     * those of the query part it is in, and not those around a `CALL { … }` that it is in and that does not import them.
     * In a statement that writes, each variable the row holds, which a `CALL { … }` there sees whether it imports it or not.
     */
    private fun redeclared(returned: Collection<String>, bound: Collection<String>) {
        returned.firstOrNull { it in bound }?.let { throw DrivineException("The variable $it is bound already, so a CALL does not return or yield it, as in Cypher: name it with AS.") }
    }

    /** `right`: the last [count] characters of the SQL text [text], or all of it where it has fewer. */
    protected abstract fun rightText(text: String, count: String): String

    /** `range`: the whole numbers from [from] to [to], [step] apart, SQL numbers each, as a JSON list. */
    protected abstract fun range(from: String, to: String, step: String): String

    /** The store's SQL for temporal values, which [temporals] works openCypher's out of. */
    protected abstract val temporalSql: TemporalSql

    /** openCypher's temporal values: see [Temporals]. */
    private val temporals by lazy { Temporals(temporalSql, sharedList) }

    // ----- A store's procedure, for a statement that writes -----

    /**
     * The statement that runs the procedure whose steps are [program], and gives the rows it returns, of one column. Its
     * variables are those the companion names ([PARAMS], [ROWS] …), [PARAMS] the object bound at [Parameters.OBJECT].
     * A store with a procedural language writes the steps in it: see [statements].
     */
    protected abstract fun procedure(program: List<ProcedureStep>): String

    /**
     * The steps [program] as the statements of a procedural language, each ending in `;`, indented by how deep in
     * loops and conditions it is, from [depth]: a loop over a query's rows is `FOR … IN (…) LOOP`, a value set into a
     * variable `SELECT … INTO …` or `… := …`, and the rows returned and a failure the store's own, [returnRows] and [raise].
     */
    protected fun statements(program: List<ProcedureStep>, depth: Int = 0): List<String> = program.flatMap { step ->
        fun line(text: String) = text.prependIndent("    ".repeat(depth))
        when (step) {
            is ProcedureStep.Select -> listOf(line("${planned("SELECT ${step.expression} INTO ${step.variable}${step.from}")};"))
            is ProcedureStep.Let -> listOf(line("${step.variable} := ${step.expression};"))
            is ProcedureStep.Loop ->
                listOf(line(if (step.multiline) "FOR ${step.record} IN (\n${indent(planned(step.query))}\n) LOOP" else "FOR ${step.record} IN (${planned(step.query)}) LOOP")) +
                    statements(step.body, depth + 1) + line("END LOOP;")
            is ProcedureStep.If -> listOf(line("IF ${step.condition} THEN")) + statements(step.body, depth + 1) + line("END IF;")
            is ProcedureStep.Raise -> listOf(line(raise(step.message)))
            is ProcedureStep.Execute -> listOf(line("${step.statement}${step.into?.let { " INTO $it" }.orEmpty()};"))
            is ProcedureStep.Return -> returnRows(planned(step.query)).map(::line)
        }
    }

    /** The statements of a procedural language that return the rows of the query [query], whose one column is `result`: see [statements]. */
    protected open fun returnRows(query: String): List<String> = throw UnsupportedOperationException("This store writes its procedure in no procedural language.")

    /** The statement of a procedural language that fails the procedure with [message]: see [statements]. */
    protected open fun raise(message: String): String = throw UnsupportedOperationException("This store writes its procedure in no procedural language.")

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

        /** The variables made here for a randomUUID() made first, for each row: no variable of the statement's, though one may be quoted as named alike. */
        private val madeAnew = mutableSetOf<String>()

        /** The queries given to compile, [query] and [procedure], whose names [hidden] keeps clear of, not yet in [queryTexts]. */
        private val queries = mutableListOf<Query>()

        /** Each text of the queries given to compile, as far as its first space ([textsIn]), gathered when [hidden] needs it. */
        private val queryTexts = mutableSetOf<String>()

        /**
         * Holds [query] among the [queries]: as [query] and [procedure] do, or first, for the queries of a `UNION` compiled one
         * by one, so a name made in one is named as no later one's variable is.
         */
        fun give(query: Query) {
            queries += query
        }

        /**
         * A variable named [prefix] and a number, new: named as no text of the statement's is, nor begins, so a variable quoted
         * alike, `` `#anew2` `` or `` `#anew2 row` ``, is not taken for it (#479). The counter moves past such a name only:
         * where none is quoted alike, the name is [alias]'s.
         */
        private fun hidden(prefix: String): String {
            if (queries.isNotEmpty()) {
                queries.forEach { textsIn(it, queryTexts) }
                queries.clear()
            }
            var name = alias(prefix)
            while (name in queryTexts) name = alias(prefix)
            return name
        }

        /** A variable of [madeAnew]'s, new, and no variable of the statement's: see [hidden]. */
        private fun anewVariable() = hidden(ANEW).also { madeAnew += it }

        fun query(query: Query): String {
            give(query)
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
         * of its own. [outer] is what a subquery reads from the row it is in. A `WITH *` alone where nothing is bound passes
         * on nothing, as Neo4j has it, and is left out: SQL has no query of no column (#472). Its `WHERE` is met on the rows it
         * reads, which hold what that reads; with `DISTINCT`, an order, `SKIP` or `LIMIT` it is refused, as it was.
         */
        private fun read(select: Select, clause: Clause, outer: Map<String, Value> = emptyMap()): Select = when (clause) {
            is Clause.Match -> match(select, clause)
            is Clause.Unwind -> anewBefore(select, listOf(clause.list), whole = true)
                ?.let { (made, read) -> made.also { unwind(it, clause.copy(list = read.single())) } } ?: select.also { unwind(it, clause) }
            is Clause.With ->
                if (clause.projection == Projection(false, true, emptyList(), emptyList(), null, null) && select.bound.keys.all { it in madeAnew }) {
                    clause.where?.let { filtered(select, it) } ?: select
                } else with(select, clause, outer)
            is Clause.Call -> anewCall(select, clause)
            else -> error("${clause::class.simpleName} reads nothing")
        }

        /**
         * The rows [select] reads kept where [where] holds; each randomUUID() it reads ([anew] tells one) made first, for each
         * row, as [anewBefore] makes it, then the condition over those rows.
         */
        private fun filtered(select: Select, where: Expression, anew: (Expression) -> Boolean = ::isAnew): Select {
            val (made, read) = anewBefore(select, listOf(where), whole = true, anew) ?: (select to listOf(where))
            return made.also { it.where += condition(read.single(), it.scope) }
        }

        /** The rows [select] reads with a UUID made for each, in a `WITH *` of its own, and its variable's name, which is no variable of the statement's: see `project`. */
        private fun seededRows(select: Select): Pair<Select, String> {
            val seed = anewVariable()
            val made = Projection(false, true, listOf(Projection.Item(Expression.Function("randomUUID", false, emptyList()), seed)), emptyList(), null, null)
            return with(select, Clause.With(made, null), select.scope.filterKeys { it !in select.bound }, apart = true) to seed
        }

        /**
         * A `CALL { … }` over the rows [select] reads; one whose query holds a randomUUID() ([holdsAnew]) first given a UUID
         * made for each row, in a `WITH *` of its own, which its query unwinds before its own clauses. A query that reads nothing
         * of the row it is joined to is worked out once for the statement, by each store, and every row was given the same
         * UUID (#412): so it reads the row. That UUID is no variable of the statement's: see `project`.
         */
        private fun anewCall(select: Select, call: Clause.Call): Select {
            if (!holdsAnew(call.query)) return select.also { call(it, call) }
            val (seeded, seed) = seededRows(select)
            // The variable its query unwinds the UUID to is no variable of the statement's either.
            val row = "$seed row".also { madeAnew += it }
            fun read(clauses: List<Clause>) = listOf(Clause.Unwind(Expression.ListLiteral(listOf(Expression.Variable(seed))), row)) + clauses
            val query = Query(read(call.query.clauses), call.query.unions.map { it.copy(clauses = read(it.clauses)) })
            return seeded.also { call(it, call.copy(query = query)) }
        }

        /** A `WITH`: a query of its own, which the rest of the statement reads from, as it reads [outer]. */
        private fun with(select: Select, with: Clause.With, outer: Map<String, Value> = emptyMap(), apart: Boolean = false): Select {
            val projected = project(select, with.projection, final = false)
            val name = alias("w")
            val next = Select(outer)
            next.walks = select.walks
            // A value made anew for each row is one value however often the clauses after read it: see [once]. Kept apart
            // from the query that reads it, where that query must not have it merged in: see `casesFirst`.
            val anew = with.projection.items.any { item -> holdsAnew(item.expression) }
            val sql = if (anew) once(projected.sql) else projected.sql
            next.source("(\n${indent(if (apart) called(sql) else sql)}\n) $name")
            projected.scope.forEach { (variable, value) -> next.bind(variable, value.columnsOf(name)) }
            projected.order.forEach { (column, descending) -> next.order += "$name.$column" to descending }
            return with.where?.let { filtered(next, it) } ?: next
        }

        /**
         * A `MATCH` over the rows [select] reads. Each randomUUID() its condition reads is made for each row it finds, first
         * ([filtered]). An `OPTIONAL MATCH` finds its rows in a lateral query, which reads nothing of the row where its pattern
         * does not: Oracle and SQLite worked it out once for the statement, and kept every row's match or none (#412). There
         * each is read beside a UUID made first for the row ([seededRows], [seeded]), so the lateral query reads the row, then
         * made for each row it finds. A shortest path's condition is met where its paths are found, in a lateral query too,
         * which Postgres worked out once where each row joins the same two nodes: each is read beside the row's UUID there as
         * well, and made in the condition.
         */
        private fun match(select: Select, match: Clause.Match): Select {
            // A shortest path is the shortest of the paths that meet the WHERE, as Cypher finds it: the WHERE is met where they are found.
            val shortestWhere = match.where?.takeIf { match.patterns.last().shortest != null }
            val where = match.where.takeIf { shortestWhere == null }
            val seededFirst = match.where?.let(::holdsAnew) == true && (match.optional || shortestWhere != null)
            val (row, seed) = if (seededFirst) seededRows(select) else select to null
            val metByPaths = shortestWhere?.let { if (seed == null) it else seeded(it, seed, madeAnew, inBody = true) }
            if (!match.optional) {
                patterns(row, match.patterns, metByPaths)
                return where?.let { filtered(row, it) } ?: row
            }
            // What is found is read by a lateral query, joined to keep the row when it finds nothing.
            var inner = Select(row.scope)
            patterns(inner, match.patterns, metByPaths)
            where?.let { inner = if (seed == null) filtered(inner, it) else filtered(inner, seeded(it, seed, madeAnew, inBody = true)) { e -> isSeeded(e, madeAnew) } }
            val found = inner.bound.filterKeys { it !in row.scope && it !in madeAnew }
            if (found.isEmpty() && inner.sources.isEmpty()) return row
            val name = alias("o")
            // Each variable found under a name of its own: a variable may be called what SQL will not take.
            val named = found.entries.map { (variable, value) -> Triple(variable, value, alias("v")) }
            val columns = named.flatMap { (_, value, column) -> value.columns(column) }.ifEmpty { listOf("1 AS one") }
            val kinds = named.flatMap { (_, value, column) -> value.columnKinds(column) }.ifEmpty { listOf("one" to false) }
            val text = listOf("SELECT ${columns.joinToString(", ")}") + inner.from() + listOfNotNull(inner.whereClause())
            row.join(lateral(text.joinToString("\n"), name, optional = true, kinds))
            named.forEach { (variable, value, column) -> row.bind(variable, value.columnsOf(PENDING, column).laterally(name, kinds)) }
            return row
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
            val projected = if (call.query.unions.isEmpty()) calledPart(select, call, call.query.clauses, null, distinct = false) else calledUnion(select, call)
            rowOrder = order
            redeclared(projected.scope.keys, select.bound.keys)
            val name = alias("c")
            select.join(lateral(called(projected.sql), name, call.optional, projected.columns))
            projected.scope.forEach { (variable, value) ->
                val returned = value.laterally(name, projected.columns)
                // A value taken from a write's row is null where an OPTIONAL CALL finds nothing: it is read as the call returns it (#336).
                select.bind(variable, if (call.optional && returned is Value.Json) returned.copy(rowKeys = null) else returned)
            }
            projected.order.forEach { (column, descending) -> select.order += lateralColumn(name, column, json = false) to descending }
        }

        /**
         * One query of [call], [clauses], for the row [select] reads: its returned items in the order [names] lists
         * them, where a `UNION` names them so, and kept distinct with [distinct]. It sees only the variables of the row it
         * imports ([VariableKinds.imports]): any other name in it is a new variable, as in Cypher (#410). What its brackets
         * import it sees throughout, after a `WITH` that does not pass it on too, as Neo4j has it; what a leading `WITH`
         * imports is gone after one. A leading `WITH *` passes on what it imports ([starredImports]).
         */
        private fun calledPart(select: Select, call: Clause.Call, clauses: List<Clause>, names: List<String>?, distinct: Boolean): Projected {
            val imported = calledScope(call, clauses, select.scope)
            var inner = Select(imported)
            importsApart(imported, clauses)?.let { (source, imports) ->
                inner.source(source)
                imports.forEach(inner::bind)
            }
            val bracketed = call.imports != null || call.importsAll
            val outer = if (bracketed) imported else emptyMap()
            val reading = if (bracketed) clauses.dropLast(1) else starredImports(clauses.dropLast(1), imported.keys)
            reading.forEach { inner = read(inner, it, outer) }
            val projection = (clauses.last() as Clause.Return).projection
            val items = names?.map { name -> projection.items.single { it.name == name } } ?: projection.items
            return project(inner, projection.copy(items = items, distinct = distinct || projection.distinct), final = false)
        }

        /**
         * The reading [clauses] of a `CALL { … }` without brackets, its leading `WITH *` naming each variable it [imports] in
         * place of its star (#472): those are no variables its query binds, so the star gave none of them, and was refused
         * where nothing else was bound. Not one the compiler made (see `anewCall`), which the statement never named. Where it
         * imports none, the `WITH` is as it was, and passes on nothing ([read]). With brackets, Neo4j's star gives none of what
         * they import, which the query sees throughout anyway, so their clauses are as they were.
         */
        private fun starredImports(clauses: List<Clause>, imports: Set<String>): List<Clause> {
            val leading = clauses.indexOfFirst { !(it is Clause.Unwind && it.variable in madeAnew) }
            val with = clauses.getOrNull(leading) as? Clause.With ?: return clauses
            val named = imports.filterNot { it in madeAnew }.sorted()
            if (!with.projection.star || named.isEmpty()) return clauses
            val items = named.filter { name -> with.projection.items.none { it.name == name } }.map { Projection.Item(Expression.Variable(it), it) }
            val replaced = with.copy(projection = with.projection.copy(star = false, items = items + with.projection.items))
            return clauses.subList(0, leading) + replaced + clauses.subList(leading + 1, clauses.size)
        }

        /**
         * The variables of the row, [scope], that the query [clauses] of [call] sees: those it imports ([VariableKinds.imports]),
         * and each the compiler made for it, whatever it imports. Those are no variables of the statement's: the UUID made for
         * each row that `anewCall` unwinds before the clauses, so a query holding a randomUUID() reads the row (#412). Imports
         * are read from the clauses after that `UNWIND`, as the statement wrote them, so a leading `WITH` imports still (#410).
         */
        private fun calledScope(call: Clause.Call, clauses: List<Clause>, scope: Map<String, Value>): Map<String, Value> {
            val written = clauses.dropWhile { it is Clause.Unwind && it.variable in madeAnew }
            val imports = VariableKinds.imports(call, written, scope.keys.filterNot { it in madeAnew })
            return scope.filterKeys { it in imports || it in madeAnew }
        }

        /**
         * Where the query [clauses] of a `CALL { … }` aggregates, the variables of the row it is in, [scope], read from a query
         * of no table of their own in the call's rows: that query, as a source, and the variables as read from it; null where
         * it aggregates nothing. Read from the row, a variable is of the row's query, not the call's: Postgres takes an aggregate
         * of it alone, `count(o)`, as one of the row's query, which it refuses in a FROM, and so does SQLite; Oracle's aggregate
         * reads a variable of an unwound row as null there, so `sum(i + o)` gave 0 (#368). Kept apart with [boundSelect], or
         * Oracle merges it back. Bound as the call's own, so a `WITH *` a projection works out first carries them: not a name
         * the call binds itself, as a CALL within it yields one, and not where its `RETURN` and each `WITH` is `*`, which would
         * return them: a `WITH` of named items before a `RETURN *` passes on only those, as it does without them.
         */
        private fun importsApart(scope: Map<String, Value>, clauses: List<Clause>): Pair<String, Map<String, Value>>? {
            val projections = clauses.mapNotNull { (it as? Clause.With)?.projection ?: (it as? Clause.Return)?.projection }
            if (projections.none { p -> (p.items.map { it.expression } + p.orderBy.map { it.expression }).any { contains(it, ::isAggregate) } }) return null
            if (projections.all { it.star }) return null
            val declared = clauses.flatMap { clause ->
                when (clause) {
                    is Clause.Unwind -> listOf(clause.variable)
                    is Clause.Call -> (listOf(clause.query.clauses) + clause.query.unions.map { it.clauses })
                        .flatMap { (it.last() as? Clause.Return)?.projection?.items.orEmpty().map { item -> item.name } }
                    else -> emptyList()
                }
            }.toSet()
            val named = scope.filterKeys { it !in declared }.entries.map { (variable, value) -> Triple(variable, value, alias("v")) }
            if (named.isEmpty()) return null
            val name = alias("i")
            val columns = named.flatMap { (_, value, column) -> value.columns(column) }
            return "(${boundSelect(columns.joinToString(", "))}) $name" to named.associate { (variable, value, column) -> variable to value.columnsOf(name, column) }
        }

        /** A `UNION` in a `CALL { … }`: each query's rows, the same items of the same kinds, matched by name as Cypher matches them. */
        private fun calledUnion(select: Select, call: Clause.Call): Projected {
            val query = call.query
            val parts = listOf(query.clauses) + query.unions.map { it.clauses }
            if (query.unions.map { it.all }.distinct().size > 1) throw DrivineException("UNION and UNION ALL are not mixed in one statement, as in Cypher.")
            val returns = parts.map { it.last() as Clause.Return }
            if (returns.any { it.projection.orderBy.isNotEmpty() }) unsupported("An ORDER BY in a query of a UNION in CALL { … }")
            val names = returns.first().projection.items.map { it.name }
            if (returns.any { r -> r.projection.items.map { it.name }.toSet() != names.toSet() }) throw DrivineException("Each query of a UNION returns the same names, as in Cypher.")
            val distinct = !query.unions.first().all
            var projected = parts.map { calledPart(select, call, it, names, distinct) }
            if (projected.map { p -> p.scope.values.map { it::class } }.distinct().size > 1) unsupported("A UNION in CALL { … } whose queries return values of different kinds")
            // A map or a list that holds a node carries a column more, of its identity. Where one query's value has it and
            // another's has none, as `collect([r])` beside `[]`, the queries are compiled again without identities, so their
            // columns match, and what they return compares by its properties, as it did before identities.
            if (projected.map { p -> p.scope.values.map { (it as? Value.Json)?.identity != null } }.distinct().size > 1) {
                val was = identities
                identities = false
                try {
                    projected = parts.map { calledPart(select, call, it, names, distinct) }
                } finally {
                    identities = was
                }
            }
            val sql = projected.joinToString(if (distinct) "\nUNION\n" else "\nUNION ALL\n") { "SELECT * FROM (\n${indent(it.sql)}\n) ${alias("q")}" }
            // A value is of a temporal kind where each query makes it of that one. It is at keys of a write's row ([Value.Json.rowKeys])
            // only where each query takes it from those: a property of it is read from the row there, which would read the first
            // query's value for every query's rows (#336). It is read from an unwound item ([Value.Json.fromList]) only where each
            // query reads it from one. Its kind is known only where each query writes it as a value of that one.
            val scope = projected.first().scope.mapValues { (variable, value) ->
                val all = projected.map { it.scope.getValue(variable) as? Value.Json }
                if (value !is Value.Json) value
                else value.copy(
                    temporal = all.map { it?.temporal }.distinct().singleOrNull(), items = all.map { it?.items }.distinct().singleOrNull(),
                    rowKeys = all.map { it?.rowKeys }.distinct().singleOrNull(), fromList = all.all { it?.fromList == true },
                    itemItems = all.map { it?.itemItems }.distinct().singleOrNull(), temporals = all.any { it?.temporals == true },
                    text = all.all { it?.text == true }, texts = all.all { it?.texts == true },
                    known = all.map { it?.known }.distinct().singleOrNull(),
                )
            }
            return Projected(sql, scope, emptyList(), projected.first().columns)
        }

        private fun unwind(select: Select, unwind: Clause.Unwind) {
            val list = expression(unwind.list, select.scope)
            val name = alias("u")
            // A list of nodes or relationships unwinds to them, as they are now, so a write reaches them; a null item is a null one.
            if (list is Value.Entities) {
                select.source(elements(list.ids, name))
                val row = alias(if (list.relationships) "e" else "n")
                if (list.relationships) {
                    select.join("LEFT JOIN $edges $row ON $row.eid = ${idOf(item(name), null)}")
                    select.bind(unwind.variable, Value.Relationship("$row.eid", "$row.src", "$row.dst", "$row.type", "$row.props"))
                } else {
                    select.join("LEFT JOIN $nodes $row ON $row.nid = ${idOf(item(name), null)}")
                    select.bind(unwind.variable, Value.Node("$row.nid", "$row.labels", "$row.props", "$row.stamp"))
                }
                select.order += itemNo(name) to false
                return
            }
            // A value that is no list is one row, and null no rows, as in Cypher.
            val items = shared(comparable(json(list))) { (x) ->
                "CASE WHEN ${typeOf(x)} = 'array' THEN $x WHEN NOT ${isJsonNull(x)} THEN ${comparable(Value.Json(jsonArray(listOf(x)), ordered = true))} END"
            }
            select.source(elementsOf(list, items, name))
            // An item of a list that holds no null holds none; of a list of texts, or of a text, is a text.
            select.bind(
                unwind.variable,
                Value.Json(
                    item(name), temporal = itemsOf(list), items = itemItemsOf(list), temporals = itemTemporalsOf(list), list = holdsLists(list), flat = isFlat(list),
                    text = holdsTexts(list) || isText(list), fromList = true,
                ),
            )
            select.order += itemNo(name) to false
        }

        /**
         * The relationships one part of a pattern matched: one, its id [eids], or with [many] those a
         * variable-length relationship or a quantified path pattern walked, a list of ids. Of the [types] it
         * may have; [nodes] is the list of the ids of the nodes it reaches after the one it starts from.
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
                if (relationship.group != null) {
                    hops += quantified(select, relationship, bound[i].nid, bound[i + 1].nid, seed(pattern.nodes[i], before))
                    return@forEachIndexed
                }
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
            if (relationship?.range == null || relationship.group != null) unsupported("A shortest path of anything but one variable-length relationship")
            val inner = Select(select.scope)
            val hops = mutableListOf<Hop>()
            // Its ends named, to find the shortest between each two of them where they were not bound before it.
            val ends = listOf(pattern.nodes.first().variable ?: SHORTEST_FROM, pattern.nodes.last().variable ?: SHORTEST_TO)
            pattern(inner, pattern.copy(nodes = listOf(pattern.nodes.first().copy(variable = ends[0]), pattern.nodes.last().copy(variable = ends[1])), shortest = null), hops)
            where?.let { inner.where += condition(it, inner.scope) }
            val found = inner.bound.filterKeys { it !in select.scope && it !in setOf(SHORTEST_FROM, SHORTEST_TO) }.entries.map { (variable, value) -> Triple(variable, value, alias("v")) }
            val length = number(idCount(hops.single().eids))
            val (from, to) = ends.map { (inner.scope.getValue(it) as Value.Node).nid }
            val columns = found.flatMap { (_, value, column) -> value.columns(column) } + "$length AS len" + "$from AS from_nid" + "$to AS to_nid"
            val kinds = found.flatMap { (_, value, column) -> value.columnKinds(column) } + listOf("len" to false, "from_nid" to false, "to_nid" to false)
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
            select.join(lateral(text, name, optional = false, kinds))
            found.forEach { (variable, value, column) -> select.bind(variable, value.columnsOf(PENDING, column).laterally(name, kinds)) }
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
            val walked = walked(
                Walk(from, to, alias("p"), alias("w"), listOf(Walk.Hop(edge, relationship.direction)), listOf(null, null), range.min ?: 1, range.max, conditions, backwards, seed)
            )
            select.source(walked.source)
            select.walks = true
            select.where += walked.condition
            // A variable on it is the list of its relationships.
            relationship.variable?.let { select.bind(it, relationshipList(walked.eids)) }
            return Hop(walked.eids, true, relationship.types, walked.nids)
        }

        /**
         * A quantified path pattern, `((a)-[r:R]->(b) WHERE …){min,max}`, from the node [from] to the node [to]: a walk
         * whose each step is a repetition of the group's pattern, that takes a relationship no more than once, as a
         * walk of a variable-length relationship does. Each variable of the group is bound to the list of what it
         * matched in each repetition, in turn.
         */
        private fun quantified(select: Select, relationship: RelationshipPattern, from: String, to: String, seed: ((String) -> String)?): Hop {
            val group = relationship.group!!
            val inner = group.pattern
            val rows = inner.nodes.map { alias("n") }
            val links = inner.relationships.map { alias("e") }
            val scope = inner.nodes.zip(rows).mapNotNull { (node, row) ->
                node.variable?.let { it to Value.Node("$row.nid", "$row.labels", "$row.props", "$row.stamp") }
            } + inner.relationships.zip(links).mapNotNull { (relationship, edge) ->
                relationship.variable?.let { it to Value.Relationship("$edge.eid", "$edge.src", "$edge.dst", "$edge.type", "$edge.props") }
            }
            scope.groupBy({ it.first }).forEach { (variable, values) ->
                if (values.size > 1) unsupported("A variable named twice in a quantified path pattern")
                if (select.scope[variable] != null) throw DrivineException("$variable is bound outside the quantified path pattern that names it.")
            }
            val tests = inner.nodes.mapNotNull { it.properties } + inner.relationships.mapNotNull { it.properties } + listOfNotNull(group.where)
            val read = tests.flatMap(::variablesIn).toSet()
            // A subquery reads every variable, for [variablesIn] names none of a subquery's: as [evaluate] takes it.
            val subquery = tests.any(::holdsSubquery)
            if (!walksReadRow && read.any { select.scope[it] != null }) unsupported("On Oracle, a quantified path pattern that reads a variable from outside it")
            if (!walksReadRow && subquery && select.scope.isNotEmpty()) unsupported("On Oracle, a subquery in a quantified path pattern that may read a variable from outside it")
            val within = select.scope + scope
            val outer = stepping
            stepping = true
            val conditions = try {
                inner.nodes.zip(rows).flatMap { (node, row) ->
                    listOfNotNull(
                        node.labels.takeIf { it.isNotEmpty() }?.let { hasLabels("$row.labels", it) },
                        node.properties?.let { propertiesMatch("$row.props", it, within) },
                    )
                } + inner.relationships.zip(links).flatMap { (relationship, edge) ->
                    listOfNotNull(types("$edge.type", relationship.types), relationship.properties?.let { propertiesMatch("$edge.props", it, within) })
                } + listOfNotNull(group.where?.let { condition(it, within) })
            } finally {
                stepping = outer
            }
            // A node is joined to the step where a condition reads it.
            val joined = inner.nodes.zip(rows).map { (node, row) ->
                row.takeIf { (subquery && node.variable != null) || node.labels.isNotEmpty() || node.properties != null || node.variable in read }
            }
            val range = relationship.range!!
            val walked = walked(
                Walk(from, to, alias("p"), alias("w"), inner.relationships.zip(links).map { (r, edge) -> Walk.Hop(edge, r.direction) }, joined, range.min ?: 1, range.max, conditions, false, seed)
            )
            select.source(walked.source)
            select.walks = true
            select.where += walked.condition
            // The step's nodes and relationships, k of each a repetition, in turn: each variable is every k-th of them.
            val k = inner.relationships.size
            val nids = joinIds(listOf(ids(from), walked.nids))
            inner.nodes.forEachIndexed { i, node -> node.variable?.let { select.bind(it, Value.Entities(every(nids, i, k, k - i), relationships = false)) } }
            inner.relationships.forEachIndexed { i, r -> r.variable?.let { select.bind(it, Value.Entities(every(walked.eids, i, k, 0), relationships = true)) } }
            val types = inner.relationships.takeIf { rs -> rs.all { it.types.isNotEmpty() } }?.flatMap { it.types }?.distinct().orEmpty()
            return Hop(walked.eids, true, types, walked.nids)
        }

        /**
         * The ids of the store's list [list] from the one after the first [first], every [stride]-th, short of the last
         * [leave], as a JSON list in its order.
         */
        private fun every(list: String, first: Int, stride: Int, leave: Int): String {
            val u = alias("u")
            val v = alias("v")
            return "(SELECT ${comparable(Value.Json(aggregateList(scalar("$v.id"), "$v.ord"), ordered = true))} FROM (SELECT ${named(rowId(u), "id")}, ${named(rowOrd(u), "ord")}, COUNT(*) OVER () AS cnt FROM ${idRows(list, u)}) $v " +
                "WHERE $v.ord > $first AND MOD($v.ord - ${first + 1}, $stride) = 0 AND $v.ord <= $v.cnt - $leave)"
        }

        /** Whether a quantified path pattern's conditions are being compiled: what they hold is in its walk's step. */
        private var stepping = false

        /** [walk]'s rows. On Oracle, no walk is in another's step, for its recursive query holds no `WITH` of its own. */
        private fun walked(walk: Walk): Walked {
            if (!walksReadRow && stepping) unsupported("On Oracle, a variable-length relationship or quantified path pattern in a subquery of a quantified path pattern")
            return walk(walk)
        }

        /** The nodes whose ids are the store's list [nids], in its order: null where the list is. */
        private fun nodeList(nids: String): Value.Entities = Value.Entities(idList(nids), relationships = false)

        /** The relationships whose ids are the store's list [eids], in its order: null where the list is. */
        private fun relationshipList(eids: String): Value.Entities = Value.Entities(idList(eids), relationships = true)

        /** The store's list of ids [list] as a JSON list of them, in its order: null where the list is. */
        private fun idList(list: String): String {
            val u = alias("u")
            return "CASE WHEN $list IS NOT NULL THEN (SELECT ${comparable(Value.Json(aggregateList(scalar(rowId(u)), rowOrd(u)), ordered = true))} FROM ${idRows(list, u)}) END"
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
            "CASE WHEN $ids IS NOT NULL THEN (SELECT ${aggregateList(value, itemNo(u))} FROM ${elements(ids, u)} LEFT JOIN $table $row ON $row.$id = ${idOf(item(u), null)}) END"
        }

        /**
         * The node or the relationship at [at] in [entities]: its id read from the list, and its columns from its row,
         * so it is compared by its id as a node bound by a pattern is. Null where the list has no such item.
         */
        private fun entityAt(entities: Value.Entities, at: String): Value {
            val id = idOf(element(entities.ids, at), null)
            val row = alias(if (entities.relationships) "e" else "n")
            fun column(name: String) = "(SELECT $row.$name FROM ${if (entities.relationships) edges else nodes} $row WHERE $row.${if (entities.relationships) "eid" else "nid"} = $id)"
            return if (entities.relationships) Value.Relationship(column("eid"), column("src"), column("dst"), column("type"), column("props"))
            else Value.Node(column("nid"), column("labels"), column("props"), column("stamp"))
        }

        /**
         * The rows of the list of ids [ids], as a `FROM` names them under [name], each joined to the row of its node or, of
         * [relationships], its relationship; and the item each row is, that node or relationship.
         */
        private fun entityItems(ids: String, relationships: Boolean, name: String): Pair<String, Value> {
            val row = alias(if (relationships) "e" else "n")
            return if (relationships) {
                "FROM ${elements(ids, name)} LEFT JOIN $edges $row ON $row.eid = ${idOf(item(name), null)}" to
                    Value.Relationship("$row.eid", "$row.src", "$row.dst", "$row.type", "$row.props")
            } else {
                "FROM ${elements(ids, name)} LEFT JOIN $nodes $row ON $row.nid = ${idOf(item(name), null)}" to
                    Value.Node("$row.nid", "$row.labels", "$row.props", "$row.stamp")
            }
        }

        /** A list comprehension over a list of values. */
        private fun listComprehension(comprehension: Expression.ListComprehension, listed: Value, scope: Map<String, Value>): Value {
            val list = comparable(json(listed))
            val name = alias("x")
            val inner = scope + (
                comprehension.variable to
                    Value.Json(item(name), temporal = itemsOf(listed), items = itemItemsOf(listed), temporals = itemTemporalsOf(listed), list = holdsLists(listed))
                )
            val projected = comprehension.projection?.let { expression(it, inner) }
            val projection = projected?.let { json(it).sql } ?: item(name)
            val filter = comprehension.where?.let { " WHERE ${condition(it, inner)}" }.orEmpty()
            // Of null, null, as in Cypher, and not an empty list.
            return Value.Json(
                shared(list) { (items) ->
                    "CASE WHEN NOT ${isJsonNull(items)} THEN (SELECT ${aggregateList(projection, itemNo(name))} FROM ${elementsOf(listed, items, name)}$filter) END"
                },
                ordered = true,
                items = if (projected != null) (projected as? Value.Json)?.temporal else itemsOf(listed),
                itemItems = if (projected != null) itemsOf(projected) else itemItemsOf(listed),
                temporals = if (projected != null) hasTemporals(projected) else holdsTemporals(listed),
                list = true,
                itemLists = projected?.let(::isList) ?: holdsLists(listed),
            )
        }

        /**
         * A list comprehension over a list of nodes or relationships: each item is the node or the relationship, and
         * what it keeps of them, with no projection or one of nodes or relationships, is a list of them.
         */
        private fun entityComprehension(comprehension: Expression.ListComprehension, listed: Value.Entities, scope: Map<String, Value>): Value {
            val name = alias("x")
            var kind: Value? = null
            val list = shared(listed.ids) { (ids) ->
                val (from, item) = entityItems(ids, listed.relationships, name)
                val inner = scope + (comprehension.variable to item)
                val projected = comprehension.projection?.let { expression(it, inner) } ?: item
                kind = projected
                val filter = comprehension.where?.let { " WHERE ${condition(it, inner)}" }.orEmpty()
                val value = when (projected) {
                    is Value.Node -> scalar(projected.nid)
                    is Value.Relationship -> scalar(projected.eid)
                    else -> json(projected).sql
                }
                // Of null, null, as in Cypher, and not an empty list.
                "CASE WHEN $ids IS NOT NULL THEN (SELECT ${aggregateList(value, itemNo(name))} $from$filter) END"
            }
            return when (val projected = kind) {
                is Value.Node -> Value.Entities(comparable(Value.Json(list, ordered = true)), false)
                is Value.Relationship -> Value.Entities(comparable(Value.Json(list, ordered = true)), true)
                else -> Value.Json(
                    list, ordered = true, items = (projected as? Value.Json)?.temporal, itemItems = projected?.let(::itemsOf), list = true,
                    itemLists = projected?.let(::isList) == true, temporals = projected?.let(::hasTemporals) == true,
                )
            }
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
                // A property holds no null, as a graph engine stores none in a list.
                val compiled = expression(value, scope)
                equality(Value.Json(propertyOf(props, key), flat = true), compiled, value, scalar(value, scope) || isFlat(compiled))
            }
            else -> containsAll(props, comparable(json(expression(properties, scope))))
        }

        // ----- A statement that writes -----

        /** The procedure's steps, in order: the steps of each loop and condition being written within it, innermost last. */
        private val blocks = ArrayDeque<MutableList<ProcedureStep>>().apply { addLast(mutableListOf()) }

        /** The variables a row holds, and what each is: what [ROWS] and [ROW] carry from clause to clause. */
        private val kinds = linkedMapOf<String, Kind>()

        /** The value each variable of [Kind.VALUE] was when a row was carried: it is read again of the kinds that value was of. */
        private val carried = hashMapOf<String, Value.Json>()

        /** The key each variable is held at in a row: a name of the compiler's, so any variable's name will do. */
        private val keys = hashMapOf<String, String>()

        /** The rows the last write left, as read from [ROWS], and how much was read of them then: nothing more is there to carry. */
        private var unread: Pair<Select, Int>? = null

        /** The steps of the procedure [query] is: its reads, a loop over its rows for each write, and what it returns. */
        fun procedure(query: Query): List<ProcedureStep> {
            give(query)
            var select = Select(emptyMap())
            for (clause in creates(query.clauses)) {
                if (clause is Clause.Return) step(ProcedureStep.Return(project(select, clause.projection, final = true).sql)) else select = next(select, clause)
            }
            return blocks.single()
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

        private fun step(step: ProcedureStep) {
            blocks.last() += step
        }

        /** The steps [body] writes, as the body of a loop or a condition. */
        private fun block(body: () -> Unit): List<ProcedureStep> {
            blocks.addLast(mutableListOf())
            body()
            return blocks.removeLast()
        }

        /** [body] for each row of [query], whose `item` is [record]`.item`: see [ProcedureStep.Loop]. */
        private fun loop(record: String, query: String, multiline: Boolean = false, body: () -> Unit) = step(ProcedureStep.Loop(record, query, multiline, block(body)))

        /** [body] where [condition] holds. */
        private fun branch(condition: String, body: () -> Unit) = step(ProcedureStep.If(condition, block(body)))

        private fun assign(variable: String, sql: String) = step(ProcedureStep.Select(variable, sql, ""))

        private fun let(variable: String, sql: String) = step(ProcedureStep.Let(variable, sql))

        private fun execute(statement: String, into: String? = null) = step(ProcedureStep.Execute(statement, into))

        private fun key(variable: String) = keys.getOrPut(variable) { "v${keys.size + 1}" }

        /** The rows [select] reads, each a JSON map of the variables it binds, into [ROWS]. */
        private fun carry(select: Select) {
            if (unread?.let { (rows, size) -> rows === select && size == select.size() } == true) return
            kinds.clear()
            carried.clear()
            val entries = select.scope.map { (variable, value) ->
                if (value is Value.Json) carried[variable] = value
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
            step(ProcedureStep.Select(ROWS, list, (from(select) + listOfNotNull(select.whereClause())).joinToString("") { "\n$it" }))
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
            select.order.joinToString(", ") { (term, descending) -> orderTerm(term, descending) }.ifEmpty { null }

        /** The rows in [ROWS], as a query reads them. */
        private fun rows(): Select {
            val select = Select(emptyMap())
            val r = alias("r")
            select.source(elements(ROWS, r))
            select.order += itemNo(r) to false
            bindRow(select, item(r))
            unread = select to select.size()
            return select
        }

        /**
         * The row in [ROW] alone, as a statement of a write reads it, with the variables [needed] bound: a row
         * may hold many, and each node or relationship bound is a join.
         */
        private fun row(needed: Set<String>?, written: Boolean = false): Select = Select(emptyMap()).also { bindRow(it, ROW, needed, written) }

        /**
         * Binds into [select] each variable the row [row] holds, of [needed] where given: a node or a relationship is read from
         * its table, as it is now. With [written], [row] is [ROW], and a value and each property of it are read as [rowValue]
         * reads them, for a statement that works out what a write writes. Each is of its kind in [held], and a value as it was
         * carried in [values]: those of the rows now, but for a row held from before.
         */
        private fun bindRow(
            select: Select, row: String, needed: Set<String>? = null, written: Boolean = false, held: Map<String, Kind> = kinds, values: Map<String, Value.Json> = carried,
        ) {
            held.filterKeys { needed == null || it in needed }.forEach { (variable, kind) ->
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
                    Kind.VALUE -> select.bind(
                        variable,
                        Value.Json(
                            if (written) rowValue(listOf(key)) else propertyOf(row, key), temporal = values[variable]?.temporal, items = values[variable]?.items,
                            itemItems = values[variable]?.itemItems, temporals = values[variable]?.temporals == true,
                            list = values[variable]?.list == true, itemLists = values[variable]?.itemLists == true,
                            text = values[variable]?.text == true, texts = values[variable]?.texts == true,
                            rowKeys = if (written) listOf(key) else null, fromList = !written, known = values[variable]?.known,
                        ),
                    )
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
         * them, in a comprehension's, a quantifier's or a reduce's expression too, reads every variable of the row, for
         * [variablesIn] names none of a subquery's.
         */
        private fun evaluate(variable: String, vararg expressions: Expression?, value: (Map<String, Value>) -> String) {
            val read = expressions.filterNotNull()
            val select = row(if (read.any(::holdsSubquery)) null else read.flatMap(::variablesIn).toSet(), written = true)
            step(ProcedureStep.Select(variable, value(select.scope), from(select).joinToString("") { "\n$it" }))
        }

        /** One write: made for each row of [ROWS] in turn, and the rows it leaves put back there. */
        private fun write(clause: Clause) {
            assign(OUT, literalJson(emptyList<Any>()))
            val deleting = clause is Clause.Delete && !clause.detach
            if (deleting) assign(DOOMED, literalJson(emptyList<Any>()))
            val r = alias("r")
            loop(RECORD, "SELECT ${named(item(r), "item")} FROM ${elements(ROWS, r)} ORDER BY ${itemNo(r)}") {
                let(ROW, "$RECORD.item")
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
            }
            let(ROWS, OUT)
            if (deleting) {
                // A node is deleted when the clause has deleted the relationships it deletes: one it does not delete must not be there.
                val d = alias("d")
                val doomed = "SELECT ${idOf(item(d), null)} FROM ${elements(DOOMED, d)}"
                step(ProcedureStep.Select(COUNT, "count(*)", " FROM $edges WHERE src IN ($doomed) OR dst IN ($doomed)"))
                branch("$COUNT > 0") { step(ProcedureStep.Raise("A node is deleted that still has relationships: delete them first, or DETACH DELETE it.")) }
                execute("DELETE FROM $nodes WHERE nid IN ($doomed)")
            }
        }

        /**
         * A `CALL { … }` that writes, over the rows in [ROWS]: its clauses written for each row alone, in turn, as a
         * graph engine writes them, so what one row writes is there for the next to read. One that returns nothing
         * leaves the rows as it found them; one that returns adds what it returns to each row it gives, the row's
         * own variables as they were before the call, whatever it bound to their names inside it.
         */
        private fun callWriting(call: Clause.Call) {
            if (call.optional) unsupported("An OPTIONAL CALL { … } that writes")
            if (call.query.unions.isNotEmpty()) unsupported("A UNION in CALL { … }")
            val ret = call.query.clauses.last() as? Clause.Return
            if (ret != null && ret.projection.items.any { contains(it.expression, ::isAggregate) }) unsupported("An aggregate in the RETURN of a CALL { … } that writes")
            if (nesting == NESTING) unsupported("A FOREACH or CALL { … } that writes nested more than $NESTING deep")
            ret?.let { r -> redeclared(r.projection.items.map { it.name }, kinds.keys) }
            val kept = "$SAVED${++nesting}"
            val gathered = "$GATHERED$nesting"
            val caller = "$CALLER$nesting"
            val before = LinkedHashMap(kinds)
            val carriedBefore = HashMap(carried)
            val returnedKinds = linkedMapOf<String, Kind>()
            val returnedCarried = hashMapOf<String, Value.Json>()
            let(kept, ROWS)
            if (ret != null) assign(gathered, literalJson(emptyList<Any>()))
            // Only what the call imports is seen in it: any other name in it is a new variable, as in Cypher (#410).
            val imported = VariableKinds.imports(call, call.query.clauses, before.keys)
            val clauses = imported(call, imported)
            // Read again after each WITH, where brackets import them: see below.
            val rebound = (call.imports != null || call.importsAll) && clauses.any { it is Clause.With }
            eachRow(kept) {
                if (ret != null || rebound) let(caller, "$RECORD.item")
                restore(before.filterKeys { it in imported }, carriedBefore.filterKeys { it in imported })
                var select = rows()
                for (clause in clauses) {
                    select = when {
                        clause is Clause.Return -> with(select, Clause.With(clause.projection, null))
                        // What brackets import is seen throughout the call, as Neo4j has it, after a WITH that does not pass it on too:
                        // read again from the row the call is written for, so `CALL (a) { WITH 1 AS one CREATE (a)-[:R]->() }` joins
                        // the row's a, not a new node. Its WHERE reads them too, so it is met after.
                        clause is Clause.With && rebound -> with(select, clause.copy(where = null)).also { w ->
                            bindRow(w, caller, imported - w.scope.keys, held = before, values = carriedBefore)
                            clause.where?.let { w.where += condition(it, w.scope) }
                        }
                        else -> next(select, clause)
                    }
                }
                if (ret != null) {
                    // The rows this row gives, after those of the rows before it: each the row the call was written for,
                    // read from before the call, with what it returns. A name of the row it returns is the row's.
                    carry(select)
                    val returned = kinds.filterKeys { it !in before }
                    returnedKinds.putAll(returned)
                    returnedCarried.putAll(carried.filterKeys { it in returned })
                    val (g, a, b) = listOf(alias("g"), alias("g"), alias("g"))
                    val row = comparable(
                        Value.Json(jsonObject(before.keys.map { key(it) to propertyOf(caller, key(it)) } + returned.keys.map { key(it) to propertyOf(item(b), key(it)) }), ordered = true)
                    )
                    val both = "SELECT 1 AS part, ${named(itemNo(a), "item_no")}, ${named(item(a), "item")} FROM ${elements(gathered, a)} " +
                        "UNION ALL SELECT 2 AS part, ${named(itemNo(b), "item_no")}, $row AS item FROM ${elements(ROWS, b)}"
                    step(ProcedureStep.Select(gathered, comparable(Value.Json(aggregateList("$g.item", "$g.part, $g.item_no"), ordered = true)), " FROM ($both) $g"))
                }
            }
            if (ret == null) {
                let(ROWS, kept)
                restore(before, carriedBefore)
            } else {
                let(ROWS, gathered)
                restore(before + returnedKinds, carriedBefore + returnedCarried)
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
            loop(RECORD, "SELECT ${named(item(r), "item")} FROM ${elements(rows, r)} ORDER BY ${itemNo(r)}") {
                assign(ROWS, appended(literalJson(emptyList<Any>()), "$RECORD.item"))
                unread = null
                body()
            }
        }

        /**
         * The clauses of [call] without a `WITH` that opens them and passes on variables it [imports] alone, as they are:
         * the row holds those already, and what brackets import is seen after it anyway, as Neo4j has it, so
         * `CALL (a, b) { WITH a SET b.x = 1 }` sets b's.
         */
        private fun imported(call: Clause.Call, imports: Set<String>): List<Clause> {
            val clauses = call.query.clauses
            val first = clauses.firstOrNull() as? Clause.With ?: return clauses
            val projection = first.projection
            val importing = first.where == null && !projection.distinct && projection.orderBy.isEmpty() && projection.skip == null && projection.limit == null &&
                projection.items.all { (it.expression as? Expression.Variable)?.name == it.name } &&
                (projection.star || projection.items.all { it.name in imports })
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
            val carriedBefore = HashMap(carried)
            let(saved, ROWS)
            val unwound = rows()
            unwind(unwound, Clause.Unwind(foreach.list, foreach.variable))
            carry(unwound)
            let(items, ROWS)
            eachRow(items) {
                var select = rows()
                for (clause in creates(foreach.clauses)) select = next(select, clause)
            }
            let(ROWS, saved)
            restore(before, carriedBefore)
            unread = null
            nesting--
        }

        /**
         * The rows' variables put back as they were, [before] of each its kind and [carriedBefore] its value, where a
         * `FOREACH` or a `CALL { … }` leaves the rows as it found them: a variable it bound again within is not of its kind.
         */
        private fun restore(before: Map<String, Kind>, carriedBefore: Map<String, Value.Json>) {
            kinds.clear()
            kinds.putAll(before)
            carried.clear()
            carried.putAll(carriedBefore)
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
                        execute(
                            "INSERT INTO $nodes (labels, props, stamp) VALUES (${literalJson(node.labels)}, ${stripNulls(unstamped(VALUE))}, ${stampOf(VALUE)}) RETURNING nid",
                            into = ID,
                        )
                        made(node.variable, Kind.NODE)
                    }
                    else -> throw DrivineException("${node.variable} is not a node, and is used as one in a pattern.")
                }
            }
            pattern.relationships.forEachIndexed { i, relationship ->
                if (relationship.group != null) unsupported("A quantified path pattern in CREATE or MERGE")
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
                execute("INSERT INTO $edges (src, dst, type, props) VALUES ($src, $dst, ${literal(type)}, ${stripNulls(VALUE)}) RETURNING eid", into = ID)
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
            let(FOUND, "FALSE")
            loop(MATCHED, match.joinToString("\n"), multiline = true) {
                let(FOUND, "TRUE")
                let(ROW, "$MATCHED.item")
                found.forEach { (variable, value) -> kinds[variable] = if (value is Value.Node) Kind.NODE else Kind.RELATIONSHIP }
                merge.onMatch.forEach(::set)
                keep()
            }
            kinds.clear()
            kinds.putAll(before)
            branch("NOT $FOUND") {
                create(merge.pattern, merging = true)
                merge.onCreate.forEach(::set)
                keep()
            }
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
                    // A map written, or a list written that holds one or a list, is no property, as Neo4j refuses it, and as drivine's saves do.
                    val written = item.value
                    if (written is Expression.MapLiteral || written is Expression.ListLiteral && written.items.any { it is Expression.MapLiteral || it is Expression.ListLiteral }) {
                        throw DrivineException("A property is a number, a text, a truth value, a temporal value or a list of them, not ${if (written is Expression.MapLiteral) "a map" else "a list of maps or lists"}, as in Neo4j.")
                    }
                    evaluate(VALUE, item.value) { scope -> comparable(json(expression(item.value, scope))) }
                    if (table == nodes && item.key == Stamps.PROPERTY) execute("UPDATE $nodes SET stamp = ${text(VALUE)} WHERE nid = $row")
                    else execute("UPDATE $table SET props = ${merged("props", jsonObject(listOf(item.key to VALUE)))} WHERE $id = $row")
                }
                is SetItem.Replace -> {
                    val (table, id, row) = target(item.variable, "SET")
                    evaluate(VALUE, item.value) { scope -> properties(expression(item.value, scope)) }
                    if (table == nodes) execute("UPDATE $nodes SET props = ${stripNulls(unstamped(orEmpty(VALUE)))}, stamp = ${stampOf(VALUE)} WHERE nid = $row")
                    else execute("UPDATE $table SET props = ${stripNulls(orEmpty(VALUE))} WHERE $id = $row")
                }
                is SetItem.Add -> {
                    val (table, id, row) = target(item.variable, "SET")
                    evaluate(VALUE, item.value) { scope -> properties(expression(item.value, scope)) }
                    if (table == nodes) {
                        // The stamp is kept where the map has no entry for it, and removed where its entry is null.
                        val stamp = "CASE WHEN ${propertyOf(VALUE, Stamps.PROPERTY)} IS NULL THEN stamp ELSE ${stampOf(VALUE)} END"
                        execute("UPDATE $nodes SET props = ${merged("props", unstamped(orEmpty(VALUE)))}, stamp = $stamp WHERE nid = $row")
                    } else {
                        execute("UPDATE $table SET props = ${merged("props", orEmpty(VALUE))} WHERE $id = $row")
                    }
                }
                is SetItem.Labels -> {
                    val (table, id, row) = target(item.variable, "SET")
                    if (table != nodes) throw DrivineException("${item.variable} is not a node, and a label is set on one.")
                    item.labels.forEach { label ->
                        execute("UPDATE $nodes SET labels = ${appended("labels", literalJson(label))} WHERE $id = $row AND NOT ${hasLabel("labels", label)}")
                    }
                }
            }
        }

        private fun remove(item: RemoveItem) {
            when (item) {
                is RemoveItem.Property -> {
                    val (table, id, row) = target(item.variable, "REMOVE")
                    if (table == nodes && item.key == Stamps.PROPERTY) execute("UPDATE $nodes SET stamp = NULL WHERE nid = $row")
                    else execute("UPDATE $table SET props = ${merged("props", jsonObject(listOf(item.key to literalJson(null))))} WHERE $id = $row")
                }
                is RemoveItem.Labels -> {
                    val (table, id, row) = target(item.variable, "REMOVE")
                    if (table != nodes) throw DrivineException("${item.variable} is not a node, and a label is removed from one.")
                    item.labels.forEach { label -> execute("UPDATE $nodes SET labels = ${withoutLabel("labels", label)} WHERE $id = $row") }
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
            targets.filter { it.second.first == edges }.forEach { (_, target) -> execute("DELETE FROM $edges WHERE eid = ${target.third}") }
            targets.filter { it.second.first == nodes }.forEach { (_, target) ->
                if (delete.detach) execute("DELETE FROM $nodes WHERE nid = ${target.third}")
                else assign(DOOMED, appended(DOOMED, scalar(target.third)))
            }
        }

        // ----- Projection -----

        /**
         * What a `WITH` or a `RETURN` makes of the rows it reads: the [sql] of its query, what each item's name is
         * bound to among its columns, the order of its rows, each term a column of the query and whether it descends,
         * and its [columns], each named and whether it holds JSON, as a [lateral] join of the query reads them.
         */
        inner class Projected(val sql: String, val scope: Map<String, Value>, val order: List<Pair<String, Boolean>>, val columns: List<Pair<String, Boolean>>)

        /**
         * The query a `WITH` or a `RETURN` makes of [select]: its items as columns, grouped where it
         * aggregates, then ordered, skipped and limited. Rows it does not order, group or keep distinct
         * stay in the order [select] reads them, as a graph engine keeps them.
         */
        fun project(select: Select, written: Projection, final: Boolean): Projected {
            // As in Neo4j, a star gives the variables in the order of their names, before the items named after it.
            // Not a randomUUID() made first for a CALL or a condition (see `anewCall`), which the statement never named.
            val starred = select.bound.keys.filterNot { it in madeAnew }.sorted()
            val items = (if (written.star) starred.map { Projection.Item(Expression.Variable(it), it) } else emptyList()) + written.items
            if (items.isEmpty()) throw DrivineException("A projection has at least one item.")
            // An order term, or a part of one, written as an item but for its functions' names' case is that item, as Neo4j
            // names a function in any case: so `ORDER BY TOUPPER(x.a) + count(*)` after `toUpper(x.a)` reads the key.
            val projection = written.copy(
                orderBy = written.orderBy.map { sort ->
                    sort.copy(expression = replaced(sort.expression) { part -> items.firstOrNull { it.expression != part && keyOf(it.expression) == keyOf(part) }?.expression })
                },
            )
            // Before aggregatesFirst, which names each variable it groups by as an item of its own.
            requireExplicitGrouping(items, projection.orderBy, select.scope.keys)
            anewFirst(select, projection, items)?.let { (made, kept) -> return project(made, kept, final) }
            casesFirst(select, projection, items)?.let { (cased, kept) -> return project(cased, kept, final) }
            // After casesFirst, which refuses what it must of an aggregate's CASE before working it out first.
            sharedFirst(select, projection, items)?.let { (shared, kept) -> return project(shared, kept, final) }
            sortsFirst(select, projection, items)?.let { (sorted, kept) -> return project(sorted, kept, final) }
            aggregatesFirst(select, projection, items)?.let { (grouped, kept) -> return project(grouped, kept, final) }
            heldFirst(select, projection, items)?.let { (held, keyed) -> return project(held, keyed, final) }
            if (final && items.size > 1) {
                logger.warn(
                    "The statement returns {} items ({}). Prefer returning a single map or value, such as RETURN {{ key1: val1, key2: val2 }}: " +
                        "on Oracle, Postgres and SQLite it is returned as a list of its items, as drivine gives a row of several columns.",
                    items.size, items.joinToString { it.name },
                )
            }
            rowOrder = orderOf(select)
            val compiled = items.map { item -> item.name to aggregating(item.expression) { item(item.expression, select) } }
            val aggregating = items.any { contains(it.expression, ::isAggregate) }
            // Where rows are compared (DISTINCT, GROUP BY), JSON is compared as what compares.
            val compared = projection.distinct || aggregating
            val columns = compiled.mapIndexed { i, (_, value) -> "c${i + 1}" to (if (compared && value is Value.Json && value.ordered) value.copy(sql = comparable(value), ordered = false) else value) }
            val selectList = columns.flatMap { (column, value) -> value.columns(column) }.toMutableList()
            // An order is written over the items' names, and where nothing is grouped or kept distinct, the variables too.
            val name = alias("p")
            // Where rows are compared, an order term of the items that is no item is read over their columns, once compared:
            // a group is keyed by an item, and by no part of one.
            val itemColumns = items.zip(columns).associate { (item, pair) -> item.name to pair.second.columnsOf(name, pair.first) }
            val after = mutableMapOf<String, List<String>>()
            // A term worked out in the query is sorted by in the query that reads it: the value carried out as a column, and
            // what sorts it (JSON's null as SQL's) worked out of that column. Oracle (23.26), sorting by a value that reads
            // the row more than once beside a variable-length walk, closes the session.
            val sorted = mutableMapOf<String, (String) -> List<String>>()
            // An item made anew for each row, randomUUID(), is sorted by of its column, as it is given, not made again: see [once].
            val anew = items.filter { contains(it.expression, ::isAnew) }.map { it.name }.toSet()
            var sortsAnew = false
            fun carried(column: String, value: () -> Value, aggregates: Expression) {
                val (carried, sorts) = aggregating(aggregates) { sortTerm(value()) }
                selectList += "$carried AS $column"
                sorted[column] = sorts
            }
            val sorts = projection.orderBy.mapIndexed { i, sort ->
                val column = "s${i + 1}"
                // An order term that is an item, as written, is that item: so it may be ordered by where rows are compared.
                val item = items.indexOfFirst { it.expression == sort.expression }
                // A variable an item is named after is that item, not the variable of that name before it.
                val overItems = if (!aggregating) null else replaced(sort.expression) { part ->
                    if (part is Expression.Variable && part.name in itemColumns) part else items.firstOrNull { it.expression == part }?.let { Expression.Variable(it.name) }
                }
                when {
                    contains(sort.expression) { it is Expression.Variable && it.name in anew } && variablesIn(sort.expression).all { it in itemColumns } -> {
                        after[column] = sortable(expression(sort.expression, itemColumns))
                        sortsAnew = true
                    }
                    item >= 0 -> carried(column, { compiled[item].second }, sort.expression)
                    compared && !contains(sort.expression) { isAggregate(it) || it is Expression.Variable && it.name.startsWith("#aggregate") } ->
                        after[column] = sortable(expression(sort.expression, itemColumns))
                    // Where rows are grouped, so is one whose every aggregate and key is an item, each such part read by its item's
                    // name, as aggregatesFirst reads it: worked out in the query, each key is read again as the GROUP BY reads it
                    // ([keysRead]), on Oracle through its text ([itemProperty]), at each level of an operation over it, and
                    // `me.age + count(you.age)` compiled to twice what it did in 0.3.0 (#413).
                    overItems != null && !holdsSubquery(overItems) && !contains(overItems, ::isAggregate) && variablesIn(overItems).all { it in itemColumns } ->
                        after[column] = sortable(expression(overItems, itemColumns))
                    // One that aggregates reads the rows it aggregates, as an item does; so does one that reads an aggregate
                    // aggregatesFirst worked out first, which no item is. Beside a variable-length walk it binds no value in a
                    // subquery: carried out of a WITH's query, one so bound closes Oracle's (23.26) session (ORA-03113), as
                    // `WITH b ORDER BY toString(coalesce(b.name, b.title))` did, its argument long enough to be bound. Elsewhere
                    // it does, so operations nested in it compile to a statement that grows with them, not exponentially.
                    // Where rows are grouped, a part of it that is an item keying the groups reads that item's column value, as
                    // the GROUP BY does: Oracle refuses another read of the key (ORA-00979), as `me.age` read from the
                    // properties beside `me.age AS age` read through its text ([itemProperty]) was (#413).
                    else -> {
                        val (read, keys) = if (aggregating) keysRead(sort.expression, items, columns) else sort.expression to emptyMap()
                        val term = { carried(column, { expression(read, select.scope + compiled.toMap() + keys) }, sort.expression) }
                        if (select.walks) unshared(term) else term()
                    }
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
            // What sorts a term in the query that reads this one: worked out there, of the column carried out, or that column.
            // Each is named for the query after it, which sorts by them in turn: the last by the term's column, any before it,
            // where its kind comes ([openSortKey]), by that column with a number after it.
            fun sortedBy(column: String): List<Pair<String, String>> =
                (after[column] ?: sorted[column]?.invoke("$name.$column") ?: listOf("$name.$column")).let { terms ->
                    terms.mapIndexed { i, term -> (if (i == terms.lastIndex) column else "${column}_${i + 1}") to term }
                }
            val order = sorts.flatMap { (column, descending) -> sortedBy(column).map { (named, _) -> named to descending } }
            val outerColumns = if (final) {
                val values = columns.map { (column, value) -> json(value.columnsOf(name, column)) }
                val result = if (values.size == 1) values.single().sql else jsonArray(values.map { it.sql })
                "$result AS result"
            } else {
                (columns.flatMap { (column, value) -> value.columns(column).map { "$name.${it.substringAfterLast(" AS ")}" } } +
                    sorts.flatMap { (column, _) -> sortedBy(column).map { (named, term) -> "$term AS $named" } }).joinToString(", ")
            }
            val outer = buildList {
                add("SELECT $outerColumns")
                add("FROM (\n${indent(if (sortsAnew) once(inner) else inner)}\n) $name")
                if (sorts.isNotEmpty()) {
                    add("ORDER BY ${sorts.flatMap { (column, descending) -> sortedBy(column).map { (_, term) -> orderTerm(term, descending) } }.joinToString(", ")}")
                }
                addAll(page(projection.skip?.let(::count), projection.limit?.let(::count)))
            }.joinToString("\n")
            // What each item is bound to in the query that reads this one: its columns, which that query names.
            val scope = items.zip(columns).associate { (item, pair) -> item.name to pair.second.columnsOf(PENDING, pair.first) }
            val kinds = columns.flatMap { (column, value) -> value.columnKinds(column) } + order.map { (column, _) -> column to false }
            return Projected(outer, scope, order, kinds)
        }

        /**
         * [term], each part of it that is an item of a grouping projection keying the groups, one of [items], read by a
         * name no variable has, and what each such name is bound to: the item's value among [columns], as it is grouped by.
         * [replaced] reads no comprehension's, quantifier's or reduce's own expressions, so a part it reads is never one
         * whose variables such a one binds again: a key is read beside `[me IN [1] | me]` as anywhere else.
         */
        private fun keysRead(term: Expression, items: List<Projection.Item>, columns: List<Pair<String, Value>>): Pair<Expression, Map<String, Value>> {
            val keys = items.withIndex().filter { (_, item) -> !contains(item.expression, ::isAggregate) && variablesIn(item.expression).isNotEmpty() }
                .associate { (i, item) -> item.expression to i }
            val bound = mutableMapOf<String, Value>()
            // Named as no variable is, unless quoted so.
            val read = replaced(term) { part -> keys[part]?.let { i -> Expression.Variable("#key ${i + 1}").also { bound[it.name] = columns[i].second } } }
            return read to bound
        }

        /**
         * The rows [select] reads with each randomUUID() that a term of the projection reads inside a larger expression made
         * first ([anewBefore]), and the projection with the term reading that UUID's column; null where there is none. A term
         * that is a randomUUID() alone is a column of the projection already. Two items written alike are given a UUID each,
         * as in Neo4j; an order term written as an item reads that item's, as Neo4j sorts by the item.
         */
        private fun anewFirst(select: Select, projection: Projection, items: List<Projection.Item>): Pair<Select, Projection>? {
            val itemTerms = items.map { it.expression }
            val orderTerms = projection.orderBy.map { it.expression }.filter { it !in itemTerms }.distinct()
            val (made, read) = anewBefore(select, itemTerms + orderTerms, whole = false) ?: return null
            val reads = itemTerms.zip(read).reversed().toMap() + orderTerms.zip(read.drop(items.size))
            val kept = projection.copy(
                star = false,
                items = items.zip(read) { item, term -> item.copy(expression = term) },
                orderBy = projection.orderBy.map { it.copy(expression = reads.getValue(it.expression)) },
            )
            return made to kept
        }

        /**
         * The rows [select] reads with each randomUUID() that [terms] read made first, for each row, in a `WITH *` of its own,
         * and each term as it reads that UUID's column, in their order; null where there is none, or, where not [whole], where
         * each is a term alone. Worked out in the term, the UUID could be bound in a subquery that reads nothing of the row
         * ([shared]), or read in a comprehension's subquery that reads nothing of it either, and each store works such a
         * subquery out once for the statement: every row was given the same UUID (#412). Made first, it is a column of the
         * row, as a `WITH`'s own randomUUID() is ([once]). One in a comprehension's, a quantifier's, a reduce's or a pattern
         * comprehension's own expressions is made again for each item there, as in Neo4j: it is read beside a UUID made first
         * for the row ([seeded]), so that subquery reads the row. Each call is a UUID of its own, in two terms written alike
         * too, as in Neo4j, where `randomUUID() = randomUUID()` is false. [anew] tells a call: see [match] for another.
         */
        private fun anewBefore(select: Select, terms: List<Expression>, whole: Boolean, anew: (Expression) -> Boolean = ::isAnew): Pair<Select, List<Expression>>? {
            // A call read beside a row's UUID already ([isSeeded]) reads the row: left as it is, as a call alone is.
            fun seededHere(e: Expression) = isSeeded(e, madeAnew)
            fun alone(term: Expression) = anew(term) || seededHere(term)
            fun holds(e: Expression): Boolean = anew(e) || (!seededHere(e) && children(e).any(::holds))
            val inBodies = terms.any { seeded(it, ANEW, madeAnew) != it }
            if (!inBodies && terms.none { (whole || !alone(it)) && holds(it) }) return null
            val made = mutableListOf<Projection.Item>()
            val seed = if (!inBodies) null else anewVariable().also { made += Projection.Item(Expression.Function("randomUUID", false, emptyList()), it) }
            val reads = terms.map { term ->
                if (!whole && alone(term)) term
                else replaced(term) { e ->
                    if (anew(e)) Expression.Variable(anewVariable()).also { made += Projection.Item(e, it.name) } else e.takeIf(::seededHere)
                }
                    .let { read -> if (seed == null) read else seeded(read, seed, madeAnew) }
            }
            val with = Projection(false, true, made, emptyList(), null, null)
            // Kept apart, as sortsFirst keeps its own, so no store merges it back into the query that reads it.
            return with(select, Clause.With(with, null), select.scope.filterKeys { it !in select.bound }, apart = true) to reads
        }

        /**
         * On a store that aggregates no `CASE` holding a subquery ([aggregatesCaseSubqueries]), the rows [select] reads with
         * each aggregate's argument that is such a `CASE` worked out first, in a `WITH *` of its own, and the projection with
         * the aggregate of that argument read from there: Oracle (23.26) fails `collect(CASE WHEN … THEN [r] ELSE [m.title] END)`
         * with ORA-00979, or closes the session (ORA-03113). Null where there is none.
         */
        private fun casesFirst(select: Select, projection: Projection, items: List<Projection.Item>): Pair<Select, Projection>? {
            if (aggregatesCaseSubqueries) return null
            val arguments = linkedSetOf<Expression>()
            // Those of a collect whose list is read, not only counted by size(): see below.
            val gathered = linkedSetOf<Expression>()
            (items.map { it.expression } + projection.orderBy.map { it.expression }).forEach { term ->
                val sized = mutableListOf<Expression>()
                contains(term) { e ->
                    if (e is Expression.Function && e.name.lowercase() == "size") sized += e.arguments
                    if (isAggregate(e) && e is Expression.Function) e.arguments.filter { argument ->
                        contains(argument) { it is Expression.Case } && unshared { json(expression(argument, select.scope)).sql }.contains("SELECT", ignoreCase = true)
                    }.forEach {
                        arguments += it
                        if (e.name.lowercase() == "collect" && sized.none { s -> s === e }) gathered += it
                    }
                    false
                }
            }
            if (arguments.isEmpty()) return null
            // Gathered by collect, a list of nodes or relationships beside a value of another kind, or the null of a CASE with no
            // ELSE, closes Oracle's (23.26) session (ORA-03113) wherever the CASE is worked out: refused, as Oracle gave an error of
            // its own for it (ORA-00979) before. Counted, by count() or the size() of the collect, it is read.
            gathered.forEach { argument ->
                contains(argument) { e ->
                    if (e is Expression.Case) {
                        val kinds = (e.alternatives.map { it.second } + listOfNotNull(e.otherwise)).map { unshared { expression(it, select.scope) } is Value.Entities } +
                            listOfNotNull(false.takeIf { e.otherwise == null })
                        if (kinds.any { it } && !kinds.all { it }) unsupported("On Oracle, an aggregate of a CASE that gives a list of nodes or relationships beside a value of another kind")
                    }
                    false
                }
            }
            // Named as no variable is, unless quoted so.
            val names = arguments.associateWith { "#case${++counter}" }
            val with = Projection(false, true, names.map { (argument, name) -> Projection.Item(argument, name) }, emptyList(), null, null)
            fun cased(expression: Expression) = replaced(expression) { names[it]?.let(Expression::Variable) }
            val kept = projection.copy(
                star = false,
                items = items.map { it.copy(expression = cased(it.expression)) },
                orderBy = projection.orderBy.map { it.copy(expression = cased(it.expression)) },
            )
            // Kept apart, or Oracle merges it back into the query that aggregates.
            return with(select, Clause.With(with, null), apart = true) to kept
        }

        /**
         * The rows [select] reads with each aggregate's argument that [shared] would bind a value in worked out first, in a
         * `WITH *` of its own, and the projection with the aggregate of that argument read from there; null where there is none.
         * An aggregate's argument is compiled with no value bound ([aggregating]): bound in a subquery around the aggregate,
         * a value would be taken from the group the aggregate reads, which no store reads from a subquery's FROM. Unbound, an
         * operation that reads its operands more than once writes them out again at each level, and `count(t + duration({minutes: i}))`
         * compiled to megabytes (#333). Worked out first, for each row, the argument binds its values as any item does, and
         * the aggregate reads its column, as it does where a `WITH` works it out first in the statement itself.
         */
        private fun sharedFirst(select: Select, projection: Projection, items: List<Projection.Item>): Pair<Select, Projection>? {
            if (!sharing) return null
            // Order terms too, beside a variable-length walk as well: there `project` binds no value in one that is none of the
            // items, so worked out here, in a WITH's item, is the only place its aggregate's argument binds one.
            val terms = items.map { it.expression } + projection.orderBy.map { it.expression }
            val arguments = linkedSetOf<Expression>()
            terms.forEach { term ->
                contains(term) { e ->
                    if (isAggregate(e) && e is Expression.Function) e.arguments.filter { it !in arguments && binds(it, select) }.forEach { arguments += it }
                    false
                }
            }
            if (arguments.isEmpty()) return null
            // Named as no variable is, unless quoted so.
            val names = arguments.associateWith { "#shared${++counter}" }
            val with = Projection(false, true, names.map { (argument, name) -> Projection.Item(argument, name) }, emptyList(), null, null)
            fun shared(expression: Expression) = replaced(expression) { term ->
                if (isAggregate(term) && term is Expression.Function) term.copy(arguments = term.arguments.map { names[it]?.let(Expression::Variable) ?: it }) else null
            }
            val kept = projection.copy(
                star = false,
                items = items.map { it.copy(expression = shared(it.expression)) },
                orderBy = projection.orderBy.map { it.copy(expression = shared(it.expression)) },
            )
            // Kept apart, as casesFirst keeps its own, so no store merges it back into the query that aggregates.
            return with(select, Clause.With(with, null), select.scope.filterKeys { it !in select.bound }, apart = true) to kept
        }

        /**
         * Beside a variable-length walk, the rows [select] reads with each term the projection sorts by that [shared] would
         * bind a value in worked out first, in a `WITH *` of its own, and the projection sorted by that term's column; null
         * where there is none. There `project` binds no value in a term that is none of the items (a value so bound, carried
         * out of a WITH's query, closed Oracle's session), so an operation that reads its operands more than once wrote them
         * out again at each level, and `ORDER BY (datetime(b.at) + duration({minutes: b.m})).minute` compiled to megabytes
         * (#378). Worked out first, for each row, the term binds its values as any item does, as where a `WITH` works it out
         * first in the statement itself.
         *
         * Only where rows are not compared: after DISTINCT or an aggregate, a term is sorted by over the items' columns, as
         * Neo4j sorts by nothing else there. A term that reads an item's name reads that item's expression, as it is sorted
         * by, in a comprehension's, a quantifier's or a reduce's own expressions too. Over aggregatesFirst's grouping `WITH`,
         * whose rows are its groups already, a term over an aggregate's result reads that result's column as any other. Left
         * as it was, in the order these are seen: a term holding an aggregate or randomUUID(); one holding a subquery, whose
         * names neither [scoped] nor [variablesIn] sees; one that reads an item's name where it is not replaced, where a name
         * the comprehension binds would hide it or a variable of the item's expression, in a pattern or as a map projection's
         * variable; and one that, so read, is an item or binds nothing. An item made anew, randomUUID(), that such a term
         * reads is made first, in a `WITH *` before the term's, and read from there by both.
         */
        private fun sortsFirst(select: Select, projection: Projection, items: List<Projection.Item>): Pair<Select, Projection>? {
            if (!sharing || !select.walks || projection.distinct || items.any { contains(it.expression, ::isAggregate) }) return null
            val named = items.associate { it.name to it.expression }
            val terms = linkedMapOf<Expression, Expression>()
            // Each item made anew that a term reads, by its name, and the column it is made first in.
            val made = linkedMapOf<String, String>()
            projection.orderBy.map { it.expression }.distinct().forEach { term ->
                // An aggregate is sorted by as `project` sorts it; so is a randomUUID() of its own.
                if (contains(term) { isAggregate(it) || isAnew(it) }) return@forEach
                // A subquery's, `COUNT { … }`'s, names are seen by neither scoped nor variablesIn: one there is read as it was.
                if (holdsSubquery(term)) return@forEach
                // Each item's name read first as a name no variable has, so one left where it is not replaced is seen.
                val marked = scoped(term) { v, local ->
                    v.takeIf { it.name in named && it.name !in local && local.none { name -> name in variablesIn(named.getValue(it.name)) } }
                        ?.let { Expression.Variable("#item ${it.name}") }
                }
                if (variablesIn(marked).any { it in named }) return@forEach
                val read = scoped(marked) { v, _ -> if (v.name.startsWith("#item ")) named[v.name.removePrefix("#item ")] else null }
                // A term that is an item, as written or by its name, is sorted by as `project` sorts it: by the item, bound as it is.
                if (items.none { it.expression == read } && binds(read, select)) {
                    terms[term] = read
                    contains(marked) { e ->
                        val name = (e as? Expression.Variable)?.name?.takeIf { it.startsWith("#item ") }?.removePrefix("#item ")
                        if (name != null && contains(named.getValue(name), ::isAnew)) made.getOrPut(name) { "#sort${++counter}" }
                        false
                    }
                }
            }
            if (terms.isEmpty()) return null
            // An item made anew for each row, randomUUID(), that a term reads is made first, in a WITH of its own, as a WITH's
            // items read none of each other; the projection, compiled again, reads it and works the term out over its column.
            // Worked out in the term, it would be made again, and the rows sorted by a value none of them is given.
            if (made.isNotEmpty()) {
                val with = Projection(false, true, made.map { (name, column) -> Projection.Item(named.getValue(name), column) }, emptyList(), null, null)
                val kept = projection.copy(star = false, items = items.map { item -> made[item.name]?.let { item.copy(expression = Expression.Variable(it)) } ?: item })
                return with(select, Clause.With(with, null), select.scope.filterKeys { it !in select.bound }, apart = true) to kept
            }
            // Named as no variable is, unless quoted so.
            val names = terms.keys.associateWith { "#sort${++counter}" }
            val with = Projection(false, true, terms.map { (term, read) -> Projection.Item(read, names.getValue(term)) }, emptyList(), null, null)
            val kept = projection.copy(
                star = false,
                items = items,
                orderBy = projection.orderBy.map { sort -> names[sort.expression]?.let { sort.copy(expression = Expression.Variable(it)) } ?: sort },
            )
            // Kept apart, as sharedFirst keeps its own, so no store merges the value it binds back into the query beside the walk.
            return with(select, Clause.With(with, null), select.scope.filterKeys { it !in select.bound }, apart = true) to kept
        }

        /**
         * [expression] with each variable [replacement] gives a replacement for replaced, as [replaced] replaces, and in a
         * comprehension's, a quantifier's and a reduce's own expressions too: [replacement] is given the names bound there.
         */
        private fun scoped(expression: Expression, local: Set<String> = emptySet(), replacement: (Expression.Variable, Set<String>) -> Expression?): Expression =
            replaced(expression) { e ->
                fun r(part: Expression, names: Set<String> = local) = scoped(part, names, replacement)
                when (e) {
                    is Expression.Variable -> replacement(e, local)
                    is Expression.ListComprehension -> e.copy(list = r(e.list), where = e.where?.let { r(it, local + e.variable) }, projection = e.projection?.let { r(it, local + e.variable) })
                    is Expression.Quantifier -> e.copy(list = r(e.list), where = e.where?.let { r(it, local + e.variable) })
                    is Expression.Reduce -> e.copy(initial = r(e.initial), list = r(e.list), expression = r(e.expression, local + e.accumulator + e.variable))
                    else -> null
                }
            }

        /**
         * Whether [shared] binds a value in [expression], compiled over the rows [select] reads. Compiled only to see:
         * the names it takes and the functions it declares (a `reduce`'s on Oracle) are given back, so a statement whose
         * aggregates bind nothing compiles as it did.
         */
        private fun binds(expression: Expression, select: Select): Boolean = binds { expression(expression, select.scope) }

        /**
         * Whether [shared] would bind a value in [term], over an aggregate's result, where [term] is no aggregate itself:
         * each aggregate in it compiled as it is, with nothing bound ([aggregating]), and the operations over them with
         * [shared] on. Compiled only to see, as [binds] is. An order term reads each item it names, in [named], by its name:
         * the item's value, compiled over the rows [select] reads as `project` compiles it, is bound to that name, so a comprehension's own name hides
         * it as it hides any variable, and the item's expression is never read where a comprehension binds its variables.
         */
        private fun bindsOverAggregates(term: Expression, select: Select, named: Map<String, Expression> = emptyMap()): Boolean {
            if (isAggregate(term) || !contains(term, ::isAggregate)) return false
            return binds {
                // Each item compiled as `project` compiles it, with shared on where it does not aggregate, lest one nested deep be
                // written out again at each level; what it binds is its own, not the term's.
                val items = named.filterKeys { it in variablesIn(term) }.mapValues { (_, item) ->
                    val was = sharedBindings
                    aggregating(item) { expression(item, select.scope) }.also { sharedBindings = was }
                }
                val results = mutableMapOf<String, Value>()
                // Named as no variable is, unless quoted so.
                val over = replaced(term) { part ->
                    if (!isAggregate(part)) null else Expression.Variable("#over${results.size + 1}").also { results[it.name] = unshared { expression(part, select.scope) } }
                }
                expression(over, select.scope + items + results)
            }
        }

        /** Whether [shared] binds a value in what [work] compiles, the names it takes and the functions it declares given back. */
        private fun binds(work: () -> Unit): Boolean {
            val (wasCounter, wasShares, wasBindings) = Triple(counter, shares, sharedBindings)
            val wasFunctions = functions.size
            try {
                work()
                return sharedBindings > wasBindings
            } finally {
                counter = wasCounter
                shares = wasShares
                sharedBindings = wasBindings
                functions.subList(wasFunctions, functions.size).clear()
            }
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
         * Where a projection holds `collect(DISTINCT …)`, or reads an aggregate item by item (a comprehension, a quantifier
         * or a reduce over `collect(…)`, `IN collect(…)`, a slice of it…), the rows [select] reads grouped first, in a `WITH` of their own:
         * each item that does not aggregate a key of its own; each variable an aggregating item names beside its aggregates,
         * in a body, a pattern or a subquery it holds too, and each key an aggregating order term names; and each aggregate,
         * the items' and the order terms', `collect(DISTINCT …)` collected without its `DISTINCT`. Then the projection, over
         * those, keeps each item of such a collection once, where it is first, as a graph engine does: by its id for a node
         * or a relationship, not its properties. An order term, or a part of one, that is an item is read by the item's name.
         * A store dedupes no aggregate in the query that makes it, and Oracle's `LISTAGG`, which does, gives at most 4,000
         * bytes; and Oracle reads no aggregate of the query it is in from a subquery, which reading a list item by item is.
         * So too where an operation over an aggregate's result would bind a value ([bindsOverAggregates]): in the query
         * that aggregates, nothing is bound ([aggregating]), so `max(n.title) + ': ' + max(n.first) + ' ' + max(n.last)`
         * wrote each aggregate out again at every level, to 68 MB on Postgres, and ran the compiler out of memory on Oracle
         * (#373); over the aggregates' columns, the operation binds its values as any item does. So too where an item that
         * aggregates reads a key that is a property ([readsOutsideAggregates]), as `r.role + count(*)` beside `r.role`:
         * grouped in place, it would be grouped by the variable `r` it names, a row for each relationship, where Neo4j gives
         * one for each role (#491); over the keys' columns, it reads the key's. Null where the projection does none of these.
         */
        private fun aggregatesFirst(select: Select, projection: Projection, items: List<Projection.Item>): Pair<Select, Projection>? {
            fun collectsDistinct(expression: Expression) = expression is Expression.Function && expression.distinct && expression.name.lowercase() == "collect"
            fun iteratesAggregate(expression: Expression) = iterated(expression).any { contains(it, ::isAggregate) }
            val terms = items.map { it.expression } + projection.orderBy.map { it.expression }
            // An order term reads an item by its name.
            val byName = items.associate { it.name to it.expression }
            // A key that is a property, read by an item that aggregates: grouped by as itself, not by its variable.
            val properties = items.map { it.expression }.filter { it is Expression.Property && !contains(it, ::isAggregate) }.toSet()
            if (terms.none { contains(it, ::collectsDistinct) || contains(it, ::iteratesAggregate) } &&
                items.none { bindsOverAggregates(it.expression, select) } &&
                projection.orderBy.none { bindsOverAggregates(it.expression, select, byName) } &&
                items.none { contains(it.expression, ::isAggregate) && readsOutsideAggregates(it.expression, properties) }
            ) return null
            // Named as no variable is, unless quoted so.
            val keys = items.filter { !contains(it.expression, ::isAggregate) }.associate { it.expression to "#group${++counter}" }
            val aggregates = linkedMapOf<Expression, String>()
            terms.forEach { term -> contains(term) { if (isAggregate(it)) aggregates.getOrPut(it) { "#aggregate${++counter}" }; false } }
            // A variable a comprehension's, a quantifier's or a reduce's body names outside its own is kept, but where it is read
            // only in a key that `rekeyed` reads by its name; so is a pattern's or a subquery's. A subquery may name any variable:
            // each the projection groups by is kept.
            fun named(expression: Expression): Set<String> = if (isAggregate(expression)) emptySet() else variablesOutsideAggregates(expression) + when (expression) {
                is Expression.ListComprehension -> listOfNotNull(expression.where, expression.projection).flatMap(::named) - expression.variable
                is Expression.Quantifier -> listOfNotNull(expression.where).flatMap(::named) - expression.variable
                is Expression.Reduce -> named(expression.expression) - expression.accumulator - expression.variable
                is Expression.PatternComprehension, is Expression.PatternPredicate, is Expression.Exists -> variablesIn(expression)
                is Expression.Subquery -> keys.keys.filterIsInstance<Expression.Variable>().map { it.name }
                else -> emptySet()
            } + children(expression).flatMap(::named)
            fun kept(term: Expression) = keys[term]?.let(Expression::Variable) ?: aggregates[term]?.let { name ->
                if (collectsDistinct(term)) Expression.Function(DISTINCT_ITEMS, false, listOf(Expression.Variable(name))) else Expression.Variable(name)
            }
            // A list comprehension's WHERE reads a key by its name too, where it binds no variable the key reads. In its projection,
            // a quantifier's WHERE or a reduce's step, Neo4j groups by each variable the key reads as well, so the body is kept as it
            // is, and those variables beside: `[t IN collect(m.title) | r.role]` beside `r.role` gives a row for each `r`.
            fun rekeyed(term: Expression, bound: Set<String> = emptySet()): Expression = replaced(term) { part ->
                when {
                    (bound.isEmpty() || part in keys) && variablesIn(part).none { it in bound } && kept(part) != null -> kept(part)
                    part is Expression.ListComprehension && part.where != null ->
                        part.copy(list = rekeyed(part.list, bound), where = rekeyed(part.where, bound + part.variable))
                    else -> null
                }
            }
            val rekeyedItems = items.map { it.copy(expression = rekeyed(it.expression)) }
            // An order term that aggregates may name a key too, by the variable the projection groups by.
            val keyed = keys.keys.filterIsInstance<Expression.Variable>().map { it.name }
            val beside = (items.zip(rekeyedItems).filter { (item, _) -> contains(item.expression, ::isAggregate) }.flatMap { (_, item) -> named(item.expression) } +
                projection.orderBy.filter { contains(it.expression, ::isAggregate) }.flatMap { named(it.expression) }.filter { it in keyed })
                .distinct().filter { it in select.scope }
            val grouped = Projection(
                distinct = false,
                star = false,
                items = keys.map { (expression, name) -> Projection.Item(expression, name) } + beside.map { Projection.Item(Expression.Variable(it), it) } +
                    aggregates.map { (aggregate, name) -> Projection.Item(if (collectsDistinct(aggregate)) (aggregate as Expression.Function).copy(distinct = false) else aggregate, name) },
                orderBy = emptyList(),
                skip = null,
                limit = null,
            )
            // An order term, or a part of one, that is an item is read by the item's name, as over a DISTINCT projection it can only be;
            // a variable an item is named after is that item, not the variable of that name before it.
            val itemNames = items.associate { it.expression to it.name }
            val kept = projection.copy(
                star = false,
                items = rekeyedItems,
                orderBy = projection.orderBy.map { sort ->
                    sort.copy(
                        expression = replaced(sort.expression) { term ->
                            if (term is Expression.Variable && term.name in byName) term else itemNames[term]?.let(Expression::Variable) ?: kept(term)
                        },
                    )
                },
            )
            return with(select, Clause.With(grouped, null)) to kept
        }

        /**
         * The order of the rows the projection being compiled reads, which `collect` gathers them in, as Cypher
         * gathers them: a group's rows are not read in the order of the query they come from.
         */
        private var rowOrder: String? = null

        /** [work], with no value bound in a subquery where [expression] aggregates: see [sharing]. */
        private fun <T> aggregating(expression: Expression, work: () -> T): T =
            if (contains(expression, ::isAggregate)) unshared(work) else work()

        /** [work], with no value bound in a subquery: see [sharing]. */
        private fun <T> unshared(work: () -> T): T {
            val was = sharing
            sharing = false
            try {
                return work()
            } finally {
                sharing = was
            }
        }

        /**
         * Refuses what Cypher refuses (Neo4j's 42I18): an item that aggregates and names a variable of [scope] outside its
         * aggregates and each key it reads, in a comprehension's, a quantifier's, a reduce's or a pattern's body too, which a
         * graph engine would have to group by without being told. A key is an item that does not aggregate, and Neo4j reads
         * one there only where it is a variable or a variable's property ([isPlainKey]): `x.a + count(*)` beside `x.a`, but
         * not `toUpper(p.name) + count(*)` beside `toUpper(p.name)`; in a pattern's parts, only where it is a variable. So too
         * a term of [orderBy] that aggregates, beside items that aggregate, and names a variable of [scope] outside an item's
         * name and an item that does not aggregate, whatever that item is, as Neo4j takes them.
         */
        private fun requireExplicitGrouping(items: List<Projection.Item>, orderBy: List<Projection.SortItem>, scope: Set<String>) {
            val grouping = items.filterNot { contains(it.expression, ::isAggregate) }
            val grouped = grouping.map { it.expression }.toSet()
            val keys = grouped.filter(::isPlainKey).toSet()
            items.filter { contains(it.expression, ::isAggregate) }.forEach { item ->
                val implicit = namedOutside(item.expression, emptySet(), scope, keys) - keys.filterIsInstance<Expression.Variable>().map { it.name }.toSet()
                if (implicit.isNotEmpty()) {
                    throw DrivineException(
                        "${item.name} aggregates, and names ${implicit.joinToString()} outside its aggregates, which no other item is: " +
                            "Cypher would group by it without being told. Aggregate in a WITH first, " +
                            "as in WITH ${implicit.joinToString()}, collect(…) AS items RETURN { … }."
                    )
                }
            }
            if (grouping.size == items.size) return
            val names = items.map { it.name }.toSet()
            orderBy.filter { contains(it.expression, ::isAggregate) }.forEach { sort ->
                // Not less the variables an item is: one an item renames, named in an order term's pattern, is a new variable of the
                // pattern's to Neo4j, which matches any node, so it is refused rather than read as the key.
                val implicit = namedOutside(sort.expression, names, scope, grouped)
                if (implicit.isNotEmpty()) {
                    throw DrivineException(
                        "A term of an ORDER BY aggregates, and names ${implicit.joinToString()} outside its aggregates and the items: " +
                            "Cypher would group by it without being told. Order by an item's name, or by an expression of the items and aggregates."
                    )
                }
            }
        }

        /**
         * The variables of [scope] that [expression] names outside its aggregates, and outside each part of it [keys] holds,
         * a comprehension's, a quantifier's, a reduce's or a pattern's body among them, but for those it binds or [local]
         * holds. A pattern binds each of its variables not of [scope], and in its parts, as Neo4j reads them, a key is only one
         * that is a variable: `q.name = p.name` in a pattern comprehension's WHERE names `p`, though `p.name` is a key.
         * A subquery's own query is read for none.
         */
        private fun namedOutside(expression: Expression, local: Set<String>, scope: Set<String>, keys: Set<Expression>): Set<String> {
            fun outside(part: Expression, inner: Set<String> = local) = namedOutside(part, inner, scope, keys)
            fun patterned(pattern: Pattern, parts: List<Expression>): Set<String> {
                val named = variablesIn(pattern) + listOfNotNull(pattern.variable)
                val bound = local + (named - scope)
                val variables = keys.filterIsInstance<Expression.Variable>().toSet<Expression>()
                return (named intersect scope) - local + parts.flatMap { namedOutside(it, bound, scope, variables) }
            }
            return when {
                isAggregate(expression) || expression in keys -> emptySet()
                expression is Expression.Variable -> if (expression.name in local || expression.name !in scope) emptySet() else setOf(expression.name)
                // A map projection of a key reads the key, as Neo4j reads it.
                expression is Expression.MapProjection -> (
                    if (expression.variable in local || expression.variable !in scope || Expression.Variable(expression.variable) in keys) emptySet()
                    else setOf(expression.variable)
                    ) +
                    children(expression).flatMap { outside(it) }
                expression is Expression.ListComprehension -> outside(expression.list) +
                    listOfNotNull(expression.where, expression.projection).flatMap { outside(it, local + expression.variable) }
                expression is Expression.Quantifier -> outside(expression.list) + listOfNotNull(expression.where).flatMap { outside(it, local + expression.variable) }
                expression is Expression.Reduce -> outside(expression.initial) + outside(expression.list) +
                    outside(expression.expression, local + expression.accumulator + expression.variable)
                expression is Expression.PatternComprehension -> patterned(expression.pattern, listOfNotNull(expression.where, expression.projection))
                expression is Expression.PatternPredicate -> patterned(expression.pattern, emptyList())
                expression is Expression.Exists -> expression.patterns.flatMap { patterned(it, listOfNotNull(expression.where)) }.toSet()
                else -> children(expression).flatMap { outside(it) }.toSet()
            }
        }

        /** A `SKIP` or `LIMIT`: a whole number, or a parameter bound to one. */
        private fun count(expression: Expression): String = when (expression) {
            is Expression.Literal -> (expression.value as? Long)?.toString() ?: throw DrivineException("SKIP and LIMIT take a whole number, not ${expression.value}.")
            is Expression.Parameter -> parameters.count(expression.name)
            else -> unsupported("SKIP or LIMIT of an expression")
        }

        // ----- Expressions -----

        /**
         * [where], a condition that keeps the rows it is true of, as SQL: within it, a comparison whose truth is all a `WHERE` reads
         * (one of its conditions joined by `AND` or `OR`) may be false where Cypher's is null, as neither keeps a row.
         */
        fun condition(where: Expression, scope: Map<String, Value>): String {
            filtering = true
            return try {
                bool(expression(where, scope))
            } finally {
                filtering = false
            }
        }

        /** Whether the expression being compiled is read by a `WHERE` for its truth alone: see [condition]. */
        private var filtering = false

        /** Whether the expression [evaluate] has begun is read by a `WHERE` for its truth alone, as [filtering] said as it began. */
        private var filtered = false

        /**
         * The value [expression] gives in [scope]: see [evaluate]. Marked [Value.Json.flat] where it is known to hold no null in a list or a map,
         * and with its [Value.Json.known] kind where the statement writes it as a value of one.
         */
        fun expression(expression: Expression, scope: Map<String, Value>): Value {
            val outer = filtering
            // Only a condition joined by AND or OR is read for its truth alone: NOT, a CASE or a function reads null apart from false.
            filtering = outer && expression is Expression.Binary && (expression.operator == Operator.AND || expression.operator == Operator.OR)
            filtered = outer
            val evaluated = try {
                evaluate(expression, scope)
            } finally {
                filtering = outer
            }
            val value = if (evaluated is Value.Json && evaluated.known == null) knownKind(expression, scope)?.let { evaluated.copy(known = it) } ?: evaluated else evaluated
            return when {
                value is Value.Json && !value.flat && flat(expression, scope) -> value.copy(flat = true)
                value is Value.Entities && !value.flat && flat(expression, scope) -> value.copy(flat = true)
                else -> value
            }
        }

        private fun evaluate(expression: Expression, scope: Map<String, Value>): Value = when (expression) {
            is Expression.Literal -> Value.Json(literalJson(expression.value), text = expression.value is String)
            is Expression.Parameter ->
                givenParameters?.get(expression.name) ?: Value.Json(parameters.json(expression.name), temporal = Temporal.ofValue(parameters.value(expression.name)))
            is Expression.Variable -> scope[expression.name] ?: throw DrivineException("${expression.name} is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.")
            is Expression.Property -> inParameter(expression)?.let { Value.Json(it) } ?: property(expression(expression.subject, scope), expression.key)
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
                    else -> Value.Json(
                        jsonArray(items.map { json(it).sql }),
                        ordered = true,
                        items = kindOf(expression.items.zip(items)),
                        identity = identityHolding(items) { jsonArray(it) },
                        itemItems = itemKindOf(expression.items.zip(items)),
                        temporals = items.any(::hasTemporals),
                        list = true,
                        itemLists = entities.isNotEmpty() && entities.all(::isList),
                        texts = items.all(::isText),
                    )
                }
            }
            is Expression.MapLiteral -> {
                val entries = expression.entries.map { (key, value) -> key to expression(value, scope) }
                Value.Json(
                    jsonObject(entries.map { (key, value) -> key to json(value).sql }),
                    ordered = true,
                    identity = identityHolding(entries.map { it.second }) { ids -> jsonObject(entries.map { it.first }.zip(ids)) },
                    temporals = entries.any { hasTemporals(it.second) },
                )
            }
            is Expression.MapProjection -> mapProjection(expression, scope)
            is Expression.Binary -> binary(expression, scope)
            is Expression.Unary -> unary(expression, scope)
            is Expression.Index -> {
                indexOfKind(expression, scope)
                val index = expression.index
                val listed by lazy { expression(expression.subject, scope) }
                val subject = { comparable(json(listed)) }
                val at = comparable(json(expression(index, scope)))
                val known = known(index)
                when {
                    known is String -> inParameter(expression)?.let { Value.Json(it) } ?: property(expression(expression.subject, scope), known)
                    // An item of a list of nodes or relationships is the node or the relationship, so it is compared by its id;
                    // of one collect gathers in this query, its properties, for a store reads no aggregate from a subquery. A
                    // pattern comprehension gathers its properties too, but in a subquery of its own, so its item is read by id.
                    listed is Value.Entities && !contains(expression.subject, ::isAggregate) -> entityAt(listed as Value.Entities, at)
                    // An item of a list that holds a node or a relationship holds it by identity too: see [Value.Json.identity].
                    known is Number -> element(subject(), at).let { item ->
                        Value.Json(
                            item, temporal = itemsOf(listed), items = itemItemsOf(listed), identity = identityOf(listed, item, true) { element(it, at) },
                            list = holdsLists(listed), text = holdsTexts(listed), temporals = itemTemporalsOf(listed),
                        )
                    }
                    else -> {
                        fun item(list: String) = shared(list, at) { (map, key) ->
                            // A store that reads no key an expression gives reads a list's item alone.
                            val keyed = keyed(map, key)?.let { " WHEN 'string' THEN $it" }.orEmpty()
                            "CASE ${typeOf(key)} WHEN 'number' THEN ${element(map, key)}$keyed END"
                        }
                        item(subject()).let { taken ->
                            Value.Json(
                                taken, temporal = itemsOf(listed), items = itemItemsOf(listed), identity = identityOf(listed, taken, true, ::item),
                                list = holdsLists(listed), text = holdsTexts(listed), temporals = itemTemporalsOf(listed),
                            )
                        }
                    }
                }
            }
            is Expression.Slice -> slice(expression, scope)
            is Expression.Function -> function(expression, scope)
            Expression.CountStar -> Value.Json(scalar("count(*)"))
            is Expression.Case -> case(expression, scope)
            is Expression.ListComprehension -> when (val listed = expression(expression.list, scope)) {
                is Value.Entities -> entityComprehension(expression, listed, scope)
                else -> listComprehension(expression, listed, scope)
            }
            is Expression.Reduce -> {
                val start = expression(expression.initial, scope)
                val initial = comparable(json(start))
                val listed = expression(expression.list, scope)
                val list = comparable(json(listed))
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
                            val inner = scope + mapOf(expression.accumulator to Value.Json(accumulator), expression.variable to Value.Json(item, temporal = itemsOf(listed), temporals = itemTemporalsOf(listed)))
                            comparable(json(expression(expression.expression, inner)))
                        },
                        context = context,
                        contextStep = { accumulator, item, map ->
                            // A value read from the map is of the kind the statement made it.
                            val inner = read.associateWith { (scope.getValue(it) as? Value.Json).let { v -> Value.Json(propertyOf(map, it), temporal = v?.temporal, items = v?.items, known = v?.known) } } +
                                mapOf(expression.accumulator to Value.Json(accumulator), expression.variable to Value.Json(item, temporal = itemsOf(listed), temporals = itemTemporalsOf(listed)))
                            val was = givenParameters
                            givenParameters = given.associateWith { Value.Json(propertyOf(map, "$$it"), temporal = Temporal.ofValue(parameters.value(it))) }
                            try {
                                comparable(json(expression(expression.expression, inner)))
                            } finally {
                                givenParameters = was
                            }
                        },
                    ),
                    // It may hold what its start, its list or a value of the row its step reads holds (#347).
                    temporals = hasTemporals(start) || hasTemporals(listed) || read.any { hasTemporals(scope.getValue(it)) },
                )
            }
            is Expression.PatternComprehension -> {
                val inner = Select(scope)
                patterns(inner, listOf(expression.pattern))
                expression.where?.let { inner.where += condition(it, inner.scope) }
                val projected = expression(expression.projection, inner.scope)
                // A body that reads no variable of the pattern's own, `[(p)-->() | p.name]`, is worked out for each of the pattern's
                // rows in a derived table, and gathered from there: an aggregate that reads only the row's columns is an aggregate of
                // the row's query, which Postgres refuses and SQLite works out over every row, all of them in one (#467). One that
                // reads nothing of any row, a literal or a parameter, is the comprehension's aggregate as it is.
                val own = variablesIn(expression.pattern) + listOfNotNull(expression.pattern.variable) - scope.keys
                val read = variablesReadIn(expression.projection)
                val apart = read.none { it in own } && (read.isNotEmpty() || holdsSubquery(expression.projection))
                fun gathered(value: String): String {
                    fun rows(select: String) = (listOf(select) + inner.from() + listOfNotNull(inner.whereClause())).joinToString(" ")
                    if (!apart) return "(${rows("SELECT ${aggregateList(value)}")})"
                    val each = alias("pc")
                    return "(SELECT ${aggregateList("$each.v")} FROM (${rows("SELECT $value AS v")}) $each)"
                }
                // A comprehension of nodes or relationships is a list of them, so a write to an item of it reaches them.
                when (projected) {
                    is Value.Node -> Value.Entities(comparable(Value.Json(gathered(scalar(projected.nid)), ordered = true)), false, gathered(json(projected).sql))
                    is Value.Relationship -> Value.Entities(comparable(Value.Json(gathered(scalar(projected.eid)), ordered = true)), true, gathered(json(projected).sql))
                    else -> Value.Json(
                        gathered(json(projected).sql), ordered = true, items = (projected as? Value.Json)?.temporal, itemItems = itemsOf(projected), list = true,
                        itemLists = isList(projected), temporals = hasTemporals(projected),
                    )
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
                // Read by a WHERE for its truth alone, a quantifier that is null may be false, as neither keeps a row.
                val filter = filtered
                val listed = expression(expression.list, scope)
                val name = alias("q")
                fun quantified(from: String, item: Value, listNull: String): String {
                    val inner = scope + (expression.variable to item)
                    fun exists(condition: String) = "EXISTS (SELECT 1 $from WHERE $condition)"
                    val kind = expression.kind
                    val value = if (filter && kind != Expression.Quantifier.Kind.SINGLE) {
                        // any() is true where an item's condition is true, and all() where each one's is: each is kept where its condition
                        // is true, as a WHERE keeps a row, so an IN in it is false where it is null. none() is true where each one's is false.
                        val condition = expression.where?.let { if (kind == Expression.Quantifier.Kind.NONE) bool(expression(it, inner)) else condition(it, inner) } ?: "TRUE"
                        when (kind) {
                            Expression.Quantifier.Kind.ANY -> exists(condition)
                            Expression.Quantifier.Kind.ALL -> "NOT ${exists("CASE WHEN $condition THEN 0 ELSE 1 END = 1")}"
                            else -> "NOT ${exists("CASE WHEN NOT ($condition) THEN 0 ELSE 1 END = 1")}"
                        }
                    } else {
                        // Cypher's quantifiers are three-valued: an item whose condition is null decides nothing, and leaves null one no
                        // item decides. Each item's is read once, in one pass, and counted: 1 is true, 0 false, and null null.
                        val condition = expression.where?.let { bool(expression(it, inner)) } ?: "TRUE"
                        val counts = alias("q")
                        val (t, f, u) = listOf("$counts.v", "NOT $counts.v", "$counts.v IS NULL").map { "count(CASE WHEN $it THEN 1 END)" }
                        val counted = when (kind) {
                            Expression.Quantifier.Kind.ANY -> "WHEN $t > 0 THEN 1 WHEN $u > 0 THEN NULL ELSE 0"
                            Expression.Quantifier.Kind.ALL -> "WHEN $f > 0 THEN 0 WHEN $u > 0 THEN NULL ELSE 1"
                            Expression.Quantifier.Kind.NONE -> "WHEN $t > 0 THEN 0 WHEN $u > 0 THEN NULL ELSE 1"
                            // Two items whose condition is true make single() false, whatever the others' are.
                            Expression.Quantifier.Kind.SINGLE -> "WHEN $t > 1 THEN 0 WHEN $u > 0 THEN NULL WHEN $t = 1 THEN 1 ELSE 0"
                        }
                        "(SELECT CASE $counted END FROM (SELECT $condition AS v $from) $counts) = 1"
                    }
                    // Of a null list, null, as in Cypher, and its items are not read, where it may be one.
                    return if (present(expression.list)) value else "CASE WHEN NOT ($listNull) THEN $value END"
                }
                Value.Bool(
                    // Over a list of nodes or relationships, each item is the node or the relationship, read from its table.
                    if (listed is Value.Entities) shared(listed.ids) { (ids) -> entityItems(ids, listed.relationships, name).let { (from, item) -> quantified(from, item, "$ids IS NULL") } }
                    else shared(comparable(json(listed))) { (items) -> quantified("FROM ${elementsOf(listed, items, name)}", Value.Json(item(name), temporal = itemsOf(listed), temporals = itemTemporalsOf(listed)), isJsonNull(items)) }
                )
            }
        }

        /**
         * `list[from..to]`: the items from the one at `from` up to the one before `to`, each counted from the end where it is
         * negative, a bound left out the list's end; null where the list or a bound given is. A slice of nodes or
         * relationships is a list of them.
         */
        private fun slice(slice: Expression.Slice, scope: Map<String, Value>): Value {
            val listed = expression(slice.subject, scope)
            val bounds = listOfNotNull(slice.from, slice.to).map { comparable(json(expression(it, scope))) }
            val list = if (listed is Value.Entities) listed.ids else comparable(json(listed))
            fun sliced(list: String) = shared(list, *bounds.toTypedArray()) { values ->
                val x = values[0]
                var i = 1
                val from = slice.from?.let { values[i++] }
                val to = slice.to?.let { values[i] }
                val e = alias("e")
                val v = alias("v")
                // A bound counted from the end where it is negative, as an index is: past either end, the end it is past.
                fun bound(json: String) = "CASE WHEN ${number(json)} < 0 THEN $v.cnt + ${number(json)} ELSE ${number(json)} END"
                val within = listOfNotNull(from?.let { "$v.item_no > ${bound(it)}" }, to?.let { "$v.item_no <= ${bound(it)}" })
                val items = "SELECT ${item(e)} AS item, ${itemNo(e)} AS item_no, COUNT(*) OVER () AS cnt FROM ${elementsOf(listed, x, e)}"
                val query = "SELECT ${comparable(Value.Json(aggregateList("$v.item", "$v.item_no"), ordered = true))} FROM ($items) $v" +
                    within.joinToString(" AND ", " WHERE ")
                val present = (listOf("${typeOf(x)} = 'array'") + listOfNotNull(from, to).map { "NOT ${isJsonNull(it)}" }).joinToString(" AND ")
                "CASE WHEN $present THEN ($query) END"
            }
            // A slice of a list that holds a node or a relationship holds it by identity too: see [Value.Json.identity].
            return if (listed is Value.Entities) Value.Entities(sliced(list), listed.relationships)
            else sliced(list).let { taken ->
                Value.Json(
                    taken, items = itemsOf(listed), identity = identityOf(listed, taken, false, ::sliced), itemItems = itemItemsOf(listed), list = true,
                    itemLists = holdsLists(listed), texts = holdsTexts(listed), temporals = holdsTemporals(listed),
                )
            }
        }

        private fun exists(patterns: List<Pattern>, where: Expression?, scope: Map<String, Value>): Value.Bool {
            val inner = Select(scope)
            patterns(inner, patterns)
            where?.let { inner.where += condition(it, inner.scope) }
            return Value.Bool("EXISTS (${(listOf("SELECT 1") + inner.from() + listOfNotNull(inner.whereClause())).joinToString(" ")})")
        }

        /** An index written, or bound to a parameter, is known: a text is the property of that name, a number an item. */
        private fun known(index: Expression): Any? = when (index) {
            is Expression.Literal -> index.value
            is Expression.Parameter -> parameters.value(index.name)
            // `-1` is a negated number.
            is Expression.Unary -> (index.operand as? Expression.Literal)?.value?.takeIf { index.operator == Expression.Unary.Operator.NEGATE && it is Number }
            else -> null
        }

        /**
         * The kind of value [expression] is in [scope], where the statement writes it as one ([Value.Json.known]): a literal, a
         * negated number, a list or a map written, or a variable bound to one. Read from the statement alone, compiling nothing,
         * so a check may run before its operand is compiled. Null where the kind is left open, as it is for a property, a
         * parameter or a function's value: Neo4j checks those when the row is read.
         */
        private fun knownKind(expression: Expression, scope: Map<String, Value>): Value.Known? = when (expression) {
            is Expression.Literal -> Value.Known.of(expression.value)
            is Expression.Unary -> knownKind(expression.operand, scope)
                ?.takeIf { expression.operator == Expression.Unary.Operator.NEGATE && (it == Value.Known.INTEGER || it == Value.Known.FLOAT) }
            is Expression.ListLiteral -> Value.Known.LIST
            is Expression.MapLiteral -> Value.Known.MAP
            is Expression.Variable -> (scope[expression.name] as? Value.Json)?.known
            else -> null
        }

        /**
         * Refuses [index], `subject[index]`, where the statement writes its subject as a value that is no list or map, or its
         * index as a value of a kind its subject is not indexed by ([knownKind]): a list by an integer, a map by a text. As
         * Neo4j refuses it; where the subject's kind is left open, it is compiled as before. An index given as a parameter is of
         * its value's kind ([known]): the statement is compiled for each call, and its SQL is already made by that value, a
         * text read as a property and a number as an item, so one of the other kind would be read as neither.
         */
        private fun indexOfKind(index: Expression.Index, scope: Map<String, Value>) {
            val subject = knownKind(index.subject, scope) ?: return
            val by = when (subject) {
                Value.Known.LIST -> Value.Known.INTEGER
                Value.Known.MAP -> Value.Known.TEXT
                else -> throw DrivineException("${subject.noun.replaceFirstChar(Char::uppercase)} is not a list or a map, and is indexed as one, as in Neo4j.")
            }
            val kind = knownKind(index.index, scope) ?: (index.index as? Expression.Parameter)?.let { Value.Known.of(known(it)) } ?: return
            if (kind != by) throw DrivineException("${subject.noun.replaceFirstChar(Char::uppercase)} is indexed by ${by.noun}, not ${kind.noun}, as in Neo4j.")
        }

        /** Refuses [expression], an operand of [operator], where its kind is known ([knownKind]) to be other than a truth value, as Neo4j refuses it. */
        private fun requireCondition(expression: Expression, scope: Map<String, Value>, operator: String) {
            val kind = knownKind(expression, scope) ?: return
            if (kind != Value.Known.BOOLEAN) throw DrivineException("$operator takes a truth value, not ${kind.noun}, as in Neo4j.")
        }

        /**
         * A value in a parameter that is a map, `${'$'}p.text`, `${'$'}p.a.b` or `${'$'}p['a'].b`, each key a property or an index
         * [known] as a text, read at once from the text the parameter is bound as, where the store reads it so (see
         * [Parameters.json]). Oracle crashed the session where a statement took a parameter past 32 KB as JSON, then the
         * value from it, for each of two rows (#256).
         */
        private fun inParameter(value: Expression): String? {
            val keys = mutableListOf<String>()
            var subject: Expression = value
            while (true) {
                val (key, next) = when (subject) {
                    is Expression.Property -> subject.key to subject.subject
                    is Expression.Index -> (known(subject.index) as? String ?: break) to subject.subject
                    else -> break
                }
                keys.add(0, key)
                subject = next
            }
            if (subject !is Expression.Parameter || givenParameters?.containsKey(subject.name) == true) return null
            return parameters.json(subject.name, keys)
        }

        /**
         * The item [expression] of a `RETURN` or a `WITH` over [select]: a property of a node or a relationship, the item
         * itself, read as [itemProperty] reads it, and a property of an unwound item ([Value.Json.fromList]) as [itemValue] reads
         * it, but beside a variable-length walk; anything else as elsewhere. A node or a
         * relationship is compiled once here: compiling the item over it again lost the rows of statements that match a
         * relationship.
         */
        private fun item(expression: Expression, select: Select): Value {
            if (expression is Expression.Property && !select.walks) {
                // A property of a node or a relationship holds no null in a list, as expression() marks it (see flat).
                when (val subject = expression(expression.subject, select.scope)) {
                    is Value.Node -> if (expression.key != Stamps.PROPERTY) return Value.Json(itemProperty(subject.props, expression.key), flat = true)
                    is Value.Relationship -> return Value.Json(itemProperty(subject.props, expression.key), flat = true)
                    // A value of an unwound item, but a temporal value's field, and one a write reads from its row as rowValue does.
                    // It may hold temporal values deeper, as property() has it, so that a list of them is read by its places (#369).
                    // One of a kind known other than a map is left to property(), which refuses it.
                    is Value.Json -> if (subject.fromList && subject.temporal == null && subject.rowKeys == null && expression.key !in temporals.fieldNames &&
                        (subject.known == null || subject.known == Value.Known.MAP))
                        return Value.Json(itemValue(comparable(subject), expression.key), temporals = subject.temporals)
                    else -> {}
                }
            }
            return expression(expression, select.scope)
        }

        private fun property(subject: Value, key: String): Value = when (subject) {
            is Value.Entities -> throw DrivineException("A list has no properties.")
            is Value.Node -> Value.Json(if (key == Stamps.PROPERTY) scalar(subject.stamp) else propertyOf(subject.props, key))
            is Value.Relationship -> Value.Json(propertyOf(subject.props, key))
            // A value the statement writes as one of a kind other than a map has none, as Neo4j says when it plans the statement.
            is Value.Json -> subject.known?.takeIf { it != Value.Known.MAP }?.let { throw DrivineException("${it.noun.replaceFirstChar(Char::uppercase)} has no properties.") }
                ?: temporalField(subject, key) ?: subject.rowKeys?.let { keys -> Value.Json(rowValue(keys + key), rowKeys = keys + key) }
                ?: propertyOf(comparable(subject), key).let { taken ->
                    Value.Json(taken, identity = identityOf(subject, taken, true) { propertyOf(it, key) }, temporals = subject.temporals)
                }
            is Value.Bool -> throw DrivineException("A truth value has no properties.")
            is Value.Path -> throw DrivineException("A path has no properties.")
        }

        /**
         * The temporal kind the statement makes each of [values], as each is written and compiled, where it makes
         * every one of the same kind, a null written among them aside: a `CASE`, a `coalesce` or a list of dates is
         * a date, or a list of them. Null where they are of different kinds or of none.
         */
        private fun kindOf(values: List<Pair<Expression, Value>>): Temporal? =
            values.filter { (written, _) -> written != Expression.Literal(null) }.map { (_, value) -> (value as? Value.Json)?.temporal }.distinct().singleOrNull()

        /** Whether the statement makes [value] a list: see [Value.Json.list]. */
        private fun isList(value: Value): Boolean = value is Value.Entities || (value as? Value.Json)?.list == true

        /** Whether the statement makes [value] a list whose every item is a list: see [Value.Json.itemLists]. */
        private fun holdsLists(value: Value): Boolean = (value as? Value.Json)?.itemLists == true

        /** Whether the statement makes [value] a text, or null: see [Value.Json.text]. */
        private fun isText(value: Value): Boolean = (value as? Value.Json)?.text == true

        /** Whether the statement makes [value] a list whose every item is a text, or null: see [Value.Json.texts]. */
        private fun holdsTexts(value: Value): Boolean = (value as? Value.Json)?.texts == true

        /** The items of the JSON list [json], the value [list], as rows named [alias]: by [temporalElements] where it holds temporal values at any depth. */
        private fun elementsOf(list: Value, json: String, alias: String) = elements(json, alias, temporal = holdsTemporals(list))

        /**
         * Whether the statement makes the list or map [list] hold, at any depth, temporal values, as its items or deeper: see
         * [temporalElements]. A list of other values, or of lists of them, is read as [elements] reads it.
         */
        private fun holdsTemporals(list: Value) = itemsOf(list) != null || itemItemsOf(list) != null || (list as? Value.Json)?.temporals == true

        /** Whether the statement makes [value] a temporal value, or a list or a map that holds one at any depth: see [Value.Json.temporals]. */
        private fun hasTemporals(value: Value) = (value as? Value.Json)?.temporal != null || holdsTemporals(value)

        /** Whether an item taken from the list [list] may hold temporal values at any depth: not where it is of a temporal kind itself. */
        private fun itemTemporalsOf(list: Value) = (list as? Value.Json)?.let { it.temporals && it.items == null } == true

        /** The temporal kind the statement makes each item of the list [list]: see [Value.Json.items]. */
        private fun itemsOf(list: Value): Temporal? = (list as? Value.Json)?.items

        /** The temporal kind the statement makes each item of each item of the list [list]: see [Value.Json.itemItems]. */
        private fun itemItemsOf(list: Value): Temporal? = (list as? Value.Json)?.itemItems

        /** The temporal kind the statement makes each item of each of [values], lists all, as [kindOf] tells it of values. */
        private fun itemKindOf(values: List<Pair<Expression, Value>>): Temporal? = listKindOf(values, ::itemsOf)

        /**
         * The kind [of] gives each of [values], lists all, where it gives every one the same: a null written among them aside, and
         * an empty list written, which holds no item of another kind. Null where they are of different kinds or of none.
         */
        private fun listKindOf(values: List<Pair<Expression, Value>>, of: (Value) -> Temporal?): Temporal? =
            values.filter { (written, _) -> written != Expression.Literal(null) && written != Expression.ListLiteral(emptyList()) }
                .takeIf { listed -> listed.isNotEmpty() && listed.all { (_, value) -> isList(value) } }?.map { (_, value) -> of(value) }?.distinct()?.singleOrNull()

        /**
         * A field of a temporal value, `d.year` or `dur.days`: of the kind the statement makes it, or of the kind a
         * text's shape tells, where a map's is its entry of that name; null for a name no temporal value has. A map's entry
         * may hold what the map holds, as `{minute: l}.minute` does: see [Value.Json.temporals].
         */
        private fun temporalField(subject: Value.Json, key: String): Value? {
            if (key !in temporals.fieldNames) return null
            subject.temporal?.let { kind ->
                val field = temporals.field(text(comparable(subject)), kind, key) ?: return Value.Json(literalJson(null))
                return Value.Json(scalar(field.sql))
            }
            return Value.Json(shared(comparable(subject)) { (x) ->
                val kinds = Temporal.entries.filter { temporals.field("NULL", it, key) != null }
                val read = temporals.byKind(text(x), kinds) { kind, t -> scalar(temporals.field(t, kind, key)!!.sql) }
                "CASE WHEN ${typeOf(x)} = 'string' THEN $read ELSE ${propertyOf(x, key)} END"
            }, temporals = subject.temporals)
        }

        /**
         * Temporal arithmetic and comparison, where the statement makes either side of a temporal kind: a moment
         * and a duration added or taken away, two durations added, a duration scaled, two moments compared as the
         * moments they are. Null where neither side is of a temporal kind, or the operator is no such one.
         */
        private fun temporalBinary(operator: Operator, left: Value, right: Value): Value? {
            val l = (left as? Value.Json)?.temporal
            val r = (right as? Value.Json)?.temporal
            if (l == null && r == null) return null
            val (jsonA, jsonB) = comparable(json(left)) to comparable(json(right))
            val a = text(jsonA)
            val b = text(jsonB)
            fun of(text: String, kind: Temporal?) = Value.Json(scalar(text), temporal = kind)
            val duration = Temporal.DURATION
            return when (operator) {
                Operator.ADD, Operator.SUBTRACT -> {
                    val sign = if (operator == Operator.ADD) 1 else -1
                    // A side read from the store: [temporal] of the two texts, null where neither is of a kind it reads; then, in an
                    // addition, the two added as any two values are, as Cypher adds a temporal value to a list or to a text. Bound
                    // as a sum's sides are, so a stored text past 32 KB beside a long duration leaves Oracle's session open (#370).
                    fun read(kind: Temporal?, temporal: (String, String) -> String) = Value.Json(sharedSum(jsonA, jsonB) { (x, y) ->
                        val value = scalar(shared(text(x), text(y)) { (tx, ty) -> temporal(tx, ty) })
                        if (sign > 0) "COALESCE($value, ${add(x, y)})" else value
                    }, temporal = kind)
                    when {
                        l == duration && r == duration -> of(temporals.sum(a, b, sign), duration)
                        l != null && l.instant && r == duration -> of(temporals.add(a, l, b, sign), l)
                        l == duration && r != null && r.instant && sign > 0 -> of(temporals.add(b, r, a, 1), r)
                        // A value read from the store, its kind told by its text, and a duration the statement gives.
                        l == null && r == duration -> read(null) { x, y ->
                            "CASE WHEN ${temporals.isKind(x, duration)} THEN ${temporals.sum(x, y, sign)} ELSE ${temporals.addToAny(x, y, sign)} END"
                        }
                        l == duration && r == null && sign > 0 -> read(null) { x, y ->
                            "CASE WHEN ${temporals.isKind(y, duration)} THEN ${temporals.sum(x, y, 1)} ELSE ${temporals.addToAny(y, x, 1)} END"
                        }
                        // A duration read from the store, taken from a duration or added to a moment the statement gives. A sum is of
                        // no kind, for what is read may be a list or a text, which the moment is added to as any value is.
                        l == duration && r == null -> read(duration) { x, y -> "CASE WHEN ${temporals.isKind(y, duration)} THEN ${temporals.sum(x, y, sign)} END" }
                        l != null && l.instant && r == null ->
                            read(l.takeIf { sign < 0 }) { x, y -> "CASE WHEN ${temporals.isKind(y, duration)} THEN ${temporals.add(x, l, y, sign)} END" }
                        l == null && r != null && r.instant && sign > 0 ->
                            read(null) { x, y -> "CASE WHEN ${temporals.isKind(x, duration)} THEN ${temporals.add(y, r, x, 1)} END" }
                        else -> null
                    }
                }
                Operator.MULTIPLY -> when {
                    l == duration -> of(temporals.times(a, number(comparable(json(right)))), duration)
                    r == duration -> of(temporals.times(b, number(comparable(json(left)))), duration)
                    else -> null
                }
                Operator.DIVIDE -> if (l == duration) of(temporals.times(a, "(1.0 / ${number(comparable(json(right)))})"), duration) else null
                Operator.EQUALS, Operator.NOT_EQUALS, Operator.LESS_THAN, Operator.GREATER_THAN, Operator.LESS_THAN_OR_EQUAL, Operator.GREATER_THAN_OR_EQUAL -> {
                    val sql = mapOf(
                        Operator.EQUALS to "=", Operator.NOT_EQUALS to "<>", Operator.LESS_THAN to "<", Operator.GREATER_THAN to ">",
                        Operator.LESS_THAN_OR_EQUAL to "<=", Operator.GREATER_THAN_OR_EQUAL to ">=",
                    ).getValue(operator)
                    Value.Bool(temporals.compare(a, b, (l ?: r)!!, sql))
                }
                else -> null
            }
        }

        /** A temporal value of [kind] made by its function of [function]'s [arguments]: the moment, a text, or a map of its fields. */
        private fun temporal(kind: Temporal, function: Expression.Function, arguments: List<Value>, scope: Map<String, Value>): Value {
            fun of(text: String) = Value.Json(scalar(text), temporal = kind)
            if (arguments.isEmpty()) {
                if (kind == Temporal.DURATION) throw DrivineException("duration() takes a text or a map of its fields.")
                return of(temporals.now(kind))
            }
            if (arguments.size != 1) throw DrivineException("${function.name}() takes a text or a map of its fields.")
            val written = function.arguments.single()
            // A text the statement writes is worked out here, as the store would work it out.
            if (written is Expression.Literal && written.value is String) {
                val canonical = try {
                    Temporals.canonical(written.value as String, kind) ?: throw DrivineException("'${written.value}' is no ${function.name}.")
                } catch (e: java.time.DateTimeException) {
                    throw DrivineException("'${written.value}' is no ${function.name}: ${e.message}", e)
                } catch (e: NumberFormatException) {
                    throw DrivineException("'${written.value}' is no ${function.name}.", e)
                }
                return Value.Json(literalJson(canonical), temporal = kind)
            }
            if (written is Expression.MapLiteral) {
                val fields = written.entries.associate { (key, value) -> key to expression(value, scope) }
                val dated = kind == Temporal.DATE || kind == Temporal.LOCAL_DATE_TIME || kind == Temporal.DATE_TIME
                val known = if (kind == Temporal.DURATION) DURATION_MAP_FIELDS else if (dated) INSTANT_MAP_FIELDS + Temporals.CALENDAR_FIELDS else INSTANT_MAP_FIELDS
                // A time of day has no date to give, as Neo4j has it.
                if (kind.instant && !dated) fields.keys.firstOrNull { it in Temporals.CALENDAR_FIELDS }?.let { throw DrivineException("${function.name}() takes no field $it.") }
                fields.keys.firstOrNull { it !in known }?.let { unsupported("${function.name}() of a map with $it") }
                val numbers = fields.filterKeys { it != "timezone" }.mapValues { number(comparable(json(it.value))) }
                if (kind == Temporal.DURATION) return of(temporals.duration(numbers))
                val timezone = fields["timezone"]?.let { text(comparable(json(it))) }
                if (fields.keys.any { it in Temporals.CALENDAR_FIELDS }) {
                    // A zone by name, which no store's text holds yet, is refused rather than given as null.
                    val zone = (written.entries.firstOrNull { it.first == "timezone" }?.second as? Expression.Literal)?.value as? String
                    if (zone != null && runCatching { java.time.ZoneId.of(zone) }.getOrNull().let { it != null && it !is java.time.ZoneOffset }) unsupported("The time zone '$zone'")
                    val date = calendarDate(function, written)
                    if (date != null) {
                        val day = mapOf("year" to "${date.year}", "month" to "${date.monthValue}", "day" to "${date.dayOfMonth}")
                        return of(temporals.of(numbers - Temporals.CALENDAR_FIELDS + day, timezone, kind))
                    }
                    return of(temporals.ofCalendar(numbers, timezone, kind))
                }
                return of(temporals.of(numbers, timezone, kind))
            }
            val given = arguments.single() as? Value.Json ?: throw DrivineException("${function.name}() takes a text or a map of its fields.")
            val text = text(comparable(given))
            return when {
                kind == Temporal.DURATION -> of(temporals.duration(text))
                given.temporal?.instant == true -> of(temporals.convert(text, given.temporal, kind))
                else -> of(temporals.convert(text, kind))
            }
        }

        /**
         * The date the map [written], the argument of [function], gives by the calendar's other fields beside the year (see
         * [Temporals.ofCalendar]), where it writes the year and each of them as a whole number or null: null where an
         * expression gives any of them, for the store to work out. As Neo4j has them, a field that is null is given as its
         * least (the year 0, the first week, day or quarter), a week runs to 53 and a day of the quarter to 92 in any year,
         * carried into the next where it has fewer, and an ordinal day runs to the year's last. Fails, as Neo4j does, on
         * fields of two calendars (a month beside a week), a day of the week or of the quarter without its week or quarter,
         * a week or a quarter without a year, and a number written that is not whole or is past its field's range.
         */
        private fun calendarDate(function: Expression.Function, written: Expression.MapLiteral): java.time.LocalDate? {
            val name = function.name
            val keys = written.entries.map { it.first }.toSet()
            val calendars = listOf(
                "a month" to setOf("month", "day"), "a week" to setOf("week", "dayOfWeek"), "an ordinal day" to setOf("ordinalDay"),
                "a quarter" to setOf("quarter", "dayOfQuarter"),
            ).filter { (_, fields) -> fields.any { it in keys } }
            if (calendars.size > 1) throw DrivineException("$name() takes ${calendars[0].first} or ${calendars[1].first}, not both.")
            if ("dayOfWeek" in keys && "week" !in keys) throw DrivineException("$name() takes a day of the week beside a week.")
            if ("dayOfQuarter" in keys && "quarter" !in keys) throw DrivineException("$name() takes a day of the quarter beside a quarter.")
            if ("year" !in keys) {
                // Neo4j makes an ordinal day of no year one of the year 0, which no store's date reaches.
                if ("ordinalDay" in keys) unsupported("$name() of an ordinal day without a year")
                throw DrivineException("$name() takes a year beside ${calendars.single().first}.")
            }
            val numbers = written.entries.filter { (key, _) -> key == "year" || key in Temporals.CALENDAR_FIELDS }.associate { (key, value) ->
                key to when (val literal = (writtenLiteral(value) ?: return null).value) {
                    null -> CALENDAR_LEAST.getValue(key)
                    is Long -> literal
                    is Int -> literal.toLong()
                    else -> throw DrivineException("$name() takes a whole number for $key, not ${if (literal is String) "'$literal'" else literal}.")
                }
            }
            val year = numbers.getValue("year")
            fun field(key: String, label: String, last: Long, of: String = "") = (numbers[key] ?: 1L).also {
                if (it !in 1L..last) throw DrivineException("$name() takes $label from 1 to $last$of, not $it.")
            }
            return try {
                // A year past java.time's, as Neo4j's, fails here, where toInt() would wrap it into range.
                val y = java.time.temporal.ChronoField.YEAR.checkValidIntValue(year)
                when {
                    "week" in keys -> {
                        val monday = java.time.LocalDate.of(y, 1, 4).with(java.time.temporal.ChronoField.DAY_OF_WEEK, 1)
                        monday.plusDays((field("week", "a week", 53) - 1) * 7 + field("dayOfWeek", "a day of the week", 7) - 1)
                    }
                    "ordinalDay" in keys -> {
                        val first = java.time.LocalDate.of(y, 1, 1)
                        first.plusDays(field("ordinalDay", "an ordinal day", first.lengthOfYear().toLong(), " in $year") - 1)
                    }
                    else -> {
                        val quarter = field("quarter", "a quarter", 4)
                        java.time.LocalDate.of(y, ((quarter - 1) * 3 + 1).toInt(), 1).plusDays(field("dayOfQuarter", "a day of the quarter", 92) - 1)
                    }
                }
            } catch (e: java.time.DateTimeException) {
                throw DrivineException("$name() takes no year $year: ${e.message}", e)
            }
        }

        /**
         * The literal [value] writes as a temporal map's field, `-1`, a negated whole number, as much as `1`: null where an
         * expression gives it, for the store to work out.
         */
        private fun writtenLiteral(value: Expression): Expression.Literal? =
            (value as? Expression.Unary)?.takeIf { it.operator == Expression.Unary.Operator.NEGATE }
                ?.let { (it.operand as? Expression.Literal)?.value as? Long }?.let { Expression.Literal(-it) } ?: value as? Expression.Literal

        /**
         * The fields the map [written], the third argument of [function], gives the value of [kind] it truncates, in place
         * of its own, as Neo4j takes them. A field of no such value, a day or a month beside a day of the week, a number
         * that is not whole, or a number written past its field's range (see [TRUNCATION_RANGES]; a microsecond beside a
         * millisecond given or kept by a cut to [unit] runs to 999), fails, where a store would carry it into the next
         * field or fail on it at its own time. A field that is null is given as its least, as a map that leaves
         * it out makes a value: the year 0, the first month, day or day of the week, and no time; a time zone that is null
         * fails. Fields the stores have no SQL for yet (nanoseconds, the fields of a week or a quarter, a time zone by
         * name) are refused.
         */
        private fun truncationFields(function: Expression.Function, unit: String, kind: Temporal, written: Expression, scope: Map<String, Value>): Temporals.Overrides {
            // Null gives no fields, as Neo4j has it.
            if (written == Expression.Literal(null)) return Temporals.Overrides(emptyMap(), null)
            val entries = (written as? Expression.MapLiteral ?: unsupported("A truncation's fields that an expression gives")).entries
            val takes = (if (kind != Temporal.LOCAL_TIME && kind != Temporal.TIME) TRUNCATION_DATE_FIELDS else emptySet()) +
                (if (kind != Temporal.DATE) TRUNCATION_TIME_FIELDS else emptySet()) + (if (kind.zoned) setOf("timezone") else emptySet())
            entries.forEach { (key, _) ->
                if (key !in takes) throw DrivineException("${function.name}() takes no field $key.")
                if (key in TRUNCATION_FIELDS_NOT_COMPILED) unsupported("${function.name}() of a map with $key")
            }
            val keys = entries.map { it.first }.toSet()
            if ("day" in keys && "dayOfWeek" in keys) throw DrivineException("${function.name}() takes a day or a day of the week, not both.")
            if ("month" in keys && "dayOfWeek" in keys) throw DrivineException("${function.name}() takes a month or a day of the week, not both.")
            val millis = "millisecond" in keys || unit == "millisecond"
            val timezone = entries.firstOrNull { it.first == "timezone" }?.let { (_, value) ->
                if (value == Expression.Literal(null)) throw DrivineException("${function.name}() takes a time zone, not null.")
                val zone = (value as? Expression.Literal)?.value as? String ?: unsupported("A truncation to a time zone an expression gives")
                // Neo4j takes an offset written as `Z` or `+01:00` alone, and a zone by name, which no store's text holds yet.
                val id = runCatching { java.time.ZoneId.of(zone) }.getOrNull()
                when {
                    id is java.time.ZoneOffset && Regex("Z|[+-][0-9]{2}:[0-9]{2}").matches(zone) -> "'${id.id}'"
                    id != null && id !is java.time.ZoneOffset -> unsupported("The time zone '$zone'")
                    else -> throw DrivineException("'$zone' is no time zone.")
                }
            }
            val numbers = entries.filter { it.first != "timezone" }.associate { (key, value) ->
                val least = TRUNCATION_LEAST[key] ?: 0
                val number = when (val literal = writtenLiteral(value)?.value) {
                    null -> if (value is Expression.Literal) "$least" else "COALESCE(${number(comparable(json(expression(value, scope))))}, $least)"
                    is Long, is Int -> {
                        val range = if (key == "microsecond" && millis) 0L..999L else TRUNCATION_RANGES[key]
                        if (range != null && (literal as Number).toLong() !in range) {
                            val name = mapOf("dayOfWeek" to "a day of the week", "hour" to "an hour")[key] ?: "a $key"
                            val beside = if (key == "microsecond" && millis) " beside a millisecond" else ""
                            throw DrivineException("${function.name}() takes $name from ${range.first} to ${range.last}$beside, not $literal.")
                        }
                        "$literal"
                    }
                    else -> throw DrivineException("${function.name}() takes a whole number for $key, not ${if (literal is String) "'$literal'" else literal}.")
                }
                key to number
            }
            return Temporals.Overrides(numbers, timezone)
        }

        private fun mapProjection(projection: Expression.MapProjection, scope: Map<String, Value>): Value {
            val subject = scope[projection.variable] ?: throw DrivineException("${projection.variable} is not defined.")
            val values = projection.elements.filterNot { it is Expression.MapProjection.Element.All }.map { element ->
                when (element) {
                    is Expression.MapProjection.Element.Selected -> element.key to property(subject, element.key)
                    is Expression.MapProjection.Element.Entry -> element.key to expression(element.value, scope)
                    is Expression.MapProjection.Element.Variable -> element.name to (scope[element.name] ?: throw DrivineException("${element.name} is not defined."))
                    Expression.MapProjection.Element.All -> error("filtered")
                }
            }
            val entries = values.map { (key, value) -> key to json(value).sql }
            // A projection of null is null, as in Cypher, and not a map of nulls.
            fun present(map: String) = "CASE WHEN NOT (${isNull(subject)}) THEN $map END"
            val temporals = values.any { hasTemporals(it.second) }
            if (projection.elements.none { it is Expression.MapProjection.Element.All }) {
                val identity = identityHolding(values.map { it.second }) { ids -> present(jsonObject(values.map { it.first }.zip(ids))) }
                return Value.Json(present(jsonObject(entries)), ordered = true, identity = identity, temporals = temporals)
            }
            // Every property, then the entries over them.
            return Value.Json(present(withEntries(comparable(json(subject)), entries)), temporals = temporals || holdsTemporals(subject))
        }

        /**
         * [left] and [right], the sides of [binary], as compared: where one holds a node or a relationship by identity and the
         * other may hold one without it (an item taken from a list, a collect, a variable), both by their properties, as
         * before identities: only a value the statement writes out or is given, all the way down (a literal, a parameter, a
         * map, a list or a map projection of such, a `CASE` that gives one), holds none where it carries no identity, so
         * `[{x: r}][0] = {x: r}` is true, and `{x: r} = {x: {}}` false. A node or a relationship compared with such a value is
         * compared by its identity, so `r = {}` is false.
         */
        private fun compared(left: Value, right: Value, binary: Expression.Binary): Pair<Value, Value> {
            // Written all the way down: `{a: ms[0]}` and `n {.*, x: r}` hold what may be a node without its identity.
            fun written(expression: Expression): Boolean = when (expression) {
                is Expression.Literal, is Expression.Parameter -> true
                is Expression.MapLiteral -> expression.entries.all { written(it.second) }
                is Expression.ListLiteral -> expression.items.all(::written)
                is Expression.MapProjection -> expression.elements.all {
                    it is Expression.MapProjection.Element.Selected || it is Expression.MapProjection.Element.Entry && written(it.value)
                }
                is Expression.Case -> (expression.alternatives.map { it.second } + listOfNotNull(expression.otherwise)).all(::written)
                else -> false
            }
            fun held(value: Value) = (value as? Value.Json)?.identity != null
            fun plain(value: Value) = if (value is Value.Json) value.copy(identity = null) else value
            fun entity(value: Value) = value is Value.Node || value is Value.Relationship
            fun identity(value: Value) = Value.Json(json(value).sql, identity = identityOf(value), flat = true)
            return when {
                entity(left) && right is Value.Json && !held(right) && written(binary.right) -> identity(left) to right
                entity(right) && left is Value.Json && !held(left) && written(binary.left) -> left to identity(right)
                held(left) && right is Value.Json && !held(right) && !written(binary.right) -> plain(left) to right
                held(right) && left is Value.Json && !held(left) && !written(binary.left) -> left to plain(right)
                else -> left to right
            }
        }

        private fun binary(binary: Expression.Binary, scope: Map<String, Value>): Value {
            val filter = filtered
            if (binary.operator == Operator.AND || binary.operator == Operator.OR || binary.operator == Operator.XOR) {
                listOf(binary.left, binary.right).forEach { requireCondition(it, scope, binary.operator.name) }
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
            if (binary.operator == Operator.IN) knownKind(binary.right, scope)?.takeIf { it != Value.Known.LIST }?.let { kind ->
                throw DrivineException("IN takes a list, not ${kind.noun}, as in Neo4j.")
            }
            if (binary.operator == Operator.EQUALS || binary.operator == Operator.NOT_EQUALS || binary.operator == Operator.IN) {
                itemwise(binary, scope)?.let { return Value.Bool(if (binary.operator == Operator.NOT_EQUALS) "(NOT $it)" else it) }
            }
            val (left, right) = compared(expression(binary.left, scope), expression(binary.right, scope), binary)
            temporalBinary(binary.operator, left, right)?.let { return it }
            val a = comparable(json(left))
            val b = comparable(json(right))
            return when (binary.operator) {
                Operator.EQUALS, Operator.NOT_EQUALS -> {
                    val plain = plain(binary.left, binary.right, left, right, scope)
                    val equal = (if (plain) null else givenItemwise(binary.left, left, binary.right, right))
                        ?: equality(left, right, binary.right, plain, stored(binary.left, scope))
                    Value.Bool(if (binary.operator == Operator.EQUALS) equal else "NOT ($equal)")
                }
                // Values of two kinds are not ordered, as in Cypher: the comparison is null. Two lists are, item by item.
                Operator.LESS_THAN, Operator.GREATER_THAN, Operator.LESS_THAN_OR_EQUAL, Operator.GREATER_THAN_OR_EQUAL -> {
                    val operator = mapOf(
                        Operator.LESS_THAN to "<", Operator.GREATER_THAN to ">", Operator.LESS_THAN_OR_EQUAL to "<=", Operator.GREATER_THAN_OR_EQUAL to ">=",
                    ).getValue(binary.operator)
                    if (listed(left, right)) Value.Bool(listCompared(left, a, right, b, operator))
                    else Value.Bool(sharedCompared(a, b) { (x, y) -> "CASE WHEN ${typeOf(x)} = ${typeOf(y)} AND ${typeOf(x)} IN ('number', 'string', 'boolean') THEN ${ordered(x, operator, y)} END" })
                }
                // Two lists of nodes, or of relationships, are together a list of them.
                Operator.ADD -> if (left is Value.Entities && right is Value.Entities && left.relationships == right.relationships) {
                    val gathered = if (left.gathered != null || right.gathered != null) shared(a, b) { (x, y) -> add(x, y) } else null
                    Value.Entities(shared(left.ids, right.ids) { (x, y) -> add(x, y) }, left.relationships, gathered)
                } else {
                    // Two lists of items of one kind are, together, a list of that kind, and so of lists of items of one kind. A side the statement makes a text, not given,
                    // is written as its own text; a text put together with a text, or a number given, is a text.
                    val (givenLeft, givenRight) = given(binary.left) to given(binary.right)
                    val (textLeft, textRight) = (isText(left) || givenLeft is String) to (isText(right) || givenRight is String)
                    Value.Json(
                        sharedSum(a, b) { (x, y) ->
                            add(x, y, (givenLeft ?: MadeText.takeIf { textLeft }) to (givenRight ?: MadeText.takeIf { textRight }))
                        },
                        items = itemsOf(left)?.takeIf { it == itemsOf(right) }, itemItems = itemItemsOf(left)?.takeIf { it == itemItemsOf(right) },
                        temporals = hasTemporals(left) || hasTemporals(right),
                        list = isList(left) || isList(right), itemLists = holdsLists(left) && (holdsLists(right) || !isList(right)),
                        text = (textLeft && (textRight || isGivenNumber(givenRight))) || (textRight && isGivenNumber(givenLeft)),
                        texts = (holdsTexts(left) && (holdsTexts(right) || textRight)) || (textLeft && holdsTexts(right)),
                    )
                }
                Operator.SUBTRACT -> Value.Json(shared(a, b) { (x, y) -> arithmetic(x, y, "-") })
                Operator.MULTIPLY -> Value.Json(shared(a, b) { (x, y) -> arithmetic(x, y, "*") })
                // A whole number divided by one is a whole number, as in Cypher.
                Operator.DIVIDE -> Value.Json(
                    shared(a, b) { (x, y) -> "CASE WHEN ${integral(x)} AND ${integral(y)} THEN ${scalar(wholeQuotient(number(x), number(y)))} ELSE ${arithmetic(x, y, "/")} END" }
                )
                Operator.MODULO -> Value.Json(scalar(modulo(number(a), number(b))))
                Operator.POWER -> Value.Json(scalar(float("power(${number(a)}, ${number(b)})")))
                Operator.IN -> Value.Bool(membership(binary, left, right, scope, filter))
                Operator.STARTS_WITH -> Value.Bool(texts(a, b) { texts -> shared(*texts.toTypedArray()) { (x, y) -> startsWith(x, y) } })
                // A pattern is short: only the text is read as a long one where it is.
                Operator.MATCHES -> Value.Bool(texts(a) { (x) -> matches(x, text(b)) })
                Operator.ENDS_WITH -> Value.Bool(texts(a, b) { texts -> shared(*texts.toTypedArray()) { (x, y) -> endsWith(x, y) } })
                Operator.CONTAINS -> Value.Bool(texts(a, b) { (x, y) -> containsText(x, y) })
                else -> error("logical operators are handled above")
            }
        }

        /**
         * Whether `<`, `<=`, `>` or `>=` of [left] and [right] compares two lists, item by item: where the statement makes either
         * a list, of values (a list of nodes or of relationships compares as before). Any other comparison is written as it was.
         */
        private fun listed(left: Value, right: Value): Boolean = left is Value.Json && right is Value.Json && (left.list || right.list)

        /**
         * `<`, `<=`, `>` or `>=`, [operator], of [left] and [right], whose JSON is [a] and [b]: where both are lists, as [listOrder]
         * orders them, their temporal items as [comparedItems] gives them; else null, as a list and a value of another kind are not
         * ordered.
         */
        private fun listCompared(left: Value, a: String, right: Value, b: String, operator: String): String {
            val (x, y) = comparedItems(left, a, right, b)
            return shared(x, y) { (p, q) -> "CASE WHEN ${typeOf(p)} = 'array' AND ${typeOf(q)} = 'array' THEN ${listOrder(p, q)} $operator 0 END" }
        }

        /**
         * [a] and [b], the JSON of the lists [left] and [right], as [listOrder] compares them, where the statement makes the items of
         * both, or the items of their items, temporal: a zoned value as the moment it is, whatever its offset, as `ORDER BY` sorts it
         * (see [momentsKey]); a duration as a map of it alone, equal where it is equal and else not ordered, as in Cypher; and a value
         * of one kind beside one of another as a map of its kind, so the two are not ordered, as is one beside a text, where the
         * statement makes the other list's items texts. A local value is its ISO text, which orders as it does. Each as it is otherwise:
         * a list of temporal values beside one of values of several kinds, a list whose temporal values are deeper, or one whose items
         * the statement does not know, compares its ISO texts. Their items are read by their places ([temporalElements]), as Oracle
         * must read a list of temporal values.
         */
        private fun comparedItems(left: Value, a: String, right: Value, b: String): Pair<String, String> {
            fun kind(list: Value) = itemsOf(list)?.let { it to 1 } ?: itemItemsOf(list)?.takeIf { holdsLists(list) }?.let { it to 2 }
            fun wrapped(of: Temporal): (String) -> String = { item -> comparable(Value.Json(jsonObject(listOf(of.name.lowercase() to item)), ordered = true)) }
            val (l, r) = kind(left) to kind(right)
            // A temporal value beside a text: its ISO text would order where Cypher does not.
            if (l?.second == 1 && r == null && holdsTexts(right)) return mappedItems(a, 1, wrapped(l.first)) to b
            if (r?.second == 1 && l == null && holdsTexts(left)) return a to mappedItems(b, 1, wrapped(r.first))
            if (l == null || r == null || l.second != r.second) return a to b
            val (kind, depth) = l
            val moment: (String) -> String = { item -> scalar(temporalSql.sortable(temporals.moment(text(item), kind))) }
            val (eachOfA, eachOfB) = when {
                kind != r.first -> wrapped(kind) to wrapped(r.first)
                kind == Temporal.DURATION -> wrapped(kind) to wrapped(kind)
                kind.zoned -> moment to moment
                else -> return a to b
            }
            return mappedItems(a, depth, eachOfA) to mappedItems(b, depth, eachOfB)
        }

        /** The JSON list [json] with each item at [depth], 1 its own items and 2 theirs, that is not null as [each] gives it. */
        private fun mappedItems(json: String, depth: Int, each: (String) -> String): String = shared(json) { (x) ->
            fun over(list: String, depth: Int): String {
                val e = alias("lc")
                val mapped = if (depth == 1) each(item(e)) else "CASE WHEN ${typeOf(item(e))} = 'array' THEN ${over(item(e), depth - 1)} ELSE ${item(e)} END"
                val items = aggregateList("CASE WHEN ${isJsonNull(item(e))} THEN ${literalJson(null)} ELSE $mapped END", itemNo(e))
                return comparable(Value.Json("(SELECT $items FROM ${temporalElements(list, e)})", ordered = true))
            }
            "CASE WHEN ${typeOf(x)} = 'array' THEN ${over(x, depth)} END"
        }

        /**
         * Whether two values are equal: as JSON, so `'7'` is not `7` and `1` is `1.0`. A node is equal to a
         * node by its row. Where a property is compared with [written], a text or a truth value the statement
         * is given, a test an index answers stands beside it, where the store has one: such a value's text is
         * equal where its JSON is, and a number's is not (`1` and `1.0`). [left] is [stored] where it is a property read from the
         * store, which is SQL's null where it is not there, and so needs no test of its own beside such a value. Where neither
         * is [plain], either may hold a null: see [plain].
         */
        private fun equality(left: Value, right: Value, written: Expression? = null, plain: Boolean = true, stored: Boolean = true): String {
            (temporalBinary(Operator.EQUALS, left, right) as? Value.Bool)?.let { return it.sql }
            if (left is Value.Node && right is Value.Node) return "${left.nid} = ${right.nid}"
            if (left is Value.Relationship && right is Value.Relationship) return "${left.eid} = ${right.eid}"
            // A map or a list that holds a node or a relationship is equal where what it holds is, by identity.
            if ((left as? Value.Json)?.identity != null || (right as? Value.Json)?.identity != null) {
                if (!plain) return nullableEquals(identified(left), identified(right), ::identityEquals)
                return shared(identified(left), identified(right)) { (x, y) -> "CASE WHEN NOT ${isJsonNull(x)} AND NOT ${isJsonNull(y)} THEN ${identityEquals(x, y)} END" }
            }
            // Two lists of nodes, or of relationships, are equal where their ids are, item by item.
            val entities = left is Value.Entities && right is Value.Entities && left.relationships == right.relationships
            val a = if (entities) (left as Value.Entities).ids else comparable(json(left))
            val b = if (entities) (right as Value.Entities).ids else comparable(json(right))
            // Either may hold a null, which makes the comparison null where no item or entry differs.
            if (!plain) return nullableEquals(a, b, ::jsonEquals)
            val given = written?.let(::given)
            val indexed = if (given is String || given is Boolean) indexedEquality(a, b) else null
            // Null is equal to nothing, itself included: the comparison is null, as in Cypher. A value the statement
            // is given that is not null needs no test of its own, and a property that is not there is SQL's null.
            val equal = if (given != null) givenEquals(a, b, given).let { if (stored) it else "CASE WHEN NOT ${isJsonNull(a)} THEN $it END" } else sharedCompared(a, b) { (x, y) -> "CASE WHEN NOT ${isJsonNull(x)} AND NOT ${isJsonNull(y)} THEN ${jsonEquals(x, y)} END" }
            return if (indexed != null) "($indexed AND $equal)" else equal
        }

        /**
         * Whether the sides [leftWritten] and [rightWritten] of a comparison, compiled as [left] and [right], compare as JSON, as
         * [jsonEquals] compares them: where one is no list or map, or neither holds a null in one (see [Value.Json.flat]). Else
         * a null they hold makes the comparison null where no item or entry differs, as in Cypher.
         */
        private fun plain(leftWritten: Expression, rightWritten: Expression, left: Value, right: Value, scope: Map<String, Value>): Boolean =
            scalar(leftWritten, scope) || scalar(rightWritten, scope) || (isFlat(left) && isFlat(right))

        /** Whether [expression] is a property of a node or a relationship in [scope], read from the store: SQL's null where it is not there. */
        private fun stored(expression: Expression, scope: Map<String, Value>): Boolean =
            expression is Expression.Property && (expression.subject as? Expression.Variable)?.let { scope[it.name] is Value.Node || scope[it.name] is Value.Relationship } == true

        /** Whether [value] is known to hold no null in a list or a map: see [Value.Json.flat]. A node or a relationship is compared by its id. */
        private fun isFlat(value: Value): Boolean = when (value) {
            is Value.Json -> value.flat
            is Value.Entities -> value.flat
            is Value.Bool, is Value.Node, is Value.Relationship -> true
            is Value.Path -> false
        }

        /** Whether [expression] is never null: a value the statement writes, a list or a map among them, or a parameter bound to one. */
        private fun present(expression: Expression): Boolean = when (expression) {
            is Expression.Literal -> expression.value != null
            is Expression.Parameter -> parameters.value(expression.name) != null
            is Expression.ListLiteral, is Expression.MapLiteral -> true
            else -> false
        }

        /** Whether [expression] is a number, a text or a truth value the statement writes, or a parameter bound to one: never null, and no list or map. */
        private fun presentScalar(expression: Expression, scope: Map<String, Value>): Boolean = present(expression) && scalar(expression, scope)

        /** Whether the value [expression] gives in [scope] is known to be no list or map: it may be null. */
        private fun scalar(expression: Expression, scope: Map<String, Value>): Boolean = when (expression) {
            is Expression.Literal, is Expression.Unary, is Expression.HasLabels, is Expression.CountStar, is Expression.PatternPredicate,
            is Expression.Exists, is Expression.Subquery, is Expression.Quantifier -> true
            is Expression.Parameter -> parameterTree(expression.name)?.isContainerNode != true
            is Expression.Variable -> scope[expression.name] is Value.Bool
            // A sum is a list where either side is; every other operator gives a number, a text, a temporal value or a truth value.
            is Expression.Binary -> expression.operator != Operator.ADD || (scalar(expression.left, scope) && scalar(expression.right, scope))
            is Expression.Function -> expression.name.lowercase() in SCALAR_FUNCTIONS
            else -> false
        }

        /**
         * Whether the value [expression] gives in [scope] is known to hold no null in a list or a map, at any depth: see
         * [Value.Json.flat]. Read from what is written, and what [scope] holds, so it compiles nothing.
         */
        private fun flat(expression: Expression, scope: Map<String, Value>): Boolean = when (expression) {
            is Expression.Literal, is Expression.Unary, is Expression.HasLabels, is Expression.CountStar, is Expression.PatternPredicate,
            is Expression.Exists, is Expression.Subquery, is Expression.Quantifier -> true
            is Expression.Parameter -> parameterTree(expression.name)?.let(::holdsNoNull) ?: true
            is Expression.Variable -> scope[expression.name]?.let(::isFlat) ?: false
            // A property of a node or a relationship holds no null, as a graph engine stores none in a list; a parameter's map's, as it is bound.
            is Expression.Property -> (expression.subject as? Expression.Parameter)?.let { parameterTree(it.name)?.get(expression.key)?.let(::holdsNoNull) ?: true }
                ?: flat(expression.subject, scope)
            is Expression.ListLiteral -> expression.items.all { present(it) && flat(it, scope) }
            is Expression.MapLiteral -> expression.entries.all { (_, value) -> present(value) && flat(value, scope) }
            // A list and an item that may be null are a list that holds it.
            is Expression.Binary -> expression.operator != Operator.ADD || (flat(expression.left, scope) && flat(expression.right, scope) &&
                (present(expression.left) || presentScalar(expression.right, scope)) && (present(expression.right) || presentScalar(expression.left, scope)))
            is Expression.Index -> flat(expression.subject, scope)
            is Expression.Slice -> flat(expression.subject, scope)
            is Expression.Function -> expression.name.lowercase().let { name ->
                name in FLAT_FUNCTIONS || (name in CARRYING_FUNCTIONS && expression.arguments.all { flat(it, scope) })
            }
            is Expression.Case -> (expression.alternatives.map { it.second } + listOfNotNull(expression.otherwise)).all { flat(it, scope) }
            else -> false
        }

        /** The parameter [name]'s value as the JSON it is bound as; null where it is null. */
        private fun parameterTree(name: String): JsonNode? = parameterTrees.getOrPut(name) {
            Neo4jObjectMapper.instance.valueToTree<JsonNode>(parameters.value(name)).takeUnless { it.isNull || it.isMissingNode }
        }

        private val parameterTrees = mutableMapOf<String, JsonNode?>()

        /** Whether the JSON [node] holds no null in a list or a map, at any depth. */
        private fun holdsNoNull(node: JsonNode): Boolean = !node.isContainerNode || node.all { !it.isNull && holdsNoNull(it) }

        /**
         * [binary], an equality or an `IN`, compared item by item where its sides are lists or maps the statement writes out, `[a, b] = [c, d]`,
         * `{x: a} = {x: b}`, `x IN [a, b]`, and one of them may hold a null: each pair compared, and the comparisons taken together as SQL's
         * `AND` (`OR` for an `IN`) takes them, which is as Cypher takes them: false where one is false, else null where one is null. Lists of
         * different lengths, and maps of different keys, are not equal. Null where the sides are not so written, or hold no null, and so
         * compare whole, as JSON.
         */
        private fun itemwise(binary: Expression.Binary, scope: Map<String, Value>): String? {
            val (left, right) = binary.left to binary.right
            if (flat(left, scope) && flat(right, scope)) return null
            fun joined(pairs: List<Pair<Expression, Expression>>, operator: String) =
                grouped(pairs.map { (a, b) -> bool(binary(Expression.Binary(Operator.EQUALS, a, b), scope)) }, operator)
            return when {
                // The item compiled once, and compared with each of the list's. A null the list writes makes its comparison null
                // whatever the value, so the nulls are one null term. A value that compares as JSON is tested at once, as [membership]
                // tests it against a list, against the items that are constants holding no null, where there are two or more: written
                // one by one, a long list of mixed kinds compiled to megabytes on Oracle (#360). A value that may hold a null is tested
                // against the texts, numbers and truth values apart, as one that holds none, which none of them is equal to but for a
                // null; and against the lists and maps as membership tests such a value, item by item where it holds a null.
                binary.operator == Operator.IN -> (right as? Expression.ListLiteral)?.takeIf { it.items.isNotEmpty() }?.let { list ->
                    val item = expression(left, scope)
                    val (nulls, items) = list.items.partition { it is Expression.Literal && it.value == null }
                    val (constants, others) = items.partition { plainJson(item) && constant(it) }
                        .let { (constants, others) -> if (constants.size > 1) constants to others else emptyList<Expression>() to items }
                    val (scalars, containers) = if (isFlat(item)) constants to emptyList()
                        else constants.partition { it !is Expression.ListLiteral && it !is Expression.MapLiteral }
                    val within = listOf(scalars to (item as? Value.Json)?.copy(flat = true), containers to item).filter { it.first.isNotEmpty() }
                        .map { (written, value) ->
                            val listed = Expression.ListLiteral(written)
                            membership(Expression.Binary(Operator.IN, left, listed), requireNotNull(value), expression(listed, scope), scope, false)
                        }
                    val compared = others.map { listed ->
                        val equals = Expression.Binary(Operator.EQUALS, left, listed)
                        val (l, r) = compared(item, expression(listed, scope), equals)
                        equality(l, r, listed, plain(left, listed, l, r, scope), stored(left, scope))
                    }
                    val terms = within + compared
                    // A null beside other terms takes its kind from them; alone, it is given one, as Postgres can't make a bare NULL JSON.
                    when {
                        nulls.isEmpty() -> grouped(terms, "OR")
                        terms.isEmpty() -> "(CASE WHEN 1 = 0 THEN FALSE END)"
                        else -> grouped(terms + "NULL", "OR")
                    }
                }
                left is Expression.ListLiteral && right is Expression.ListLiteral ->
                    if (left.items.size != right.items.size) "FALSE" else joined(left.items.zip(right.items), "AND")
                left is Expression.MapLiteral && right is Expression.MapLiteral -> {
                    val (l, r) = left.entries.toMap() to right.entries.toMap()
                    if (l.keys != r.keys) "FALSE" else joined(l.map { (key, value) -> value to r.getValue(key) }, "AND")
                }
                else -> null
            }
        }

        /**
         * Whether [value] compares as its JSON, as [membership] compares a value with a list's items: no node, relationship or
         * value that holds one, which compares by identity, and no temporal value, which compares by its kind. One that may hold
         * a null [membership] compares as [equality] does, null where an item is equal but for the null.
         */
        private fun plainJson(value: Value): Boolean =
            value is Value.Json && value.identity == null && !holdsTemporals(value) && value.temporal == null

        /**
         * Whether [expression] is a constant that holds no null: a number, a text or a truth value the statement writes, a
         * negated number, or a list or a map of such. As JSON, it is equal to a value that holds no null where Cypher finds them equal.
         */
        private fun constant(expression: Expression): Boolean = when (expression) {
            is Expression.Literal -> expression.value != null
            is Expression.Unary -> expression.operator == Expression.Unary.Operator.NEGATE && (expression.operand as? Expression.Literal)?.value is Number
            is Expression.ListLiteral -> expression.items.all(::constant)
            is Expression.MapLiteral -> expression.entries.all { constant(it.second) }
            else -> false
        }

        /**
         * The conditions [terms] joined by [operator], in parentheses. SQLite reads `a OR b OR c` as an expression as deep as it
         * is long, and refuses one deeper than 1,000 (#332): past [MOST_ARGUMENTS] terms, each run of them is joined in
         * parentheses of its own, and the runs joined so in turn.
         */
        private fun grouped(terms: List<String>, operator: String): String =
            if (terms.size <= MOST_ARGUMENTS) terms.joinToString(" $operator ", "(", ")")
            else grouped(terms.chunked(MOST_ARGUMENTS) { grouped(it, operator) }, operator)

        /**
         * An equality of [left] and [right], written as [leftWritten] and [rightWritten], where one is a parameter bound to a list
         * that holds a null, lists within it among its items and no map: the other compared with it item by item, each item
         * as the statement is compiled with it, as [itemwise] compares a list written out, so no store walks the two. Null
         * where neither is such a parameter, or the store's own comparison compares a null as Cypher does. Nor where an item is
         * too long to write in the statement (see [short]): such a list is walked as any other.
         */
        private fun givenItemwise(leftWritten: Expression, left: Value, rightWritten: Expression, right: Value): String? {
            if (holdsNull("x") == null) return null
            fun given(expression: Expression) = (expression as? Expression.Parameter)?.let { parameterTree(it.name) }
                ?.takeIf { it.isArray && !holdsNoNull(it) && arrays(it) && short(it) }
            fun other(value: Value) = (value as? Value.Json)?.takeIf { it.identity == null && it.temporal == null }
            given(rightWritten)?.let { list -> other(left)?.let { return itemsEqual(comparable(it), list) } }
            given(leftWritten)?.let { list -> other(right)?.let { return itemsEqual(comparable(it), list) } }
            return null
        }

        /** Whether the JSON [node] holds no map, at any depth. */
        private fun arrays(node: JsonNode): Boolean = !node.isObject && (!node.isArray || node.all(::arrays))

        /**
         * Whether each text, number and truth value the JSON [node], a list that holds no map, holds is short enough to write in
         * the statement: see [LITERAL_BYTES].
         */
        private fun short(node: JsonNode): Boolean =
            if (node.isArray) node.all(::short)
            else Neo4jObjectMapper.instance.writeValueAsString(scalarOf(node)).toByteArray(Charsets.UTF_8).size <= LITERAL_BYTES

        /**
         * Whether the JSON value [json] is equal to [given], a list that may hold a null and lists, as Cypher compares them: the
         * same length, and each item equal, false where one is not, else null where one is null. A text, a number or a truth value
         * is compared as JSON.
         */
        private fun itemsEqual(json: String, given: JsonNode): String = shared(json) { (x) ->
            if (!given.isArray) return@shared "CASE WHEN NOT ${isJsonNull(x)} THEN ${jsonEquals(x, literalJson(scalarOf(given)))} END"
            val checks = given.withIndex().filter { !it.value.isNull }.map { (i, item) -> itemsEqual(itemAt(x, i), item) }
            val nulls = given.any { it.isNull }
            // A null item makes the whole null where every other item is equal.
            val items = when {
                !nulls -> if (checks.isEmpty()) "TRUE" else checks.joinToString(" AND ", "(", ")")
                checks.isEmpty() -> "NULL"
                else -> "CASE WHEN NOT ${checks.joinToString(" AND ", "(", ")")} THEN FALSE END"
            }
            // Its size read only of a list, in the order a CASE reads its conditions.
            "CASE WHEN ${isJsonNull(x)} THEN NULL WHEN ${typeOf(x)} <> 'array' THEN FALSE WHEN ${listSize(x)} <> ${given.size()} THEN FALSE ELSE $items END"
        }

        /** The text, the number or the truth value [node] holds, as the statement would write it. */
        private fun scalarOf(node: JsonNode): Any? = when {
            node.isTextual -> node.textValue()
            node.isBoolean -> node.booleanValue()
            node.isIntegralNumber -> node.longValue()
            node.isNumber -> node.doubleValue()
            else -> null
        }

        /**
         * `x IN list`, of [left] and [right], the sides of [binary], as Cypher has it: null where the list is null; else true where an item is
         * equal to the value, else null where a comparison with one is null (the value is null, an item is, or holds a null where the value
         * holds another), else false. A value that holds no null, in a list that holds none, is in it where an item is equal to it;
         * read by a `WHERE` for its truth alone ([filter]), it is not in it where it is null, as before.
         */
        private fun membership(binary: Expression.Binary, left: Value, right: Value, scope: Map<String, Value>, filter: Boolean): String {
            // A node or a relationship is in a list of them where its id is.
            val id = when {
                right !is Value.Entities -> null
                left is Value.Node && !right.relationships -> scalar(left.nid)
                left is Value.Relationship && right.relationships -> scalar(left.eid)
                else -> null
            }
            val identities = (left as? Value.Json)?.identity != null || (right as? Value.Json)?.identity != null
            val list = when {
                id != null -> (right as Value.Entities).ids
                // A map or a list that holds a node or a relationship is in a list where an item holds the same, by identity.
                identities -> identified(right)
                else -> comparable(json(right))
            }
            val x = when {
                id != null -> id
                identities -> identified(left)
                else -> comparable(json(left))
            }
            val equals: (String, String) -> String = if (identities) ::identityEquals else ::jsonEquals
            val items = if (id == null && !identities) givenItems(binary.right) else null
            fun matches(item: String, value: String) = when {
                id != null -> "$item = $value"
                items != null -> givenItemEquals(item, value, items)
                else -> equals(item, value)
            }
            val within = isFlat(right)
            // A node, a relationship or a value of one kind holds no null; nor does an item of a list that holds none.
            val whole = id != null || scalar(binary.left, scope) || isFlat(left)
            val given = (binary.right as? Expression.Parameter)?.let { parameterTree(it.name) }
            val filled = (binary.right as? Expression.ListLiteral)?.items?.isNotEmpty() == true || (given != null && given.size() > 0)
            // Null and false keep no row alike, and a value that holds no null is null beside such a list only where it is null:
            // beside any list, read by a WHERE for its truth alone, it is equal as JSON only to an item Cypher finds equal.
            if (whole && (filter || (present(binary.left) && present(binary.right) && within))) {
                val i = alias("i")
                // But JSON's null is equal as JSON to an item that is, so a value that may be JSON's null (not a property read from
                // the store, which is SQL's null where it is not there) is in a list that may hold a null only where it is not null.
                val nullable = !within && id == null && !present(binary.left) && !stored(binary.left, scope) && holdsNull("x") != null
                if (!nullable) return "EXISTS (SELECT 1 FROM ${elementsOf(right, list, i)} WHERE ${matches(item(i), x)})"
                return sharedCompared(list, x) { (l, v) -> "(EXISTS (SELECT 1 FROM ${elementsOf(right, l, i)} WHERE ${matches(item(i), v)}) AND NOT ${isJsonNull(v)})" }
            }
            // A value that may be null, in a list that is not and holds none: in where an item is equal, null where the value is
            // null, else not, written so the store still answers the first as it did, as a semi-join.
            if (present(binary.right) && within && whole && filled) {
                val i = alias("i")
                return sharedCompared(list, x) { (l, v) -> "(EXISTS (SELECT 1 FROM ${elementsOf(right, l, i)} WHERE ${matches(item(i), v)}) OR CASE WHEN NOT ${isJsonNull(v)} THEN FALSE END)" }
            }
            return sharedCompared(list, x) { (l, v) ->
                val (i, j) = alias("i") to alias("i")
                val cases = mutableListOf<String>()
                if (!present(binary.right)) cases += "WHEN ${isJsonNull(l)} THEN NULL"
                // Null is compared with each item, and is in no empty list.
                if (!present(binary.left)) cases += "WHEN ${isJsonNull(v)} THEN " +
                    if (filled) "NULL" else "CASE WHEN EXISTS (SELECT 1 FROM ${elementsOf(right, l, j)}) THEN NULL ELSE FALSE END"
                // A value that holds a null is equal to no item.
                val held = if (whole) null else holdsNull(v)
                cases += "WHEN ${held?.let { "NOT ($it) AND " }.orEmpty()}EXISTS (SELECT 1 FROM ${elementsOf(right, l, i)} WHERE ${matches(item(i), v)}) THEN TRUE"
                if (!(within && whole)) {
                    val k = alias("i")
                    val undecided = if (id != null) isJsonNull(item(k)) else "(${nullableEquals(item(k), v, equals)}) IS NULL"
                    cases += "WHEN EXISTS (SELECT 1 FROM ${elementsOf(right, l, k)} WHERE $undecided) THEN NULL"
                }
                "CASE ${cases.joinToString(" ")} ELSE FALSE END"
            }
        }

        /**
         * The items of the list [expression] where the statement writes or is given each, a list literal of literals and parameters,
         * or a parameter: null where it is not so given, or holds a list or a map, whose items [givenItemEquals] doesn't know.
         */
        private fun givenItems(expression: Expression): List<Any?>? = when (expression) {
            is Expression.ListLiteral -> expression.items.map { item ->
                when (item) {
                    is Expression.Literal -> item.value
                    is Expression.Parameter -> parameterTree(item.name)?.let { if (it.isContainerNode) return null else scalarOf(it) }
                    else -> return null
                }
            }.takeIf { values -> values.none { it is List<*> || it is Map<*, *> } }
            is Expression.Parameter -> parameterTree(expression.name)?.takeIf { it.isArray && it.none(JsonNode::isContainerNode) }?.map(::scalarOf)
            else -> null
        }

        /**
         * [value] as JSON that compares by identity: a node or a relationship as its id, under a key no property of the graph's has
         * (`" node"`, `" relationship"`), a list of them as the list of their ids, and a map or a list that holds one as its
         * [Value.Json.identity]. Null where the value holds none.
         */
        private fun identityOf(value: Value): String? = when (value) {
            is Value.Node -> "CASE WHEN ${value.nid} IS NOT NULL THEN ${comparable(Value.Json(jsonObject(listOf(" node" to scalar(value.nid))), ordered = true))} END"
            is Value.Relationship -> "CASE WHEN ${value.eid} IS NOT NULL THEN ${comparable(Value.Json(jsonObject(listOf(" relationship" to scalar(value.eid))), ordered = true))} END"
            // Each id as the node or relationship it is, so a list of them is the same as one that holds them among other values.
            is Value.Entities -> idTokens(value.ids, if (value.relationships) " relationship" else " node")
            is Value.Json -> value.identity
            else -> null
        }

        /**
         * What [take] takes of [list]'s identity, where it holds a node or a relationship by one: an item, a key's value, a slice;
         * [taken] is what it takes of the value. Taken from a node or a relationship, a property holds neither, so its identity
         * is [taken] itself. [item] says whether it takes one value, not a slice.
         */
        private fun identityOf(list: Value, taken: String, item: Boolean, take: (String) -> String): String? =
            (list as? Value.Json)?.identity?.let { identity ->
                if (!item) return@let take(identity)
                shared(identity, taken) { (i, t) ->
                    // A map's key, not a list's items' keys, as Oracle's lax path reads them.
                    "CASE WHEN ${typeOf(i)} = 'object' AND COALESCE(${propertyOf(i, " node")}, ${propertyOf(i, " relationship")}) IS NOT NULL THEN $t ELSE ${take(i)} END"
                }
            }

        /** [value] as JSON that compares by identity where it holds a node or a relationship, and as itself where it does not: see [identityOf]. */
        private fun identified(value: Value): String = identityOf(value) ?: comparable(json(value))

        /**
         * The identity of a map or a list of [values], built by [build] of each value's: see [Value.Json.identity]. Null where
         * none of them holds a node or a relationship.
         */
        private fun identityHolding(values: List<Value>, build: (List<String>) -> String): String? {
            if (values.none { identityOf(it) != null }) return null
            return comparable(Value.Json(build(values.map { identityOf(it) ?: json(it).sql }), ordered = true))
        }

        /** The value [expression] is where the statement gives it: written, or bound to a parameter or a parameter's key; null where it is not given, or is null. */
        private fun given(expression: Expression): Any? = when (expression) {
            is Expression.Literal -> expression.value
            is Expression.Parameter -> parameters.value(expression.name)
            is Expression.Property -> (expression.subject as? Expression.Parameter)?.let { (parameters.value(it.name) as? Map<*, *>)?.get(expression.key) }
            else -> null
        }

        private fun unary(unary: Expression.Unary, scope: Map<String, Value>): Value {
            if (unary.operator == Expression.Unary.Operator.NOT) requireCondition(unary.operand, scope, "NOT")
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
            val results = (case.alternatives.map { it.second } + listOfNotNull(case.otherwise)).map { it to expression(it, scope) }
            val conditions = case.alternatives.map { (key, _) ->
                if (subject != null) expression(key, scope).let { value -> equality(subject, value, plain = plain(case.subject!!, key, subject, value, scope)) }
                else bool(expression(key, scope))
            }
            fun written(value: (Value) -> String): String {
                val alternatives = conditions.zip(results).joinToString(" ") { (condition, result) -> "WHEN $condition THEN ${value(result.second)}" }
                val otherwise = case.otherwise?.let { " ELSE ${value(results.last().second)}" }.orEmpty()
                return "CASE $alternatives$otherwise END"
            }
            // Where a result holds a node or a relationship, the CASE holds it by identity too.
            val identity = if (results.any { identityOf(it.second) != null }) written(::identified) else null
            // On Oracle a list of nodes or relationships, read from their rows in a subquery, beside a result that reads the row, as
            // `CASE WHEN … THEN [r] ELSE [m.title] END`, closes the session (ORA-03113): its results are worked out in a subquery first.
            // Not a CASE that reads an aggregate, which the subquery would aggregate again, over its one row.
            val sql = if (aggregatesCaseSubqueries || results.none { it.second is Value.Entities } || contains(case, ::isAggregate)) written { comparable(json(it)) } else {
                val values = results.map { comparable(json(it.second)) }
                bound(*values.toTypedArray()) { bound ->
                    val byResult = results.map { it.second }.zip(bound).toMap()
                    written { byResult.getValue(it) }
                }
            }
            return Value.Json(
                sql, temporal = kindOf(results), items = itemKindOf(results), identity = identity, temporals = results.any { hasTemporals(it.second) },
                list = results.all { isList(it.second) }, itemLists = results.all { holdsLists(it.second) },
                text = results.all { isText(it.second) }, texts = results.all { holdsTexts(it.second) },
            )
        }

        private fun function(function: Expression.Function, scope: Map<String, Value>): Value {
            val arguments = function.arguments.map { expression(it, scope) }
            fun one(): Value = arguments.singleOrNull() ?: throw DrivineException("${function.name}() takes one argument.")
            val distinct = if (function.distinct) "DISTINCT " else ""
            return when (val name = function.name.lowercase()) {
                "properties" -> when (val subject = one()) {
                    is Value.Node, is Value.Relationship -> json(subject)
                    else -> subject.also { requireKind(it, Value.Known.MAP) { kind -> "properties() takes a node, a relationship or a map, not $kind, as in Neo4j." } }
                }
                "labels" -> (one() as? Value.Node)?.let { Value.Json(it.labels) } ?: throw DrivineException("labels() takes a node.")
                "type" -> (one() as? Value.Relationship)?.let { Value.Json(scalar(it.type), text = true) } ?: throw DrivineException("type() takes a relationship.")
                "id", "elementid" -> when (val subject = one()) {
                    is Value.Node -> Value.Json(scalar(subject.nid))
                    is Value.Relationship -> Value.Json(scalar(subject.eid))
                    else -> throw DrivineException("${function.name}() takes a node or a relationship.")
                }
                "length" -> (one() as? Value.Path)?.let { Value.Json(idCount(it.eids)) } ?: throw DrivineException("length() takes a path.")
                "nodes" -> (one() as? Value.Path)?.let { nodeList(it.nids) } ?: throw DrivineException("nodes() takes a path.")
                "relationships" -> (one() as? Value.Path)?.let { relationshipList(it.eids) } ?: throw DrivineException("relationships() takes a path.")
                "coalesce" -> Value.Json(
                    "COALESCE(${arguments.joinToString(", ") { nullIfJsonNull(comparable(json(it))) }})",
                    temporal = kindOf(function.arguments.zip(arguments)),
                    // A list of one kind where each is, or is null or an empty list written, as `coalesce(l, [])`: so Oracle reads its items
                    // by their places, as it reads l's (#347).
                    items = listKindOf(function.arguments.zip(arguments), ::itemsOf), itemItems = listKindOf(function.arguments.zip(arguments), ::itemItemsOf),
                    temporals = arguments.any(::hasTemporals),
                    text = arguments.all(::isText), texts = arguments.all(::holdsTexts),
                )
                "count" -> Value.Json(scalar("count($distinct${countable(one())})"))
                // collect(DISTINCT …) is collected in a WITH of its own, then kept once each: see aggregatesFirst.
                "collect" -> if (function.distinct) error("collect(DISTINCT …) is gathered by aggregatesFirst") else when (val gathered = one()) {
                    // Nodes or relationships are gathered as their ids, so a list of them is still of them.
                    is Value.Node -> Value.Entities(gatheredIds("CASE WHEN ${gathered.nid} IS NOT NULL THEN ${scalar(gathered.nid)} END"), false, collected(gathered))
                    is Value.Relationship -> Value.Entities(gatheredIds("CASE WHEN ${gathered.eid} IS NOT NULL THEN ${scalar(gathered.eid)} END"), true, collected(gathered))
                    // What holds a node or a relationship, a map or a list of them, is gathered beside its identity too.
                    else -> Value.Json(
                        collected(gathered), ordered = true, items = (gathered as? Value.Json)?.temporal, itemItems = itemsOf(gathered), list = true,
                        itemLists = isList(gathered), texts = isText(gathered), temporals = hasTemporals(gathered),
                        identity = identityOf(gathered)?.let { comparable(Value.Json(collect(it, rowOrder), ordered = true)) },
                    )
                }
                DISTINCT_ITEMS -> when (val list = one()) {
                    // Nodes or relationships once each by their ids, not their properties.
                    is Value.Entities -> Value.Entities(comparable(Value.Json(distinctItems(list.ids), ordered = true)), list.relationships)
                    // What holds a node or a relationship once each by its identity, not its properties.
                    is Value.Json -> list.identity?.let { identity ->
                        Value.Json(
                            firstOfEach(comparable(list), identity), ordered = true, items = list.items, identity = comparable(Value.Json(distinctItems(identity), ordered = true)),
                            itemItems = list.itemItems, list = true, itemLists = list.itemLists, texts = list.texts, temporals = list.temporals,
                        )
                    } ?: Value.Json(
                        distinctItems(comparable(list), temporal = holdsTemporals(list)), ordered = true, items = list.items, itemItems = list.itemItems, list = true, itemLists = list.itemLists,
                        texts = list.texts, temporals = list.temporals,
                    )
                    else -> Value.Json(distinctItems(comparable(json(list))), ordered = true)
                }
                "sum" -> Value.Json(sum(comparable(json(one())), distinct))
                "avg" -> Value.Json(scalar(float("avg($distinct${number(comparable(json(one())))})")))
                // Zoned values as the moments they are, whatever their offsets, and lists of them item by item so; of a temporal kind,
                // still of it, and a list of the kinds of list it was. A list the statement makes item by item, as an ORDER BY sorts it: a store's
                // JSON may put the shorter list first.
                "min", "max" -> {
                    val value = json(one())
                    val key = value.temporal?.takeIf { it.zoned }?.let { temporals.moment(text(comparable(value)), it) }
                        ?: value.items?.takeIf { it.zoned && value.list }?.let { momentsKey(comparable(value), it) }
                        ?: value.itemItems?.takeIf { it.zoned && value.itemLists }?.let { momentsKey(comparable(value), it, depth = 2) }
                        ?: if (extremesByListKey && isList(one())) listSortKey(comparable(value)) else null
                    Value.Json(
                        extreme(comparable(value), min = name == "min", key = key), temporal = value.temporal, items = value.items, itemItems = value.itemItems,
                        list = value.list, itemLists = value.itemLists, text = isText(value), temporals = value.temporals,
                    )
                }
                "range" -> {
                    if (arguments.size !in 2..3) throw DrivineException("range() takes a start, an end and a step.")
                    arguments.forEach { requireKind(it, Value.Known.INTEGER) { kind -> "range() takes integers, not $kind, as in Neo4j." } }
                    // A step written or given as 0 would never reach the end.
                    if (function.arguments.getOrNull(2)?.let(::known).let { it is Long && it == 0L || it is Int && it == 0 }) {
                        throw DrivineException("range() takes a step other than 0, as in Neo4j.")
                    }
                    val (from, to) = arguments.map { number(comparable(json(it))) }
                    Value.Json(range(from, to, arguments.getOrNull(2)?.let { number(comparable(json(it))) } ?: "1"), list = true)
                }
                "split" -> {
                    if (arguments.size != 2) throw DrivineException("split() takes a text and a separator.")
                    Value.Json(texts(*arguments.map { comparable(json(it)) }.toTypedArray()) { (text, separator) -> split(text, separator) }, list = true, texts = true)
                }
                "reverse" -> Value.Json(shared(comparable(json(one()))) { (x) ->
                    val e = alias("e")
                    "CASE ${typeOf(x)} WHEN 'string' THEN ${texts(x) { (t) -> reversedText(t) }} " +
                        "WHEN 'array' THEN (SELECT ${comparable(Value.Json(aggregateList(item(e), "${itemNo(e)} DESC"), ordered = true))} FROM ${elementsOf(one(), x, e)}) END"
                }, items = itemsOf(one()), itemItems = itemItemsOf(one()), list = isList(one()), itemLists = holdsLists(one()), text = isText(one()), texts = holdsTexts(one()),
                    temporals = holdsTemporals(one()))
                "tail" -> Value.Json(shared(comparable(json(one()))) { (x) ->
                    val e = alias("e")
                    "CASE WHEN ${typeOf(x)} = 'array' THEN (SELECT ${comparable(Value.Json(aggregateList(item(e), itemNo(e)), ordered = true))} FROM ${elementsOf(one(), x, e)} WHERE ${itemNo(e)} > 1) END"
                }, items = itemsOf(one()), itemItems = itemItemsOf(one()), list = isList(one()), itemLists = holdsLists(one()), texts = holdsTexts(one()),
                    temporals = holdsTemporals(one()))
                // A text is read as Cypher reads it, its case and the spaces around it aside; a number is true but for 0.
                "toboolean" -> Value.Json(shared(comparable(json(one()))) { (x) ->
                    "CASE WHEN ${typeOf(x)} = 'boolean' THEN $x " +
                        "WHEN ${typeOf(x)} = 'number' THEN CASE WHEN ${number(x)} = 0 THEN ${literalJson(false)} ELSE ${literalJson(true)} END " +
                        "WHEN LOWER(TRIM(${text(x)})) = 'true' THEN ${literalJson(true)} WHEN LOWER(TRIM(${text(x)})) = 'false' THEN ${literalJson(false)} END"
                })
                "isempty" -> Value.Json(shared(comparable(json(one()))) { (x) ->
                    val size = function("size", x)!!
                    boolJson("CASE WHEN ${typeOf(x)} = 'object' THEN ${jsonEquals(x, literalJson(emptyMap<String, Any>()))} WHEN ${typeOf(x)} IN ('array', 'string') THEN ${number(size)} = 0 END")
                })
                // A float, as Cypher gives these; round as Java rounds, half up.
                // The precision is read as a whole number toward zero, as Neo4j reads it: 1.7 places is 1.
                "round" -> when (arguments.size) {
                    3 -> roundingMode(function.arguments[2])?.let { mode ->
                        Value.Json(scalar(float(rounded(arguments[0], arguments[1], function.arguments[1]) { n, p -> roundTo(n, p, mode) })))
                    } ?: Value.Json(literalJson(null))
                    // To 0 places Neo4j rounds as round(x) does, half toward +infinity, as Java's Math.round; to more, half away from zero.
                    2 -> Value.Json(scalar(float(rounded(arguments[0], arguments[1], function.arguments[1], { roundWhole(it) }) { n, p -> roundPlaces(n, p) })))
                    else -> Value.Json(scalar(float(roundWhole(number(comparable(json(one())))))))
                }
                "floor", "ceil" -> Value.Json(scalar(float("${name.uppercase()}(${number(comparable(json(one())))})")))
                in MATH_FUNCTIONS -> Value.Json(scalar(math(name, listOf(number(comparable(json(one())))))))
                "atan2" -> if (arguments.size == 2) Value.Json(scalar(math(name, arguments.map { number(comparable(json(it))) })))
                    else throw DrivineException("atan2() takes two numbers.")
                // Java's constants, which Neo4j gives, written as the floats they are.
                "e", "pi" -> if (arguments.isEmpty()) Value.Json(literalJson(if (name == "e") Math.E else Math.PI))
                    else throw DrivineException("${function.name}() takes no argument.")
                "sign" -> Value.Json(scalar(signOf(number(comparable(json(one()))))))
                "ltrim", "rtrim" -> Value.Json(texts(comparable(json(one()))) { (t) -> scalar("${name.uppercase()}($t)") }, text = true)
                "randomuuid" -> if (arguments.isEmpty()) Value.Json(scalar(uuid())) else throw DrivineException("randomUUID() takes no argument.")
                // A position is counted from 0, as Cypher counts it, and SQL from 1.
                "substring" -> {
                    if (arguments.size !in 2..3) throw DrivineException("substring() takes a text, a start and a length.")
                    Value.Json(texts(comparable(json(arguments[0]))) { read ->
                        scalar(shared(read.single()) { (text) ->
                            val start = countOf(function, 1, "start", arguments, text)
                            val length = if (arguments.size == 3) ", ${countOf(function, 2, "length", arguments, text)}" else ""
                            // The greatest start is past any text, so it is kept as it is rather than counted past Postgres's integer.
                            val position = shared(start) { (s) -> "CASE WHEN $s < $MAX_COUNT THEN $s + 1 ELSE $s END" }
                            "SUBSTR($text, $position$length)"
                        })
                    }, text = true)
                }
                "left", "right" -> {
                    if (arguments.size != 2) throw DrivineException("${function.name}() takes a text and a length.")
                    Value.Json(texts(comparable(json(arguments[0]))) { read ->
                        scalar(shared(read.single()) { (text) ->
                            val count = countOf(function, 1, "length", arguments, text)
                            if (name == "left") "SUBSTR($text, 1, $count)" else rightText(text, count)
                        })
                    }, text = true)
                }
                "replace" -> {
                    if (arguments.size != 3) throw DrivineException("replace() takes a text, what to replace, and what with.")
                    // How long what to replace, and what with, are, in bytes, where the statement gives them.
                    val (searchBytes, replacementBytes) = function.arguments.drop(1).map { (given(it) as? String)?.toByteArray(Charsets.UTF_8)?.size }
                    Value.Json(texts(*arguments.map { comparable(json(it)) }.toTypedArray()) { (text, search, replacement) ->
                        scalar(replaceText(text, search, replacement, searchBytes, replacementBytes))
                    }, text = true)
                }
                "date", "localtime", "time", "localdatetime", "datetime", "duration" -> temporal(Temporal.ofFunction(name)!!, function, arguments, scope)
                "timestamp" -> if (arguments.isEmpty()) Value.Json(scalar(temporals.field(temporals.now(Temporal.DATE_TIME), Temporal.DATE_TIME, "epochMillis")!!.sql))
                    else throw DrivineException("timestamp() takes no argument.")
                // The moment the statement runs, however it is asked for: one statement is one moment here.
                in TEMPORAL_NOW -> Value.Json(scalar(temporals.now(Temporal.ofFunction(name.substringBefore('.'))!!)), temporal = Temporal.ofFunction(name.substringBefore('.')))
                in TEMPORAL_TRUNCATE -> {
                    val to = Temporal.ofFunction(name.substringBefore('.'))!!
                    if (arguments.size < 2) throw DrivineException("${function.name}() takes a unit and a value.")
                    if (arguments.size > 3) throw DrivineException("${function.name}() takes a unit, a value and a map of fields.")
                    val unit = (function.arguments[0] as? Expression.Literal)?.value as? String ?: unsupported("A truncation to a unit an expression gives")
                    val given = arguments[1] as? Value.Json ?: throw DrivineException("${function.name}() takes a unit and a value.")
                    val fields = function.arguments.getOrNull(2)?.let { truncationFields(function, unit, to, it, scope) }
                    fun cut(text: String, from: Temporal) = temporals.truncate(unit, text, from, to, fields)
                        ?: throw DrivineException("${function.name}() truncates to ${TemporalSql.UNITS.dropLast(1).joinToString(", ")} or ${TemporalSql.UNITS.last()}, not $unit.")
                    val text = text(comparable(given))
                    Value.Json(scalar(given.temporal?.takeIf { it.instant }?.let { cut(text, it) } ?: temporals.byKind(text) { from, t -> cut(t, from) }), temporal = to)
                }
                "duration.between", "duration.inmonths", "duration.indays", "duration.inseconds" -> {
                    if (arguments.size != 2) throw DrivineException("${function.name}() takes two temporal values.")
                    val unit = mapOf("duration.inmonths" to "months", "duration.indays" to "days", "duration.inseconds" to "seconds")[name]
                    val (from, to) = arguments.map { (it as? Value.Json) ?: throw DrivineException("${function.name}() takes two temporal values.") }
                    val (a, b) = text(comparable(from)) to text(comparable(to))
                    // A value whose kind the statement does not make is taken to be of the other's.
                    val (fromKind, toKind) = listOf(from, to).map { side -> side.temporal?.takeIf { it.instant } }
                    val kind = fromKind ?: toKind
                    val between = if (kind != null) temporals.between(a, b, kind, unit, toKind ?: kind)
                    else shared(b) { (y) -> temporals.byKind(a) { k, x -> temporals.between(x, y, k, unit) } }
                    Value.Json(scalar(between), temporal = Temporal.DURATION)
                }
                else -> {
                    // A function the store has no SQL for is named before its arguments are counted.
                    if (function(name, "NULL") == null) unsupported("The function ${function.name}()")
                    if (name == "tostring") textOfKind(function, one())
                    val argument = comparable(json(one()))
                    // An item taken from a list is of the kind its items are.
                    val taken = name == "head" || name == "last"
                    val result = shared(argument) { (x) -> function(name, x)!! }
                    Value.Json(
                        result,
                        temporal = if (taken) itemsOf(one()) else null,
                        items = if (taken) itemItemsOf(one()) else null,
                        identity = if (taken) identityOf(one(), result, true) { list -> shared(list) { (x) -> function(name, x)!! } } else null,
                        list = taken && holdsLists(one()), temporals = taken && itemTemporalsOf(one()),
                        text = if (taken) holdsTexts(one()) else name in TEXT_FUNCTIONS,
                    )
                }
            }
        }

        /**
         * Refuses [value], an argument, where the statement writes it as a value of a kind ([Value.Json.known]) other than
         * [allowed], with the [message] of that kind's noun, as Neo4j refuses it when it plans the statement. A value whose
         * kind the statement leaves open is compiled as before.
         */
        private fun requireKind(value: Value, allowed: Value.Known, message: (String) -> String) {
            val kind = (value as? Value.Json)?.known ?: return
            if (kind != allowed) throw DrivineException(message(kind.noun))
        }

        /**
         * Refuses `toString` of [value], the argument of [function], where the statement makes it a list or a map: Neo4j refuses
         * it when it plans the statement. A value whose kind the statement leaves open is compiled as before.
         */
        private fun textOfKind(function: Expression.Function, value: Value) {
            val written = function.arguments.single()
            val kind = when {
                isList(value) -> "list"
                written is Expression.MapLiteral || written is Expression.MapProjection -> "map"
                else -> return
            }
            throw DrivineException("${function.name}() takes a number, a text, a truth value or a temporal value, not a $kind, as in Neo4j.")
        }

        /**
         * The mode [mode] a `round()` names, as Java's `BigDecimal` names it, as Neo4j takes it: null where it is null, as
         * the rounding then is. Written, or a parameter's value: the SQL is worked out for the one mode.
         */
        private fun roundingMode(mode: Expression): RoundingMode? {
            if (mode !is Expression.Literal && mode !is Expression.Parameter) unsupported("A rounding mode an expression gives")
            val name = given(mode) ?: return null
            val known = RoundingMode.entries.firstOrNull { it.name == name }
                ?: throw DrivineException("round() rounds by CEILING, FLOOR, UP, DOWN, HALF_EVEN, HALF_UP, HALF_DOWN or UNNECESSARY, not $name.")
            // It fails where rounding would change the number, which SQL has no way to say in a query.
            if (known == RoundingMode.UNNECESSARY) unsupported("round() by UNNECESSARY")
            return known
        }

        /**
         * The start or length [what] of `substring()`, `left()` or `right()`, its argument [index], as a SQL whole number,
         * as Neo4j reads it. A float written in the statement, even a whole one such as 2.0, is refused, whatever the
         * text, as Neo4j refuses an argument it knows to be a float; one bound to a parameter or read from data is cut
         * toward zero, as Neo4j cuts it: 2.7 is 2, and -0.5 is 0. A number then below 0 or past 2147483647 is refused
         * where the text, [text] in SQL, is not null: when compiling, where the number and the text are given; and else
         * when the row is read, by [failure], on every store alike.
         */
        private fun countOf(function: Expression.Function, index: Int, what: String, arguments: List<Value>, text: String): String {
            val outOfRange = "${function.name}() takes a $what from 0 to $MAX_COUNT, as in Neo4j."
            val count = function.arguments[index]
            val negated = count is Expression.Unary && count.operator == Expression.Unary.Operator.NEGATE
            val written = if (negated) (count as Expression.Unary).operand else count
            val given = given(written)
            if (written is Expression.Literal && given is Double) throw DrivineException("${function.name}() takes an integer for its $what, not a float, as in Neo4j.")
            if (given is Number) {
                val cut = if (given is Long || given is Int) given.toLong() else kotlin.math.truncate(given.toDouble()).toLong()
                val whole = if (negated) -cut else cut
                if (whole in 0..MAX_COUNT) return "CAST($whole AS INTEGER)"
                val subject = function.arguments[0]
                // Where the text is given, it is null, so the function gives null, or it is refused.
                if (isGiven(subject)) {
                    if (given(subject) == null) return "CAST(NULL AS INTEGER)"
                    throw DrivineException(outOfRange)
                }
                // A failure of constants alone may be worked out before any row: this one reads the text.
                return "CASE WHEN $text IS NOT NULL THEN ${failure(outOfRange, "CASE WHEN $text IS NOT NULL THEN $whole END")} END"
            }
            // Read only beside a text that is not null, so a number past SQL's integer beside a null text is null, and a
            // constant out of range is not worked out before any row.
            return shared("CASE WHEN $text IS NOT NULL THEN ${temporalSql.trunc}(${number(comparable(json(arguments[index])))}) END") { (n) ->
                "CASE WHEN $n < 0 OR $n > $MAX_COUNT THEN ${failure(outOfRange, n)} ELSE CAST($n AS INTEGER) END"
            }
        }

        /** Whether the statement gives the value of [expression], as [given] reads it, null or not. */
        private fun isGiven(expression: Expression): Boolean =
            expression is Expression.Literal || expression is Expression.Parameter || (expression as? Expression.Property)?.subject is Expression.Parameter

        /** [value] as a whole number with its fraction cut off, toward zero, as Neo4j reads `round()`'s precision. */
        private fun truncatedWhole(value: Value): String = "CAST(${temporalSql.trunc}(${number(comparable(json(value)))}) AS INTEGER)"

        /**
         * `round()` of [value] to [precision] places, by [places] over the value as a SQL number and the precision as a whole
         * number toward zero, written in the statement as [written]: -0.5 is 0 places. Given [whole], to 0 places it rounds
         * by that instead. A precision below 0 or past [MAX_COUNT] fails, as Neo4j fails it: only on a row that rounds a
         * number by it, by [failure], on every store alike, whether it is written, negated or not, a parameter's value or
         * read from data. Neo4j reads the precision only when it rounds, so a null value rounds to null by any precision,
         * and with no row, nothing fails.
         *
         * Places known when compiling pick the rounding then, [whole] below 1 place: under an aggregate, where nothing is
         * bound, a CASE of both would write the number out in each, at each round() nested in another. Places from data
         * are told apart as the statement runs, in one CASE over the value and the places, where places out of range are
         * failed before either rounding: so the precision each rounding writes out, more than once on a store, is the places
         * alone, and the value is asked of its kind, whose test writes it out fewer times than its number does.
         */
        private fun rounded(value: Value, precision: Value, written: Expression, whole: ((String) -> String)? = null, places: (String, String) -> String): String {
            val negated = written is Expression.Unary && written.operator == Expression.Unary.Operator.NEGATE
            val given = (given(if (negated) (written as Expression.Unary).operand else written) as? Number)?.toDouble()
            if (given != null) {
                val signed = if (negated) -given else given
                val cut = kotlin.math.truncate(signed)
                // Not `cut in 0..MAX_COUNT`, which NaN fails: Neo4j casts NaN to 0 places, as Java casts it to a long.
                if (cut < 0 || cut > MAX_COUNT) {
                    val named = if (cut.isInfinite()) signed.toString() else java.math.BigDecimal(cut).toBigInteger().toString()
                    return outOfPlaces(comparable(json(value)), literal(named))
                }
                val number = number(comparable(json(value)))
                // Not `signed < 1`, which NaN fails.
                if (whole != null && !(signed >= 1)) return whole(number)
                return places(number, if (signed.isNaN()) "CAST(0 AS INTEGER)" else truncatedWhole(precision))
            }
            return shared(comparable(json(value)), "${temporalSql.trunc}(${number(comparable(json(precision)))})") { (x, t) ->
                val toWhole = whole?.let { "WHEN $t = 0 THEN ${it(number(x))} " } ?: ""
                // Out of range, places are not cast, which Postgres's integer would fail on beside a null value.
                "CASE ${toWhole}WHEN $t NOT BETWEEN 0 AND $MAX_COUNT THEN ${outOfPlaces(x, t)} ELSE ${places(number(x), "CAST($t AS INTEGER)")} END"
            }
        }

        /**
         * `round()` of the JSON value [x] to [precision] places out of range: it fails where [x] is a number, and is null
         * where it is not, as a null rounds to null by any precision in Neo4j.
         */
        private fun outOfPlaces(x: String, precision: String) =
            "CASE WHEN ${typeOf(x)} = 'number' THEN ${failure(PRECISION_OUT_OF_RANGE, precision)} END"

        /** The values [value] of a group, gathered as `collect` gathers them, as JSON. */
        private fun collected(value: Value) = collect(collectable(value), rowOrder)

        /** The ids [id] of a group, gathered as `collect` gathers them, as JSON that compares. */
        private fun gatheredIds(id: String) = comparable(Value.Json(collect(id, rowOrder), ordered = true))

        /** What `count` counts of a value: a node by its row, anything else by its value, nulls not counted. */
        private fun countable(value: Value): String = when (value) {
            is Value.Node -> value.nid
            is Value.Relationship -> value.eid
            is Value.Path -> value.nids
            is Value.Entities -> value.ids
            // What holds a node or a relationship by its identity, so `count(DISTINCT {x: r})` counts each relationship.
            else -> nullIfJsonNull(identified(value))
        }

        /**
         * What `collect` gathers of a value: nulls are left out, as Cypher's `collect` leaves them out, JSON's null
         * among them, as a list's item or a map's entry may be.
         */
        private fun collectable(value: Value): String = when {
            value is Value.Node -> "CASE WHEN ${value.nid} IS NOT NULL THEN ${json(value).sql} END"
            value is Value.Json && !value.ordered -> nullIfJsonNull(value.sql)
            else -> json(value).sql
        }

        // ----- Conversions -----

        /** A value as JSON, for a projection or a comparison. A node is its properties, its stamp among them, as drivine gives a node. */
        fun json(value: Value): Value.Json = when (value) {
            is Value.Json -> value
            is Value.Bool -> Value.Json(boolJson(value.sql))
            is Value.Node -> Value.Json(nodeJson(value))
            is Value.Relationship -> Value.Json("CASE WHEN ${value.eid} IS NOT NULL THEN ${value.props} END")
            is Value.Entities -> Value.Json(entitiesJson(value), ordered = true, list = true)
            // Each graph engine's driver gives a path as a value of its own, so a path is given by its parts.
            is Value.Path -> throw DrivineException("A path is not a value drivine gives the same on each store: use nodes(p), relationships(p) and length(p).")
        }

        /**
         * A value as a condition: a boolean as itself, JSON's `true` and `false` as theirs, anything else as null. One the statement
         * writes as a value of another kind ([Value.Json.known]) is refused, as Neo4j refuses it when it plans the statement.
         */
        fun bool(value: Value): String = when (value) {
            is Value.Bool -> value.sql
            is Value.Json -> value.known?.takeIf { it != Value.Known.BOOLEAN }?.let { throw DrivineException("${it.noun.replaceFirstChar(Char::uppercase)} is not a condition, as in Neo4j.") }
                ?: boolOf(comparable(value))
            else -> throw DrivineException("A node, a relationship or a path is not a condition.")
        }

        /**
         * What an order sorts a value by: JSON's order for a value, a node by its properties. JSON's null, as a map
         * or a list the statement builds holds it, is SQL's null, which comes last, as a graph engine orders null.
         */
        private fun sortable(value: Value): List<String> = sortTerm(value).let { (carried, sorts) -> sorts(carried) }

        /**
         * What [sortable] sorts [value] by, in two: the value as a column carries it, and the terms that sort it, of that column.
         * A value whose kind the statement leaves open sorts as [openSortKey] has it, where its kind comes first; one it makes a
         * truth value, a temporal value, a text, a list or a node is of one kind, and sorts by one term alone: a zoned value or a
         * list of them by its moments, another list by [listSortKey], any other by [sortKey].
         */
        private fun sortTerm(value: Value): Pair<String, (String) -> List<String>> = when {
            value is Value.Bool -> value.sql to { listOf(it) }
            // A zoned value the statement makes sorts as the moment it is, whatever its offset.
            value is Value.Json && value.temporal?.zoned == true -> comparable(value) to { listOf(temporals.moment(text(it), value.temporal)) }
            // A list of zoned values the statement makes sorts item by item as the moments they are: of one kind, a list, it needs no
            // rank of its kind.
            value is Value.Json && value.list && value.items?.zoned == true -> comparable(value) to { listOf(momentsKey(it, value.items)) }
            value is Value.Json && value.itemLists && value.itemItems?.zoned == true ->
                comparable(value) to { listOf(momentsKey(it, value.itemItems, depth = 2)) }
            // A list the statement makes sorts as Cypher sorts lists, item by item; any other value as the store sorts its JSON.
            isList(value) -> comparable(json(value)) to { listOf(listSortKey(it)) }
            value !is Value.Json || value.temporal != null || isText(value) -> comparable(json(value)) to { listOf(sortKey(it)) }
            else -> comparable(json(value)) to { openSortKey(it) }
        }
    }

    /** A node as JSON: its properties, its stamp among them, as drivine gives a node; null where there is no node. */
    protected abstract fun nodeJson(node: Value.Node): String

    /** The rows a part of a statement reads: its sources and joins, its conditions, and the variables it binds. */
    private class Select(private val outer: Map<String, Value>) {
        val sources = mutableListOf<String>()
        val where = mutableListOf<String>()
        val bound = linkedMapOf<String, Value>()

        /**
         * Whether a variable-length walk is among its sources, or among those of a `WITH` it reads: a term it sorts by is
         * then bound in no subquery (see `project`).
         */
        var walks = false

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

        /** A column of the query [PENDING] stands for. */
        val PENDING_COLUMN = Regex("${PENDING}\\.(\\w+)")

        fun literal(text: String) = "'${text.replace("'", "''")}'"

        /**
         * The most arguments a function takes on Postgres, and on the SQLite Drivine ships: a list or a map written by one
         * call of a function past them, as a literal of more than 100 items or 50 entries is, is written in parts (#332).
         */
        const val MOST_ARGUMENTS = 100

        /** What `round()` fails with, as Neo4j fails it, a precision below 0 or past [MAX_COUNT]. */
        const val PRECISION_OUT_OF_RANGE = "round() takes a precision from 0 to 2147483647, as in Neo4j."

        /**
         * The greatest start or length `substring()`, `left()` and `right()` take, and the greatest precision `round()`
         * takes, as Neo4j takes them: Java's greatest `int`.
         */
        const val MAX_COUNT = Int.MAX_VALUE.toLong()

        /**
         * The most bytes of JSON's text, in UTF-8, an item of a parameter's list is written in the statement in, as
         * `givenItemwise` writes it: Oracle takes no text literal past 4,000 bytes (ORA-01704), a text's JSON is written in a
         * literal of its own, and each quote in it is doubled there.
         */
        const val LITERAL_BYTES = 1000

        /** [sql] as the select-list entry of the column [name]: the column itself where [sql] reads one of that name. */
        fun named(sql: String, name: String) = if (sql.endsWith(".$name")) sql else "$sql AS $name"

        fun indent(text: String) = text.prependIndent("    ")

        fun unsupported(construct: String): Nothing = throw DrivineException("$construct is Cypher, and is not compiled to SQL yet.")

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

        /**
         * The row a `CALL { … }` that writes and returns is written for, followed by its depth, from 1 to [NESTING]:
         * each row it gives is that row with what it returns, whatever the call bound to the row's names inside it.
         */
        const val CALLER = "cy_caller"

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

        /**
         * The format a float from 10^-3 up to 10^7 is written in by `TO_CHAR`, as Java writes a double: no exponent, a digit
         * before the point and one after it at least, and as many more as its shortest decimal has.
         */
        const val PLAIN_FLOAT = "FM9999990.0999999999999999999999"

        /** How long a value [shared] binds may be written where it is read, in characters. */
        const val SHARED_ABOVE = 200

        /** The functions of a temporal kind that give the moment the statement runs. */
        val TEMPORAL_NOW = listOf("date", "localtime", "time", "localdatetime", "datetime").flatMap { kind -> listOf("statement", "transaction", "realtime").map { "$kind.$it" } }

        /** The fields a map makes a moment or a time of day of. */
        val INSTANT_MAP_FIELDS = setOf("year", "month", "day", "hour", "minute", "second", "millisecond", "microsecond", "nanosecond", "timezone")

        /** The fields a map makes a duration of. */
        val DURATION_MAP_FIELDS = setOf("years", "quarters", "months", "weeks", "days", "hours", "minutes", "seconds", "milliseconds", "microseconds", "nanoseconds")

        /** The functions of a temporal kind that truncate a value to a unit. */
        val TEMPORAL_TRUNCATE = listOf("date", "localtime", "time", "localdatetime", "datetime").map { "$it.truncate" }

        /** The fields of a date a truncation's map may give, as Neo4j takes them. */
        val TRUNCATION_DATE_FIELDS = setOf("year", "month", "day", "dayOfWeek", "week", "weekYear", "quarter", "dayOfQuarter", "ordinalDay")

        /** The fields of a time of day a truncation's map may give, as Neo4j takes them. */
        val TRUNCATION_TIME_FIELDS = setOf("hour", "minute", "second", "millisecond", "microsecond", "nanosecond")

        /** The fields a truncation's map may give that the stores have no SQL for yet: nanoseconds, and the fields of a week or a quarter. */
        val TRUNCATION_FIELDS_NOT_COMPILED = setOf("nanosecond", "week", "weekYear", "quarter", "dayOfQuarter", "ordinalDay")

        /** The values each field a truncation's map gives may take, as Neo4j takes them, but for the year, which runs past what a store's text holds. */
        val TRUNCATION_RANGES = mapOf(
            "month" to 1L..12L, "day" to 1L..31L, "dayOfWeek" to 1L..7L, "hour" to 0L..23L, "minute" to 0L..59L, "second" to 0L..59L,
            "millisecond" to 0L..999L, "microsecond" to 0L..999_999L,
        )

        /** The least of the year and each field of a date by the calendar's other fields, which a field given as null is. */
        val CALENDAR_LEAST = mapOf("year" to 0L, "week" to 1L, "dayOfWeek" to 1L, "ordinalDay" to 1L, "quarter" to 1L, "dayOfQuarter" to 1L)

        /** The least of each field a truncation's map gives that is not 0, which a field given as null is. */
        val TRUNCATION_LEAST = mapOf("month" to 1, "day" to 1, "dayOfWeek" to 1)

        val AGGREGATES = setOf("count", "collect", "sum", "avg", "min", "max")


        /** The function that keeps each item of a list once, where it is first: see [Writer.aggregatesFirst]. No statement can name it. */
        const val DISTINCT_ITEMS = "#distinct items"


        /** openCypher's functions of one number that [math] works out, as Neo4j names them, lowered. */
        val MATH_FUNCTIONS = setOf("exp", "log", "log10", "sqrt", "sin", "cos", "tan", "cot", "asin", "acos", "atan", "degrees", "radians", "haversin")

        /** The functions of one value a store writes ([function]) whose result is a text, or null: see [Value.Json.text]. */
        val TEXT_FUNCTIONS = setOf("tostring", "toupper", "tolower", "trim")

        /** The functions whose value, null aside, is a number, a text, a truth value or a temporal value: no list or map. */
        val SCALAR_FUNCTIONS = setOf(
            "size", "length", "count", "sum", "avg", "tostring", "tointeger", "tofloat", "toboolean", "tolower", "toupper", "trim", "ltrim", "rtrim",
            "substring", "left", "right", "replace", "id", "elementid", "type", "round", "floor", "ceil", "sign", "atan2", "e", "pi", "randomuuid",
            "timestamp", "isempty", "date", "localtime", "time", "localdatetime", "datetime", "duration",
            "duration.between", "duration.inmonths", "duration.indays", "duration.inseconds",
        ) + MATH_FUNCTIONS + TEMPORAL_NOW + TEMPORAL_TRUNCATE

        /** The functions whose value holds no null in a list or a map: see [Value.Json.flat]. */
        val FLAT_FUNCTIONS = SCALAR_FUNCTIONS + setOf("labels", "keys", "nodes", "relationships", "range", "split")

        /** The functions whose value holds a null in a list or a map only where an argument does: see [Value.Json.flat]. */
        val CARRYING_FUNCTIONS = setOf("collect", "min", "max", "coalesce", "head", "last", "tail", "reverse", "properties", DISTINCT_ITEMS)

        /**
         * The doubles Java's `Math.toDegrees` and `Math.toRadians` multiply by, as Neo4j's `degrees` and `radians` do: a
         * store that works out 180 / π otherwise, or divides, can differ in the last digit, as at `degrees(0.1)`.
         */
        const val RADIANS_TO_DEGREES = "57.29577951308232"
        const val DEGREES_TO_RADIANS = "0.017453292519943295"

        /** Whether [expression] is a call of `randomUUID()`, the one function compiled here that gives a value anew each time it is called. */
        fun isAnew(expression: Expression): Boolean = expression is Expression.Function && expression.name.equals("randomUUID", ignoreCase = true)

        /** What the name of a variable begins with that holds a randomUUID() made first, for each row: see `anewFirst`. Named as no variable is, unless quoted so. */
        const val ANEW = "#anew"

        /**
         * Whether [expression] holds a call of randomUUID() anywhere: in a comprehension's, a quantifier's, a reduce's or a
         * pattern comprehension's own expressions too, where [contains] does not look. Not in a subquery's query.
         */
        fun holdsAnew(expression: Expression): Boolean = when (expression) {
            is Expression.ListComprehension -> listOfNotNull(expression.list, expression.where, expression.projection).any(::holdsAnew)
            is Expression.Quantifier -> listOfNotNull(expression.list, expression.where).any(::holdsAnew)
            is Expression.Reduce -> listOf(expression.initial, expression.list, expression.expression).any(::holdsAnew)
            is Expression.PatternComprehension -> listOfNotNull(expression.where, expression.projection).any(::holdsAnew)
            else -> isAnew(expression) || children(expression).any(::holdsAnew)
        }

        /** Whether a clause of [query], or of a query it calls, holds a call of randomUUID() ([holdsAnew]) in its expressions. */
        fun holdsAnew(query: Query): Boolean = (listOf(query.clauses) + query.unions.map { it.clauses }).flatten().any { clause ->
            fun projected(projection: Projection) = (projection.items.map { it.expression } + projection.orderBy.map { it.expression }).any(::holdsAnew)
            when (clause) {
                is Clause.With -> projected(clause.projection) || clause.where?.let(::holdsAnew) == true
                is Clause.Return -> projected(clause.projection)
                is Clause.Unwind -> holdsAnew(clause.list)
                is Clause.Match -> clause.where?.let(::holdsAnew) == true
                is Clause.Call -> holdsAnew(clause.query)
                else -> false
            }
        }

        /**
         * [expression] with each randomUUID() in a comprehension's, a quantifier's, a reduce's or a pattern comprehension's own
         * expressions read beside the variable [seed], made anew for each row: `CASE WHEN seed IS NULL THEN null ELSE randomUUID() END`,
         * which is randomUUID() still, as [seed] is never null. So the subquery that works the body out reads the row, and a
         * store works it out again for each row, as it does not for a subquery that reads nothing of it (#412). One so read
         * already, beside one of the variables the compiler [made], is left as it is ([isSeeded]). Each item's variable is read beside [seed] too ([own]), the path's of a pattern
         * comprehension, named here where it has none: read beside the row's alone, a value bound in a subquery ([shared]) reads
         * nothing of the item, and Postgres and Oracle worked it out once for all of them, so `[x IN range(1, 20) WHERE left(randomUUID(), 1) < '8' | x]`
         * kept every item or none; and an aggregate of a pattern comprehension's body that reads only the row's columns is an
         * aggregate of the row's query, which Postgres refuses and SQLite works out over every row.
         */
        fun seeded(expression: Expression, seed: String, made: Set<String>, inBody: Boolean = false, own: List<String> = emptyList()): Expression = replaced(expression) { e ->
            fun body(part: Expression, variable: String) = seeded(part, seed, made, inBody = true, own = own + variable)
            fun same(part: Expression) = seeded(part, seed, made, inBody, own)
            when {
                isSeeded(e, made) -> e
                inBody && isAnew(e) -> {
                    val condition = (listOf(seed) + own).map<String, Expression> { Expression.Unary(Expression.Unary.Operator.IS_NULL, Expression.Variable(it)) }
                        .reduce { a, b -> Expression.Binary(Expression.Binary.Operator.AND, a, b) }
                    Expression.Case(null, listOf(condition to Expression.Literal(null)), e)
                }
                e is Expression.ListComprehension ->
                    e.copy(list = same(e.list), where = e.where?.let { body(it, e.variable) }, projection = e.projection?.let { body(it, e.variable) })
                e is Expression.Quantifier -> e.copy(list = same(e.list), where = e.where?.let { body(it, e.variable) })
                e is Expression.Reduce -> e.copy(initial = same(e.initial), list = same(e.list), expression = body(e.expression, e.variable))
                // Named only where its body holds one, so a statement without randomUUID() is compiled as it was.
                e is Expression.PatternComprehension && listOfNotNull(e.where, e.projection).any(::holdsAnew) -> {
                    val path = e.pattern.variable ?: "$seed path${own.size}"
                    e.copy(pattern = e.pattern.copy(variable = path), where = e.where?.let { body(it, path) }, projection = body(e.projection, path))
                }
                else -> null
            }
        }

        /**
         * Whether [expression] is a randomUUID() read beside a variable made first for the row, as [seeded] reads one: one of
         * those the compiler [made], never a variable of the statement's named alike, `` CASE WHEN `#anew1` IS NULL THEN null ELSE randomUUID() END ``,
         * which was taken for one and not read beside each item (#479).
         */
        fun isSeeded(expression: Expression, made: Set<String>): Boolean {
            if (expression !is Expression.Case) return false
            var condition = expression.alternatives.singleOrNull()?.first
            while (condition is Expression.Binary && condition.operator == Expression.Binary.Operator.AND) condition = condition.left
            return condition is Expression.Unary && condition.operator == Expression.Unary.Operator.IS_NULL &&
                (condition.operand as? Expression.Variable)?.name in made
        }

        fun isAggregate(expression: Expression): Boolean =
            expression == Expression.CountStar || (expression is Expression.Function && expression.name.lowercase() in AGGREGATES)

        /**
         * Whether [expression], an item that does not aggregate, is a key that an item that aggregates may read, as Neo4j
         * takes one there: a variable, or a variable's property. Not `x.a.b`, `x['a']` or `toUpper(x.a)`.
         */
        fun isPlainKey(expression: Expression): Boolean =
            expression is Expression.Variable || (expression is Expression.Property && expression.subject is Expression.Variable)

        /**
         * The lists [expression] reads item by item, in a subquery of its own: a comprehension's, a quantifier's or a reduce's,
         * what `IN` searches, what a slice, `reverse` or `tail` takes items of.
         */
        fun iterated(expression: Expression): List<Expression> = when (expression) {
            is Expression.ListComprehension -> listOf(expression.list)
            is Expression.Quantifier -> listOf(expression.list)
            is Expression.Reduce -> listOf(expression.list)
            is Expression.Slice -> listOf(expression.subject)
            is Expression.Binary -> if (expression.operator == Operator.IN) listOf(expression.right) else emptyList()
            is Expression.Function -> if (expression.name.lowercase() in setOf("reverse", "tail")) expression.arguments.take(1) else emptyList()
            else -> emptyList()
        }

        /** Whether [expression] holds an expression [test] holds of, outside a nested query of its own. */
        fun contains(expression: Expression, test: (Expression) -> Boolean): Boolean {
            if (test(expression)) return true
            return children(expression).any { contains(it, test) }
        }

        /**
         * Whether [expression] holds a subquery anywhere: in a comprehension's, a quantifier's or a pattern's
         * condition or properties too, where [contains] does not look.
         */
        fun holdsSubquery(expression: Expression): Boolean = when (expression) {
            is Expression.Subquery -> true
            is Expression.ListComprehension -> listOfNotNull(expression.list, expression.where, expression.projection).any(::holdsSubquery)
            is Expression.Quantifier -> listOfNotNull(expression.list, expression.where).any(::holdsSubquery)
            is Expression.Reduce -> listOf(expression.initial, expression.list, expression.expression).any(::holdsSubquery)
            is Expression.PatternComprehension -> holdsSubquery(expression.pattern) || listOfNotNull(expression.where, expression.projection).any(::holdsSubquery)
            is Expression.PatternPredicate -> holdsSubquery(expression.pattern)
            is Expression.Exists -> expression.patterns.any(::holdsSubquery) || expression.where?.let(::holdsSubquery) == true
            else -> children(expression).any(::holdsSubquery)
        }

        /** Whether [pattern] holds a subquery in its parts' properties or a quantified path pattern's condition. */
        fun holdsSubquery(pattern: Pattern): Boolean =
            pattern.nodes.any { it.properties?.let(::holdsSubquery) == true } ||
                pattern.relationships.any { relationship ->
                    relationship.properties?.let(::holdsSubquery) == true ||
                        relationship.group?.let { holdsSubquery(it.pattern) || it.where?.let(::holdsSubquery) == true } == true
                }

        /**
         * [expression] with each function's name in lower case, in a comprehension's, a quantifier's or a reduce's body too: the
         * same for two expressions that are the same to Neo4j, which names a function in any case. For comparing only.
         */
        fun keyOf(expression: Expression): Expression = replaced(expression) { part ->
            when (part) {
                is Expression.Function -> part.copy(name = part.name.lowercase(), arguments = part.arguments.map(::keyOf))
                is Expression.ListComprehension -> part.copy(list = keyOf(part.list), where = part.where?.let(::keyOf), projection = part.projection?.let(::keyOf))
                is Expression.Quantifier -> part.copy(list = keyOf(part.list), where = part.where?.let(::keyOf))
                is Expression.Reduce -> part.copy(initial = keyOf(part.initial), list = keyOf(part.list), expression = keyOf(part.expression))
                else -> null
            }
        }

        /** Whether [expression], outside its aggregates, holds one of [parts], in a comprehension's, a quantifier's or a reduce's body too. */
        fun readsOutsideAggregates(expression: Expression, parts: Set<Expression>): Boolean = !isAggregate(expression) && (
            expression in parts || (children(expression) + when (expression) {
                is Expression.ListComprehension -> listOfNotNull(expression.where, expression.projection)
                is Expression.Quantifier -> listOfNotNull(expression.where)
                is Expression.Reduce -> listOf(expression.expression)
                else -> emptyList()
            }).any { readsOutsideAggregates(it, parts) }
            )

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

        /**
         * The variables [expression] reads of the scope it is in: those [variablesIn] gives, but for a name that a comprehension,
         * a quantifier or a reduce binds of its own and reads only inside, so `[m IN [p.name] | m]` reads `p` alone. A pattern's
         * new variables can't be named so in Cypher, nor a subquery's, so a pattern's are as [variablesIn] gives them.
         */
        fun variablesReadIn(expression: Expression): Set<String> = when (expression) {
            is Expression.Variable -> setOf(expression.name)
            is Expression.MapProjection -> setOf(expression.variable) +
                expression.elements.filterIsInstance<Expression.MapProjection.Element.Variable>().map { it.name } + children(expression).flatMap(::variablesReadIn)
            is Expression.ListComprehension -> variablesReadIn(expression.list) + (listOfNotNull(expression.where, expression.projection).flatMap(::variablesReadIn) - expression.variable)
            is Expression.Quantifier -> variablesReadIn(expression.list) + (expression.where?.let(::variablesReadIn).orEmpty() - expression.variable)
            is Expression.Reduce -> variablesReadIn(expression.initial) + variablesReadIn(expression.list) +
                (variablesReadIn(expression.expression) - expression.accumulator - expression.variable)
            is Expression.PatternComprehension -> variablesIn(expression.pattern) + listOfNotNull(expression.where, expression.projection).flatMap(::variablesReadIn)
            is Expression.PatternPredicate -> variablesIn(expression.pattern)
            is Expression.Exists -> expression.patterns.flatMap(::variablesIn).toSet() + expression.where?.let(::variablesReadIn).orEmpty()
            else -> children(expression).flatMap(::variablesReadIn).toSet()
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
                pattern.relationships.flatMap { relationship ->
                    listOfNotNull(relationship.variable) + (relationship.properties?.let(::variablesIn) ?: emptySet()) +
                        (relationship.group?.let { variablesIn(it.pattern) + (it.where?.let(::variablesIn) ?: emptySet()) } ?: emptySet())
                }

        /** [expression] with each part [replacement] gives a replacement for replaced, among those [children] finds. */
        fun replaced(expression: Expression, replacement: (Expression) -> Expression?): Expression {
            replacement(expression)?.let { return it }
            fun r(part: Expression) = replaced(part, replacement)
            return when (expression) {
                is Expression.Property -> expression.copy(subject = r(expression.subject))
                is Expression.HasLabels -> expression.copy(subject = r(expression.subject))
                is Expression.ListLiteral -> expression.copy(items = expression.items.map(::r))
                is Expression.MapLiteral -> expression.copy(entries = expression.entries.map { (key, value) -> key to r(value) })
                is Expression.MapProjection -> expression.copy(
                    elements = expression.elements.map { if (it is Expression.MapProjection.Element.Entry) it.copy(value = r(it.value)) else it },
                )
                is Expression.Binary -> expression.copy(left = r(expression.left), right = r(expression.right))
                is Expression.Unary -> expression.copy(operand = r(expression.operand))
                is Expression.Index -> expression.copy(subject = r(expression.subject), index = r(expression.index))
                is Expression.Slice -> expression.copy(subject = r(expression.subject), from = expression.from?.let(::r), to = expression.to?.let(::r))
                is Expression.Function -> expression.copy(arguments = expression.arguments.map(::r))
                is Expression.Case -> expression.copy(
                    subject = expression.subject?.let(::r),
                    alternatives = expression.alternatives.map { (condition, result) -> r(condition) to r(result) },
                    otherwise = expression.otherwise?.let(::r),
                )
                is Expression.ListComprehension -> expression.copy(list = r(expression.list))
                is Expression.Reduce -> expression.copy(initial = r(expression.initial), list = r(expression.list))
                is Expression.Quantifier -> expression.copy(list = r(expression.list))
                else -> expression
            }
        }

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

        /**
         * Each text [query] holds anywhere, as far as its first space, added to [texts]: each of its variables' names, and
         * more (an alias, a label, a key, a parameter's or a function's name, a text it gives), which is as much as a name made
         * for it, with no space, needs to keep clear of: see `Writer.hidden`.
         */
        fun textsIn(query: Query, texts: MutableSet<String>) = Texts(texts).query(query)

        /** What [textsIn] adds each text to, [texts], as it reads a statement through. */
        private class Texts(val texts: MutableSet<String>) {
            fun text(text: String?) {
                if (text != null) texts += text.substringBefore(' ')
            }

            fun value(value: Any?) {
                when (value) {
                    is String -> text(value)
                    is Iterable<*> -> value.forEach(::value)
                    is Map<*, *> -> value.forEach { (key, item) -> value(key); value(item) }
                    is Array<*> -> value.forEach(::value)
                }
            }

            fun query(query: Query) {
                clauses(query.clauses)
                query.unions.forEach { clauses(it.clauses) }
            }

            fun clauses(clauses: List<Clause>): Unit = clauses.forEach { clause ->
                when (clause) {
                    is Clause.Match -> { clause.patterns.forEach(::pattern); expression(clause.where) }
                    is Clause.Unwind -> { expression(clause.list); text(clause.variable) }
                    is Clause.With -> { projection(clause.projection); expression(clause.where) }
                    is Clause.Create -> clause.patterns.forEach(::pattern)
                    is Clause.Merge -> { pattern(clause.pattern); (clause.onCreate + clause.onMatch).forEach(::setItem) }
                    is Clause.Set -> clause.items.forEach(::setItem)
                    is Clause.Remove -> clause.items.forEach { item ->
                        when (item) {
                            is RemoveItem.Property -> { text(item.variable); text(item.key) }
                            is RemoveItem.Labels -> { text(item.variable); item.labels.forEach(::text) }
                        }
                    }
                    is Clause.Delete -> clause.expressions.forEach(::expression)
                    is Clause.Call -> { query(clause.query); clause.imports?.forEach(::text) }
                    is Clause.Foreach -> { text(clause.variable); expression(clause.list); clauses(clause.clauses) }
                    is Clause.Return -> projection(clause.projection)
                }
            }

            fun setItem(item: SetItem) {
                when (item) {
                    is SetItem.Property -> { text(item.variable); text(item.key); expression(item.value) }
                    is SetItem.Replace -> { text(item.variable); expression(item.value) }
                    is SetItem.Add -> { text(item.variable); expression(item.value) }
                    is SetItem.Labels -> { text(item.variable); item.labels.forEach(::text) }
                }
            }

            fun projection(projection: Projection) {
                projection.items.forEach { expression(it.expression); text(it.name) }
                projection.orderBy.forEach { expression(it.expression) }
                expression(projection.skip)
                expression(projection.limit)
            }

            fun pattern(pattern: Pattern) {
                text(pattern.variable)
                pattern.nodes.forEach { node -> text(node.variable); node.labels.forEach(::text); expression(node.properties) }
                pattern.relationships.forEach { relationship ->
                    text(relationship.variable)
                    relationship.types.forEach(::text)
                    expression(relationship.properties)
                    relationship.group?.let { pattern(it.pattern); expression(it.where) }
                }
            }

            fun expression(expression: Expression?) {
                when (expression) {
                    null, Expression.CountStar -> {}
                    is Expression.Literal -> value(expression.value)
                    is Expression.Parameter -> text(expression.name)
                    is Expression.Variable -> text(expression.name)
                    is Expression.Property -> { expression(expression.subject); text(expression.key) }
                    is Expression.HasLabels -> { expression(expression.subject); expression.labels.forEach(::text) }
                    is Expression.MapLiteral -> expression.entries.forEach { (key, value) -> text(key); expression(value) }
                    is Expression.MapProjection -> {
                        text(expression.variable)
                        expression.elements.forEach { element ->
                            when (element) {
                                is Expression.MapProjection.Element.Selected -> text(element.key)
                                is Expression.MapProjection.Element.Entry -> { text(element.key); expression(element.value) }
                                is Expression.MapProjection.Element.Variable -> text(element.name)
                                Expression.MapProjection.Element.All -> {}
                            }
                        }
                    }
                    is Expression.Function -> { text(expression.name); expression.arguments.forEach(::expression) }
                    is Expression.ListComprehension -> {
                        text(expression.variable); expression(expression.list); expression(expression.where); expression(expression.projection)
                    }
                    is Expression.Quantifier -> { text(expression.variable); expression(expression.list); expression(expression.where) }
                    is Expression.Reduce -> {
                        text(expression.accumulator); text(expression.variable)
                        expression(expression.initial); expression(expression.list); expression(expression.expression)
                    }
                    is Expression.PatternComprehension -> { pattern(expression.pattern); expression(expression.where); expression(expression.projection) }
                    is Expression.PatternPredicate -> pattern(expression.pattern)
                    is Expression.Exists -> { expression.patterns.forEach(::pattern); expression(expression.where) }
                    is Expression.Subquery -> query(expression.query)
                    // No else: a kind of expression added later is not compiled until it is read here too.
                    is Expression.ListLiteral, is Expression.Binary, is Expression.Unary, is Expression.Index, is Expression.Slice, is Expression.Case ->
                        children(expression).forEach(::expression)
                }
            }
        }
    }

    // ----- Columns of a value -----

    /** The select-list entries that carry this value out of a query, under [name]. */
    private fun Value.columns(name: String): List<String> = when (this) {
        is Value.Json -> listOf("$sql AS $name") + listOfNotNull(identity?.takeIf { identities }?.let { "$it AS ${name}_identity" })
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
            is Value.Json -> copy(sql = "$prefix$base", identity = identity?.takeIf { identities }?.let { "$prefix${base}_identity" })
            is Value.Entities -> Value.Entities("$prefix$base", relationships, flat = flat)
            is Value.Bool -> Value.Bool("$prefix$base")
            is Value.Node -> Value.Node("$prefix${base}_nid", "$prefix${base}_labels", "$prefix${base}_props", "$prefix${base}_stamp")
            is Value.Relationship -> Value.Relationship("$prefix${base}_eid", "$prefix${base}_src", "$prefix${base}_dst", "$prefix${base}_type", "$prefix${base}_props")
            is Value.Path -> Value.Path("$prefix${base}_nids", "$prefix${base}_eids")
        }
    }

    /** This value with [PENDING] read as [alias]. */
    private fun Value.withAlias(alias: String): Value = mapSql { it.replace(PENDING, alias) }

    /**
     * This value, whose columns are named for a query not yet named ([PENDING]), read from the rows named [name] that
     * a [lateral] join gives, of the columns [columns].
     */
    private fun Value.laterally(name: String, columns: List<Pair<String, Boolean>>): Value {
        val json = columns.toMap()
        return mapSql { sql -> PENDING_COLUMN.replace(sql) { lateralColumn(name, it.groupValues[1], json.getValue(it.groupValues[1])) } }
    }

    /** This value with each piece of its SQL [transform]ed. */
    private fun Value.mapSql(transform: (String) -> String): Value = when (this) {
        is Value.Json -> copy(sql = transform(sql), identity = identity?.let(transform))
        is Value.Entities -> copy(ids = transform(ids), gathered = gathered?.let(transform))
        is Value.Bool -> copy(sql = transform(sql))
        is Value.Node -> Value.Node(transform(nid), transform(labels), transform(props), transform(stamp))
        is Value.Relationship -> Value.Relationship(transform(eid), transform(src), transform(dst), transform(type), transform(props))
        is Value.Path -> Value.Path(transform(nids), transform(eids))
    }

    /** The columns [columns] gives this value under [name], each named and whether it holds JSON, in the same order. */
    private fun Value.columnKinds(name: String): List<Pair<String, Boolean>> = when (this) {
        is Value.Json -> listOf(name to true) + listOfNotNull(identity?.takeIf { identities }?.let { "${name}_identity" to true })
        is Value.Entities -> listOf(name to true)
        is Value.Bool -> listOf(name to false)
        is Value.Node -> listOf("${name}_nid" to false, "${name}_labels" to true, "${name}_props" to true, "${name}_stamp" to false)
        is Value.Relationship -> listOf("${name}_eid" to false, "${name}_src" to false, "${name}_dst" to false, "${name}_type" to false, "${name}_props" to true)
        is Value.Path -> listOf("${name}_nids" to false, "${name}_eids" to false)
    }

    /** What a projection that aggregates is grouped by, to keep this value: a node by its columns. */
    private fun Value.groupKeys(): List<String> = when (this) {
        is Value.Json -> listOf(sql) + listOfNotNull(identity)
        is Value.Entities -> listOf(ids)
        is Value.Bool -> listOf(sql)
        is Value.Node -> listOf(nid, labels, props, stamp)
        is Value.Relationship -> listOf(eid, src, dst, type, props)
        is Value.Path -> listOf(nids, eids)
    }
}
