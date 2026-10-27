package org.drivine.postgres

import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.connection.TablesOnFirstConnectionContract
import org.drivine.test.PostgresTestContainer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/** The graph's tables made on a Postgres provider's first connection, in a database made for each test. */
@PostgresTests
class PostgresTablesOnFirstConnectionTest : TablesOnFirstConnectionContract() {

    private val database = "fresh_${databases.incrementAndGet()}"
    private val reader = "${database}_reader"

    @BeforeEach
    fun makeDatabase() {
        dropDatabase()
        PostgresTestDatabase.run("CREATE DATABASE $database")
    }

    @AfterEach
    fun dropDatabase() {
        endAll()
        PostgresTestDatabase.run("DROP DATABASE IF EXISTS $database WITH (FORCE)", "DROP ROLE IF EXISTS $reader")
    }

    private fun url() = JdbcConnectionProvider.postgresUrl(PostgresTestContainer.getConnectionHost(), PostgresTestContainer.getConnectionPort(), database)

    /** A connection to the test's database as its owner, through no provider, so it makes nothing. */
    private fun owner() = DriverManager.getConnection(url(), PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword())

    override fun provider(graph: String): JdbcConnectionProvider = JdbcConnectionProvider.pooled(
        "fresh", DatabaseType.POSTGRES, url(), PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword(), graphName = graph,
    )

    /** A role that may read the tables of [graph] and make nothing: no role but the owner may make a table in `public` here. */
    override fun readOnly(graph: String): JdbcConnectionProvider {
        PostgresTestDatabase.run("CREATE ROLE $reader LOGIN PASSWORD 'reader'")
        owner().use { c ->
            c.createStatement().use {
                it.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC")
                it.execute("GRANT SELECT ON ${graph}_node, ${graph}_edge, ${graph}_schema TO $reader")
            }
        }
        return JdbcConnectionProvider.pooled("fresh", DatabaseType.POSTGRES, url(), reader, "reader", graphName = graph)
    }

    override fun tables(graph: String): List<String> = owner().use { c ->
        c.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_name LIKE ? ORDER BY 1").use { s ->
            s.setString(1, "${graph.lowercase()}\\_%")
            s.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }
    }

    private companion object {
        /** Names each test's database apart from the others'. */
        val databases = AtomicInteger()
    }
}
