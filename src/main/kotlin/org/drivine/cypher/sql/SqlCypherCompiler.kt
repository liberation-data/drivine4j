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

    /** Declares [function], a function's whole text, for the statement to call: a query's own, before it. */
    protected fun declare(function: String) {
        functions += function
    }

    /** [compiled] with the functions it calls declared before it. */
    private fun withFunctions(compiled: CompiledCypher): CompiledCypher =
        if (functions.isEmpty()) compiled else compiled.copy(sql = "WITH\n${functions.joinToString("\n")}\n${compiled.sql}")

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

    /** The statement [cypher], with its parameters' values [parameters], as SQL. */
    fun compile(cypher: String, parameters: Map<String, Any?> = emptyMap()): CompiledCypher =
        compile(CypherReader.read(cypher), parameters)

    /** The statement [query], read already, with its parameters' values [parameters], as SQL: a query, or a procedure where it writes. */
    fun compile(query: Query, parameters: Map<String, Any?>): CompiledCypher {
        if (query.unions.isNotEmpty()) return withFunctions(union(query, parameters))
        if (query.clauses.none(::writes)) {
            val writer = Writer(Parameters(parameters, ::parameterJson, ::parameterCount, text = ::parameterText))
            return withFunctions(CompiledCypher(writer.query(query), writer.parameters.values))
        }
        procedural = true
        // A procedure takes its parameters as one object, and reads each from it.
        val writer = Writer(
            Parameters(parameters, { name, _ -> nullIfJsonNull(propertyOf(PARAMS, name)) }, { number(propertyOf(PARAMS, it)) }, inObject = true, text = ::parameterText)
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
        if (!sharing || values.all { it.length <= SHARED_ABOVE }) return body(values.toList())
        return bound(*values, body = body)
    }

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
        val writer = Writer(Parameters(parameters, ::parameterJson, ::parameterCount, text = ::parameterText))
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

    /** The parameter bound as [marker], the JSON text [text], as JSON. */
    protected abstract fun parameterJson(marker: String, text: String?): String

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

    /** The items of the JSON list [json] as rows named [alias], whose item is [item] and whose place in the list, counted from 1, [itemNo]. */
    protected abstract fun elements(json: String, alias: String): String

    /** The item, as JSON, of the row named [alias] that [elements] gives. */
    protected open fun item(alias: String) = "$alias.item"

    /** The place in its list, counted from 1, of the row named [alias] that [elements] gives. */
    protected open fun itemNo(alias: String) = "$alias.item_no"

    /** Whether the JSON values [x] and [y], neither of them null, are equal, as Cypher compares them: see [Writer.equality]. */
    protected open fun jsonEquals(x: String, y: String) = "$x = $y"

    /**
     * Whether the identities [x] and [y], neither of them null, are equal: see [Writer.identityOf]. A list of a few thousand
     * relationships' tokens is larger than Oracle's `=` compares, so Oracle compares them otherwise.
     */
    protected open fun identityEquals(x: String, y: String) = jsonEquals(x, y)

    /** Whether the JSON value [x] is equal to [y], the JSON of [given], a value the statement gives that is not null. */
    protected open fun givenEquals(x: String, y: String, given: Any) = "$x = $y"

    /** Whether the JSON values [x] and [y], of one kind, a number, a text or a truth value, stand as [operator] says (`<`, `>=`…). */
    protected open fun ordered(x: String, operator: String, y: String) = "$x $operator $y"

    /** A SQL truth value [sql] as JSON's `true` or `false`, or null. */
    protected open fun boolJson(sql: String) = scalar(sql)

    /** What an order sorts the JSON value [json] by: JSON's null as SQL's, which comes last, as a graph engine orders null. */
    protected open fun sortKey(json: String) = nullIfJsonNull(json)

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
     * before it collected, for a store dedupes no aggregate in the query that makes it.
     */
    protected abstract fun distinctItems(list: String): String

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
     * gives each as, where it does: see [summedText].
     */
    protected abstract fun add(a: String, b: String, given: Pair<Any?, Any?> = null to null): String

    /**
     * The text the JSON [json], a side of a `+` that may put a number and a text together, is written as there: by [written],
     * as `toString` writes it, a float as Java writes a double. Where the statement gives it a number, [given], it is that
     * number's text, worked out here; and where it gives the other side, [other], a number, or this side a text, it is its own
     * text, [plain], for a side is put together with a number only where it is a text. So only a side the statement gives
     * neither way is written as `toString` writes it, which is long: `c.name + 1` compiles as it did before floats were so written.
     * NaN and the infinities count as given neither way: Postgres binds them as texts, so a number may be added to one as text.
     */
    protected fun summedText(json: String, given: Any?, other: Any?, plain: (String) -> String, written: (String) -> String): String = when {
        isGivenNumber(given) -> literal(given.toString())
        given is String || isGivenNumber(other) -> plain(json)
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
            else -> "(SIGN($n) * $magnitude / $scale)"
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
            is ProcedureStep.Select -> listOf(line("SELECT ${step.expression} INTO ${step.variable}${step.from};"))
            is ProcedureStep.Let -> listOf(line("${step.variable} := ${step.expression};"))
            is ProcedureStep.Loop ->
                listOf(line(if (step.multiline) "FOR ${step.record} IN (\n${indent(step.query)}\n) LOOP" else "FOR ${step.record} IN (${step.query}) LOOP")) +
                    statements(step.body, depth + 1) + line("END LOOP;")
            is ProcedureStep.If -> listOf(line("IF ${step.condition} THEN")) + statements(step.body, depth + 1) + line("END IF;")
            is ProcedureStep.Raise -> listOf(line(raise(step.message)))
            is ProcedureStep.Execute -> listOf(line("${step.statement}${step.into?.let { " INTO $it" }.orEmpty()};"))
            is ProcedureStep.Return -> returnRows(step.query).map(::line)
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
        private fun with(select: Select, with: Clause.With, outer: Map<String, Value> = emptyMap(), apart: Boolean = false): Select {
            val projected = project(select, with.projection, final = false)
            val name = alias("w")
            val next = Select(outer)
            next.walks = select.walks
            // A value made anew for each row is one value however often the clauses after read it: see [once]. Kept apart
            // from the query that reads it, where that query must not have it merged in: see `casesFirst`.
            val anew = with.projection.items.any { item -> contains(item.expression, ::isAnew) }
            val sql = if (anew) once(projected.sql) else projected.sql
            next.source("(\n${indent(if (apart) called(sql) else sql)}\n) $name")
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
            val kinds = named.flatMap { (_, value, column) -> value.columnKinds(column) }.ifEmpty { listOf("one" to false) }
            val text = listOf("SELECT ${columns.joinToString(", ")}") + inner.from() + listOfNotNull(inner.whereClause())
            select.join(lateral(text.joinToString("\n"), name, optional = true, kinds))
            named.forEach { (variable, value, column) -> select.bind(variable, value.columnsOf(PENDING, column).laterally(name, kinds)) }
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
            redeclared(projected.scope.keys, select.bound.keys)
            val name = alias("c")
            select.join(lateral(called(projected.sql), name, call.optional, projected.columns))
            projected.scope.forEach { (variable, value) -> select.bind(variable, value.laterally(name, projected.columns)) }
            projected.order.forEach { (column, descending) -> select.order += lateralColumn(name, column, json = false) to descending }
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
            var projected = parts.map { calledPart(select, it, names, distinct) }
            if (projected.map { p -> p.scope.values.map { it::class } }.distinct().size > 1) unsupported("A UNION in CALL { … } whose queries return values of different kinds")
            // A map or a list that holds a node carries a column more, of its identity. Where one query's value has it and
            // another's has none, as `collect([r])` beside `[]`, the queries are compiled again without identities, so their
            // columns match, and what they return compares by its properties, as it did before identities.
            if (projected.map { p -> p.scope.values.map { (it as? Value.Json)?.identity != null } }.distinct().size > 1) {
                val was = identities
                identities = false
                try {
                    projected = parts.map { calledPart(select, it, names, distinct) }
                } finally {
                    identities = was
                }
            }
            val sql = projected.joinToString(if (distinct) "\nUNION\n" else "\nUNION ALL\n") { "SELECT * FROM (\n${indent(it.sql)}\n) ${alias("q")}" }
            // A value is of a temporal kind where each query makes it of that one.
            val scope = projected.first().scope.mapValues { (variable, value) ->
                val all = projected.map { it.scope.getValue(variable) as? Value.Json }
                if (value !is Value.Json) value
                else value.copy(temporal = all.map { it?.temporal }.distinct().singleOrNull(), items = all.map { it?.items }.distinct().singleOrNull())
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
            select.source(elements(items, name))
            select.bind(unwind.variable, Value.Json(item(name), temporal = itemsOf(list), list = holdsLists(list)))
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
            where?.let { inner.where += bool(expression(it, inner.scope)) }
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
                } + listOfNotNull(group.where?.let { bool(expression(it, within)) })
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
            val inner = scope + (comprehension.variable to Value.Json(item(name), temporal = itemsOf(listed)))
            val projected = comprehension.projection?.let { expression(it, inner) }
            val projection = projected?.let { json(it).sql } ?: item(name)
            val filter = comprehension.where?.let { " WHERE ${bool(expression(it, inner))}" }.orEmpty()
            // Of null, null, as in Cypher, and not an empty list.
            return Value.Json(
                shared(list) { (items) ->
                    "CASE WHEN NOT ${isJsonNull(items)} THEN (SELECT ${aggregateList(projection, itemNo(name))} FROM ${elements(items, name)}$filter) END"
                },
                ordered = true,
                items = if (projected != null) (projected as? Value.Json)?.temporal else itemsOf(listed),
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
                val filter = comprehension.where?.let { " WHERE ${bool(expression(it, inner))}" }.orEmpty()
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
                else -> Value.Json(list, ordered = true, items = (projected as? Value.Json)?.temporal, list = true, itemLists = projected?.let(::isList) == true)
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
                equality(Value.Json(propertyOf(props, key)), expression(value, scope), value)
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
                    Kind.VALUE -> select.bind(
                        variable,
                        Value.Json(
                            propertyOf(row, key), temporal = carried[variable]?.temporal, items = carried[variable]?.items,
                            list = carried[variable]?.list == true, itemLists = carried[variable]?.itemLists == true,
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
            val select = row(if (read.any(::holdsSubquery)) null else read.flatMap(::variablesIn).toSet())
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
            eachRow(kept) {
                if (ret != null) let(caller, "$RECORD.item")
                var select = rows()
                for (clause in imported(call.query.clauses)) {
                    select = if (clause is Clause.Return) with(select, Clause.With(clause.projection, null)) else next(select, clause)
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
        fun project(select: Select, projection: Projection, final: Boolean): Projected {
            // As in Neo4j, a star gives the variables in the order of their names, before the items named after it.
            val items = (if (projection.star) select.bound.keys.sorted().map { Projection.Item(Expression.Variable(it), it) } else emptyList()) + projection.items
            if (items.isEmpty()) throw DrivineException("A projection has at least one item.")
            // Before aggregatesFirst, which names each variable it groups by as an item of its own.
            requireExplicitGrouping(items)
            casesFirst(select, projection, items)?.let { (cased, kept) -> return project(cased, kept, final) }
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
            val compiled = items.map { item -> item.name to aggregating(item.expression) { expression(item.expression, select.scope) } }
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
            val after = mutableMapOf<String, String>()
            // A term worked out in the query is sorted by in the query that reads it: the value carried out as a column, and
            // what sorts it (JSON's null as SQL's) worked out of that column. Oracle (23.26), sorting by a value that reads
            // the row more than once beside a variable-length walk, closes the session.
            val sorted = mutableMapOf<String, (String) -> String>()
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
                when {
                    contains(sort.expression) { it is Expression.Variable && it.name in anew } && variablesIn(sort.expression).all { it in itemColumns } -> {
                        after[column] = sortable(expression(sort.expression, itemColumns))
                        sortsAnew = true
                    }
                    item >= 0 -> carried(column, { compiled[item].second }, sort.expression)
                    compared && !contains(sort.expression) { isAggregate(it) || it is Expression.Variable && it.name.startsWith("#aggregate") } ->
                        after[column] = sortable(expression(sort.expression, itemColumns))
                    // One that aggregates reads the rows it aggregates, as an item does; so does one that reads an aggregate
                    // aggregatesFirst worked out first, which no item is. Beside a variable-length walk it binds no value in a
                    // subquery: carried out of a WITH's query, one so bound closes Oracle's (23.26) session (ORA-03113), as
                    // `WITH b ORDER BY toString(coalesce(b.name, b.title))` did, its argument long enough to be bound. Elsewhere
                    // it does, so operations nested in it compile to a statement that grows with them, not exponentially.
                    else -> {
                        val term = { carried(column, { expression(sort.expression, select.scope + compiled.toMap()) }, sort.expression) }
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
            fun sortedBy(column: String) = after[column] ?: sorted[column]?.invoke("$name.$column") ?: "$name.$column"
            val outerColumns = if (final) {
                val values = columns.map { (column, value) -> json(value.columnsOf(name, column)) }
                val result = if (values.size == 1) values.single().sql else jsonArray(values.map { it.sql })
                "$result AS result"
            } else {
                (columns.flatMap { (column, value) -> value.columns(column).map { "$name.${it.substringAfterLast(" AS ")}" } } +
                    sorts.map { (column, _) -> "${sortedBy(column)} AS $column" }).joinToString(", ")
            }
            val outer = buildList {
                add("SELECT $outerColumns")
                add("FROM (\n${indent(if (sortsAnew) once(inner) else inner)}\n) $name")
                if (sorts.isNotEmpty()) {
                    add("ORDER BY ${sorts.joinToString(", ") { (column, descending) -> orderTerm(sortedBy(column), descending) }}")
                }
                addAll(page(projection.skip?.let(::count), projection.limit?.let(::count)))
            }.joinToString("\n")
            // What each item is bound to in the query that reads this one: its columns, which that query names.
            val scope = items.zip(columns).associate { (item, pair) -> item.name to pair.second.columnsOf(PENDING, pair.first) }
            val kinds = columns.flatMap { (column, value) -> value.columnKinds(column) } + sorts.map { (column, _) -> column to false }
            return Projected(outer, scope, sorts, kinds)
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
         * Null where the projection does neither.
         */
        private fun aggregatesFirst(select: Select, projection: Projection, items: List<Projection.Item>): Pair<Select, Projection>? {
            fun collectsDistinct(expression: Expression) = expression is Expression.Function && expression.distinct && expression.name.lowercase() == "collect"
            fun iteratesAggregate(expression: Expression) = iterated(expression).any { contains(it, ::isAggregate) }
            val terms = items.map { it.expression } + projection.orderBy.map { it.expression }
            if (terms.none { contains(it, ::collectsDistinct) || contains(it, ::iteratesAggregate) }) return null
            // Named as no variable is, unless quoted so.
            val keys = items.filter { !contains(it.expression, ::isAggregate) }.associate { it.expression to "#group${++counter}" }
            val aggregates = linkedMapOf<Expression, String>()
            terms.forEach { term -> contains(term) { if (isAggregate(it)) aggregates.getOrPut(it) { "#aggregate${++counter}" }; false } }
            // A comprehension's, a quantifier's or a reduce's body is kept as it is, so a variable it names outside its own is kept too,
            // and so is a pattern's or a subquery's. A subquery may name any variable: each the projection groups by is kept.
            fun named(expression: Expression): Set<String> = if (isAggregate(expression)) emptySet() else variablesOutsideAggregates(expression) + when (expression) {
                is Expression.ListComprehension -> listOfNotNull(expression.where, expression.projection).flatMap(::named) - expression.variable
                is Expression.Quantifier -> listOfNotNull(expression.where).flatMap(::named) - expression.variable
                is Expression.Reduce -> named(expression.expression) - expression.accumulator - expression.variable
                is Expression.PatternComprehension, is Expression.PatternPredicate, is Expression.Exists -> variablesIn(expression)
                is Expression.Subquery -> keys.keys.filterIsInstance<Expression.Variable>().map { it.name }
                else -> emptySet()
            } + children(expression).flatMap(::named)
            // An order term that aggregates may name a key too, by the variable the projection groups by.
            val keyed = keys.keys.filterIsInstance<Expression.Variable>().map { it.name }
            val beside = (items.filter { contains(it.expression, ::isAggregate) }.flatMap { named(it.expression) } +
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
            fun kept(term: Expression) = keys[term]?.let(Expression::Variable) ?: aggregates[term]?.let { name ->
                if (collectsDistinct(term)) Expression.Function(DISTINCT_ITEMS, false, listOf(Expression.Variable(name))) else Expression.Variable(name)
            }
            // An order term, or a part of one, that is an item is read by the item's name, as over a DISTINCT projection it can only be;
            // a variable an item is named after is that item, not the variable of that name before it.
            val itemNames = items.associate { it.expression to it.name }
            val aliases = items.map { it.name }.toSet()
            val kept = projection.copy(
                star = false,
                items = items.map { it.copy(expression = replaced(it.expression, ::kept)) },
                orderBy = projection.orderBy.map { sort ->
                    sort.copy(
                        expression = replaced(sort.expression) { term ->
                            if (term is Expression.Variable && term.name in aliases) term else itemNames[term]?.let(Expression::Variable) ?: kept(term)
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
            is Expression.Parameter ->
                givenParameters?.get(expression.name) ?: Value.Json(parameters.json(expression.name), temporal = Temporal.ofValue(parameters.value(expression.name)))
            is Expression.Variable -> scope[expression.name] ?: throw DrivineException("${expression.name} is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.")
            is Expression.Property -> property(expression(expression.subject, scope), expression.key)
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
                        list = true,
                        itemLists = entities.isNotEmpty() && entities.all(::isList),
                    )
                }
            }
            is Expression.MapLiteral -> {
                val entries = expression.entries.map { (key, value) -> key to expression(value, scope) }
                Value.Json(
                    jsonObject(entries.map { (key, value) -> key to json(value).sql }),
                    ordered = true,
                    identity = identityHolding(entries.map { it.second }) { ids -> jsonObject(entries.map { it.first }.zip(ids)) },
                )
            }
            is Expression.MapProjection -> mapProjection(expression, scope)
            is Expression.Binary -> binary(expression, scope)
            is Expression.Unary -> unary(expression, scope)
            is Expression.Index -> {
                val index = expression.index
                val listed by lazy { expression(expression.subject, scope) }
                val subject = { comparable(json(listed)) }
                val at = comparable(json(expression(index, scope)))
                // An index written, or bound to a parameter, is known: a text is the property of that name, a number an item.
                val known = when (index) {
                    is Expression.Literal -> index.value
                    is Expression.Parameter -> parameters.value(index.name)
                    // `-1` is a negated number.
                    is Expression.Unary -> (index.operand as? Expression.Literal)?.value?.takeIf { index.operator == Expression.Unary.Operator.NEGATE && it is Number }
                    else -> null
                }
                when {
                    known is String -> property(expression(expression.subject, scope), known)
                    // An item of a list of nodes or relationships is the node or the relationship, so it is compared by its id;
                    // of one collect gathers in this query, its properties, for a store reads no aggregate from a subquery. A
                    // pattern comprehension gathers its properties too, but in a subquery of its own, so its item is read by id.
                    listed is Value.Entities && !contains(expression.subject, ::isAggregate) -> entityAt(listed as Value.Entities, at)
                    // An item of a list that holds a node or a relationship holds it by identity too: see [Value.Json.identity].
                    known is Number -> element(subject(), at).let { item ->
                        Value.Json(item, temporal = itemsOf(listed), identity = identityOf(listed, item, true) { element(it, at) }, list = holdsLists(listed))
                    }
                    else -> {
                        fun item(list: String) = shared(list, at) { (map, key) ->
                            // A store that reads no key an expression gives reads a list's item alone.
                            val keyed = keyed(map, key)?.let { " WHEN 'string' THEN $it" }.orEmpty()
                            "CASE ${typeOf(key)} WHEN 'number' THEN ${element(map, key)}$keyed END"
                        }
                        item(subject()).let { taken -> Value.Json(taken, temporal = itemsOf(listed), identity = identityOf(listed, taken, true, ::item), list = holdsLists(listed)) }
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
                val initial = comparable(json(expression(expression.initial, scope)))
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
                            val inner = scope + mapOf(expression.accumulator to Value.Json(accumulator), expression.variable to Value.Json(item, temporal = itemsOf(listed)))
                            comparable(json(expression(expression.expression, inner)))
                        },
                        context = context,
                        contextStep = { accumulator, item, map ->
                            // A value read from the map is of the kind the statement made it.
                            val inner = read.associateWith { (scope.getValue(it) as? Value.Json).let { v -> Value.Json(propertyOf(map, it), temporal = v?.temporal, items = v?.items) } } +
                                mapOf(expression.accumulator to Value.Json(accumulator), expression.variable to Value.Json(item, temporal = itemsOf(listed)))
                            val was = givenParameters
                            givenParameters = given.associateWith { Value.Json(propertyOf(map, "$$it"), temporal = Temporal.ofValue(parameters.value(it))) }
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
                    else -> Value.Json(gathered(json(projected).sql), ordered = true, items = (projected as? Value.Json)?.temporal, list = true, itemLists = isList(projected))
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
                val listed = expression(expression.list, scope)
                val name = alias("q")
                fun quantified(from: String, item: Value): String {
                    val inner = scope + (expression.variable to item)
                    val condition = expression.where?.let { bool(expression(it, inner)) } ?: "TRUE"
                    return when (expression.kind) {
                        Expression.Quantifier.Kind.ANY -> "EXISTS (SELECT 1 $from WHERE $condition)"
                        Expression.Quantifier.Kind.ALL -> "NOT EXISTS (SELECT 1 $from WHERE NOT ($condition))"
                        Expression.Quantifier.Kind.NONE -> "NOT EXISTS (SELECT 1 $from WHERE $condition)"
                        Expression.Quantifier.Kind.SINGLE -> "(SELECT count(*) $from WHERE $condition) = 1"
                    }
                }
                Value.Bool(
                    // Over a list of nodes or relationships, each item is the node or the relationship, read from its table.
                    if (listed is Value.Entities) shared(listed.ids) { (ids) -> entityItems(ids, listed.relationships, name).let { (from, item) -> quantified(from, item) } }
                    else quantified("FROM ${elements(comparable(json(listed)), name)}", Value.Json(item(name), temporal = itemsOf(listed)))
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
                val items = "SELECT ${item(e)} AS item, ${itemNo(e)} AS item_no, COUNT(*) OVER () AS cnt FROM ${elements(x, e)}"
                val query = "SELECT ${comparable(Value.Json(aggregateList("$v.item", "$v.item_no"), ordered = true))} FROM ($items) $v" +
                    within.joinToString(" AND ", " WHERE ")
                val present = (listOf("${typeOf(x)} = 'array'") + listOfNotNull(from, to).map { "NOT ${isJsonNull(it)}" }).joinToString(" AND ")
                "CASE WHEN $present THEN ($query) END"
            }
            // A slice of a list that holds a node or a relationship holds it by identity too: see [Value.Json.identity].
            return if (listed is Value.Entities) Value.Entities(sliced(list), listed.relationships)
            else sliced(list).let { taken ->
                Value.Json(taken, items = itemsOf(listed), identity = identityOf(listed, taken, false, ::sliced), list = true, itemLists = holdsLists(listed))
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
            is Value.Json -> temporalField(subject, key) ?: propertyOf(comparable(subject), key).let { taken ->
                Value.Json(taken, identity = identityOf(subject, taken, true) { propertyOf(it, key) })
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

        /** The temporal kind the statement makes each item of the list [list]: see [Value.Json.items]. */
        private fun itemsOf(list: Value): Temporal? = (list as? Value.Json)?.items

        /**
         * A field of a temporal value, `d.year` or `dur.days`: of the kind the statement makes it, or of the kind a
         * text's shape tells, where a map's is its entry of that name; null for a name no temporal value has.
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
            })
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
                    // addition, the two added as any two values are, as Cypher adds a temporal value to a list or to a text.
                    fun read(kind: Temporal?, temporal: (String, String) -> String) = Value.Json(shared(jsonA, jsonB) { (x, y) ->
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
                val known = if (kind == Temporal.DURATION) DURATION_MAP_FIELDS else INSTANT_MAP_FIELDS
                fields.keys.firstOrNull { it !in known }?.let { unsupported("${function.name}() of a map with $it") }
                val numbers = fields.filterKeys { it != "timezone" }.mapValues { number(comparable(json(it.value))) }
                if (kind == Temporal.DURATION) return of(temporals.duration(numbers))
                return of(temporals.of(numbers, fields["timezone"]?.let { text(comparable(json(it))) }, kind))
            }
            val given = arguments.single() as? Value.Json ?: throw DrivineException("${function.name}() takes a text or a map of its fields.")
            val text = text(comparable(given))
            return when {
                kind == Temporal.DURATION -> of(temporals.duration(text))
                given.temporal?.instant == true -> of(temporals.convert(text, given.temporal, kind))
                else -> of(temporals.convert(text, kind))
            }
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
            if (projection.elements.none { it is Expression.MapProjection.Element.All }) {
                val identity = identityHolding(values.map { it.second }) { ids -> present(jsonObject(values.map { it.first }.zip(ids))) }
                return Value.Json(present(jsonObject(entries)), ordered = true, identity = identity)
            }
            // Every property, then the entries over them.
            return Value.Json(present(withEntries(comparable(json(subject)), entries)))
        }

        /**
         * [left] and [right], the sides of [binary], as compared: where one holds a node or a relationship by identity and the
         * other may hold one without it (an item taken from a list, a collect, a variable), both by their properties, as
         * before identities: only a value the statement writes out or is given, all the way down (a literal, a parameter, a
         * map, a list or a map projection of such, a `CASE` that gives one), holds none where it carries no identity, so
         * `[{x: r}][0] = {x: r}` is true, and `{x: r} = {x: {}}` false.
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
            return when {
                held(left) && right is Value.Json && !held(right) && !written(binary.right) -> plain(left) to right
                held(right) && left is Value.Json && !held(left) && !written(binary.left) -> left to plain(right)
                else -> left to right
            }
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
            val (left, right) = compared(expression(binary.left, scope), expression(binary.right, scope), binary)
            temporalBinary(binary.operator, left, right)?.let { return it }
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
                    Value.Bool(shared(a, b) { (x, y) -> "CASE WHEN ${typeOf(x)} = ${typeOf(y)} AND ${typeOf(x)} IN ('number', 'string', 'boolean') THEN ${ordered(x, operator, y)} END" })
                }
                // Two lists of nodes, or of relationships, are together a list of them.
                Operator.ADD -> if (left is Value.Entities && right is Value.Entities && left.relationships == right.relationships) {
                    val gathered = if (left.gathered != null || right.gathered != null) shared(a, b) { (x, y) -> add(x, y) } else null
                    Value.Entities(shared(left.ids, right.ids) { (x, y) -> add(x, y) }, left.relationships, gathered)
                } else {
                    // Two lists of items of one kind are, together, a list of that kind.
                    Value.Json(
                        shared(a, b) { (x, y) -> add(x, y, given(binary.left) to given(binary.right)) }, items = itemsOf(left)?.takeIf { it == itemsOf(right) },
                        list = isList(left) || isList(right), itemLists = holdsLists(left) && (holdsLists(right) || !isList(right)),
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
                Operator.IN -> {
                    val i = alias("i")
                    // A node or a relationship is in a list of them where its id is.
                    val id = when {
                        right !is Value.Entities -> null
                        left is Value.Node && !right.relationships -> scalar(left.nid)
                        left is Value.Relationship && right.relationships -> scalar(left.eid)
                        else -> null
                    }
                    when {
                        id != null -> Value.Bool("EXISTS (SELECT 1 FROM ${elements((right as Value.Entities).ids, i)} WHERE ${item(i)} = $id)")
                        // A map or a list that holds a node or a relationship is in a list where an item holds the same, by identity.
                        (left as? Value.Json)?.identity != null || (right as? Value.Json)?.identity != null ->
                            Value.Bool("EXISTS (SELECT 1 FROM ${elements(identified(right), i)} WHERE ${identityEquals(item(i), identified(left))})")
                        else -> Value.Bool("EXISTS (SELECT 1 FROM ${elements(b, i)} WHERE ${jsonEquals(item(i), a)})")
                    }
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
            (temporalBinary(Operator.EQUALS, left, right) as? Value.Bool)?.let { return it.sql }
            if (left is Value.Node && right is Value.Node) return "${left.nid} = ${right.nid}"
            if (left is Value.Relationship && right is Value.Relationship) return "${left.eid} = ${right.eid}"
            // A map or a list that holds a node or a relationship is equal where what it holds is, by identity.
            if ((left as? Value.Json)?.identity != null || (right as? Value.Json)?.identity != null) {
                return shared(identified(left), identified(right)) { (x, y) -> "CASE WHEN NOT ${isJsonNull(x)} AND NOT ${isJsonNull(y)} THEN ${identityEquals(x, y)} END" }
            }
            // Two lists of nodes, or of relationships, are equal where their ids are, item by item.
            val entities = left is Value.Entities && right is Value.Entities && left.relationships == right.relationships
            val a = if (entities) (left as Value.Entities).ids else comparable(json(left))
            val b = if (entities) (right as Value.Entities).ids else comparable(json(right))
            val given = written?.let(::given)
            val indexed = if (given is String || given is Boolean) indexedEquality(a, b) else null
            // Null is equal to nothing, itself included: the comparison is null, as in Cypher. A value the statement
            // is given that is not null needs no test of its own, and a property that is not there is SQL's null.
            val equal = if (given != null) givenEquals(a, b, given) else shared(a, b) { (x, y) -> "CASE WHEN NOT ${isJsonNull(x)} AND NOT ${isJsonNull(y)} THEN ${jsonEquals(x, y)} END" }
            return if (indexed != null) "($indexed AND $equal)" else equal
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
                if (subject != null) equality(subject, expression(key, scope)) else bool(expression(key, scope))
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
                sql, temporal = kindOf(results), identity = identity,
                list = results.all { isList(it.second) }, itemLists = results.all { holdsLists(it.second) },
            )
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
                "coalesce" -> Value.Json(
                    "COALESCE(${arguments.joinToString(", ") { nullIfJsonNull(comparable(json(it))) }})",
                    temporal = kindOf(function.arguments.zip(arguments)),
                )
                "count" -> Value.Json(scalar("count($distinct${countable(one())})"))
                // collect(DISTINCT …) is collected in a WITH of its own, then kept once each: see aggregatesFirst.
                "collect" -> if (function.distinct) error("collect(DISTINCT …) is gathered by aggregatesFirst") else when (val gathered = one()) {
                    // Nodes or relationships are gathered as their ids, so a list of them is still of them.
                    is Value.Node -> Value.Entities(gatheredIds("CASE WHEN ${gathered.nid} IS NOT NULL THEN ${scalar(gathered.nid)} END"), false, collected(gathered))
                    is Value.Relationship -> Value.Entities(gatheredIds("CASE WHEN ${gathered.eid} IS NOT NULL THEN ${scalar(gathered.eid)} END"), true, collected(gathered))
                    // What holds a node or a relationship, a map or a list of them, is gathered beside its identity too.
                    else -> Value.Json(
                        collected(gathered), ordered = true, items = (gathered as? Value.Json)?.temporal, list = true, itemLists = isList(gathered),
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
                            list = true, itemLists = list.itemLists,
                        )
                    } ?: Value.Json(distinctItems(comparable(list)), ordered = true, items = list.items, list = true, itemLists = list.itemLists)
                    else -> Value.Json(distinctItems(comparable(json(list))), ordered = true)
                }
                "sum" -> Value.Json(scalar("COALESCE(sum($distinct${number(comparable(json(one())))}), 0)"))
                "avg" -> Value.Json(scalar(float("avg($distinct${number(comparable(json(one())))})")))
                // Zoned values as the moments they are, whatever their offsets; of a temporal kind, still of it. A list the
                // statement makes item by item, as an ORDER BY sorts it: a store's JSON may put the shorter list first.
                "min", "max" -> {
                    val value = json(one())
                    val key = value.temporal?.takeIf { it.zoned }?.let { temporals.moment(text(comparable(value)), it) }
                        ?: if (extremesByListKey && isList(one())) listSortKey(comparable(value)) else null
                    Value.Json(extreme(comparable(value), min = name == "min", key = key), temporal = value.temporal)
                }
                "range" -> {
                    if (arguments.size !in 2..3) throw DrivineException("range() takes a start, an end and a step.")
                    val (from, to) = arguments.map { number(comparable(json(it))) }
                    Value.Json(range(from, to, arguments.getOrNull(2)?.let { number(comparable(json(it))) } ?: "1"), list = true)
                }
                "split" -> {
                    if (arguments.size != 2) throw DrivineException("split() takes a text and a separator.")
                    val (text, separator) = arguments.map { text(comparable(json(it))) }
                    Value.Json(split(text, separator), list = true)
                }
                "reverse" -> Value.Json(shared(comparable(json(one()))) { (x) ->
                    val e = alias("e")
                    "CASE ${typeOf(x)} WHEN 'string' THEN ${scalar(reverseText(text(x)))} " +
                        "WHEN 'array' THEN (SELECT ${comparable(Value.Json(aggregateList(item(e), "${itemNo(e)} DESC"), ordered = true))} FROM ${elements(x, e)}) END"
                }, items = itemsOf(one()), list = isList(one()), itemLists = holdsLists(one()))
                "tail" -> Value.Json(shared(comparable(json(one()))) { (x) ->
                    val e = alias("e")
                    "CASE WHEN ${typeOf(x)} = 'array' THEN (SELECT ${comparable(Value.Json(aggregateList(item(e), itemNo(e)), ordered = true))} FROM ${elements(x, e)} WHERE ${itemNo(e)} > 1) END"
                }, items = itemsOf(one()), list = isList(one()), itemLists = holdsLists(one()))
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
                        refuseNegativePrecision(function.arguments[1])
                        Value.Json(scalar(float(roundTo(number(comparable(json(arguments[0]))), truncatedWhole(arguments[1]), mode))))
                    } ?: Value.Json(literalJson(null))
                    2 -> {
                        refuseNegativePrecision(function.arguments[1])
                        Value.Json(scalar(float("ROUND(${number(comparable(json(arguments[0])))}, ${truncatedWhole(arguments[1])})")))
                    }
                    else -> Value.Json(scalar(float("FLOOR(${number(comparable(json(one())))} + 0.5)")))
                }
                "floor", "ceil" -> Value.Json(scalar(float("${name.uppercase()}(${number(comparable(json(one())))})")))
                in MATH_FUNCTIONS -> Value.Json(scalar(math(name, listOf(number(comparable(json(one())))))))
                "atan2" -> if (arguments.size == 2) Value.Json(scalar(math(name, arguments.map { number(comparable(json(it))) })))
                    else throw DrivineException("atan2() takes two numbers.")
                // Java's constants, which Neo4j gives, written as the floats they are.
                "e", "pi" -> if (arguments.isEmpty()) Value.Json(literalJson(if (name == "e") Math.E else Math.PI))
                    else throw DrivineException("${function.name}() takes no argument.")
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
                "date", "localtime", "time", "localdatetime", "datetime", "duration" -> temporal(Temporal.ofFunction(name)!!, function, arguments, scope)
                "timestamp" -> if (arguments.isEmpty()) Value.Json(scalar(temporals.field(temporals.now(Temporal.DATE_TIME), Temporal.DATE_TIME, "epochMillis")!!.sql))
                    else throw DrivineException("timestamp() takes no argument.")
                // The moment the statement runs, however it is asked for: one statement is one moment here.
                in TEMPORAL_NOW -> Value.Json(scalar(temporals.now(Temporal.ofFunction(name.substringBefore('.'))!!)), temporal = Temporal.ofFunction(name.substringBefore('.')))
                in TEMPORAL_TRUNCATE -> {
                    val to = Temporal.ofFunction(name.substringBefore('.'))!!
                    if (arguments.size != 2) throw DrivineException("${function.name}() takes a unit and a value.")
                    val unit = (function.arguments[0] as? Expression.Literal)?.value as? String ?: unsupported("A truncation to a unit an expression gives")
                    val given = arguments[1] as? Value.Json ?: throw DrivineException("${function.name}() takes a unit and a value.")
                    fun cut(text: String, from: Temporal) = temporals.truncate(unit, text, from, to)
                        ?: throw DrivineException("${function.name}() truncates to year, quarter, month, week, day, hour, minute, second or millisecond, not $unit.")
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
                    val argument = comparable(json(one()))
                    // An item taken from a list is of the kind its items are.
                    val taken = name == "head" || name == "last"
                    val result = shared(argument) { (x) -> function(name, x)!! }
                    Value.Json(
                        result,
                        temporal = if (taken) itemsOf(one()) else null,
                        identity = if (taken) identityOf(one(), result, true) { list -> shared(list) { (x) -> function(name, x)!! } } else null,
                        list = taken && holdsLists(one()),
                    )
                }
            }
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

        /** A function's text, its first argument, and a whole number, its second. */
        private fun textAndCount(arguments: List<Value>): Pair<String, String> = text(comparable(json(arguments[0]))) to whole(arguments[1])

        /** A value as a SQL whole number. */
        private fun whole(value: Value): String = "CAST(${number(comparable(json(value)))} AS INTEGER)"

        /** [value] as a whole number with its fraction cut off, toward zero, as Neo4j reads `round()`'s precision. */
        private fun truncatedWhole(value: Value): String = "CAST(${temporalSql.trunc}(${number(comparable(json(value)))}) AS INTEGER)"

        /**
         * Refuses a precision for `round()` that is below 0 as a whole number toward zero, as Neo4j refuses it,
         * where it is written, negated or not, or a parameter's value: -0.5 is 0 places, and -1.2 is refused.
         * One read from data is not known here, and SQL has no way to fail on it, so it rounds to tens or hundreds
         * where Neo4j fails.
         */
        private fun refuseNegativePrecision(precision: Expression) {
            val negated = precision is Expression.Unary && precision.operator == Expression.Unary.Operator.NEGATE
            val given = (given(if (negated) (precision as Expression.Unary).operand else precision) as? Number)?.toDouble() ?: return
            if (kotlin.math.truncate(if (negated) -given else given) < 0) {
                throw DrivineException("round() takes a precision of 0 or more, as in Neo4j.")
            }
        }

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
        private fun sortable(value: Value): String = sortTerm(value).let { (carried, sorts) -> sorts(carried) }

        /** What [sortable] sorts [value] by, in two: the value as a column carries it, and what sorts it, of that column. */
        private fun sortTerm(value: Value): Pair<String, (String) -> String> = when {
            value is Value.Bool -> value.sql to { it }
            // A zoned value the statement makes sorts as the moment it is, whatever its offset.
            value is Value.Json && value.temporal?.zoned == true -> comparable(value) to { temporals.moment(text(it), value.temporal) }
            // A list the statement makes sorts as Cypher sorts lists, item by item; any other value as the store sorts its JSON.
            isList(value) -> comparable(json(value)) to { listSortKey(it) }
            else -> comparable(json(value)) to { sortKey(it) }
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

        val AGGREGATES = setOf("count", "collect", "sum", "avg", "min", "max")

        /** The function that keeps each item of a list once, where it is first: see [Writer.aggregatesFirst]. No statement can name it. */
        const val DISTINCT_ITEMS = "#distinct items"


        /** openCypher's functions of one number that [math] works out, as Neo4j names them, lowered. */
        val MATH_FUNCTIONS = setOf("exp", "log", "log10", "sqrt", "sin", "cos", "tan", "cot", "asin", "acos", "atan", "degrees", "radians", "haversin")

        /**
         * The doubles Java's `Math.toDegrees` and `Math.toRadians` multiply by, as Neo4j's `degrees` and `radians` do: a
         * store that works out 180 / π otherwise, or divides, can differ in the last digit, as at `degrees(0.1)`.
         */
        const val RADIANS_TO_DEGREES = "57.29577951308232"
        const val DEGREES_TO_RADIANS = "0.017453292519943295"

        /** Whether [expression] is a call of `randomUUID()`, the one function compiled here that gives a value anew each time it is called. */
        fun isAnew(expression: Expression): Boolean = expression is Expression.Function && expression.name.equals("randomUUID", ignoreCase = true)

        fun isAggregate(expression: Expression): Boolean =
            expression == Expression.CountStar || (expression is Expression.Function && expression.name.lowercase() in AGGREGATES)

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
            is Value.Entities -> Value.Entities("$prefix$base", relationships)
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
