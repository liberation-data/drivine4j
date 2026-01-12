package org.drivine.query

import org.junit.jupiter.api.Test
import sample.mapped.view.RaisedAndAssignedIssue
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
}
