package org.drivine.backend.sql

import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.dsl.NullPlacement
import org.drivine.query.dsl.CypherOrderExpressions
import org.drivine.query.dsl.ExpressionOrder
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.cypher.sql.FragmentVariable
import org.drivine.connection.CypherCompile
import org.drivine.cypher.sql.SqlFragment
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.WhereCondition
import org.drivine.model.GraphViewModel

/**
 * The parts of one statement the object manager writes whose values the compiler's module compares, sorts and binds
 * (drivine-cypher-spec#161, its fragments): a condition on a property of a row of the statement, or the terms an `ORDER BY`
 * sorts by it, each as the module compiles the Cypher it is, on the store's layout, so that a temporal value is compared by
 * its key, a key that begins with `$` is read as it is held, an equality is answered by the store's index, and a value is
 * bound as a compiled statement binds it. The statement's joins, its rows and how it reads them back stay the object
 * manager's own.
 *
 * Each fragment's Cypher parameters are named apart, `f<n>_<name>`, and bound in [scope] under the names the module gives
 * them (`_cy_f<n>_<name>`), beside the statement's own. [hints] are those the statement's outermost `SELECT` carries.
 *
 * @param compiler the store's compiler, for this statement alone, as no other statement is compiled on it: from
 *   [CypherCompile], so that a fragment the module refuses is thrown as drivine4j's refusal of a statement is
 */
internal class Fragments(private val compiler: CypherCompile.FragmentCompiler, private val scope: StatementScope) {
    private var count = 0

    /** How many parameters the DSL's conditions written so far ([where]) bind: the next are numbered on from it. */
    private var conditionParameters = 0

    /** The optimizer hints the fragments written so far ask the statement's outermost `SELECT` to carry. */
    val hints = linkedSetOf<String>()

    /** Whether a value a fragment written so far gives may be NaN or an infinity. */
    var nonFinite = false
        private set

    /**
     * The condition [cypher] makes of the node [row], `n` in it, with [parameters] by the names it gives them: [labels] are
     * those the statement knows the node has, so an equality of its property is answered by that label's index.
     */
    fun condition(row: String, labels: List<String>, parameters: Map<String, Any?>, cypher: (Names) -> String): String {
        val names = Names(++count)
        val fragment = compiler.condition(cypher(names), mapOf(NODE to FragmentVariable.Node(row, labels)), names.given(parameters))
        return bound(fragment)
    }

    /**
     * The condition the query DSL's [conditions] make on the node [row], which they call [alias]: the Cypher the DSL writes
     * them as for Neo4j ([written]), compiled whole, so a condition on a related node is the module's `EXISTS` and a negation
     * is Cypher's. [labels] are the node's, for the index of an equality it must pass. Null where there are none.
     */
    fun where(row: String, alias: String, labels: List<String>, conditions: List<WhereCondition>, viewModel: GraphViewModel?, depthOverrides: Map<String, Int>): String? =
        written(conditions, viewModel, depthOverrides)?.let { (cypher, parameters) -> take(compiled(row, alias, labels, cypher, parameters)) }

    /**
     * The Cypher the query DSL writes [conditions] as for Neo4j ([CypherGenerator]), with the parameters it binds, numbered on
     * from those of the conditions written before; null where there are none.
     *
     * @param viewModel the view whose relationship fields a condition may name; null for a fragment
     * @param depthOverrides the depths the query reads relationship fields to, which a condition on one walks too
     */
    fun written(conditions: List<WhereCondition>, viewModel: GraphViewModel?, depthOverrides: Map<String, Int>): Pair<String, Map<String, Any?>>? {
        if (conditions.isEmpty()) return null
        val start = conditionParameters
        val written = CypherGenerator.buildWhereClause(conditions, viewModel, Neo4j5Grammar(ApocSortMapsEmitter()), false, start, depthOverrides)
        check(written.prologs.isEmpty() && written.bridgeVariables.isEmpty()) { "Neo4j's Cypher of a condition needs no clause before it: ${written.prologs}" }
        val cypher = written.whereClause ?: return null
        val parameters = CypherGenerator.extractBindings(conditions, viewModel, start)
        conditionParameters += CypherGenerator.parameterCount(conditions, viewModel)
        return cypher to parameters
    }

    /** The condition [cypher] of the node [row], which it calls [alias], of [labels], compiled and not yet [take]n. */
    fun compiled(row: String, alias: String, labels: List<String>, cypher: String, parameters: Map<String, Any?>): SqlFragment =
        compiler.condition(cypher, mapOf(alias to FragmentVariable.Node(row, labels)), parameters)

    /** [fragment], a condition [compiled] for the statement: its term, its parameters bound and its hints kept. */
    fun take(fragment: SqlFragment): String = bound(fragment)

    /** The terms an `ORDER BY` sorts by [cypher], over the node [row], `n` in it, descending where [descending]. */
    fun order(row: String, descending: Boolean, parameters: Map<String, Any?> = emptyMap(), cypher: (Names) -> String): List<String> {
        val names = Names(++count)
        val fragment = compiler.order(cypher(names), descending, mapOf(NODE to FragmentVariable.Node(row)), names.given(parameters))
        taken(fragment)
        return fragment.terms
    }

    /** The value [cypher] gives over the node [row], `n` in it, as the store's JSON holds it: for a statement to group or bind by, never to compare. */
    fun value(row: String, cypher: String): String = bound(compiler.value(cypher, mapOf(NODE to FragmentVariable.Node(row)), emptyMap()))

    /** The one term of [fragment], a condition or a value, once [taken]. */
    private fun bound(fragment: SqlFragment): String {
        taken(fragment)
        return fragment.terms.single()
    }

    /** [fragment]'s parameters bound in the statement, and its hints and whether it may give NaN kept for it. */
    private fun taken(fragment: SqlFragment) {
        scope.boundAs(fragment.parameters)
        hints += fragment.hints
        nonFinite = nonFinite || fragment.nonFinite
    }

    /** The names of one fragment's parameters: [invoke] gives `$f<n>_<name>`, apart from every other fragment's. */
    class Names(private val number: Int) {
        operator fun invoke(name: String) = "\$f${number}_$name"
        fun given(parameters: Map<String, Any?>) = parameters.mapKeys { (name, _) -> "f${number}_$name" }
    }

    /**
     * The terms an `ORDER BY` sorts the root [row] by its [order]: a property, or an expression of its properties (which the
     * DSL calls the root [rootAlias] in), as Cypher sorts it, its nulls first or last where the order places them.
     */
    fun orderTerms(row: String, rootAlias: String, order: OrderTerm): List<String> {
        val (cypher, parameters, nulls) = when (order) {
            is OrderSpec -> Triple(property(order.propertyPath.substringAfter('.')), emptyMap(), order.nulls)
            is ExpressionOrder -> {
                val written = CypherOrderExpressions(Neo4j5Grammar(ApocSortMapsEmitter()), rootAlias) { path -> property(path.substringAfter('.')) }
                Triple(written.write(order.expression), written.bindings, order.nulls)
            }
        }
        return ordered(row, cypher, order.direction == OrderDirection.DESC, nulls, parameters)
    }

    /**
     * The terms an `ORDER BY` sorts the node [row] by [cypher], over `n`, descending where [descending], its nulls first or
     * last where [nulls] places them and where Cypher does where it is null.
     */
    fun ordered(row: String, cypher: String, descending: Boolean, nulls: NullPlacement?, parameters: Map<String, Any?> = emptyMap()): List<String> {
        // Where an order places its nulls, a term of whether the value is null comes first: true sorts after false.
        val placed = when (nulls) {
            NullPlacement.FIRST -> order(row, true, parameters) { cypher.named(it, parameters.keys) + " IS NULL" }
            NullPlacement.LAST -> order(row, false, parameters) { cypher.named(it, parameters.keys) + " IS NULL" }
            null -> emptyList()
        }
        return placed + order(row, descending, parameters) { cypher.named(it, parameters.keys) }
    }

    /**
     * The predicate that selects the rows after a keyset cursor ([KeysetPredicate]): as Cypher compares the root's
     * properties with the cursor's values, its [labels] the root's.
     */
    fun after(row: String, labels: List<String>, orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String {
        val (cypher, parameters) = KeysetPredicate.cypher(orders, values, collectionSortCount, ::property)
        return condition(row, labels, parameters) { names -> cypher.named(names, parameters.keys) }
    }

    companion object {
        /** [this] Cypher with each of [parameters] named as the fragment [names] it. */
        private fun String.named(names: Names, parameters: Set<String>): String =
            parameters.sortedByDescending { it.length }.fold(this) { cypher, name -> cypher.replace("\$$name", names(name)) }

        /** The node a fragment is over, as its Cypher names it. */
        const val NODE = "n"

        /** [key] as Cypher reads a property of `n` by it: quoted, a backtick in it doubled. An empty name is none, as the graph engines' Cypher refuses it. */
        fun property(key: String): String {
            require(key.isNotEmpty()) { "A property name is empty: an empty name is no property a statement can read." }
            return "$NODE.`${key.replace("`", "``")}`"
        }

        /**
         * The Cypher of `property operator value` over `n`'s property [key], the value bound as `value`: as the graph
         * engines' Cypher writes each operator ([org.drivine.query.dsl.CypherGenerator]). A lowercased copy is compared with
         * the value lowercased, and an operator that ignores case lowercases both sides.
         */
        fun compared(key: String, operator: ComparisonOperator, names: Names): String {
            val p = property(key)
            val v = names("value")
            return when (operator) {
                ComparisonOperator.IS_NULL -> "$p IS NULL"
                ComparisonOperator.IS_NOT_NULL -> "$p IS NOT NULL"
                ComparisonOperator.EQUALS -> "$p = $v"
                ComparisonOperator.NOT_EQUALS -> "$p <> $v"
                ComparisonOperator.GREATER_THAN -> "$p > $v"
                ComparisonOperator.GREATER_THAN_OR_EQUAL -> "$p >= $v"
                ComparisonOperator.LESS_THAN -> "$p < $v"
                ComparisonOperator.LESS_THAN_OR_EQUAL -> "$p <= $v"
                ComparisonOperator.IN -> "$p IN $v"
                ComparisonOperator.NOT_IN -> "NOT ($p IN $v)"
                ComparisonOperator.HAS_ELEMENT -> "$v IN $p"
                ComparisonOperator.CONTAINS -> "$p CONTAINS $v"
                ComparisonOperator.STARTS_WITH -> "$p STARTS WITH $v"
                ComparisonOperator.ENDS_WITH -> "$p ENDS WITH $v"
                ComparisonOperator.CONTAINS_IGNORE_CASE -> "toLower($p) CONTAINS toLower($v)"
                ComparisonOperator.EQUALS_IGNORE_CASE -> "toLower($p) = toLower($v)"
                ComparisonOperator.EQUALS_LOWERED -> "$p = toLower($v)"
                ComparisonOperator.CONTAINS_LOWERED -> "$p CONTAINS toLower($v)"
                ComparisonOperator.MATCHES -> "$p =~ $v"
            }
        }

        /** The value [operator] binds for [value]: a list for `IN`, which the DSL may give as an array. */
        fun value(operator: ComparisonOperator, value: Any?): Any? = when {
            operator != ComparisonOperator.IN && operator != ComparisonOperator.NOT_IN -> value
            value is Collection<*> -> value.toList()
            value is Array<*> -> value.toList()
            else -> listOf(value)
        }
    }
}
