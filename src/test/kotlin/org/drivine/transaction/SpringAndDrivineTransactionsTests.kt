package org.drivine.transaction

import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import sample.simple.TestAppContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A bean whose `@Transactional` methods write a marker, for blocks of [Transactions] to call. */
open class MarkerWriter(private val persistenceManager: PersistenceManager, private val contextHolder: TransactionContextHolder) {

    /** The transaction the method ran in. */
    @Transactional
    open fun write(run: String, name: String): DrivineTransactionObject? {
        mark(run, name)
        return contextHolder.currentTransaction
    }

    @Transactional
    open fun writeAndThrow(run: String, name: String) {
        mark(run, name)
        error("the @Transactional method fails")
    }

    open fun mark(run: String, name: String) = persistenceManager.execute(
        QuerySpecification.withStatement("CREATE (:Marker {run: \$run, name: \$name})").bind(mapOf("run" to run, "name" to name))
    )
}

@Configuration
@EnableTransactionManagement
class MarkerWriterConfig {
    @Bean
    fun markerWriter(persistenceManager: PersistenceManager, contextHolder: TransactionContextHolder) =
        MarkerWriter(persistenceManager, contextHolder)
}

/**
 * Drivine's own [Transactions] and Spring's `@Transactional` and [TransactionTemplate], through
 * [DrivineTransactionManager], nest within each other on a thread, either way round: both keep the
 * transaction in the one [TransactionContextHolder].
 */
@SpringBootTest(classes = [TestAppContext::class, MarkerWriterConfig::class])
class SpringAndDrivineTransactionsTests @Autowired constructor(
    private val transactions: Transactions,
    private val writer: MarkerWriter,
    private val persistenceManager: PersistenceManager,
    transactionManager: PlatformTransactionManager,
) {

    private val template = TransactionTemplate(transactionManager)
    private val run = "nest-" + UUID.randomUUID().toString().take(8)

    /** FalkorDB has no transactions, so nothing there is rolled back. */
    @BeforeEach
    fun assumeTransactions() =
        assumeTrue(persistenceManager.type != DatabaseType.FALKORDB, "FalkorDB has no transactions")

    @AfterEach
    fun clean() {
        assertNull(transactions.current, "a test left a transaction on the thread")
        persistenceManager.execute(
            QuerySpecification.withStatement("MATCH (m:Marker {run: \$run}) DELETE m").bind(mapOf("run" to run))
        )
    }

    /** What was committed, read outside any transaction. */
    private fun committed(): List<String> = transactions.transaction(Propagation.NOT_SUPPORTED) {
        persistenceManager.query(
            QuerySpecification.withStatement("MATCH (m:Marker {run: \$run}) RETURN m.name ORDER BY m.name")
                .bind(mapOf("run" to run)).transform(String::class.java)
        )
    }

    // A block of Drivine's, with Spring inside it

    @Test
    fun `a @Transactional method called from a block joins the block's transaction`() {
        transactions.transaction {
            val block = assertNotNull(transactions.current)
            writer.mark(run, "block")
            assertSame(block, writer.write(run, "method"))
            assertEquals(emptyList(), committed(), "the method's write waits for the block's end")
        }
        assertEquals(listOf("block", "method"), committed())
    }

    @Test
    fun `a block that throws rolls back what a @Transactional method inside it wrote`() {
        assertFailsWith<IllegalStateException> {
            transactions.transaction {
                writer.write(run, "method")
                template.executeWithoutResult { writer.mark(run, "template") }
                error("the block fails")
            }
        }
        assertEquals(emptyList(), committed())
    }

    @Test
    fun `a @Transactional method that throws inside a block leaves the end to the block`() {
        transactions.transaction {
            writer.mark(run, "block")
            assertFailsWith<IllegalStateException> { writer.writeAndThrow(run, "method") }
            assertTrue(transactions.current!!.rollbackOnly)
        }
        assertEquals(listOf("block", "method"), committed())
    }

    // Spring's transaction, with a block of Drivine's inside it

    @Test
    fun `a block run inside a TransactionTemplate joins Spring's transaction`() {
        template.executeWithoutResult {
            val spring = assertNotNull(transactions.current)
            writer.mark(run, "template")
            transactions.transaction(Propagation.MANDATORY) {
                assertSame(spring, transactions.current)
                writer.mark(run, "block")
            }
            assertEquals(emptyList(), committed())
        }
        assertEquals(listOf("block", "template"), committed())
    }

    @Test
    fun `a TransactionTemplate that throws rolls back the block inside it`() {
        assertFailsWith<IllegalStateException> {
            template.executeWithoutResult {
                transactions.transaction { writer.mark(run, "block") }
                error("the template fails")
            }
        }
        assertEquals(emptyList(), committed())
    }

    @Test
    fun `a block that throws inside Spring's transaction leaves the end to Spring, as a @Transactional method does`() {
        template.executeWithoutResult {
            writer.mark(run, "template")
            assertFailsWith<IllegalStateException> {
                transactions.transaction {
                    writer.mark(run, "block")
                    error("caught by the template")
                }
            }
        }
        assertEquals(listOf("block", "template"), committed())
    }

    @Test
    fun `a REQUIRES_NEW block inside Spring's transaction commits by itself`() {
        assertFailsWith<IllegalStateException> {
            template.executeWithoutResult {
                val spring = transactions.current
                transactions.transaction(Propagation.REQUIRES_NEW) { writer.mark(run, "new") }
                assertSame(spring, transactions.current)
                writer.mark(run, "template")
                error("the template fails")
            }
        }
        assertEquals(listOf("new"), committed())
    }

    @Test
    fun `a block given rollback rolls Spring's transaction back`() {
        template.executeWithoutResult {
            writer.mark(run, "template")
            transactions.transaction(rollback = true) { writer.mark(run, "block") }
        }
        assertEquals(emptyList(), committed())
    }
}
