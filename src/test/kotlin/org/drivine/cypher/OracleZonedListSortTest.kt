package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.query.SqlParameters
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

    @Test
    fun `on Oracle a coalesce of a list of temporal values, and a list that holds them deeper, is read item by item too`() {
        // coalesce(l, []) is of l's kind (#347).
        val coalesced = OracleCypherCompiler("g").compile("$repro RETURN k ORDER BY size([x IN coalesce(l, []) WHERE x IS NOT NULL])").sql
        assertTrue(coalesced.contains("PASSING x6_r.n - 1"), coalesced)
        assertFalse(coalesced.contains("JSON_TABLE(w4.c2"), coalesced)
        // A list of lists of lists, or of maps, of temporal values, as collect(DISTINCT …) gathers it.
        for (deep in deepRepros) {
            val sql = OracleCypherCompiler("g").compile(deep).sql
            assertTrue(sql.contains("PASSING e_r.n - 1"), sql)
            assertFalse(sql.contains(Regex("JSON_TABLE\\(w\\d+\\.")), sql)
        }
        // A list of maps of them unwound, and such a list taken back out of a map.
        for (unwound in listOf("$repro UNWIND [x IN l | {t: x}] AS m RETURN count(m.t)", "$repro WITH k, {l: l} AS m UNWIND m.l AS t RETURN count(t)")) {
            val sql = OracleCypherCompiler("g").compile(unwound).sql
            assertTrue(sql.contains(Regex("PASSING u\\d+_r\\.n - 1")), sql)
        }
        // Not a list of lists of lists of other values, a coalesce, a collect, a UNION or a map of them.
        val numbers = "UNWIND [0, 1] AS k WITH k, [i IN range(1, 10) | i + k] AS l"
        for (other in listOf(
            "$numbers RETURN collect(DISTINCT [l, l])",
            "$numbers RETURN size([x IN coalesce(l, []) WHERE x > 1])",
            "$numbers RETURN size([x IN coalesce(l, [1, 2]) WHERE x > 1])",
            "$numbers UNWIND l AS x WITH k, collect(x) AS c RETURN size([y IN c WHERE y > 1])",
            "$numbers WITH k, [x IN l | {a: x, b: 'z'}] AS ms RETURN size([m IN ms WHERE m.a > 1])",
            "$numbers WITH k, {l: l, n: 1} AS m RETURN size([x IN m.l WHERE x > 1])",
            "CALL { RETURN [1, 2] AS l UNION RETURN [3, 4] AS l } RETURN size([x IN l WHERE x > 1])",
        )) {
            val sql = OracleCypherCompiler("g").compile(other).sql
            assertFalse(sql.contains("PASSING"), sql)
        }
    }

    @Test
    fun `on Oracle an item a quantifier takes, a coalesce, CASE or reduce that may hold a temporal value, and a map of them and its keys, are read item by item too`() {
        for (cypher in mayHoldRepros) {
            val sql = OracleCypherCompiler("g").compile(cypher).sql
            assertTrue(sql.contains("PASSING"), sql)
            assertFalse(sql.contains(Regex("JSON_TABLE\\((w|q)\\d+\\.(c|item)")), sql)
        }
        val keys = OracleCypherCompiler("g").compile(keyed).sql
        assertTrue(keys.contains("PASSING dg_r.n - 1"), keys)
        assertFalse(keys.contains("JSON_TABLE((SELECT JSON_DATAGUIDE"), keys)
        // Worked out by the lateral view itself: a subquery in its select list is worked out once, for every row (#414).
        assertTrue(keys.contains("LATERAL (SELECT /*+ NO_MERGE */ JSON_DATAGUIDE("), keys)
        assertFalse(keys.contains("(SELECT JSON_DATAGUIDE"), keys)
    }

    @Test
    fun `on Oracle a list of temporal values a WITH takes out of an unwound map is read item by item too`() {
        // A property of an unwound item held no temporal values deeper, and such a list was read with JSON_TABLE again (#369).
        // Zoned values and durations, and a list with nulls among them, as well.
        val others = listOf("datetime('2024-03-15T10:00:00+02:00') + duration({minutes: i})", "duration({minutes: i})", "CASE i WHEN 1 THEN null ELSE date() END")
            .map { "UNWIND [0, 1] AS k WITH k, [{l: [i IN range(1, 10) | $it]}] AS ms UNWIND ms AS t WITH k, t.l AS l RETURN size([x IN l WHERE x IS NOT NULL])" }
        for (cypher in unwoundRepros + others) {
            val sql = OracleCypherCompiler("g").compile(cypher).sql
            assertTrue(sql.contains(Regex("PASSING x\\d+_r\\.n - 1")), sql)
            assertFalse(sql.contains(Regex("JSON_TABLE\\((w|q|u)\\d+\\.(c|item)")), sql)
        }
        // A list of other values out of an unwound map is still read with JSON_TABLE, and the value is read as #344 reads it.
        val numbers = OracleCypherCompiler("g").compile("UNWIND [0, 1] AS k WITH k, [{l: range(1, 10)}] AS ms UNWIND ms AS t WITH k, t.l AS l RETURN size([x IN l WHERE x > 1])").sql
        assertFalse(numbers.contains("PASSING"), numbers)
        for (cypher in listOf(unwoundRepros[0], "UNWIND [{a: 'x', l: [date()]}] AS t WITH t.a AS a RETURN a")) {
            val sql = OracleCypherCompiler("g").compile(cypher).sql
            assertTrue(sql.contains("JSON(JSON_QUERY(JSON_SERIALIZE("), sql)
        }
    }
}

/**
 * #331's list in a map, unwound, and taken out by a `WITH` (#369): from a map, one the list was put in after it was worked out, a map
 * two levels down, a list of two maps, and after a write.
 */
private val unwoundRepros = listOf(
    "UNWIND [0, 1] AS k WITH k, [{l: $list}] AS ms UNWIND ms AS t WITH k, t.l AS l RETURN size([x IN l WHERE x IS NOT NULL])",
    "$repro UNWIND [{l: l}] AS t WITH k, t.l AS l RETURN size([x IN l WHERE x IS NOT NULL])",
    "UNWIND [0, 1] AS k WITH k, [{m: {l: $list}}] AS ms UNWIND ms AS t WITH k, t.m AS m WITH k, m.l AS l RETURN size([x IN l WHERE x IS NOT NULL])",
    "UNWIND [0, 1] AS k WITH k, [{l: $list}, {l: $list}] AS ms UNWIND ms AS t WITH k, t.l AS l RETURN size([x IN l WHERE x IS NOT NULL])",
    "UNWIND [0, 1] AS k WITH k, [{l: $list}] AS ms UNWIND ms AS t CREATE (:CyUnwoundTemporals) WITH k, t.l AS l RETURN size([x IN l WHERE x IS NOT NULL])",
)

/** Statements that collect, once each, lists that hold #331's temporal values two levels down, or in maps: see #347. */
private val deepRepros = listOf(
    "$repro RETURN collect(DISTINCT [l, l])",
    "$repro RETURN collect(DISTINCT [x IN l | [x]])",
    "$repro RETURN collect(DISTINCT [x IN l | [x, null]])",
    "$repro WITH k, [l, l] AS ll RETURN collect(DISTINCT ll)",
    "$repro RETURN collect(DISTINCT [x IN l | {t: x}])",
)

/**
 * Statements whose lists may hold #331's temporal values: a list of lists of them, an item of which a quantifier or reduce takes,
 * a list worked out item by item with a coalesce or a CASE that is a temporal value or a number, a list a reduce gives of them,
 * and a list of them at a map's key that a temporal value's field is named for.
 */
private val mayHoldRepros = listOf(
    "$repro WITH k, [l, l] AS ll RETURN [k, any(x IN ll WHERE size([y IN x WHERE y IS NOT NULL]) > 9)] ORDER BY k",
    "$repro WITH k, [l, l] AS ll RETURN reduce(s = 0, x IN ll | s + size([y IN x WHERE y IS NOT NULL])) / 2",
    "UNWIND [0, 1] AS k WITH k, [i IN range(1, 10) | coalesce(CASE WHEN k > 5 THEN k END, localdatetime('2024-03-15T10:00:00') + duration({minutes: i}))] AS l " +
        "RETURN size([x IN l WHERE x IS NOT NULL])",
    "UNWIND [0, 1] AS k WITH k, [i IN range(1, 10) | CASE WHEN k > 5 THEN k ELSE localdatetime('2024-03-15T10:00:00') + duration({minutes: i}) END] AS l " +
        "RETURN size([x IN l WHERE x IS NOT NULL])",
    "$repro WITH k, reduce(acc = [], x IN l | acc + [x]) AS m RETURN size([y IN m WHERE y IS NOT NULL])",
    "$repro WITH k, {minute: l} AS m RETURN size([y IN m.minute WHERE y IS NOT NULL])",
    "$repro WITH k, [{minute: l}] AS ms RETURN size([y IN ms[0].minute WHERE y IS NOT NULL])",
)

/** #331's list: worked out item by item with temporal arithmetic, by a comprehension. */
private const val list = "[i IN range(1, 10) | localdatetime('2024-03-15T10:00:00') + duration({minutes: i})]"

/** #331's statement: its list, on each of two rows. */
private const val repro = "UNWIND [0, 1] AS k WITH k, $list AS l"

/** #331's list in a map whose keys are taken: Oracle reads them from the map's data guide. */
private const val keyed = "$repro WITH k, {l: l} AS m RETURN size(keys(m))"

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
    fun `a coalesce of such a list, and a list of lists of them or of maps of them, are read in moments`() {
        // 21 to 106 seconds each, before #347.
        assertEquals(listOf("[0,10]", "[1,10]"), rows("$repro RETURN [k, size([x IN coalesce(l, []) WHERE x IS NOT NULL])] ORDER BY k"))
        assertEquals(setOf("0", "1"), rows("$repro RETURN k ORDER BY size([x IN coalesce(l, []) WHERE x IS NOT NULL])").toSet())
        for (deep in deepRepros) {
            val row = rows(deep).single()
            assertTrue(row.startsWith("[[") && row.contains("2024-03-15T10:10:00"), row)
        }
        assertEquals(listOf("[1,2,10]"), rows("$repro WITH collect(DISTINCT [l, l]) AS ls RETURN [size(ls), size(ls[0]), size(ls[0][0])]"))
        assertEquals(listOf("10"), rows("$repro WITH k, [x IN l | {t: x}] AS ms WHERE k = 0 UNWIND ms AS m RETURN count(m.t)"))
        assertEquals(listOf("10"), rows("$repro WITH k, {l: l} AS m WHERE k = 0 UNWIND m.l AS t RETURN count(t)"))
        assertEquals(listOf("[0,true]", "[1,true]"), rows(mayHoldRepros[0]))
        for (cypher in mayHoldRepros.drop(1)) assertEquals(listOf("10", "10"), rows(cypher))
        assertEquals(listOf("1", "1"), rows(keyed))
    }

    @Test
    fun `a list of temporal values a WITH takes out of an unwound map is read in moments`() {
        // No answer within 30 seconds before #369; the session had to be killed.
        for (cypher in unwoundRepros.dropLast(2)) assertEquals(listOf("10", "10"), rows(cypher))
        assertEquals(listOf("10", "10", "10", "10"), rows(unwoundRepros[3]))
        // After a write, rolled back with the session's work.
        assertEquals(listOf("10", "10"), rows(unwoundRepros[4]))
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
        val compiled = OracleCypherCompiler("cypher").compile(cypher)
        val positional = SqlParameters.positional(compiled.sql)
        val connection = OracleTestDatabase.provider.nativeDriver.connection
        // A statement that writes is a block that hands its rows back, and what it writes is rolled back.
        connection.autoCommit = false
        val sid = connection.createStatement().use { it.executeQuery("SELECT SYS_CONTEXT('USERENV', 'SID') FROM dual").use { r -> r.next(); r.getString(1) } }
        val running = executor.submit<List<String>> {
            connection.prepareStatement(positional.statement).use { statement ->
                positional.names.forEachIndexed { index, name -> statement.setObject(index + 1, compiled.parameters[name]) }
                if (statement.execute() || statement.moreResults) {
                    statement.resultSet.use { r -> generateSequence { if (r.next()) r.getString(1) else null }.toList() }
                } else {
                    emptyList()
                }
            }
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
            runCatching { connection.rollback() }
            runCatching { connection.close() }
        }
    }
}
