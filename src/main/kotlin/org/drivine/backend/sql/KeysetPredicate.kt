package org.drivine.backend.sql

import org.drivine.query.dsl.KeysetPlanner
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.SeekValueSpec

/** The predicate that selects the rows after a keyset cursor, the same on every SQL store, as the Cypher the module compiles. */
internal object KeysetPredicate {

    /**
     * The predicate that selects the rows after a cursor, as Cypher over the root's properties, each written by [property],
     * and the cursor's values it binds, `c0`, `c1`, …: for orders `a, b` and the cursor `(A, B)`, `a > A OR (a = A AND
     * b > B)`, each comparison turned round for a descending order. The module compiles it as Cypher compares, whatever kind
     * each value is, so a row whose ordered property is absent meets no comparison, and is not returned.
     */
    fun cypher(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int, property: (String) -> String): Pair<String, Map<String, Any?>> {
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
        fun key(index: Int) = property(orders[index].propertyPath.substringAfter('.'))
        val branches = orders.indices.map { branch ->
            val equal = (0 until branch).map { "${key(it)} = \$c$it" }
            val beyond = "${key(branch)} ${if (orders[branch].direction == OrderDirection.ASC) ">" else "<"} \$c$branch"
            (equal + beyond).let { if (it.size == 1) it.single() else it.joinToString(" AND ", "(", ")") }
        }
        return (branches.singleOrNull() ?: branches.joinToString(" OR ", "(", ")")) to values.mapIndexed { i, v -> "c$i" to v.value }.toMap()
    }

    /** Why a seek may have fewer root orders than the query gave: some sort a relationship collection. */
    private fun hint(collectionSortCount: Int): String =
        if (collectionSortCount == 0) "" else " ($collectionSortCount declared orderBy properties sort a relationship collection, which cannot participate in a keyset cursor)"
}
