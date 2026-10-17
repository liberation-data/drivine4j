package org.drivine.query.dsl

import org.drivine.query.grammar.CypherGrammar

/**
 * Writes the [OrderExpression]s of one `ORDER BY` in Cypher. Each value is bound under a name of
 * its own, `_order_0`, `_order_1`, …, numbered in the order the values are written, and kept in
 * [bindings]. Every operation is written in parentheses of its own, so no engine's precedence is
 * relied on.
 *
 * @param grammar the engine's grammar, which says how it reads a moment as a number, or that it cannot
 * @param path how a property of the root is written: from the projected map after a view's projection
 */
internal class CypherOrderExpressions(
    private val grammar: CypherGrammar,
    private val path: (String) -> String,
) {
    private val bound = linkedMapOf<String, Any?>()

    /** The values bound so far, by parameter name. */
    val bindings: Map<String, Any?> get() = bound

    fun write(expression: OrderExpression): String = when (expression) {
        is OrderExpression.Property -> path(expression.path)
        is OrderExpression.Parameter -> bind(expression.value)
        is OrderExpression.Arithmetic -> arithmetic(expression)
        is OrderExpression.Negation -> "(-${write(expression.operand)})"
        is OrderExpression.Call -> "${expression.function.written}(${expression.arguments.joinToString(", ") { write(it) }})"
        is OrderExpression.EpochSeconds -> epochSeconds(expression)
        is OrderExpression.Case -> case(expression)
    }

    /** Division is of real numbers, as it is in SQL: Cypher divides two integers as integers. */
    private fun arithmetic(expression: OrderExpression.Arithmetic): String {
        val left = write(expression.left)
        val right = write(expression.right)
        return if (expression.operator == ArithmeticOperator.DIVIDE) "(toFloat($left) / $right)" else "($left ${expression.operator.symbol} $right)"
    }

    /** In parentheses of its own, as every operation is: the grammar's form may be a division. */
    private fun epochSeconds(expression: OrderExpression.EpochSeconds): String {
        val moment = path(expression.moment.path)
        val seconds = grammar.epochSeconds(moment) ?: throw UnsupportedOrderFunctionException(
            "epochSeconds", grammar.engineName, grammar.epochSecondsLimit,
        )
        return "($seconds)"
    }

    private fun case(expression: OrderExpression.Case): String {
        val branches = expression.branches.joinToString(" ") { "WHEN ${condition(it.condition)} THEN ${write(it.result)}" }
        return "CASE $branches ELSE ${write(expression.otherwise)} END"
    }

    private fun condition(condition: OrderCondition): String = when (condition) {
        is OrderCondition.Comparison -> "${write(condition.left)} ${condition.operator.symbol} ${write(condition.right)}"
        is OrderCondition.Missing -> "${write(condition.operand)} ${if (condition.negated) "IS NOT NULL" else "IS NULL"}"
        is OrderCondition.All -> condition.conditions.joinToString(" AND ", "(", ")") { condition(it) }
        is OrderCondition.AnyOf -> condition.conditions.joinToString(" OR ", "(", ")") { condition(it) }
        is OrderCondition.Not -> "NOT (${condition(condition.condition)})"
    }

    private fun bind(value: Any): String {
        val name = "$PREFIX${bound.size}"
        bound[name] = CypherGenerator.convertToNeo4jValue(value)
        return "\$$name"
    }

    companion object {
        /** What the parameters of an order expression are named by, followed by their number. */
        const val PREFIX = "_order_"
    }
}
