package org.drivine.manager

import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.mapped.fragment.Issue
import sample.mapped.fragment.IssueStateReason
import sample.mapped.fragment.Organization
import sample.mapped.fragment.Person
import sample.mapped.view.PersonContext
import sample.mapped.view.RaisedAndAssignedIssue
import sample.simple.TestAppContext
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class GraphViewSaveTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'graphview-save-test' DETACH DELETE n")
        )
    }

    @Test
    fun `should create new GraphView with relationships`() {
        // Create test data
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val assignee1Uuid = UUID.randomUUID()
        val orgUuid = UUID.randomUUID()

        val raiser = Person(
            uuid = raiserUuid,
            name = "Martin Fowler",
            bio = "Refactoring author"
        )

        val org = Organization(
            uuid = orgUuid,
            name = "ThoughtWorks"
        )

        val assignee1 = Person(
            uuid = assignee1Uuid,
            name = "Kent Beck",
            bio = "TDD pioneer"
        )

        val issue = Issue(
            uuid = issueUuid,
            id = 2001,
            title = "Implement GraphView save",
            body = "Add support for saving GraphViews",
            state = "open",
            stateReason = IssueStateReason.REOPENED,
            locked = false
        )

        val raiserContext = PersonContext(
            person = raiser,
            worksFor = listOf(org)
        )

        val graphView = RaisedAndAssignedIssue(
            issue = issue,
            assignedTo = listOf(assignee1),
            raisedBy = raiserContext
        )

        // Save the GraphView
        graphObjectManager.save(graphView)

        // Verify it was saved
        val loaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(loaded)
        assertEquals("Implement GraphView save", loaded.issue.title)
        assertEquals(1, loaded.assignedTo.size)
        assertEquals("Kent Beck", loaded.assignedTo.first().name)
        assertEquals("Martin Fowler", loaded.raisedBy.person.name)
        assertEquals(1, loaded.raisedBy.worksFor.size)
        assertEquals("ThoughtWorks", loaded.raisedBy.worksFor.first().name)
    }

    @Test
    fun `should update GraphView and add relationship`() {
        // Create initial data with one assignee
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val assignee1Uuid = UUID.randomUUID()
        val assignee2Uuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue:Issue {
                        uuid: ${'$'}issueUuid,
                        id: 2002,
                        title: 'Test Issue',
                        body: 'Body text',
                        state: 'open',
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'graphview-save-test'
                    })
                    CREATE (raiser:Person:Mapped {
                        uuid: ${'$'}raiserUuid,
                        name: 'Ward Cunningham',
                        bio: 'Wiki inventor',
                        createdBy: 'graphview-save-test'
                    })
                    CREATE (assignee1:Person:Mapped {
                        uuid: ${'$'}assignee1Uuid,
                        name: 'Alice',
                        bio: 'Developer',
                        createdBy: 'graphview-save-test'
                    })
                    CREATE (issue)-[:RAISED_BY]->(raiser)
                    CREATE (issue)-[:ASSIGNED_TO]->(assignee1)
                """.trimIndent())
                .bind(mapOf(
                    "issueUuid" to issueUuid.toString(),
                    "raiserUuid" to raiserUuid.toString(),
                    "assignee1Uuid" to assignee1Uuid.toString()
                ))
        )

        // Load the GraphView (adds to session)
        val loaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(loaded)
        assertEquals(1, loaded.assignedTo.size)

        // Add a second assignee
        val assignee2 = Person(
            uuid = assignee2Uuid,
            name = "Bob",
            bio = "Tester"
        )

        val modified = loaded.copy(
            assignedTo = loaded.assignedTo + assignee2
        )

        // Save the updated GraphView
        graphObjectManager.save(modified)

        // Verify the second assignee was added
        val reloaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(reloaded)
        assertEquals(2, reloaded.assignedTo.size)
        assertTrue(reloaded.assignedTo.any { it.name == "Alice" })
        assertTrue(reloaded.assignedTo.any { it.name == "Bob" })
    }

    @Test
    fun `should update GraphView and remove relationship`() {
        // Create initial data with two assignees
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val assignee1Uuid = UUID.randomUUID()
        val assignee2Uuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue:Issue {
                        uuid: ${'$'}issueUuid,
                        id: 2003,
                        title: 'Test Issue',
                        body: 'Body text',
                        state: 'open',
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'graphview-save-test'
                    })
                    CREATE (raiser:Person:Mapped {
                        uuid: ${'$'}raiserUuid,
                        name: 'Ward Cunningham',
                        bio: 'Wiki inventor',
                        createdBy: 'graphview-save-test'
                    })
                    CREATE (assignee1:Person:Mapped {
                        uuid: ${'$'}assignee1Uuid,
                        name: 'Alice',
                        bio: 'Developer',
                        createdBy: 'graphview-save-test'
                    })
                    CREATE (assignee2:Person:Mapped {
                        uuid: ${'$'}assignee2Uuid,
                        name: 'Bob',
                        bio: 'Tester',
                        createdBy: 'graphview-save-test'
                    })
                    CREATE (issue)-[:RAISED_BY]->(raiser)
                    CREATE (issue)-[:ASSIGNED_TO]->(assignee1)
                    CREATE (issue)-[:ASSIGNED_TO]->(assignee2)
                """.trimIndent())
                .bind(mapOf(
                    "issueUuid" to issueUuid.toString(),
                    "raiserUuid" to raiserUuid.toString(),
                    "assignee1Uuid" to assignee1Uuid.toString(),
                    "assignee2Uuid" to assignee2Uuid.toString()
                ))
        )

        // Load the GraphView
        val loaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(loaded)
        assertEquals(2, loaded.assignedTo.size)

        // Remove Bob
        val modified = loaded.copy(
            assignedTo = loaded.assignedTo.filter { it.name == "Alice" }
        )

        // Save the updated GraphView
        graphObjectManager.save(modified)

        // Verify Bob was removed
        val reloaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(reloaded)
        assertEquals(1, reloaded.assignedTo.size)
        assertEquals("Alice", reloaded.assignedTo.first().name)

        // Verify Bob's Person node still exists (relationship only was deleted)
        val bobStillExists = persistenceManager.query(
            QuerySpecification
                .withStatement("MATCH (p:Person {uuid: \$uuid}) RETURN count(p)")
                .bind(mapOf("uuid" to assignee2Uuid.toString()))
                .transform<Int>()
        )
        assertEquals(1, bobStillExists.first())
    }

    @Test
    fun `should update root fragment with dirty fields only`() {
        // Create initial data
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue:Issue {
                        uuid: ${'$'}issueUuid,
                        id: 2004,
                        title: 'Original Title',
                        body: 'Original Body',
                        state: 'open',
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'graphview-save-test'
                    })
                    CREATE (raiser:Person:Mapped {
                        uuid: ${'$'}raiserUuid,
                        name: 'Ward Cunningham',
                        bio: 'Wiki inventor',
                        createdBy: 'graphview-save-test'
                    })
                    CREATE (issue)-[:RAISED_BY]->(raiser)
                """.trimIndent())
                .bind(mapOf(
                    "issueUuid" to issueUuid.toString(),
                    "raiserUuid" to raiserUuid.toString()
                ))
        )

        // Load the GraphView
        val loaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(loaded)

        // Modify only the title
        val modified = loaded.copy(
            issue = loaded.issue.copy(title = "Updated Title")
        )

        // Save
        graphObjectManager.save(modified)

        // Verify only title changed
        val reloaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(reloaded)
        assertEquals("Updated Title", reloaded.issue.title)
        assertEquals("Original Body", reloaded.issue.body) // Body unchanged
    }
}