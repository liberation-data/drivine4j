package org.drivine.query

import org.junit.jupiter.api.Test
import sample.mapped.view.ParentViewWithNestedList
import sample.mapped.view.RaisedAndAssignedIssue
import sample.mapped.view.TopLevelWithNestedGraphViews
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphViewQueryBuilderTests {

    @Test
    fun `should generate query for RaisedAndAssignedIssue`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class)
        val query = builder.buildQuery()

        println("Generated query:")
        println(query)

        // Verify key components are present
        assertTrue(query.contains("MATCH (issue:Issue)"))
        assertTrue(query.contains("WITH"))
        assertTrue(query.contains("-[:ASSIGNED_TO]->"))
        assertTrue(query.contains("-[:RAISED_BY]->"))
        assertTrue(query.contains("assignedTo:Person:Mapped"))
        assertTrue(query.contains("raisedBy:Person:Mapped")) // PersonContext's root is Person:Mapped

        // Verify explicit field mappings (field: var.field format)
        assertTrue(query.contains("body: issue.body"))
        assertTrue(query.contains("id: issue.id"))
        assertTrue(query.contains("locked: issue.locked"))
        assertTrue(query.contains("uuid: issue.uuid"))

        // Verify relationship fields
        assertTrue(query.contains("bio: assignedTo.bio"))
        assertTrue(query.contains("name: assignedTo.name"))

        // Verify nested GraphView (PersonContext) with its relationships
        assertTrue(query.contains("person:"))
        assertTrue(query.contains("bio: raisedBy.bio"))
        assertTrue(query.contains("worksFor:"))
        assertTrue(query.contains("-[:WORKS_FOR]->"))
        assertTrue(query.contains("worksFor:Organization"))

        // Verify comments
        assertTrue(query.contains("// Issue"))
        assertTrue(query.contains("// assignedTo"))
        assertTrue(query.contains("// raisedBy"))

        // Verify RETURN structure
        assertTrue(query.contains("RETURN {"))
        assertTrue(query.contains("issue: issue,"))
        assertTrue(query.contains("assignedTo: assignedTo,"))
        assertTrue(query.contains("raisedBy: raisedBy"))
        assertTrue(query.contains("} AS result"))
    }

    @Test
    fun `should generate query with WHERE clause`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class)
        val query = builder.buildQuery("issue.uuid = \$uuid")

        println("Generated query with WHERE:")
        println(query)

        assertTrue(query.contains("MATCH (issue:Issue)"))
        assertTrue(query.contains("WHERE issue.uuid = \$uuid"))
    }

    @Test
    fun `should handle collection relationships correctly`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class)
        val query = builder.buildQuery()

        // assignedTo is a List, so it should use pattern comprehension without [0]
        assertTrue(query.contains("[(issue)-[:ASSIGNED_TO]->(assignedTo:Person:Mapped)"))
        assertTrue(query.contains("] AS assignedTo"))
        assertTrue(!query.contains("][0] AS assignedTo"))
    }

    @Test
    fun `should handle single relationships correctly`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class)
        val query = builder.buildQuery()

        // raisedBy is a single PersonContext, so it should use [0] to get first element
        // PersonContext's root is Person:Mapped with both labels
        assertTrue(query.contains("[(issue)-[:RAISED_BY]->(raisedBy:Person:Mapped)"))
        assertTrue(query.contains("][0] AS raisedBy"))
    }

    @Test
    fun `should recursively handle nested GraphViews`() {
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class)
        val query = builder.buildQuery()

        println("Generated query for nested GraphView check:")
        println(query)

        // Verify PersonContext (a nested GraphView) includes its worksFor relationship
        assertTrue(query.contains("worksFor: ["))
        assertTrue(query.contains("(raisedBy)-[:WORKS_FOR]->(worksFor:Organization)"))
        assertTrue(query.contains("name: worksFor.name"))
        assertTrue(query.contains("uuid: worksFor.uuid"))
    }

    @Test
    fun `should not generate trailing commas in nested projections`() {
        // This test prevents regression of the trailing comma bug that occurred when
        // getFragmentFields() returned an empty list instead of null, causing
        // joinToString() to produce "" and leaving trailing commas in the Cypher
        val builder = GraphViewQueryBuilder.forView(RaisedAndAssignedIssue::class)
        val query = builder.buildQuery()

        println("Generated query for trailing comma check:")
        println(query)

        // Check for invalid trailing comma patterns in Cypher
        // Pattern 1: Opening brace followed immediately by comma (no content)
        // This catches: "{ ," or "{\n    ,"
        val openBraceComma = Regex("""\{\s*,""")
        val match1 = openBraceComma.find(query)
        if (match1 != null) {
            println("Found invalid pattern '{ ,' at: ${match1.value}")
        }
        assertTrue(match1 == null, "Query contains '{ ,' pattern indicating empty first field")

        // Pattern 2: Comma followed immediately by closing brace (trailing comma at end)
        // This catches: ", }" or ",\n    }"
        val commaCloseBrace = Regex(""",\s*\}""")
        val match2 = commaCloseBrace.find(query)
        if (match2 != null) {
            println("Found invalid pattern ', }' at: ${match2.value}")
        }
        assertTrue(match2 == null, "Query contains ', }' pattern indicating trailing comma")

        // Verify that nested object blocks have actual content
        // The person: block in PersonContext should have field mappings, not be empty
        assertTrue(
            query.contains(Regex("""person:\s*\{\s*\w+:""")),
            "Nested person block should contain field mappings"
        )

        // Verify the worksFor nested relationship has field content
        assertTrue(
            query.contains(Regex("""worksFor\s*\{\s*\w+:""")),
            "Nested worksFor block should contain field mappings"
        )
    }

    @Test
    fun `should use index 0 for single relationships inside nested GraphViews`() {
        // This test verifies that single-object relationships within nested GraphViews
        // use [0] to return an object instead of an array.
        // Bug: When loading a GraphView with List<NestedGraphView>, where NestedGraphView
        // has single relationships, those relationships were returned as arrays causing
        // deserialization errors like:
        // "Cannot deserialize value of type X from Array value (token JsonToken.START_ARRAY)"
        val builder = GraphViewQueryBuilder.forView(ParentViewWithNestedList::class)
        val query = builder.buildQuery()

        println("Generated query for nested single relationship check:")
        println(query)

        // The nested GraphView (NestedViewWithSingleRelationship) has a single
        // relationship: primaryEmployer: Organization?
        // This should use [0] to return an object, not an array

        // Verify the primaryEmployer pattern uses [0] for single-object access
        // Pattern should be: ][0] for single relationships inside nested views
        assertTrue(
            query.contains("primaryEmployer:") && query.contains(Regex("""\]\[0\]""")),
            "Single relationship 'primaryEmployer' inside nested GraphView should use [0] index"
        )

        // Also verify collection relationships DON'T use [0]
        // The outer assignees is a List, so it should NOT have [0]
        assertFalse(
            query.contains("][0] AS assignees"),
            "Collection relationship 'assignees' should NOT use [0] index"
        )
    }

    @Test
    fun `should correctly project deeply nested GraphViews with their Root fragments`() {
        // This test verifies that when a GraphView (Level 2) inside a List has a relationship
        // to another GraphView (Level 3), the Level 3 GraphView's @Root fragment is correctly
        // projected - not just .* but the full structure with person: { fields... }
        //
        // Bug: When buildNestedViewProjection processes relationships, it used getFragmentFields()
        // which fails for GraphView targets, falling back to .* which doesn't include the
        // GraphView's @Root structure. This causes the @Root property to be null during deserialization.
        val builder = GraphViewQueryBuilder.forView(TopLevelWithNestedGraphViews::class)
        val query = builder.buildQuery()

        println("Generated query for deeply nested GraphView check:")
        println(query)

        // Level 3 (DeeplyNestedView) has @Root val person: Person
        // When nested inside Level 2's raisedBy relationship, it should have:
        // raisedBy: { person: { uuid: ..., name: ..., bio: ... }, employer: [...] }
        // NOT just: raisedBy: { .*, labels: ... }

        // Verify that the Level 3 GraphView's @Root fragment (person) is explicitly projected
        // The pattern should include "person:" with field mappings, not just .*
        assertTrue(
            query.contains("raisedBy") && query.contains(Regex("""person:\s*\{""")),
            "Deeply nested GraphView (DeeplyNestedView) should have its @Root 'person' fragment explicitly projected"
        )

        // Verify the person fields are mapped (not just .*)
        // DeeplyNestedView.person is of type Person which has uuid, name, bio fields
        assertTrue(
            query.contains(Regex("""person:\s*\{\s*\w+:""")),
            "The @Root 'person' block should contain explicit field mappings, not just .*"
        )

        // Verify Level 3's relationship (employer) is also present
        assertTrue(
            query.contains("employer:"),
            "Deeply nested GraphView should also include its relationships (employer)"
        )
    }
}
