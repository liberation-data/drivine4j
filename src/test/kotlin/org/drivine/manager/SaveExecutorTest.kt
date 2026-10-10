package org.drivine.manager

import java.lang.reflect.Proxy
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import org.drivine.DrivineException
import org.drivine.StaleObjectException
import org.drivine.query.SaveStatement
import org.drivine.query.StampWrite
import org.junit.jupiter.api.Test
import org.neo4j.driver.exceptions.ServiceUnavailableException
import org.neo4j.driver.exceptions.TransientException
import sample.stateless.Claim

/** What [SaveExecutor] does when the engine turns a save away, and when a save returns nothing. */
class SaveExecutorTest {

    private fun statement(expected: String?) =
        SaveStatement("RETURN 1", emptyMap(), StampWrite(Claim::class.java, "c1", "Claim", "id", expected), emptyList())

    private fun contended(depth: Int = 1): DrivineException {
        var cause: Throwable = TransientException("Neo.TransientError.Transaction.DeadlockDetected", "deadlock")
        repeat(depth - 1) { cause = RuntimeException(cause) }
        return DrivineException.withRootCause(cause)
    }

    /** A persistence manager whose queries give [outcomes] in turn: a failure to throw, or rows to return. */
    private fun answering(vararg outcomes: Any): Pair<PersistenceManager, () -> Int> {
        var calls = 0
        val manager = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PersistenceManager::class.java)) { _, method, _ ->
            check(method.name == "query") { "unexpected call of ${method.name}" }
            when (val outcome = outcomes[calls++]) {
                is Throwable -> throw outcome
                else -> outcome
            }
        } as PersistenceManager
        return manager to { calls }
    }

    @Test
    fun `a save the engine turned away is run again`() {
        val (manager, calls) = answering(contended(), contended(depth = 3), listOf("s1"))

        assertEquals(listOf("s1"), SaveExecutor(manager).save(statement(expected = null)))
        assertEquals(3, calls())
    }

    @Test
    fun `the stamps of related nodes are handed back in the statement's order, however the row lists them`() {
        val (manager, _) = answering(listOf("root,1/b=second,0/a=first"))
        val statement = SaveStatement(
            "RETURN 1", emptyMap(), StampWrite(Claim::class.java, "c1", "Claim", "id", null), listOf(Claim("a", "a"), Claim("b", "b")),
        )

        assertEquals(listOf("root", "first", "second"), SaveExecutor(manager).save(statement))
    }

    @Test
    fun `a related node whose relationships changed since it was loaded keeps the token it carried`() {
        val (manager, _) = answering(listOf("root,0/n0:found=node0:now,1/n1:other=node1:now,2/n2:found=node2:now"))
        val statement = SaveStatement(
            "RETURN 1", emptyMap(), StampWrite(Claim::class.java, "c1", "Claim", "id", null),
            listOf(Claim("a", "a"), Claim("b", "b"), Claim("c", "c")), listOf("n0:found", "n1:carried", null),
        )

        assertEquals(listOf("root", "node0:now", "node1:carried", "node2:now"), SaveExecutor(manager).save(statement))
    }

    @Test
    fun `when every attempt is turned away, the first failure is thrown`() {
        val first = contended()
        val (manager, calls) = answering(first, contended(), contended(), contended(), contended(), contended())

        assertSame(first, assertFailsWith<DrivineException> { SaveExecutor(manager).save(statement(expected = null)) })
        assertEquals(6, calls())
    }

    @Test
    fun `when every attempt of a checked save is turned away, the object is stale and the engine's error is the cause`() {
        val first = contended()
        val (manager, calls) = answering(first, contended(), contended(), contended(), contended(), contended())

        val stale = assertFailsWith<StaleObjectException> { SaveExecutor(manager).save(statement(expected = "mine:links")) }

        assertSame(first, stale.cause)
        assertContains(stale.message.orEmpty(), "the engine turned the save away each time it was run")
        assertFalse("was changed by another writer" in stale.message.orEmpty(), "the save cannot tell that the object's node changed")
        assertEquals("mine:links", stale.expectedStamp)
        assertEquals(6, calls())
    }

    @Test
    fun `a failure that is not contention is thrown at once`() {
        val failure = DrivineException.withRootCause(IllegalStateException("syntax"))
        val (manager, calls) = answering(failure)

        assertSame(failure, assertFailsWith<DrivineException> { SaveExecutor(manager).save(statement(expected = null)) })
        assertEquals(1, calls())
    }

    @Test
    fun `a lost connection is not run again, for the save may have been applied`() {
        val failure = DrivineException.withRootCause(ServiceUnavailableException("connection reset"))
        val (manager, calls) = answering(failure)

        assertSame(failure, assertFailsWith<DrivineException> { SaveExecutor(manager).save(statement(expected = null)) })
        assertEquals(1, calls())
    }

    @Test
    fun `a related node whose data changed since it was loaded keeps the token it carried`() {
        val (manager, _) = answering(listOf("root,0/n0:l0=new0:l0,1/other:l1=new1:l1,2/=new2:l2"))
        val statement = SaveStatement(
            "RETURN 1", emptyMap(), StampWrite(Claim::class.java, "c1", "Claim", "id", null),
            listOf(Claim("a", "a"), Claim("b", "b"), Claim("c", "c")), listOf("n0:l0", "n1:l1", null),
        )

        assertEquals(listOf("root", "new0:l0", "n1:l1", "new2:l2"), SaveExecutor(manager).save(statement))
    }

    @Test
    fun `a later failure of another kind is added to the first, which is thrown`() {
        val first = contended()
        val later = DrivineException.withRootCause(IllegalStateException("gone"))
        val (manager, _) = answering(first, later)

        val thrown = assertFailsWith<DrivineException> { SaveExecutor(manager).save(statement(expected = null)) }

        assertSame(first, thrown)
        assertEquals(listOf<Throwable>(later), thrown.suppressed.toList())
    }

    @Test
    fun `no row from a checked save is a stale object, and the node gone is told from the node changed`() {
        val (changed, _) = answering(emptyList<String>(), listOf("other"))
        val stale = assertFailsWith<StaleObjectException> { SaveExecutor(changed).save(statement(expected = "mine")) }
        assertEquals("other", stale.foundStamp)
        assertEquals(false, stale.deleted)

        val (gone, _) = answering(emptyList<String>(), emptyList<String>())
        assertEquals(true, assertFailsWith<StaleObjectException> { SaveExecutor(gone).save(statement(expected = "mine")) }.deleted)
    }

    @Test
    fun `no row from an unchecked save is an error`() {
        val (manager, _) = answering(emptyList<String>())

        assertFailsWith<IllegalStateException> { SaveExecutor(manager).save(statement(expected = null)) }
    }
}
