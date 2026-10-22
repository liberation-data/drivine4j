package org.drivine.cypher.sql

import org.drivine.DrivineException
import org.drivine.cypher.CypherReader
import org.drivine.cypher.ast.Clause
import org.drivine.cypher.ast.Expression
import org.drivine.cypher.ast.Expression.Binary.Operator
import org.drivine.cypher.ast.Pattern
import org.drivine.cypher.ast.Projection
import org.drivine.cypher.ast.Query
import org.drivine.cypher.ast.RelationshipPattern
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps
import org.slf4j.LoggerFactory

/**
 * Compiles a Cypher statement to SQL over a graph's tables on Postgres, `<graph>_node` and `<graph>_edge`,
 * so a statement written for a graph engine runs on Postgres and gives the same rows.
 *
 * - A `MATCH` is a join of node and relationship rows, its patterns' labels, types, directions and
 *   properties its conditions. Two relationships of one `MATCH` are two relationships, as in Cypher.
 * - A variable-length relationship, `*min..max`, is a recursive walk from the end of it that is pinned
 *   down, which takes no relationship twice.
 * - An `OPTIONAL MATCH` is a lateral join that keeps the row with nulls where nothing is found.
 * - A `WITH` is a query of its own, which the rest of the statement reads from.
 * - A projection that aggregates is grouped by what it holds outside its aggregates, as Cypher groups it.
 * - A value is JSON: `jsonb`, so a comparison is JSON's and `'7'` is not `7`, and a missing property is
 *   null and meets no comparison. A map or a list the statement builds is `json`, which keeps its keys in
 *   the order written, as a graph engine gives them.
 * - The statement returns one value a row, as drivine's statements are written to: the one item a
 *   `RETURN` has, or a list of its items when it has more, as drivine gives a row of several columns.
 *   A node is its properties, as drivine gives a node.
 */
class PostgresCypherCompiler(graph: String) {

    private val nodes = "${graph}_node"
    private val edges = "${graph}_edge"
    private val logger = LoggerFactory.getLogger(PostgresCypherCompiler::class.java)

    /** The statement [cypher], with its parameters' values [parameters], as SQL. */
    fun compile(cypher: String, parameters: Map<String, Any?> = emptyMap()): CompiledCypher =
        compile(CypherReader.read(cypher), parameters)

    fun compile(query: Query, parameters: Map<String, Any?>): CompiledCypher {
        val writer = Writer(Parameters(parameters))
        return CompiledCypher(writer.query(query), writer.parameters.values)
    }

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
            select.source("jsonb_array_elements(${jsonb(json(list))}) $name (value)")
            select.bind(unwind.variable, Value.Json("$name.value"))
        }

        /**
         * The relationships one part of a pattern matched: one, its id [eids], or with [many] those a
         * variable-length relationship walked, an array of ids. Of the [types] it may have.
         */
        private inner class Hop(val eids: String, val many: Boolean, val types: List<String>) {
            /** That no relationship of this hop is one of [other]'s. */
            fun distinctFrom(other: Hop): String = when {
                !many && !other.many -> "$eids <> ${other.eids}"
                !many -> "$eids <> ALL (${other.eids})"
                !other.many -> "${other.eids} <> ALL ($eids)"
                else -> "NOT ($eids && ${other.eids})"
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
            if (pattern.variable != null) unsupported("A path variable (${pattern.variable} = …)")
            // A node bound before the pattern, or given properties, is where a walk starts best.
            val pinned = pattern.nodes.map { node -> node.properties != null || node.variable?.let { select.scope[it] } != null }
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
                    hops += if (pinned[i + 1] && !pinned[i]) walk(select, relationship.reversed(), bound[i + 1].nid, bound[i].nid)
                    else walk(select, relationship, bound[i].nid, bound[i + 1].nid)
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
                if (relationship.types.isNotEmpty()) {
                    select.where += if (relationship.types.size == 1) "${value.type} = ${literal(relationship.types.single())}"
                    else "${value.type} IN (${relationship.types.joinToString(", ") { literal(it) }})"
                }
                relationship.properties?.let { select.where += propertiesMatch(value.props, it, select.scope) }
                hops += Hop(value.eid, false, relationship.types)
            }
        }

        /**
         * A variable-length relationship, `-[:T*min..max]->`, from the node [from] to the node [to]: a walk
         * from [from] that takes a relationship no more than once, as Cypher takes it, to a depth from
         * `min` (1 if left out) to `max` (any if left out), its rows each way it reaches [to].
         */
        private fun walk(select: Select, relationship: RelationshipPattern, from: String, to: String): Hop {
            relationship.variable?.let { unsupported("A variable on a variable-length relationship ($it*)") }
            val range = relationship.range!!
            val min = range.min ?: 1
            val max = range.max
            val name = alias("p")
            val w = alias("w")
            val e = alias("e")
            val (join, far) = when (relationship.direction) {
                RelationshipPattern.Direction.OUTGOING -> "$e.src = $w.nid" to "$e.dst"
                RelationshipPattern.Direction.INCOMING -> "$e.dst = $w.nid" to "$e.src"
                // Either way, and a relationship from a node to itself once.
                RelationshipPattern.Direction.EITHER -> "($e.src = $w.nid OR $e.dst = $w.nid)" to "CASE WHEN $e.src = $w.nid THEN $e.dst ELSE $e.src END"
            }
            val conditions = listOfNotNull(
                "$e.eid <> ALL ($w.eids)",
                max?.let { "$w.depth < $it" },
                when (relationship.types.size) {
                    0 -> null
                    1 -> "$e.type = ${literal(relationship.types.single())}"
                    else -> "$e.type IN (${relationship.types.joinToString(", ") { literal(it) }})"
                },
                relationship.properties?.let { propertiesMatch("$e.props", it, select.scope) },
            )
            val text = listOf(
                "WITH RECURSIVE $w (nid, depth, eids) AS (",
                "    SELECT $from, 0, CAST(ARRAY[] AS bigint[])",
                "    UNION ALL",
                "    SELECT $far, $w.depth + 1, $w.eids || $e.eid",
                "    FROM $w JOIN $edges $e ON $join",
                "    WHERE ${conditions.joinToString(" AND ")}",
                ")",
                "SELECT nid, eids FROM $w WHERE depth >= $min",
            ).joinToString("\n")
            select.source("LATERAL (\n${indent(text)}\n) $name")
            select.where += "$name.nid = $to"
            return Hop("$name.eids", true, relationship.types)
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
                equality(Value.Json("($props -> ${literal(key)})"), expression(value, scope), propertyText = "($props ->> ${literal(key)})")
            }
            else -> "$props @> ${jsonb(json(expression(properties, scope)))}"
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
            // Where rows are compared (DISTINCT, GROUP BY), JSON is compared as jsonb: json has no equality.
            val compared = projection.distinct || aggregating
            val columns = compiled.mapIndexed { i, (_, value) -> "c${i + 1}" to (if (compared && value is Value.Json && value.ordered) Value.Json(jsonb(value)) else value) }
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
                val result = if (values.size == 1) values.single().sql else "json_build_array(${values.joinToString(", ") { it.sql }})"
                "$result AS result"
            } else {
                columns.flatMap { (column, value) -> value.columns(column).map { it.substringAfterLast(" AS ") } }.joinToString(", ") { "$name.$it" }
            }
            val outer = buildList {
                add("SELECT $outerColumns")
                add("FROM (\n${indent(inner)}\n) $name")
                if (sorts.isNotEmpty()) add("ORDER BY ${sorts.joinToString(", ") { "$name.$it" }}")
                projection.skip?.let { add("OFFSET ${count(it)}") }
                projection.limit?.let { add("LIMIT ${count(it)}") }
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
            is Expression.Literal -> Value.Json("CAST(${literal(Neo4jObjectMapper.instance.writeValueAsString(expression.value))} AS jsonb)")
            is Expression.Parameter -> Value.Json(parameters.json(expression.name))
            is Expression.Variable -> scope[expression.name] ?: throw DrivineException("${expression.name} is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.")
            is Expression.Property -> property(expression(expression.subject, scope), expression.key)
            is Expression.HasLabels -> when (val subject = expression(expression.subject, scope)) {
                is Value.Node -> Value.Bool(expression.labels.joinToString(" AND ") { hasLabel(subject.labels, it) })
                else -> throw DrivineException("A label is tested of a node.")
            }
            is Expression.ListLiteral -> Value.Json("json_build_array(${expression.items.joinToString(", ") { json(expression(it, scope)).sql }})", ordered = true)
            is Expression.MapLiteral -> Value.Json(
                "json_build_object(${expression.entries.joinToString(", ") { (key, value) -> "${literal(key)}, ${json(expression(value, scope)).sql}" }})",
                ordered = true,
            )
            is Expression.MapProjection -> mapProjection(expression, scope)
            is Expression.Binary -> binary(expression, scope)
            is Expression.Unary -> unary(expression, scope)
            is Expression.Index -> {
                val subject = jsonb(json(expression(expression.subject, scope)))
                val index = jsonb(json(expression(expression.index, scope)))
                Value.Json("CASE jsonb_typeof($index) WHEN 'number' THEN $subject -> CAST($index #>> '{}' AS int) WHEN 'string' THEN $subject -> ($index #>> '{}') END")
            }
            is Expression.Slice -> unsupported("A slice of a list (list[from..to])")
            is Expression.Function -> function(expression, scope)
            Expression.CountStar -> Value.Json("to_jsonb(count(*))")
            is Expression.Case -> case(expression, scope)
            is Expression.ListComprehension -> {
                val list = jsonb(json(expression(expression.list, scope)))
                val name = alias("x")
                val inner = scope + (expression.variable to Value.Json("$name.value"))
                val projection = expression.projection?.let { json(expression(it, inner)).sql } ?: "$name.value"
                val filter = expression.where?.let { " WHERE ${bool(expression(it, inner))}" }.orEmpty()
                Value.Json("(SELECT COALESCE(json_agg($projection ORDER BY $name.ordinality), '[]'::json) FROM jsonb_array_elements($list) WITH ORDINALITY $name (value, ordinality)$filter)", ordered = true)
            }
            is Expression.PatternComprehension -> {
                val inner = Select(scope)
                patterns(inner, listOf(expression.pattern))
                expression.where?.let { inner.where += bool(expression(it, inner.scope)) }
                val projection = json(expression(expression.projection, inner.scope)).sql
                val text = (listOf("SELECT COALESCE(json_agg($projection), '[]'::json)") + inner.from() + listOfNotNull(inner.whereClause())).joinToString(" ")
                Value.Json("($text)", ordered = true)
            }
            is Expression.PatternPredicate -> exists(listOf(expression.pattern), null, scope)
            is Expression.Exists -> exists(expression.patterns, expression.where, scope)
            is Expression.Quantifier -> {
                val list = jsonb(json(expression(expression.list, scope)))
                val name = alias("q")
                val inner = scope + (expression.variable to Value.Json("$name.value"))
                val condition = expression.where?.let { bool(expression(it, inner)) } ?: "TRUE"
                val from = "FROM jsonb_array_elements($list) $name (value)"
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
            is Value.Node ->
                if (key == Stamps.PROPERTY) Value.Json("to_jsonb(${subject.stamp})") else Value.Json("(${subject.props} -> ${literal(key)})")
            is Value.Relationship -> Value.Json("(${subject.props} -> ${literal(key)})")
            is Value.Json -> Value.Json("(${jsonb(subject)} -> ${literal(key)})")
            is Value.Bool -> throw DrivineException("A truth value has no properties.")
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
            val built = "json_build_object(${entries.joinToString(", ") { (key, value) -> "${literal(key)}, $value" }})"
            if (projection.elements.none { it is Expression.MapProjection.Element.All }) return Value.Json(built, ordered = true)
            // Every property, then the entries over them.
            return Value.Json("(${jsonb(json(subject))} || ${jsonb(Value.Json(built, ordered = true))})")
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
            val a = jsonb(json(left))
            val b = jsonb(json(right))
            return when (binary.operator) {
                Operator.EQUALS -> Value.Bool(equality(left, right))
                Operator.NOT_EQUALS -> Value.Bool("NOT (${equality(left, right)})")
                // Values of two kinds are not ordered, as in Cypher: the comparison is null.
                Operator.LESS_THAN, Operator.GREATER_THAN, Operator.LESS_THAN_OR_EQUAL, Operator.GREATER_THAN_OR_EQUAL -> {
                    val operator = mapOf(
                        Operator.LESS_THAN to "<", Operator.GREATER_THAN to ">", Operator.LESS_THAN_OR_EQUAL to "<=", Operator.GREATER_THAN_OR_EQUAL to ">=",
                    ).getValue(binary.operator)
                    Value.Bool("CASE WHEN jsonb_typeof($a) = jsonb_typeof($b) AND jsonb_typeof($a) IN ('number', 'string', 'boolean') THEN $a $operator $b END")
                }
                Operator.ADD -> Value.Json(
                    "CASE WHEN jsonb_typeof($a) = 'number' AND jsonb_typeof($b) = 'number' THEN to_jsonb(${number(a)} + ${number(b)}) " +
                        "WHEN jsonb_typeof($a) = 'array' AND jsonb_typeof($b) = 'array' THEN $a || $b " +
                        "WHEN jsonb_typeof($a) = 'array' THEN $a || jsonb_build_array($b) " +
                        "WHEN jsonb_typeof($b) = 'array' THEN jsonb_build_array($a) || $b " +
                        "WHEN jsonb_typeof($a) IN ('string', 'number') AND jsonb_typeof($b) IN ('string', 'number') THEN to_jsonb(($a #>> '{}') || ($b #>> '{}')) END"
                )
                Operator.SUBTRACT -> arithmetic(a, b, "-")
                Operator.MULTIPLY -> arithmetic(a, b, "*")
                // A whole number divided by one is a whole number, as in Cypher.
                Operator.DIVIDE -> Value.Json(
                    "CASE WHEN ${integral(a)} AND ${integral(b)} THEN to_jsonb(trunc(${number(a)} / ${number(b)})) ELSE to_jsonb(${number(a)} / ${number(b)}) END"
                )
                Operator.MODULO -> arithmetic(a, b, "%")
                Operator.POWER -> Value.Json("to_jsonb(power(${number(a)}, ${number(b)})::double precision)")
                Operator.IN -> Value.Bool("EXISTS (SELECT 1 FROM jsonb_array_elements($b) i (value) WHERE i.value = $a)")
                Operator.STARTS_WITH -> Value.Bool("starts_with(${text(a)}, ${text(b)})")
                Operator.ENDS_WITH -> Value.Bool("right(${text(a)}, length(${text(b)})) = ${text(b)}")
                Operator.CONTAINS -> Value.Bool("strpos(${text(a)}, ${text(b)}) > 0")
                else -> error("logical operators are handled above")
            }
        }

        private fun arithmetic(a: String, b: String, operator: String) = Value.Json("to_jsonb(${number(a)} $operator ${number(b)})")

        /**
         * Whether two values are equal: as JSON, so `'7'` is not `7` and `1` is `1.0`. A node is equal to a
         * node by its row. Where a property is compared with a value that does not depend on the row, the
         * property's text is compared too, which a range index or a uniqueness constraint answers.
         */
        private fun equality(left: Value, right: Value, propertyText: String? = null): String {
            if (left is Value.Node && right is Value.Node) return "${left.nid} = ${right.nid}"
            if (left is Value.Relationship && right is Value.Relationship) return "${left.eid} = ${right.eid}"
            val a = jsonb(json(left))
            val b = jsonb(json(right))
            val text = propertyText ?: (left as? Value.Json)?.sql?.let(::propertyTextOf)
            val independent = right is Value.Json && !right.sql.contains(".props") && !right.sql.contains(".value")
            return if (text != null && independent) "$text = ($b #>> '{}') AND $a = $b" else "$a = $b"
        }

        /** The text of a property read with `->`, as an index over it reads it with `->>`; null for anything else. */
        private fun propertyTextOf(sql: String): String? =
            Regex("""^\((\w+\.props) -> ('(?:[^']|'')*')\)$""").matchEntire(sql)?.let { "(${it.groupValues[1]} ->> ${it.groupValues[2]})" }

        private fun unary(unary: Expression.Unary, scope: Map<String, Value>): Value {
            val operand = expression(unary.operand, scope)
            return when (unary.operator) {
                Expression.Unary.Operator.NOT -> Value.Bool("(NOT ${bool(operand)})")
                Expression.Unary.Operator.NEGATE -> Value.Json("to_jsonb(-${number(jsonb(json(operand)))})")
                Expression.Unary.Operator.IS_NULL -> Value.Bool("(${isNull(operand)})")
                Expression.Unary.Operator.IS_NOT_NULL -> Value.Bool("(NOT ${isNull(operand)})")
            }
        }

        private fun isNull(value: Value): String = when (value) {
            is Value.Node -> "${value.nid} IS NULL"
            is Value.Relationship -> "${value.eid} IS NULL"
            is Value.Bool -> "${value.sql} IS NULL"
            // A property stored as JSON's null is no property, as on a graph engine.
            is Value.Json -> "COALESCE(jsonb_typeof(${jsonb(value)}), 'null') = 'null'"
        }

        private fun case(case: Expression.Case, scope: Map<String, Value>): Value {
            val subject = case.subject?.let { expression(it, scope) }
            val alternatives = case.alternatives.joinToString(" ") { (key, value) ->
                val condition = if (subject != null) equality(subject, expression(key, scope)) else bool(expression(key, scope))
                "WHEN $condition THEN ${jsonb(json(expression(value, scope)))}"
            }
            val otherwise = case.otherwise?.let { " ELSE ${jsonb(json(expression(it, scope)))}" }.orEmpty()
            return Value.Json("CASE $alternatives$otherwise END")
        }

        private fun function(function: Expression.Function, scope: Map<String, Value>): Value {
            val arguments = function.arguments.map { expression(it, scope) }
            fun one(): Value = arguments.singleOrNull() ?: throw DrivineException("${function.name}() takes one argument.")
            fun jsonbOf(i: Int) = jsonb(json(arguments.getOrElse(i) { throw DrivineException("${function.name}() takes ${i + 1} arguments.") }))
            val distinct = if (function.distinct) "DISTINCT " else ""
            return when (function.name.lowercase()) {
                "properties" -> when (val subject = one()) {
                    is Value.Node, is Value.Relationship -> json(subject)
                    else -> subject
                }
                "labels" -> (one() as? Value.Node)?.let { Value.Json(it.labels) } ?: throw DrivineException("labels() takes a node.")
                "type" -> (one() as? Value.Relationship)?.let { Value.Json("to_jsonb(${it.type})") } ?: throw DrivineException("type() takes a relationship.")
                "id", "elementid" -> when (val subject = one()) {
                    is Value.Node -> Value.Json("to_jsonb(${subject.nid})")
                    is Value.Relationship -> Value.Json("to_jsonb(${subject.eid})")
                    else -> throw DrivineException("${function.name}() takes a node or a relationship.")
                }
                "keys" -> Value.Json("(SELECT COALESCE(jsonb_agg(k ORDER BY k), '[]'::jsonb) FROM jsonb_object_keys(${jsonb(json(one()))}) k)")
                "size" -> {
                    val a = jsonbOf(0)
                    Value.Json("CASE jsonb_typeof($a) WHEN 'array' THEN to_jsonb(jsonb_array_length($a)) WHEN 'string' THEN to_jsonb(length($a #>> '{}')) END")
                }
                "coalesce" -> Value.Json("COALESCE(${arguments.joinToString(", ") { "NULLIF(${jsonb(json(it))}, 'null'::jsonb)" }})")
                "tolower" -> Value.Json("to_jsonb(lower(${text(jsonbOf(0))}))")
                "toupper" -> Value.Json("to_jsonb(upper(${text(jsonbOf(0))}))")
                "trim" -> Value.Json("to_jsonb(btrim(${text(jsonbOf(0))}))")
                "tostring" -> Value.Json("to_jsonb(${jsonbOf(0)} #>> '{}')")
                "tointeger" -> Value.Json("to_jsonb(trunc(CAST(NULLIF(${jsonbOf(0)} #>> '{}', '') AS numeric)))")
                "tofloat" -> Value.Json("to_jsonb(CAST(NULLIF(${jsonbOf(0)} #>> '{}', '') AS double precision))")
                "head" -> Value.Json("(${jsonbOf(0)} -> 0)")
                "last" -> Value.Json("(${jsonbOf(0)} -> -1)")
                "abs" -> Value.Json("to_jsonb(abs(${number(jsonbOf(0))}))")
                "count" -> Value.Json("to_jsonb(count($distinct${countable(one())}))")
                "collect" -> Value.Json("COALESCE(json_agg($distinct${collectable(one())}) FILTER (WHERE ${collectable(one())} IS NOT NULL), '[]'::json)", ordered = true)
                "sum" -> Value.Json("to_jsonb(COALESCE(sum($distinct${number(jsonbOf(0))}), 0))")
                "avg" -> Value.Json("to_jsonb(avg($distinct${number(jsonbOf(0))})::double precision)")
                // Postgres has no min or max of jsonb, which orders: the first of the values in order is the least.
                "min", "max" -> {
                    val value = jsonbOf(0)
                    val order = if (function.name.lowercase() == "min") "ASC" else "DESC"
                    Value.Json("(array_agg($value ORDER BY $value $order) FILTER (WHERE COALESCE(jsonb_typeof($value), 'null') <> 'null'))[1]")
                }
                else -> unsupported("The function ${function.name}()")
            }
        }

        /** What `count` counts of a value: a node by its row, anything else by its value, nulls not counted. */
        private fun countable(value: Value): String = when (value) {
            is Value.Node -> value.nid
            is Value.Relationship -> value.eid
            else -> "NULLIF(${jsonb(json(value))}, 'null'::jsonb)"
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
            is Value.Bool -> Value.Json("to_jsonb(${value.sql})")
            is Value.Node -> Value.Json(
                "CASE WHEN ${value.nid} IS NOT NULL THEN ${value.props} || jsonb_strip_nulls(jsonb_build_object(${literal(Stamps.PROPERTY)}, ${value.stamp})) END"
            )
            is Value.Relationship -> Value.Json("CASE WHEN ${value.eid} IS NOT NULL THEN ${value.props} END")
        }

        /** A JSON value as `jsonb`: a `json` one is cast. */
        fun jsonb(value: Value.Json): String = if (value.ordered) "CAST(${value.sql} AS jsonb)" else value.sql

        /** A value as a condition: a boolean as itself, JSON's `true` and `false` as theirs, anything else as null. */
        fun bool(value: Value): String = when (value) {
            is Value.Bool -> value.sql
            is Value.Json -> "CASE WHEN jsonb_typeof(${jsonb(value)}) = 'boolean' THEN CAST(${jsonb(value)} AS boolean) END"
            else -> throw DrivineException("A node or a relationship is not a condition.")
        }

        /** What an order sorts a value by: JSON's order for a value, a node by its properties. */
        private fun sortable(value: Value): String = when (value) {
            is Value.Bool -> value.sql
            else -> jsonb(json(value))
        }

        private fun number(jsonb: String) = "CASE WHEN jsonb_typeof($jsonb) = 'number' THEN CAST($jsonb #>> '{}' AS numeric) END"

        private fun integral(jsonb: String) = "(jsonb_typeof($jsonb) = 'number' AND ($jsonb #>> '{}') ~ '^-?[0-9]+$')"

        private fun text(jsonb: String) = "CASE WHEN jsonb_typeof($jsonb) = 'string' THEN $jsonb #>> '{}' END"

        private fun hasLabel(labels: String, label: String) = "$labels @> ${literal(Neo4jObjectMapper.instance.writeValueAsString(listOf(label)))}"
    }

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

    private companion object {
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
    }

    /** What a projection that aggregates is grouped by, to keep this value: a node by its columns. */
    private fun Value.groupKeys(): List<String> = when (this) {
        is Value.Json -> listOf(sql)
        is Value.Bool -> listOf(sql)
        is Value.Node -> listOf(nid, labels, props, stamp)
        is Value.Relationship -> listOf(eid, src, dst, type, props)
    }
}
