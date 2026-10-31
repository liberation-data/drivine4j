package org.drivine.cypher.sql

import org.drivine.DrivineException
import org.drivine.cypher.ast.Clause
import org.drivine.cypher.ast.Expression
import org.drivine.cypher.ast.Pattern
import org.drivine.cypher.ast.Projection
import org.drivine.cypher.ast.Query
import org.drivine.cypher.ast.SetItem

/**
 * Where a statement aggregates, as Neo4j refuses it when it plans the statement: an aggregate in a `WHERE` (its
 * InvalidAggregation), of a `MATCH`, a `WITH`, a comprehension, a quantifier, a pattern's or `EXISTS { … }`'s, in a
 * `CALL { … }` or a subquery too. Read from the statement alone, before it is compiled, so a statement that compiles is
 * compiled as it was. A subquery's own query, `COUNT { MATCH … RETURN count(*) }`, aggregates in its own projections. The
 * `WHERE` of a `WITH` that aggregates, or is `DISTINCT`, filters its groups, and holds an aggregate as Neo4j has it (#497).
 *
 * [isAggregate] and [children] are the compiler's, passed in so that its companion, part of what a subclass outside
 * Drivine sees, stays as it is.
 */
internal class Aggregates(
    private val isAggregate: (Expression) -> Boolean,
    private val children: (Expression) -> List<Expression>,
) {

    /** Refuses [query] where it aggregates as Cypher does not. */
    fun check(query: Query) = query(query)

    private fun query(query: Query) = (listOf(query.clauses) + query.unions.map { it.clauses }).forEach(::clauses)

    private fun clauses(clauses: List<Clause>): Unit = clauses.forEach { clause ->
        when (clause) {
            is Clause.Match -> { clause.patterns.forEach(::pattern); where(clause.where) }
            is Clause.Unwind -> expression(clause.list)
            is Clause.With -> {
                projection(clause.projection)
                // One that is DISTINCT groups its rows by its items, as one that aggregates does by those that do not.
                val groups = clause.projection.distinct || clause.projection.items.any { grouped(it.expression) != null }
                if (groups) having(clause.where) else where(clause.where)
            }
            is Clause.Create -> clause.patterns.forEach(::pattern)
            is Clause.Merge -> { pattern(clause.pattern); (clause.onCreate + clause.onMatch).forEach(::setItem) }
            is Clause.Set -> clause.items.forEach(::setItem)
            is Clause.Remove -> {}
            is Clause.Delete -> clause.expressions.forEach(::expression)
            is Clause.Call -> query(clause.query)
            is Clause.Foreach -> { expression(clause.list); clauses(clause.clauses) }
            is Clause.Return -> projection(clause.projection)
        }
    }

    private fun setItem(item: SetItem) {
        when (item) {
            is SetItem.Property -> expression(item.value)
            is SetItem.Replace -> expression(item.value)
            is SetItem.Add -> expression(item.value)
            is SetItem.Labels -> {}
        }
    }

    private fun projection(projection: Projection) {
        projection.items.forEach { expression(it.expression) }
        projection.orderBy.forEach { expression(it.expression) }
        expression(projection.skip)
        expression(projection.limit)
    }

    private fun pattern(pattern: Pattern) {
        pattern.nodes.forEach { expression(it.properties) }
        pattern.relationships.forEach { relationship ->
            expression(relationship.properties)
            relationship.group?.let { pattern(it.pattern); where(it.where) }
        }
    }

    /** Refuses a `WHERE`, [where], that aggregates outside a subquery's own query; then reads it as any expression. */
    private fun where(where: Expression?) {
        if (where == null) return
        aggregateIn(where)?.let(::refuse)
        expression(where)
    }

    /**
     * The `WHERE`, [where], of a `WITH` that aggregates: it filters the groups, as SQL's HAVING does, so an aggregate is
     * read in it as Neo4j reads it (#497), but not in a comprehension's, a quantifier's, a reduce's or a pattern's own expressions.
     */
    private fun having(where: Expression?) {
        if (where == null) return
        beyondGroups(where)?.let(::refuse)
        expression(where)
    }

    private fun refuse(aggregate: Expression): Nothing = throw DrivineException(
        "${named(aggregate)} aggregates, and a WHERE holds no aggregate unless its WITH aggregates, as in Neo4j: aggregate in a WITH first, " +
            "and filter what it gives in that WITH's WHERE, as in WITH x, count(*) AS c WHERE c > 1."
    )

    /**
     * The first aggregate [expression] holds outside a comprehension's, a quantifier's, a reduce's and a pattern's own
     * expressions and a subquery's own query, where [children] finds its parts: one the compiler reads over a WITH's groups.
     */
    private fun grouped(expression: Expression): Expression? =
        if (isAggregate(expression)) expression else children(expression).firstNotNullOfOrNull(::grouped)

    /** The first aggregate [expression] holds that [grouped] does not find, but [aggregateIn] does. */
    private fun beyondGroups(expression: Expression): Expression? {
        if (isAggregate(expression)) return null
        val near = children(expression)
        return near.firstNotNullOfOrNull(::beyondGroups) ?: (parts(expression) - near.toSet()).firstNotNullOfOrNull(::aggregateIn)
    }

    /** An aggregate's name as a message gives it. */
    private fun named(aggregate: Expression) = if (aggregate == Expression.CountStar) "count(*)" else "${(aggregate as Expression.Function).name}()"

    /** The first aggregate [expression] holds, a comprehension's, a pattern's or `EXISTS { … }`'s among them, but not one a subquery's own query holds. */
    private fun aggregateIn(expression: Expression?): Expression? {
        if (expression == null) return null
        if (isAggregate(expression)) return expression
        return when (expression) {
            is Expression.Subquery -> null
            else -> parts(expression).firstNotNullOfOrNull(::aggregateIn)
        }
    }

    /** Reads [expression] for the `WHERE`s and subqueries it holds. */
    private fun expression(expression: Expression?) {
        when (expression) {
            null -> {}
            is Expression.Subquery -> query(expression.query)
            is Expression.ListComprehension -> { expression(expression.list); where(expression.where); expression(expression.projection) }
            is Expression.Quantifier -> { expression(expression.list); where(expression.where) }
            is Expression.PatternComprehension -> { pattern(expression.pattern); where(expression.where); expression(expression.projection) }
            is Expression.Exists -> { expression.patterns.forEach(::pattern); where(expression.where) }
            is Expression.PatternPredicate -> pattern(expression.pattern)
            else -> parts(expression).forEach(::expression)
        }
    }

    /** Each expression [expression] holds directly: a comprehension's, a quantifier's, a reduce's and a pattern's parts too. */
    private fun parts(expression: Expression): List<Expression> = when (expression) {
        is Expression.ListComprehension -> listOfNotNull(expression.list, expression.where, expression.projection)
        is Expression.Quantifier -> listOfNotNull(expression.list, expression.where)
        is Expression.Reduce -> listOf(expression.initial, expression.list, expression.expression)
        is Expression.PatternComprehension -> patternParts(expression.pattern) + listOfNotNull(expression.where, expression.projection)
        is Expression.PatternPredicate -> patternParts(expression.pattern)
        is Expression.Exists -> expression.patterns.flatMap(::patternParts) + listOfNotNull(expression.where)
        is Expression.Subquery -> emptyList()
        else -> children(expression)
    }

    private fun patternParts(pattern: Pattern): List<Expression> =
        pattern.nodes.mapNotNull { it.properties } + pattern.relationships.flatMap { relationship ->
            listOfNotNull(relationship.properties) + (relationship.group?.let { patternParts(it.pattern) + listOfNotNull(it.where) } ?: emptyList())
        }
}
