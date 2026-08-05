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
 * `(a <= $_seek_0 AND (a < $_seek_0 OR (a = $_seek_0 AND b < $_seek_1)))`.
 *
 * The leading `a <= $_seek_0` is logically redundant — the disjunction already implies it — but a
 * top-level `OR` typically stops a planner from using an index on `a`, whereas the extra conjunct
 * gives it a range bound to seek on. It is omitted for a single-key cursor, where the predicate is
 * already a bare comparison.
 *
 * **Nulls.** A row whose ordered property is null never satisfies a comparison, so it is dropped by
 * every page after the first. Engines also disagree on where nulls sort. Keyset properties must be
 * non-null in the data; see the README's pagination section.
 */
internal object KeysetPlanner {

    /**
     * @param orders the root-level orders, in declaration order
     * @param values the cursor components, which must align 1:1 with [orders]
     * @param collectionSortCount how many declared orders were routed to collection sorts rather
     *   than root ordering — used only to explain an arity mismatch
     */
    fun plan(
        orders: List<OrderSpec>,
        values: List<SeekValueSpec>,
        collectionSortCount: Int = 0,
    ): KeysetPlan {
        require(orders.isNotEmpty()) {
            "seek requires at least one root orderBy property" +
                collectionSortHint(collectionSortCount)
        }
        require(orders.size == values.size) {
            "seek supplied ${values.size} cursor values for ${orders.size} root orderBy " +
                "properties" + collectionSortHint(collectionSortCount)
        }

        orders.zip(values).forEachIndexed { index, (order, value) ->
            require(order.propertyPath == value.propertyPath) {
                "seek property #${index + 1} is ${value.propertyPath}, but orderBy property " +
                    "#${index + 1} is ${order.propertyPath}"
            }
        }

        val branches = orders.indices.map { branchIndex ->
            val equalPrefix = (0 until branchIndex).map { keyIndex ->
                "${orders[keyIndex].propertyPath} = \$${paramName(keyIndex)}"
            }
            val tail = comparison(orders[branchIndex], strict = true, index = branchIndex)
            (equalPrefix + tail).joinToString(" AND ").let { if (branchIndex == 0) it else "($it)" }
        }

        val disjunction = branches.joinToString(" OR ", prefix = "(", postfix = ")")
        val predicate = if (orders.size == 1) {
            disjunction
        } else {
            "(${comparison(orders[0], strict = false, index = 0)} AND $disjunction)"
        }

        return KeysetPlan(
            predicate = predicate,
            bindings = values.mapIndexed { index, value ->
                paramName(index) to requireNotNull(CypherGenerator.convertToNeo4jValue(value.value)) {
                    "Keyset cursor value for ${value.propertyPath} converted to null"
                }
            }.toMap(),
        )
    }

    /** Renders `path < $_seek_n` (strict) or `path <= $_seek_n`, per the order's direction. */
    private fun comparison(order: OrderSpec, strict: Boolean, index: Int): String {
        val operator = when (order.direction) {
            OrderDirection.ASC -> if (strict) ">" else ">="
            OrderDirection.DESC -> if (strict) "<" else "<="
        }
        return "${order.propertyPath} $operator \$${paramName(index)}"
    }

    private fun collectionSortHint(collectionSortCount: Int): String = when (collectionSortCount) {
        0 -> ""
        1 -> " (1 declared orderBy property sorts a relationship collection, which cannot " +
            "participate in a keyset cursor)"
        else -> " ($collectionSortCount declared orderBy properties sort a relationship " +
            "collection, which cannot participate in a keyset cursor)"
    }

    private fun paramName(index: Int): String = "_seek_$index"
}
