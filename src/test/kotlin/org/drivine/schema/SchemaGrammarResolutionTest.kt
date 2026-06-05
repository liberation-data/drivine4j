package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SchemaGrammarResolutionTest {

    @Test
    fun `Neo4j 4 and 5 share the same schema grammar`() {
        assertTrue(CypherDialect.NEO4J_5.schemaGrammar() is Neo4jSchemaGrammar)
        assertTrue(CypherDialect.NEO4J_4.schemaGrammar() is Neo4jSchemaGrammar)
    }

    @Test
    fun `Memgraph and FalkorDB resolve to their own schema grammars`() {
        assertTrue(CypherDialect.MEMGRAPH.schemaGrammar() is MemgraphSchemaGrammar)
        assertTrue(CypherDialect.FALKORDB.schemaGrammar() is FalkorDbSchemaGrammar)
    }

    @Test
    fun `unsupported dialects fail loudly on any schema operation`() {
        listOf(CypherDialect.NEPTUNE, CypherDialect.OPEN_CYPHER).forEach { dialect ->
            val grammar = dialect.schemaGrammar()
            assertTrue(grammar is UnsupportedSchemaGrammar)

            val exception = assertThrows<DrivineException> {
                grammar.createIndex(RangeIndexSpec("Person", "name"))
            }
            assertTrue(exception.message!!.contains(dialect.name))
            assertThrows<DrivineException> { grammar.listIndexesQuery(SchemaItemKind.RANGE_INDEX) }
            assertThrows<DrivineException> { grammar.listConstraintsQuery() }
        }
    }

    @Test
    fun `default names derive from label and properties`() {
        assertEquals("Proposition_embedding_vector", VectorIndexSpec("Proposition", "embedding", 8).effectiveName)
        assertEquals("Message_sessionId_createdAt_range", RangeIndexSpec("Message", listOf("sessionId", "createdAt")).effectiveName)
        assertEquals("Person_email_unique", UniquenessConstraintSpec("Person", "email").effectiveName)
        assertEquals("explicit", RangeIndexSpec("Person", "email", name = "explicit").effectiveName)
    }
}