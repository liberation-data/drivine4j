package org.drivine.coroutines

import org.drivine.Drivine
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.test.OracleTestContainer
import org.drivine.test.PostgresTestContainer
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

private fun drivine(name: String, properties: ConnectionProperties) = Drivine(DataSourceMap(mapOf(name to properties)))

class Neo4jSuspendingTransactionsTest : SuspendingTransactionsContract() {

    private val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
        .withAdminPassword(PASSWORD)
        .withStartupTimeout(Duration.ofMinutes(5))
        .withStartupAttempts(2)
        .also { it.start() }

    override val database = "neo"
    override val drivine = drivine(
        database,
        ConnectionProperties(
            type = DatabaseType.NEO4J,
            host = container.host,
            port = container.getMappedPort(7687),
            userName = "neo4j",
            password = PASSWORD,
            databaseName = "neo4j",
        ),
    )

    override fun close() {
        super.close()
        container.stop()
    }

    private companion object {
        const val PASSWORD = "coroutinestest"
    }
}

class MemgraphSuspendingTransactionsTest : SuspendingTransactionsContract() {

    private val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
        .withExposedPorts(7687)
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)))
        .withStartupAttempts(2)
        .also { it.start() }

    override val database = "memgraph"
    override val drivine = drivine(
        database,
        ConnectionProperties(
            type = DatabaseType.MEMGRAPH,
            host = container.host,
            port = container.getMappedPort(7687),
            userName = "",
            password = "",
        ),
    )

    override fun close() {
        super.close()
        container.stop()
    }
}

/** Runs only with `ORACLE_TESTS=true`, as the root module's Oracle tests do: see `TEST_CONTAINERS.md`. */
@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS", matches = "true")
class OracleSuspendingTransactionsTest : SuspendingTransactionsContract() {

    override val database = "oracle"
    override val drivine by lazy {
        drivine(
            database,
            ConnectionProperties(
                type = DatabaseType.ORACLE,
                host = OracleTestContainer.getConnectionHost(),
                port = OracleTestContainer.getConnectionPort(),
                userName = OracleTestContainer.getConnectionUsername(),
                password = OracleTestContainer.getConnectionPassword(),
                databaseName = OracleTestContainer.SERVICE,
                defaultGraphPath = "coroutines",
            ),
        )
    }
}

/** Runs by default, and `POSTGRES_TESTS=false` skips it, as the root module's Postgres tests: see `TEST_CONTAINERS.md`. */
@DisabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "false")
class PostgresSuspendingTransactionsTest : SuspendingTransactionsContract() {

    override val database = "postgres"
    override val drivine by lazy {
        drivine(
            database,
            ConnectionProperties(
                type = DatabaseType.POSTGRES,
                host = PostgresTestContainer.getConnectionHost(),
                port = PostgresTestContainer.getConnectionPort(),
                userName = PostgresTestContainer.getConnectionUsername(),
                password = PostgresTestContainer.getConnectionPassword(),
                databaseName = PostgresTestContainer.DATABASE,
                defaultGraphPath = "coroutines",
            ),
        )
    }
}
