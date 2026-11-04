package org.drivine.oracle

import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.test.OracleTestContainer
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How long Oracle takes to plan a statement of temporal arithmetic, so that a change to the SQL a year BC is read and
 * written by fails here, clearly, and not as a test that times out. Renumbered inline, with a test and a regular
 * expression that wrote each value three or four times, the min and max of a collected list of moments took 4.7 seconds
 * of Oracle's hard parse, a moment two `CALL`s deep 72 seconds, and a truncation four deep was not planned in ten minutes
 * (drivine#572). The store's era functions (see `OracleGraph.era`) took them to 1.1, 28 and 16 seconds.
 *
 * The time is the session's `parse time cpu` in `v$sesstat`, which the test user may not read, so the system user reads
 * it; each statement is new to Oracle (a comment that no other has), so it is parsed hard.
 */
@OracleTests
class OracleEraParseTest {

    private val graph = "cy_era_parse"

    private val at = "datetime(CASE k WHEN 0 THEN '2024-03-15T10:00:00+02:00' ELSE '2024-03-15T09:00:00Z' END)"

    /** The CPU, in milliseconds, Oracle took to parse [cypher], compiled for a store with the era functions, and its rows. */
    private fun parsed(cypher: String): Pair<Long, List<Any?>> {
        val manager = OracleTestDatabase.graphManager(graph)
        OracleTestDatabase.clear(manager)
        manager.query(QuerySpecification.withStatement(CypherStatement("CREATE (:CyTemporal {id: 'a', day: date('2024-03-15')})")))
        val compiler = OracleCypherCompiler(graph)
        val compiled = compiler.compile(cypher, emptyMap())
        assertTrue(compiler.callsStoredEra, compiled.sql)
        val connection = JdbcConnectionProvider(graph, DatabaseType.ORACLE, OracleTestDatabase.provider.nativeDriver, graphName = graph).connect()
        try {
            val sid = connection.query(QuerySpecification.withStatement("SELECT SYS_CONTEXT('USERENV', 'SID') AS sid FROM dual")).single().toString().toInt()
            return DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), "system", OracleTestContainer.getConnectionPassword()).use { admin ->
                fun parseCpu(): Long = admin.prepareStatement(
                    "SELECT s.value FROM v\$sesstat s JOIN v\$statname n ON n.statistic# = s.statistic# WHERE s.sid = ? AND n.name = 'parse time cpu'",
                ).use { statement ->
                    statement.setInt(1, sid)
                    statement.executeQuery().use { it.next(); it.getLong(1) }
                }
                val before = parseCpu()
                val rows = connection.query(QuerySpecification.withStatement("/* era parse ${System.nanoTime()} */ ${compiled.sql}").bind(compiled.parameters))
                // Centiseconds.
                (parseCpu() - before) * 10 to rows
            }
        } finally {
            connection.release()
        }
    }

    @Test
    fun `the min and max of a collected list of moments are planned in seconds`() {
        // 1.1 seconds measured; 4.7 renumbered inline.
        val (ms, rows) = parsed(
            "UNWIND [0, 1] AS k UNWIND range(1, 3) AS i WITH k, $at + duration({minutes: i}) AS t WITH k, collect(t) AS l RETURN [min(l)[0].hour, max(l)[0].hour]",
        )
        assertEquals(listOf(listOf(10L, 9L)), rows)
        assertTrue(ms < ZLS_BOUND, "hard parse took $ms ms, over $ZLS_BOUND ms")
    }

    @Test
    fun `a moment two CALLs deep is planned well within a minute`() {
        // 28 seconds measured; 72 renumbered inline, so the bound is under twice what it takes, not three times.
        val (ms, rows) = parsed(
            "MATCH (n:CyTemporal) CALL { WITH n CALL { WITH n RETURN n.day + duration('P1D') AS e } RETURN e AS d } RETURN (d + duration('P1D')).day",
        )
        assertEquals(listOf(17L), rows)
        assertTrue(ms < CALL_BOUND, "hard parse took $ms ms, over $CALL_BOUND ms")
    }

    @Test
    fun `a truncation with fields four deep is planned within a minute`() {
        // 16 seconds measured; renumbered inline it was still being planned after ten minutes, and 8.4 seconds before drivine#487.
        val (ms, rows) = parsed(
            "RETURN " + "datetime.truncate('second', ".repeat(4) + "datetime('2017-10-11T12:31:14+01:00')" + ", {hour: 3})".repeat(4) + ".hour",
        )
        assertEquals(listOf(3L), rows)
        assertTrue(ms < TRUNC_BOUND, "hard parse took $ms ms, over $TRUNC_BOUND ms")
    }

    private companion object {
        const val ZLS_BOUND = 3_500L
        const val CALL_BOUND = 50_000L
        const val TRUNC_BOUND = 60_000L
    }
}
