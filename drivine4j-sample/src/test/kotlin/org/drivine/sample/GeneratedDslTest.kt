package org.drivine.sample

import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
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
                this(query.issue.state eq "open")
            }
        }

        assertEquals(1, results.size)
        assertEquals("open", results[0].issue.state)
    }

    @Test
    fun `filter by issue id`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                this(query.issue.id eq 123)
            }
        }

        assertEquals(1, results.size)
        assertEquals(123, results[0].issue.id)
    }

    @Test
    fun `filter by person name in nested view`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                this(query.raisedBy.person.name eq "Alice Developer")
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
                this(query.raisedBy.worksFor.name eq "Acme Corp")
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
                this(query.issue.state eq "open")
                this(query.issue.locked eq false)
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
                this(query.worksFor.name eq "Acme Corp")
            }
        }

        assertEquals(1, results.size)
        assertEquals("Alice Developer", results[0].person.name)
    }

    @Test
    fun `filter returns empty list when no matches`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                this(query.issue.state eq "closed")
            }
        }

        assertTrue(results.isEmpty())
    }

    @Test
    fun `filter by title contains specific text`() {
        val results = graphObjectManager.loadAll<RaisedAndAssignedIssue> {
            where {
                this(query.issue.title eq "Implement feature X")
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
                    this(query.issue.state eq "closed")
                    this(query.issue.locked eq true)
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
                    this(query.issue.state eq "open")
                    this(query.issue.state eq "closed")
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
                    this(query.assignedTo.name eq "Alice Developer")
                    this(query.assignedTo.name eq "Bob Developer")
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
                    this(query.raisedBy.person.name eq "Alice Developer")
                    this(query.raisedBy.person.name eq "Bob Developer")
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
                    this(query.raisedBy.worksFor.name eq "Acme Corp")
                    this(query.raisedBy.worksFor.name eq "Initech")
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
                this(query.issue.locked eq false)  // AND
                anyOf {  // OR
                    this(query.raisedBy.person.name eq "Alice Developer")
                    this(query.raisedBy.worksFor.name eq "Initech")
                }
            }
        }

        // Should return 1 result (locked=false AND (Alice OR Initech))
        // Alice exists, so this matches
        assertEquals(1, results.size)
        assertEquals(false, results[0].issue.locked)
        assertEquals("Alice Developer", results[0].raisedBy?.person?.name)
    }
}
