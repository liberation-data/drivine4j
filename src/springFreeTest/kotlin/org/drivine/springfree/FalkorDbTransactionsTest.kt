package org.drivine.springfree

import org.drivine.Drivine
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbTransactionMode
import org.drivine.query.QuerySpecification
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
import kotlin.test.assertNull

/**
 * Drivine's own transactions on FalkorDB, which commits each statement as it runs: what a block does
 * there is its [FalkorDbTransactionMode], as for `@Transactional`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FalkorDbTransactionsTest {

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
                    databaseName = "springfree",
                    falkorDbTransactionMode = mode,
                )
            )
        )
    )

    private val warn = drivine(FalkorDbTransactionMode.WARN)
    private val strict = drivine(FalkorDbTransactionMode.STRICT)

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
    fun `in WARN mode a block runs, and its writes stay though it throws`() {
        assertFailsWith<IllegalStateException> {
            warn.transaction {
                warn.persistenceManager().execute(QuerySpecification.withStatement("CREATE (:Marker {name: 'a'})"))
                error("a rollback undoes nothing")
            }
        }

        assertEquals(listOf("a"), names())
        assertNull(warn.transactions.current)
    }

    @Test
    fun `in STRICT mode a block's first statement is refused, and nothing is written`() {
        assertFailsWith<UnsupportedOperationException> {
            strict.transaction {
                strict.persistenceManager().execute(QuerySpecification.withStatement("CREATE (:Marker {name: 'a'})"))
            }
        }

        assertEquals(emptyList(), names())
        assertNull(strict.transactions.current)
        // Outside a block, STRICT mode runs a statement as it comes.
        strict.persistenceManager().execute(QuerySpecification.withStatement("CREATE (:Marker {name: 'b'})"))
        assertEquals(listOf("b"), names())
    }
}
