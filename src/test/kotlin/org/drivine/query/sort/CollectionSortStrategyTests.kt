package org.drivine.query.sort

import org.drivine.query.GraphViewQueryBuilder
import org.drivine.query.dsl.CollectionSortSpec
import org.junit.jupiter.api.Test
import sample.mapped.view.RaisedAndAssignedIssue
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CollectionSortStrategyTests {

    // =========================================================================
    // APOC_SORT_MAPS — existing behavior preserved
    // =========================================================================

    @Test
    fun `APOC - top-level sort emits apoc coll sortMaps`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, ApocSortMapsEmitter())
        val sort = CollectionSortSpec("assignedTo", "name", ascending = true)
        val query = builder.buildQuery(null, null, listOf(sort))

        println("APOC top-level:\n$query\n")
        assertContains(query, "apoc.coll.sortMaps")
        assertContains(query, "reverse(")
        assertFalse(query.contains("CALL {"))
    }

    @Test
    fun `APOC - nested sort emits apoc coll sortMaps`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, ApocSortMapsEmitter())
        val sort = CollectionSortSpec("raisedBy_worksFor", "name", ascending = true)
        val query = builder.buildQuery(null, null, listOf(sort))

        println("APOC nested:\n$query\n")
        assertContains(query, "apoc.coll.sortMaps")
    }

    @Test
    fun `APOC - descending sort omits reverse`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, ApocSortMapsEmitter())
        val sort = CollectionSortSpec("assignedTo", "name", ascending = false)
        val query = builder.buildQuery(null, null, listOf(sort))

        assertContains(query, "apoc.coll.sortMaps")
        assertFalse(query.contains("reverse("))
    }

    // =========================================================================
    // CALL_SUBQUERY — portable path
    // =========================================================================

    @Test
    fun `CALL - top-level sort emits CALL subquery prolog`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CallSubqueryEmitter())
        val sort = CollectionSortSpec("assignedTo", "name", ascending = true)
        val query = builder.buildQuery(null, null, listOf(sort))

        println("CALL top-level:\n$query\n")
        assertContains(query, "CALL {")
        assertContains(query, "ORDER BY")
        assertContains(query, "collect(")
        assertContains(query, "ASC")
        assertFalse(query.contains("apoc.coll.sortMaps"))
    }

    @Test
    fun `CALL - descending sort emits DESC`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CallSubqueryEmitter())
        val sort = CollectionSortSpec("assignedTo", "name", ascending = false)
        val query = builder.buildQuery(null, null, listOf(sort))

        assertContains(query, "CALL {")
        assertContains(query, "DESC")
    }

    @Test
    fun `CALL - prolog appears before WITH clause`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CallSubqueryEmitter())
        val sort = CollectionSortSpec("assignedTo", "name", ascending = true)
        val query = builder.buildQuery(null, null, listOf(sort))

        val callIdx = query.indexOf("CALL {")
        val withIdx = query.indexOf("\nWITH\n")
        assertTrue(callIdx > 0, "CALL { should be present")
        assertTrue(withIdx > 0, "WITH should be present")
        assertTrue(callIdx < withIdx, "CALL { should appear before WITH")
    }

    @Test
    fun `CALL - no sort produces no CALL block`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CallSubqueryEmitter())
        val query = builder.buildQuery(null, null, emptyList())

        assertFalse(query.contains("CALL {"))
    }

    @Test
    fun `CALL - nested sort throws UnsupportedOperationException`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CallSubqueryEmitter())
        val sort = CollectionSortSpec("raisedBy_worksFor", "name", ascending = true)

        val ex = assertFailsWith<UnsupportedOperationException> {
            builder.buildQuery(null, null, listOf(sort))
        }
        assertContains(ex.message!!, "CALL_SUBQUERY")
        assertContains(ex.message!!, "@SortedBy")
    }

    // =========================================================================
    // Factory
    // =========================================================================

    @Test
    fun `strategy emitter factory returns correct types`() {
        assertTrue(CollectionSortStrategy.APOC_SORT_MAPS.emitter() is ApocSortMapsEmitter)
        assertTrue(CollectionSortStrategy.CALL_SUBQUERY.emitter() is CallSubqueryEmitter)
    }
}