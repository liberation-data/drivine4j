package org.drivine.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The columns read from a statement: those the store names, or none, or null where they can't be read. */
class ColumnsTest {

    @Test
    fun `the last RETURN names the columns, by alias or by the expression's text as written`() {
        assertEquals(listOf("a", "b"), Columns.of("MATCH (n) RETURN n.x AS a, n.y AS b"))
        assertEquals(listOf("1+1", "toUpper( 'a' )"), Columns.of("RETURN 1+1, toUpper( 'a' )"))
        assertEquals(listOf("x"), Columns.of("RETURN DISTINCT 1 AS x"))
        assertEquals(listOf("m"), Columns.of("RETURN {a: 1} AS m"))
        assertEquals(listOf("{a: 1}"), Columns.of("RETURN {a: 1}"))
        assertEquals(listOf("x"), Columns.of("CALL { RETURN 1 AS x } RETURN x ORDER BY x"))
    }

    @Test
    fun `a UNION's columns are its first part's, which every part shares`() {
        assertEquals(listOf("a", "b"), Columns.of("RETURN 1 AS a, 2 AS b UNION ALL RETURN 3 AS a, 4 AS b"))
    }

    @Test
    fun `a statement that returns nothing has no columns`() {
        assertEquals(emptyList(), Columns.of("CREATE (:P)"))
        assertEquals(emptyList(), Columns.of("MATCH (n) DETACH DELETE n"))
    }

    @Test
    fun `RETURN star, a statement that ends in a call, and one the reader can't read have columns it can't name`() {
        assertNull(Columns.of("UNWIND [1] AS x RETURN *"))
        assertNull(Columns.of("CALL { CREATE (:P) }"))
        assertNull(Columns.of("SHOW DATABASES"))
        assertEquals(listOf(Columns.UNNAMED), Columns.guessed(listOf(1L)))
        assertEquals(emptyList(), Columns.guessed(emptyList()))
    }
}
