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
import sample.mapped.fragment.Person
import sample.mapped.view.RaisedAndAssignedIssue
import sample.simple.TestAppContext
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class DeleteTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'delete-test' DETACH DELETE n")
        )
    }

    // ==================== Fragment Delete Tests ====================

    @Test
    fun `delete by ID should remove fragment node`() {
        val uuid = UUID.randomUUID()
        createPerson(uuid, "Kent Beck", "TDD pioneer")

        // Verify it exists
        val loaded = graphObjectManager.load<Person>(uuid)
        assertEquals("Kent Beck", loaded?.name)

        // Delete it
        val deleted = graphObjectManager.delete<Person>(uuid)
        assertEquals(1, deleted)

        // Verify it's gone
        val afterDelete = graphObjectManager.load<Person>(uuid)
        assertNull(afterDelete)
    }

    @Test
    fun `delete by ID should return 0 when node does not exist`() {
        val nonExistentUuid = UUID.randomUUID()

        val deleted = graphObjectManager.delete<Person>(nonExistentUuid)
        assertEquals(0, deleted)
    }

    @Test
    fun `delete by ID with where clause should only delete matching node`() {
        val uuid = UUID.randomUUID()
        createIssue(uuid, 1001, "open", "Test Issue")

        // Try to delete with non-matching condition
        val deleted1 = graphObjectManager.delete<Issue>(uuid, "n.state = 'closed'")
        assertEquals(0, deleted1)

        // Verify it still exists
        val stillExists = graphObjectManager.load<Issue>(uuid)
        assertEquals("open", stillExists?.state)

        // Delete with matching condition
        val deleted2 = graphObjectManager.delete<Issue>(uuid, "n.state = 'open'")
        assertEquals(1, deleted2)

        // Verify it's gone
        val afterDelete = graphObjectManager.load<Issue>(uuid)
        assertNull(afterDelete)
    }

    @Test
    fun `deleteAll should remove all matching fragments`() {
        // Create multiple persons
        val uuid1 = UUID.randomUUID()
        val uuid2 = UUID.randomUUID()
        val uuid3 = UUID.randomUUID()
        createPerson(uuid1, "Person 1", "Bio 1")
        createPerson(uuid2, "Person 2", "Bio 2")
        createPerson(uuid3, "Person 3", "Bio 3")

        // Verify they exist
        val beforeCount = countPersons()
        assertEquals(3, beforeCount)

        // Delete all (with createdBy filter to not affect other tests)
        val deleted = graphObjectManager.deleteAll<Person>("n.createdBy = 'delete-test'")
        assertEquals(3, deleted)

        // Verify they're all gone
        val afterCount = countPersons()
        assertEquals(0, afterCount)
    }

    @Test
    fun `deleteAll with where clause should only delete matching fragments`() {
        // Create issues with different states
        val uuid1 = UUID.randomUUID()
        val uuid2 = UUID.randomUUID()
        val uuid3 = UUID.randomUUID()
        createIssue(uuid1, 2001, "open", "Open Issue 1")
        createIssue(uuid2, 2002, "closed", "Closed Issue")
        createIssue(uuid3, 2003, "open", "Open Issue 2")

        // Delete only closed issues
        val deleted = graphObjectManager.deleteAll<Issue>("n.state = 'closed' AND n.createdBy = 'delete-test'")
        assertEquals(1, deleted)

        // Verify open issues still exist
        val openIssue1 = graphObjectManager.load<Issue>(uuid1)
        val openIssue2 = graphObjectManager.load<Issue>(uuid3)
        assertEquals("open", openIssue1?.state)
        assertEquals("open", openIssue2?.state)

        // Verify closed issue is gone
        val closedIssue = graphObjectManager.load<Issue>(uuid2)
        assertNull(closedIssue)
    }

    // ==================== GraphView Delete Tests ====================

    @Test
    fun `delete by ID should remove GraphView root node and detach relationships`() {
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val assigneeUuid = UUID.randomUUID()
        createIssueWithRelationships(issueUuid, raiserUuid, assigneeUuid)

        // Verify it exists via view
        val loaded = graphObjectManager.load<RaisedAndAssignedIssue>(issueUuid)
        assertEquals("Test Issue", loaded?.issue?.title)

        // Delete the issue (root node)
        val deleted = graphObjectManager.delete<RaisedAndAssignedIssue>(issueUuid)
        assertEquals(1, deleted)

        // Verify issue is gone
        val afterDelete = graphObjectManager.load<RaisedAndAssignedIssue>(issueUuid)
        assertNull(afterDelete)

        // Verify related persons still exist (DETACH DELETE only removes relationships)
        val raiser = graphObjectManager.load<Person>(raiserUuid)
        val assignee = graphObjectManager.load<Person>(assigneeUuid)
        assertEquals("Martin Fowler", raiser?.name)
        assertEquals("Kent Beck", assignee?.name)
    }

    @Test
    fun `delete GraphView by ID with where clause should respect condition`() {
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val assigneeUuid = UUID.randomUUID()
        createIssueWithRelationships(issueUuid, raiserUuid, assigneeUuid, state = "open")

        // Try to delete with non-matching condition (using root fragment alias 'issue')
        val deleted1 = graphObjectManager.delete<RaisedAndAssignedIssue>(issueUuid, "issue.state = 'closed'")
        assertEquals(0, deleted1)

        // Verify it still exists
        val stillExists = graphObjectManager.load<RaisedAndAssignedIssue>(issueUuid)
        assertEquals("open", stillExists?.issue?.state)

        // Delete with matching condition
        val deleted2 = graphObjectManager.delete<RaisedAndAssignedIssue>(issueUuid, "issue.state = 'open'")
        assertEquals(1, deleted2)
    }

    @Test
    fun `deleteAll GraphView should remove all matching root nodes`() {
        // Create multiple issues with relationships
        val issue1Uuid = UUID.randomUUID()
        val issue2Uuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val assigneeUuid = UUID.randomUUID()

        // Create shared raiser and assignee
        createPerson(raiserUuid, "Shared Raiser", "Raiser bio")
        createPerson(assigneeUuid, "Shared Assignee", "Assignee bio")

        // Create two issues pointing to same people
        createIssueOnly(issue1Uuid, 3001, "open", "Issue 1")
        createIssueOnly(issue2Uuid, 3002, "open", "Issue 2")
        createRelationships(issue1Uuid, raiserUuid, assigneeUuid)
        createRelationships(issue2Uuid, raiserUuid, assigneeUuid)

        // Delete all issues (using view's root fragment alias)
        val deleted = graphObjectManager.deleteAll<RaisedAndAssignedIssue>("issue.createdBy = 'delete-test'")
        assertEquals(2, deleted)

        // Verify issues are gone
        assertNull(graphObjectManager.load<RaisedAndAssignedIssue>(issue1Uuid))
        assertNull(graphObjectManager.load<RaisedAndAssignedIssue>(issue2Uuid))

        // Verify persons still exist
        assertEquals("Shared Raiser", graphObjectManager.load<Person>(raiserUuid)?.name)
        assertEquals("Shared Assignee", graphObjectManager.load<Person>(assigneeUuid)?.name)
    }

    @Test
    fun `deleteAll GraphView with where clause should filter correctly`() {
        val openIssueUuid = UUID.randomUUID()
        val closedIssueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()

        createPerson(raiserUuid, "Raiser", "Bio")
        createIssueOnly(openIssueUuid, 4001, "open", "Open Issue")
        createIssueOnly(closedIssueUuid, 4002, "closed", "Closed Issue")
        createRelationshipsRaiserOnly(openIssueUuid, raiserUuid)
        createRelationshipsRaiserOnly(closedIssueUuid, raiserUuid)

        // Delete only closed issues
        val deleted = graphObjectManager.deleteAll<RaisedAndAssignedIssue>(
            "issue.state = 'closed' AND issue.createdBy = 'delete-test'"
        )
        assertEquals(1, deleted)

        // Verify open issue still exists
        val openIssue = graphObjectManager.load<Issue>(openIssueUuid)
        assertEquals("open", openIssue?.state)

        // Verify closed issue is gone
        assertNull(graphObjectManager.load<Issue>(closedIssueUuid))
    }

    // ==================== Helper Methods ====================

    private fun createPerson(uuid: UUID, name: String, bio: String) {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:Person:Mapped {
                        uuid: ${'$'}uuid,
                        name: ${'$'}name,
                        bio: ${'$'}bio,
                        createdBy: 'delete-test'
                    })
                """.trimIndent())
                .bind(mapOf(
                    "uuid" to uuid.toString(),
                    "name" to name,
                    "bio" to bio
                ))
        )
    }

    private fun createIssue(uuid: UUID, id: Long, state: String, title: String) {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (i:Issue {
                        uuid: ${'$'}uuid,
                        id: ${'$'}id,
                        state: ${'$'}state,
                        title: ${'$'}title,
                        body: 'Test body',
                        locked: false,
                        createdBy: 'delete-test'
                    })
                """.trimIndent())
                .bind(mapOf(
                    "uuid" to uuid.toString(),
                    "id" to id,
                    "state" to state,
                    "title" to title
                ))
        )
    }

    private fun createIssueOnly(uuid: UUID, id: Long, state: String, title: String) {
        createIssue(uuid, id, state, title)
    }

    private fun createIssueWithRelationships(
        issueUuid: UUID,
        raiserUuid: UUID,
        assigneeUuid: UUID,
        state: String = "open"
    ) {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue:Issue {
                        uuid: ${'$'}issueUuid,
                        id: 5001,
                        title: 'Test Issue',
                        body: 'Test body',
                        state: ${'$'}state,
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'delete-test'
                    })
                    CREATE (raiser:Person:Mapped {
                        uuid: ${'$'}raiserUuid,
                        name: 'Martin Fowler',
                        bio: 'Refactoring author',
                        createdBy: 'delete-test'
                    })
                    CREATE (assignee:Person:Mapped {
                        uuid: ${'$'}assigneeUuid,
                        name: 'Kent Beck',
                        bio: 'TDD pioneer',
                        createdBy: 'delete-test'
                    })
                    CREATE (issue)-[:RAISED_BY]->(raiser)
                    CREATE (raiser)-[:WORKS_FOR]->(:Organization:Mapped {uuid: randomUUID(), name: 'ThoughtWorks', createdBy: 'delete-test'})
                    CREATE (issue)-[:ASSIGNED_TO]->(assignee)
                """.trimIndent())
                .bind(mapOf(
                    "issueUuid" to issueUuid.toString(),
                    "raiserUuid" to raiserUuid.toString(),
                    "assigneeUuid" to assigneeUuid.toString(),
                    "state" to state
                ))
        )
    }

    private fun createRelationships(issueUuid: UUID, raiserUuid: UUID, assigneeUuid: UUID) {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {uuid: ${'$'}issueUuid})
                    MATCH (raiser:Person {uuid: ${'$'}raiserUuid})
                    MATCH (assignee:Person {uuid: ${'$'}assigneeUuid})
                    CREATE (issue)-[:RAISED_BY]->(raiser)
                    CREATE (raiser)-[:WORKS_FOR]->(:Organization:Mapped {uuid: randomUUID(), name: 'ThoughtWorks', createdBy: 'delete-test'})
                    CREATE (issue)-[:ASSIGNED_TO]->(assignee)
                """.trimIndent())
                .bind(mapOf(
                    "issueUuid" to issueUuid.toString(),
                    "raiserUuid" to raiserUuid.toString(),
                    "assigneeUuid" to assigneeUuid.toString()
                ))
        )
    }

    private fun createRelationshipsRaiserOnly(issueUuid: UUID, raiserUuid: UUID) {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {uuid: ${'$'}issueUuid})
                    MATCH (raiser:Person {uuid: ${'$'}raiserUuid})
                    CREATE (issue)-[:RAISED_BY]->(raiser)
                    CREATE (raiser)-[:WORKS_FOR]->(:Organization:Mapped {uuid: randomUUID(), name: 'Org', createdBy: 'delete-test'})
                """.trimIndent())
                .bind(mapOf(
                    "issueUuid" to issueUuid.toString(),
                    "raiserUuid" to raiserUuid.toString()
                ))
        )
    }

    private fun countPersons(): Int {
        return persistenceManager.getOne(
            QuerySpecification
                .withStatement("MATCH (p:Person) WHERE p.createdBy = 'delete-test' RETURN count(p)")
                .transform<Int>()
        )
    }
}