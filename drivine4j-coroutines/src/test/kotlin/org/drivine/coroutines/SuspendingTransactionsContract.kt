package org.drivine.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.drivine.Drivine
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.deleteAll
import org.drivine.manager.loadAll
import org.drivine.transaction.DrivineTransactionManager
import org.drivine.transaction.IllegalTransactionStateException
import org.drivine.transaction.Propagation
import org.drivine.transaction.transactionSuspending
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

@NodeFragment(labels = ["CoroutineMarker"])
data class CoroutineMarker(@NodeId val id: String, val name: String)

/**
 * A suspend transaction block on an engine with transactions, made as an application with no
 * framework makes it: with [Drivine]. Each engine's class gives the [Drivine] and the database name.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@OptIn(ExperimentalCoroutinesApi::class)
abstract class SuspendingTransactionsContract {

    protected abstract val drivine: Drivine
    protected abstract val database: String

    private val gom: StatelessGraphObjectManager by lazy { drivine.graphObjectManager(database) }

    /** One thread, so that two coroutines on it take turns there. */
    private val oneThread = Dispatchers.IO.limitedParallelism(1)

    private fun create(name: String) {
        gom.save(CoroutineMarker(UUID.randomUUID().toString(), name))
    }

    /** What the database holds, read outside any transaction, so only what was committed. */
    private fun committed(): List<String> = drivine.transaction(Propagation.NOT_SUPPORTED) {
        gom.loadAll<CoroutineMarker>().map { it.name }.sorted()
    }

    @BeforeEach
    fun clean() {
        gom.deleteAll<CoroutineMarker>()
    }

    @AfterEach
    fun noTransactionLeft() {
        assertNull(drivine.transactions.current, "a test left a transaction on the thread")
    }

    @AfterAll
    open fun close() {
        drivine.close()
    }

    @Test
    fun `a block that writes, switches dispatcher, writes again and throws leaves neither write`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            drivine.transactionSuspending {
                create("before")
                withContext(Dispatchers.IO) { create("after") }
                error("no")
            }
        }

        assertEquals(emptyList(), committed())
    }

    @Test
    fun `a coroutine on the same thread, while the block is suspended, sees no transaction`() = runBlocking {
        val blockWrote = CompletableDeferred<Unit>()
        val otherWrote = CompletableDeferred<Unit>()
        coroutineScope {
            launch(oneThread) {
                assertFailsWith<IllegalStateException> {
                    drivine.transactionSuspending {
                        create("in the block")
                        blockWrote.complete(Unit)
                        otherWrote.await()
                        create("in the block, again")
                        error("no")
                    }
                }
            }
            launch(oneThread) {
                blockWrote.await()
                assertNull(drivine.transactions.current)
                create("alone")
                otherWrote.complete(Unit)
            }
        }

        assertEquals(listOf("alone"), committed())
    }

    @Test
    fun `a block's writes on either side of a dispatcher switch commit together`() = runBlocking {
        val result = drivine.transactionSuspending {
            val transaction = assertNotNull(drivine.transactions.current)
            create("a")
            withContext(Dispatchers.IO) {
                assertSame(transaction, drivine.transactions.current)
                create("b")
            }
            withContext(Dispatchers.Default) { assertSame(transaction, drivine.transactions.current) }
            create("c")
            assertEquals(emptyList(), committed(), "nothing commits before the block returns")
            "done"
        }

        assertEquals("done", result)
        assertEquals(listOf("a", "b", "c"), committed())
        assertNull(drivine.transactions.current)
    }

    @Test
    fun `blocks nested in a block join its transaction, suspend or blocking`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            drivine.transactionSuspending {
                val outer = drivine.transactions.current
                drivine.transactionSuspending {
                    assertSame(outer, drivine.transactions.current)
                    withContext(Dispatchers.IO) { create("suspend") }
                }
                withContext(Dispatchers.IO) {
                    drivine.transaction {
                        assertSame(outer, drivine.transactions.current)
                        create("blocking")
                    }
                }
                error("the outer block fails")
            }
        }

        assertEquals(emptyList(), committed())
    }

    @Test
    fun `REQUIRES_NEW commits by itself, and NOT_SUPPORTED runs without the transaction`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            drivine.transactionSuspending {
                val outer = drivine.transactions.current
                create("outer")
                drivine.transactionSuspending(Propagation.REQUIRES_NEW) {
                    assertNotSame(outer, drivine.transactions.current)
                    withContext(Dispatchers.IO) { create("new") }
                }
                drivine.transactionSuspending(Propagation.NOT_SUPPORTED) {
                    withContext(Dispatchers.IO) {
                        assertNull(drivine.transactions.current)
                        create("aside")
                    }
                }
                assertSame(outer, drivine.transactions.current)
                error("the outer block fails")
            }
        }

        assertEquals(listOf("aside", "new"), committed())
    }

    @Test
    fun `MANDATORY in a block joins its transaction`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            drivine.transactionSuspending {
                val outer = drivine.transactions.current
                drivine.transactionSuspending(Propagation.MANDATORY) {
                    withContext(Dispatchers.IO) {
                        assertSame(outer, drivine.transactions.current)
                        create("mandatory")
                    }
                }
                error("the outer block fails")
            }
        }

        assertEquals(emptyList(), committed())
    }

    @Test
    fun `the propagations that refuse, refuse`() = runBlocking {
        assertFailsWith<IllegalTransactionStateException> { drivine.transactionSuspending(Propagation.MANDATORY) { create("a") } }
        assertFailsWith<IllegalTransactionStateException> { drivine.transactionSuspending(Propagation.NESTED) { create("a") } }
        drivine.transactionSuspending {
            assertFailsWith<IllegalTransactionStateException> { drivine.transactionSuspending(Propagation.NEVER) { create("a") } }
        }
        assertEquals(emptyList(), committed())
    }

    @Test
    fun `a coroutine launched in the block is refused the transaction, and the block rolls back`() = runBlocking {
        val refused = assertFailsWith<IllegalTransactionStateException> {
            drivine.transactionSuspending {
                create("in the block")
                launch(Dispatchers.IO) { create("in a launched coroutine") }
            }
        }

        assertContains(refused.message!!, "launched inside transaction")
        assertContains(refused.message!!, "REQUIRES_NEW")
        assertEquals(emptyList(), committed())
    }

    @Test
    fun `an async coroutine in the block is refused the transaction, and a block it runs can't join it`() = runBlocking {
        drivine.transactionSuspending {
            // A supervisor scope, so that each refusal is thrown from await and does not fail the block.
            supervisorScope {
                assertFailsWith<IllegalTransactionStateException> { async { create("async") }.await() }
                assertFailsWith<IllegalTransactionStateException> { async { drivine.transaction { create("joined") } }.await() }
                assertFailsWith<IllegalTransactionStateException> {
                    async { drivine.transactionSuspending { create("joined") } }.await()
                }
                assertEquals(42, async { 42 }.await(), "a coroutine that doesn't use Drivine runs as it would")
            }
        }

        assertEquals(emptyList(), committed())
    }

    @Test
    fun `a coroutine launched in the block runs in a transaction of its own, given REQUIRES_NEW`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            drivine.transactionSuspending {
                create("outer")
                val children = listOf("one", "two").map { name ->
                    launch(Dispatchers.IO) {
                        drivine.transactionSuspending(Propagation.REQUIRES_NEW) { create(name) }
                    }
                }
                children.forEach { it.join() }
                launch(Dispatchers.IO) { drivine.transaction(Propagation.REQUIRES_NEW) { create("blocking") } }.join()
                error("the outer block fails")
            }
        }

        assertEquals(listOf("blocking", "one", "two"), committed())
    }

    @Test
    fun `a block joins a Spring transaction begun on the thread, and Spring joins the block's`() {
        val template = TransactionTemplate(DrivineTransactionManager(drivine.transactionContextHolder))

        assertFailsWith<IllegalStateException> {
            template.executeWithoutResult {
                val spring = drivine.transactions.current
                runBlocking {
                    drivine.transactionSuspending {
                        assertSame(spring, drivine.transactions.current)
                        withContext(Dispatchers.IO) { create("in the block") }
                    }
                }
                error("Spring's transaction fails")
            }
        }
        assertEquals(emptyList(), committed())

        runBlocking {
            drivine.transactionSuspending {
                val block = drivine.transactions.current
                withContext(Dispatchers.IO) {
                    template.executeWithoutResult {
                        assertSame(block, drivine.transactions.current)
                        create("in Spring's")
                    }
                }
                create("in the block")
            }
        }
        assertEquals(listOf("in Spring's", "in the block"), committed())

        clean()
        val requiresNew = TransactionTemplate(DrivineTransactionManager(drivine.transactionContextHolder))
            .apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
        assertFailsWith<IllegalStateException> {
            runBlocking {
                drivine.transactionSuspending {
                    launch(Dispatchers.IO) { requiresNew.executeWithoutResult { create("in Spring's own") } }.join()
                    error("the block fails")
                }
            }
        }
        assertEquals(listOf("in Spring's own"), committed())
    }

    @Test
    fun `a block run through runBlocking inside a block joins it, where the outer block found the thread's transaction`() {
        val template = TransactionTemplate(DrivineTransactionManager(drivine.transactionContextHolder))

        template.executeWithoutResult {
            runBlocking {
                drivine.transactionSuspending {
                    val outer = drivine.transactions.current
                    withContext(Dispatchers.IO) {
                        // Blocking code the block calls, which runs suspend code of its own: nested, not at once.
                        runBlocking {
                            drivine.transactionSuspending {
                                assertSame(outer, drivine.transactions.current)
                                create("nested")
                            }
                        }
                    }
                }
            }
        }

        assertEquals(listOf("nested"), committed())
    }

    @Test
    fun `rollback leaves nothing a block wrote, though it returned`() = runBlocking {
        val result = drivine.transactionSuspending(rollback = true) {
            create("a")
            withContext(Dispatchers.IO) { create("b") }
            "done"
        }

        assertEquals("done", result)
        assertEquals(emptyList(), committed())
    }

    @Test
    fun `a coroutine launched in a block with no transaction runs as it would anywhere`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            drivine.transactionSuspending {
                drivine.transactionSuspending(Propagation.NOT_SUPPORTED) {
                    launch(Dispatchers.IO) {
                        assertNull(drivine.transactions.current)
                        create("launched, no transaction")
                    }.join()
                }
                drivine.transactionSuspending(Propagation.SUPPORTS) {
                    assertNotNull(drivine.transactions.current)
                    create("supports, joined")
                }
                error("the outer block fails")
            }
        }
        drivine.transactionSuspending(Propagation.SUPPORTS) {
            assertNull(drivine.transactions.current)
            create("supports, alone")
        }

        assertEquals(listOf("launched, no transaction", "supports, alone"), committed())
    }

    @Test
    fun `a coroutine launched in the block can't join it through Spring`() = runBlocking {
        val template = TransactionTemplate(DrivineTransactionManager(drivine.transactionContextHolder))

        drivine.transactionSuspending {
            supervisorScope {
                assertFailsWith<IllegalTransactionStateException> {
                    async(Dispatchers.IO) { template.executeWithoutResult { create("joined through Spring") } }.await()
                }
            }
        }

        assertEquals(emptyList(), committed())
    }

    @Test
    fun `two coroutines that find the thread's transaction can't run blocks in it at once`() {
        val template = TransactionTemplate(DrivineTransactionManager(drivine.transactionContextHolder))
        val first = CompletableDeferred<Unit>()

        template.executeWithoutResult {
            runBlocking {
                val holding = launch {
                    drivine.transactionSuspending {
                        create("first")
                        first.complete(Unit)
                        withContext(Dispatchers.IO) { Thread.sleep(50) }
                    }
                }
                first.await()
                assertFailsWith<IllegalTransactionStateException> { drivine.transactionSuspending { create("second") } }
                holding.join()
                drivine.transactionSuspending { create("after") }
            }
        }

        assertEquals(listOf("after", "first"), committed())
    }
}
