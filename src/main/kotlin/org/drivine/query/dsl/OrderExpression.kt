package org.drivine.query.dsl

/**
 * What an order expression is built from: a typed property reference of the root, or an expression
 * already built. The functions of `OrderExpressions` take operands and give expressions.
 */
sealed interface Operand

/**
 * A value computed for each root from its properties and from values the caller binds, by which
 * the roots may be ordered: arithmetic (`+ - * /`), [exp], [floor], [coalesce], [epochSeconds] of a
 * moment the caller binds, and [case]. A stored moment read as seconds is a function of the temporal
 * capability, `org.drivine.query.dsl.temporal.epochSeconds`, behind its opt-in. Build one with those functions inside an `orderBy` block and order by it with [asc] or
 * [desc]; a property order after it breaks its ties, as it does after a property.
 *
 * ```kotlin
 * orderBy {
 *     case(
 *         (query.validFrom above asOf) or (query.validTo atMost asOf) then 0.0,
 *         otherwise = query.confidence * exp(-query.decay * floor((asOf.epochSecond - query.lastReadSeconds) / 86400)),
 *     ).desc()
 *     query.id.asc()
 * }
 * ```
 *
 * Every value that is not a property, a number written in the expression among them, is bound as
 * a parameter: none is written into the statement. Division is division of real numbers on every
 * backend, whatever the types of its operands; division by zero differs between them (see `div`).
 * An expression reads only the root's properties.
 *
 * A backend that cannot write a function the expression uses refuses the load before anything
 * runs, with an [UnsupportedOrderFunctionException].
 */
sealed class OrderExpression : Operand {

    /** The order by this expression, from the smallest value up. For Java; Kotlin writes [asc] in an `orderBy` block. */
    fun ascending(): ExpressionOrder = ExpressionOrder(this, OrderDirection.ASC)

    /** The order by this expression, from the largest value down. For Java; Kotlin writes [desc] in an `orderBy` block. */
    fun descending(): ExpressionOrder = ExpressionOrder(this, OrderDirection.DESC)

    /** Orders by this expression from the smallest value up (Kotlin DSL); `asc().nullsLast()` places its nulls. */
    context(builder: OrderBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("ascContext")
    fun asc(): RegisteredOrder = builder.register(ascending())

    /** Orders by this expression from the largest value down (Kotlin DSL); `desc().nullsLast()` places its nulls. */
    context(builder: OrderBuilder<*>)
    @Suppress("INAPPLICABLE_JVM_NAME")
    @JvmName("descContext")
    fun desc(): RegisteredOrder = builder.register(descending())

    /** A property of the node [alias] stands for, by its stored name. */
    internal data class Property(val alias: String, val propertyName: String) : OrderExpression() {
        val path: String get() = "$alias.$propertyName"
    }

    /** A value the caller gives, bound as a parameter. */
    internal data class Parameter(val value: Any) : OrderExpression()

    internal data class Arithmetic(val operator: ArithmeticOperator, val left: OrderExpression, val right: OrderExpression) : OrderExpression()

    internal data class Negation(val operand: OrderExpression) : OrderExpression()

    internal data class Call(val function: OrderFunction, val arguments: List<OrderExpression>) : OrderExpression()

    /** The moment a property holds, as seconds since the epoch. */
    internal data class EpochSeconds(val moment: Property) : OrderExpression()

    internal data class Case(val branches: List<CaseBranch>, val otherwise: OrderExpression) : OrderExpression()
}

internal enum class ArithmeticOperator(val symbol: String) {
    PLUS("+"),
    MINUS("-"),
    TIMES("*"),
    DIVIDE("/"),
}

/** The functions an expression may call, other than [epochSeconds], which is not a function of numbers. */
internal enum class OrderFunction(val written: String) {
    EXP("exp"),
    FLOOR("floor"),
    COALESCE("coalesce"),
}

/**
 * A test an expression's [case] makes of each root: a comparison, a test for a value, and their
 * `and`, `or` and `not`. A comparison with no value on either side holds for no root, so a root
 * without the property goes to the next branch.
 */
sealed class OrderCondition {

    /** Holds where this and [other] both hold. */
    infix fun and(other: OrderCondition): OrderCondition = All(listOf(this, other))

    /** Holds where this or [other] holds. */
    infix fun or(other: OrderCondition): OrderCondition = AnyOf(listOf(this, other))

    /** Holds where this does not. */
    operator fun not(): OrderCondition = Not(this)

    /** A branch of a [case]: where this holds, the expression is [value], an operand or a value that is bound. */
    infix fun then(value: Any): CaseBranch = CaseBranch(this, operandOrValue(value))

    internal data class Comparison(val operator: ExpressionComparison, val left: OrderExpression, val right: OrderExpression) : OrderCondition()

    internal data class Missing(val operand: OrderExpression, val negated: Boolean) : OrderCondition()

    internal data class All(val conditions: List<OrderCondition>) : OrderCondition()

    internal data class AnyOf(val conditions: List<OrderCondition>) : OrderCondition()

    internal data class Not(val condition: OrderCondition) : OrderCondition()
}

internal enum class ExpressionComparison(val symbol: String) {
    EQUAL("="),
    NOT_EQUAL("<>"),
    LESS("<"),
    AT_MOST("<="),
    GREATER(">"),
    AT_LEAST(">="),
}

/** One `WHEN … THEN …` of a [case]: made by `condition then value`. */
data class CaseBranch(
    internal val condition: OrderCondition,
    internal val result: OrderExpression,
) {
    /**
     * The [case] of this one branch, whose value is [value], an operand or a value that is bound,
     * wherever the condition does not hold.
     */
    fun otherwise(value: Any): OrderExpression = OrderExpression.Case(listOf(this), operandOrValue(value))
}

/**
 * An order by an [OrderExpression]. Obtain these with `expression.desc()` inside an `orderBy`
 * block, or `expression.descending()` from Java.
 *
 * [nulls] says where the rows whose value is null go, as it does for an [OrderSpec].
 */
data class ExpressionOrder @JvmOverloads constructor(
    val expression: OrderExpression,
    override val direction: OrderDirection,
    override val nulls: NullPlacement? = null,
) : OrderTerm {
    /** This order, with the rows whose value is null before every other. */
    override fun nullsFirst(): ExpressionOrder = copy(nulls = NullPlacement.FIRST)

    /** This order, with the rows whose value is null after every other. */
    override fun nullsLast(): ExpressionOrder = copy(nulls = NullPlacement.LAST)
}

/**
 * Thrown when a load orders by an expression that calls a function its backend cannot write. It
 * is thrown as the statement is built, before anything is sent to the store.
 *
 * @param function the function, as the DSL names it
 * @param backend the engine that cannot write it
 */
class UnsupportedOrderFunctionException(
    val function: String,
    val backend: String,
    reason: String,
) : UnsupportedOperationException("$backend cannot order by $function(): $reason")

/** The operand as an expression: a property reference becomes a read of its property. */
internal fun Operand.expression(): OrderExpression = when (this) {
    is OrderExpression -> this
    is PropertyReference<*> -> OrderExpression.Property(alias, propertyName)
}

/** A value of the expression: an operand as it is, and anything else bound (see [param], which refuses what is no value). */
internal fun operandOrValue(value: Any): OrderExpression = if (value is Operand) value.expression() else param(value)
