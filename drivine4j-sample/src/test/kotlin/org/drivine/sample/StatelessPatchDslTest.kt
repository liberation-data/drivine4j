package org.drivine.sample

import org.drivine.manager.PatchResult
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.sample.fragment.IssueCore
import org.drivine.sample.fragment.StampedNote
import org.drivine.sample.fragment.patch
import org.drivine.sample.view.RaisedAndAssignedIssue
import org.drivine.sample.view.assignedTo
import org.drivine.sample.view.issue
import org.drivine.sample.view.patch
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

private val changed = PatchResult(found = true, applied = true, changed = true)
private val unmet = PatchResult(found = true, applied = false, changed = false)
private val absent = PatchResult(found = false, applied = false, changed = false)

/** The generated `patch<T>(id) { }`, for a fragment and for a view, inside the test's transaction. */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class StatelessPatchDslTest @Autowired constructor(
    private val stateless: StatelessGraphObjectManager,
    private val persistenceManager: PersistenceManager,
) {
    @Test
    fun `a fragment is patched by its id, where it meets the predicate`() {
        val saved = stateless.save(StampedNote(UUID.randomUUID().toString(), "Call Ada"))

        val refused = stateless.patch<StampedNote>(saved.id) {
            fields { query.text setTo "Call Bob" }
            where { query.text eq "Call Cy" }
        }
        val applied = stateless.patch<StampedNote>(saved.id) {
            fields { query.text setTo "Call Bob" }
            where { query.text eq "Call Ada" }
        }

        assertEquals(unmet to changed, refused to applied)
        val stored = assertNotNull(stateless.load(saved.id, StampedNote::class.java))
        assertEquals("Call Bob", stored.text)
        assertNotEquals(saved.stamp, stored.stamp)
        assertEquals(absent, stateless.patch<StampedNote>(UUID.randomUUID().toString()) { fields { query.text setTo "nobody" } })
    }

    @Test
    fun `a view is patched by the fields of its root, where a relationship meets the predicate`() {
        val uuid = UUID.randomUUID()
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (i:Issue {uuid: '$uuid', id: 7, title: 'As raised', locked: false, createdBy: 'patch-dsl-test'})
                CREATE (i)-[:ASSIGNED_TO]->(:Person:Mapped {uuid: '${UUID.randomUUID()}', name: 'Ada', createdBy: 'patch-dsl-test'})
                """.trimIndent()
            )
        )
        fun retitle(title: String, assignee: String) = stateless.patch<RaisedAndAssignedIssue>(uuid) {
            fields { issue.title setTo title }
            where { assignedTo.name eq assignee }
        }

        assertEquals(unmet, retitle("For Bob", "Bob"))
        assertEquals(changed, retitle("For Ada", "Ada"))

        assertEquals("For Ada", assertNotNull(stateless.load(uuid.toString(), IssueCore::class.java)).title)
    }
}

/** A patch joins the caller's transaction, and is undone with it. */
@SpringBootTest(classes = [SampleAppContext::class])
class StatelessPatchTransactionTest @Autowired constructor(
    private val stateless: StatelessGraphObjectManager,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Test
    fun `a patch in a transaction that is rolled back leaves the node as it was`() {
        val id = UUID.randomUUID().toString()
        val saved = assertNotNull(transaction.execute { stateless.save(StampedNote(id, "Call Ada")) })
        try {
            val result = transaction.execute { status ->
                stateless.patch<StampedNote>(id) { fields { query.text setTo "Call Bob" } }.also {
                    // The patch is seen inside the transaction it joined.
                    assertEquals("Call Bob", assertNotNull(stateless.load(id, StampedNote::class.java)).text)
                    status.setRollbackOnly()
                }
            }

            assertEquals(changed, result)
            val stored = assertNotNull(transaction.execute { stateless.load(id, StampedNote::class.java) })
            assertEquals("Call Ada" to saved.stamp, stored.text to stored.stamp)
        } finally {
            transaction.execute { stateless.delete(id, StampedNote::class.java) }
        }
    }
}
