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

/** The text of the statements of a patch, of many and of one: what each matches by, and how each property is set and compared. */
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

    // ----- A patch of one node -----

    @Test
    fun `a patch of one matches by the id and never merges, and reads its predicate once the node is locked`() {
        val statement = neo4j.one(FragmentModel.from(WidgetNode::class.java), "n", mapOf("name" to "new"), "n.name = \$param_n_name_0")

        val lines = statement.lines()
        assertEquals("MATCH (n:Widget {widget_key: \$id})", lines[0])
        assertFalse("MERGE" in statement)
        assertEquals("SET n.`__drivine.lock` = true REMOVE n.`__drivine.lock`", lines[1])
        // A value of a WITH, and no WHERE: a node that does not meet the predicate still gives its row.
        assertEquals("WITH n, coalesce((n.name = \$param_n_name_0), false) AS _met", lines[2])
        assertEquals("WITH n, _met, _met AND ((NOT coalesce(n.name = \$_v0, false))) AS _changed", lines[3])
        assertEquals("FOREACH (_ IN CASE WHEN _changed THEN [1] ELSE [] END | SET n.name = \$_v0)", lines[4])
        assertContains(lines[5], "SET n.`__drivine.stamp` = CASE WHEN (_changed) OR ")
        assertEquals("RETURN '0/' + CASE WHEN _changed THEN '1' WHEN _met THEN '0' ELSE '-' END + '=' + n.`__drivine.stamp`", lines[6])
        assertFalse("WHERE" in statement)
    }

    @Test
    fun `a patch of one with no predicate is met wherever the node is`() {
        val statement = neo4j.one(FragmentModel.from(ChunkNode::class.java), "n", mapOf("text" to "new"), where = null)

        assertContains(statement, "WITH n, true AS _met\n")
    }

    @Test
    fun `a property a patch of one clears is set to null, and is a change only where it held a value`() {
        val statement = neo4j.one(FragmentModel.from(ChunkNode::class.java), "chunk", mapOf("text" to "new", "sequence_number" to null), where = null)

        assertContains(statement, "| SET chunk.text = \$_v0, chunk.sequence_number = null)")
        assertContains(statement, "_met AND ((NOT coalesce(chunk.text = \$_v0, false)) OR (chunk.sequence_number IS NOT NULL)) AS _changed")
        // The value of the property at an index is bound under that index, so a cleared one binds nothing.
        assertFalse("\$_v1" in statement)
    }

    @Test
    fun `a patch of one reads a relationship count from a prolog, after the lock`() {
        val prolog = "CALL {\n    WITH claim\n    OPTIONAL MATCH (claim)-[:MENTIONS]->(people) WHERE people.name = \$p\n    RETURN count(people) AS _ec0\n}"
        val statement = neo4j.one(FragmentModel.from(ChunkNode::class.java), "claim", mapOf("text" to "new"), "_ec0 > 0", listOf(prolog))

        assertContains(statement, "REMOVE claim.`__drivine.lock`\nWITH claim\n$prolog\nWITH claim, coalesce((_ec0 > 0), false) AS _met")
    }

    @Test
    fun `a patch of one wraps a vector where the engine wraps one, in what it sets and in what it compares`() {
        val model = FragmentModel.from(DocNode::class.java)

        val wrapped = falkorDb.one(model, "n", mapOf("title" to "t", "embedding" to listOf(1.0f)), where = null)
        assertContains(wrapped, "SET n.title = \$_v0, n.embedding = vecf32(\$_v1))")
        assertContains(wrapped, "(NOT coalesce(n.embedding = vecf32(\$_v1), false))")
        // A cleared vector is never wrapped.
        assertContains(falkorDb.one(model, "n", mapOf("embedding" to null), where = null), "SET n.embedding = null)")
        assertContains(neo4j.one(model, "n", mapOf("embedding" to listOf(1.0f)), where = null), "SET n.embedding = \$_v0)")
    }
}
