package org.drivine.cli

import org.drivine.test.DrivineTestContainer
import org.drivine.test.FalkorDbTestContainer
import org.drivine.test.MemgraphTestContainer
import org.drivine.test.OracleTestContainer
import org.drivine.test.PostgresTestContainer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals

/**
 * The CLI against each store but SQLite, in the containers the root module's tests run (`org.drivine.test`): a
 * statement, a failure, a refused password, and the contract's cases. Opt-in, so a plain `test` starts no container:
 * `CLI_STORE_TESTS=true` runs them, and `ORACLE_TESTS=true` with it runs Oracle's too. One store's:
 * `CLI_STORE_TESTS=true ./gradlew :drivine4j-cli:test --tests 'org.drivine.cli.CliPostgresTest'`.
 */
abstract class CliStoreTest {

    /** The store's name in cli.yaml. */
    abstract val store: String

    /** The store's address, in its own scheme. */
    abstract val address: String

    open val user: String? = null
    open val password: String? = null

    private fun run(vararg args: String, env: Map<String, String> = credentials()) = runCli(listOf("-a", address) + args, env)

    private fun credentials() = buildMap {
        user?.let { put(Invocation.ENV_USER, it) }
        password?.let { put(Invocation.ENV_PASSWORD, it) }
    }

    @Test
    fun `a statement's rows print as one json line, and the run exits with 0`() {
        assertEquals(CliRun(0, "{\"columns\":[\"n\"],\"rows\":[[1]]}\n", ""), run("--format", "json", "RETURN 1 AS n"))
    }

    @Test
    fun `a statement that fails exits with 1`() {
        assertEquals(1, run("--format", "json", "RETURN 1/0").exit)
    }

    @Test
    fun `a wrong password exits with 3, where the store has one`() {
        if (password == null) return
        assertEquals(3, run("RETURN 1", env = credentials() + (Invocation.ENV_PASSWORD to "not-the-password")).exit)
    }

    @TestFactory
    fun `the contract's cases`(): List<DynamicTest> = CliCases(store, address, user, password).tests()
}

@EnabledIfEnvironmentVariable(named = "CLI_STORE_TESTS", matches = "true")
class CliNeo4jTest : CliStoreTest() {
    override val store = "neo4j"
    override val address: String get() = DrivineTestContainer.getConnectionUrl()
    override val user: String get() = DrivineTestContainer.getConnectionUsername()
    override val password: String get() = DrivineTestContainer.getConnectionPassword()
}

@EnabledIfEnvironmentVariable(named = "CLI_STORE_TESTS", matches = "true")
class CliMemgraphTest : CliStoreTest() {
    override val store = "memgraph"
    override val address: String get() = "memgraph://${MemgraphTestContainer.getConnectionHost()}:${MemgraphTestContainer.getConnectionPort()}"
}

@EnabledIfEnvironmentVariable(named = "CLI_STORE_TESTS", matches = "true")
class CliFalkorDbTest : CliStoreTest() {
    override val store = "falkordb"
    override val address: String get() = "falkordb://${FalkorDbTestContainer.getConnectionHost()}:${FalkorDbTestContainer.getConnectionPort()}"
}

@EnabledIfEnvironmentVariable(named = "CLI_STORE_TESTS", matches = "true")
class CliPostgresTest : CliStoreTest() {
    override val store = "postgres"
    override val address: String
        get() = "postgresql://${PostgresTestContainer.getConnectionHost()}:${PostgresTestContainer.getConnectionPort()}/${PostgresTestContainer.DATABASE}"
    override val user: String get() = PostgresTestContainer.getConnectionUsername()
    override val password: String get() = PostgresTestContainer.getConnectionPassword()
}

@EnabledIfEnvironmentVariable(named = "CLI_STORE_TESTS", matches = "true")
@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS", matches = "true")
class CliOracleTest : CliStoreTest() {
    override val store = "oracle"
    override val address: String
        get() = "oracle://${OracleTestContainer.getConnectionHost()}:${OracleTestContainer.getConnectionPort()}/${OracleTestContainer.SERVICE}"
    override val user: String get() = OracleTestContainer.getConnectionUsername()
    override val password: String get() = OracleTestContainer.getConnectionPassword()
}
