/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.drivine.query.dsl

/** A keyset continuation predicate and its reserved parameter bindings. */
internal data class KeysetPlan(
    val predicate: String,
    val bindings: Map<String, Any>,
)

/**
 * Compiles root ordering plus cursor values into a lexicographic seek predicate.
 *
 * For `a DESC, b DESC` after `(A, B)` this emits:
 * `(a < $_seek_0 OR (a = $_seek_0 AND b < $_seek_1))`.
 */
internal object KeysetPlanner {

    fun plan(orders: List<OrderSpec>, values: List<SeekValueSpec>): KeysetPlan {
        require(orders.isNotEmpty()) { "seekAfter requires at least one root orderBy property" }
        require(orders.size == values.size) {
            "seekAfter supplied ${values.size} cursor values for ${orders.size} root orderBy properties"
        }

        orders.zip(values).forEachIndexed { index, (order, value) ->
            require(order.propertyPath == value.propertyPath) {
                "seekAfter property #${index + 1} is ${value.propertyPath}, but orderBy property " +
                    "#${index + 1} is ${order.propertyPath}"
            }
        }

        val branches = orders.indices.map { branchIndex ->
            val equalPrefix = (0 until branchIndex).map { keyIndex ->
                "${orders[keyIndex].propertyPath} = \$${paramName(keyIndex)}"
            }
            val comparison = when (orders[branchIndex].direction) {
                OrderDirection.ASC -> ">"
                OrderDirection.DESC -> "<"
            }
            val tail = "${orders[branchIndex].propertyPath} $comparison \$${paramName(branchIndex)}"
            (equalPrefix + tail).joinToString(" AND ").let { if (branchIndex == 0) it else "($it)" }
        }

        return KeysetPlan(
            predicate = branches.joinToString(" OR ", prefix = "(", postfix = ")"),
            bindings = values.mapIndexed { index, value ->
                paramName(index) to requireNotNull(CypherGenerator.convertToNeo4jValue(value.value))
            }.toMap(),
        )
    }

    private fun paramName(index: Int): String = "_seek_$index"
}
