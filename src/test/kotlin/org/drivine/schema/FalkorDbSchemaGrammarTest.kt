package org.drivine.schema

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FalkorDbSchemaGrammarTest {

    private val grammar = FalkorDbSchemaGrammar()

    // ----- Capabilities -----

    @Test
    fun `capabilities reflect FalkorDB features`() {
        assertFalse(grammar.supportsIfNotExists)
        assertFalse(grammar.supportsNamedItems)
        assertTrue(grammar.constraintsRequireBackingIndex)
        assertTrue(grammar.constraintCreationIsAsync)
    }

    // ----- DDL: indexes -----

    @Test
    fun `vector index DDL has no name and uses OPTIONS`() {
        val statement = grammar.createIndex(
            VectorIndexSpec("Proposition", "embedding", 1536, name = "ignored_name")
        ).single() as SchemaStatement.Cypher

        assertTrue(statement.statement.contains("CREATE VECTOR INDEX FOR (n:Proposition) ON (n.embedding)"))
        assertTrue(statement.statement.contains("OPTIONS {dimension: 1536, similarityFunction: 'cosine'}"))
        // FalkorDB has no user-supplied index names
        assertFalse(statement.statement.contains("ignored_name"))
    }

    @Test
    fun `range index DDL uses FOR ON syntax`() {
        val statement = grammar.createIndex(
            RangeIndexSpec("Message", listOf("sessionId", "createdAt"))
        ).single() as SchemaStatement.Cypher

        assertEquals("CREATE INDEX FOR (n:Message) ON (n.sessionId, n.createdAt)", statement.statement)
    }

    @Test
    fun `range index creation only emits properties missing from the existing label index`() {
        // FalkorDB rejects creating an already-indexed property, so extension is incremental
        val existing = SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Proposition", listOf("contextId"))

        val statements = grammar.createIndex(
            RangeIndexSpec("Proposition", listOf("contextId", "status")),
            existing,
        )

        assertEquals(
            listOf(SchemaStatement.Cypher("CREATE INDEX FOR (n:Proposition) ON (n.status)")),
            statements
        )
    }

    @Test
    fun `range index creation emits nothing when all properties are already indexed`() {
        val existing = SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Proposition", listOf("contextId", "status"))

        val statements = grammar.createIndex(RangeIndexSpec("Proposition", "contextId"), existing)

        assertTrue(statements.isEmpty())
    }

    // ----- DDL: fulltext -----

    @Test
    fun `fulltext index DDL is one procedure call per property`() {
        val statements = grammar.createIndex(
            FullTextIndexSpec("Chunk", listOf("title", "body"), name = "ignored_name")
        )

        assertEquals(
            listOf(
                SchemaStatement.Cypher("CALL db.idx.fulltext.createNodeIndex('Chunk', 'title')"),
                SchemaStatement.Cypher("CALL db.idx.fulltext.createNodeIndex('Chunk', 'body')"),
            ),
            statements
        )
    }

    @Test
    fun `fulltext creation only emits properties missing from the existing label coverage`() {
        // FalkorDB rejects re-indexing an already-fulltext property, exactly as for range
        val existing = SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Chunk", listOf("title"))

        val statements = grammar.createIndex(FullTextIndexSpec("Chunk", listOf("title", "body")), existing)

        assertEquals(
            listOf(SchemaStatement.Cypher("CALL db.idx.fulltext.createNodeIndex('Chunk', 'body')")),
            statements
        )
    }

    @Test
    fun `fulltext creation emits nothing when all properties are already covered`() {
        val existing = SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Chunk", listOf("title", "body"))

        assertTrue(grammar.createIndex(FullTextIndexSpec("Chunk", "title"), existing).isEmpty())
    }

    @Test
    fun `fulltext drop emits one statement per property`() {
        val statements = grammar.dropIndex(
            SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Chunk", listOf("title", "body"))
        )

        assertEquals(
            listOf(
                SchemaStatement.Cypher("DROP FULLTEXT INDEX FOR (n:Chunk) ON (n.title)"),
                SchemaStatement.Cypher("DROP FULLTEXT INDEX FOR (n:Chunk) ON (n.body)"),
            ),
            statements
        )
    }

    @Test
    fun `one db indexes row reassembles all FULLTEXT properties into a single item`() {
        // The fiddly case: [title, body] was created by two calls but must read back as ONE item,
        // or every ensure() would report drift. A property may carry both RANGE and FULLTEXT.
        val rows = listOf(
            mapOf(
                "label" to "Chunk",
                "properties" to listOf("age", "title", "body"),
                "types" to mapOf(
                    "age" to listOf("RANGE", "FULLTEXT"),
                    "title" to listOf("FULLTEXT"),
                    "body" to listOf("FULLTEXT"),
                ),
                "options" to emptyMap<String, Any?>(),
                "entitytype" to "NODE",
            )
        )

        val items = grammar.parseIndexRows(rows)

        val fulltext = items.single { it.kind == SchemaItemKind.FULLTEXT_INDEX }
        assertEquals("Chunk", fulltext.label)
        assertEquals(setOf("age", "title", "body"), fulltext.properties.toSet())
        assertNull(fulltext.name) // FalkorDB indexes are unnamed

        // The range fan-out is independent and still sees only the RANGE-typed property
        val range = items.single { it.kind == SchemaItemKind.RANGE_INDEX }
        assertEquals(listOf("age"), range.properties)
    }

    @Test
    fun `fulltext identity uses coverage, like range`() {
        val existing = SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Chunk", listOf("title", "body", "summary"))

        assertTrue(grammar.matchesIdentity(existing, FullTextIndexSpec("Chunk", listOf("title", "body"))))
        assertFalse(grammar.matchesIdentity(existing, FullTextIndexSpec("Chunk", listOf("title", "missing"))))
    }

    @Test
    fun `index drops emit one statement per property`() {
        val statements = grammar.dropIndex(
            SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Message", listOf("sessionId", "createdAt"))
        )

        assertEquals(
            listOf(
                SchemaStatement.Cypher("DROP INDEX FOR (n:Message) ON (n.sessionId)"),
                SchemaStatement.Cypher("DROP INDEX FOR (n:Message) ON (n.createdAt)"),
            ),
            statements
        )
    }

    // ----- DDL: constraints (native Redis commands) -----

    @Test
    fun `uniqueness constraint is a native GRAPH CONSTRAINT command with graph name placeholder`() {
        val statements = grammar.createConstraint(
            UniquenessConstraintSpec("Membership", listOf("tenantId", "userId"))
        )

        assertEquals(
            listOf(
                SchemaStatement.Native(
                    listOf(
                        "GRAPH.CONSTRAINT", "CREATE", SchemaStatement.Native.GRAPH_NAME,
                        "UNIQUE", "NODE", "Membership",
                        "PROPERTIES", "2", "tenantId", "userId",
                    )
                )
            ),
            statements
        )
    }

    @Test
    fun `constraint drop is a native GRAPH CONSTRAINT DROP command`() {
        val statements = grammar.dropConstraint(
            SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("email"))
        )

        assertEquals(
            listOf(
                SchemaStatement.Native(
                    listOf(
                        "GRAPH.CONSTRAINT", "DROP", SchemaStatement.Native.GRAPH_NAME,
                        "UNIQUE", "NODE", "Person",
                        "PROPERTIES", "1", "email",
                    )
                )
            ),
            statements
        )
    }

    // ----- Introspection parsing -----

    @Test
    fun `one db indexes row yields range item plus vector items - FalkorDB 4 per-property options`() {
        // db.indexes() returns one row per label; types maps property → index types.
        // FalkorDB 4.x keys options per property:
        // {embedding: {dimension: 1536, similarityFunction: cosine, M: 16, …}, contextId: {}}
        val rows = listOf(
            mapOf(
                "label" to "Proposition",
                "properties" to listOf("contextId", "status", "embedding"),
                "types" to mapOf(
                    "contextId" to listOf("RANGE"),
                    "status" to listOf("RANGE"),
                    "embedding" to listOf("VECTOR"),
                ),
                "options" to mapOf(
                    "contextId" to emptyMap<String, Any>(),
                    "status" to emptyMap<String, Any>(),
                    "embedding" to mapOf(
                        "dimension" to 1536L,
                        "similarityFunction" to "cosine",
                        "M" to 16L,
                        "efConstruction" to 200L,
                        "efRuntime" to 10L,
                    ),
                ),
                "entitytype" to "NODE",
            ),
        )

        val items = grammar.parseIndexRows(rows)

        assertEquals(2, items.size)

        val range = items.first { it.kind == SchemaItemKind.RANGE_INDEX }
        assertEquals(listOf("contextId", "status"), range.properties)
        assertNull(range.name)

        val vector = items.first { it.kind == SchemaItemKind.VECTOR_INDEX }
        assertEquals(listOf("embedding"), vector.properties)
        assertEquals(1536, vector.dimensions)
        assertEquals(SimilarityFunction.COSINE, vector.similarity)
    }

    @Test
    fun `vector options also parse from the legacy flat options shape`() {
        val rows = listOf(
            mapOf(
                "label" to "Doc",
                "properties" to listOf("embedding"),
                "types" to mapOf("embedding" to listOf("VECTOR")),
                "options" to mapOf("dimension" to 768L, "similarityFunction" to "euclidean"),
                "entitytype" to "NODE",
            ),
        )

        val vector = grammar.parseIndexRows(rows).single()

        assertEquals(768, vector.dimensions)
        assertEquals(SimilarityFunction.EUCLIDEAN, vector.similarity)
    }

    @Test
    fun `skips relationship index rows`() {
        val rows = listOf(
            mapOf(
                "label" to "KNOWS",
                "properties" to listOf("since"),
                "types" to mapOf("since" to listOf("RANGE")),
                "options" to null,
                "entitytype" to "RELATIONSHIP",
            ),
        )

        assertTrue(grammar.parseIndexRows(rows).isEmpty())
    }

    @Test
    fun `parses unique constraint rows with status`() {
        val rows = listOf(
            mapOf(
                "type" to "unique", "label" to "Person", "properties" to listOf("email"),
                "entitytype" to "NODE", "status" to "UNDER CONSTRUCTION",
            ),
            mapOf(
                "type" to "mandatory", "label" to "Person", "properties" to listOf("id"),
                "entitytype" to "NODE", "status" to "OPERATIONAL",
            ),
        )

        val items = grammar.parseConstraintRows(rows)

        assertEquals(1, items.size)
        assertEquals("UNDER CONSTRUCTION", items[0].status)
    }

    // ----- Matching: coverage semantics -----

    @Test
    fun `range identity uses coverage - label index covering more properties still matches`() {
        val existing = SchemaItemInfo(
            SchemaItemKind.RANGE_INDEX, "Proposition", listOf("contextId", "status", "createdAt")
        )

        assertTrue(grammar.matchesIdentity(existing, RangeIndexSpec("Proposition", "contextId")))
        assertTrue(grammar.matchesIdentity(existing, RangeIndexSpec("Proposition", listOf("contextId", "status"))))
        assertFalse(grammar.matchesIdentity(existing, RangeIndexSpec("Proposition", "unindexedProp")))
    }

    @Test
    fun `vector identity remains exact`() {
        val existing = SchemaItemInfo(SchemaItemKind.VECTOR_INDEX, "Doc", listOf("embedding"), dimensions = 768)

        assertTrue(grammar.matchesIdentity(existing, VectorIndexSpec("Doc", "embedding", 1536)))
        assertFalse(grammar.matchesIdentity(existing, VectorIndexSpec("Doc", "other", 1536)))
    }
}