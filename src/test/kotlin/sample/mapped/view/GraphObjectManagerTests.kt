package sample.mapped.view

import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
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
import sample.simple.TestAppContext
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class GraphObjectManagerTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'graphview-test' DETACH DELETE n")
        )

        // Create test data: Issue -> RAISED_BY -> Person -> WORKS_FOR -> Organization
        //                   Issue -> ASSIGNED_TO -> Person

        val raiserUuid = UUID.randomUUID()
        val assignee1Uuid = UUID.randomUUID()
        val assignee2Uuid = UUID.randomUUID()
        val orgUuid = UUID.randomUUID()
        val issueUuid = UUID.randomUUID()

        val query = """
            CREATE (raiser:Person:Mapped {
                uuid: ${'$'}raiserUuid,
                name: 'Rod Johnson',
                bio: 'Creator of Spring Framework',
                createdBy: 'graphview-test'
            })
            CREATE (org:Organization {
                uuid: ${'$'}orgUuid,
                name: 'Pivotal',
                createdBy: 'graphview-test'
            })
            CREATE (assignee1:Person:Mapped {
                uuid: ${'$'}assignee1Uuid,
                name: 'Jasper Blues',
                bio: 'Drivine maintainer',
                createdBy: 'graphview-test'
            })
            CREATE (assignee2:Person:Mapped {
                uuid: ${'$'}assignee2Uuid,
                name: 'Alice Smith',
                bio: 'Senior Developer',
                createdBy: 'graphview-test'
            })
            CREATE (issue:Issue {
                uuid: ${'$'}issueUuid,
                id: 1001,
                title: 'Implement GraphView support',
                body: 'Add support for loading GraphViews from Neo4j',
                state: 'open',
                stateReason: 'REOPENED',
                locked: false,
                createdBy: 'graphview-test'
            })
            CREATE (raiser)-[:WORKS_FOR]->(org)
            CREATE (issue)-[:RAISED_BY]->(raiser)
            CREATE (issue)-[:ASSIGNED_TO]->(assignee1)
            CREATE (issue)-[:ASSIGNED_TO]->(assignee2)
        """.trimIndent()

        persistenceManager.execute(
            QuerySpecification
                .withStatement(query)
                .bind(
                    mapOf(
                        "raiserUuid" to raiserUuid.toString(),
                        "assignee1Uuid" to assignee1Uuid.toString(),
                        "assignee2Uuid" to assignee2Uuid.toString(),
                        "orgUuid" to orgUuid.toString(),
                        "issueUuid" to issueUuid.toString()
                    )
                )
        )
    }

    @Test
    fun `should load all RaisedAndAssignedIssue instances`() {
        val results = graphObjectManager.loadAll(RaisedAndAssignedIssue::class.java)

        println("Loaded ${results.size} RaisedAndAssignedIssue instances")
        results.forEach { issue ->
            println("Issue: ${issue.issue.title}")
            println("  Raised by: ${issue.raisedBy.person.name}")
            println("  Works for: ${issue.raisedBy.worksFor.map { it.name }}")
            println("  Assigned to: ${issue.assignedTo.map { it.name }}")
        }

        assertTrue(results.isNotEmpty())
        val issue = results.first()

        // Verify issue properties
        assertNotNull(issue.issue)
        assertEquals("Implement GraphView support", issue.issue.title)
        assertEquals("Add support for loading GraphViews from Neo4j", issue.issue.body)
        assertEquals("open", issue.issue.state)
        assertEquals(IssueStateReason.REOPENED, issue.issue.stateReason)
        assertEquals(false, issue.issue.locked)

        // Verify raisedBy (nested GraphView)
        assertNotNull(issue.raisedBy)
        assertEquals("Rod Johnson", issue.raisedBy.person.name)
        assertEquals("Creator of Spring Framework", issue.raisedBy.person.bio)

        // Verify raisedBy worksFor relationship
        assertEquals(1, issue.raisedBy.worksFor.size)
        assertEquals("Pivotal", issue.raisedBy.worksFor.first().name)

        // Verify assignedTo (collection)
        assertEquals(2, issue.assignedTo.size)
        assertTrue(issue.assignedTo.any { it.name == "Jasper Blues" })
        assertTrue(issue.assignedTo.any { it.name == "Alice Smith" })
    }

    @Test
    fun `should load RaisedAndAssignedIssue by id`() {
        // First get all to find the UUID
        val all = graphObjectManager.loadAll(RaisedAndAssignedIssue::class.java)
        assertTrue(all.isNotEmpty())
        val expectedIssue = all.first()

        // Now load by ID
        val loaded = graphObjectManager.load(expectedIssue.issue.uuid.toString(), RaisedAndAssignedIssue::class.java)

        assertNotNull(loaded)
        assertEquals(expectedIssue.issue.uuid, loaded.issue.uuid)
        assertEquals(expectedIssue.issue.title, loaded.issue.title)
        assertEquals(expectedIssue.raisedBy.person.name, loaded.raisedBy.person.name)
        assertEquals(expectedIssue.assignedTo.size, loaded.assignedTo.size)

        println("Successfully loaded issue by ID: ${loaded.issue.title}")
    }

    @Test
    fun `should return null when loading non-existent id`() {
        val nonExistentId = UUID.randomUUID().toString()
        val result = graphObjectManager.load(nonExistentId, RaisedAndAssignedIssue::class.java)

        assertNull(result)
        println("Correctly returned null for non-existent ID")
    }

    @Test
    fun `should load PersonContext GraphView`() {
        val results = graphObjectManager.loadAll(PersonContext::class.java)

        println("Loaded ${results.size} PersonContext instances")
        results.forEach { ctx ->
            println("Person: ${ctx.person.name}")
            println("  Works for: ${ctx.worksFor.map { it.name }}")
        }

        assertTrue(results.isNotEmpty())

        // Find Rod Johnson
        val rod = results.find { it.person.name == "Rod Johnson" }
        assertNotNull(rod)
        assertEquals(1, rod.worksFor.size)
        assertEquals("Pivotal", rod.worksFor.first().name)
    }
}