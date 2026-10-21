package org.drivine.backend.oracle

import org.drivine.backend.sql.Derived
import org.drivine.backend.sql.Query
import org.drivine.backend.sql.under
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** How a query of a statement is laid out, and what a `GRAPH_TABLE` gives of its rows. No database is needed. */
class OracleQueryTest {

    private val columns = listOf(GraphTable.Column("src", "claim_.nid"), GraphTable.Column("eprops", "e.props")) + GraphTable.node("people_")

    private fun people() = GraphTable("g", "(claim_ IS node) -[e IS edge]-> (people_ IS node)", columns, "people_")

    private fun String.columnsRead(): String = substringAfter("COLUMNS (").substringBefore("))")

    // ----- GraphTable -----

    @Test
    fun `a column is named for the property it reads, and renamed only when it is called something else`() {
        assertEquals("people_.doc", GraphTable.Column("doc", "people_.doc").text)
        assertEquals("claim_.nid AS src", GraphTable.Column("src", "claim_.nid").text)
        assertEquals("e.props AS eprops", GraphTable.Column("eprops", "e.props").text)
    }

    @Test
    fun `a node gives the columns of a node, each its own`() {
        assertEquals(listOf("v.nid", "v.labels", "v.props", "v.stamp", "v.doc"), GraphTable.node("v").map { it.text })
    }

    @Test
    fun `the columns are those the readers name, in the order the source declares them`() {
        val read = people().reading(listOf("people_.doc", "people_.src = claim_.nid AND JSON_VALUE(people_.props, '$.a') = 1"))

        assertEquals("claim_.nid AS src, people_.props, people_.doc", read.columnsRead())
    }

    @Test
    fun `a column is read only where it is named whole, as a column of this source`() {
        // `people_.docs` is no read of `doc`, nor is `other_people_.doc`, nor `people_.src` of another row called `people_2`.
        assertEquals("people_.nid", people().reading(listOf("people_.docs", "other_people_.doc", "people_2.src", "people_.nid")).columnsRead())
        assertEquals("people_.doc", people().reading(listOf("(people_.doc)")).columnsRead())
        assertEquals("people_.doc", people().reading(listOf("x = people_.doc")).columnsRead())
    }

    @Test
    fun `a row another query's pattern names as its start is read by its id there`() {
        val nested = "SELECT 1 FROM GRAPH_TABLE (g MATCH (people_ IS node) -[e IS edge]-> (b_ IS node) COLUMNS (people_.nid AS src)) b_ WHERE b_.src = people_.nid"

        assertEquals("people_.nid", people().reading(listOf(nested)).columnsRead())
    }

    @Test
    fun `a query that reads nothing of a row is given its first column, for a row is never nothing`() {
        assertEquals("claim_.nid AS src", people().reading(listOf("COUNT(*)")).columnsRead())
        assertEquals("claim_.nid AS src", people().reading(emptyList()).columnsRead())
    }

    @Test
    fun `whole is every column the source can give`() {
        assertEquals(columns.joinToString(", ") { it.text }, people().whole().columnsRead())
    }

    @Test
    fun `an entire source gives every column, whatever is read of it, under the same name`() {
        val entire = people().entire()

        assertEquals("people_", entire.alias)
        assertEquals(people().whole(), entire.reading(listOf("people_.doc")))
        assertEquals(people().whole(), entire.reading(emptyList()))
    }

    @Test
    fun `a graph table is three lines, its pattern and its columns under the graph it reads`() {
        assertEquals(
            "GRAPH_TABLE (g\n    MATCH (claim_ IS node) -[e IS edge]-> (people_ IS node)\n    COLUMNS (people_.doc)) people_",
            people().reading(listOf("people_.doc")),
        )
    }

    // ----- Query -----

    @Test
    fun `a query is a clause to a line, its predicates joined by AND under WHERE and its tail after`() {
        val query = Query("people_.doc", people(), listOf("people_.src = claim_.nid", "people_.eid > 1"), listOf("ORDER BY people_.eid", "FETCH FIRST 1 ROWS ONLY"))

        assertEquals(
            """
            SELECT people_.doc
            FROM GRAPH_TABLE (g
                MATCH (claim_ IS node) -[e IS edge]-> (people_ IS node)
                COLUMNS (claim_.nid AS src, people_.doc)) people_
            WHERE people_.src = claim_.nid
              AND people_.eid > 1
            ORDER BY people_.eid
            FETCH FIRST 1 ROWS ONLY
            """.trimIndent(),
            query.text(),
        )
    }

    @Test
    fun `a query with no predicate has no WHERE, and reads of its source what its tail names`() {
        val query = Query("COUNT(*)", people(), tail = listOf("ORDER BY people_.stamp")).text()

        assertEquals("SELECT COUNT(*)\nFROM GRAPH_TABLE (g\n    MATCH (claim_ IS node) -[e IS edge]-> (people_ IS node)\n    COLUMNS (people_.stamp)) people_\nORDER BY people_.stamp", query)
    }

    @Test
    fun `a nested query is in parentheses, each of its lines under the line that opens them`() {
        val nested = Query("1", Derived("SELECT a\nFROM t", "d")).nested()

        assertEquals("(\n    SELECT 1\n    FROM (\n        SELECT a\n        FROM t\n    ) d)", nested)
    }

    @Test
    fun `a derived source is the query it was given, whatever is read of it`() {
        assertEquals("(\n    SELECT a, b\n    FROM t\n) d", Derived("SELECT a, b\nFROM t", "d").reading(listOf("d.a")))
    }

    @Test
    fun `text moved under a line keeps its first line where it is`() {
        assertEquals("a\n    b\n        c", "a\nb\n    c".under())
        assertEquals("a", "a".under())
    }
}
