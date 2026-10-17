package org.drivine.query.dsl

import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.TemporalOrderSupport

/**
 * Writes the [OrderExpression]s of one `ORDER BY` in Cypher. Each value is bound under a name of
 * its own, `_order_0`, `_order_1`, …, numbered in the order the values are written, and kept in
 * [bindings].
 *
 * @param grammar the engine's grammar, which says how it reads a moment as a number, or that it cannot
 * @param rootAlias what the DSL calls the root, whose properties alone an expression reads
 * @param path how a property of the root is written: from the projected map after a view's projection
 */
internal class CypherOrderExpressions(
    private val grammar: CypherGrammar,
    rootAlias: String,
    private val path: (String) -> String,
) : OrderExpressionWriter(rootAlias) {
    private val bound = linkedMapOf<String, Any?>()

    /** The values bound so far, by parameter name. */
    val bindings: Map<String, Any?> get() = bound

    override fun property(property: OrderExpression.Property, use: PropertyUse): String = path(property.path)

    override fun bind(value: Any): String {
        val name = "$PREFIX${bound.size}"
        bound[name] = CypherGenerator.convertToNeo4jValue(value)
        return "\$$name"
    }

    /** Division is of real numbers, as it is in SQL: Cypher divides two integers as integers. */
    override fun divide(left: String, right: String): String = "(toFloat($left) / $right)"

    override fun function(function: OrderFunction): String = function.written

    /** In parentheses of its own, as every operation is: the grammar's form may be a division. */
    override fun epochSeconds(moment: OrderExpression.Property): String =
        when (val support = grammar.epochSeconds(path(moment.path))) {
            is TemporalOrderSupport.Written -> "(${support.expression})"
            is TemporalOrderSupport.Refused -> throw UnsupportedOrderFunctionException("epochSeconds", support.engine, support.reason)
        }

    companion object {
        /** What the parameters of an order expression are named by, followed by their number. */
        const val PREFIX = "_order_"
    }
}
