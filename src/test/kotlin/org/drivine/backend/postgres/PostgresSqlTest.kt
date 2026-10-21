package org.drivine.backend.postgres

import org.drivine.backend.sql.SqlKind
import org.drivine.model.Stamps
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PostgresSqlTest {

    @Test
    fun `a label is written into a JSON literal the index over labels answers, its quotes escaped for JSON and for SQL`() {
        assertEquals("n.labels @> '[\"Person\"]'", PostgresSql.hasLabel("n", "Person"))
        // A test for each label, so a partial index over the nodes of either is seen to hold them.
        assertEquals("n.labels @> '[\"Person\"]' AND n.labels @> '[\"Author\"]'", PostgresSql.hasLabels("n", listOf("Person", "Author")))
        assertEquals("labels @> '[\"Person\"]'", PostgresSql.hasLabel("", "Person"), "the table's own column, as an index names it")
        assertEquals("n.labels @> '[\"O''Brien \\\"x\\\"\"]'", PostgresSql.hasLabel("n", "O'Brien \"x\""))
        assertNull(PostgresSql.hasLabels("n", emptyList()))
    }

    @Test
    fun `a property is read as text, a number only where one is stored, and the stamp from its column`() {
        assertEquals("(n.props ->> 'name')", PostgresSql.property("n", "name"))
        assertEquals("CASE WHEN jsonb_typeof((n.props -> 'age')) = 'number' THEN (n.props ->> 'age')::numeric END", PostgresSql.property("n", "age", SqlKind.NUMBER))
        assertEquals("(n.props ->> 'born')::timestamptz", PostgresSql.property("n", "born", SqlKind.INSTANT))
        assertEquals("n.stamp", PostgresSql.property("n", Stamps.PROPERTY, SqlKind.NUMBER))
        assertEquals("(n.props ->> 'it''s')", PostgresSql.property("n", "it's"))
        assertThrows<IllegalArgumentException> { PostgresSql.property("n", "") }
    }

    @Test
    fun `whether a property is there is asked without a question mark, which JDBC would take for a parameter`() {
        assertEquals("(n.props -> 'note') IS NOT NULL", PostgresSql.hasProperty("n", "note"))
        assertFalse('?' in PostgresSql.hasProperty("n", "note") + PostgresSql.hasLabels("n", listOf("A", "B")) + PostgresSql.values("\$p", SqlKind.TEXT))
    }

    @Test
    fun `a node's document is its properties, its labels and its stamp when it has one`() {
        assertEquals(
            "(n.props || jsonb_build_object('labels', n.labels) || jsonb_strip_nulls(jsonb_build_object('${Stamps.PROPERTY}', n.stamp)))",
            PostgresSql.document("n"),
        )
    }
}
