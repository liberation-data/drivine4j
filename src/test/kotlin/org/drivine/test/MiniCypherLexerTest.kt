package org.drivine.test

import org.drivine.test.MiniCypherLexer.Kind
import org.drivine.test.MiniCypherLexer.Token
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How a fixture's text is cut into tokens, and what cannot be cut. */
class MiniCypherLexerTest {

    private fun tokens(text: String): List<Token> = MiniCypherLexer(text).tokens()

    /** The tokens of [text] but the closing one, each as its kind and text. */
    private fun cut(text: String): List<Pair<Kind, String>> = tokens(text).dropLast(1).map { it.kind to it.text }

    private fun refused(text: String): String = assertFailsWith<IllegalArgumentException> { tokens(text) }.message!!

    @Test
    fun `text with nothing in it is the closing token alone`() {
        assertEquals(listOf(Token(Kind.END, "", 0)), tokens(""))
        assertEquals(listOf(Token(Kind.END, "", 3)), tokens(" \n\t"))
    }

    @Test
    fun `each token keeps where it stood, and space between tokens is dropped`() {
        assertEquals(
            listOf(
                Token(Kind.WORD, "MATCH", 0),
                Token(Kind.SYMBOL, "(", 6),
                Token(Kind.WORD, "n", 7),
                Token(Kind.SYMBOL, ")", 8),
                Token(Kind.WORD, "RETURN", 12),
                Token(Kind.WORD, "n", 19),
                Token(Kind.END, "", 20),
            ),
            tokens("MATCH (n)\n  RETURN n"),
        )
    }

    @Test
    fun `a word is letters, digits and underscores, and a keyword is a word like any other`() {
        assertEquals(listOf(Kind.WORD to "WHERE", Kind.WORD to "_a1", Kind.WORD to "été", Kind.WORD to "toString"), cut("WHERE _a1 été toString"))
    }

    @Test
    fun `a name in backticks holds any character, and is no word`() {
        assertEquals(listOf(Kind.QUOTED_NAME to "a b.c", Kind.QUOTED_NAME to "MATCH"), cut("`a b.c` `MATCH`"))
        val quoted = tokens("`MATCH`")[0]
        assertFalse(quoted.isWord("MATCH"), "a quoted name is never a keyword")
        assertTrue(quoted.isName)
    }

    @Test
    fun `a string's text is the string, its quotes gone and its escapes read`() {
        assertEquals(listOf(Kind.STRING to "it's"), cut("""'it\'s'"""))
        assertEquals(listOf(Kind.STRING to "say \"hi\""), cut(""""say \"hi\"""""))
        assertEquals(listOf(Kind.STRING to "a'b"), cut(""""a'b""""))
        assertEquals(listOf(Kind.STRING to "a\\b\n\t\r\b\u000c"), cut("""'a\\b\n\t\r\b\f'"""))
        assertEquals(listOf(Kind.STRING to "A é"), cut("""'A é'"""))
        assertEquals(listOf(Kind.STRING to ""), cut("''"))
        assertEquals(listOf(Kind.STRING to "MATCH (n) -- not read"), cut("'MATCH (n) -- not read'"))
    }

    @Test
    fun `a number is its digits with any point and exponent, and has no sign`() {
        assertEquals(listOf(Kind.NUMBER to "42", Kind.NUMBER to "3.0", Kind.NUMBER to "1e3", Kind.NUMBER to "2.5E2"), cut("42 3.0 1e3 2.5E2"))
        assertEquals(listOf(Kind.SYMBOL to "-", Kind.NUMBER to "1"), cut("-1"))
    }

    @Test
    fun `digits written as a number are a Long when whole and a Double when not`() {
        assertEquals(42L, numberOf("42"))
        assertEquals(-42L, numberOf("-42"))
        assertEquals(3.0, numberOf("3.0"))
        assertEquals(1000.0, numberOf("1e3"))
        assertEquals(null, numberOf("1.2.3"))
        assertEquals(null, numberOf("-"))
    }

    @Test
    fun `a comparison of two characters is one symbol, and an arrow is not`() {
        assertEquals(listOf("<>", "<=", ">=", "<", ">", "="), cut("<> <= >= < > =").map { it.second })
        // The parser tells an arrow from a comparison with a negative number by where it stands.
        assertEquals(listOf("<", "-", "[", "]", "-", ">"), cut("<-[]->").map { it.second })
        assertEquals(listOf(Kind.WORD to "a", Kind.SYMBOL to "<", Kind.SYMBOL to "-", Kind.NUMBER to "1"), cut("a<-1"))
    }

    @Test
    fun `a character with no meaning to the fixtures is a symbol, left to the parser to refuse where it stands`() {
        assertEquals(listOf(Kind.WORD to "a", Kind.SYMBOL to "*", Kind.SYMBOL to "|", Kind.SYMBOL to "%", Kind.SYMBOL to ";"), cut("a * | % ;"))
    }

    @Test
    fun `a pattern is cut into its brackets, names and literals`() {
        assertEquals(
            listOf("(", "a", ":", "Human", "{", "id", ":", "ada", ",", "n", ":", "$", "n", "}", ")", "-", "[", ":", "KNOWS", "]", "-", ">", "(", ")"),
            cut("(a:Human {id: 'ada', n: \$n})-[:KNOWS]->()").map { it.second },
        )
    }

    @Test
    fun `a string with no closing quote is refused at its opening quote`() {
        val message = refused("RETURN 'abc")
        assertContains(message, "Expected the end of a string at 7: 'abc")
        assertContains(message, "in: RETURN 'abc")
    }

    @Test
    fun `an escape Cypher does not have is refused at its backslash`() {
        assertContains(refused("""RETURN 'a\qb'"""), """Expected a string escape at 9: \qb'""")
        assertContains(refused("""RETURN 'a\u00zz'"""), """Expected four hex digits at 9: \u00zz'""")
        assertContains(refused("""RETURN 'a\u12"""), "Expected four hex digits at 9")
        assertContains(refused("RETURN 'a\\"), "Expected an escape at 9")
    }

    @Test
    fun `a quoted name with no closing backtick is refused where it opens`() {
        assertContains(refused("MATCH (n) RETURN n.`a b"), "Expected the end of a quoted name at 19: `a b")
    }

    @Test
    fun `digits that are no number are refused where they open`() {
        assertContains(refused("RETURN 1.2.3"), "Expected a number at 7: 1.2.3")
        assertContains(refused("RETURN [1, 2e]"), "Expected a number at 11: 2e]")
    }
}
