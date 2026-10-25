package org.drivine.backend.oracle

import org.drivine.backend.sql.RelationshipConditions
import org.drivine.backend.sql.KeysetPredicate
import org.drivine.backend.sql.SqlKind
import org.drivine.annotation.GraphView
import org.drivine.backend.oracle.OracleSql.hasLabels
import org.drivine.backend.oracle.OracleSql.path
import org.drivine.backend.oracle.OracleSql.property
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.Stamps
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.NullPlacement
import org.drivine.query.dsl.ExpressionOrder
import org.drivine.query.dsl.OrderExpression
import org.drivine.query.dsl.OrderExpressionWriter
import org.drivine.query.dsl.OrderFunction
import org.drivine.query.dsl.PropertyUse
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.query.dsl.UnsupportedOrderFunctionException
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.WhereCondition

/**
 * Writes the predicates of a statement over an [OracleGraph] from the query DSL: the conditions on the
 * root and on the nodes related to it, the order of the roots, and the predicate that seeks past a
 * keyset cursor. It writes for one statement, whose [scope] it binds values in.
 *
 * @param rootAlias what a condition calls the root, and [root] the root's row in the statement
 * @param rootModel the root fragment, whose declared fields say how a property is read
 * @param viewModel the view whose relationship fields a condition may name; null for a fragment
 * @param rootLabels the labels of the root
 * @param labelled the labelled indexes there are, which a condition on the root may be read through
 */
internal class OraclePredicates(
    private val scope: OracleStatementScope,
    private val rows: OracleRowSources,
    private val rootAlias: String,
    private val root: String,
    private val rootModel: FragmentModel,
    private val viewModel: GraphViewModel?,
    private val rootLabels: () -> List<String> = { emptyList() },
    private val labelled: LabelledIndexes = LabelledIndexes.NONE,
) {

    /**
     * The predicate [conditions] make on the root; null when there are none.
     *
     * With [indexed], a condition every root meets that a labelled index can answer, a comparison of
     * a property with a text, is read through that index: the property is read as its expression,
     * null where the node does not carry the label, and the label is then not tested on its own (see
     * [OracleStatementScope.implied]). The index gives the nodes of the label that meet the
     * condition and nothing else, whether the value is rare or held by most of them. Tested on its
     * own in the form the index over labels answers, the label would be read through that index
     * instead: Oracle estimates it gives one row, and it gives every node of the label.
     */
    fun predicate(conditions: List<WhereCondition>, indexed: Boolean = false): String? {
        val grouped = grouped(conditions)
        if (indexed && !scope.narrowed) {
            grouped.forEach { condition -> answeredBy(condition)?.let { (key, label) -> scope.answered.putIfAbsent(key, label) } }
        }
        return all(grouped, mapOf(rootAlias to root), viewModel).ifEmpty { null }
    }

    /**
     * The property [condition] compares and the label of the root whose labelled index answers it,
     * for a condition every root meets; null when none does. An index answers a comparison with a text of the property it begins with: `=`, `<`,
     * `<=`, `>`, `>=`. Not `IN`, which Oracle answers by reading every node, and not a negation, a
     * test for no value or a test of part of the text, which no index answers.
     */
    private fun answeredBy(condition: WhereCondition): Pair<String, String>? {
        if (condition !is WhereCondition.PropertyCondition || condition.propertyPath.substringBefore('.') != rootAlias) return null
        val key = condition.propertyPath.substringAfter('.')
        val value = condition.value
        val answerable = condition.operator in RANGE && key != Stamps.PROPERTY &&
            value != null && value != "" && SqlKind.ofValue(value) == SqlKind.TEXT && value !is Collection<*>
        if (!answerable) return null
        return rootLabels().firstOrNull { labelled.leads(it, key) }?.let { key to it }
    }

    /**
     * The property [key] of the row [row], read as text. On the root, a property a label's index
     * answers for ([OracleStatementScope.answered]) is read through that label's index, and no
     * other's: that comparison is what tests the label. Another property is read through the index
     * of a label that needs no test of its own, where one holds it; it is then the same value, for
     * that label is tested by a comparison of its own. A comparison read through the expression of
     * an index may be answered by it.
     */
    private fun text(row: String, key: String): String {
        if (row != root || key == Stamps.PROPERTY) return property(row, key)
        scope.answered[key]?.let { label -> return labelled.expression(row, label, key) ?: property(row, key) }
        return scope.implied.firstNotNullOfOrNull { labelled.expression(row, it, key) } ?: property(row, key)
    }

    /** Conditions on a relationship's alias, gathered into one condition on it: see [RelationshipConditions.grouped]. */
    private fun grouped(conditions: List<WhereCondition>): List<WhereCondition> = RelationshipConditions.grouped(conditions, viewModel)

    /**
     * @param rows the row each alias of the query stands for
     * @param model the view whose relationship fields [conditions] may name
     */
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
                val relationship = alias.substringBefore('_')
                one(WhereCondition.RelationshipCondition(relationship, listOf(condition)), rows, model)
            }
        }
        is WhereCondition.RelationshipCondition -> related(condition, rows, model)
        is WhereCondition.LabelCondition -> {
            val row = rows[condition.alias]
            if (row != null) {
                // On a root read by its labels, the form the index over labels answers.
                (if (row == root) scope.rootHasLabels(row, condition.labels) else hasLabels(row, condition.labels)) ?: "1 = 1"
            } else {
                one(WhereCondition.RelationshipCondition(condition.alias, listOf(condition)), rows, model)
            }
        }
        is WhereCondition.AnyLabelCondition -> {
            val row = rows.getValue(condition.alias)
            "EXISTS (SELECT 1 FROM JSON_TABLE($row.labels, '$[*]' COLUMNS (l VARCHAR2(4000) PATH '$')) WHERE l IN (${OracleSql.values(scope.bound(condition.labels), SqlKind.TEXT)}))"
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
        // A pattern holds no subquery (`ORA-40988`), so a condition written with one is asked of the pattern's rows.
        val (onRows, inPattern) = direct.partition(::holdsSubquery)
        val found = this.rows.exists(
            parent, this.rows.steps(relationship, emptyList()), row,
            where = directPredicates(inPattern, target, row),
            predicates = directPredicates(onRows, target, row) + nestedPredicates(nested, targetType, target, row),
        )
        return if (condition.negate) "NOT $found" else found
    }

    /**
     * Whether [condition] is written with a subquery: a test against a list is, which reads the list
     * as rows, and so is a test for any of several labels. An `or` or a `not` is, when one of the
     * conditions it holds is: it is one predicate, and is asked whole where its conditions can be.
     */
    fun holdsSubquery(condition: WhereCondition): Boolean = when (condition) {
        is WhereCondition.PropertyCondition -> condition.operator == ComparisonOperator.IN || condition.operator == ComparisonOperator.NOT_IN
        is WhereCondition.AnyLabelCondition -> true
        is WhereCondition.OrCondition -> condition.conditions.any(::holdsSubquery)
        is WhereCondition.NotCondition -> condition.conditions.any(::holdsSubquery)
        else -> false
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
                val (onRows, inPattern) = conditions.partition(::holdsSubquery)
                val names = mapOf("${target}_$name" to nestedRow)
                // No labels: a condition on a relationship is met by whatever node it leads to.
                rows.exists(
                    row, rows.steps(nestedRelationship, emptyList()), nestedRow,
                    where = inPattern.map { one(it, names, null) }, predicates = onRows.map { one(it, names, null) } + below,
                )
            }
    }

    /**
     * `property operator value`, with the property read as the type of the value it is compared with.
     *
     * Oracle keeps an empty string as null, so one bound compares with nothing: a test against `''` is
     * written as the test it means, as Cypher reads it. `CONTAINS ''`, `STARTS WITH ''` and
     * `ENDS WITH ''` hold for any string, the empty one among them; `= ''` for a property stored
     * empty, which `JSON_VALUE` reads as null and a JSON path still sees; `<> ''` for any other.
     */
    private fun compared(row: String, key: String, operator: ComparisonOperator, value: Any?): String {
        val text = text(row, key)
        val kind = SqlKind.ofValue(value)
        val typed = if (kind == SqlKind.TEXT) text else property(row, key, kind)
        val empty = value == ""
        // Any string, the empty one among them: `JSON_VALUE` reads a stored `""` as null.
        val anyText = "($text IS NOT NULL OR ${storedEmpty(row, key)})"
        return when (operator) {
            ComparisonOperator.IS_NULL -> "NOT ${OracleSql.hasProperty(row, key)}"
            ComparisonOperator.IS_NOT_NULL -> OracleSql.hasProperty(row, key)
            ComparisonOperator.EQUALS -> if (empty) storedEmpty(row, key) else "$typed = ${scope.bound(scalar(value))}"
            ComparisonOperator.NOT_EQUALS -> if (empty) "($text IS NOT NULL AND NOT ${storedEmpty(row, key)})" else "$typed <> ${scope.bound(scalar(value))}"
            ComparisonOperator.GREATER_THAN -> "$typed > ${scope.bound(scalar(value))}"
            ComparisonOperator.GREATER_THAN_OR_EQUAL -> "$typed >= ${scope.bound(scalar(value))}"
            ComparisonOperator.LESS_THAN -> "$typed < ${scope.bound(scalar(value))}"
            ComparisonOperator.LESS_THAN_OR_EQUAL -> "$typed <= ${scope.bound(scalar(value))}"
            ComparisonOperator.IN -> "$typed IN (${OracleSql.values(scope.bound(listed(value)), SqlKind.ofValue(value))})"
            ComparisonOperator.NOT_IN -> "NOT ($typed IN (${OracleSql.values(scope.bound(listed(value)), SqlKind.ofValue(value))}))"
            ComparisonOperator.HAS_ELEMENT -> holds(row, key, value)
            ComparisonOperator.CONTAINS -> if (empty) anyText else "INSTR($text, ${scope.bound(value?.toString())}) > 0"
            ComparisonOperator.STARTS_WITH -> if (empty) anyText else scope.bound(value?.toString()).let { "SUBSTR($text, 1, LENGTH($it)) = $it" }
            ComparisonOperator.ENDS_WITH -> if (empty) anyText else scope.bound(value?.toString()).let { "SUBSTR($text, -LENGTH($it)) = $it" }
            ComparisonOperator.CONTAINS_IGNORE_CASE -> if (empty) anyText else "INSTR(LOWER($text), ${scope.bound(value?.toString()?.lowercase())}) > 0"
            ComparisonOperator.EQUALS_IGNORE_CASE -> if (empty) storedEmpty(row, key) else "LOWER($text) = ${scope.bound(value?.toString()?.lowercase())}"
            ComparisonOperator.MATCHES -> matches(text, value?.toString().orEmpty())
        }
    }

    /** Whether the property [key] of [row] is stored as the empty string, which `JSON_VALUE` reads as null. */
    private fun storedEmpty(row: String, key: String): String =
        if (key == Stamps.PROPERTY) "1 = 0" else "JSON_EXISTS($row.props, ${path(key, "?(@ == \"\")")})"

    /**
     * A regular expression that must match the whole value, as Cypher's `=~` does. A leading `(?i)`
     * ignores case. The expression itself is Oracle's, in the POSIX dialect: what Java adds to it,
     * such as `(?:…)`, a look-around or `\b`, is not read, and a flag anywhere but the start is not either.
     *
     * The expression is anchored in a group of its own, which POSIX can only write as one that
     * captures: so each group the caller wrote is one further on, and a backreference to it is
     * renumbered to match (see [renumbered]). It ends at `\z`, the value's end, as Oracle's `$` also
     * matches before a line break that ends it.
     */
    private fun matches(text: String, pattern: String): String {
        val ignoreCase = pattern.startsWith("(?i)")
        val body = renumbered(pattern.removePrefix("(?i)"))
        return "REGEXP_LIKE($text, ${scope.bound("^($body)\\z")}${if (ignoreCase) ", 'i'" else ""})"
    }

    /**
     * [body] with each backreference, `\1` to `\8`, one higher, for the group that anchors it is the
     * first. An escaped backslash is no backreference, nor is a backslash inside a bracket expression,
     * where POSIX reads it as itself. `\9` would need a tenth group, which POSIX does not have, and is
     * refused.
     */
    private fun renumbered(body: String): String {
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '[' -> {
                    // A bracket expression is copied whole: `[`, a `^`, a `]` first among its characters, and on to the `]` that ends it.
                    var end = i + 1
                    if (end < body.length && body[end] == '^') end++
                    if (end < body.length && body[end] == ']') end++
                    end = body.indexOf(']', end).let { if (it < 0) body.length else it + 1 }
                    out.append(body, i, end)
                    i = end
                }
                c == '\\' && i + 1 < body.length -> {
                    when (val next = body[i + 1]) {
                        in '1'..'8' -> out.append('\\').append(next + 1)
                        '9' -> throw IllegalArgumentException(
                            "matches on Oracle reads at most eight groups by reference: '$body' refers to the ninth, and Oracle's expressions have no tenth to anchor it beside."
                        )
                        else -> out.append(c).append(next)
                    }
                    i += 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * Whether the list property [key] of [row] holds [value]. A boolean is written into the path: in a
     * path a boolean is itself, which the text `JSON_VALUE` reads it as would not equal.
     */
    private fun holds(row: String, key: String, value: Any?): String =
        if (value is Boolean) {
            "JSON_EXISTS($row.props, ${path(key, "[*]?(@ == $value)")})"
        } else {
            "JSON_EXISTS($row.props, ${path(key, "[*]?(@ == \$v)")} PASSING ${scope.bound(value)} AS \"v\")"
        }

    /** A boolean is compared with the text JSON writes it as, which is what `JSON_VALUE` reads it as. */
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
        val inferred = scope.inferredKinds[key] ?: SqlKind.TEXT
        return if (inferred == SqlKind.NUMBER) undeclaredNumber(key) else property(root, key, inferred)
    }

    /**
     * A property no field declares, read as a number: a number stored as one, and null for anything
     * else. `RETURNING NUMBER` alone would read the text `"10"` as ten, and then a page of such texts,
     * ordered as numbers, would not be in the order a seek with one of them compares in.
     */
    private fun undeclaredNumber(key: String): String = SqlKind.NUMBER.read("$root.props", path(key, ".numberOnly()"))

    /**
     * The `ORDER BY` terms for [order]. A property no field declares, and no cursor says the kind of,
     * is ordered as a number first and as text after, so that a page of numbers is in the order a
     * seek with one of them as its cursor compares in, and anything else is in the order of its text.
     *
     * Where the order places its nulls, an order of one term says so on the term. An order of two
     * cannot: a row with a text is null as a number, so `NULLS LAST` on the number would move the
     * texts with the rows that have no value, and the texts and the numbers would change places. It
     * is ordered by whether the property has a value first, and then as it is without a placement.
     */
    fun orderTerms(order: OrderTerm): String = when (order) {
        is OrderSpec -> propertyTerms(order)
        is ExpressionOrder -> expressionTerm(order)
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
        return "$nulls${undeclaredNumber(key)} $direction, $text $direction"
    }

    /**
     * The `ORDER BY` term for an order by an expression: the expression in SQL, with its nulls placed
     * on the term. Every value it holds is bound.
     */
    private fun expressionTerm(order: ExpressionOrder): String =
        "${expressions.write(order.expression)} ${order.direction.name}${placement(order.nulls)}"

    /** Where a term of one value puts its nulls, as SQL says so after the direction. */
    private fun placement(nulls: NullPlacement?): String = when (nulls) {
        NullPlacement.FIRST -> " NULLS FIRST"
        NullPlacement.LAST -> " NULLS LAST"
        null -> ""
    }

    private val expressions = OracleOrderExpressions()

    /**
     * Writes an order expression in SQL. A property is read as the field that declares it is ordered
     * (see [ordered]); one no field declares, as the kind of the value it is compared with in a case,
     * as text in a test for a value, and as a number anywhere else, where it is a term of arithmetic.
     */
    private inner class OracleOrderExpressions : OrderExpressionWriter(rootAlias) {

        override fun property(property: OrderExpression.Property, use: PropertyUse): String {
            val key = property.propertyName
            if (key == Stamps.PROPERTY || declaredKind(key) != null) return ordered(key)
            val kind = when (use) {
                PropertyUse.Plain -> SqlKind.NUMBER
                is PropertyUse.ComparedWith -> SqlKind.ofValue(use.value)
                // As text: read as a number, a property no field declares would have no value where it holds a text.
                PropertyUse.TestedForValue -> SqlKind.TEXT
            }
            return if (kind == SqlKind.NUMBER) undeclaredNumber(key) else OracleSql.property(root, key, kind)
        }

        override fun bind(value: Any): String = scope.bound(scalar(value))

        /** SQL divides numbers as real numbers. */
        override fun divide(left: String, right: String): String = "($left / $right)"

        override fun function(function: OrderFunction): String = function.written.uppercase()

        /**
         * The moment a property holds as seconds since the epoch: the interval from the epoch to the
         * moment in UTC, in its days, hours, minutes and seconds with their fraction. Only a property a
         * field declares as a moment is read as one: any other is held as its text.
         */
        override fun epochSeconds(moment: OrderExpression.Property): String {
            if (declaredKind(moment.propertyName) != SqlKind.INSTANT) {
                throw UnsupportedOrderFunctionException(
                    "epochSeconds", "Oracle", "it reads a moment only from a property a field declares as a moment, and ${moment.path} is not one.",
                )
            }
            val since = "(SYS_EXTRACT_UTC(${ordered(moment.propertyName)}) - TIMESTAMP '1970-01-01 00:00:00')"
            return "(EXTRACT(DAY FROM $since) * 86400 + EXTRACT(HOUR FROM $since) * 3600 + EXTRACT(MINUTE FROM $since) * 60 + EXTRACT(SECOND FROM $since))"
        }
    }

    /** The predicate that selects the rows after a cursor: see [KeysetPredicate.after]. */
    fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String =
        KeysetPredicate.after(orders, values, collectionSortCount, scope.inferredKinds, ::declaredKind, { scope.bound(scalar(it)) }, ::ordered)

    private companion object {
        /** The comparisons an index answers. */
        val RANGE = setOf(
            ComparisonOperator.EQUALS, ComparisonOperator.GREATER_THAN, ComparisonOperator.GREATER_THAN_OR_EQUAL,
            ComparisonOperator.LESS_THAN, ComparisonOperator.LESS_THAN_OR_EQUAL,
        )
    }
}
