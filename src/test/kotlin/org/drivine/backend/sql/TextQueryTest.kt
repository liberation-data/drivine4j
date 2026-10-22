package org.drivine.backend.sql

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

    @Test
    fun `a backslash before a sign makes the sign text, so the word is neither required nor excluded`() {
        val query = TextQuery.parse("""graph \+kotlin \-python""")

        assertEquals(listOf(word("graph"), word("kotlin"), word("python")), query.scored)
        assertTrue(query.required.isEmpty() && query.excluded.isEmpty())
    }

    @Test
    fun `a backslash before a closing star makes the star text, so the word is matched whole`() {
        val query = TextQuery.parse("""pre\*""")

        assertEquals(listOf(word("pre")), query.scored)
        assertEquals("({pre})", query.oracleText)
    }

    @Test
    fun `a star after a backslash that is itself text is still the start of a word`() {
        // Two backslashes are one backslash of text, so the star after them is not escaped.
        assertEquals("(pre%)", TextQuery.parse("""pre\\*""").oracleText)
    }

    @Test
    fun `a backslash before a colon keeps what stands before the colon, where a bare colon makes it a field`() {
        assertEquals("({https example com docs})", TextQuery.parse("""https\:\/\/example.com\/docs""").oracleText)
        assertEquals("({example com docs})", TextQuery.parse("https://example.com/docs").oracleText)
    }

    @Test
    fun `a backslash before a quote keeps the quote from opening or closing a phrase`() {
        assertEquals("({say} ACCUM {hello} ACCUM {world})", TextQuery.parse("""say \"hello world\"""").oracleText)
        assertEquals("({say hello now})", TextQuery.parse(""""say \"hello\" now"""").oracleText)
    }

    @Test
    fun `a backslash before a space joins the two words into one term`() {
        assertEquals("({graph data})", TextQuery.parse("""graph\ data""").oracleText)
    }

    @Test
    fun `an operator written with backslashes is text, and holds no word`() {
        val query = TextQuery.parse("""graph \&\& store \! python""")

        assertEquals(listOf(word("graph"), word("store"), word("python")), query.scored)
        assertTrue(query.required.isEmpty() && query.excluded.isEmpty())
    }

    @Test
    fun `a backslash before a letter is text, so it is a gap between two words as it was before`() {
        assertEquals("({foo bar})", TextQuery.parse("""foo\bar""").oracleText)
        assertEquals("({domain user})", TextQuery.parse("""DOMAIN\user""").oracleText)
        // "C:" is a field, so the term is what follows it.
        assertEquals("({users bob})", TextQuery.parse("""C:\Users\bob""").oracleText)
    }

    @Test
    fun `a backslash that ends the query is text`() {
        assertEquals("({graph})", TextQuery.parse("""graph\""").oracleText)
    }

    @Test
    fun `a required word whose punctuation is escaped is required as the phrase of its parts`() {
        val query = TextQuery.parse("""+PN\-4471\-B order""")

        assertEquals("({pn 4471 b} ACCUM {order}) AND {pn 4471 b}", query.oracleText)
        assertEquals(1, query.required.size)
    }

    @Test
    fun `a phrase that only a backslashed quote can close is closed by it`() {
        assertEquals("({c} ACCUM {foo})", TextQuery.parse(""""C:\" foo""").oracleText)
        // The star is not the last character of the phrase, the backslash is.
        assertEquals("({pre} ACCUM {foo})", TextQuery.parse(""""pre*\" foo""").oracleText)
    }

    @Test
    fun `a backslashed quote does not close a phrase when a quote without a backslash stands later`() {
        // The phrase runs to the quote before "D". What is left is a field and a quote of text, which hold no word.
        assertEquals("({c and})", TextQuery.parse(""""C:\" and "D:\"""").oracleText)
    }

    @Test
    fun `a backslash before a line break keeps a phrase one phrase`() {
        assertEquals("({foo bar})", TextQuery.parse("\"foo\\\nbar\"").oracleText)
    }

    @Test
    fun `a star before a line break that ends a phrase is not the end of the phrase`() {
        assertEquals("({graph pre})", TextQuery.parse("\"graph pre*\n\"").oracleText)
    }

    @Test
    fun `an operator before a token that holds no word does not reach the word after it`() {
        val negated = TextQuery.parse("""graph NOT \! python""")
        assertEquals(listOf(word("graph"), word("python")), negated.scored)
        assertTrue(negated.excluded.isEmpty())

        val conjoined = TextQuery.parse("""graph AND \&\& store""")
        assertEquals(listOf(word("graph"), word("store")), conjoined.scored)
        assertTrue(conjoined.required.isEmpty())
    }

    @Test
    fun `a word or a phrase of any length is read`() {
        val word = "a".repeat(200_000)
        assertEquals("({$word})", TextQuery.parse(word).oracleText)
        assertEquals("({$word})", TextQuery.parse("\"$word\"").oracleText)
        assertEquals("({$word})", TextQuery.parse("title:$word").oracleText)
    }

    @Test
    fun `a query of many phrases that never close is read in one pass`() {
        val query = TextQuery.parse("\"graph ".repeat(50_000))

        assertEquals(50_000, query.scored.size)
    }
}
