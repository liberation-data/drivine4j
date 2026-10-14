package org.drivine.query

import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.NodeFragment
import org.drivine.backend.GraphStatement
import org.drivine.model.FragmentField
import org.drivine.model.FragmentModel
import org.drivine.model.Stamps
import org.drivine.query.dsl.Aggregate
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.GroupFunction
import org.drivine.query.dsl.GroupOrder
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.Key
import org.drivine.query.dsl.KeySource
import org.drivine.query.dsl.NullPlacement
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.grammar.CypherGrammar
import org.drivine.schema.SchemaGrammar

/** The field stored as [propertyName]; null for a property no field declares. The stamp's own name is held quoted in the model. */
internal fun FragmentModel.fieldStoredAs(propertyName: String): FragmentField? =
    fields.firstOrNull { (if (it.stamp) Stamps.PROPERTY else it.propertyName) == propertyName }

/**
 * What a grouped read asks, checked against the model before a backend writes it: every refusal of
 * `GroupOperations.load` is made here, so each backend refuses the same reads.
 *
 * @param source the fragment whose nodes are grouped
 */
internal class GroupQuery private constructor(val source: FragmentModel, val spec: GroupQuerySpec<*>) {

    /** The field of the source stored as [propertyName]; null for a property no field declares. */
    fun field(propertyName: String): FragmentField? = source.fieldStoredAs(propertyName)

    /** The fragment a path key reads as. */
    fun target(path: KeySource.Path): FragmentModel = FragmentModel.from(path.target)

    /**
     * The order of the groups: what the read asked for, and then every key it left out, ascending.
     * Groups that tie in what was asked for then come back in one order on every engine.
     */
    val orders: List<GroupOrder> =
        spec.orders + spec.keys.filter { key -> spec.orders.none { it.column === key } }.map { GroupOrder(it, OrderDirection.ASC) }

    companion object {
        /** [spec] as a read of [graphClass]'s nodes. */
        fun of(graphClass: Class<*>, spec: GroupQuerySpec<*>): GroupQuery {
            require(!graphClass.isAnnotationPresent(GraphView::class.java)) {
                "A grouped read groups the nodes of a @NodeFragment, and ${graphClass.simpleName} is a @GraphView. " +
                    "Group its root fragment, and reach a related node with key(path<…>(…))."
            }
            require(graphClass.isAnnotationPresent(NodeFragment::class.java)) { "A grouped read groups the nodes of a @NodeFragment; ${graphClass.simpleName} is not one." }
            val source = FragmentModel.from(graphClass)
            require(source.labels.isNotEmpty()) { "No labels defined for fragment ${source.className}. @NodeFragment must specify at least one label." }
            require(spec.keys.isNotEmpty()) { "A grouped read of ${graphClass.simpleName} declares no key. To count every node, use count." }
            return GroupQuery(source, spec).also { it.check() }
        }

        private val COLLECTIONS = listOf(Collection::class.java, Map::class.java)

        /**
         * What follows a key's column in the name of a column a backend returns beside it: whether the
         * key's text is stored empty, for a store that reads `""` as no value. Oracle returns one.
         */
        const val EMPTY = "e"

        /** As [EMPTY]: the key's value where it is stored as a number, for a store that reads a key no field declares as text. */
        const val NUMBER = "n"

        /** What a `having` compares an aggregate with a value by. */
        private val COMPARISONS = setOf(
            ComparisonOperator.EQUALS, ComparisonOperator.NOT_EQUALS, ComparisonOperator.GREATER_THAN,
            ComparisonOperator.GREATER_THAN_OR_EQUAL, ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_THAN_OR_EQUAL,
        )

        private const val ELSEWHERE = "that was declared in another grouped read: a handle is a column of the read that declared it."
    }

    private fun check() {
        val name = source.clazz.simpleName
        ownOnly(spec.conditions, "The where of a grouped read of $name")
        // A Java field does not say whether it can be null: only a Kotlin class is held to it.
        val declaresNullability = source.clazz.isAnnotationPresent(Metadata::class.java)
        spec.keys.forEach { key ->
            when (val from = key.source) {
                is KeySource.Property -> {
                    own(from.alias, "A key of a grouped read of $name")
                    val field = field(from.propertyName) ?: return@forEach
                    require(!severalValues(field)) { "'${field.name}' of $name holds several values, and a key is one value: it cannot group." }
                    require(key.nullable || !declaresNullability || !field.nullable) {
                        "'${field.name}' of $name is declared nullable, so key(…) cannot give it as a value that is never null. " +
                            "Use keyOrNull(…): the nodes with no ${field.name} are then a group of their own."
                    }
                }
                is KeySource.Path -> {
                    require(from.target.isAnnotationPresent(NodeFragment::class.java) && !from.target.isAnnotationPresent(GraphView::class.java)) {
                        "path<${from.target.simpleName}>: a path key ends at a @NodeFragment, and ${from.target.simpleName} is not one."
                    }
                    require(from.hops.isNotEmpty()) { "path<${from.target.simpleName}>() has no hops: it must say how a $name reaches a ${from.target.simpleName}." }
                    val target = target(from)
                    require(target.labels.isNotEmpty()) { "No labels defined for fragment ${target.className}. @NodeFragment must specify at least one label." }
                    val asked = from.hops.last().label
                    require(asked.isEmpty() || asked in target.labels) {
                        "The last hop of path<${from.target.simpleName}> asks for the label '$asked', and the node it ends at is found by the labels of " +
                            "${from.target.simpleName}: ${target.labels.joinToString()}. Leave the label out, or end the path at a fragment that carries it."
                    }
                    requireNotNull(target.nodeIdField) { "${from.target.simpleName} has no @NodeId field, which a path key is ordered by." }
                }
            }
        }
        spec.aggregates.forEach { aggregate ->
            aggregate.alias?.let { own(it, "An aggregate of a grouped read of $name") }
            aggregate.propertyName?.let(::field)?.let { field ->
                require(!severalValues(field)) {
                    "'${field.name}' of $name holds several values, and ${aggregate.function.name.lowercase()} reads one value of each node."
                }
            }
            ownOnly(aggregate.filter, "An aggregate's filter in a grouped read of $name")
        }
        spec.havings.forEach { having ->
            require(spec.aggregates.any { it === having.aggregate }) { "A having of a grouped read of $name compares an aggregate $ELSEWHERE" }
            require(having.operator in COMPARISONS) {
                "A having compares an aggregate with a value by ${COMPARISONS.joinToString { it.name }}; ${having.operator.name} is none of them."
            }
        }
        spec.orders.forEach { order ->
            require(spec.keys.any { it === order.column } || spec.aggregates.any { it === order.column }) { "The order of a grouped read of $name names a column $ELSEWHERE" }
        }
    }

    /** Whether [field] is a list, a map or an array. A parameterized type has no class of its own in the model, and its Kotlin class says what it is. */
    private fun severalValues(field: FragmentField): Boolean {
        val type = field.kotlinType?.java ?: field.type
        return type.isArray || COLLECTIONS.any { it.isAssignableFrom(type) }
    }

    private fun own(alias: String, what: String) =
        require(alias == FragmentQueryBuilder.NODE_ALIAS) { "$what names '$alias', which is not the fragment itself: it reads the properties of $NODES only." }

    /** Refuses a condition that is not on the source's own properties. */
    private fun ownOnly(conditions: List<WhereCondition>, what: String) {
        conditions.forEach { condition ->
            when (condition) {
                is WhereCondition.PropertyCondition -> own(condition.propertyPath.substringBefore('.'), what)
                is WhereCondition.ListMembershipCondition -> own(condition.propertyPath.substringBefore('.'), what)
                is WhereCondition.LabelCondition -> own(condition.alias, what)
                is WhereCondition.AnyLabelCondition -> own(condition.alias, what)
                is WhereCondition.OrCondition -> ownOnly(condition.conditions, what)
                is WhereCondition.NotCondition -> ownOnly(condition.conditions, what)
                is WhereCondition.RelationshipCondition ->
                    throw IllegalArgumentException("$what names the relationship '${condition.relationshipName}': it reads the properties of $NODES only.")
            }
        }
    }
}

private const val NODES = "the nodes that are grouped"

/**
 * Writes a grouped read as one Cypher statement, of the shape one would write by hand: match, group
 * in a `WITH`, filter the groups, return one map for each.
 *
 * ```cypher
 * MATCH (n:StoredMessage)<-[:HAS_MESSAGE]-(:ChatSession)-[:OWNED_BY]->(k0:GuideUser:User)
 * WHERE n.role = $param_n_role_0
 * WITH DISTINCT n, k0
 * WITH k0, count(n) AS a0
 * WHERE a0 >= $_h0
 * RETURN { k0: { … }, a0: a0 } AS row
 * ORDER BY a0 DESC, k0.id ASC
 * LIMIT $_limit
 * ```
 *
 * A column is named by its position, `k0…` for a key and `a0…` for an aggregate, as its handle is.
 * Every `MATCH` comes before the aggregation, and each path key has one of its own.
 */
internal class GroupQueryBuilder(private val grammar: CypherGrammar) {

    fun build(query: GroupQuery): GraphStatement {
        val spec = query.spec
        val bindings = linkedMapOf<String, Any?>()
        // The where and every aggregate's filter are numbered in one run, so two filters on one property bind two parameters.
        var parameters = 0
        fun predicate(conditions: List<WhereCondition>): String? {
            if (conditions.isEmpty()) return null
            val written = CypherGenerator.buildWhereClause(conditions, null, grammar, projectedCollectionMode = false, startIndex = parameters)
            bindings += CypherGenerator.extractBindings(conditions, null, parameters)
            parameters += CypherGenerator.parameterCount(conditions)
            return written.whereClause
        }

        val paths = spec.keys.filter { it.source is KeySource.Path }
        val lines = mutableListOf<String>()
        // A MATCH of its own for each path: within one, Cypher walks a relationship once, and two keys may be reached over the same one.
        val patterns = patterns(query, paths)
        val walkedOnce = paths.mapIndexed { index, key -> walkedOnce(index, (key.source as KeySource.Path).hops) }
        lines += "MATCH ${patterns.first()}"
        listOfNotNull(predicate(spec.conditions), walkedOnce.firstOrNull()).takeIf { it.isNotEmpty() }?.let { lines += "WHERE " + it.joinToString(" AND ") }
        patterns.drop(1).forEachIndexed { index, pattern ->
            lines += "MATCH $pattern"
            walkedOnce[index + 1]?.let { lines += "WHERE $it" }
        }
        // A node that reaches one key node by several ways is one member of that group, whatever is counted or summed.
        if (paths.isNotEmpty()) lines += "WITH DISTINCT $NODE, ${paths.joinToString(", ") { it.column }}"

        val keys = spec.keys.map { key ->
            when (val from = key.source) {
                is KeySource.Property -> "${property(from.propertyName)} AS ${key.column}"
                is KeySource.Path -> key.column
            }
        }
        val aggregates = spec.aggregates.map { "${aggregate(it, predicate(it.filter))} AS ${it.column}" }
        // With no aggregate a WITH groups nothing, and each key is to come back once.
        lines += (if (aggregates.isEmpty()) "WITH DISTINCT " else "WITH ") + (keys + aggregates).joinToString(",\n     ")

        spec.havings.forEachIndexed { index, having ->
            bindings["$HAVING$index"] = CypherGenerator.convertToNeo4jValue(having.value)
        }
        if (spec.havings.isNotEmpty()) {
            lines += "WHERE " + spec.havings.withIndex().joinToString(" AND ") { (index, having) ->
                "${having.aggregate.column} ${having.operator.cypherOperator} \$$HAVING$index"
            }
        }

        val returned = spec.keys.map { "${it.column}: ${value(query, it)}" } + spec.aggregates.map { "${it.column}: ${it.column}" }
        lines += "RETURN { ${returned.joinToString(", ")} } AS row"
        lines += "ORDER BY " + query.orders.joinToString(", ") { order(query, it) }
        spec.skip?.let { lines += "SKIP \$$SKIP"; bindings[SKIP] = it }
        spec.limit?.let { lines += "LIMIT \$$LIMIT"; bindings[LIMIT] = it }
        return GraphStatement(lines.joinToString("\n"), bindings)
    }

    /** The source, and from it the way to each path key's node. */
    private fun patterns(query: GroupQuery, paths: List<Key<*>>): List<String> {
        val source = "($NODE:${query.source.labels.joinToString(":")})"
        if (paths.isEmpty()) return listOf(source)
        return paths.mapIndexed { index, key ->
            val path = key.source as KeySource.Path
            buildString {
                append(if (index == 0) source else "($NODE)")
                path.hops.forEachIndexed { i, hop ->
                    // A hop is a value here, where a @GraphPath's is written in the model: its names are quoted where they are no plain identifier.
                    val named = if (path.hops.count { it.type == hop.type } > 1) relationship(index, i) else ""
                    append(Directions.hopArrow(SchemaGrammar.identifier(hop.type), hop.direction).replaceFirst("[:", "[$named:"))
                    if (i == path.hops.lastIndex) {
                        append("(${key.column}:${query.target(path).labels.joinToString(":")})")
                    } else {
                        append("(${hop.label.takeIf { it.isNotEmpty() }?.let { ":${SchemaGrammar.identifier(it)}" }.orEmpty()})")
                    }
                }
            }
        }
    }

    private fun relationship(path: Int, hop: Int): String = "r${path}_$hop"

    /**
     * That two hops of one type in a path are two relationships; null where no two hops share a type.
     * Cypher says a pattern walks a relationship once, and not every engine holds a path to it: asked
     * for outright, a path there and back over one relationship is no way to the node it started from
     * on any of them.
     */
    private fun walkedOnce(path: Int, hops: List<Hop>): String? =
        hops.indices.flatMap { i -> (i + 1..hops.lastIndex).filter { hops[it].type == hops[i].type }.map { j -> "${relationship(path, i)} <> ${relationship(path, j)}" } }
            .takeIf { it.isNotEmpty() }?.joinToString(" AND ")

    private fun property(propertyName: String): String = "$NODE.${CypherGenerator.quoteProperty(propertyName)}"

    /**
     * An aggregate over the group, or over its nodes that meet [filter]. A filtered count is a sum of
     * ones and zeros; any other filtered aggregate skips the nodes that do not meet the filter, as it
     * skips a null. `sum` over no values is written as 0, which not every engine gives.
     */
    private fun aggregate(aggregate: Aggregate<*>, filter: String?): String {
        if (aggregate.function == GroupFunction.COUNT) {
            return if (filter == null) "count($NODE)" else "sum(CASE WHEN $filter THEN 1 ELSE 0 END)"
        }
        val stored = property(requireNotNull(aggregate.propertyName))
        val value = if (filter == null) stored else "CASE WHEN $filter THEN $stored END"
        return when (aggregate.function) {
            GroupFunction.SUM -> "coalesce(sum($value), 0)"
            else -> "${aggregate.function.name.lowercase()}($value)"
        }
    }

    /** A key as the row returns it: a property as it is, a node as a load of its fragment projects it. */
    private fun value(query: GroupQuery, key: Key<*>): String {
        val path = key.source as? KeySource.Path ?: return key.column
        val target = query.target(path)
        val node = key.column
        return if (FragmentQueryBuilder.readWhole(target)) {
            "$node { .*, labels: labels($node) }"
        } else {
            "{ ${FragmentQueryBuilder.fieldMappings(target, node).joinToString(", ")}, labels: labels($node) }"
        }
    }

    /** One term of the order: a property key or an aggregate by its column, a node key by the node's id. */
    private fun order(query: GroupQuery, order: GroupOrder): String {
        val path = ((order.column as? Key<*>)?.source as? KeySource.Path)?.let { "${order.column.column}.${query.target(it).nodeIdReference}" } ?: order.column.column
        // Cypher has no NULLS FIRST / LAST: ordering by the test for null before the value does the same.
        val nulls = when (order.nulls) {
            NullPlacement.FIRST -> "$path IS NOT NULL, "
            NullPlacement.LAST -> "$path IS NULL, "
            null -> ""
        }
        return "$nulls$path ${if (order.direction == OrderDirection.ASC) "ASC" else "DESC"}"
    }

    private companion object {
        const val NODE = FragmentQueryBuilder.NODE_ALIAS
        const val HAVING = "_h"
        const val SKIP = "_skip"
        const val LIMIT = "_limit"
    }
}
