package org.drivine.backend.oracle

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextQueryTest {

    private fun word(text: String) = "(^|[^[:alnum:]_])$text($|[^[:alnum:]_])"

    @Test
    fun `words score, and each is matched whole and in lower case`() {
        val query = TextQuery.parse("Graph databases")

        assertEquals(listOf(word("graph"), word("databases")), query.scored)
        assertTrue(query.required.isEmpty() && query.excluded.isEmpty())
    }

    @Test
    fun `a plus or an AND makes a word one a node must hold, a minus or a NOT one it must not`() {
        val query = TextQuery.parse("+kotlin graph AND store -python NOT rust")

        assertEquals(listOf(word("kotlin"), word("graph"), word("store")), query.scored)
        assertEquals(listOf(word("kotlin"), word("store")), query.required)
        assertEquals(listOf(word("python"), word("rust")), query.excluded)
    }

    @Test
    fun `a quoted phrase is its words in turn, a star the start of a word, and a field's word the word`() {
        val query = TextQuery.parse("\"graph data\" data* name:Ada")

        assertEquals(listOf("(^|[^[:alnum:]_])graph[^[:alnum:]_]+data($|[^[:alnum:]_])", "(^|[^[:alnum:]_])data", word("ada")), query.scored)
    }

    @Test
    fun `what is not a word is not looked for, so it cannot be read as an expression`() {
        assertEquals(listOf(word("c")), TextQuery.parse("c++ (.*)").scored)
        assertFalse(TextQuery.parse("a.b").scored.single().contains("."), "a dot between words is a gap, not any character")
    }

    @Test
    fun `the same query for Oracle Text accumulates what scores, requires and takes away, each word in braces`() {
        assertEquals("({graph} ACCUM {databases})", TextQuery.parse("Graph databases").oracleText)
        assertEquals("({kotlin} ACCUM {graph} ACCUM {store}) AND {kotlin} AND {store} NOT {python} NOT {rust}", TextQuery.parse("+kotlin graph AND store -python NOT rust").oracleText)
        assertEquals("({graph data} ACCUM data% ACCUM {ada})", TextQuery.parse("\"graph data\" data* name:Ada").oracleText)
        // A word that is one of Oracle Text's own is still a word.
        assertEquals("({near} ACCUM {about})", TextQuery.parse("near about").oracleText)
    }

    @Test
    fun `a query with no word to look for is refused`() {
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("-only") }
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("  ") }
    }

    @Test
    fun `the symbols for AND, OR and NOT read as the words do`() {
        val query = TextQuery.parse("graph && store || index ! python")

        assertEquals(listOf(word("graph"), word("store"), word("index")), query.scored)
        assertEquals(listOf(word("store")), query.required)
        assertEquals(listOf(word("python")), query.excluded)
    }

    @Test
    fun `an operator with nothing after it is nothing`() {
        val query = TextQuery.parse("graph AND")

        assertEquals(listOf(word("graph")), query.scored)
        assertTrue(query.required.isEmpty() && query.excluded.isEmpty())
        assertEquals("({graph})", query.oracleText)
    }

    @Test
    fun `an empty phrase and a star alone hold no word, and are refused`() {
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("\"\"") }
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("*") }
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("title:\"\"") }
    }

    @Test
    fun `a field scoped to a phrase is the one phrase, and a field scoped to a word the word`() {
        val phrase = TextQuery.parse("title:\"graph data\"")
        assertEquals(listOf("(^|[^[:alnum:]_])graph[^[:alnum:]_]+data($|[^[:alnum:]_])"), phrase.scored)
        assertEquals("({graph data})", phrase.oracleText)

        val word = TextQuery.parse("name:Ada")
        assertEquals(listOf(word("ada")), word.scored)
        assertEquals("({ada})", word.oracleText)
    }

    @Test
    fun `a star in the middle of a word is a gap between two words, not a wildcard`() {
        val query = TextQuery.parse("foo*bar")

        assertEquals(listOf("(^|[^[:alnum:]_])foo[^[:alnum:]_]+bar($|[^[:alnum:]_])"), query.scored)
        assertEquals("({foo bar})", query.oracleText)
    }

    @Test
    fun `for Oracle Text a required word is both accumulated and conjoined, an excluded one taken away, a prefix a percent`() {
        assertEquals("({kotlin}) AND {kotlin}", TextQuery.parse("+kotlin").oracleText)
        assertEquals("({kotlin}) NOT {python}", TextQuery.parse("kotlin -python").oracleText)
        assertEquals("({kotlin}) NOT {python} NOT {rust}", TextQuery.parse("kotlin NOT python !rust".replace("!rust", "! rust")).oracleText)
        assertEquals("(pre%)", TextQuery.parse("pre*").oracleText)
        assertEquals("({graph} pre%)", TextQuery.parse("\"graph pre*\"").oracleText)
    }
}
