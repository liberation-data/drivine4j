package org.drivine.backend.sql

import org.drivine.backend.sqlite.SqlitePatterns
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
    fun `each scoring term gives the words a node that matches it holds, and no term that only excludes does`() {
        val query = TextQuery.parse("\"Graph data\" +snake_case data* -python NOT rust")

        assertEquals(listOf(listOf("graph", "data"), listOf("snake_case"), listOf("data")), query.scoredWords)
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

    /** How often [pattern] matches in [text], each match after the last one ends, as Postgres's and SQLite's counts are. */
    private fun count(pattern: String, text: String): Int {
        val matcher = SqlitePatterns().of(pattern).matcher(text)
        var count = 0
        while (matcher.find()) count++
        return count
    }

    /** How often the term [query] occurs in [text], counted as Postgres and SQLite count it. */
    private fun counted(query: String, text: String) = count(TextQuery.lookingAhead(TextQuery.parse(query).scored.single()), text)

    /** How often the term [query] occurs in [text], counted as Oracle counts it (OracleSelect.matching). */
    private fun countedByOracle(query: String, text: String): Int {
        val term = TextQuery.parse(query).scored.single()
        return TextQuery.counted(term)?.let { (ended, continued) -> count(ended, text) - (continued?.let { count(it, text) } ?: 0) }
            ?: count(term, SqlitePatterns().of(TextQuery.SEPARATOR).matcher(text).replaceAll("\$1\$1"))
    }

    @Test
    fun `a term's trailing boundary is looked ahead to, and a prefix term, which has none, is as it was`() {
        assertEquals("(^|[^[:alnum:]_])kiwi(?=\$|[^[:alnum:]_])", TextQuery.lookingAhead(word("kiwi")))
        assertEquals("(^|[^[:alnum:]_])kiw", TextQuery.lookingAhead(TextQuery.parse("kiw*").scored.single()))
    }

    @Test
    fun `a term written again at once is counted each time, wherever it stands and whatever is around it`() {
        val texts = mapOf(
            "kiwi kiwi kiwi" to 3,
            "kiwi,kiwi.kiwi!" to 3,
            "(kiwi)" to 1,
            "kiwi" to 1,
            "kiwis kiwi_kiwi kiwi2 akiwi" to 0,
            "kiwi\nkiwi\n" to 2,
        )
        texts.forEach { (text, n) ->
            assertEquals(n, counted("kiwi", text), text)
            assertEquals(n, countedByOracle("kiwi", text), text)
        }
    }

    @Test
    fun `a phrase written again at once is counted each time, and its gap may be any punctuation`() {
        val texts = mapOf("of soup of soup" to 2, "of  soup, of -- soup." to 2, "of soups of soup" to 1, "of soup_ of soup" to 1, "of soup_of soup" to 0)
        texts.forEach { (text, n) ->
            assertEquals(n, counted("\"of soup\"", text), text)
            assertEquals(n, countedByOracle("\"of soup\"", text), text)
        }
    }

    @Test
    fun `a prefix term counts each word it starts, its last word the start of one`() {
        assertEquals(3, counted("kiw*", "kiwi kiwis kiw akiwi"))
        assertEquals(3, countedByOracle("kiw*", "kiwi kiwis kiw akiwi"))
        assertEquals(2, counted("\"of sou*\"", "of soup of soups"))
        assertEquals(2, countedByOracle("\"of sou*\"", "of soup of soups"))
    }

    @Test
    fun `a word in any script is a word, so a letter after a term in another script goes on with it`() {
        assertEquals(2, counted("café", "café café cafés caféé"))
        assertEquals(2, countedByOracle("café", "café café cafés caféé"))
        assertEquals(2, counted("кот", "кот кот котик"))
        assertEquals(2, countedByOracle("кот", "кот кот котик"))
    }

    @Test
    fun `Oracle counts a term of words each once by two counts, and a phrase that repeats a word in the text with its gaps doubled`() {
        assertEquals("(^|[^[:alnum:]_])kiwi" to "(^|[^[:alnum:]_])kiwi[[:alnum:]_]", TextQuery.counted(word("kiwi")))
        assertEquals("(^|[^[:alnum:]_])kiw" to null, TextQuery.counted(TextQuery.parse("kiw*").scored.single()))
        assertEquals(null, TextQuery.counted(TextQuery.parse("\"la la\"").scored.single()))
        assertEquals(null, TextQuery.counted(TextQuery.parse("\"la di la\"").scored.single()))
        assertEquals("(^|[^[:alnum:]_])of[^[:alnum:]_]+soup", TextQuery.counted(TextQuery.parse("\"of soup\"").scored.single())?.first)
    }

    @Test
    fun `a phrase whose words begin it again counts the same on Oracle, where the word after it goes on`() {
        // "la la" ends at the space before "land", and once; Oracle's count of "la la" ending anywhere took the
        // first, and of it going on into a word took the second, "la la" of "la land", so it counted none.
        assertEquals(1, counted("\"la la\"", "la la land"))
        assertEquals(1, countedByOracle("\"la la\"", "la la land"))
    }

    @Test
    fun `a phrase whose last word starts its first counts the same on Oracle, where the word after it goes on`() {
        // Its words are each once, yet "kiwis kiwi" ending anywhere took the first two words, and going on into a word
        // took them too, so the two counts counted none.
        assertEquals(null, TextQuery.counted(TextQuery.parse("\"kiwis kiwi\"").scored.single()))
        assertEquals(1, counted("\"kiwis kiwi\"", "kiwis kiwis kiwi"))
        assertEquals(1, countedByOracle("\"kiwis kiwi\"", "kiwis kiwis kiwi"))
        assertEquals(1, countedByOracle("\"kiwis bird kiwi\"", "kiwis bird kiwis bird kiwi"))
    }

    @Test
    fun `a phrase of distinct words, whose last doesn't start its first, counts the same on Oracle by its two counts`() {
        // No occurrence of such a term begins inside another: one would begin at a later word of it, which would be its first.
        val cases = listOf(
            // Its first word starts its last.
            "\"kiwi kiwis\"" to "kiwi kiwi kiwis kiwis kiwi kiwisx kiwi kiwis",
            // Its first word starts a middle one.
            "\"ki kiwi bird\"" to "ki ki kiwi bird ki kiwi birds ki kiwi bird ki kiwi bird",
            // Words of digits and underscores, which go on as letters do.
            "\"a1 a12\"" to "a1 a1 a12 a1 a12 a1 a123 a1 a12_",
            "\"_a a_b\"" to "_a _a a_b _a a_b _a a_bc",
            "a_1" to "a_1 a_1 a_1a a_1_ a_1",
        )
        cases.forEach { (query, text) ->
            assertTrue(TextQuery.counted(TextQuery.parse(query).scored.single()) != null, query)
            assertEquals(counted(query, text), countedByOracle(query, text), "$query in $text")
        }
        assertEquals(2, counted("\"kiwi kiwis\"", "kiwi kiwi kiwis kiwis kiwi kiwisx kiwi kiwis"))
        assertEquals(3, countedByOracle("\"ki kiwi bird\"", "ki ki kiwi bird ki kiwi birds ki kiwi bird ki kiwi bird"))
    }

    @Test
    fun `a word holding a number that isn't a digit is counted on Oracle by its one expression, so no occurrence is lost`() {
        // "²" is a word's to the query, not to [[:alnum:]]: "b²b" in "b²b²bx" begins inside the first, and the two counts
        // took the first ending and the second going on, so counted none.
        val term = TextQuery.parse("b²b").scored.single()
        assertEquals(term to null, TextQuery.counted(term))
        assertEquals(1, counted("b²b", "b²b²bx"))
        assertEquals(1, countedByOracle("b²b", "b²b²bx"))
    }
}
