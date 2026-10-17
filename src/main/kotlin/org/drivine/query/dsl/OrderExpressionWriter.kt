package org.drivine.query.dsl

/**
 * Writes an [OrderExpression] as the text of one statement, by the one walk of the tree every backend
 * shares. A case, `AND`, `OR` and `NOT`, the tests for a value, negation and arithmetic are written
 * the same way on each, every operation in parentheses of its own so no engine's precedence is
 * relied on. What differs between backends is asked of the hooks: how a property and a bound value
 * are written, how a division is, a function's name, and a moment as seconds.
 *
 * Every property an expression reads is the root's: an expression orders the roots, one value for
 * each, and a related node is one of a collection. One of another node is refused with an
 * [IllegalArgumentException] before a hook is asked to write it, so before anything is sent.
 *
 * @param rootAlias what the DSL calls the root: the view's root field, or the fragment's alias
 */
internal abstract class OrderExpressionWriter(private val rootAlias: String) {

    /** [expression] in the statement's language. */
    fun write(expression: OrderExpression): String = write(expression, PropertyUse.Plain)

    /** How a property of the root is written, where it is used as [use] says. */
    protected abstract fun property(property: OrderExpression.Property, use: PropertyUse): String

    /** [value], bound as a parameter: the name it is written by. */
    protected abstract fun bind(value: Any): String

    /** The division of [left] by [right], as real numbers. */
    protected abstract fun divide(left: String, right: String): String

    /** The name [function] is called by. */
    protected abstract fun function(function: OrderFunction): String

    /** The moment the root's property [moment] holds, as seconds since the epoch, in parentheses of its own. */
    protected abstract fun epochSeconds(moment: OrderExpression.Property): String

    private fun write(expression: OrderExpression, use: PropertyUse): String = when (expression) {
        is OrderExpression.Property -> property(rootProperty(expression), use)
        is OrderExpression.Parameter -> bind(expression.value)
        is OrderExpression.Arithmetic -> arithmetic(expression)
        is OrderExpression.Negation -> "(-${write(expression.operand)})"
        is OrderExpression.Call -> "${function(expression.function)}(${expression.arguments.joinToString(", ") { write(it) }})"
        is OrderExpression.EpochSeconds -> epochSeconds(rootProperty(expression.moment))
        is OrderExpression.Case -> case(expression)
    }

    private fun arithmetic(expression: OrderExpression.Arithmetic): String {
        val left = write(expression.left)
        val right = write(expression.right)
        return if (expression.operator == ArithmeticOperator.DIVIDE) divide(left, right) else "($left ${expression.operator.symbol} $right)"
    }

    private fun case(expression: OrderExpression.Case): String {
        val branches = expression.branches.joinToString(" ") { "WHEN ${condition(it.condition)} THEN ${write(it.result)}" }
        return "CASE $branches ELSE ${write(expression.otherwise)} END"
    }

    private fun condition(condition: OrderCondition): String = when (condition) {
        is OrderCondition.Comparison -> {
            val use = comparedWith(condition)
            "${write(condition.left, use)} ${condition.operator.symbol} ${write(condition.right, use)}"
        }
        is OrderCondition.Missing ->
            "${write(condition.operand, PropertyUse.TestedForValue)} ${if (condition.negated) "IS NOT NULL" else "IS NULL"}"
        is OrderCondition.All -> condition.conditions.joinToString(" AND ", "(", ")") { condition(it) }
        is OrderCondition.AnyOf -> condition.conditions.joinToString(" OR ", "(", ")") { condition(it) }
        is OrderCondition.Not -> "NOT (${condition(condition.condition)})"
    }

    /** How a property is used on either side of [comparison]: compared with the value bound on the other side, where there is one. */
    private fun comparedWith(comparison: OrderCondition.Comparison): PropertyUse {
        val bound = comparison.right as? OrderExpression.Parameter ?: comparison.left as? OrderExpression.Parameter
        return bound?.let { PropertyUse.ComparedWith(it.value) } ?: PropertyUse.Plain
    }

    private fun rootProperty(property: OrderExpression.Property): OrderExpression.Property {
        require(property.alias == rootAlias) {
            "An order by an expression reads only the root's properties: ${property.path} is not one of them."
        }
        return property
    }
}

/** Where an expression uses a property, which a backend that reads a property by the kind of its value is told. */
internal sealed interface PropertyUse {
    /** A term of arithmetic, a function's argument, or a value of a case. */
    data object Plain : PropertyUse

    /** One side of a comparison whose other side is [value], bound. */
    data class ComparedWith(val value: Any) : PropertyUse

    /** Tested for whether it has a value. */
    data object TestedForValue : PropertyUse
}
