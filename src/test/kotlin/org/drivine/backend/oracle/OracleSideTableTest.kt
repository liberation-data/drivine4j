package org.drivine.backend.oracle

import org.drivine.query.SqlParameters
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The statements that make and fill a table beside the nodes, apart from the index a grammar puts over it. */
class OracleSideTableTest {

    private fun side(tolerant: Boolean = false, filter: ((String) -> String)? = null) = OracleSideTable(
        name = "g_t_doc",
        nodes = "g_node",
        column = "doc",
        columnType = "JSON",
        payload = { row -> "JSON_VALUE($row.props, '$.it''s')" },
        indexed = { row -> OracleSql.carriesLabel(row, "Doc") },
        backfillFilter = filter,
        tolerant = tolerant,
    )

    private fun count(text: String, part: String): Int = Regex(Regex.escape(part)).findAll(text).count()

    @Test
    fun `the trigger and the index are named after the table, which is made only when it is not there`() {
        val side = side()

        assertEquals("g_t_doc_t", side.trigger)
        assertEquals("g_t_doc_i", side.index)
        assertEquals("CREATE TABLE IF NOT EXISTS g_t_doc (nid NUMBER PRIMARY KEY REFERENCES g_node (nid) ON DELETE CASCADE, doc JSON NOT NULL)", side.create)
    }

    @Test
    fun `a trigger that is not tolerant lets a payload the column refuses fail the write of the node`() {
        val ddl = side(tolerant = false).triggerDdl

        assertFalse("EXCEPTION" in ddl, ddl)
        // The row leaves the table only when the node no longer belongs in it.
        assertEquals(1, count(ddl, "DELETE FROM g_t_doc WHERE nid = :NEW.nid;"))
        assertEquals(1, count(ddl, "BEGIN"), "one block: $ddl")
        assertTrue(ddl.startsWith("CREATE OR REPLACE TRIGGER g_t_doc_t AFTER INSERT OR UPDATE OF labels, props ON g_node FOR EACH ROW\nBEGIN"), ddl)
        assertTrue(ddl.endsWith("END IF;\nEND;"), ddl)
    }

    @Test
    fun `a tolerant trigger keeps the node and leaves it out of the table, in a block of its own inside the test of whether it belongs`() {
        val ddl = side(tolerant = true).triggerDdl

        val belongs = ddl.indexOf("IF INSTR(JSON_SERIALIZE(:NEW.labels), '\"Doc\"') > 0 THEN")
        val handler = ddl.indexOf("EXCEPTION WHEN OTHERS THEN")
        val otherwise = ddl.indexOf("ELSE")
        assertTrue(belongs in 0 until handler && handler < otherwise, ddl)
        assertEquals(2, count(ddl, "BEGIN"))
        assertEquals(2, count(ddl, "DELETE FROM g_t_doc WHERE nid = :NEW.nid;"))
        // The write is the same either way: the handler is around it, and changes none of it.
        for (trigger in listOf(ddl, side(tolerant = false).triggerDdl)) {
            assertTrue("MERGE INTO g_t_doc s USING (SELECT :NEW.nid AS nid, JSON_VALUE(:NEW.props, '$.it''s') AS doc FROM dual) n ON (s.nid = n.nid)" in trigger, trigger)
            assertTrue("WHEN MATCHED THEN UPDATE SET s.doc = n.doc" in trigger, trigger)
            assertTrue("WHEN NOT MATCHED THEN INSERT (nid, doc) VALUES (n.nid, n.doc);" in trigger, trigger)
        }
    }

    @Test
    fun `the trigger is made from inside a block, as a literal with every quote of it doubled, so nothing in it is read as a parameter`() {
        val side = side(tolerant = true)
        val statement = side.triggerStatement

        assertEquals("BEGIN EXECUTE IMMEDIATE ${OracleSql.literal(side.triggerDdl)}; END;", statement)
        // A quote the payload had doubled already is doubled again, so the trigger that is made is the one that was written.
        assertTrue("''$.it''''s''" in statement, statement)
        assertEquals(side.triggerDdl, statement.removePrefix("BEGIN EXECUTE IMMEDIATE '").removeSuffix("'; END;").replace("''", "'"))
        assertEquals(emptyList(), SqlParameters.positional(statement).names)
    }

    @Test
    fun `the backfill takes the nodes that belong and are not in the table yet, and makes a further test only when it is given one`() {
        val plain = side().backfill
        val filtered = side(filter = { row -> "LENGTH(JSON_VALUE($row.props, '$.title')) > 0" }).backfill

        assertEquals(
            listOf(
                "INSERT INTO g_t_doc (nid, doc)",
                "SELECT n.nid, JSON_VALUE(n.props, '$.it''s') FROM g_node n",
                "WHERE INSTR(JSON_SERIALIZE(n.labels), '\"Doc\"') > 0",
                "  AND NOT EXISTS (SELECT 1 FROM g_t_doc s WHERE s.nid = n.nid)",
            ).joinToString("\n"),
            plain,
        )
        assertTrue("WHERE INSTR(JSON_SERIALIZE(n.labels), '\"Doc\"') > 0 AND LENGTH(JSON_VALUE(n.props, '$.title')) > 0\n  AND NOT EXISTS" in filtered, filtered)
    }

    @Test
    fun `dropping takes the trigger before the table, and neither fails when it is not there`() {
        assertEquals(listOf("DROP TRIGGER IF EXISTS g_t_doc_t", "DROP TABLE IF EXISTS g_t_doc PURGE"), OracleSideTable.dropping("g_t_doc"))
        assertEquals("DROP TRIGGER IF EXISTS ${side().trigger}", OracleSideTable.dropping(side().name).first())
    }
}
