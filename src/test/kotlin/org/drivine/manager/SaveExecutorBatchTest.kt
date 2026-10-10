package org.drivine.manager

import java.lang.reflect.Proxy
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.drivine.DrivineException
import org.drivine.StaleObjectException
import org.drivine.query.NodeKey
import org.drivine.query.QuerySpecification
import org.drivine.query.Repeat
import org.drivine.query.SaveStatement
import org.drivine.query.StampWrite
import org.junit.jupiter.api.Test
import org.neo4j.driver.exceptions.TransientException
import sample.stateless.Claim

/** What [SaveExecutor] does with a batch, and with the stamps of a node a statement saves more than once. */
class SaveExecutorBatchTest {

    private fun statement(expected: String?, id: String = "c1") =
        SaveStatement("RETURN 1", emptyMap(), StampWrite(Claim::class.java, id, "Claim", "id", expected, expected), emptyList())

    private fun contended(): DrivineException =
        DrivineException.withRootCause(TransientException("Neo.TransientError.Transaction.DeadlockDetected", "deadlock"))

    /** A statement of the batch gave no row: its post-processors say what that means, as a persistence manager has them do. */
    private object NoRows

    /**
     * A persistence manager whose batches give [outcomes] in turn: a failure to throw, rows to return,
     * or [NoRows]. A query, which reads the stamp a refused node carries, gives [found].
     */
    private fun answering(vararg outcomes: Any, found: List<String> = emptyList()): Pair<PersistenceManager, () -> Int> {
        var calls = 0
        val manager = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PersistenceManager::class.java)) { _, method, arguments ->
            when (method.name) {
                "query" -> found
                "queryBatch" -> when (val outcome = outcomes[calls++]) {
                    is Throwable -> throw outcome
                    NoRows -> try {
                        @Suppress("UNCHECKED_CAST")
                        (arguments[0] as List<QuerySpecification<*>>).map { spec -> spec.postProcessors.fold(emptyList<Any>()) { rows, next -> next.apply(rows) } }
                    } catch (refused: RuntimeException) {
                        throw DrivineException.withRootCause(refused)
                    }
                    else -> outcome
                }
                else -> throw AssertionError("unexpected call of ${method.name}")
            }
        } as PersistenceManager
        return manager to { calls }
    }

    @Test
    fun `a batch the engine turned away is run again`() {
        val (manager, calls) = answering(contended(), listOf(listOf("s1")))
        val executor = SaveExecutor(manager)

        assertEquals(listOf(listOf("s1")), executor.batch(listOf(executor.spec(statement(expected = "mine"), inBatch = true))))
        assertEquals(2, calls())
    }

    @Test
    fun `a batch that is turned away and then finds a root stale is refused as stale`() {
        val first = contended()
        val (manager, calls) = answering(first, NoRows, found = listOf("other:links"))
        val executor = SaveExecutor(manager)

        val stale = assertFailsWith<StaleObjectException> {
            executor.batch(listOf(executor.spec(statement(expected = "mine:links"), inBatch = true)))
        }

        assertEquals("c1", stale.id)
        assertEquals("other:links", stale.foundStamp)
        assertEquals(false, stale.bySameBatch)
        assertEquals(2, calls())
    }

    @Test
    fun `a stale root of a batch is the one named`() {
        val (manager, _) = answering(NoRows, found = listOf("other:links"))
        val executor = SaveExecutor(manager)
        val specs = listOf(executor.spec(statement(expected = null, id = "unchecked"), inBatch = true), executor.spec(statement(expected = "mine:links", id = "c2"), inBatch = true))

        assertEquals("c2", assertFailsWith<StaleObjectException> { executor.batch(specs) }.id)
    }

    @Test
    fun `a root refused though it is as its object says once the batch is rolled back was changed by the batch`() {
        val (manager, _) = answering(NoRows, found = listOf("mine:links"))
        val executor = SaveExecutor(manager)

        val stale = assertFailsWith<StaleObjectException> {
            executor.batch(listOf(executor.spec(statement(expected = "mine:links"), inBatch = true)))
        }

        assertTrue(stale.bySameBatch)
        assertContains(stale.message.orEmpty(), "was changed by an earlier save of the same saveAll")
    }

    @Test
    fun `a root refused carrying a token the batch offered was changed by the batch`() {
        val (manager, _) = answering(NoRows, found = listOf("mine:marked"))
        val executor = SaveExecutor(manager)

        val stale = assertFailsWith<StaleObjectException> {
            executor.batch(listOf(executor.spec(statement(expected = "mine:links"), inBatch = true)), offered = listOf("anode:marked"))
        }

        assertTrue(stale.bySameBatch)
    }

    @Test
    fun `a batch that fails some other way after it was turned away throws the first failure`() {
        val first = contended()
        val later = DrivineException.withRootCause(IllegalStateException("syntax"))
        val (manager, _) = answering(first, later)

        val thrown = assertFailsWith<DrivineException> { SaveExecutor(manager).batch(listOf(QuerySpecification.withStatement("RETURN 1"))) }

        assertSame(first, thrown)
        assertEquals(listOf<Throwable>(later), thrown.suppressed.toList())
    }

    // ----- The stamps of a node one statement saves more than once -----

    private val claim = NodeKey(setOf("Claim"), "k")

    @Test
    fun `a node saved by two parts is handed back as the first part found it`() {
        // The second part found what the first wrote, and not what the node carried before the statement.
        val rows = listOf("root:r,0/n0:l0=n1:l1,1/n1:l1=n1:l1")
        val statement = SaveStatement(
            "RETURN 1", emptyMap(), StampWrite(Claim::class.java, "c1", "Claim", "id", null), listOf(Claim("k", "a"), Claim("k", "b")),
            listOf("n0:l0", "n0:l0"), listOf(claim, claim),
        )

        assertEquals(listOf("root:r", "n1:l1", "n1:l1"), SaveExecutor(answering().first).stamps(statement, rows))
    }

    @Test
    fun `a fragment for the root's node is handed the root's stamp`() {
        val rows = listOf("n1:l1,0/n1:l0=n1:l1")
        val root = StampWrite(Claim::class.java, "k", "Claim", "id", "n0:l0", "n0:l0")
        val same = SaveStatement("RETURN 1", emptyMap(), root, listOf(Claim("k", "a")), listOf("n0:l0"), listOf(claim))
        val other = SaveStatement("RETURN 1", emptyMap(), root, listOf(Claim("k", "a")), listOf("older:l0"), listOf(claim))

        assertEquals(listOf("n1:l1", "n1:l1"), SaveExecutor(answering().first).stamps(same, rows))
        assertEquals(listOf("n1:l1", "older:l0"), SaveExecutor(answering().first).stamps(other, rows), "one that carried another stamp keeps it")
    }

    @Test
    fun `a fragment that was not written is handed what the one written for its node is`() {
        val rows = listOf("root:r,0/n0:l0=n1:l1")
        val repeat = Claim("k", "first")
        val statement = SaveStatement(
            "RETURN 1", emptyMap(), StampWrite(Claim::class.java, "c1", "Claim", "id", null), listOf(Claim("k", "last")),
            listOf("n0:l0"), listOf(claim), listOf(Repeat(repeat, "n0:l0", claim), Repeat(repeat, "older:l0", claim)),
        )

        assertEquals(listOf("root:r", "n1:l1", "n1:l1", "older:l1"), SaveExecutor(answering().first).stamps(statement, rows))
    }

    @Test
    fun `a subtype's labels and a number's width do not make another node of it`() {
        assertTrue(NodeKey(setOf("Claim"), 5).sameNodeAs(NodeKey(setOf("Claim", "Disputed"), 5L)))
        assertEquals(false, NodeKey(setOf("Claim"), 5).sameNodeAs(NodeKey(setOf("Human"), 5)))
        assertEquals(false, NodeKey(setOf("Claim"), 5).sameNodeAs(NodeKey(setOf("Claim"), "5")))
    }

    @Test
    fun `no row from a statement that does not make its root says the root is gone`() {
        val statement = SaveStatement(
            "RETURN 1", emptyMap(), StampWrite(Claim::class.java, "c1", "Claim", "id", null), emptyList(), createsRoot = false,
        )

        assertEquals("c1", assertFailsWith<SaveExecutor.RootGone> { SaveExecutor(answering().first).stamps(statement, emptyList()) }.root.id)
    }
}
