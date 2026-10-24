package org.drivine.backend.sqlite

import org.drivine.query.SqlParameters
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaStatement
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqliteSchemaGrammarTest {

    private val graph = SqliteGraph("g")
    private val grammar = SqliteSchemaGrammar(graph)

    private fun texts(statements: List<SchemaStatement>) = statements.map { (it as SchemaStatement.Cypher).statement }

    @Test
    fun `a range index is a partial index over the property as a statement reads it, for the nodes of its label`() {
        val made = texts(grammar.createIndex(RangeIndexSpec("Person", listOf("email"))))
        val name = graph.objectName("ix", listOf("Person", "email"))
        assertEquals(graph.ddl, made.take(graph.ddl.size), "the store is made first")
        assertEquals("CREATE INDEX IF NOT EXISTS $name ON g_node (${SqliteSql.property("", "email")}) WHERE ${SqliteSql.hasLabel("", "Person")}", made[graph.ddl.size])
        assertTrue("INSERT INTO g_schema" in made.last() && "'$name'" in made.last(), made.last())
    }

    @Test
    fun `a uniqueness constraint is that index made unique`() {
        val made = texts(grammar.createConstraint(UniquenessConstraintSpec("Person", listOf("email"))))
        assertTrue(made.any { it.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS ${graph.objectName("uq", listOf("Person", "email"))} ON g_node") }, made.joinToString("\n"))
    }

    @Test
    fun `a vector and a full-text index are recorded, and nothing is made for them`() {
        val vector = texts(grammar.createIndex(VectorIndexSpec("Doc", "embedding", 4, SimilarityFunction.COSINE)))
        val text = texts(grammar.createIndex(FullTextIndexSpec("Doc", listOf("title"))))
        for (made in listOf(vector, text)) {
            assertEquals(graph.ddl.size + 1, made.size, made.joinToString("\n"))
            assertTrue(made.last().contains("NULL, '{"), made.last())
        }
        assertTrue("\"dimensions\": 4" in vector.last())
    }

    @Test
    fun `an index is dropped by the name its kind gives it, and one that was never made is only forgotten`() {
        val range = texts(grammar.dropIndex(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("email"), null)))
        assertEquals("DROP INDEX IF EXISTS ${graph.objectName("ix", listOf("Person", "email"))}", range.first())
        val vector = texts(grammar.dropIndex(SchemaItemInfo(SchemaItemKind.VECTOR_INDEX, "Doc", listOf("embedding"), null)))
        assertEquals(1, vector.size)
        assertTrue(vector.single().startsWith("DELETE FROM g_schema"), vector.single())
    }

    @Test
    fun `the conflicts of a constraint are read back as JSON, the nodes of a key named by their nid, the first made first`() {
        val query = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", listOf("email", "tenant")), 5)
        assertTrue(query.startsWith(SqliteSql.JSON_ROWS), query)
        assertTrue("json_group_array(json_object('elementId', k.nid) ORDER BY k.nid) FILTER (WHERE k.position <= 5)" in query, query)
        assertEquals(emptyList(), SqlParameters.positional(query).names)
        val byUuid = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "it's"), 3)
        assertTrue("json_object('elementId', k.nid, 'it''s', k.sampled)" in byUuid, byUuid)
    }
}
