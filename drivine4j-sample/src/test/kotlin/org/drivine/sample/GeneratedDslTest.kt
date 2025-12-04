package org.drivine.sample

import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.anyOf
import org.drivine.query.dsl.query
import org.drivine.sample.view.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive test demonstrating the generated DSL with extension functions.
 *
 * Shows how easy it is to use the generated DSL - just import the view package
 * and the extension functions work automatically!
 */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class GeneratedDslTest @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'dsl-test' DETACH DELETE n")
        )

        val issueUuid = UUID.randomUUID()
        val personUuid = UUID.randomUUID()
        val orgUuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (i:Issue {
                        uuid: '$issueUuid',
                        id: 123,
                        title: 'Implement feature X',
                        body: 'This is a test issue',
                        state: 'open',
                        locked: false,
                        createdBy: 'dsl-test'
                    })
                    CREATE (p:Person:Mapped {
                        uuid: '$personUuid',
                        name: 'Alice Developer',
                        bio: 'Senior Engineer',
                        createdBy: 'dsl-test'
                    })
                    CREATE (i)-[:ASSIGNED_TO]->(p)
                    CREATE (i)-[:RAISED_BY]->(p)
                    CREATE (p)-[:WORKS_FOR]->(:Organization:Mapped {
                        uuid: '$orgUuid',
                        name: 'Acme Corp',
                        createdBy: 'dsl-test'
                    })
                """.trimIndent())
        )
    }

    @Test
    fun `load all issues with extension function - no filter`() {
        // Extension function works automatically! Just import the view package
        // Clean reified syntax: loadAll<Type> instead of loadAll(Type::class.java)
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> { }

        assertEquals(1, results.size)
        val issue = results[0]
        assertEquals("Implement feature X", issue.issue.title)
        assertEquals(1, issue.assignedTo.size)
        assertEquals("Alice Developer", issue.assignedTo[0].name)
    }

    @Test
    fun `filter by issue state using type-safe DSL`() {
        // The extension function provides type-safe query access with reified types
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.issue.state eq "open"
            }
        }

        assertEquals(1, results.size)
        assertEquals("open", results[0].issue.state)
    }

    @Test
    fun `filter by issue id`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.issue.id eq 123
            }
        }

        assertEquals(1, results.size)
        assertEquals(123, results[0].issue.id)
    }

    @Test
    fun `filter by person name in nested view`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.raisedBy.person.name eq "Alice Developer"
            }
        }

        assertEquals(1, results.size)
        assertNotNull(results[0].raisedBy)
        assertEquals("Alice Developer", results[0].raisedBy?.person?.name)
    }

    @Test
    fun `filter by organization name in deeply nested view`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.raisedBy.worksFor.name eq "Acme Corp"
            }
        }

        assertEquals(1, results.size)
        val worksFor = results[0].raisedBy?.worksFor
        assertNotNull(worksFor)
        assertTrue(worksFor.isNotEmpty())
        assertEquals("Acme Corp", worksFor[0].name)
    }

    @Test
    fun `combine multiple filters with AND`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.issue.state eq "open"
                query.issue.locked eq false
            }
        }

        assertEquals(1, results.size)
        assertEquals("open", results[0].issue.state)
        assertEquals(false, results[0].issue.locked)
    }

    @Test
    fun `load PersonContext with extension function`() {
        // Extension functions work for all @GraphView types!
        val results = graphObjectManager.loadAll<PersonContext> { }

        assertEquals(1, results.size)
        assertEquals("Alice Developer", results[0].person.name)
        assertEquals("Senior Engineer", results[0].person.bio)
        assertTrue(results[0].worksFor.isNotEmpty())
        assertEquals("Acme Corp", results[0].worksFor[0].name)
    }

    @Test
    fun `filter PersonContext by organization`() {
        val results = graphObjectManager.loadAll<PersonContext> {
            where {
                query.worksFor.name eq "Acme Corp"
            }
        }

        assertEquals(1, results.size)
        assertEquals("Alice Developer", results[0].person.name)
    }

    @Test
    fun `filter returns empty list when no matches`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.issue.state eq "closed"
            }
        }

        assertTrue(results.isEmpty())
    }

    @Test
    fun `filter by title contains specific text`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.issue.title eq "Implement feature X"
            }
        }

        assertEquals(1, results.size)
        assertEquals("Implement feature X", results[0].issue.title)
    }

    // ===== anyOf (OR) Tests =====

    @Test
    fun `filter by anyOf on root fragment properties`() {
        // Test OR conditions on the root fragment
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                anyOf {
                    query.issue.state eq "closed"
                    query.issue.locked eq true
                }
            }
        }

        // Should return empty since our test data has state="open" and locked=false
        assertTrue(results.isEmpty())
    }

    @Test
    fun `filter by anyOf matching one condition on root fragment`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                anyOf {
                    query.issue.state eq "open"
                    query.issue.state eq "closed"
                }
            }
        }

        // Should return 1 result (state is "open")
        assertEquals(1, results.size)
        assertEquals("open", results[0].issue.state)
    }

    @Test
    fun `filter by anyOf on relationship properties`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                anyOf {
                    query.assignedTo.name eq "Alice Developer"
                    query.assignedTo.name eq "Bob Developer"
                }
            }
        }

        // Should return 1 result (Alice Developer exists)
        assertEquals(1, results.size)
        assertEquals("Alice Developer", results[0].assignedTo[0].name)
    }

    @Test
    fun `filter by anyOf on nested view properties`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                anyOf {
                    query.raisedBy.person.name eq "Alice Developer"
                    query.raisedBy.person.name eq "Bob Developer"
                }
            }
        }

        // Should return 1 result (Alice Developer raised it)
        assertEquals(1, results.size)
        assertEquals("Alice Developer", results[0].raisedBy?.person?.name)
    }

    @Test
    fun `filter by anyOf on deeply nested relationships`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                anyOf {
                    query.raisedBy.worksFor.name eq "Acme Corp"
                    query.raisedBy.worksFor.name eq "Initech"
                }
            }
        }

        // Should return 1 result (Acme Corp exists)
        assertEquals(1, results.size)
        assertEquals("Acme Corp", results[0].raisedBy?.worksFor?.get(0)?.name)
    }

    @Test
    fun `combine AND and OR conditions with nested relationships`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.issue.locked eq false  // AND
                anyOf {  // OR
                    query.raisedBy.person.name eq "Alice Developer"
                    query.raisedBy.worksFor.name eq "Initech"
                }
            }
        }

        // Should return 1 result (locked=false AND (Alice OR Initech))
        // Alice exists, so this matches
        assertEquals(1, results.size)
        assertEquals(false, results[0].issue.locked)
        assertEquals("Alice Developer", results[0].raisedBy.person.name)
    }

    // ===== Creative & Edge Case Tests =====

    @Test
    fun `filter with CONTAINS on nested view property`() {
        // Real-world use case: search for people whose bio contains a keyword
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.raisedBy.person.bio.contains("Engineer")
            }
        }

        assertEquals(1, results.size)
        assertTrue(results[0].raisedBy.person.bio?.contains("Engineer") == true)
    }

    @Test
    fun `filter with STARTS_WITH on deeply nested organization`() {
        // Find issues raised by people from orgs starting with "Ac"
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.raisedBy.worksFor.name.startsWith("Ac")
            }
        }

        assertEquals(1, results.size)
        assertEquals("Acme Corp", results[0].raisedBy?.worksFor?.get(0)?.name)
    }

    @Test
    fun `complex triple OR with mixed root and nested properties`() {
        // Real-world: "Show me issues that are either locked, OR raised by Alice, OR from Acme Corp"
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                anyOf {
                    query.issue.locked eq true
                    query.raisedBy.person.name eq "Alice Developer"
                    query.raisedBy.worksFor.name eq "Acme Corp"
                }
            }
        }

        // Should match because Alice raised it
        assertEquals(1, results.size)
    }

    @Test
    fun `nested OR within AND - complex boolean logic`() {
        // Real-world: "Show open issues that are either high priority OR assigned to Alice"
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.issue.state eq "open"  // Must be open
                anyOf {  // AND one of these
                    query.issue.id eq 123
                    query.assignedTo.name eq "Alice Developer"
                }
            }
        }

        assertEquals(1, results.size)
        assertEquals("open", results[0].issue.state)
    }

    @Test
    fun `filter by title CONTAINS with AND condition`() {
        // Real-world: Search issues by title keyword + status
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.issue.title.contains("feature")
                query.issue.state eq "open"
            }
        }

        assertEquals(1, results.size)
        assertTrue(results[0].issue.title?.contains("feature") == true)
    }

    @Test
    fun `multiple OR conditions on same nested relationship`() {
        // Edge case: Multiple OR conditions all targeting the same deeply nested property
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                anyOf {
                    query.raisedBy.worksFor.name eq "Acme Corp"
                    query.raisedBy.worksFor.name eq "Initech"
                    query.raisedBy.worksFor.name eq "Umbrella Corp"
                }
            }
        }

        // Should match Acme Corp
        assertEquals(1, results.size)
        assertEquals("Acme Corp", results[0].raisedBy?.worksFor?.get(0)?.name)
    }

    @Test
    fun `OR across different relationships`() {
        // Creative: "Show issues where EITHER assignedTo is Alice OR raisedBy is Alice"
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                anyOf {
                    query.assignedTo.name eq "Alice Developer"
                    query.raisedBy.person.name eq "Alice Developer"
                }
            }
        }

        // Both are Alice in our test data
        assertEquals(1, results.size)
    }

    @Test
    fun `complex multi-level AND with OR at different nesting levels`() {
        // Real-world complex query:
        // "Show me open, unlocked issues raised by someone at Acme OR assigned to Alice"
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.issue.state eq "open"
                query.issue.locked eq false
                anyOf {
                    query.raisedBy.worksFor.name eq "Acme Corp"
                    query.assignedTo.name eq "Alice Developer"
                }
            }
        }

        assertEquals(1, results.size)
        assertEquals("open", results[0].issue.state)
        assertEquals(false, results[0].issue.locked)
    }

    @Test
    fun `filter PersonContext by person bio CONTAINS`() {
        // Test string operations on nested views directly
        val results = graphObjectManager.loadAll<PersonContext> {
            where {
                query.person.bio.contains("Senior")
            }
        }

        assertEquals(1, results.size)
        assertTrue(results[0].person.bio?.contains("Senior") == true)
    }

    @Test
    fun `combine multiple string operations in OR`() {
        // Creative: Search across multiple text fields
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                anyOf {
                    query.issue.title.contains("Implement")
                    query.issue.body.contains("test")
                    query.raisedBy.person.bio.contains("Developer")
                }
            }
        }

        // Should match on title containing "Implement"
        assertEquals(1, results.size)
    }

    @Test
    fun `edge case - OR with no matches returns empty`() {
        // Edge case: All OR conditions fail
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                anyOf {
                    query.issue.state eq "closed"
                    query.assignedTo.name eq "Nobody"
                    query.raisedBy.worksFor.name eq "NonExistent Corp"
                }
            }
        }

        assertTrue(results.isEmpty())
    }

    @Test
    fun `edge case - nested OR within OR`() {
        // Edge case: Can we nest OR within OR? (Should work with recursive handling)
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                anyOf { query.issue.state eq "open"
                    anyOf {
                        query.issue.locked eq true
                        query.assignedTo.name eq "Alice Developer"
                    }
                }
            }
        }

        // Should match because state is "open"
        assertEquals(1, results.size)
    }

    @Test
    fun `real world scenario - search and filter dashboard query`() {
        // Realistic dashboard query: "Show my open issues from my team"
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.issue.state eq "open"
                query.raisedBy.worksFor.name eq "Acme Corp"
                anyOf {
                    query.assignedTo.name eq "Alice Developer"
                    query.issue.title.contains("feature")
                }
            }
        }

        assertEquals(1, results.size)
        assertEquals("Acme Corp", results[0].raisedBy?.worksFor?.get(0)?.name)
    }

    @Test
    fun `edge case - multiple AND conditions on same nested property`() {
        // Edge case: Can we AND multiple conditions on same nested property?
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                query.raisedBy.person.name eq "Alice Developer"
                query.raisedBy.person.bio.contains("Engineer")
            }
        }

        // Should work - both conditions on raisedBy.person
        assertEquals(1, results.size)
    }
}
