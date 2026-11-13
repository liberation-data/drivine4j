package org.drivine.backend.postgres

import org.drivine.backend.sql.Fragments
import org.drivine.backend.sql.RelationshipConditions
import org.drivine.annotation.GraphView
import org.drivine.backend.postgres.PostgresSql.property
import org.drivine.backend.sql.SqlKind
import org.drivine.backend.sql.StatementScope
import org.drivine.backend.sql.Step
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.WhereCondition

/**
 * Writes the predicates of a statement over a [PostgresGraph] from the query DSL: the conditions on
 * the root and on the nodes related to it, the order of the roots, and the predicate that seeks past a
 * keyset cursor. It writes for one statement, whose [scope] it binds values in.
 *
 * @param rootAlias what a condition calls the root, and [root] the root's row in the statement
 * @param rootModel the root fragment, whose declared fields say how a property is read
 * @param viewModel the view whose relationship fields a condition may name; null for a fragment
 * @param depthOverrides the depths the query reads relationship fields to, by field, which a condition on one walks too
 */
internal class PostgresPredicates(
    private val scope: StatementScope,
    private val rows: PostgresRowSources,
    private val rootAlias: String,
    private val root: String,
    private val rootModel: FragmentModel,
    private val viewModel: GraphViewModel?,
    private val depthOverrides: Map<String, Int> = emptyMap(),
    private val fragments: Fragments,
) {

    /** The predicate [conditions] make on the root; null when there are none. */
    fun predicate(conditions: List<WhereCondition>): String? =
        all(grouped(conditions), mapOf(rootAlias to root), viewModel).ifEmpty { null }

    /** Conditions on a relationship's alias, gathered into one condition on it: see [RelationshipConditions.grouped]. */
    private fun grouped(conditions: List<WhereCondition>): List<WhereCondition> = RelationshipConditions.grouped(conditions, viewModel)

    /** [conditions] all met; where [negated], not all met: one of them not. */
    private fun all(conditions: List<WhereCondition>, rows: Map<String, String>, model: GraphViewModel?, negated: Boolean = false): String =
        conditions.joinToString(if (negated) " OR " else " AND ", if (negated && conditions.size > 1) "(" else "", if (negated && conditions.size > 1) ")" else "") {
            one(it, rows, model, negated)
        }

    /**
     * One condition as a predicate, or where [negated] its negation. A condition whose alias is no row of [rows] is on a
     * relationship field of that name: some node related through it meets the condition. A negation is taken down to the
     * comparisons, each a fragment of Cypher's `NOT`: a fragment may be false where Cypher's comparison is null, as neither
     * keeps a row, and SQL's `NOT` around it would keep the row Cypher's doesn't. An `EXISTS` and a label test are true or
     * false, and are negated by SQL's.
     */
    private fun one(condition: WhereCondition, rows: Map<String, String>, model: GraphViewModel?, negated: Boolean = false): String = when (condition) {
        is WhereCondition.PropertyCondition -> {
            val alias = condition.propertyPath.substringBefore('.')
            val row = rows[alias]
            if (row != null) {
                compared(row, condition.propertyPath.substringAfter('.'), condition.operator, condition.value, negated)
            } else {
                // A property of a related node, named outside any block: some related node has it.
                one(WhereCondition.RelationshipCondition(alias.substringBefore('_'), listOf(condition)), rows, model, negated)
            }
        }
        is WhereCondition.RelationshipCondition -> related(condition, rows, model).let { if (negated) "NOT ($it)" else it }
        is WhereCondition.LabelCondition -> {
            val row = rows[condition.alias]
            if (row != null) {
                (PostgresSql.hasLabels(row, condition.labels) ?: "TRUE").let { if (negated) "NOT ($it)" else it }
            } else {
                one(WhereCondition.RelationshipCondition(condition.alias, listOf(condition)), rows, model, negated)
            }
        }
        is WhereCondition.AnyLabelCondition -> {
            val row = rows.getValue(condition.alias)
            "EXISTS (SELECT 1 FROM jsonb_array_elements_text($row.labels) l WHERE l IN (${PostgresSql.values(scope.bound(condition.labels), SqlKind.TEXT)}))"
                .let { if (negated) "NOT $it" else it }
        }
        is WhereCondition.ListMembershipCondition -> {
            val row = rows.getValue(condition.propertyPath.substringBefore('.'))
            compared(row, condition.propertyPath.substringAfter('.'), ComparisonOperator.HAS_ELEMENT, condition.value, negated)
        }
        // Not any of them, where negated: each not.
        is WhereCondition.OrCondition -> condition.conditions.joinToString(if (negated) " AND " else " OR ", "(", ")") { one(it, rows, model, negated) }
        is WhereCondition.NotCondition -> all(condition.conditions, rows, model, !negated).let { if (condition.conditions.size > 1 && !negated) it else "($it)" }
    }

    /**
     * Whether some node the field loads meets the conditions on it; none does, when the condition is negated. Those
     * are the nodes related through the field that carry its target's labels, as many hops away as the load reads
     * ([Step.read]), here and on a nested field.
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
        val predicates = directPredicates(direct, target, row) + nestedPredicates(nested, targetType, target, row)
        val found = Step.anyPath(Step.read(relationship, depthOverrides)) { steps -> this.rows.exists(parent, steps, row, predicates) }
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
                val predicates = conditions.map { one(it, names, null) } + below
                Step.anyPath(Step.read(nestedRelationship, depthOverrides)) { steps -> rows.exists(row, steps, nestedRow, predicates) }
            }
    }

    /**
     * `property operator value`, as the compiler's module compiles its Cypher on the store's layout ([Fragments]): a value is
     * compared as Cypher compares it, whatever its kind, and an equality on the root is answered by its label's index.
     */
    private fun compared(row: String, key: String, operator: ComparisonOperator, value: Any?, negated: Boolean = false): String =
        fragments.condition(row, if (row == root) rootModel.labels else emptyList(), mapOf("value" to Fragments.value(operator, value))) {
            Fragments.compared(key, operator, it).let { cypher -> if (negated) "NOT ($cypher)" else cypher }
        }

    /** A boolean is compared with the text JSON writes it as, which is what `->>` reads it as. */
    private fun scalar(value: Any?): Any? = if (value is Boolean) value.toString() else value

    /** [value] as the values of a list: itself when it is one, else a list of it alone. */
    private fun listed(value: Any?): Collection<*> = when (value) {
        is Collection<*> -> value.map(::scalar)
        is Array<*> -> value.map(::scalar)
        else -> listOf(scalar(value))
    }

    // ----- Ordering and keyset paging -----

    /** The `ORDER BY` terms for [order], as Cypher orders it, compiled by the module ([Fragments.orderTerms]). */
    fun orderTerms(order: OrderTerm): String = fragments.orderTerms(root, rootAlias, order).joinToString()

    /** The predicate that selects the rows after a cursor, as Cypher compares the root's properties with the cursor's values. */
    fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String =
        fragments.after(root, rootModel.labels, orders, values, collectionSortCount)
}
