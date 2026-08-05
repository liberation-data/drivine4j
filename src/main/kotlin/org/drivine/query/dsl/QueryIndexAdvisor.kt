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

import org.drivine.schema.ConstraintManager
import org.drivine.schema.IndexManager
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * What to do when an ordered query has no index to seek into.
 *
 * Ordering without a matching index is *correct* — it just scans and sorts, which is fine for a
 * small collection and quietly expensive for a large one. So this is advice, not validation, and
 * the level is the caller's to choose.
 */
enum class IndexAdvicePolicy {
    /** Say nothing. */
    OFF,

    /** Log a warning, once per distinct label/property combination. The default. */
    WARN,

    /** Throw. Intended for development and CI, where an unindexed page should fail the build. */
    FAIL,
}

/**
 * Checks that an ordered or keyset-paginated query has a range index it can seek into, and reports
 * when it does not.
 *
 * The rule is that **the index mirrors the cursor**: a range index over exactly the ordered
 * properties, in the same order. Partial coverage does not help — see the pagination section of the
 * README for why an index over only some cursor keys is worse than none.
 *
 * The database's index list is read once and cached for the life of this advisor, so the check costs
 * one round trip per process rather than one per query. Schema created *after* the first check is
 * therefore invisible to it; call [refresh] if an application ensures indexes lazily.
 */
internal class QueryIndexAdvisor(
    private val indexes: IndexManager,
    private val constraints: ConstraintManager,
) {

    private val logger = LoggerFactory.getLogger(QueryIndexAdvisor::class.java)

    @Volatile
    private var cachedIndexes: List<SchemaItemInfo>? = null

    @Volatile
    private var cachedConstraints: List<SchemaItemInfo>? = null

    /** Signatures already reported, so a warning fires once rather than once per page. */
    private val reported = ConcurrentHashMap.newKeySet<String>()

    /**
     * Reports if no range index covers [properties] on [label].
     *
     * @param label the node label the ordering applies to
     * @param properties the ordered properties, in `orderBy` order
     * @param operation what the caller was doing, for the message ("seek", "orderBy")
     * @param pinnedBy properties the query constrains by equality at the top level of its `where`.
     *   If one of them is unique for [label], the query already selects at most one root, so its
     *   ordering is over a single row and no index would change the plan.
     */
    fun check(
        policy: IndexAdvicePolicy,
        label: String,
        properties: List<String>,
        operation: String,
        pinnedBy: Set<String> = emptySet(),
        uniqueByContract: Set<String> = emptySet(),
    ) {
        if (policy == IndexAdvicePolicy.OFF || properties.isEmpty()) return
        if (selectsAtMostOneRow(label, pinnedBy, uniqueByContract)) return
        if (hasMatchingIndex(label, properties)) return

        val signature = "$operation|$label|${properties.joinToString(",")}"
        val message = advice(label, properties, operation)

        when (policy) {
            IndexAdvicePolicy.FAIL -> throw IllegalStateException(message)
            IndexAdvicePolicy.WARN -> if (reported.add(signature)) logger.warn(message)
            IndexAdvicePolicy.OFF -> Unit
        }
    }

    /** Drops the cached index list, so the next check re-reads it from the database. */
    fun refresh() {
        cachedIndexes = null
        cachedConstraints = null
        reported.clear()
    }

    /**
     * Whether the query is already narrowed to at most one root, making its ordering trivial.
     *
     * Uniqueness comes from two places: the fragment's `@NodeId`, which Drivine treats as unique by
     * contract whether or not a constraint was declared, and single-property uniqueness constraints
     * the database actually holds. A composite uniqueness constraint does not count unless every one
     * of its properties is pinned.
     *
     * Only top-level equalities pin — an equality inside `anyOf` constrains nothing on its own.
     */
    private fun selectsAtMostOneRow(
        label: String,
        pinnedBy: Set<String>,
        uniqueByContract: Set<String>,
    ): Boolean {
        if (pinnedBy.isEmpty()) return false
        if (pinnedBy.any { it in uniqueByContract }) return true

        return constraintList()
            .filter { it.kind == SchemaItemKind.UNIQUENESS_CONSTRAINT && it.label == label }
            .any { it.properties.isNotEmpty() && pinnedBy.containsAll(it.properties) }
    }

    private fun hasMatchingIndex(label: String, properties: List<String>): Boolean =
        indexList().any {
            it.kind == SchemaItemKind.RANGE_INDEX && it.label == label && it.properties == properties
        }

    /**
     * Reads the index list once. A failure to introspect (permissions, an engine that does not
     * support it) yields an empty list rather than an error — advice must never break a query that
     * would otherwise run.
     */
    private fun indexList(): List<SchemaItemInfo> = cachedIndexes ?: read("indexes") { indexes.list() }
        .also { cachedIndexes = it }

    private fun constraintList(): List<SchemaItemInfo> =
        cachedConstraints ?: read("constraints") { constraints.list() }.also { cachedConstraints = it }

    private fun read(what: String, load: () -> List<SchemaItemInfo>): List<SchemaItemInfo> = try {
        load()
    } catch (e: Exception) {
        logger.debug("Could not introspect {} for query index advice", what, e)
        emptyList()
    }

    private fun advice(label: String, properties: List<String>, operation: String): String {
        val quoted = properties.joinToString(", ") { "\"$it\"" }
        val declaration = if (properties.size == 1) {
            "@RangeIndex on ${properties.single()}"
        } else {
            "@RangeIndex(properties = [$quoted])"
        }
        return "$operation on $label(${properties.joinToString(", ")}) has no matching range index, " +
            "so the query scans and sorts instead of seeking. Declare $declaration on the fragment, " +
            "or call indexes.ensure(RangeIndexSpec(\"$label\", listOf($quoted))). The index must " +
            "cover exactly these properties, in this order."
    }
}
