@file:JvmName("OrderExpressions")

package org.drivine.query.dsl

import java.time.Instant
import java.time.temporal.TemporalAccessor

// The functions an order expression is built with. Kotlin writes the arithmetic as operators,
// `query.confidence * exp(-query.decay)`; Java calls the same functions by name, as static methods
// of OrderExpressions: `times(dsl.getConfidence(), exp(unaryMinus(dsl.getDecay())))`.

/**
 * A value the caller gives, bound as a parameter: an `asOf` moment, a threshold, a weight. A property
 * reference or an expression is no value to bind, and is refused: it is an operand as it stands. So
 * is any other object of the DSL, a node reference, a condition or an order, which no store could
 * hold as a value. The comparisons, `then`, `otherwise` and `coalesce` bind what is not an operand
 * through this, and refuse the same.
 */
fun param(value: Any): OrderExpression {
    require(value !is Operand) { "param binds a value, and $value is an operand: use it as it stands." }
    require(!isQueryObject(value)) { "$value is an object of the query DSL, which is no value to bind: an expression reads only properties and values." }
    return OrderExpression.Parameter(value)
}

/** Whether [value] is an object of the DSL that is not an operand: a reference to a node or a bag, a condition, a branch or an order. */
private fun isQueryObject(value: Any): Boolean =
    value is NodeReference || value is PropertyBagReference || value is OrderCondition || value is CaseBranch ||
        value is OrderTerm || value is WhereCondition || value is PropertyConditionBuilder

// ----- Arithmetic -----

operator fun Operand.plus(other: Operand): OrderExpression = arithmetic(ArithmeticOperator.PLUS, expression(), other.expression())

operator fun Operand.plus(other: Number): OrderExpression = arithmetic(ArithmeticOperator.PLUS, expression(), param(other))

operator fun Number.plus(other: Operand): OrderExpression = arithmetic(ArithmeticOperator.PLUS, param(this), other.expression())

operator fun Operand.minus(other: Operand): OrderExpression = arithmetic(ArithmeticOperator.MINUS, expression(), other.expression())

operator fun Operand.minus(other: Number): OrderExpression = arithmetic(ArithmeticOperator.MINUS, expression(), param(other))

operator fun Number.minus(other: Operand): OrderExpression = arithmetic(ArithmeticOperator.MINUS, param(this), other.expression())

operator fun Operand.times(other: Operand): OrderExpression = arithmetic(ArithmeticOperator.TIMES, expression(), other.expression())

operator fun Operand.times(other: Number): OrderExpression = arithmetic(ArithmeticOperator.TIMES, expression(), param(other))

operator fun Number.times(other: Operand): OrderExpression = arithmetic(ArithmeticOperator.TIMES, param(this), other.expression())

/**
 * Division of real numbers on every backend: `3 / 2` is `1.5` even where both are whole numbers.
 * Division by zero is not: Oracle refuses the statement, and Neo4j does where the divisor is a whole
 * number. Guard a divisor that can be zero with a [case].
 */
operator fun Operand.div(other: Operand): OrderExpression = arithmetic(ArithmeticOperator.DIVIDE, expression(), other.expression())

/** Division of real numbers on every backend: `3 / 2` is `1.5` even where both are whole numbers. */
operator fun Operand.div(other: Number): OrderExpression = arithmetic(ArithmeticOperator.DIVIDE, expression(), param(other))

/** Division of real numbers on every backend: `3 / 2` is `1.5` even where both are whole numbers. */
operator fun Number.div(other: Operand): OrderExpression = arithmetic(ArithmeticOperator.DIVIDE, param(this), other.expression())

operator fun Operand.unaryMinus(): OrderExpression = OrderExpression.Negation(expression())

private fun arithmetic(operator: ArithmeticOperator, left: OrderExpression, right: OrderExpression): OrderExpression =
    OrderExpression.Arithmetic(operator, left, right)

// ----- Functions -----

/** `e` raised to [power]. */
fun exp(power: Operand): OrderExpression = OrderExpression.Call(OrderFunction.EXP, listOf(power.expression()))

/** The largest whole number no greater than [value]: whole days from a count of days. */
fun floor(value: Operand): OrderExpression = OrderExpression.Call(OrderFunction.FLOOR, listOf(value.expression()))

/**
 * The first of [first] and [rest] that has a value. Each of [rest] is an operand, or a value that is
 * bound: `coalesce(query.boost, 1.0)`.
 */
fun coalesce(first: Operand, vararg rest: Any): OrderExpression {
    require(rest.isNotEmpty()) { "coalesce needs a second operand: of one, it is that operand." }
    return OrderExpression.Call(OrderFunction.COALESCE, listOf(first.expression()) + rest.map(::operandOrValue))
}

/**
 * The moment [moment] as seconds since the epoch, with its fraction, bound as a number. It is read
 * on the caller's side, so every backend can take it: `epochSeconds(asOf) - query.lastReadSeconds`.
 * A stored moment read as seconds is `org.drivine.query.dsl.temporal.epochSeconds(property)`, which
 * not every backend can write.
 *
 * @throws java.time.DateTimeException where [moment] names no instant, as a local date-time does not
 */
fun epochSeconds(moment: TemporalAccessor): OrderExpression {
    val instant = Instant.from(moment)
    return param(instant.epochSecond + instant.nano / NANOS_PER_SECOND)
}

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * The value of the first of [branches] whose condition holds, and [otherwise] where none does.
 * [otherwise] is an operand, or a value that is bound.
 *
 * ```kotlin
 * case((query.validFrom above asOf) or (query.validTo atMost asOf) then 0.0, otherwise = query.confidence)
 * ```
 *
 * Java, where `case` is a word of the language, calls it `caseOf`.
 */
@JvmName("caseOf")
fun case(vararg branches: CaseBranch, otherwise: Any): OrderExpression {
    require(branches.isNotEmpty()) { "case needs a branch: with none, it is its otherwise." }
    return OrderExpression.Case(branches.toList(), operandOrValue(otherwise))
}

// ----- Conditions of a case -----
// The value each is compared with is an operand, or a value that is bound: `query.validTo atMost asOf`.

/** Holds where this is greater than [other]. */
infix fun Operand.above(other: Any): OrderCondition = compared(ExpressionComparison.GREATER, this, other)

/** Holds where this is less than [other]. */
infix fun Operand.below(other: Any): OrderCondition = compared(ExpressionComparison.LESS, this, other)

/** Holds where this is greater than or equal to [other]. */
infix fun Operand.atLeast(other: Any): OrderCondition = compared(ExpressionComparison.AT_LEAST, this, other)

/** Holds where this is less than or equal to [other]. */
infix fun Operand.atMost(other: Any): OrderCondition = compared(ExpressionComparison.AT_MOST, this, other)

/** Holds where this equals [other]. */
infix fun Operand.equalTo(other: Any): OrderCondition = compared(ExpressionComparison.EQUAL, this, other)

/** Holds where this does not equal [other]; not where either has no value. */
infix fun Operand.notEqualTo(other: Any): OrderCondition = compared(ExpressionComparison.NOT_EQUAL, this, other)

/** Holds where this has a value. */
fun Operand.hasValue(): OrderCondition = OrderCondition.Missing(expression(), negated = true)

/** Holds where this has no value. */
fun Operand.hasNoValue(): OrderCondition = OrderCondition.Missing(expression(), negated = false)

private fun compared(operator: ExpressionComparison, left: Operand, right: Any): OrderCondition =
    OrderCondition.Comparison(operator, left.expression(), operandOrValue(right))
