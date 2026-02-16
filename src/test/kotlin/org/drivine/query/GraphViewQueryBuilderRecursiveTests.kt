package org.drivine.query

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.junit.jupiter.api.Test
import sample.mapped.fragment.Location
import sample.mapped.fragment.Organization
import sample.mapped.fragment.Person
import sample.mapped.view.LocationHierarchy
import sample.mapped.view.OrgPersonView
import sample.mapped.view.PersonOrgView
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphViewQueryBuilderRecursiveTests {

    // =========================================================================
    // DIRECT SELF-REFERENCE TESTS (Location → Location)
    // =========================================================================

    @Test
    fun `should generate 3-level nested pattern comprehensions for LocationHierarchy`() {
        val builder = GraphViewQueryBuilder.forView(LocationHierarchy::class)
        val query = builder.buildQuery()

        println("Generated query for LocationHierarchy (maxDepth=3):")
        println(query)

        // Verify MATCH clause
        assertTrue(query.contains("MATCH (location:Location)"))

        // Verify 3 depth levels with unique aliases
        assertTrue(query.contains("subLocations_d1:Location"), "Should have depth 1 alias")
        assertTrue(query.contains("subLocations_d2:Location"), "Should have depth 2 alias")
        assertTrue(query.contains("subLocations_d3:Location"), "Should have depth 3 alias")

        // Verify field projections at each depth
        assertTrue(query.contains("name: subLocations_d1.name"))
        assertTrue(query.contains("name: subLocations_d2.name"))
        assertTrue(query.contains("name: subLocations_d3.name"))

        // Verify terminal level has empty list
        assertTrue(query.contains("subLocations: []"), "Terminal depth should have subLocations: []")

        // Verify RETURN structure
        assertTrue(query.contains("RETURN {"))
        assertTrue(query.contains("location: location"))
        assertTrue(query.contains("subLocations: subLocations"))
    }

    @Test
    fun `should have root fragment projected as nested object at each depth`() {
        val builder = GraphViewQueryBuilder.forView(LocationHierarchy::class)
        val query = builder.buildQuery()

        // Each depth level should have the root fragment as a nested object
        // (location: { ... }) pattern at each depth
        assertTrue(query.contains(Regex("""location:\s*\{""")), "Should project location fragment at depth levels")

        // Verify uuid, name, type fields at depth levels
        assertTrue(query.contains("uuid: subLocations_d1.uuid"))
        assertTrue(query.contains("type: subLocations_d1.type"))
    }

    @Test
    fun `should generate exactly one level for maxDepth 1`() {
        // LocationHierarchyShallow has maxDepth=1
        val builder = GraphViewQueryBuilder.forView(LocationHierarchyShallow::class)
        val query = builder.buildQuery()

        println("Generated query for LocationHierarchyShallow (maxDepth=1):")
        println(query)

        // Should have only depth 1
        assertTrue(query.contains("subLocations_d1:Location"), "Should have depth 1 alias")

        // Should NOT have depth 2
        assertFalse(query.contains("subLocations_d2"), "Should NOT have depth 2 alias")

        // Terminal level at depth 1
        assertTrue(query.contains("subLocations: []"), "Depth 1 should terminate with []")
    }

    @Test
    fun `should produce empty list for maxDepth 0`() {
        val builder = GraphViewQueryBuilder.forView(LocationHierarchy::class)
        val query = builder.buildQuery(null, null, emptyList(), mapOf("subLocations" to 0))

        println("Generated query with maxDepth=0 override:")
        println(query)

        // Should be just [] AS subLocations
        assertTrue(query.contains("[] AS subLocations"), "maxDepth=0 should produce [] AS subLocations")
        assertFalse(query.contains("subLocations_d1"), "maxDepth=0 should not expand at all")
    }

    @Test
    fun `should override annotation maxDepth with query-time depth`() {
        val builder = GraphViewQueryBuilder.forView(LocationHierarchy::class)
        // Annotation says maxDepth=3, override to 2
        val query = builder.buildQuery(null, null, emptyList(), mapOf("subLocations" to 2))

        println("Generated query with depth override to 2:")
        println(query)

        // Should have depth 1 and 2 but NOT 3
        assertTrue(query.contains("subLocations_d1:Location"), "Should have depth 1")
        assertTrue(query.contains("subLocations_d2:Location"), "Should have depth 2")
        assertFalse(query.contains("subLocations_d3"), "Should NOT have depth 3 (overridden)")
    }

    @Test
    fun `should combine WHERE clause with recursive patterns`() {
        val builder = GraphViewQueryBuilder.forView(LocationHierarchy::class)
        val query = builder.buildQuery("location.type = \$type")

        println("Generated query with WHERE clause:")
        println(query)

        assertTrue(query.contains("WHERE location.type = \$type"))
        assertTrue(query.contains("subLocations_d1:Location"))
    }

    @Test
    fun `should handle recursive relationship with non-recursive siblings`() {
        // LocationWithLandmarks has both a recursive subLocations and a non-recursive landmarks
        val builder = GraphViewQueryBuilder.forView(LocationWithLandmarks::class)
        val query = builder.buildQuery()

        println("Generated query for LocationWithLandmarks:")
        println(query)

        // Should have recursive subLocations
        assertTrue(query.contains("subLocations_d1:Location"), "Should expand recursive subLocations")
        assertTrue(query.contains("subLocations: []"), "Should terminate recursive at max depth")

        // Should also have non-recursive landmarks at each depth level
        assertTrue(query.contains("landmarks:Organization"), "Should project non-recursive landmarks")
    }

    @Test
    fun `should not produce trailing commas in recursive projections`() {
        val builder = GraphViewQueryBuilder.forView(LocationHierarchy::class)
        val query = builder.buildQuery()

        // Check for invalid trailing comma patterns
        val commaCloseBrace = Regex(""",\s*\}""")
        val match = commaCloseBrace.find(query)
        if (match != null) {
            println("Found invalid pattern ', }' at: ${match.value}")
        }
        assertTrue(match == null, "Query should not contain trailing commas before '}'")
    }

    // =========================================================================
    // CHAIN CYCLE TESTS (A → B → A)
    // =========================================================================

    @Test
    fun `should detect chain cycle and terminate with default maxDepth`() {
        val builder = GraphViewQueryBuilder.forView(PersonOrgView::class)
        val query = builder.buildQuery()

        println("Generated query for chain cycle PersonOrgView → OrgPersonView → PersonOrgView:")
        println(query)

        // PersonOrgView has person + employer: OrgPersonView?
        // OrgPersonView has org + employees: List<PersonOrgView> (maxDepth=2)
        // First traversal: PersonOrgView → OrgPersonView → PersonOrgView (count=2, maxDepth=2) → expand again
        // Second traversal: PersonOrgView → OrgPersonView → PersonOrgView (count=3, maxDepth=2) → terminate

        // Should contain the OrgPersonView nested projection
        assertTrue(query.contains("-[:WORKS_FOR]->"), "Should have WORKS_FOR relationship")
        assertTrue(query.contains("org:"), "Should project OrgPersonView's root fragment")

        // Should contain EMPLOYS relationship (from OrgPersonView to PersonOrgView)
        assertTrue(query.contains("-[:EMPLOYS]->"), "Should have EMPLOYS relationship in nested view")

        // The cycle should eventually terminate
        // Either with [] for collections or null for single values
        val hasTermination = query.contains("employees: []") || query.contains("employer: null")
        assertTrue(hasTermination, "Chain cycle should terminate with [] or null")
    }

    @Test
    fun `should support chain cycle with depth override`() {
        val builder = GraphViewQueryBuilder.forView(PersonOrgView::class)
        // Override the employees relationship to maxDepth=1 (single traversal)
        val query = builder.buildQuery(null, null, emptyList(), mapOf("employees" to 1))

        println("Generated query with chain cycle depth override to 1:")
        println(query)

        // With maxDepth=1 on employees, the chain should terminate sooner
        assertTrue(query.contains("-[:WORKS_FOR]->"), "Should still have WORKS_FOR")
    }

    // =========================================================================
    // BACKWARD COMPATIBILITY
    // =========================================================================

    @Test
    fun `should not affect non-recursive views`() {
        // RaisedAndAssignedIssue is non-recursive, should work exactly as before
        val builder = GraphViewQueryBuilder.forView(sample.mapped.view.RaisedAndAssignedIssue::class)
        val query = builder.buildQuery()

        // Verify existing behavior is preserved
        assertTrue(query.contains("MATCH (issue:Issue)"))
        assertTrue(query.contains("-[:ASSIGNED_TO]->"))
        assertTrue(query.contains("-[:RAISED_BY]->"))
        assertTrue(query.contains("RETURN {"))
    }
}

// =========================================================================
// TEST-ONLY VIEW MODELS
// =========================================================================

@GraphView
data class LocationHierarchyShallow(
    val location: Location,
    @GraphRelationship(type = "HAS_LOCATION", direction = Direction.OUTGOING, maxDepth = 1)
    val subLocations: List<LocationHierarchyShallow>
)

@GraphView
data class LocationWithLandmarks(
    val location: Location,
    @GraphRelationship(type = "HAS_LOCATION", direction = Direction.OUTGOING, maxDepth = 2)
    val subLocations: List<LocationWithLandmarks>,
    @GraphRelationship(type = "HAS_LANDMARK", direction = Direction.OUTGOING)
    val landmarks: List<Organization>
)