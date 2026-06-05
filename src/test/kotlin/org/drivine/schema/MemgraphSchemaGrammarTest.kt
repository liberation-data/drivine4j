package org.drivine.schema

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MemgraphSchemaGrammarTest {

    private val grammar = MemgraphSchemaGrammar()

    // ----- Capabilities -----

    @Test
    fun `capabilities reflect Memgraph features`() {
        assertFalse(grammar.supportsIfNotExists)
        assertTrue(grammar.supportsNamedItems)
        assertFalse(grammar.constraintsRequireBackingIndex)
        assertFalse(grammar.constraintCreationIsAsync)
    }

    // ----- DDL -----

    @Test
    fun `vector index DDL uses WITH CONFIG and uSearch metric shorthand`() {
        val statement = grammar.createIndex(
            VectorIndexSpec("Proposition", "embedding", 1536)
        ).single() as SchemaStatement.Cypher

        assertTrue(statement.statement.contains("CREATE VECTOR INDEX Proposition_embedding_vector ON :Proposition(embedding)"))
        assertTrue(statement.statement.contains(""""dimension": 1536"""))
        assertTrue(statement.statement.contains(""""metric": "cos""""))
        assertTrue(statement.statement.contains(""""capacity": ${MemgraphSchemaGrammar.DEFAULT_VECTOR_CAPACITY}"""))
    }

    @Test
    fun `euclidean similarity maps to l2sq metric`() {
        val statement = grammar.createIndex(
            VectorIndexSpec("Doc", "vec", 768, SimilarityFunction.EUCLIDEAN)
        ).single() as SchemaStatement.Cypher

        assertTrue(statement.statement.contains(""""metric": "l2sq""""))
    }

    @Test
    fun `range index DDL is label-property style`() {
        val single = grammar.createIndex(RangeIndexSpec("Proposition", "contextId")).single() as SchemaStatement.Cypher
        val composite = grammar.createIndex(
            RangeIndexSpec("Message", listOf("sessionId", "createdAt"))
        ).single() as SchemaStatement.Cypher

        assertEquals("CREATE INDEX ON :Proposition(contextId)", single.statement)
        assertEquals("CREATE INDEX ON :Message(sessionId, createdAt)", composite.statement)
    }

    @Test
    fun `uniqueness constraint DDL uses ASSERT IS UNIQUE`() {
        val single = grammar.createConstraint(UniquenessConstraintSpec("ChatSession", "sessionId")).single() as SchemaStatement.Cypher
        val composite = grammar.createConstraint(
            UniquenessConstraintSpec("Membership", listOf("tenantId", "userId"))
        ).single() as SchemaStatement.Cypher

        assertEquals("CREATE CONSTRAINT ON (n:ChatSession) ASSERT n.sessionId IS UNIQUE", single.statement)
        assertEquals("CREATE CONSTRAINT ON (n:Membership) ASSERT n.tenantId, n.userId IS UNIQUE", composite.statement)
    }

    @Test
    fun `drop statements`() {
        val vectorDrop = grammar.dropIndex(
            SchemaItemInfo(SchemaItemKind.VECTOR_INDEX, "Doc", listOf("vec"), name = "doc_vec_vector")
        )
        val rangeDrop = grammar.dropIndex(
            SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Doc", listOf("title"))
        )
        val constraintDrop = grammar.dropConstraint(
            SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Doc", listOf("id"))
        )

        assertEquals(listOf(SchemaStatement.Cypher("DROP VECTOR INDEX doc_vec_vector")), vectorDrop)
        assertEquals(listOf(SchemaStatement.Cypher("DROP INDEX ON :Doc(title)")), rangeDrop)
        assertEquals(
            listOf(SchemaStatement.Cypher("DROP CONSTRAINT ON (n:Doc) ASSERT n.id IS UNIQUE")),
            constraintDrop
        )
    }

    // ----- Introspection parsing -----

    @Test
    fun `parses vector index rows from map shape`() {
        // CALL vector_search.show_index_info() wrapped in RETURN {…} → map rows
        val rows = listOf(
            mapOf(
                "index_name" to "prop_embedding_vector",
                "label" to "Proposition",
                "property" to "embedding",
                "dimension" to 1536L,
                "metric" to "cos",
            ),
        )

        val items = grammar.parseIndexRows(rows)

        assertEquals(1, items.size)
        assertEquals(SchemaItemKind.VECTOR_INDEX, items[0].kind)
        assertEquals("Proposition", items[0].label)
        assertEquals(1536, items[0].dimensions)
        assertEquals(SimilarityFunction.COSINE, items[0].similarity)
    }

    @Test
    fun `parses label-property index rows from positional list shape`() {
        // SHOW INDEX INFO is multi-column → positional list rows: [index type, label, property, count]
        val rows = listOf(
            listOf("label+property", "Proposition", "contextId", 0L),
            listOf("label", "Proposition", null, 10L), // label-only index — not managed
            listOf("label+property", "Message", listOf("sessionId", "createdAt"), 0L), // composite
        )

        val items = grammar.parseIndexRows(rows)

        assertEquals(2, items.size)
        assertEquals(listOf("contextId"), items[0].properties)
        assertEquals(SchemaItemKind.RANGE_INDEX, items[0].kind)
        assertEquals(listOf("sessionId", "createdAt"), items[1].properties)
    }

    @Test
    fun `parses constraint rows from positional list shape`() {
        // SHOW CONSTRAINT INFO → [constraint type, label, properties]
        val rows = listOf(
            listOf("unique", "ChatSession", "sessionId"),
            listOf("unique", "Membership", listOf("tenantId", "userId")),
            listOf("exists", "ChatSession", "createdAt"), // not managed
        )

        val items = grammar.parseConstraintRows(rows)

        assertEquals(2, items.size)
        assertEquals(listOf("sessionId"), items[0].properties)
        assertEquals(listOf("tenantId", "userId"), items[1].properties)
    }
}