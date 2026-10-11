package org.drivine.backend.oracle

import org.junit.jupiter.api.Test
import sample.stateless.ClaimView
import kotlin.test.assertEquals

/** A predicate written by hand, with the root's properties written as Cypher writes them, read on Oracle. */
class CypherShorthandTest {

    private val shorthand = CypherShorthand("n", "n_")

    @Test
    fun `a property of the root is read from its document`() {
        assertEquals("JSON_VALUE(n_.props, '$.name') = 'x'", shorthand.rewrite("n.name = 'x'"))
    }

    @Test
    fun `a name in backticks is one key, quoted in the path`() {
        assertEquals("JSON_VALUE(n_.props, '$.\"a.b\"') = 1", shorthand.rewrite("n.`a.b` = 1"))
    }

    @Test
    fun `the row's own columns are the columns`() {
        assertEquals("n_.stamp IS NULL", shorthand.rewrite("n.stamp IS NULL"))
        assertEquals("JSON_EXISTS(n_.props, '$.x')", shorthand.rewrite("JSON_EXISTS(n.props, '$.x')"))
        assertEquals("JSON_SERIALIZE(n_.labels)", shorthand.rewrite("JSON_SERIALIZE(n.labels)"))
        assertEquals("n_.nid > 0", shorthand.rewrite("n.nid > 0"))
    }

    @Test
    fun `text inside a string literal is left as it is`() {
        assertEquals("JSON_VALUE(n_.props, '$.name') = 'n.name'", shorthand.rewrite("n.name = 'n.name'"))
        assertEquals("JSON_VALUE(n_.props, '$.text') = 'it''s n.text, see'", shorthand.rewrite("n.text = 'it''s n.text, see'"))
        assertEquals("'n.name'", shorthand.rewrite("'n.name'"))
    }

    @Test
    fun `another alias is not the root`() {
        assertEquals("other.name = 'x'", shorthand.rewrite("other.name = 'x'"))
        assertEquals("an.name = 'x'", shorthand.rewrite("an.name = 'x'"))
        assertEquals("x.n.name = 'x'", shorthand.rewrite("x.n.name = 'x'"))
    }

    @Test
    fun `a property inside a function is rewritten, a function on the alias is not`() {
        assertEquals("LOWER(JSON_VALUE(n_.props, '$.x')) = 'a'", shorthand.rewrite("LOWER(n.x) = 'a'"))
        assertEquals("n.fn(1) = 2", shorthand.rewrite("n.fn(1) = 2"))
    }

    @Test
    fun `several properties in one predicate are each rewritten`() {
        assertEquals(
            "JSON_VALUE(n_.props, '$.a') = JSON_VALUE(n_.props, '$.b') AND JSON_VALUE(n_.props, '$.c') IS NOT NULL",
            shorthand.rewrite("n.a = n.b AND n.c IS NOT NULL"),
        )
    }

    @Test
    fun `a view's root is written by its field name`() {
        assertEquals("JSON_VALUE(claim_.props, '$.id') = 'c1'", CypherShorthand("claim", "claim_").rewrite("claim.id = 'c1'"))
        val select = OracleSelect(OracleGraph("g"), ClaimView::class.java)
        assertEquals("JSON_VALUE(claim_.props, '$.id') = 'c1' AND people.name = 'x'", select.written("claim.id = 'c1' AND people.name = 'x'"))
    }

    @Test
    fun `a predicate with nothing of the root in it is unchanged`() {
        assertEquals("1 = 1", shorthand.rewrite("1 = 1"))
        assertEquals("", shorthand.rewrite(""))
    }
}
