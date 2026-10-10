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
    fun `a query with no word to look for is refused`() {
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("-only") }
        assertFailsWith<IllegalArgumentException> { TextQuery.parse("  ") }
    }
}
