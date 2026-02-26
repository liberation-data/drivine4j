package org.drivine.manager

import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.mapped.view.RaisedAndAssignedIssue
import sample.simple.TestAppContext
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class CascadeDeleteTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'cascade-test' DETACH DELETE n")
        )
    }

    @Test
    fun `CASCADE NONE should only delete relationship, not fragment`() {
        // Create issue with one assignee
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val assigneeUuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue:Issue {
                        uuid: ${'$'}issueUuid,
                        id: 3001,
                        title: 'Test Issue',
                        body: 'Body text',
                        state: 'open',
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'cascade-test'
                    })
                    CREATE (raiser:Person:Mapped {
                        uuid: ${'$'}raiserUuid,
                        name: 'Martin Fowler',
                        bio: 'Refactoring author',
                        createdBy: 'cascade-test'
                    })
                    CREATE (assignee:Person:Mapped {
                        uuid: ${'$'}assigneeUuid,
                        name: 'Kent Beck',
                        bio: 'TDD pioneer',
                        createdBy: 'cascade-test'
                    })
                    CREATE (issue)-[:RAISED_BY]->(raiser)
                    CREATE (issue)-[:ASSIGNED_TO]->(assignee)
                """.trimIndent())
                .bind(mapOf(
                    "issueUuid" to issueUuid.toString(),
                    "raiserUuid" to raiserUuid.toString(),
                    "assigneeUuid" to assigneeUuid.toString()
                ))
        )

        // Load and remove assignee
        val loaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(loaded)
        assertEquals(1, loaded.assignedTo.size)

        val modified = loaded.copy(assignedTo = emptyList())

        // Save with CASCADE NONE (default)
        graphObjectManager.save(modified, CascadeType.NONE)

        // Verify relationship deleted but Person node still exists
        val reloaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(reloaded)
        assertEquals(0, reloaded.assignedTo.size)

        // Verify Person node still exists
        val personCount = persistenceManager.getOne(
            QuerySpecification
                .withStatement("MATCH (p:Person {uuid: \$uuid}) RETURN count(p)")
                .bind(mapOf("uuid" to assigneeUuid.toString()))
                .transform<Int>()
        )
        assertEquals(1, personCount)
    }

    @Test
    fun `CASCADE DELETE_ALL should delete fragment even with other relationships`() {
        // Create issue with one assignee
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val assigneeUuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue:Issue {
                        uuid: ${'$'}issueUuid,
                        id: 3002,
                        title: 'Test Issue',
                        body: 'Body text',
                        state: 'open',
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'cascade-test'
                    })
                    CREATE (raiser:Person:Mapped {
                        uuid: ${'$'}raiserUuid,
                        name: 'Martin Fowler',
                        bio: 'Refactoring author',
                        createdBy: 'cascade-test'
                    })
                    CREATE (assignee:Person:Mapped {
                        uuid: ${'$'}assigneeUuid,
                        name: 'Kent Beck',
                        bio: 'TDD pioneer',
                        createdBy: 'cascade-test'
                    })
                    CREATE (issue)-[:RAISED_BY]->(raiser)
                    CREATE (issue)-[:ASSIGNED_TO]->(assignee)
                """.trimIndent())
                .bind(mapOf(
                    "issueUuid" to issueUuid.toString(),
                    "raiserUuid" to raiserUuid.toString(),
                    "assigneeUuid" to assigneeUuid.toString()
                ))
        )

        // Load and remove assignee
        val loaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(loaded)
        assertEquals(1, loaded.assignedTo.size)

        val modified = loaded.copy(assignedTo = emptyList())

        // Save with CASCADE DELETE_ALL (nuclear option - DETACH DELETE)
        graphObjectManager.save(modified, CascadeType.DELETE_ALL)

        // Verify relationship deleted
        val reloaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(reloaded)
        assertEquals(0, reloaded.assignedTo.size)

        // Verify Person node ALSO DELETED (DETACH DELETE removes all relationships and node)
        val personCount = persistenceManager.getOne(
            QuerySpecification
                .withStatement("MATCH (p:Person {uuid: \$uuid}) RETURN count(p)")
                .bind(mapOf("uuid" to assigneeUuid.toString()))
                .transform<Int>()
        )
        assertEquals(0, personCount)
    }

    @Test
    fun `CASCADE DELETE_ALL should delete multiple fragments`() {
        // Create issue with TWO assignees
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val assignee1Uuid = UUID.randomUUID()
        val assignee2Uuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue:Issue {
                        uuid: ${'$'}issueUuid,
                        id: 3003,
                        title: 'Test Issue',
                        body: 'Body text',
                        state: 'open',
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'cascade-test'
                    })
                    CREATE (raiser:Person:Mapped {
                        uuid: ${'$'}raiserUuid,
                        name: 'Martin Fowler',
                        bio: 'Refactoring author',
                        createdBy: 'cascade-test'
                    })
                    CREATE (assignee1:Person:Mapped {
                        uuid: ${'$'}assignee1Uuid,
                        name: 'Kent Beck',
                        bio: 'TDD pioneer',
                        createdBy: 'cascade-test'
                    })
                    CREATE (assignee2:Person:Mapped {
                        uuid: ${'$'}assignee2Uuid,
                        name: 'Ward Cunningham',
                        bio: 'Wiki inventor',
                        createdBy: 'cascade-test'
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

        // Load and remove both assignees
        val loaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(loaded)
        assertEquals(2, loaded.assignedTo.size)

        val modified = loaded.copy(assignedTo = emptyList())

        // Save with CASCADE DELETE_ALL
        graphObjectManager.save(modified, CascadeType.DELETE_ALL)

        // Verify both Person nodes deleted
        val person1Count = persistenceManager.getOne(
            QuerySpecification
                .withStatement("MATCH (p:Person {uuid: \$uuid}) RETURN count(p)")
                .bind(mapOf("uuid" to assignee1Uuid.toString()))
                .transform<Int>()
        )
        assertEquals(0, person1Count)

        val person2Count = persistenceManager.getOne(
            QuerySpecification
                .withStatement("MATCH (p:Person {uuid: \$uuid}) RETURN count(p)")
                .bind(mapOf("uuid" to assignee2Uuid.toString()))
                .transform<Int>()
        )
        assertEquals(0, person2Count)
    }

    @Test
    fun `CASCADE DELETE_ORPHAN should delete fragment only if no other relationships exist`() {
        // Create issue with assignee who has NO other relationships
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val orphanAssigneeUuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue:Issue {
                        uuid: ${'$'}issueUuid,
                        id: 3004,
                        title: 'Test Issue',
                        body: 'Body text',
                        state: 'open',
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'cascade-test'
                    })
                    CREATE (raiser:Person:Mapped {
                        uuid: ${'$'}raiserUuid,
                        name: 'Martin Fowler',
                        bio: 'Refactoring author',
                        createdBy: 'cascade-test'
                    })
                    CREATE (assignee:Person:Mapped {
                        uuid: ${'$'}orphanAssigneeUuid,
                        name: 'Kent Beck',
                        bio: 'TDD pioneer',
                        createdBy: 'cascade-test'
                    })
                    CREATE (issue)-[:RAISED_BY]->(raiser)
                    CREATE (issue)-[:ASSIGNED_TO]->(assignee)
                """.trimIndent())
                .bind(mapOf(
                    "issueUuid" to issueUuid.toString(),
                    "raiserUuid" to raiserUuid.toString(),
                    "orphanAssigneeUuid" to orphanAssigneeUuid.toString()
                ))
        )

        // Load and remove assignee
        val loaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(loaded)
        assertEquals(1, loaded.assignedTo.size)

        val modified = loaded.copy(assignedTo = emptyList())

        // Save with CASCADE DELETE_ORPHAN (safe option)
        graphObjectManager.save(modified, CascadeType.DELETE_ORPHAN)

        // Verify relationship deleted
        val reloaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(reloaded)
        assertEquals(0, reloaded.assignedTo.size)

        // Verify Person node ALSO DELETED because it was orphaned
        val personCount = persistenceManager.getOne(
            QuerySpecification
                .withStatement("MATCH (p:Person {uuid: \$uuid}) RETURN count(p)")
                .bind(mapOf("uuid" to orphanAssigneeUuid.toString()))
                .transform<Int>()
        )
        assertEquals(0, personCount)
    }

    @Test
    fun `CASCADE DELETE_ORPHAN should NOT delete fragment if other relationships exist`() {
        // Create TWO issues sharing the same assignee
        val issue1Uuid = UUID.randomUUID()
        val issue2Uuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val sharedAssigneeUuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue1:Issue {
                        uuid: ${'$'}issue1Uuid,
                        id: 3005,
                        title: 'Test Issue 1',
                        body: 'Body text',
                        state: 'open',
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'cascade-test'
                    })
                    CREATE (issue2:Issue {
                        uuid: ${'$'}issue2Uuid,
                        id: 3006,
                        title: 'Test Issue 2',
                        body: 'Body text',
                        state: 'open',
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'cascade-test'
                    })
                    CREATE (raiser:Person:Mapped {
                        uuid: ${'$'}raiserUuid,
                        name: 'Martin Fowler',
                        bio: 'Refactoring author',
                        createdBy: 'cascade-test'
                    })
                    CREATE (assignee:Person:Mapped {
                        uuid: ${'$'}sharedAssigneeUuid,
                        name: 'Kent Beck',
                        bio: 'TDD pioneer',
                        createdBy: 'cascade-test'
                    })
                    CREATE (issue1)-[:RAISED_BY]->(raiser)
                    CREATE (issue2)-[:RAISED_BY]->(raiser)
                    CREATE (issue1)-[:ASSIGNED_TO]->(assignee)
                    CREATE (issue2)-[:ASSIGNED_TO]->(assignee)
                """.trimIndent())
                .bind(mapOf(
                    "issue1Uuid" to issue1Uuid.toString(),
                    "issue2Uuid" to issue2Uuid.toString(),
                    "raiserUuid" to raiserUuid.toString(),
                    "sharedAssigneeUuid" to sharedAssigneeUuid.toString()
                ))
        )

        // Load issue1 and remove assignee
        val loaded = graphObjectManager.load(issue1Uuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(loaded)
        assertEquals(1, loaded.assignedTo.size)

        val modified = loaded.copy(assignedTo = emptyList())

        // Save with CASCADE DELETE_ORPHAN (safe option)
        graphObjectManager.save(modified, CascadeType.DELETE_ORPHAN)

        // Verify assignee removed from issue1
        val reloaded = graphObjectManager.load(issue1Uuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(reloaded)
        assertEquals(0, reloaded.assignedTo.size)

        // Verify Person node STILL EXISTS because issue2 still references it (safe!)
        val personCount = persistenceManager.getOne(
            QuerySpecification
                .withStatement("MATCH (p:Person {uuid: \$uuid}) RETURN count(p)")
                .bind(mapOf("uuid" to sharedAssigneeUuid.toString()))
                .transform<Int>()
        )
        assertEquals(1, personCount)
    }

    @Test
    fun `CASCADE PRESERVE should skip removals and keep all existing relationships`() {
        // Create issue with two assignees
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val assignee1Uuid = UUID.randomUUID()
        val assignee2Uuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue:Issue {
                        uuid: ${'$'}issueUuid,
                        id: 3007,
                        title: 'Test Issue',
                        body: 'Body text',
                        state: 'open',
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'cascade-test'
                    })
                    CREATE (raiser:Person:Mapped {
                        uuid: ${'$'}raiserUuid,
                        name: 'Martin Fowler',
                        bio: 'Refactoring author',
                        createdBy: 'cascade-test'
                    })
                    CREATE (assignee1:Person:Mapped {
                        uuid: ${'$'}assignee1Uuid,
                        name: 'Kent Beck',
                        bio: 'TDD pioneer',
                        createdBy: 'cascade-test'
                    })
                    CREATE (assignee2:Person:Mapped {
                        uuid: ${'$'}assignee2Uuid,
                        name: 'Ward Cunningham',
                        bio: 'Wiki inventor',
                        createdBy: 'cascade-test'
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

        // Load and remove both assignees from the in-memory object
        val loaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(loaded)
        assertEquals(2, loaded.assignedTo.size)

        val modified = loaded.copy(assignedTo = emptyList())

        // Save with CASCADE PRESERVE — removals should be silently skipped
        graphObjectManager.save(modified, CascadeType.PRESERVE)

        // Verify both relationships still exist in the database
        val reloaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(reloaded)
        assertEquals(2, reloaded.assignedTo.size)
        assertTrue(reloaded.assignedTo.any { it.name == "Kent Beck" })
        assertTrue(reloaded.assignedTo.any { it.name == "Ward Cunningham" })
    }

    @Test
    fun `CASCADE PRESERVE should still allow adding new relationships`() {
        // Create issue with one assignee
        val issueUuid = UUID.randomUUID()
        val raiserUuid = UUID.randomUUID()
        val assignee1Uuid = UUID.randomUUID()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue:Issue {
                        uuid: ${'$'}issueUuid,
                        id: 3008,
                        title: 'Test Issue',
                        body: 'Body text',
                        state: 'open',
                        stateReason: 'REOPENED',
                        locked: false,
                        createdBy: 'cascade-test'
                    })
                    CREATE (raiser:Person:Mapped {
                        uuid: ${'$'}raiserUuid,
                        name: 'Martin Fowler',
                        bio: 'Refactoring author',
                        createdBy: 'cascade-test'
                    })
                    CREATE (assignee1:Person:Mapped {
                        uuid: ${'$'}assignee1Uuid,
                        name: 'Kent Beck',
                        bio: 'TDD pioneer',
                        createdBy: 'cascade-test'
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

        // Load, drop existing assignee, add a new one
        val loaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(loaded)
        assertEquals(1, loaded.assignedTo.size)

        val newAssigneeUuid = UUID.randomUUID()
        val newAssignee = sample.mapped.fragment.Person(
            uuid = newAssigneeUuid,
            name = "Ward Cunningham",
            bio = "Wiki inventor"
        )

        // Replace list with only the new assignee (drops Kent Beck from in-memory)
        val modified = loaded.copy(assignedTo = listOf(newAssignee))

        // Save with CASCADE PRESERVE — the removal of Kent Beck should be skipped,
        // but the addition of Ward Cunningham should still happen
        graphObjectManager.save(modified, CascadeType.PRESERVE)

        // Verify BOTH assignees exist: the original was preserved, and the new one was added
        val reloaded = graphObjectManager.load(issueUuid.toString(), RaisedAndAssignedIssue::class.java)
        assertNotNull(reloaded)
        assertEquals(2, reloaded.assignedTo.size)
        assertTrue(reloaded.assignedTo.any { it.name == "Kent Beck" }, "Original assignee should be preserved")
        assertTrue(reloaded.assignedTo.any { it.name == "Ward Cunningham" }, "New assignee should be added")
    }
}