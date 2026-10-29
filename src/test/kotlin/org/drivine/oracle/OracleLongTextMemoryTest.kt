package org.drivine.oracle

import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.test.OracleTestContainer
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compiled Cypher statements that read a text past 32 KB, many of them on one session, as an application runs them:
 * each statement's memory goes back to the instance when it ends.
 */
@OracleTests
class OracleLongTextMemoryTest {

    /** The memory, in bytes, the session [sid] holds now, as the container's administrator sees it. */
    private fun sessionMemory(sid: Long): Long =
        DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), "system", OracleTestContainer.PASSWORD).use { c ->
            c.prepareStatement(
                "SELECT st.value FROM v\$sesstat st JOIN v\$statname sn ON sn.statistic# = st.statistic# WHERE sn.name = 'session pga memory' AND st.sid = ?",
            ).use { s -> s.setLong(1, sid); s.executeQuery().use { it.next(); it.getLong(1) } }
        }

    @Test
    fun `compiled statements that read a text past 32 KB give back their memory, however many run on one session`() {
        // Each of these held 2 to 24 MB of the instance's memory for as long as the driver kept its statement in its
        // cache, 64 statements a session, and a few dozen ran the instance out of PGA (ORA-04036,
        // liberation-data/drivine4j#313). Given, stored and made by the statement, each a statement of its own.
        val provider = JdbcConnectionProvider.pooled(
            "longtextmemory", DatabaseType.ORACLE, OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getConnectionUsername(), OracleTestContainer.getConnectionPassword(), graphName = "longtextmemory",
        )
        val connection = provider.connect()
        try {
            fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
                connection.query(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
            val t = "aB".repeat(20_000)
            query("MATCH (n:LongText) DETACH DELETE n")
            query("CREATE (n:LongText {t: \$t})", mapOf("t" to t))
            val sid = (connection.query(QuerySpecification.withStatement("SELECT SYS_CONTEXT('USERENV', 'SID') AS sid FROM dual")).single()).toString().toLong()
            val before = sessionMemory(sid)
            val parts = mapOf("a" to t.substring(0, 20_000), "b" to t.substring(20_000))
            for (i in 1..30) {
                val function = "left(t, size(t) - $i)"
                assertEquals(listOf(t.dropLast(i)), query("WITH \$t AS t RETURN $function", mapOf("t" to t)), "$function given")
                assertEquals(listOf(t.dropLast(i)), query("MATCH (n:LongText) WITH n.t AS t RETURN $function"), "$function stored")
                assertEquals(listOf(t.dropLast(i)), query("WITH \$a + \$b AS t RETURN $function", parts), "$function made")
            }
            val grown = sessionMemory(sid) - before
            // Kept, the 90 statements held 1,134 MB; closed, 37 MB.
            assertTrue(grown < 200_000_000, "The session holds ${grown / 1_000_000} MB more after 90 statements.")
            query("MATCH (n:LongText) DETACH DELETE n")
        } finally {
            connection.release()
            provider.end()
        }
    }
}
