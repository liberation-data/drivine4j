package org.drivine.query.dsl

import org.drivine.annotation.Direction
import org.drivine.annotation.Hop

/**
 * A grouped read: the nodes of one fragment, put in groups by a key, with numbers about each group.
 * See `GroupOperations.load`.
 *
 * Each line is a clause of the query one would write by hand, and a `val` is its `AS`:
 *
 * ```kotlin
 * graphObjectManager.groups.load<FeedbackData, _> {
 *     where { query.createdAt gte march }             // which nodes are read at all
 *     val page  = key(query.page)                     // what puts two nodes in one group
 *     val votes = count()
 *     val likes = count { query.helpful eq true }     // which nodes count toward this one number
 *     having { votes gte 3 }                          // which groups are returned
 *     orderBy { votes.desc() }
 *     limit(10)
 *     rows { PageVotes(it[page], it[votes], it[likes]) }
 * }
 * ```
 *
 * A key or an aggregate is a handle to its column: [having] and [orderBy] take handles, and
 * [GroupRow.get] reads one, typed. [rows] is the last line, and what the block returns.
 *
 * @param Q The query object type of the source fragment, which provides its property references
 */
class GroupQuerySpec<Q : Any>(
    /** The source fragment's property references: `query.page`. */
    val query: Q,
) {
    internal val conditions = mutableListOf<WhereCondition>()
    internal val keys = mutableListOf<Key<*>>()
    internal val aggregates = mutableListOf<Aggregate<*>>()
    internal val havings = mutableListOf<HavingCondition>()
    internal val orders = mutableListOf<GroupOrder>()
    internal var limit: Int? = null
    internal var skip: Int? = null

    // ----- Which nodes are read -----

    /**
     * Filters the source's nodes before they are grouped, as a load's `where` is written. It names the
     * source's own properties.
     */
    fun where(block: context(WhereBuilder<Q>) () -> Unit) {
        conditions.addAll(collected(block))
    }

    /** [where] for Java: `spec.onlyIf(dsl.getRole().isEqualTo("USER"))`. The conditions are joined by `AND`. */
    fun onlyIf(vararg conditions: PropertyConditionBuilder): GroupQuerySpec<Q> = apply { this.conditions.addAll(conditions.map { it.condition }) }

    private fun collected(block: context(WhereBuilder<Q>) () -> Unit): List<WhereCondition> {
        val builder = WhereBuilder(query)
        block(builder)
        return builder.conditions.toList()
    }

    // ----- Keys -----

    /**
     * Groups by a property of the source: two nodes with the same value are in the same group. A
     * property the fragment declares nullable is refused, and is [keyOrNull]'s. A second key makes
     * the key compound.
     */
    fun <V> key(property: PropertyReference<V>): Key<V & Any> =
        Key<V & Any>("k${keys.size}", KeySource.Property(property.alias, property.propertyName), nullable = false).also { keys += it }

    /** Groups by a property that may have no value: the nodes without one are a group of their own, whose key is null. */
    fun <V> keyOrNull(property: PropertyReference<V>): Key<V?> =
        Key<V?>("k${keys.size}", KeySource.Property(property.alias, property.propertyName), nullable = true).also { keys += it }

    /**
     * Groups by a node the source reaches: two nodes that reach the same one are in the same group,
     * and the key reads as that node. A node that reaches none is in no group; one that reaches
     * several is in each, once.
     */
    fun <N : Any> key(path: NodePath<N>): Key<N> =
        Key<N>("k${keys.size}", KeySource.Path(path.target, path.hops), nullable = false).also { keys += it }

    /**
     * The way from a node of the source to a [N]: the hops a `@GraphPath` is made of, written as
     * values. The node at the end is found by [N]'s labels.
     *
     * ```kotlin
     * val user = key(path<GuideUserData>(Hop("HAS_MESSAGE", Direction.INCOMING, label = "ChatSession"), Hop("OWNED_BY")))
     * ```
     */
    inline fun <reified N : Any> path(vararg hops: Hop): NodePath<N> = path(N::class.java, *hops)

    /** [path] with the class as an argument, for Java. */
    fun <N : Any> path(target: Class<N>, vararg hops: Hop): NodePath<N> = NodePath(target, hops.toList())

    /** A [Hop] for Java, which cannot write an annotation as a value. An empty [label] asks nothing of the node the hop reaches. */
    @JvmOverloads
    fun hop(type: String, direction: Direction = Direction.OUTGOING, label: String = ""): Hop = Hop(type, direction, label)

    // ----- Aggregates -----

    /**
     * How many nodes the group has; with a [filter], how many of them meet it. A filter never takes a
     * node out of its group: a group none of whose nodes meet it is still returned, with 0.
     */
    fun count(filter: context(WhereBuilder<Q>) () -> Unit): Aggregate<Long> =
        aggregate(GroupFunction.COUNT, null, filterOf(filter), NumberKind.WHOLE)

    /** How many nodes the group has. */
    fun count(): Aggregate<Long> = aggregate(GroupFunction.COUNT, null, null, NumberKind.WHOLE)

    /** [count] with a filter, for Java: `spec.countWhere(dsl.getHelpful().isEqualTo(true))`. The conditions are joined by `AND`. */
    fun countWhere(vararg conditions: PropertyConditionBuilder): Aggregate<Long> {
        require(conditions.isNotEmpty()) { "countWhere needs a condition: with none it is count()." }
        return aggregate(GroupFunction.COUNT, null, conditions.map { it.condition }, NumberKind.WHOLE)
    }

    /** The sum of a whole-number property over the group, or over its nodes that meet [filter]. A node with no value adds nothing, and the sum over no values is 0. */
    @JvmName("sumOfInts")
    @JvmOverloads
    fun sum(property: PropertyReference<Int>, filter: (context(WhereBuilder<Q>) () -> Unit)? = null): Aggregate<Long> =
        aggregate(GroupFunction.SUM, property, filter?.let(::filterOf), NumberKind.WHOLE)

    /** The sum of a whole-number property over the group, or over its nodes that meet [filter]. A node with no value adds nothing, and the sum over no values is 0. */
    @JvmName("sumOfLongs")
    @JvmOverloads
    fun sum(property: PropertyReference<Long>, filter: (context(WhereBuilder<Q>) () -> Unit)? = null): Aggregate<Long> =
        aggregate(GroupFunction.SUM, property, filter?.let(::filterOf), NumberKind.WHOLE)

    /** The sum of a property over the group, or over its nodes that meet [filter]. A node with no value adds nothing, and the sum over no values is 0. */
    @JvmName("sumOfFloats")
    @JvmOverloads
    fun sum(property: PropertyReference<Float>, filter: (context(WhereBuilder<Q>) () -> Unit)? = null): Aggregate<Double> =
        aggregate(GroupFunction.SUM, property, filter?.let(::filterOf), NumberKind.FRACTIONAL)

    /** The sum of a property over the group, or over its nodes that meet [filter]. A node with no value adds nothing, and the sum over no values is 0. */
    @JvmName("sumOfDoubles")
    @JvmOverloads
    fun sum(property: PropertyReference<Double>, filter: (context(WhereBuilder<Q>) () -> Unit)? = null): Aggregate<Double> =
        aggregate(GroupFunction.SUM, property, filter?.let(::filterOf), NumberKind.FRACTIONAL)

    /** The mean of a property over the group's nodes that have a value for it, and meet [filter]; null when none does. */
    @JvmOverloads
    fun <V : Number> avg(property: PropertyReference<V>, filter: (context(WhereBuilder<Q>) () -> Unit)? = null): Aggregate<Double?> =
        aggregate(GroupFunction.AVG, property, filter?.let(::filterOf), NumberKind.FRACTIONAL)

    /** The least value of a property over the group's nodes that have one, and meet [filter]; null when none does. */
    @JvmOverloads
    fun <V> min(property: PropertyReference<V>, filter: (context(WhereBuilder<Q>) () -> Unit)? = null): Aggregate<V?> =
        aggregate(GroupFunction.MIN, property, filter?.let(::filterOf), null)

    /** The greatest value of a property over the group's nodes that have one, and meet [filter]; null when none does. */
    @JvmOverloads
    fun <V> max(property: PropertyReference<V>, filter: (context(WhereBuilder<Q>) () -> Unit)? = null): Aggregate<V?> =
        aggregate(GroupFunction.MAX, property, filter?.let(::filterOf), null)

    /**
     * A filter on an aggregate, for Java: `spec.filtered(spec.sumOfLongs(dsl.getTokens()), dsl.getRole().isEqualTo(Role.USER))`.
     * The aggregate then takes only the group's nodes that meet every one of [conditions], and is returned.
     */
    fun <A : Aggregate<*>> filtered(aggregate: A, vararg conditions: PropertyConditionBuilder): A {
        require(conditions.isNotEmpty()) { "filtered needs a condition: with none the aggregate takes every node of the group, as it does unfiltered." }
        require(aggregates.any { it === aggregate }) { "The aggregate was declared in another grouped read." }
        aggregate.filter = aggregate.filter + conditions.map { it.condition }
        return aggregate
    }

    private fun filterOf(block: context(WhereBuilder<Q>) () -> Unit): List<WhereCondition> =
        collected(block).also { require(it.isNotEmpty()) { "An aggregate's filter holds no condition. Leave the block out to take every node of the group." } }

    private fun <V> aggregate(function: GroupFunction, property: PropertyReference<*>?, filter: List<WhereCondition>?, number: NumberKind?): Aggregate<V> =
        Aggregate<V>("a${aggregates.size}", function, property?.alias, property?.propertyName, filter.orEmpty(), number).also { aggregates += it }

    // ----- Which groups are returned, and in what order -----

    /**
     * Leaves out the groups that do not meet the block: `having { votes gte 3 }`. Each line compares
     * one aggregate with a value, and the lines are joined by `AND`.
     */
    fun having(block: HavingBuilder.() -> Unit) {
        havings.addAll(HavingBuilder().apply(block).conditions)
    }

    /** [having] for Java: `spec.having(votes, ComparisonOperator.GREATER_THAN_OR_EQUAL, 3)`. */
    fun having(aggregate: Aggregate<*>, operator: ComparisonOperator, value: Any): GroupQuerySpec<Q> = apply {
        havings += HavingCondition(aggregate, operator, value)
    }

    /**
     * Orders the groups by handles: `orderBy { votes.desc(); page.asc() }`. A node key orders by the
     * node's id. Every key the order leaves out is added after it, so groups that tie come back in the
     * same order on every engine.
     */
    fun orderBy(block: GroupOrderBuilder.() -> Unit) {
        orders.addAll(GroupOrderBuilder().apply(block).orders)
    }

    /** [orderBy] for Java: `spec.orderBy(votes.descending(), page.ascending())`. */
    fun orderBy(vararg orders: GroupOrder): GroupQuerySpec<Q> = apply { this.orders.addAll(orders) }

    /** Returns at most [n] groups, bound as a parameter and applied after the order. */
    fun limit(n: Int) {
        require(n >= 0) { "limit must be >= 0, was $n" }
        limit = n
    }

    /** Skips the first [n] groups, bound as a parameter and applied before [limit]. */
    fun skip(n: Int) {
        require(n >= 0) { "skip must be >= 0, was $n" }
        skip = n
    }

    /**
     * What one group becomes: `rows { PageVotes(it[page], it[votes]) }`. It is the block's last line,
     * and its value is what the block returns. The class it makes is the caller's own.
     */
    fun <R> rows(make: (GroupRow) -> R): GroupRows<R> = GroupRows(make)

    /** [rows] for Java, as a function: `spec.rowsOf(row -> new PageVotes(row.get(page), row.get(votes)))`. */
    fun <R> rowsOf(make: java.util.function.Function<GroupRow, R>): GroupRows<R> = GroupRows { make.apply(it) }
}

/** What [GroupQuerySpec.rows] gives, and a grouped read's block returns: how one group becomes one [R]. */
class GroupRows<out R> internal constructor(internal val make: (GroupRow) -> R)

/**
 * A column of a grouped read, as the block that declared it holds it: a [Key] or an [Aggregate]. It
 * orders the groups, and [GroupRow.get] reads it.
 *
 * @param V what the column reads as
 */
sealed class GroupColumn<out V>(internal val column: String) {
    // Java's `ascending()` and `descending()`. Kotlin sees them under another name: inside an
    // `orderBy { }` block a call that returns an order and registers none would order nothing.

    /** This column ascending, for Java's `orderBy`, where it is `votes.ascending()`. In Kotlin, `orderBy { votes.asc() }`. */
    @JvmName("ascending")
    fun ascendingOrderForJava(): GroupOrder = GroupOrder(this, OrderDirection.ASC)

    /** This column descending, for Java's `orderBy`, where it is `votes.descending()`. In Kotlin, `orderBy { votes.desc() }`. */
    @JvmName("descending")
    fun descendingOrderForJava(): GroupOrder = GroupOrder(this, OrderDirection.DESC)
}

/** What puts two nodes in the same group: a property of the source, or a node it reaches. */
class Key<out V> internal constructor(
    column: String,
    internal val source: KeySource,
    internal val nullable: Boolean,
) : GroupColumn<V>(column)

/** Where a [Key]'s value is read from. */
internal sealed class KeySource {
    /** A property of the source: [alias] is what a `where` calls the source, [propertyName] the stored name. */
    class Property(val alias: String, val propertyName: String) : KeySource()

    /** The [target] fragment at the end of [hops] from the source. */
    class Path(val target: Class<*>, val hops: List<Hop>) : KeySource()
}

/** The way from a node of the source to a node of [N]. See [GroupQuerySpec.path]. */
class NodePath<N : Any> internal constructor(internal val target: Class<N>, internal val hops: List<Hop>)

/** What an [Aggregate] computes over a group. */
enum class GroupFunction { COUNT, SUM, AVG, MIN, MAX }

/** Whether an aggregate's number reads as a `Long` or as a `Double`. */
internal enum class NumberKind { WHOLE, FRACTIONAL }

/**
 * A number about a group, or the least or greatest value in it.
 *
 * @param propertyName the stored name of the property aggregated; null for a count
 * @param filter the conditions a node meets to count toward this aggregate; empty for every node
 * @param number how the value reads when it is a number; null when it reads as the property does
 */
class Aggregate<out V> internal constructor(
    column: String,
    internal val function: GroupFunction,
    internal val alias: String?,
    internal val propertyName: String?,
    internal var filter: List<WhereCondition>,
    internal val number: NumberKind?,
) : GroupColumn<V>(column)

/** One comparison of a `having`: [aggregate] against [value]. */
internal class HavingCondition(val aggregate: Aggregate<*>, val operator: ComparisonOperator, val value: Any)

/**
 * Collects the comparisons of a `having`. A count or a sum is compared with any number, so an `Int`
 * constant needs no conversion; a `min` or a `max` with a value of its property's type.
 */
class HavingBuilder internal constructor() {
    internal val conditions = mutableListOf<HavingCondition>()

    infix fun Aggregate<Number?>.eq(value: Number) = add(this, ComparisonOperator.EQUALS, value)
    infix fun Aggregate<Number?>.neq(value: Number) = add(this, ComparisonOperator.NOT_EQUALS, value)
    infix fun Aggregate<Number?>.gt(value: Number) = add(this, ComparisonOperator.GREATER_THAN, value)
    infix fun Aggregate<Number?>.gte(value: Number) = add(this, ComparisonOperator.GREATER_THAN_OR_EQUAL, value)
    infix fun Aggregate<Number?>.lt(value: Number) = add(this, ComparisonOperator.LESS_THAN, value)
    infix fun Aggregate<Number?>.lte(value: Number) = add(this, ComparisonOperator.LESS_THAN_OR_EQUAL, value)

    @JvmName("eqValue")
    infix fun <V : Comparable<V>> Aggregate<V?>.eq(value: V) = add(this, ComparisonOperator.EQUALS, value)
    @JvmName("neqValue")
    infix fun <V : Comparable<V>> Aggregate<V?>.neq(value: V) = add(this, ComparisonOperator.NOT_EQUALS, value)
    @JvmName("gtValue")
    infix fun <V : Comparable<V>> Aggregate<V?>.gt(value: V) = add(this, ComparisonOperator.GREATER_THAN, value)
    @JvmName("gteValue")
    infix fun <V : Comparable<V>> Aggregate<V?>.gte(value: V) = add(this, ComparisonOperator.GREATER_THAN_OR_EQUAL, value)
    @JvmName("ltValue")
    infix fun <V : Comparable<V>> Aggregate<V?>.lt(value: V) = add(this, ComparisonOperator.LESS_THAN, value)
    @JvmName("lteValue")
    infix fun <V : Comparable<V>> Aggregate<V?>.lte(value: V) = add(this, ComparisonOperator.LESS_THAN_OR_EQUAL, value)

    private fun add(aggregate: Aggregate<*>, operator: ComparisonOperator, value: Any) {
        conditions += HavingCondition(aggregate, operator, value)
    }
}

/** One term of a grouped read's order: a [column], its [direction], and where its nulls go. */
class GroupOrder internal constructor(
    internal val column: GroupColumn<*>,
    internal val direction: OrderDirection,
    internal val nulls: NullPlacement? = null,
) {
    /** This order, with the groups that have no value for the column before every group that has one. */
    fun nullsFirst(): GroupOrder = GroupOrder(column, direction, NullPlacement.FIRST)

    /** This order, with the groups that have no value for the column after every group that has one. */
    fun nullsLast(): GroupOrder = GroupOrder(column, direction, NullPlacement.LAST)
}

/** Collects the order of a grouped read: `votes.desc()`, `page.asc().nullsLast()`. */
class GroupOrderBuilder internal constructor() {
    internal val orders = mutableListOf<GroupOrder>()

    fun GroupColumn<*>.asc(): RegisteredGroupOrder = register(GroupOrder(this, OrderDirection.ASC))

    fun GroupColumn<*>.desc(): RegisteredGroupOrder = register(GroupOrder(this, OrderDirection.DESC))

    private fun register(order: GroupOrder): RegisteredGroupOrder {
        orders += order
        return RegisteredGroupOrder(orders, orders.lastIndex)
    }
}

/** An order a [GroupOrderBuilder] already holds, so that `desc().nullsLast()` places the nulls of the order just made. */
class RegisteredGroupOrder internal constructor(private val orders: MutableList<GroupOrder>, private val index: Int) {
    /** Groups with no value for the column come before every group that has one. See [NullPlacement]. */
    fun nullsFirst() {
        orders[index] = orders[index].nullsFirst()
    }

    /** Groups with no value for the column come after every group that has one. See [NullPlacement]. */
    fun nullsLast() {
        orders[index] = orders[index].nullsLast()
    }
}

/**
 * One group of a grouped read: its keys and its aggregates, each read through the handle that
 * declared it, as that handle's type.
 */
class GroupRow internal constructor(
    private val columns: List<GroupColumn<*>>,
    private val values: Map<String, Any?>,
    private val read: (GroupColumn<*>, Map<String, Any?>) -> Any?,
) {
    /** What has been read, by column: a node key is hydrated once, and read twice is the same object. */
    private val converted = HashMap<String, Any?>()

    /** The value of [column] for this group. */
    @Suppress("UNCHECKED_CAST")
    operator fun <V> get(column: GroupColumn<V>): V {
        require(columns.any { it === column }) { "This row has no column for the handle: it was declared in another grouped read." }
        if (column.column !in converted) converted[column.column] = read(column, values)
        return converted[column.column] as V
    }
}
