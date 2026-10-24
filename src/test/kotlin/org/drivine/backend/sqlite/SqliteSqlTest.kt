package org.drivine.backend.sqlite

import org.drivine.backend.sql.SqlKind
import org.drivine.model.Stamps
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteSqlTest {

    @Test
    fun `a label is a test of the labels' text for the label as JSON writes it, its quotes escaped for JSON and for SQL`() {
        assertEquals("instr(n.labels, '\"Person\"') > 0", SqliteSql.hasLabel("n", "Person"))
        // A test for each label, so a partial index over the nodes of either is seen to hold them.
        assertEquals("instr(n.labels, '\"Person\"') > 0 AND instr(n.labels, '\"Author\"') > 0", SqliteSql.hasLabels("n", listOf("Person", "Author")))
        assertEquals("instr(labels, '\"Person\"') > 0", SqliteSql.hasLabel("", "Person"), "the table's own column, as an index names it")
        assertEquals("instr(n.labels, '\"O''Brien \\\"x\\\"\"') > 0", SqliteSql.hasLabel("n", "O'Brien \"x\""))
        assertNull(SqliteSql.hasLabels("n", emptyList()))
    }

    @Test
    fun `a property is read by a quoted path, so a dotted name is one property`() {
        assertEquals("'$.\"metadata.source\"'", SqliteSql.path("metadata.source"))
        assertEquals("'$.\"it''s \\\"x\\\"\"'", SqliteSql.path("it's \"x\""))
        assertThrows<IllegalArgumentException> { SqliteSql.property("n", "") }
    }

    @Test
    fun `a property is read as the text Postgres gives, a number only where one is stored, a moment as nanoseconds, and the stamp from its column`() {
        assertEquals(
            "(CASE json_type(n.props, '$.\"name\"') WHEN 'text' THEN n.props ->> '$.\"name\"' WHEN 'null' THEN NULL ELSE n.props -> '$.\"name\"' END)",
            SqliteSql.property("n", "name"),
        )
        assertEquals("(CASE WHEN json_type(n.props, '$.\"age\"') IN ('integer', 'real') THEN n.props ->> '$.\"age\"' END)", SqliteSql.property("n", "age", SqlKind.NUMBER))
        assertEquals("drivine_epoch(${SqliteSql.property("n", "born")})", SqliteSql.property("n", "born", SqlKind.INSTANT))
        assertEquals("drivine_epoch(${SqliteSql.property("n", "at")})", SqliteSql.property("n", "at", SqlKind.LOCAL_DATE_TIME))
        assertEquals("date(${SqliteSql.property("n", "on")})", SqliteSql.property("n", "on", SqlKind.LOCAL_DATE))
        assertEquals("n.stamp", SqliteSql.property("n", Stamps.PROPERTY, SqlKind.NUMBER))
    }

    @Test
    fun `a property of the table itself, as an index reads it, names the bare column`() {
        assertEquals(
            "(CASE json_type(props, '$.\"name\"') WHEN 'text' THEN props ->> '$.\"name\"' WHEN 'null' THEN NULL ELSE props -> '$.\"name\"' END)",
            SqliteSql.property("", "name"),
        )
    }

    @Test
    fun `nothing is written with a question mark, which JDBC would take for a parameter`() {
        val written = SqliteSql.hasProperty("n", "note") + SqliteSql.hasLabels("n", listOf("A", "B")) + SqliteSql.values("\$p", SqlKind.TEXT) + SqliteSql.document("n")
        assertFalse('?' in written)
    }

    @Test
    fun `a node's document is its properties, its labels and its stamp, which a merge patch leaves out when it is null`() {
        assertEquals("json_patch(n.props, json_object('labels', json(n.labels), '${Stamps.PROPERTY}', n.stamp))", SqliteSql.document("n"))
    }

    @Test
    fun `an order says where its nulls go, as Cypher puts them unless told`() {
        assertEquals("ASC NULLS LAST", SqliteSql.direction(true))
        assertEquals("DESC NULLS FIRST", SqliteSql.direction(false))
        assertEquals("ASC NULLS FIRST", SqliteSql.direction(true, "NULLS FIRST"))
    }

    @Test
    fun `a value in a document is read as JSON, and a value of a grouped read as what it is`() {
        assertTrue(SqliteSql.jsonObject(listOf("a" to "(SELECT 1)")).contains("'a', json((SELECT 1))"))
        assertTrue(SqliteSql.plainObject(listOf("k0" to "g.k0")).contains("'k0', g.k0"))
    }
}
