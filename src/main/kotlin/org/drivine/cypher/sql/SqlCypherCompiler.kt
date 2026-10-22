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
 */
abstract class SqlCypherCompiler(graph: String) {

    protected val nodes = "${graph}_node"
    protected val edges = "${graph}_edge"
    private val logger = LoggerFactory.getLogger(SqlCypherCompiler::class.java)

    /** The statement [cypher], with its parameters' values [parameters], as SQL. */
    fun compile(cypher: String, parameters: Map<String, Any?> = emptyMap()): CompiledCypher =
        compile(CypherReader.read(cypher), parameters)

    fun compile(query: Query, parameters: Map<String, Any?>): CompiledCypher {
        val writer = Writer(Parameters(parameters, ::parameterJson, ::parameterCount))
        return CompiledCypher(writer.query(query), writer.parameters.values)
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

    /** The JSON [json] as text, where it is a string or a number. */
    protected abstract fun scalarText(json: String): String

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

    /** The value of the JSON map [json] at the key the JSON [key] names. */
    protected abstract fun keyed(json: String, key: String): String

    /** Whether the JSON map [props] holds every entry of the JSON map [map]. */
    protected abstract fun containsAll(props: String, map: String): String

    /** Whether the labels [labels], a JSON list, hold [label]. */
    protected abstract fun hasLabel(labels: String, label: String): String

    /**
     * A test an index answers, to stand beside the comparison of the [property], read as [propertyOf]
     * gives it, with the [value]; null where there is none.
     */
    protected open fun indexedEquality(property: String, value: String): String? = null

    /** The items of the JSON list [json] as rows named [alias], with columns `item` and `item_no`, counted from 1. */
    protected abstract fun elements(json: String, alias: String): String

    /** The values [value] of a group, or of a query's rows ordered by [order], as a JSON list: empty where there are none. */
    protected abstract fun aggregateList(value: String, order: String? = null): String

    /** `collect`: the values [value] of a group that are not null, as a JSON list; once each with [distinct]. */
    protected abstract fun collect(value: String, distinct: Boolean): String

    /** `min` or `max` of the JSON values [value] of a group. */
    protected abstract fun extreme(value: String, min: Boolean): String

    /** `a + b` of the JSON values [a] and [b]: numbers added, lists joined, texts put together. */
    protected abstract fun add(a: String, b: String): String

    /** The remainder of the SQL numbers [a] and [b]. */
    protected abstract fun modulo(a: String, b: String): String

    /** Whether the text [a] starts with the text [b]. */
    protected abstract fun startsWith(a: String, b: String): String

    /** Whether the text [a] ends with the text [b]. */
    protected abstract fun endsWith(a: String, b: String): String

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

    /** One statement's compilation: its parameters, and the names it gives its rows and columns. */
    private inner class Writer(val parameters: Parameters) {
        private var counter = 0

        private fun alias(prefix: String) = "$prefix${++counter}"

        fun query(query: Query): String {
            var select = Select(emptyMap())
            for (clause in query.clauses) {
                when (clause) {
                    is Clause.Match -> match(select, clause)
                    is Clause.Unwind -> unwind(select, clause)
                    is Clause.With -> {
                        val (sql, scope) = project(select, clause.projection, final = false)
                        val name = alias("w")
                        select = Select(emptyMap())
                        select.source("(\n${indent(sql)}\n) $name")
                        scope.forEach { (variable, value) -> select.bind(variable, value.columnsOf(name)) }
                        clause.where?.let { select.where += bool(expression(it, select.scope)) }
                    }
                    is Clause.Return -> return project(select, clause.projection, final = true).first
                }
            }
            throw DrivineException("A Cypher statement ends with RETURN.")
        }

        // ----- Clauses -----

        private fun match(select: Select, match: Clause.Match) {
            if (!match.optional) {
                patterns(select, match.patterns)
                match.where?.let { select.where += bool(expression(it, select.scope)) }
                return
            }
            // What is found is read by a lateral query, joined to keep the row when it finds nothing.
            val inner = Select(select.scope)
            patterns(inner, match.patterns)
            match.where?.let { inner.where += bool(expression(it, inner.scope)) }
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

        private fun unwind(select: Select, unwind: Clause.Unwind) {
            val list = expression(unwind.list, select.scope)
            val name = alias("u")
            select.source(elements(comparable(json(list)), name))
            select.bind(unwind.variable, Value.Json("$name.item"))
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
        private fun patterns(select: Select, patterns: List<Pattern>) {
            val hops = mutableListOf<Hop>()
            patterns.forEach { pattern(select, it, hops) }
            for (i in hops.indices) for (j in i + 1 until hops.size) {
                val (a, b) = hops[i] to hops[j]
                // Relationships of types no relationship has both of cannot be one.
                if (a.types.isEmpty() || b.types.isEmpty() || a.types.intersect(b.types.toSet()).isNotEmpty()) select.where += a.distinctFrom(b)
            }
        }

        private fun pattern(select: Select, pattern: Pattern, hops: MutableList<Hop>) {
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
                node.labels.forEach { select.where += hasLabel(value.labels, it) }
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
                (node.labels.map { hasLabel("$row.labels", it) } + listOfNotNull(properties?.let { propertiesMatch("$row.props", it, before) })).joinToString(" AND ")
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

        /** The nodes whose ids are the list [nids], as a JSON list in its order: null where the list is. */
        private fun nodeList(nids: String): Value.Json {
            val n = alias("n")
            val u = alias("u")
            val node = json(Value.Node("$n.nid", "$n.labels", "$n.props", "$n.stamp")).sql
            return Value.Json(
                "CASE WHEN $nids IS NOT NULL THEN (SELECT ${aggregateList(node, "$u.ord")} FROM ${idRows(nids, u)} JOIN $nodes $n ON $n.nid = $u.id) END",
                ordered = true,
            )
        }

        /** The relationships whose ids are the list [eids], as a JSON list in its order: null where the list is. */
        private fun relationshipList(eids: String): Value.Json {
            val e = alias("e")
            val u = alias("u")
            val relationship = json(Value.Relationship("$e.eid", "$e.src", "$e.dst", "$e.type", "$e.props")).sql
            return Value.Json(
                "CASE WHEN $eids IS NOT NULL THEN (SELECT ${aggregateList(relationship, "$u.ord")} FROM ${idRows(eids, u)} JOIN $edges $e ON $e.eid = $u.id) END",
                ordered = true,
            )
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
                equality(Value.Json(propertyOf(props, key)), expression(value, scope))
            }
            else -> containsAll(props, comparable(json(expression(properties, scope))))
        }

        // ----- Projection -----

        /**
         * The query a `WITH` or a `RETURN` makes of [select]: its items as columns, grouped where it
         * aggregates, then ordered, skipped and limited. Gives the query, and for a `WITH` what each
         * item's name is bound to among its columns.
         */
        fun project(select: Select, projection: Projection, final: Boolean): Pair<String, Map<String, Value>> {
            val items = (if (projection.star) select.bound.keys.map { Projection.Item(Expression.Variable(it), it) } else emptyList()) + projection.items
            if (items.isEmpty()) throw DrivineException("A projection has at least one item.")
            if (final && items.size > 1) {
                logger.warn(
                    "The statement returns {} items ({}). Prefer returning a single map or value, such as RETURN {{ key1: val1, key2: val2 }}: " +
                        "on Oracle and Postgres it is returned as a list of its items, as drivine gives a row of several columns.",
                    items.size, items.joinToString { it.name },
                )
            }
            requireExplicitGrouping(items)
            val compiled = items.map { item -> item.name to expression(item.expression, select.scope) }
            val aggregating = items.any { contains(it.expression, ::isAggregate) }
            // Where rows are compared (DISTINCT, GROUP BY), JSON is compared as what compares.
            val compared = projection.distinct || aggregating
            val columns = compiled.mapIndexed { i, (_, value) -> "c${i + 1}" to (if (compared && value is Value.Json && value.ordered) Value.Json(comparable(value)) else value) }
            val selectList = columns.flatMap { (column, value) -> value.columns(column) }.toMutableList()
            // An order is written over the items' names, and where nothing is grouped or kept distinct, the variables too.
            val sortScope = (if (compared) emptyMap() else select.scope) + compiled.toMap()
            val sorts = projection.orderBy.mapIndexed { i, sort ->
                val name = "s${i + 1}"
                // An order term that is an item, as written, is that item: so it may be ordered by where rows are compared.
                val value = items.indexOfFirst { it.expression == sort.expression }.takeIf { it >= 0 }?.let { compiled[it].second }
                    ?: expression(sort.expression, sortScope)
                selectList += "${sortable(value)} AS $name"
                "$name${if (sort.descending) " DESC" else ""}"
            }
            val groupBy = if (!aggregating) emptyList() else {
                val keys = mutableListOf<String>()
                items.zip(compiled).forEach { (item, pair) ->
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
            val name = alias("p")
            val outerColumns = if (final) {
                val values = columns.map { (column, value) -> json(value.columnsOf(name, column)) }
                val result = if (values.size == 1) values.single().sql else jsonArray(values.map { it.sql })
                "$result AS result"
            } else {
                columns.flatMap { (column, value) -> value.columns(column).map { it.substringAfterLast(" AS ") } }.joinToString(", ") { "$name.$it" }
            }
            val outer = buildList {
                add("SELECT $outerColumns")
                add("FROM (\n${indent(inner)}\n) $name")
                if (sorts.isNotEmpty()) add("ORDER BY ${sorts.joinToString(", ") { "$name.$it" }}")
                addAll(page(projection.skip?.let(::count), projection.limit?.let(::count)))
            }.joinToString("\n")
            // What each item is bound to in the query that reads this one: its columns, which that query names.
            val scope = items.zip(columns).associate { (item, pair) -> item.name to pair.second.columnsOf(PENDING, pair.first) }
            return outer to scope
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
            is Expression.Parameter -> Value.Json(parameters.json(expression.name))
            is Expression.Variable -> scope[expression.name] ?: throw DrivineException("${expression.name} is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.")
            is Expression.Property -> property(expression(expression.subject, scope), expression.key)
            is Expression.HasLabels -> when (val subject = expression(expression.subject, scope)) {
                is Value.Node -> Value.Bool(expression.labels.joinToString(" AND ") { hasLabel(subject.labels, it) })
                else -> throw DrivineException("A label is tested of a node.")
            }
            is Expression.ListLiteral -> Value.Json(jsonArray(expression.items.map { json(expression(it, scope)).sql }), ordered = true)
            is Expression.MapLiteral -> Value.Json(jsonObject(expression.entries.map { (key, value) -> key to json(expression(value, scope)).sql }), ordered = true)
            is Expression.MapProjection -> mapProjection(expression, scope)
            is Expression.Binary -> binary(expression, scope)
            is Expression.Unary -> unary(expression, scope)
            is Expression.Index -> {
                val index = expression.index
                // A key written as text is the property of that name.
                if (index is Expression.Literal && index.value is String) {
                    property(expression(expression.subject, scope), index.value)
                } else {
                    val subject = comparable(json(expression(expression.subject, scope)))
                    val at = comparable(json(expression(index, scope)))
                    Value.Json("CASE ${typeOf(at)} WHEN 'number' THEN ${element(subject, at)} WHEN 'string' THEN ${keyed(subject, at)} END")
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
                Value.Json("(SELECT ${aggregateList(projection, "$name.item_no")} FROM ${elements(list, name)}$filter)", ordered = true)
            }
            is Expression.PatternComprehension -> {
                val inner = Select(scope)
                patterns(inner, listOf(expression.pattern))
                expression.where?.let { inner.where += bool(expression(it, inner.scope)) }
                val projection = json(expression(expression.projection, inner.scope)).sql
                val text = (listOf("SELECT ${aggregateList(projection)}") + inner.from() + listOfNotNull(inner.whereClause())).joinToString(" ")
                Value.Json("($text)", ordered = true)
            }
            is Expression.PatternPredicate -> exists(listOf(expression.pattern), null, scope)
            is Expression.Exists -> exists(expression.patterns, expression.where, scope)
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
            if (projection.elements.none { it is Expression.MapProjection.Element.All }) return Value.Json(jsonObject(entries), ordered = true)
            // Every property, then the entries over them.
            return Value.Json(withEntries(comparable(json(subject)), entries))
        }

        private fun binary(binary: Expression.Binary, scope: Map<String, Value>): Value {
            if (binary.operator == Operator.AND || binary.operator == Operator.OR || binary.operator == Operator.XOR) {
                val left = bool(expression(binary.left, scope))
                val right = bool(expression(binary.right, scope))
                return Value.Bool(
                    when (binary.operator) {
                        Operator.AND -> "($left AND $right)"
                        Operator.OR -> "($left OR $right)"
                        else -> "($left <> $right)"
                    }
                )
            }
            val left = expression(binary.left, scope)
            val right = expression(binary.right, scope)
            val a = comparable(json(left))
            val b = comparable(json(right))
            return when (binary.operator) {
                Operator.EQUALS -> Value.Bool(equality(left, right))
                Operator.NOT_EQUALS -> Value.Bool("NOT (${equality(left, right)})")
                // Values of two kinds are not ordered, as in Cypher: the comparison is null.
                Operator.LESS_THAN, Operator.GREATER_THAN, Operator.LESS_THAN_OR_EQUAL, Operator.GREATER_THAN_OR_EQUAL -> {
                    val operator = mapOf(
                        Operator.LESS_THAN to "<", Operator.GREATER_THAN to ">", Operator.LESS_THAN_OR_EQUAL to "<=", Operator.GREATER_THAN_OR_EQUAL to ">=",
                    ).getValue(binary.operator)
                    Value.Bool("CASE WHEN ${typeOf(a)} = ${typeOf(b)} AND ${typeOf(a)} IN ('number', 'string', 'boolean') THEN $a $operator $b END")
                }
                Operator.ADD -> Value.Json(add(a, b))
                Operator.SUBTRACT -> Value.Json(scalar("(${number(a)} - ${number(b)})"))
                Operator.MULTIPLY -> Value.Json(scalar("(${number(a)} * ${number(b)})"))
                // A whole number divided by one is a whole number, as in Cypher.
                Operator.DIVIDE -> Value.Json(
                    "CASE WHEN ${integral(a)} AND ${integral(b)} THEN ${scalar("trunc(${number(a)} / ${number(b)})")} ELSE ${scalar("(${number(a)} / ${number(b)})")} END"
                )
                Operator.MODULO -> Value.Json(scalar(modulo(number(a), number(b))))
                Operator.POWER -> Value.Json(scalar(float("power(${number(a)}, ${number(b)})")))
                Operator.IN -> {
                    val i = alias("i")
                    Value.Bool("EXISTS (SELECT 1 FROM ${elements(b, i)} WHERE $i.item = $a)")
                }
                Operator.STARTS_WITH -> Value.Bool(startsWith(text(a), text(b)))
                Operator.ENDS_WITH -> Value.Bool(endsWith(text(a), text(b)))
                Operator.CONTAINS -> Value.Bool(containsText(text(a), text(b)))
                else -> error("logical operators are handled above")
            }
        }

        /**
         * Whether two values are equal: as JSON, so `'7'` is not `7` and `1` is `1.0`. A node is equal to a
         * node by its row. Where a property is compared with a value that does not depend on the row, a
         * test an index answers stands beside it, where the store has one.
         */
        private fun equality(left: Value, right: Value): String {
            if (left is Value.Node && right is Value.Node) return "${left.nid} = ${right.nid}"
            if (left is Value.Relationship && right is Value.Relationship) return "${left.eid} = ${right.eid}"
            val a = comparable(json(left))
            val b = comparable(json(right))
            val independent = right is Value.Json && !right.sql.contains(".props") && !right.sql.contains(".item")
            val indexed = if (independent) indexedEquality(a, b) else null
            return if (indexed != null) "$indexed AND $a = $b" else "$a = $b"
        }

        private fun unary(unary: Expression.Unary, scope: Map<String, Value>): Value {
            val operand = expression(unary.operand, scope)
            return when (unary.operator) {
                Expression.Unary.Operator.NOT -> Value.Bool("(NOT ${bool(operand)})")
                Expression.Unary.Operator.NEGATE -> Value.Json(scalar("(-${number(comparable(json(operand)))})"))
                Expression.Unary.Operator.IS_NULL -> Value.Bool("(${isNull(operand)})")
                Expression.Unary.Operator.IS_NOT_NULL -> Value.Bool("(NOT ${isNull(operand)})")
            }
        }

        private fun isNull(value: Value): String = when (value) {
            is Value.Node -> "${value.nid} IS NULL"
            is Value.Relationship -> "${value.eid} IS NULL"
            is Value.Bool -> "${value.sql} IS NULL"
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
                "collect" -> Value.Json(collect(collectable(one()), function.distinct), ordered = true)
                "sum" -> Value.Json(scalar("COALESCE(sum($distinct${number(comparable(json(one())))}), 0)"))
                "avg" -> Value.Json(scalar(float("avg($distinct${number(comparable(json(one())))})")))
                "min", "max" -> Value.Json(extreme(comparable(json(one())), min = name == "min"))
                else -> function(name, comparable(json(one())))?.let { Value.Json(it) } ?: unsupported("The function ${function.name}()")
            }
        }

        /** What `count` counts of a value: a node by its row, anything else by its value, nulls not counted. */
        private fun countable(value: Value): String = when (value) {
            is Value.Node -> value.nid
            is Value.Relationship -> value.eid
            is Value.Path -> value.nids
            else -> nullIfJsonNull(comparable(json(value)))
        }

        /** What `collect` gathers of a value: nulls are left out, as Cypher's `collect` leaves them out. */
        private fun collectable(value: Value): String = when (value) {
            is Value.Node -> "CASE WHEN ${value.nid} IS NOT NULL THEN ${json(value).sql} END"
            else -> json(value).sql
        }

        // ----- Conversions -----

        /** A value as JSON, for a projection or a comparison. A node is its properties, its stamp among them, as drivine gives a node. */
        fun json(value: Value): Value.Json = when (value) {
            is Value.Json -> value
            is Value.Bool -> Value.Json(scalar(value.sql))
            is Value.Node -> Value.Json(nodeJson(value))
            is Value.Relationship -> Value.Json("CASE WHEN ${value.eid} IS NOT NULL THEN ${value.props} END")
            // Each graph engine's driver gives a path as a value of its own, so a path is given by its parts.
            is Value.Path -> throw DrivineException("A path is not a value drivine gives the same on each store: use nodes(p), relationships(p) and length(p).")
        }

        /** A value as a condition: a boolean as itself, JSON's `true` and `false` as theirs, anything else as null. */
        fun bool(value: Value): String = when (value) {
            is Value.Bool -> value.sql
            is Value.Json -> boolOf(comparable(value))
            else -> throw DrivineException("A node, a relationship or a path is not a condition.")
        }

        /** What an order sorts a value by: JSON's order for a value, a node by its properties. */
        private fun sortable(value: Value): String = when (value) {
            is Value.Bool -> value.sql
            else -> comparable(json(value))
        }
    }

    /** A node as JSON: its properties, its stamp among them, as drivine gives a node; null where there is no node. */
    protected abstract fun nodeJson(node: Value.Node): String

    /** The rows a part of a statement reads: its sources and joins, its conditions, and the variables it binds. */
    private class Select(private val outer: Map<String, Value>) {
        val sources = mutableListOf<String>()
        val where = mutableListOf<String>()
        val bound = linkedMapOf<String, Value>()

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
    }

    protected companion object {
        /** Stands for the name of the query a `WITH` is read from, before it is named. */
        const val PENDING = "__with__"

        fun literal(text: String) = "'${text.replace("'", "''")}'"

        fun indent(text: String) = text.prependIndent("    ")

        fun unsupported(construct: String): Nothing = throw DrivineException("$construct is Cypher, and does not run on Oracle or Postgres yet.")

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
            is Expression.Quantifier -> listOf(expression.list)
            else -> emptyList()
        }
    }

    // ----- Columns of a value -----

    /** The select-list entries that carry this value out of a query, under [name]. */
    private fun Value.columns(name: String): List<String> = when (this) {
        is Value.Json -> listOf("$sql AS $name")
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
            is Value.Bool -> Value.Bool("$prefix$base")
            is Value.Node -> Value.Node("$prefix${base}_nid", "$prefix${base}_labels", "$prefix${base}_props", "$prefix${base}_stamp")
            is Value.Relationship -> Value.Relationship("$prefix${base}_eid", "$prefix${base}_src", "$prefix${base}_dst", "$prefix${base}_type", "$prefix${base}_props")
            is Value.Path -> Value.Path("$prefix${base}_nids", "$prefix${base}_eids")
        }
    }

    /** This value with [PENDING] read as [alias]. */
    private fun Value.withAlias(alias: String): Value = when (this) {
        is Value.Json -> copy(sql = sql.replace(PENDING, alias))
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
        is Value.Bool -> listOf(sql)
        is Value.Node -> listOf(nid, labels, props, stamp)
        is Value.Relationship -> listOf(eid, src, dst, type, props)
        is Value.Path -> listOf(nids, eids)
    }
}
