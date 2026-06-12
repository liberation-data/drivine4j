package org.drivine.manager

import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.mapped.view.DeletableSession
import sample.mapped.view.DeletableSessionDeep
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

    // ==================== Cascade delete-by-id (scoped by GraphView) ====================
    //
    // These exercise GraphObjectManager.delete(id, view, cascade) on the chat-session shape:
    //   (:User) <-[OWNED_BY]- (:Session) -[HAS_MESSAGE]-> (:Message) -[AUTHORED_BY/SENT_TO]-> (:User)
    // The delete views (DeletableSession / DeletableSessionDeep) declare only HAS_MESSAGE, so the
    // :User node is always out-of-view and must survive every cascade.

    @Test
    fun `delete by id DELETE_ALL deletes session and messages but preserves out-of-view user`() {
        val sessionId = UUID.randomUUID().toString()
        val userId = UUID.randomUUID().toString()
        val message1Id = UUID.randomUUID().toString()
        val message2Id = UUID.randomUUID().toString()

        // The trap: each Message has an edge to :User that the view does NOT declare.
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (u:User {id: ${'$'}userId, createdBy: 'cascade-test'})
                    CREATE (s:Session {id: ${'$'}sessionId, createdBy: 'cascade-test'})
                    CREATE (m1:Message {id: ${'$'}message1Id, createdBy: 'cascade-test'})
                    CREATE (m2:Message {id: ${'$'}message2Id, createdBy: 'cascade-test'})
                    CREATE (s)-[:OWNED_BY]->(u)
                    CREATE (s)-[:HAS_MESSAGE]->(m1)
                    CREATE (s)-[:HAS_MESSAGE]->(m2)
                    CREATE (m1)-[:AUTHORED_BY]->(u)
                    CREATE (m2)-[:SENT_TO]->(u)
                """.trimIndent())
                .bind(mapOf(
                    "userId" to userId,
                    "sessionId" to sessionId,
                    "message1Id" to message1Id,
                    "message2Id" to message2Id
                ))
        )

        val deleted = graphObjectManager.delete<DeletableSession>(sessionId, CascadeType.DELETE_ALL)

        // Root + both messages deleted...
        assertEquals(3, deleted)
        assertEquals(0, nodeCount("Session", sessionId))
        assertEquals(0, nodeCount("Message", message1Id))
        assertEquals(0, nodeCount("Message", message2Id))
        // ...but the user the messages pointed at is outside the view and survives.
        assertEquals(1, nodeCount("User", userId))
    }

    @Test
    fun `delete by id DELETE_ALL cascades through a nested view to every level`() {
        val sessionId = UUID.randomUUID().toString()
        val userId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        val attachmentId = UUID.randomUUID().toString()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (u:User {id: ${'$'}userId, createdBy: 'cascade-test'})
                    CREATE (s:Session {id: ${'$'}sessionId, createdBy: 'cascade-test'})
                    CREATE (m:Message {id: ${'$'}messageId, createdBy: 'cascade-test'})
                    CREATE (a:Attachment {id: ${'$'}attachmentId, createdBy: 'cascade-test'})
                    CREATE (s)-[:OWNED_BY]->(u)
                    CREATE (s)-[:HAS_MESSAGE]->(m)
                    CREATE (m)-[:HAS_ATTACHMENT]->(a)
                    CREATE (m)-[:AUTHORED_BY]->(u)
                """.trimIndent())
                .bind(mapOf(
                    "userId" to userId,
                    "sessionId" to sessionId,
                    "messageId" to messageId,
                    "attachmentId" to attachmentId
                ))
        )

        val deleted = graphObjectManager.delete<DeletableSessionDeep>(sessionId, CascadeType.DELETE_ALL)

        // Session + Message + Attachment (two levels deep through the nested view).
        assertEquals(3, deleted)
        assertEquals(0, nodeCount("Session", sessionId))
        assertEquals(0, nodeCount("Message", messageId))
        assertEquals(0, nodeCount("Attachment", attachmentId))
        assertEquals(1, nodeCount("User", userId))
    }

    @Test
    fun `delete by id DELETE_ORPHAN deletes orphaned children but preserves still-referenced ones`() {
        val sessionId = UUID.randomUUID().toString()
        val userId = UUID.randomUUID().toString()
        val referencedMessageId = UUID.randomUUID().toString()
        val orphanMessageId = UUID.randomUUID().toString()

        // referencedMessage keeps an AUTHORED_BY edge after the session goes; orphanMessage does not.
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (u:User {id: ${'$'}userId, createdBy: 'cascade-test'})
                    CREATE (s:Session {id: ${'$'}sessionId, createdBy: 'cascade-test'})
                    CREATE (referenced:Message {id: ${'$'}referencedMessageId, createdBy: 'cascade-test'})
                    CREATE (orphan:Message {id: ${'$'}orphanMessageId, createdBy: 'cascade-test'})
                    CREATE (s)-[:OWNED_BY]->(u)
                    CREATE (s)-[:HAS_MESSAGE]->(referenced)
                    CREATE (s)-[:HAS_MESSAGE]->(orphan)
                    CREATE (referenced)-[:AUTHORED_BY]->(u)
                """.trimIndent())
                .bind(mapOf(
                    "userId" to userId,
                    "sessionId" to sessionId,
                    "referencedMessageId" to referencedMessageId,
                    "orphanMessageId" to orphanMessageId
                ))
        )

        val deleted = graphObjectManager.delete<DeletableSession>(sessionId, CascadeType.DELETE_ORPHAN)

        // Root + the one orphaned message.
        assertEquals(2, deleted)
        assertEquals(0, nodeCount("Session", sessionId))
        assertEquals(0, nodeCount("Message", orphanMessageId))
        // Still referenced (AUTHORED_BY) → preserved.
        assertEquals(1, nodeCount("Message", referencedMessageId))
        assertEquals(1, nodeCount("User", userId))
    }

    @Test
    fun `delete by id with default NONE deletes only the root and orphans children`() {
        val sessionId = UUID.randomUUID().toString()
        val userId = UUID.randomUUID().toString()
        val message1Id = UUID.randomUUID().toString()
        val message2Id = UUID.randomUUID().toString()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (u:User {id: ${'$'}userId, createdBy: 'cascade-test'})
                    CREATE (s:Session {id: ${'$'}sessionId, createdBy: 'cascade-test'})
                    CREATE (m1:Message {id: ${'$'}message1Id, createdBy: 'cascade-test'})
                    CREATE (m2:Message {id: ${'$'}message2Id, createdBy: 'cascade-test'})
                    CREATE (s)-[:OWNED_BY]->(u)
                    CREATE (s)-[:HAS_MESSAGE]->(m1)
                    CREATE (s)-[:HAS_MESSAGE]->(m2)
                """.trimIndent())
                .bind(mapOf(
                    "userId" to userId,
                    "sessionId" to sessionId,
                    "message1Id" to message1Id,
                    "message2Id" to message2Id
                ))
        )

        // Default cascade = NONE: legacy root-only DETACH DELETE.
        val deleted = graphObjectManager.delete<DeletableSession>(sessionId)

        assertEquals(1, deleted)
        assertEquals(0, nodeCount("Session", sessionId))
        // Children remain as orphans — exactly the pre-cascade behavior.
        assertEquals(1, nodeCount("Message", message1Id))
        assertEquals(1, nodeCount("Message", message2Id))
        assertEquals(1, nodeCount("User", userId))
    }

    @Test
    fun `delete by id DELETE_ALL with a non-matching where clause deletes nothing`() {
        val sessionId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (s:Session {id: ${'$'}sessionId, title: 'keep', createdBy: 'cascade-test'})
                    CREATE (m:Message {id: ${'$'}messageId, createdBy: 'cascade-test'})
                    CREATE (s)-[:HAS_MESSAGE]->(m)
                """.trimIndent())
                .bind(mapOf("sessionId" to sessionId, "messageId" to messageId))
        )

        // WHERE filters out the root, so cascade matches nothing and must return a single 0 row.
        val deleted = graphObjectManager.delete<DeletableSession>(
            sessionId,
            "session.title = 'archived'",
            CascadeType.DELETE_ALL
        )

        assertEquals(0, deleted)
        assertEquals(1, nodeCount("Session", sessionId))
        assertEquals(1, nodeCount("Message", messageId))
    }

    // ==================== Cascade on save() through an unchanged nested view ====================
    //
    // When a relationship is removed *inside* a nested view whose link to the parent is itself
    // unchanged, the removal must still be reconciled on save() and honor the cascade policy.
    // Session -[HAS_MESSAGE]-> MessageWithAttachments(view) -[HAS_ATTACHMENT]-> Attachment.

    @Test
    fun `save DELETE_ALL removes an attachment inside an unchanged nested-view message`() {
        val sessionId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        val attachment1Id = UUID.randomUUID().toString()
        val attachment2Id = UUID.randomUUID().toString()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (s:Session {id: ${'$'}sessionId, createdBy: 'cascade-test'})
                    CREATE (m:Message {id: ${'$'}messageId, createdBy: 'cascade-test'})
                    CREATE (a1:Attachment {id: ${'$'}attachment1Id, name: 'keep', createdBy: 'cascade-test'})
                    CREATE (a2:Attachment {id: ${'$'}attachment2Id, name: 'drop', createdBy: 'cascade-test'})
                    CREATE (s)-[:HAS_MESSAGE]->(m)
                    CREATE (m)-[:HAS_ATTACHMENT]->(a1)
                    CREATE (m)-[:HAS_ATTACHMENT]->(a2)
                """.trimIndent())
                .bind(mapOf(
                    "sessionId" to sessionId,
                    "messageId" to messageId,
                    "attachment1Id" to attachment1Id,
                    "attachment2Id" to attachment2Id
                ))
        )

        val loaded = graphObjectManager.load(sessionId, DeletableSessionDeep::class.java)
        assertNotNull(loaded)
        assertEquals(1, loaded.messages.size)
        assertEquals(2, loaded.messages.first().attachments.size)

        // Remove attachment2 from the message; the session->message link is unchanged.
        val message = loaded.messages.first()
        val modified = loaded.copy(
            messages = listOf(
                message.copy(attachments = message.attachments.filter { it.id == attachment1Id })
            )
        )

        graphObjectManager.save(modified, CascadeType.DELETE_ALL)

        // The dropped attachment node is deleted (DELETE_ALL); the kept one and the message survive.
        assertEquals(0, nodeCount("Attachment", attachment2Id))
        assertEquals(1, nodeCount("Attachment", attachment1Id))
        assertEquals(1, nodeCount("Message", messageId))
        assertEquals(1, nodeCount("Session", sessionId))

        val reloaded = graphObjectManager.load(sessionId, DeletableSessionDeep::class.java)
        assertNotNull(reloaded)
        assertEquals(1, reloaded.messages.first().attachments.size)
        assertEquals(attachment1Id, reloaded.messages.first().attachments.first().id)
    }

    @Test
    fun `save NONE removes the relationship inside an unchanged nested-view message but keeps the node`() {
        val sessionId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        val attachment1Id = UUID.randomUUID().toString()
        val attachment2Id = UUID.randomUUID().toString()

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (s:Session {id: ${'$'}sessionId, createdBy: 'cascade-test'})
                    CREATE (m:Message {id: ${'$'}messageId, createdBy: 'cascade-test'})
                    CREATE (a1:Attachment {id: ${'$'}attachment1Id, name: 'keep', createdBy: 'cascade-test'})
                    CREATE (a2:Attachment {id: ${'$'}attachment2Id, name: 'unlink', createdBy: 'cascade-test'})
                    CREATE (s)-[:HAS_MESSAGE]->(m)
                    CREATE (m)-[:HAS_ATTACHMENT]->(a1)
                    CREATE (m)-[:HAS_ATTACHMENT]->(a2)
                """.trimIndent())
                .bind(mapOf(
                    "sessionId" to sessionId,
                    "messageId" to messageId,
                    "attachment1Id" to attachment1Id,
                    "attachment2Id" to attachment2Id
                ))
        )

        val loaded = graphObjectManager.load(sessionId, DeletableSessionDeep::class.java)
        assertNotNull(loaded)

        val message = loaded.messages.first()
        val modified = loaded.copy(
            messages = listOf(
                message.copy(attachments = message.attachments.filter { it.id == attachment1Id })
            )
        )

        graphObjectManager.save(modified, CascadeType.NONE)

        // CASCADE NONE: the HAS_ATTACHMENT relationship is removed, but the Attachment node survives.
        assertEquals(1, nodeCount("Attachment", attachment2Id))
        assertEquals(1, nodeCount("Attachment", attachment1Id))

        val reloaded = graphObjectManager.load(sessionId, DeletableSessionDeep::class.java)
        assertNotNull(reloaded)
        assertEquals(1, reloaded.messages.first().attachments.size)
        assertEquals(attachment1Id, reloaded.messages.first().attachments.first().id)
    }

    private fun nodeCount(label: String, id: String): Int {
        return persistenceManager.getOne(
            QuerySpecification
                .withStatement("MATCH (n:$label {id: \$id}) RETURN count(n)")
                .bind(mapOf("id" to id))
                .transform<Int>()
        )
    }
}