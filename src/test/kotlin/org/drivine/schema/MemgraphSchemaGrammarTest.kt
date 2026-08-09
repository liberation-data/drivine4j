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
    fun `fulltext index DDL uses Memgraph's named TEXT INDEX syntax`() {
        val multi = grammar.createIndex(
            FullTextIndexSpec("Chunk", listOf("title", "body"))
        ).single() as SchemaStatement.Cypher
        val named = grammar.createIndex(
            FullTextIndexSpec("Chunk", "summary", name = "explicit_ft")
        ).single() as SchemaStatement.Cypher

        assertEquals("CREATE TEXT INDEX Chunk_title_body_fulltext ON :Chunk(title, body)", multi.statement)
        assertEquals("CREATE TEXT INDEX explicit_ft ON :Chunk(summary)", named.statement)
    }

    @Test
    fun `fulltext drop uses DROP TEXT INDEX by name`() {
        val byName = grammar.dropIndex(
            SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Chunk", listOf("title"), name = "explicit_ft")
        )
        val derived = grammar.dropIndex(
            SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Chunk", listOf("title", "body"))
        )

        assertEquals(listOf(SchemaStatement.Cypher("DROP TEXT INDEX explicit_ft")), byName)
        assertEquals(listOf(SchemaStatement.Cypher("DROP TEXT INDEX Chunk_title_body_fulltext")), derived)
    }

    @Test
    fun `parses text index rows, recovering the name from the index type column`() {
        // SHOW INDEX INFO reports text indexes as "label_text (name: <name>)" with a property list
        val rows = listOf(
            listOf("label_text (name: chunk_ft)", "Chunk", listOf("title", "body"), 0L),
            listOf("label+property", "Chunk", "slug", 0L), // range row must still parse as range
        )

        val items = grammar.parseIndexRows(rows)

        val fulltext = items.single { it.kind == SchemaItemKind.FULLTEXT_INDEX }
        assertEquals("Chunk", fulltext.label)
        assertEquals(listOf("title", "body"), fulltext.properties)
        assertEquals("chunk_ft", fulltext.name)
        assertNull(fulltext.analyzer) // Memgraph does not report an analyzer

        assertEquals(SchemaItemKind.RANGE_INDEX, items.single { it.kind == SchemaItemKind.RANGE_INDEX }.kind)
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

    // ----- Memgraph 3.11+ introspection shape -----

    @Test
    fun `strips the leading colon Memgraph 3-11 prefixes onto introspected labels`() {
        // 3.11 returns labels colon-prefixed (`:Proposition`) from every introspection surface; earlier
        // versions returned them bare. Without normalization, identity matching against a spec's bare
        // label fails and every ensure() re-creates instead of matching.
        val vector = grammar.parseIndexRows(
            listOf(mapOf("index_name" to "i", "label" to ":Proposition", "property" to "embedding", "dimension" to 768L, "metric" to "cos"))
        ).single()
        assertEquals("Proposition", vector.label)

        val range = grammar.parseIndexRows(listOf(listOf("label+property", ":Proposition", "contextId", 0L))).single()
        assertEquals("Proposition", range.label)

        val constraint = grammar.parseConstraintRows(listOf(listOf("unique", ":ChatSession", "sessionId"))).single()
        assertEquals("ChatSession", constraint.label)
    }

    @Test
    fun `ignores the label+property_vector backing row that 3-11 surfaces in SHOW INDEX INFO`() {
        // A vector index's backing now appears in SHOW INDEX INFO as `label+property_vector`; it is owned
        // by the vector-introspection path, so the range parser must not read it as a phantom range index.
        val items = grammar.parseIndexRows(
            listOf(
                listOf("label+property_vector", ":Proposition", "embedding", 0L), // vector backing — skip
                listOf("label+property", ":Proposition", "contextId", 0L),        // real range index — keep
            )
        )
        assertEquals(1, items.size)
        assertEquals(SchemaItemKind.RANGE_INDEX, items[0].kind)
        assertEquals(listOf("contextId"), items[0].properties)
    }
}