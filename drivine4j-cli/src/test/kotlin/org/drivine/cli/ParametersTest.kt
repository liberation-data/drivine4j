package org.drivine.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [Assignment.parse] takes the contract's three forms of a parameter, and nothing else. */
class ParametersTest {

    @Test
    fun `NAME arrow EXPRESSION, NAME=EXPRESSION and a map are taken, the spaces around either sign left out`() {
        assertEquals(Assignment.Named("limit", "10"), Assignment.parse("limit => 10"))
        assertEquals(Assignment.Named("limit", "10"), Assignment.parse("limit=>10"))
        assertEquals(Assignment.Named("limit", "10"), Assignment.parse("limit = 10"))
        assertEquals(Assignment.Named("since", "date('2024-01-01')"), Assignment.parse("  since=date('2024-01-01')  "))
        assertEquals(Assignment.Map("{limit: 10, since: date(\"2024-01-01\")}"), Assignment.parse("{limit: 10, since: date(\"2024-01-01\")}"))
    }

    @Test
    fun `a text that starts with a brace is a map, whatever equals sign it holds, and the first equals sign splits the rest`() {
        assertEquals(Assignment.Map("{url: 'a?b=c'}"), Assignment.parse("{url: 'a?b=c'}"))
        // The first = splits, so an expression may hold one, and a => after it is the expression's.
        assertEquals(Assignment.Named("q", "'a=b'"), Assignment.parse("q='a=b'"))
        assertEquals(Assignment.Named("q", "x => y"), Assignment.parse("q = x => y"))
        // A comparison is an expression too: the first = still splits.
        assertEquals(Assignment.Named("b", "1 >= 2"), Assignment.parse("b => 1 >= 2"))
    }

    @Test
    fun `a name in backticks is its text between them`() {
        assertEquals(Assignment.Named("a b", "1"), Assignment.parse("`a b` => 1"))
        assertEquals(Assignment.Named("a`b", "1"), Assignment.parse("`a``b` => 1"))
    }

    @Test
    fun `a text of none of the forms is none`() {
        listOf("y", "", "=> 1", "x =>", "x = ", "1x => 2", "a b => 1", "x.y => 1").forEach { assertNull(Assignment.parse(it), it) }
    }

    @Test
    fun `a parameter's expression is evaluated as RETURN expression`() {
        assertEquals("RETURN 1 + 1 AS value", Assignment.Named("x", "1 + 1").cypher)
        assertEquals("RETURN {a: 1} AS value", Assignment.Map("{a: 1}").cypher)
    }
}
