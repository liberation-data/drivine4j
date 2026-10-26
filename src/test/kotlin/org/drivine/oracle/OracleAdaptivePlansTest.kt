package org.drivine.oracle

import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.cypher.spec.CypherSpec
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.test.OracleTestContainer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

/**
 * Oracle makes no adaptive plan in a session of Drivine's. Once the graph's tables had grown and shrunk without
 * statistics, an adaptive plan for a join that filters a node by a label and a property gave no rows, where the same
 * plan, fixed, gave them all (liberation-data/drivine4j#289). That state comes of a long run and isn't made here, so
 * the test asks for the plan: in a session of Drivine's it is fixed, and in a session of Oracle's defaults the same
 * read is planned adaptive.
 */
@OracleTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleAdaptivePlansTest {

    private val graph = "adaptive_plans"
    private val pm by lazy { OracleTestDatabase.graphManager(graph) }

    /** The compiled read that found no rows in the Cypher suite (liberation-data/drivine#331). */
    private val cypher = "MATCH (:Person {name: 'Keanu'})-[r:ACTED_IN]->(m) RETURN m.title"
    private val read = OracleCypherCompiler(graph).compile(cypher).sql

    @BeforeAll
    fun seed() {
        OracleTestDatabase.clear(pm)
        OracleTestDatabase.seed(pm, requireNotNull(CypherSpec.load("queries.yaml").graph))
    }

    @AfterAll
    fun drop() = OracleTestDatabase.drop("PROPERTY GRAPH $graph", "TABLE ${graph}_edge", "TABLE ${graph}_node", "TABLE ${graph}_schema")

    /** The notes of the plan Oracle makes for [sql] in [session]. */
    private fun notesOf(session: java.sql.Connection, sql: String): String {
        val id = "adaptive_${UUID.randomUUID().toString().take(8)}"
        session.createStatement().use { it.execute("EXPLAIN PLAN SET STATEMENT_ID = '$id' FOR $sql") }
        return session.createStatement().use { statement ->
            statement.executeQuery("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', '$id', 'TYPICAL +ADAPTIVE +NOTE'))").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.joinToString("\n")
            }
        }
    }

    private fun assertFixed(notes: String) {
        assertContains(notes, "Plan hash value")
        assertFalse("adaptive plan" in notes, notes)
    }

    @Test
    fun `each session of Drivine's pool plans the read fixed`() {
        val pool = OracleTestDatabase.provider.nativeDriver
        // Two sessions at once, so each is one the pool opened.
        pool.connection.use { first ->
            pool.connection.use { second ->
                assertNotSame(first.unwrap(java.sql.Connection::class.java), second.unwrap(java.sql.Connection::class.java))
                assertFixed(notesOf(first, read))
                assertFixed(notesOf(second, read))
            }
        }
    }

    @Test
    fun `a session of Oracle's defaults plans the same read adaptive`() {
        DriverManager.getConnection(
            OracleTestContainer.getJdbcUrl(), OracleTestContainer.getConnectionUsername(), OracleTestContainer.getConnectionPassword(),
        ).use { plain -> assertContains(notesOf(plain, read), "this is an adaptive plan") }
    }

    @Test
    fun `a session of the application's own data source plans the read fixed once Drivine connects with it`() {
        val sessions = mutableListOf<java.sql.Connection>()
        val plain = oracle.jdbc.pool.OracleDataSource().apply {
            url = OracleTestContainer.getJdbcUrl()
            user = OracleTestContainer.getConnectionUsername()
            setPassword(OracleTestContainer.getConnectionPassword())
        }
        // The application's data source, which hands its sessions to the test too.
        val applications = object : DataSource by plain {
            override fun getConnection(): java.sql.Connection = plain.connection.also { sessions += it }
        }
        val connection = JdbcConnectionProvider(graph, DatabaseType.ORACLE, applications, graphName = graph).connect()
        try {
            assertFixed(notesOf(sessions.single(), read))
        } finally {
            connection.release()
        }
    }

    @Test
    fun `a session of the application's own data source is left adaptive when the application asks Drivine to leave it`() {
        val sessions = mutableListOf<java.sql.Connection>()
        val plain = oracle.jdbc.pool.OracleDataSource().apply {
            url = OracleTestContainer.getJdbcUrl()
            user = OracleTestContainer.getConnectionUsername()
            setPassword(OracleTestContainer.getConnectionPassword())
        }
        val applications = object : DataSource by plain {
            override fun getConnection(): java.sql.Connection = plain.connection.also { sessions += it }
        }
        val connection = JdbcConnectionProvider(graph, DatabaseType.ORACLE, applications, graphName = graph, setsNoAdaptivePlans = false).connect()
        try {
            assertContains(notesOf(sessions.single(), read), "this is an adaptive plan")
        } finally {
            connection.release()
        }
    }

    @Test
    fun `the read gives its rows`() {
        assertEquals(
            listOf<Any?>("Johnny Mnemonic", "The Matrix", "The Matrix Reloaded"),
            pm.query(QuerySpecification.withStatement(cypherStatement(cypher))).map { it.toString() }.sorted(),
        )
    }
}
