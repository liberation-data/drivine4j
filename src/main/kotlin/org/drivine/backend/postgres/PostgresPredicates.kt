package org.drivine.backend.postgres

import org.drivine.backend.sql.RelationshipConditions
import org.drivine.backend.sql.KeysetPredicate
import org.drivine.annotation.GraphView
import org.drivine.backend.postgres.PostgresSql.property
import org.drivine.backend.sql.SqlKind
import org.drivine.backend.sql.StatementScope
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.Stamps
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.ExpressionOrder
import org.drivine.query.dsl.NullPlacement
import org.drivine.query.dsl.OrderExpression
import org.drivine.query.dsl.OrderExpressionWriter
import org.drivine.query.dsl.OrderFunction
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.query.dsl.PropertyUse
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.UnsupportedOrderFunctionException
import org.drivine.query.dsl.WhereCondition

/**
 * Writes the predicates of a statement over a [PostgresGraph] from the query DSL: the conditions on
 * the root and on the nodes related to it, the order of the roots, and the predicate that seeks past a
 * keyset cursor. It writes for one statement, whose [scope] it binds values in.
 *
 * @param rootAlias what a condition calls the root, and [root] the root's row in the statement
 * @param rootModel the root fragment, whose declared fields say how a property is read
 * @param viewModel the view whose relationship fields a condition may name; null for a fragment
 */
internal class PostgresPredicates(
    private val scope: StatementScope,
    private val rows: PostgresRowSources,
    private val rootAlias: String,
    private val root: String,
    private val rootModel: FragmentModel,
    private val viewModel: GraphViewModel?,
) {

    /** The predicate [conditions] make on the root; null when there are none. */
    fun predicate(conditions: List<WhereCondition>): String? =
        all(grouped(conditions), mapOf(rootAlias to root), viewModel).ifEmpty { null }

    /** Conditions on a relationship's alias, gathered into one condition on it: see [RelationshipConditions.grouped]. */
    private fun grouped(conditions: List<WhereCondition>): List<WhereCondition> = RelationshipConditions.grouped(conditions, viewModel)

    private fun all(conditions: List<WhereCondition>, rows: Map<String, String>, model: GraphViewModel?): String =
        conditions.joinToString(" AND ") { one(it, rows, model) }

    /**
     * One condition as a predicate. A condition whose alias is no row of [rows] is on a relationship
     * field of that name: some node related through it meets the condition.
     */
    private fun one(condition: WhereCondition, rows: Map<String, String>, model: GraphViewModel?): String = when (condition) {
        is WhereCondition.PropertyCondition -> {
            val alias = condition.propertyPath.substringBefore('.')
            val row = rows[alias]
            if (row != null) {
                compared(row, condition.propertyPath.substringAfter('.'), condition.operator, condition.value)
            } else {
                // A property of a related node, named outside any block: some related node has it.
                one(WhereCondition.RelationshipCondition(alias.substringBefore('_'), listOf(condition)), rows, model)
            }
        }
        is WhereCondition.RelationshipCondition -> related(condition, rows, model)
        is WhereCondition.LabelCondition -> {
            val row = rows[condition.alias]
            if (row != null) {
                PostgresSql.hasLabels(row, condition.labels) ?: "TRUE"
            } else {
                one(WhereCondition.RelationshipCondition(condition.alias, listOf(condition)), rows, model)
            }
        }
        is WhereCondition.AnyLabelCondition -> {
            val row = rows.getValue(condition.alias)
            "EXISTS (SELECT 1 FROM jsonb_array_elements_text($row.labels) l WHERE l IN (${PostgresSql.values(scope.bound(condition.labels), SqlKind.TEXT)}))"
        }
        is WhereCondition.ListMembershipCondition -> {
            val row = rows.getValue(condition.propertyPath.substringBefore('.'))
            holds(row, condition.propertyPath.substringAfter('.'), condition.value)
        }
        is WhereCondition.OrCondition -> condition.conditions.joinToString(" OR ", "(", ")") { one(it, rows, model) }
        is WhereCondition.NotCondition -> "NOT (${all(condition.conditions, rows, model)})"
    }

    /** Whether some node related through the field meets the conditions on it; none does, when the condition is negated. */
    private fun related(condition: WhereCondition.RelationshipCondition, rows: Map<String, String>, model: GraphViewModel?): String {
        requireNotNull(model) { "A condition on the relationship '${condition.relationshipName}' needs a @GraphView to find it in." }
        val relationship = model.relationships.find { it.fieldName == condition.relationshipName }
            ?: throw IllegalArgumentException(
                "Relationship '${condition.relationshipName}' not found in ${model.className}. Available relationships: ${model.relationships.map { it.fieldName }}"
            )
        val parent = rows.getValue(rootAlias)
        val target = relationship.fieldName
        val row = scope.row(target)
        // Conditions on `<target>_<nested>` are on a node the target is related to in turn.
        val (nested, direct) = condition.targetConditions.partition {
            it is WhereCondition.PropertyCondition && it.propertyPath.substringBefore('.').startsWith("${target}_")
        }
        val targetType = relationship.let { if (it.isRelationshipFragment) it.targetNodeType!! else it.elementType }
        val found = this.rows.exists(
            parent, this.rows.steps(relationship, emptyList()), row,
            directPredicates(direct, target, row) + nestedPredicates(nested, targetType, target, row),
        )
        return if (condition.negate) "NOT $found" else found
    }

    /** The conditions on the related node itself, whose row is [row]. */
    private fun directPredicates(direct: List<WhereCondition>, target: String, row: String): List<String> = direct.map { targetCondition ->
        when (targetCondition) {
            is WhereCondition.ListMembershipCondition, is WhereCondition.NotCondition, is WhereCondition.AnyLabelCondition, is WhereCondition.RelationshipCondition ->
                throw UnsupportedOperationException(
                    "${targetCondition::class.simpleName} is not supported inside a relationship any{}/none{} block; use it as a top-level root predicate."
                )
            else -> one(targetCondition, mapOf(target to row), null)
        }
    }

    /** The conditions on nodes the related node, of [targetType] and row [row], is related to in turn: one `EXISTS` for each nested field. */
    private fun nestedPredicates(nested: List<WhereCondition>, targetType: Class<*>, target: String, row: String): List<String> {
        if (nested.isEmpty()) return emptyList()
        require(targetType.isAnnotationPresent(GraphView::class.java)) {
            "Cannot filter on nested relationship '${target}_*' because ${targetType.simpleName} is not a @GraphView"
        }
        val targetModel = GraphViewModel.from(targetType)
        return nested.groupBy { (it as WhereCondition.PropertyCondition).propertyPath.substringBefore('.').removePrefix("${target}_") }
            .map { (name, conditions) ->
                val nestedRelationship = targetModel.relationships.find { it.fieldName == name }
                    ?: throw IllegalArgumentException("Nested relationship '$name' not found in ${targetModel.className}")
                val nestedRow = scope.row(name)
                val names = mapOf("${target}_$name" to nestedRow)
                // No labels: a condition on a relationship is met by whatever node it leads to.
                rows.exists(row, rows.steps(nestedRelationship, emptyList()), nestedRow, conditions.map { one(it, names, null) })
            }
    }

    /** `property operator value`, with the property read as the kind of the value it is compared with. */
    private fun compared(row: String, key: String, operator: ComparisonOperator, value: Any?): String {
        val text = property(row, key)
        val typed = property(row, key, SqlKind.ofValue(value))
        fun bound() = scope.bound(scalar(value))
        fun string() = PostgresSql.text(scope.bound(value?.toString()))
        return when (operator) {
            ComparisonOperator.IS_NULL -> "NOT ${PostgresSql.hasProperty(row, key)}"
            ComparisonOperator.IS_NOT_NULL -> PostgresSql.hasProperty(row, key)
            ComparisonOperator.EQUALS -> "$typed = ${bound()}"
            ComparisonOperator.NOT_EQUALS -> "$typed <> ${bound()}"
            ComparisonOperator.GREATER_THAN -> "$typed > ${bound()}"
            ComparisonOperator.GREATER_THAN_OR_EQUAL -> "$typed >= ${bound()}"
            ComparisonOperator.LESS_THAN -> "$typed < ${bound()}"
            ComparisonOperator.LESS_THAN_OR_EQUAL -> "$typed <= ${bound()}"
            ComparisonOperator.IN -> "$typed IN (${PostgresSql.values(scope.bound(listed(value)), SqlKind.ofValue(value))})"
            ComparisonOperator.NOT_IN -> "NOT ($typed IN (${PostgresSql.values(scope.bound(listed(value)), SqlKind.ofValue(value))}))"
            ComparisonOperator.HAS_ELEMENT -> holds(row, key, value)
            ComparisonOperator.CONTAINS -> "strpos($text, ${string()}) > 0"
            ComparisonOperator.STARTS_WITH -> "starts_with($text, ${string()})"
            ComparisonOperator.ENDS_WITH -> string().let { "right($text, length($it)) = $it" }
            ComparisonOperator.CONTAINS_IGNORE_CASE -> "strpos(lower($text), ${PostgresSql.text(scope.bound(value?.toString()?.lowercase()))}) > 0"
            ComparisonOperator.EQUALS_IGNORE_CASE -> "lower($text) = ${PostgresSql.text(scope.bound(value?.toString()?.lowercase()))}"
            ComparisonOperator.MATCHES -> matches(text, value?.toString().orEmpty())
        }
    }

    /**
     * A regular expression that must match the whole value, as Cypher's `=~` does. A leading `(?i)`
     * ignores case. The expression itself is Postgres's, which reads most of what Java's does. It is
     * held to the whole value by a group that takes no number, so its own groups keep theirs and a
     * backreference names the group it was written for.
     */
    private fun matches(text: String, pattern: String): String {
        val ignoreCase = pattern.startsWith("(?i)")
        val body = pattern.removePrefix("(?i)")
        return "$text ${if (ignoreCase) "~*" else "~"} ${PostgresSql.text(scope.bound("^(?:$body)$"))}"
    }

    /** Whether the list property [key] of [row] holds [value]: the stored array contains the one-element array of it. */
    private fun holds(row: String, key: String, value: Any?): String =
        "(jsonb_typeof(${PostgresSql.value(row, key)}) = 'array' AND ${PostgresSql.value(row, key)} @> CAST(${scope.bound(listOf(value))} AS jsonb))"

    /** A boolean is compared with the text JSON writes it as, which is what `->>` reads it as. */
    private fun scalar(value: Any?): Any? = if (value is Boolean) value.toString() else value

    /** [value] as the values of a list: itself when it is one, else a list of it alone. */
    private fun listed(value: Any?): Collection<*> = when (value) {
        is Collection<*> -> value.map(::scalar)
        is Array<*> -> value.map(::scalar)
        else -> listOf(scalar(value))
    }

    // ----- Ordering and keyset paging -----

    /** The kind the field that declares the root property [key] is ordered as; null when no field does. */
    fun declaredKind(key: String): SqlKind? = rootModel.fields.firstOrNull { it.propertyName == key }?.type?.let(SqlKind::ofType)

    /**
     * A root property as it is ordered: by the type of the field that holds it; when no field does,
     * as the value a seek compares it with, and as text when there is none.
     */
    private fun ordered(key: String): String {
        val declared = declaredKind(key)
        if (declared != null) return property(root, key, declared)
        return property(root, key, scope.inferredKinds[key] ?: SqlKind.TEXT)
    }

    /**
     * The `ORDER BY` terms for [order]. A property no field declares, and no cursor says the kind of,
     * is ordered as a number first and as text after, so that a page of numbers is in the order a
     * seek with one of them as its cursor compares in, and anything else is in the order of its text.
     * Where an order of two terms places its nulls, it says so first: a text is null as a number, and a
     * placement on the number would move the texts with the rows that have no value.
     */
    fun orderTerms(order: OrderTerm): String = when (order) {
        is OrderSpec -> propertyTerms(order)
        is ExpressionOrder -> "${expressions.write(order.expression)} ${order.direction.name}${placement(order.nulls)}"
    }

    private fun propertyTerms(order: OrderSpec): String {
        val key = order.propertyPath.substringAfter('.')
        val direction = order.direction.name
        if (key == Stamps.PROPERTY || declaredKind(key) != null || key in scope.inferredKinds) {
            return "${ordered(key)} $direction${placement(order.nulls)}"
        }
        val text = property(root, key)
        val nulls = when (order.nulls) {
            NullPlacement.FIRST -> "CASE WHEN $text IS NULL THEN 0 ELSE 1 END, "
            NullPlacement.LAST -> "CASE WHEN $text IS NULL THEN 1 ELSE 0 END, "
            null -> ""
        }
        return "$nulls${PostgresSql.number(root, key)} $direction, $text $direction"
    }

    /**
     * Where a term of one value puts its nulls, as SQL says so after the direction. With none asked for,
     * SQL puts them where Cypher does: last when ascending, first when descending.
     */
    private fun placement(nulls: NullPlacement?): String = when (nulls) {
        NullPlacement.FIRST -> " NULLS FIRST"
        NullPlacement.LAST -> " NULLS LAST"
        null -> ""
    }

    private val expressions = PostgresOrderExpressions()

    /**
     * Writes an order expression in SQL. A property is read as the field that declares it is ordered;
     * one no field declares, as the kind of the value it is compared with in a case, as text in a test
     * for a value, and as a number anywhere else, where it is a term of arithmetic.
     */
    private inner class PostgresOrderExpressions : OrderExpressionWriter(rootAlias) {

        override fun property(property: OrderExpression.Property, use: PropertyUse): String {
            val key = property.propertyName
            if (key == Stamps.PROPERTY || declaredKind(key) != null) return ordered(key)
            val kind = when (use) {
                PropertyUse.Plain -> SqlKind.NUMBER
                is PropertyUse.ComparedWith -> SqlKind.ofValue(use.value)
                PropertyUse.TestedForValue -> SqlKind.TEXT
            }
            return property(root, key, kind)
        }

        override fun bind(value: Any): String = scope.bound(scalar(value))

        /** A division of numbers, as real numbers: an integer divided by one is not cut to an integer. */
        override fun divide(left: String, right: String): String = "(($left)::double precision / ($right))"

        override fun function(function: OrderFunction): String = function.written

        /** The moment a property holds as seconds since the epoch. Only a property a field declares as a moment is read as one. */
        override fun epochSeconds(moment: OrderExpression.Property): String {
            if (declaredKind(moment.propertyName) != SqlKind.INSTANT) {
                throw UnsupportedOrderFunctionException(
                    "epochSeconds", "Postgres", "it reads a moment only from a property a field declares as a moment, and ${moment.path} is not one.",
                )
            }
            return "EXTRACT(EPOCH FROM ${ordered(moment.propertyName)})"
        }
    }

    /** The predicate that selects the rows after a cursor: see [KeysetPredicate.after]. */
    fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String =
        KeysetPredicate.after(orders, values, collectionSortCount, scope.inferredKinds, ::declaredKind, { scope.bound(scalar(it)) }, ::ordered)
}
