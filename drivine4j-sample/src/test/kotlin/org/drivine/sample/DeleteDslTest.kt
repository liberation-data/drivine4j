package org.drivine.sample

import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.manager.delete
import org.drivine.manager.deleteAll
import org.drivine.manager.load
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.sample.fragment.IssueCore
import org.drivine.sample.fragment.Person
import org.drivine.sample.view.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for delete operations using the generated DSL.
 *
 * Demonstrates type-safe delete with where clause filtering.
 */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class DeleteDslTest @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    private lateinit var openIssue1Uuid: UUID
    private lateinit var openIssue2Uuid: UUID
    private lateinit var closedIssueUuid: UUID
    private lateinit var personUuid: UUID
    private lateinit var orgUuid: UUID

    @BeforeEach
    fun setupTestData() {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'delete-dsl-test' DETACH DELETE n")
        )

        openIssue1Uuid = UUID.randomUUID()
        openIssue2Uuid = UUID.randomUUID()
        closedIssueUuid = UUID.randomUUID()
        personUuid = UUID.randomUUID()
        orgUuid = UUID.randomUUID()

        // Create test data with various states
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (i1:Issue {
                        uuid: '$openIssue1Uuid',
                        id: 1,
                        title: 'Open Issue 1',
                        body: 'First open issue',
                        state: 'open',
                        locked: false,
                        createdBy: 'delete-dsl-test'
                    })
                    CREATE (i2:Issue {
                        uuid: '$openIssue2Uuid',
                        id: 2,
                        title: 'Open Issue 2',
                        body: 'Second open issue',
                        state: 'open',
                        locked: true,
                        createdBy: 'delete-dsl-test'
                    })
                    CREATE (i3:Issue {
                        uuid: '$closedIssueUuid',
                        id: 3,
                        title: 'Closed Issue',
                        body: 'A closed issue',
                        state: 'closed',
                        locked: false,
                        createdBy: 'delete-dsl-test'
                    })
                    CREATE (p:Person:Mapped {
                        uuid: '$personUuid',
                        name: 'Test Person',
                        bio: 'Test bio',
                        createdBy: 'delete-dsl-test'
                    })
                    CREATE (o:Organization:Mapped {
                        uuid: '$orgUuid',
                        name: 'Test Org',
                        createdBy: 'delete-dsl-test'
                    })
                    CREATE (i1)-[:ASSIGNED_TO]->(p)
                    CREATE (i1)-[:RAISED_BY]->(p)
                    CREATE (i2)-[:ASSIGNED_TO]->(p)
                    CREATE (i2)-[:RAISED_BY]->(p)
                    CREATE (i3)-[:ASSIGNED_TO]->(p)
                    CREATE (i3)-[:RAISED_BY]->(p)
                    CREATE (p)-[:WORKS_FOR]->(o)
                """.trimIndent())
        )
    }

    @Test
    fun `deleteAll with DSL filter by state`() {
        // Verify we have 3 issues
        val beforeCount = graphObjectManager.loadAll<RaisedAndAssignedIssue> { }.size
        assertEquals(3, beforeCount)

        // Delete only closed issues using DSL
        val deleted = graphObjectManager.deleteAll<RaisedAndAssignedIssue> {
            where {
                query.issue.state eq "closed"
            }
        }
        assertEquals(1, deleted)

        // Verify only open issues remain
        val remaining = graphObjectManager.loadAll<RaisedAndAssignedIssue> { }
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.issue.state == "open" })
    }

    @Test
    fun `deleteAll with DSL filter by multiple conditions`() {
        // Delete open AND locked issues
        val deleted = graphObjectManager.deleteAll<RaisedAndAssignedIssue> {
            where {
                query.issue.state eq "open"
                query.issue.locked eq true
            }
        }
        assertEquals(1, deleted)

        // Verify the correct issues remain
        val remaining = graphObjectManager.loadAll<RaisedAndAssignedIssue> { }
        assertEquals(2, remaining.size)

        // Should have one open (unlocked) and one closed
        val states = remaining.mapNotNull { it.issue.state }.sorted()
        assertEquals(listOf("closed", "open"), states)
    }

    @Test
    fun `deleteAll with DSL filter by title contains`() {
        // Delete issues with "Open" in title
        val deleted = graphObjectManager.deleteAll<RaisedAndAssignedIssue> {
            where {
                query.issue.title.contains("Open")
            }
        }
        assertEquals(2, deleted)

        // Only the closed issue should remain
        val remaining = graphObjectManager.loadAll<RaisedAndAssignedIssue> { }
        assertEquals(1, remaining.size)
        assertEquals("Closed Issue", remaining[0].issue.title)
    }

    @Test
    fun `deleteAll with DSL filter by relationship property`() {
        // Delete issues assigned to "Test Person"
        val deleted = graphObjectManager.deleteAll<RaisedAndAssignedIssue> {
            where {
                query.assignedTo.name eq "Test Person"
            }
        }
        assertEquals(3, deleted)

        // All issues should be deleted
        val remaining = graphObjectManager.loadAll<RaisedAndAssignedIssue> { }
        assertTrue(remaining.isEmpty())

        // But the person should still exist
        val person = graphObjectManager.load<Person>(personUuid)
        assertEquals("Test Person", person?.name)
    }

    @Test
    fun `deleteAll with DSL filter by nested view property`() {
        // Delete issues raised by someone at "Test Org"
        val deleted = graphObjectManager.deleteAll<RaisedAndAssignedIssue> {
            where {
                query.raisedBy.worksFor.name eq "Test Org"
            }
        }
        assertEquals(3, deleted)

        val remaining = graphObjectManager.loadAll<RaisedAndAssignedIssue> { }
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `deleteAll with DSL empty where deletes all`() {
        // Delete all issues (no filter)
        val deleted = graphObjectManager.deleteAll<RaisedAndAssignedIssue> { }
        assertEquals(3, deleted)

        val remaining = graphObjectManager.loadAll<RaisedAndAssignedIssue> { }
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `deleteAll with DSL no matches returns zero`() {
        // Try to delete with non-matching filter
        val deleted = graphObjectManager.deleteAll<RaisedAndAssignedIssue> {
            where {
                query.issue.state eq "nonexistent"
            }
        }
        assertEquals(0, deleted)

        // All issues should still exist
        val remaining = graphObjectManager.loadAll<RaisedAndAssignedIssue> { }
        assertEquals(3, remaining.size)
    }

    @Test
    fun `delete by UUID removes single issue`() {
        // Delete single issue by UUID
        val deleted = graphObjectManager.delete<RaisedAndAssignedIssue>(openIssue1Uuid)
        assertEquals(1, deleted)

        // Verify it's gone
        assertNull(graphObjectManager.load<RaisedAndAssignedIssue>(openIssue1Uuid))

        // Other issues should remain
        val remaining = graphObjectManager.loadAll<RaisedAndAssignedIssue> { }
        assertEquals(2, remaining.size)
    }

    @Test
    fun `delete by UUID with where clause condition`() {
        // Try to delete open issue with "closed" condition - should fail
        val deleted1 = graphObjectManager.delete<RaisedAndAssignedIssue>(
            openIssue1Uuid,
            "issue.state = 'closed'"
        )
        assertEquals(0, deleted1)

        // Issue should still exist
        val stillExists = graphObjectManager.load<RaisedAndAssignedIssue>(openIssue1Uuid)
        assertEquals("open", stillExists?.issue?.state)

        // Now delete with matching condition
        val deleted2 = graphObjectManager.delete<RaisedAndAssignedIssue>(
            openIssue1Uuid,
            "issue.state = 'open'"
        )
        assertEquals(1, deleted2)
    }

    @Test
    fun `delete preserves related nodes`() {
        // Delete all issues
        graphObjectManager.deleteAll<RaisedAndAssignedIssue> { }

        // Person and Organization should still exist
        val person = graphObjectManager.load<Person>(personUuid)
        assertEquals("Test Person", person?.name)

        // PersonContext view should still work
        val personContexts = graphObjectManager.loadAll<PersonContext> { }
        assertEquals(1, personContexts.size)
        assertEquals("Test Person", personContexts[0].person.name)
        assertEquals("Test Org", personContexts[0].worksFor[0].name)
    }
}