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
 * Every store sorts a list of zoned values, and a list of lists of them, and takes its `min` and `max`, by their moments: see
 * `CypherQueryContract`. Oracle reads the items of a list the statement works out without `JSON_TABLE`, whose parse of one
 * worked out with temporal arithmetic stalled the session (#331).
 */
class OracleZonedListSortTest {

    private val sorted = "UNWIND ['2024-03-15T10:00:00+02:00', '2024-03-15T09:00:00Z'] AS at WITH [datetime(at)] AS l RETURN 1 ORDER BY l"
    private val extreme = "UNWIND ['2024-03-15T10:00:00+02:00', '2024-03-15T09:00:00Z'] AS at WITH [[datetime(at)]] AS l RETURN min(l), max(l)"

    @Test
    fun `every store sorts a list of zoned values, or of lists of them, and takes its min and max by their moments`() {
        for (cypher in listOf(sorted, extreme)) {
            val oracle = OracleCypherCompiler("g").compile(cypher).sql
            assertTrue(oracle.contains("LISTAGG") && oracle.contains(" mk1"), oracle)
            val postgres = PostgresCypherCompiler("g").compile(cypher).sql
            assertTrue(postgres.contains("string_agg") && postgres.contains(" mk1"), postgres)
            val sqlite = SqliteCypherCompiler("g").compile(cypher).sql
            assertTrue(sqlite.contains("group_concat") && sqlite.contains(" mk1"), sqlite)
        }
    }

    @Test
    fun `on Oracle a list of temporal values is read item by item with no JSON_TABLE, and a list of others with one`() {
        val temporal = OracleCypherCompiler("g").compile("$repro RETURN k ORDER BY size([x IN l WHERE x IS NOT NULL])").sql
        assertTrue(temporal.contains("PASSING x6_r.n - 1"), temporal)
        assertFalse(temporal.contains("JSON_TABLE(w4.c2"), temporal)
        val other = OracleCypherCompiler("g").compile("UNWIND [0, 1] AS k WITH k, [i IN range(1, 10) | i + k] AS l RETURN size([x IN l WHERE x > 1])").sql
        assertFalse(other.contains("PASSING"), other)
        // collect(DISTINCT …) keeps each list, or each value, once, reading the collected list by its places too.
        for (distinct in listOf("$repro RETURN collect(DISTINCT l)", "$repro UNWIND l AS t RETURN collect(DISTINCT t)")) {
            val sql = OracleCypherCompiler("g").compile(distinct).sql
            assertTrue(sql.contains("PASSING e_r.n - 1"), sql)
        }
        assertFalse(OracleCypherCompiler("g").compile("UNWIND [1, 1] AS k RETURN collect(DISTINCT k)").sql.contains("PASSING"))
    }
}

/** #331's statement: a list worked out item by item with temporal arithmetic, by a comprehension. */
private const val repro = "UNWIND [0, 1] AS k WITH k, [i IN range(1, 10) | localdatetime('2024-03-15T10:00:00') + duration({minutes: i})] AS l"

/**
 * The statements whose reads of a list stalled Oracle's parse (#331) run in moments, and lists of zoned values sort by their
 * moments. Each runs on a connection of its own, with a deadline: neither a query timeout nor a kill ends Oracle's parse, so past
 * the deadline the session is killed, which marks it, and the connection aborted, which ends the wait here, and the test fails
 * rather than holding the suite.
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

    /** By its moment 10:00+02:00 (08:00 UTC) comes first, as every store sorts it; by its text 09:00Z would. */
    private val at = "datetime(CASE k WHEN 0 THEN '2024-03-15T10:00:00+02:00' ELSE '2024-03-15T09:00:00Z' END)"

    @Test
    fun `a list worked out item by item with temporal arithmetic is filtered, sorted, reversed, and its size taken, in moments`() {
        assertEquals(listOf("[0,10]", "[1,10]"), rows("$repro RETURN [k, size([x IN l WHERE x IS NOT NULL])] ORDER BY k"))
        assertEquals(setOf("0", "1"), rows("$repro RETURN k ORDER BY size([x IN l WHERE x IS NOT NULL])").toSet())
        val zoned = "UNWIND [0, 1] AS k WITH k, [i IN range(1, 10) | datetime('2024-03-15T10:00:00Z') + duration({minutes: i})] AS l"
        assertEquals(listOf("[0,10]", "[1,10]"), rows("$zoned RETURN [k, size([x IN l WHERE x IS NOT NULL])] ORDER BY k"))
        assertEquals(listOf("[0,true,1]"), rows("$zoned WITH k, l WHERE k = 0 RETURN [k, all(x IN l WHERE x > datetime('2024-03-15T10:00:00Z')), size([x IN l WHERE x.minute = 1])]"))
        assertEquals(listOf("10"), rows("$repro WITH k, l WHERE k = 0 UNWIND l AS t RETURN count(t)"))
        assertEquals(listOf("[10,2]"), rows("$repro WITH k, l WHERE k = 0 RETURN [reverse(l)[0].minute, tail(l)[0].minute]"))
        assertEquals(listOf("[1,10]"), rows("$repro WITH collect(DISTINCT l) AS ls RETURN [size(ls), size(ls[0])]"))
    }

    @Test
    fun `a list worked out with temporal arithmetic, by a comprehension or collect, sorts by its moments, and in moments`() {
        val comprehension = "UNWIND [0, 1] AS k WITH k, [i IN range(1, 10) | $at + duration({minutes: i})] AS l"
        assertEquals(listOf("0", "1"), rows("$comprehension RETURN k ORDER BY l"))
        assertEquals(listOf("0", "1"), rows("$comprehension RETURN k ORDER BY [l]"))
        assertEquals(listOf("[10,9]"), rows("$comprehension RETURN [min(l)[0].hour, max(l)[0].hour]"))
        val collected = "UNWIND [0, 1] AS k UNWIND range(1, 3) AS i WITH k, $at + duration({minutes: i}) AS t WITH k, collect(t) AS l"
        assertEquals(listOf("0", "1"), rows("$collected RETURN k ORDER BY l"))
        assertEquals(listOf("[10,9]"), rows("$collected RETURN [min(l)[0].hour, max(l)[0].hour]"))
    }

    /**
     * The first column of each row [cypher], compiled for Oracle, gives, as text; failing where they take past [seconds], which is
     * short of the 21 seconds the parse of a `JSON_TABLE` over #331's list took, and far past the 15 to 200 milliseconds each of these
     * statements takes, so a loaded machine does not fail them.
     */
    private fun rows(cypher: String, seconds: Long = 10): List<String> {
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
