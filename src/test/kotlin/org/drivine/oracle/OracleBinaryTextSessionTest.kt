package org.drivine.oracle

import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.test.OracleTestContainer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * A session of Drivine's compares text by its code points, as Cypher does, whatever the application's session says
 * (drivine-cypher-spec#372). Oracle compares a `VARCHAR2` as the session's `NLS_COMP` and `NLS_SORT` say: under
 * `NLS_COMP=LINGUISTIC` with `BINARY_CI`, `'a' = 'A'` and `'B' < 'a'` is false, so a compiled read's `<`, its order, its
 * lookup by a property and its `DISTINCT` would take another order and ignore case. Drivine sets each session
 * ([JdbcConnectionProvider.ORACLE_SESSION]) as it opens it, or the first time it is given one of the application's.
 */
@OracleTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleBinaryTextSessionTest {

    private val graph = "binary_text"

    /** What a session of the application's sets itself to: text compared linguistically, and case ignored. */
    private val linguistic = "ALTER SESSION SET NLS_COMP = LINGUISTIC NLS_SORT = BINARY_CI"

    /** The application's data source, each of whose sessions starts [linguistic]. */
    private val applications: DataSource by lazy {
        val plain = oracle.jdbc.pool.OracleDataSource().apply {
            url = OracleTestContainer.getJdbcUrl()
            user = OracleTestContainer.getConnectionUsername()
            setPassword(OracleTestContainer.getConnectionPassword())
        }
        object : DataSource by plain {
            override fun getConnection(): java.sql.Connection = plain.connection.also { session ->
                session.createStatement().use { it.execute(linguistic) }
            }
        }
    }

    @AfterAll
    fun drop() = OracleTestDatabase.drop("PROPERTY GRAPH $graph", "TABLE ${graph}_edge", "TABLE ${graph}_node", "TABLE ${graph}_schema")

    private fun nls(session: java.sql.Connection): Map<String, String> = session.createStatement().use { statement ->
        statement.executeQuery("SELECT parameter, value FROM nls_session_parameters WHERE parameter IN ('NLS_COMP', 'NLS_SORT')").use { rows ->
            generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap()
        }
    }

    @Test
    fun `each session of Drivine's pool compares text by its code points`() {
        OracleTestDatabase.provider.nativeDriver.connection.use { session ->
            assertEquals(mapOf("NLS_COMP" to "BINARY", "NLS_SORT" to "BINARY"), nls(session))
        }
    }

    @Test
    fun `a session of the application's that compares text linguistically compares it by its code points once Drivine connects with it`() {
        // The application's session as it set it: 'a' and 'A' are equal there.
        applications.connection.use { session ->
            assertEquals(mapOf("NLS_COMP" to "LINGUISTIC", "NLS_SORT" to "BINARY_CI"), nls(session))
            assertEquals(1, session.createStatement().use { s -> s.executeQuery("SELECT CASE WHEN 'a' = 'A' THEN 1 ELSE 0 END FROM dual").use { it.next(); it.getInt(1) } })
        }
        val connection = JdbcConnectionProvider(graph, DatabaseType.ORACLE, applications, graphName = graph).connect()
        try {
            fun cypher(statement: String): List<Any?> = connection.query(QuerySpecification.withStatement(cypherStatement(statement)))
            cypher("MATCH (n:CyText) DETACH DELETE n")
            cypher("CREATE (:CyText {name: 'a'}), (:CyText {name: 'A'}), (:CyText {name: 'B'})")
            assertEquals(listOf<Any?>("A", "B", "a"), cypher("MATCH (n:CyText) RETURN n.name ORDER BY n.name"))
            assertEquals(listOf<Any?>("A", "B"), cypher("MATCH (n:CyText) WHERE n.name < 'a' RETURN n.name ORDER BY n.name"))
            assertEquals(listOf<Any?>(1L), cypher("MATCH (n:CyText {name: 'A'}) RETURN count(n)"))
            assertEquals(listOf<Any?>(3L), cypher("MATCH (n:CyText) RETURN count(DISTINCT n.name)"))
            assertEquals(listOf<Any?>(listOf(false, true)), cypher("MATCH (n:CyText {name: 'a'}) RETURN [n.name = 'A', n.name > 'B']"))
        } finally {
            connection.release()
        }
    }
}
