package org.drivine.coroutines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.drivine.Drivine
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbTransactionMode
import org.drivine.query.QuerySpecification
import org.drivine.transaction.transactionSuspending
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A suspend transaction block on FalkorDB, which commits each statement as it runs: what a block does
 * there is its [FalkorDbTransactionMode], as for a blocking block and for `@Transactional`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FalkorDbSuspendingTransactionsTest {

    private val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
        .withExposedPorts(6379)
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)))
        .withStartupAttempts(2)
        .also { it.start() }

    private fun drivine(mode: FalkorDbTransactionMode) = Drivine(
        DataSourceMap(
            mapOf(
                "falkor" to ConnectionProperties(
                    type = DatabaseType.FALKORDB,
                    host = container.host,
                    port = container.getMappedPort(6379),
                    databaseName = "coroutines",
                    falkorDbTransactionMode = mode,
                )
            )
        )
    )

    private val warn = drivine(FalkorDbTransactionMode.WARN)
    private val strict = drivine(FalkorDbTransactionMode.STRICT)

    private fun create(drivine: Drivine, name: String) = drivine.persistenceManager().execute(
        QuerySpecification.withStatement("CREATE (:Marker {name: \$name})").bind(mapOf("name" to name))
    )

    private fun names(): List<String> = warn.persistenceManager().query(
        QuerySpecification.withStatement("MATCH (m:Marker) RETURN m.name ORDER BY m.name").transform(String::class.java)
    )

    @BeforeEach
    fun clean() {
        warn.persistenceManager().execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    @AfterAll
    fun close() {
        warn.close()
        strict.close()
        container.stop()
    }

    @Test
    fun `in WARN mode a block runs across a dispatcher switch, and its writes stay though it throws`() = runBlocking {
        assertFailsWith<IllegalStateException> {
            warn.transactionSuspending {
                create(warn, "a")
                withContext(Dispatchers.IO) {
                    assertNotNull(warn.transactions.current)
                    create(warn, "b")
                }
                error("a rollback undoes nothing")
            }
        }

        assertEquals(listOf("a", "b"), names())
        assertNull(warn.transactions.current)
    }

    @Test
    fun `in STRICT mode a block's first statement is refused, and nothing is written`() = runBlocking {
        assertFailsWith<UnsupportedOperationException> {
            strict.transactionSuspending {
                withContext(Dispatchers.IO) { create(strict, "a") }
            }
        }

        assertEquals(emptyList(), names())
        assertNull(strict.transactions.current)
    }

    @Test
    fun `two Drivines' blocks, one nested in the other, each carry their own transaction`() = runBlocking {
        warn.transactionSuspending {
            val outer = assertNotNull(warn.transactions.current)
            strict.transactionSuspending {
                val inner = assertNotNull(strict.transactions.current)
                assertNotSame(outer, inner)
                withContext(Dispatchers.IO) {
                    assertSame(outer, warn.transactions.current)
                    assertSame(inner, strict.transactions.current)
                }
            }
            withContext(Dispatchers.IO) {
                assertSame(outer, warn.transactions.current, "the nested block of another Drivine left this one's in place")
                assertNull(strict.transactions.current)
            }
        }

        assertNull(warn.transactions.current)
        assertNull(strict.transactions.current)
    }
}
