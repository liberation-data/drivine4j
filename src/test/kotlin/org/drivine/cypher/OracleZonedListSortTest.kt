package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.test.OracleTestContainer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * On Oracle, a list of zoned values sorts, and takes its `min` and `max`, as its JSON, by the text of each item: Oracle takes
 * many minutes to parse the key that sorts it by moments over a list worked out item by item with temporal arithmetic (#331).
 * Postgres and SQLite sort it by moments: see `CypherQueryContract`.
 */
class OracleZonedListSortTest {

    private val sorted = "UNWIND ['2024-03-15T10:00:00+02:00', '2024-03-15T09:00:00Z'] AS at WITH [datetime(at)] AS l RETURN 1 ORDER BY l"
    private val extreme = "UNWIND ['2024-03-15T10:00:00+02:00', '2024-03-15T09:00:00Z'] AS at WITH [[datetime(at)]] AS l RETURN min(l), max(l)"

    @Test
    fun `on Oracle a list of zoned values, or of lists of them, sorts and takes its min and max with no key of their moments`() {
        for (cypher in listOf(sorted, extreme)) {
            val sql = OracleCypherCompiler("g").compile(cypher).sql
            assertFalse(sql.contains("LISTAGG") || sql.contains(" mk1"), sql)
        }
    }

    @Test
    fun `on Postgres and SQLite a list of zoned values, or of lists of them, sorts and takes its min and max by their moments`() {
        for (cypher in listOf(sorted, extreme)) {
            val postgres = PostgresCypherCompiler("g").compile(cypher).sql
            assertTrue(postgres.contains("string_agg") && postgres.contains(" mk1"), postgres)
            val sqlite = SqliteCypherCompiler("g").compile(cypher).sql
            assertTrue(sqlite.contains("group_concat") && sqlite.contains(" mk1"), sqlite)
        }
    }
}

/**
 * The statements whose moments key stalled Oracle's parse run in moments, and sort as JSON. Each runs on a connection of its
 * own, with a deadline: neither a query timeout nor a kill ends Oracle's parse, so past the deadline the session is killed,
 * which marks it, and the connection aborted, which ends the wait here, and the test fails rather than holding the suite.
 */
@OracleTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleZonedListSortRunTest {

    private val executor = Executors.newCachedThreadPool()

    init {
        OracleTestDatabase.graphManager("cypher")
    }

    @AfterAll
    fun end() {
        executor.shutdownNow()
    }

    /** By its moment 10:00+02:00 (08:00 UTC) comes first, and Postgres and SQLite sort it first; by its text 09:00Z does. */
    private val at = "datetime(CASE k WHEN 0 THEN '2024-03-15T10:00:00+02:00' ELSE '2024-03-15T09:00:00Z' END)"

    @Test
    fun `a list worked out with temporal arithmetic, by a comprehension or collect, sorts as its text, and in moments`() {
        val comprehension = "UNWIND [0, 1] AS k WITH k, [i IN range(1, 10) | $at + duration({minutes: i})] AS l"
        assertEquals(listOf("1", "0"), rows("$comprehension RETURN k ORDER BY l"))
        // A list of lists in Oracle's own order of JSON, not item by item: here, that it runs.
        assertEquals(setOf("0", "1"), rows("$comprehension RETURN k ORDER BY [l]").toSet())
        assertEquals(listOf("[9,10]"), rows("$comprehension RETURN [min(l)[0].hour, max(l)[0].hour]"))
        val collected = "UNWIND [0, 1] AS k UNWIND range(1, 3) AS i WITH k, $at + duration({minutes: i}) AS t WITH k, collect(t) AS l"
        assertEquals(listOf("1", "0"), rows("$collected RETURN k ORDER BY l"))
        assertEquals(listOf("[9,10]"), rows("$collected RETURN [min(l)[0].hour, max(l)[0].hour]"))
    }

    /** The first column of each row [cypher], compiled for Oracle, gives, as text; failing where they take past [seconds]. */
    private fun rows(cypher: String, seconds: Long = 30): List<String> {
        val sql = OracleCypherCompiler("cypher").compile(cypher).sql
        val connection = OracleTestDatabase.provider.nativeDriver.connection
        val sid = connection.createStatement().use { it.executeQuery("SELECT SYS_CONTEXT('USERENV', 'SID') FROM dual").use { r -> r.next(); r.getString(1) } }
        val running = executor.submit<List<String>> {
            connection.createStatement().use { it.executeQuery(sql).use { r -> generateSequence { if (r.next()) r.getString(1) else null }.toList() } }
        }
        try {
            return running.get(seconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            runCatching {
                DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), "system", OracleTestContainer.PASSWORD).use { admin ->
                    val serial = admin.createStatement().use { it.executeQuery("SELECT serial# FROM v\$session WHERE sid = $sid").use { r -> r.next(); r.getString(1) } }
                    admin.createStatement().use { it.execute("ALTER SYSTEM KILL SESSION '$sid,$serial' IMMEDIATE") }
                }
            }
            connection.abort(executor)
            fail("Oracle took more than $seconds seconds to run: $cypher")
        } finally {
            runCatching { connection.close() }
        }
    }
}
