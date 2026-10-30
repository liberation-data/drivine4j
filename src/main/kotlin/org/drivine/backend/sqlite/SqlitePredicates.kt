package org.drivine.backend.sqlite

import org.drivine.backend.sql.RelationshipConditions
import org.drivine.backend.sql.KeysetPredicate
import org.drivine.annotation.GraphView
import org.drivine.backend.sqlite.SqliteSql.property
import org.drivine.backend.sql.SqlKind
import org.drivine.backend.sql.StatementScope
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.Stamps
import org.drivine.query.GraphTypeLabels
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.ExpressionOrder
import org.drivine.query.dsl.NullPlacement
import org.drivine.query.dsl.OrderDirection
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
 * Writes the predicates of a statement over a [SqliteGraph] from the query DSL: the conditions on
 * the root and on the nodes related to it, the order of the roots, and the predicate that seeks past a
 * keyset cursor. It writes for one statement, whose [scope] it binds values in.
 *
 * A value compared with a moment or a date is read as the property is (see [SqliteSql.typed]): a
 * moment bound as its text is compared as nanoseconds since the epoch. Where an order puts its nulls is
 * always said, as SQLite puts them first when ascending and Cypher last.
 *
 * @param rootAlias what a condition calls the root, and [root] the root's row in the statement
 * @param rootModel the root fragment, whose declared fields say how a property is read
 * @param viewModel the view whose relationship fields a condition may name; null for a fragment
 */
internal class SqlitePredicates(
    private val scope: StatementScope,
    private val rows: SqliteRowSources,
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
                SqliteSql.hasLabels(row, condition.labels) ?: "1"
            } else {
                one(WhereCondition.RelationshipCondition(condition.alias, listOf(condition)), rows, model)
            }
        }
        is WhereCondition.AnyLabelCondition -> {
            val row = rows.getValue(condition.alias)
            "EXISTS (SELECT 1 FROM json_each($row.labels) l WHERE l.value IN (${SqliteSql.values(scope.bound(condition.labels), SqlKind.TEXT)}))"
        }
        is WhereCondition.ListMembershipCondition -> {
            val row = rows.getValue(condition.propertyPath.substringBefore('.'))
            holds(row, condition.propertyPath.substringAfter('.'), condition.value)
        }
        is WhereCondition.OrCondition -> condition.conditions.joinToString(" OR ", "(", ")") { one(it, rows, model) }
        is WhereCondition.NotCondition -> "NOT (${all(condition.conditions, rows, model)})"
    }

    /**
     * Whether some node the field loads meets the conditions on it; none does, when the condition is negated. Those
     * are the nodes related through the field that carry its target's labels, here and on a nested field.
     */
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
            parent, this.rows.steps(relationship, GraphTypeLabels.targetLabels(relationship)), row,
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
        val relationships = targetModel.relationships.map { it.fieldName }
        fun aliasOf(condition: WhereCondition) = (condition as WhereCondition.PropertyCondition).propertyPath.substringBefore('.')
        // Keyed by the relationship the alias names next, which may hold an underscore; a level below it is checked within it.
        return nested.groupBy { aliasOf(it).removePrefix("${target}_").let { rest -> CypherGenerator.relationshipNamedFirst(rest, relationships) ?: rest } }
            .map { (name, all) ->
                val nestedRelationship = targetModel.relationships.find { it.fieldName == name }
                    ?: throw IllegalArgumentException("Nested relationship '$name' not found in ${targetModel.className}")
                val nestedRow = scope.row(name)
                val (deeper, conditions) = all.partition { aliasOf(it).startsWith("${target}_${name}_") }
                val nestedType = nestedRelationship.let { if (it.isRelationshipFragment) it.targetNodeType!! else it.elementType }
                val below = nestedPredicates(deeper, nestedType, "${target}_$name", nestedRow)
                val names = mapOf("${target}_$name" to nestedRow)
                rows.exists(row, rows.steps(nestedRelationship, GraphTypeLabels.targetLabels(nestedRelationship)), nestedRow, conditions.map { one(it, names, null) } + below)
            }
    }

    /** `property operator value`, with the property read as the kind of the value it is compared with. */
    private fun compared(row: String, key: String, operator: ComparisonOperator, value: Any?): String {
        val text = property(row, key)
        val kind = SqlKind.ofValue(value)
        val typed = property(row, key, kind)
        fun bound() = SqliteSql.typed(kind, scope.bound(scalar(value)))
        fun string() = SqliteSql.text(scope.bound(value?.toString()))
        fun lowered() = SqliteSql.text(scope.bound(value?.toString()?.lowercase()))
        fun lower(text: String) = "${SqliteFunctions.LOWER}($text)"
        return when (operator) {
            ComparisonOperator.IS_NULL -> "NOT ${SqliteSql.hasProperty(row, key)}"
            ComparisonOperator.IS_NOT_NULL -> SqliteSql.hasProperty(row, key)
            ComparisonOperator.EQUALS -> "$typed = ${bound()}"
            ComparisonOperator.NOT_EQUALS -> "$typed <> ${bound()}"
            ComparisonOperator.GREATER_THAN -> "$typed > ${bound()}"
            ComparisonOperator.GREATER_THAN_OR_EQUAL -> "$typed >= ${bound()}"
            ComparisonOperator.LESS_THAN -> "$typed < ${bound()}"
            ComparisonOperator.LESS_THAN_OR_EQUAL -> "$typed <= ${bound()}"
            ComparisonOperator.IN -> "$typed IN (${SqliteSql.values(scope.bound(listed(value)), SqlKind.ofValue(value))})"
            ComparisonOperator.NOT_IN -> "NOT ($typed IN (${SqliteSql.values(scope.bound(listed(value)), SqlKind.ofValue(value))}))"
            ComparisonOperator.HAS_ELEMENT -> holds(row, key, value)
            ComparisonOperator.CONTAINS -> "instr($text, ${string()}) > 0"
            ComparisonOperator.STARTS_WITH -> string().let { "substr($text, 1, length($it)) = $it" }
            // `substr(t, -0)` is not the empty end of t, so an empty suffix is said to end every text.
            ComparisonOperator.ENDS_WITH -> string().let { "(length($it) = 0 AND $text IS NOT NULL OR substr($text, -length($it)) = $it)" }
            ComparisonOperator.CONTAINS_IGNORE_CASE -> "instr(${lower(text)}, ${lowered()}) > 0"
            ComparisonOperator.EQUALS_IGNORE_CASE -> "${lower(text)} = ${lowered()}"
            // A lowercased copy, compared with the value as SQLite lowercases it: see LowerCopies.
            ComparisonOperator.EQUALS_LOWERED -> "$text = ${lower(string())}"
            ComparisonOperator.CONTAINS_LOWERED -> "instr($text, ${lower(string())}) > 0"
            ComparisonOperator.MATCHES -> matches(text, value?.toString().orEmpty())
        }
    }

    /**
     * A regular expression that must match the whole value, as Cypher's `=~` does. A leading `(?i)`
     * ignores case. SQLite has no regular expressions of its own, so the expression is Java's, read by
     * the function [SqliteRegexpFunction] registers as SQLite's `REGEXP`. It is held to the whole value
     * by a group that takes no number, so its own groups keep theirs and a backreference names the
     * group it was written for.
     */
    private fun matches(text: String, pattern: String): String {
        val ignoreCase = pattern.startsWith("(?i)")
        val body = pattern.removePrefix("(?i)")
        return "$text REGEXP ${SqliteSql.text(scope.bound("${if (ignoreCase) "(?i)" else ""}^(?:$body)\\z"))}"
    }

    /**
     * Whether the list property [key] of [row] holds [value], as Postgres's `@>` says an array holds an
     * element: of the same JSON type, a number of either kind, and equal. The value is bound as the
     * one-element array of it, so it is read as JSON reads it.
     */
    private fun holds(row: String, key: String, value: Any?): String {
        val elements = "json_each($row.props, ${SqliteSql.path(key)}) h, json_each(${scope.bound(listOf(value))}) b"
        val sameType = "(h.type = b.type OR (h.type IN ('integer', 'real') AND b.type IN ('integer', 'real')))"
        return "(${SqliteSql.type(row, key)} = 'array' AND EXISTS (SELECT 1 FROM $elements WHERE $sameType AND h.value IS b.value))"
    }

    /** A boolean is compared with the text JSON writes it as, which is what a property read as text gives (see [SqliteSql.property]): SQLite's own `->>` reads `true` as 1. */
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
        is ExpressionOrder -> "${expressions.write(order.expression)} ${order.direction.name}${placement(order.nulls, order.direction)}"
    }

    private fun propertyTerms(order: OrderSpec): String {
        val key = order.propertyPath.substringAfter('.')
        val direction = order.direction.name
        if (key == Stamps.PROPERTY || declaredKind(key) != null || key in scope.inferredKinds) {
            return "${ordered(key)} $direction${placement(order.nulls, order.direction)}"
        }
        val text = property(root, key)
        val nulls = when (order.nulls) {
            NullPlacement.FIRST -> "CASE WHEN $text IS NULL THEN 0 ELSE 1 END, "
            NullPlacement.LAST -> "CASE WHEN $text IS NULL THEN 1 ELSE 0 END, "
            null -> ""
        }
        val placed = placement(null, order.direction)
        return "$nulls${SqliteSql.number(root, key)} $direction$placed, $text $direction$placed"
    }

    /**
     * Where a term of one value in [direction] puts its nulls, as SQL says so after the direction. With
     * none asked for, where Cypher puts them: last when ascending, first when descending.
     */
    private fun placement(nulls: NullPlacement?, direction: OrderDirection): String = when (nulls) {
        NullPlacement.FIRST -> " NULLS FIRST"
        NullPlacement.LAST -> " NULLS LAST"
        null -> if (direction == OrderDirection.ASC) " NULLS LAST" else " NULLS FIRST"
    }

    private val expressions = SqliteOrderExpressions()

    /**
     * Writes an order expression in SQL. A property is read as the field that declares it is ordered;
     * one no field declares, as the kind of the value it is compared with in a case, as text in a test
     * for a value, and as a number anywhere else, where it is a term of arithmetic.
     */
    private inner class SqliteOrderExpressions : OrderExpressionWriter(rootAlias) {

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

        override fun bind(value: Any): String = boundAs(value)

        /** A division of numbers, as real numbers: an integer divided by one is not cut to an integer. */
        override fun divide(left: String, right: String): String = "(CAST(($left) AS REAL) / ($right))"

        override fun function(function: OrderFunction): String = function.written

        /** The moment a property holds as seconds since the epoch. Only a property a field declares as a moment is read as one. */
        override fun epochSeconds(moment: OrderExpression.Property): String {
            if (declaredKind(moment.propertyName) != SqlKind.INSTANT) {
                throw UnsupportedOrderFunctionException(
                    "epochSeconds", "SQLite", "it reads a moment only from a property a field declares as a moment, and ${moment.path} is not one.",
                )
            }
            return "(CAST(${ordered(moment.propertyName)} AS REAL) / 1000000000)"
        }
    }

    /** The predicate that selects the rows after a cursor: see [KeysetPredicate.after]. */
    fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String =
        KeysetPredicate.after(orders, values, collectionSortCount, scope.inferredKinds, ::declaredKind, ::boundAs, ::ordered)

    /** [value] bound, and read as a property compared with it is: a moment as nanoseconds since the epoch. */
    private fun boundAs(value: Any?): String = SqliteSql.typed(SqlKind.ofValue(value), scope.bound(scalar(value)))
}
