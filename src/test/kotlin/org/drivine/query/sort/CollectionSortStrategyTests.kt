package org.drivine.query.sort

import org.drivine.query.GraphViewQueryBuilder
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import sample.mapped.view.RaisedAndAssignedIssue
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CollectionSortStrategyTests {

    // =========================================================================
    // NEO4J_5 dialect — APOC sort + EXISTS { } checks
    // =========================================================================

    @Test
    fun `NEO4J_5 - top-level sort emits apoc coll sortMaps`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CypherDialect.NEO4J_5.grammar())
        val sort = CollectionSortSpec("assignedTo", "name", ascending = true)
        val query = builder.buildQuery(null, null, listOf(sort))

        assertContains(query, "apoc.coll.sortMaps")
        assertContains(query, "reverse(")
        assertFalse(query.contains("CALL {"))
    }

    @Test
    fun `NEO4J_5 - nested sort emits apoc coll sortMaps`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CypherDialect.NEO4J_5.grammar())
        val sort = CollectionSortSpec("raisedBy_worksFor", "name", ascending = true)
        val query = builder.buildQuery(null, null, listOf(sort))

        assertContains(query, "apoc.coll.sortMaps")
    }

    @Test
    fun `NEO4J_5 - descending sort omits reverse`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CypherDialect.NEO4J_5.grammar())
        val sort = CollectionSortSpec("assignedTo", "name", ascending = false)
        val query = builder.buildQuery(null, null, listOf(sort))

        assertContains(query, "apoc.coll.sortMaps")
        assertFalse(query.contains("reverse("))
    }

    @Test
    fun `NEO4J_5 - existence check uses EXISTS subquery`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CypherDialect.NEO4J_5.grammar())
        val query = builder.buildQuery()

        assertContains(query, "EXISTS {")
    }

    // =========================================================================
    // OPEN_CYPHER dialect — CALL subquery sort + inline pattern predicate
    // =========================================================================

    @Test
    fun `OPEN_CYPHER - top-level sort emits CALL subquery prolog`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CypherDialect.OPEN_CYPHER.grammar())
        val sort = CollectionSortSpec("assignedTo", "name", ascending = true)
        val query = builder.buildQuery(null, null, listOf(sort))

        println("OPEN_CYPHER top-level:\n$query\n")
        assertContains(query, "CALL {")
        assertContains(query, "ORDER BY")
        assertContains(query, "collect(")
        assertFalse(query.contains("apoc.coll.sortMaps"))
    }

    @Test
    fun `OPEN_CYPHER - descending sort emits DESC`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CypherDialect.OPEN_CYPHER.grammar())
        val sort = CollectionSortSpec("assignedTo", "name", ascending = false)
        val query = builder.buildQuery(null, null, listOf(sort))

        assertContains(query, "CALL {")
        assertContains(query, "DESC")
    }

    @Test
    fun `OPEN_CYPHER - prolog appears before WITH clause`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CypherDialect.OPEN_CYPHER.grammar())
        val sort = CollectionSortSpec("assignedTo", "name", ascending = true)
        val query = builder.buildQuery(null, null, listOf(sort))

        val callIdx = query.indexOf("CALL {")
        val withIdx = query.indexOf("\nWITH\n")
        assertTrue(callIdx > 0, "CALL { should be present")
        assertTrue(withIdx > 0, "WITH should be present")
        assertTrue(callIdx < withIdx, "CALL { should appear before WITH")
    }

    @Test
    fun `OPEN_CYPHER - nested views use CALL prologs`() {
        // RaisedAndAssignedIssue has raisedBy: PersonContext (a nested GraphView)
        // On OPEN_CYPHER, this should emit a CALL prolog instead of nested pattern comprehensions
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CypherDialect.OPEN_CYPHER.grammar())
        val query = builder.buildQuery(null, null, emptyList())

        assertContains(query, "CALL {")
        assertContains(query, "OPTIONAL MATCH")
    }

    @Test
    fun `OPEN_CYPHER - nested sort works via CALL prolog`() {
        // Nested sorts now work on openCypher because the CallSubqueryNestedViewProjector
        // lifts the nested view out of inline comprehensions, so the sort applies naturally
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CypherDialect.OPEN_CYPHER.grammar())
        val sort = CollectionSortSpec("raisedBy_worksFor", "name", ascending = true)
        val query = builder.buildQuery(null, null, listOf(sort))

        println("OPEN_CYPHER nested sort:\n$query\n")
        assertContains(query, "CALL {")
        assertFalse(query.contains("apoc.coll.sortMaps"))
    }

    @Test
    fun `OPEN_CYPHER - existence check uses inline pattern predicate`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class, CypherDialect.OPEN_CYPHER.grammar())
        val query = builder.buildQuery()

        assertFalse(query.contains("EXISTS {"))
        // Should have inline pattern: (issue)-[:RAISED_BY]->(:Person:Mapped)
        assertContains(query, "(issue)-[:RAISED_BY]->(:Person:Mapped)")
    }

    // =========================================================================
    // Dialect defaults
    // =========================================================================

    @Test
    fun `dialect grammar defaults are correct`() {
        assertTrue(CypherDialect.NEO4J_5.grammar().collectionSortEmitter is ApocSortMapsEmitter)
        assertTrue(CypherDialect.NEO4J_4.grammar().collectionSortEmitter is ApocSortMapsEmitter)
        assertTrue(CypherDialect.OPEN_CYPHER.grammar().collectionSortEmitter is CallSubqueryEmitter)
    }
}