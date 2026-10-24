package org.drivine.sqlite

import org.drivine.DrivineException
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The functions compiled Cypher calls on SQLite, each asked directly in SQL: what each gives, and what it gives of
 * null. The cross-engine Cypher specs ask them through the statements they compile.
 */
class SqliteCypherFunctionsTest {

    private val pm = SqliteTestDatabase.pm

    private fun row(sql: String): Any? = pm.query(QuerySpecification.withStatement(sql)).single()

    @Test
    fun `drivine_equal compares as Cypher does, and null with anything is null`() {
        assertEquals(
            listOf(1L, 1L, 0L, 1L, 0L, 0L),
            row(
                "SELECT drivine_equal('1', '1.0'), drivine_equal('{\"a\":1,\"b\":[1,2]}', '{\"b\":[1,2.0],\"a\":1}'), drivine_equal('[1,2]', '[2,1]'), " +
                    "drivine_equal('\"x\"', '\"x\"'), drivine_equal('true', '1'), drivine_equal('\"7\"', '7')"
            ),
        )
        assertEquals(listOf(null, null), row("SELECT drivine_equal('null', '1'), drivine_equal(NULL, '1')"))
    }

    @Test
    fun `drivine_contains holds a map's every entry with an equal value`() {
        assertEquals(
            listOf(1L, 0L, 0L, null),
            row(
                "SELECT drivine_contains('{\"a\":1,\"b\":2}', '{\"a\":1.0}'), drivine_contains('{\"a\":1}', '{\"a\":1,\"c\":3}'), " +
                    "drivine_contains('[1]', '{}'), drivine_contains(NULL, '{}')"
            ),
        )
    }

    @Test
    fun `drivine_add sums, joins lists, takes a null into a list, and writes texts and numbers one after the other`() {
        assertEquals(
            listOf("3", "3.5", "[1,2,3]", "[1,null]", "[0,1]", "\"a1\"", "\"1.5b\"", null, null),
            row(
                "SELECT drivine_add('1', '2'), drivine_add('1', '2.5'), drivine_add('[1]', '[2,3]'), drivine_add('[1]', NULL), drivine_add('0', '[1]'), " +
                    "drivine_add('\"a\"', '1'), drivine_add('1.5', '\"b\"'), drivine_add('true', '\"b\"'), drivine_add('1', NULL)"
            ),
        )
        assertEquals("18446744073709551614", row("SELECT drivine_add('9223372036854775807', '9223372036854775807')"), "past a long, still whole")
    }

    @Test
    fun `drivine_canonical puts a map's keys in order at any depth`() {
        assertEquals(listOf("{\"a\":[{\"x\":1,\"y\":2}],\"b\":1}", null), row("SELECT drivine_canonical('{\"b\":1,\"a\":[{\"y\":2,\"x\":1}]}'), drivine_canonical(NULL)"))
    }

    @Test
    fun `the text functions raise, split, reverse and make a UUID`() {
        assertEquals(
            listOf("ÉCOLE", "[\"a\",\"\",\"b\",\"\"]", "[\"a\",\"😀\"]", "[\"\"]", "😀ba", null),
            row("SELECT drivine_upper('école'), drivine_split('a,,b,', ','), drivine_split('a😀', ''), drivine_split('', ','), drivine_reverse('ab😀'), drivine_split(NULL, ',')"),
        )
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}").matches(row("SELECT drivine_uuid()") as String))
    }

    @Test
    fun `a regular expression's group is read, and its matches replaced naming groups with a backslash`() {
        assertEquals(
            listOf("+02:00", null, "+02:00", "a\$1b"),
            row(
                "SELECT drivine_regex_group('10:00+02:00', '([+-][0-9]{2}:?[0-9]{2})$', 1), drivine_regex_group('10:00', 'Z$', 1), " +
                    "drivine_regexp_replace('+0200', '^([+-][0-9]{2})([0-9]{2})$', '\\1:\\2'), drivine_regexp_replace('ab', 'a', 'a\$1')"
            ),
        )
    }

    @Test
    fun `drivine_trunc cuts to a whole number, and drivine_to_char writes a timestamp or a number in a pattern`() {
        assertEquals(listOf(2L, -2L, 7L, null), row("SELECT drivine_trunc(2.9), drivine_trunc(-2.9), drivine_trunc(7), drivine_trunc(NULL)"))
        // A real number a unit of its last place short of a whole number is that number; a fraction past that is cut off.
        assertEquals(listOf(33001000L, -33001000L, 0L), row("SELECT drivine_trunc(33.001 * 1000000), drivine_trunc(-33.001 * 1000000), drivine_trunc(3599.999999 / 3600)"))
        assertEquals(
            listOf("2024-03-15T10:30:05", "2025", "11", "075", "0042", "2.5", "3.", "-1.25", null),
            row(
                "SELECT drivine_to_char('2024-03-15T10:30:05.000000', 'YYYY-MM-DD\"T\"HH24:MI:SS'), drivine_to_char('2024-12-30T00:00:00.000000', 'IYYY'), " +
                    "drivine_to_char('2024-03-15T00:00:00.000000', 'IW'), drivine_to_char('2024-03-15T00:00:00.000000', 'DDD'), drivine_to_char(42, 'FM0000'), " +
                    "drivine_to_char(2.5, 'FM9990.999'), drivine_to_char(3, 'FM9990.999'), drivine_to_char(-1.25, 'FM9990.999'), drivine_to_char(NULL, 'FM00')"
            ),
        )
    }

    @Test
    fun `drivine_temporal reads, moves, measures and cuts a timestamp`() {
        assertEquals(
            listOf(
                "2024-03-15T00:00:00.000000", "2024-03-15T08:30:00.000000", "1970-01-01T09:00:00.000000", "2024-02-29T10:00:00.000000",
                "2024-03-16T10:00:01.500000", 2L, -0.5, "2024-03-11T00:00:00.000000", 30.25,
            ),
            row(
                "SELECT drivine_temporal('local', '2024-03-15', 'DATE'), drivine_temporal('utc', '2024-03-15T10:30+0200', 'DATE_TIME'), " +
                    "drivine_temporal('utc', '10:00+01:00', 'TIME'), drivine_temporal('add_months', '2024-01-31T10:00:00.000000', 1), " +
                    "drivine_temporal('add_time', '2024-03-15T10:00:00.000000', 1, 1.5), " +
                    "drivine_temporal('months_between', '2024-01-31T00:00:00.000000', '2024-03-31T00:00:00.000000'), " +
                    "drivine_temporal('seconds_between', '2024-03-15T10:00:00.500000', '2024-03-15T10:00:00.000000'), " +
                    "drivine_temporal('truncate', '2024-03-15T10:30:00.000000', 'week'), drivine_temporal('extract', '2024-03-15T10:30:30.250000', 'SECOND')"
            ),
        )
        // A number of months that is no whole number rounds half away from zero, as Postgres casts one.
        assertEquals(
            listOf("2024-03-31T10:00:00.000000", "2023-11-30T10:00:00.000000"),
            row("SELECT drivine_temporal('add_months', '2024-01-31T10:00:00.000000', 1.5), drivine_temporal('add_months', '2024-01-31T10:00:00.000000', -1.5)"),
        )
        assertEquals(null, row("SELECT drivine_temporal('local', NULL, 'DATE')"))
        val refused = assertFailsWith<DrivineException> { row("SELECT drivine_temporal('local', '2024-02-30', 'DATE')") }
        assertTrue("2024-02-30" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `drivine_cypher carries out its steps, and a step that fails undoes the ones before it`() {
        val graph = SqliteTestDatabase.graphManager("cyfunctions")
        SqliteTestDatabase.clear(graph)
        fun steps(vararg steps: String) = "'{\"steps\":[${steps.joinToString(",")}]}'"
        val insert = "{\"op\":\"execute\",\"sql\":\"INSERT INTO cyfunctions_node (labels, props) VALUES (''[]'', ?1) RETURNING nid\",\"variables\":[\"cy_value\"],\"variable\":\"cy_id\"}"
        val set = "{\"op\":\"select\",\"sql\":\"SELECT ''{\\\"n\\\":1}''\",\"variables\":[],\"variable\":\"cy_value\"}"
        val returned = "{\"op\":\"return\",\"sql\":\"SELECT json_quote(?1) AS result\",\"variables\":[\"cy_id\"]}"
        assertEquals("[1]", row("SELECT drivine_cypher(${steps(set, insert, returned)}, '{}')").toString().replace(Regex("[0-9]+"), "1"))
        // A variable that holds a real number is bound as one, and not as its text.
        val real = "{\"op\":\"select\",\"sql\":\"SELECT 1.5\",\"variables\":[],\"variable\":\"cy_value\"}"
        val typed = "{\"op\":\"return\",\"sql\":\"SELECT json_quote(?1) AS result\",\"variables\":[\"cy_value\"]}"
        assertEquals("[1.5]", row("SELECT drivine_cypher(${steps(real, typed)}, '{}')"))
        val raise = "{\"op\":\"raise\",\"message\":\"no\"}"
        val failed = assertFailsWith<DrivineException> { row("SELECT drivine_cypher(${steps(set, insert, raise)}, '{}')") }
        assertTrue("no" in failed.message.orEmpty(), failed.message)
        assertEquals(1L, row("SELECT count(*) FROM cyfunctions_node"), "the failed procedure's insert is undone")
        val unknown = assertFailsWith<DrivineException> { row("SELECT drivine_cypher(${steps(set, insert, "{\"op\":\"jump\"}")}, '{}')") }
        assertTrue("no step jump" in unknown.message.orEmpty(), unknown.message)
        assertEquals(1L, row("SELECT count(*) FROM cyfunctions_node"), "a step it does not know undoes the ones before it")
    }
}
