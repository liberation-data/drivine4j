package org.drivine.backend.sql

import org.drivine.query.dsl.KeysetPlanner
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.SeekValueSpec

/**
 * The predicate that selects the rows after a keyset cursor, the same on every SQL store: what differs
 * is how a store reads an ordered property and binds a value, which it hands in.
 */
internal object KeysetPredicate {

    /**
     * The predicate that selects the rows after a cursor: for orders `a, b` and the cursor `(A, B)`,
     * `a > A OR (a = A AND b > B)`, each comparison turned round for a descending order. A row whose
     * ordered property is absent meets no comparison, and is not returned. A property no field
     * declares is compared, and ordered, as the kind of the cursor value, which is learnt into
     * [inferredKinds] before any property is read.
     *
     * @param declaredKind the kind the field that declares a root property is read as; null when none does
     * @param bind binds a cursor value and gives the parameter that stands for it
     * @param ordered a root property as the store orders it
     */
    fun after(
        orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int,
        inferredKinds: MutableMap<String, SqlKind>, declaredKind: (String) -> SqlKind?, bind: (Any?) -> String, ordered: (String) -> String,
    ): String {
        require(orders.isNotEmpty()) { "seek requires at least one root orderBy property" + hint(collectionSortCount) }
        require(orders.size == values.size) {
            "seek supplied ${values.size} cursor values for ${orders.size} root orderBy properties" + hint(collectionSortCount)
        }
        orders.forEach { it.requireNoNullPlacement(KeysetPlanner.NULLS_UNDER_SEEK) }
        orders.zip(values).forEachIndexed { index, (order, value) ->
            require(order.propertyPath == value.propertyPath) {
                "seek property #${index + 1} is ${value.propertyPath}, but orderBy property #${index + 1} is ${order.propertyPath}"
            }
        }
        val cursor = values.map { bind(it.value) }
        orders.zip(values).forEach { (order, value) ->
            val key = order.propertyPath.substringAfter('.')
            if (declaredKind(key) == null) inferredKinds[key] = SqlKind.ofValue(value.value)
        }
        fun key(index: Int) = ordered(orders[index].propertyPath.substringAfter('.'))
        val branches = orders.indices.map { branch ->
            val equal = (0 until branch).map { "${key(it)} = ${cursor[it]}" }
            val beyond = "${key(branch)} ${if (orders[branch].direction == OrderDirection.ASC) ">" else "<"} ${cursor[branch]}"
            // The first branch is one comparison, which needs no parentheses of its own.
            (equal + beyond).let { if (it.size == 1) it.single() else it.joinToString(" AND ", "(", ")") }
        }
        // One order is one comparison, which `AND` joins to the statement's other predicates as it stands.
        return branches.singleOrNull() ?: branches.joinToString(" OR ", "(", ")")
    }

    /** Why a seek may have fewer root orders than the query gave: some sort a relationship collection. */
    private fun hint(collectionSortCount: Int): String =
        if (collectionSortCount == 0) "" else " ($collectionSortCount declared orderBy properties sort a relationship collection, which cannot participate in a keyset cursor)"
}
