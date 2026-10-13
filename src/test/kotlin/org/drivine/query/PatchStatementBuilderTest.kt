package org.drivine.query

import org.drivine.model.FragmentModel
import org.drivine.query.grammar.FalkorDbCypherGrammar
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.grammar.OpenCypherGrammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.sort.CallSubqueryEmitter
import org.junit.jupiter.api.Test
import sample.graphproperty.ChunkNode
import sample.graphproperty.WidgetNode
import sample.vector.DocNode
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The text of the statement of a patch of many: what it matches by, and how each property is set and compared. */
class PatchStatementBuilderTest {

    private val neo4j = PatchStatementBuilder(Neo4j5Grammar(ApocSortMapsEmitter()))
    private val falkorDb = PatchStatementBuilder(FalkorDbCypherGrammar(CallSubqueryEmitter()))

    @Test
    fun `it matches and never merges, by the property the id is stored under`() {
        val statement = neo4j.unwind(FragmentModel.from(WidgetNode::class.java), listOf("name"), clears = false)

        assertEquals("UNWIND \$rows AS row", statement.lines().first())
        assertContains(statement, "MATCH (n:Widget {widget_key: row.id})")
        assertFalse("MERGE" in statement)
        assertContains(statement, "RETURN row.i + '/' + CASE WHEN _changed THEN '1' ELSE '0' END + '=' + n.`__drivine.stamp`")
    }

    @Test
    fun `where a null is left alone, it keeps the stored value and is no change`() {
        val statement = neo4j.unwind(FragmentModel.from(ChunkNode::class.java), listOf("text", "sequence_number"), clears = false)

        // The values are written only to a node they change; the stamp is seen to for every node found.
        assertContains(
            statement,
            "FOREACH (_ IN CASE WHEN _changed THEN [1] ELSE [] END | " +
                "SET n.text = coalesce(row.v.text, n.text), n.sequence_number = coalesce(row.v.sequence_number, n.sequence_number))\nSET n.`__drivine.stamp` = ",
        )
        assertContains(
            statement,
            "((row.v.text IS NOT NULL AND NOT coalesce(n.text = row.v.text, false)) OR " +
                "(row.v.sequence_number IS NOT NULL AND NOT coalesce(n.sequence_number = row.v.sequence_number, false))) AS _changed",
        )
    }

    @Test
    fun `where a null clears, it is a change only for a property that held a value`() {
        val statement = neo4j.unwind(FragmentModel.from(ChunkNode::class.java), listOf("text"), clears = true)

        assertContains(statement, "| SET n.text = row.v.text)")
        assertContains(statement, "(CASE WHEN row.v.text IS NULL THEN n.text IS NOT NULL ELSE NOT coalesce(n.text = row.v.text, false) END)")
    }

    @Test
    fun `a vector is wrapped where the engine wraps one, and never when it is null`() {
        val model = FragmentModel.from(DocNode::class.java)

        val kept = falkorDb.unwind(model, listOf("title", "embedding"), clears = false)
        assertContains(kept, "n.title = coalesce(row.v.title, n.title)")
        assertContains(kept, "n.embedding = CASE WHEN row.v.embedding IS NULL THEN n.embedding ELSE vecf32(row.v.embedding) END")
        assertContains(kept, "(CASE WHEN row.v.embedding IS NULL THEN false ELSE NOT coalesce(n.embedding = vecf32(row.v.embedding), false) END)")

        val cleared = falkorDb.unwind(model, listOf("embedding"), clears = true)
        assertContains(cleared, "n.embedding = CASE WHEN row.v.embedding IS NULL THEN null ELSE vecf32(row.v.embedding) END")
        assertContains(cleared, "(CASE WHEN row.v.embedding IS NULL THEN n.embedding IS NOT NULL ELSE NOT coalesce(n.embedding = vecf32(row.v.embedding), false) END)")

        assertContains(neo4j.unwind(model, listOf("embedding"), clears = false), "n.embedding = coalesce(row.v.embedding, n.embedding)")
    }

    @Test
    fun `a grammar that wraps a vector only as a parameter is refused, and only for a vector`() {
        val model = FragmentModel.from(DocNode::class.java)
        val wrapsParameterOnly = PatchStatementBuilder(object : OpenCypherGrammar(CallSubqueryEmitter()) {
            override fun vectorPropertyLiteral(param: String) = "vecf32(\$$param)"
        })

        assertFailsWith<IllegalArgumentException> { wrapsParameterOnly.unwind(model, listOf("title", "embedding"), clears = false) }
        assertContains(wrapsParameterOnly.unwind(model, listOf("title"), clears = false), "n.title = coalesce(row.v.title, n.title)")
    }
}
