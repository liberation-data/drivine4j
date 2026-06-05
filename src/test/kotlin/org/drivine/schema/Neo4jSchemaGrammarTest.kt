package org.drivine.schema

import org.drivine.DrivineException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Neo4jSchemaGrammarTest {

    private val grammar = Neo4jSchemaGrammar()

    // ----- Capabilities -----

    @Test
    fun `capabilities reflect Neo4j features`() {
        assertTrue(grammar.supportsIfNotExists)
        assertTrue(grammar.supportsNamedItems)
        assertFalse(grammar.constraintsRequireBackingIndex)
        assertFalse(grammar.constraintCreationIsAsync)
    }

    // ----- DDL: vector index -----

    @Test
    fun `vector index DDL uses IF NOT EXISTS and backtick-quoted config keys`() {
        val spec = VectorIndexSpec("Proposition", "embedding", 1536)
        val statement = grammar.createIndex(spec).single() as SchemaStatement.Cypher

        assertTrue(statement.statement.contains("CREATE VECTOR INDEX `Proposition_embedding_vector` IF NOT EXISTS"))
        assertTrue(statement.statement.contains("FOR (n:`Proposition`) ON (n.`embedding`)"))
        assertTrue(statement.statement.contains("`vector.dimensions`: 1536"))
        assertTrue(statement.statement.contains("`vector.similarity_function`: 'cosine'"))
    }

    @Test
    fun `vector index DDL respects explicit name and similarity`() {
        val spec = VectorIndexSpec("Doc", "vec", 768, SimilarityFunction.EUCLIDEAN, name = "my_index")
        val statement = grammar.createIndex(spec).single() as SchemaStatement.Cypher

        assertTrue(statement.statement.contains("CREATE VECTOR INDEX `my_index` IF NOT EXISTS"))
        assertTrue(statement.statement.contains("'euclidean'"))
    }

    // ----- DDL: range index -----

    @Test
    fun `single property range index DDL`() {
        val statement = grammar.createIndex(RangeIndexSpec("Proposition", "contextId")).single() as SchemaStatement.Cypher

        assertEquals(
            "CREATE INDEX `Proposition_contextId_range` IF NOT EXISTS FOR (n:`Proposition`) ON (n.`contextId`)",
            statement.statement
        )
    }

    @Test
    fun `composite range index DDL lists all properties in order`() {
        val statement = grammar.createIndex(
            RangeIndexSpec("Message", listOf("sessionId", "createdAt"))
        ).single() as SchemaStatement.Cypher

        assertEquals(
            "CREATE INDEX `Message_sessionId_createdAt_range` IF NOT EXISTS " +
                "FOR (n:`Message`) ON (n.`sessionId`, n.`createdAt`)",
            statement.statement
        )
    }

    // ----- DDL: constraints -----

    @Test
    fun `uniqueness constraint DDL uses REQUIRE IS UNIQUE`() {
        val statement = grammar.createConstraint(
            UniquenessConstraintSpec("ChatSession", "sessionId")
        ).single() as SchemaStatement.Cypher

        assertEquals(
            "CREATE CONSTRAINT `ChatSession_sessionId_unique` IF NOT EXISTS " +
                "FOR (n:`ChatSession`) REQUIRE (n.`sessionId`) IS UNIQUE",
            statement.statement
        )
    }

    @Test
    fun `composite uniqueness constraint DDL`() {
        val statement = grammar.createConstraint(
            UniquenessConstraintSpec("Membership", listOf("tenantId", "userId"))
        ).single() as SchemaStatement.Cypher

        assertTrue(statement.statement.contains("REQUIRE (n.`tenantId`, n.`userId`) IS UNIQUE"))
    }

    // ----- DDL: drops -----

    @Test
    fun `drops are by name with IF EXISTS`() {
        val index = SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"), name = "person_name_range")
        val constraint = SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, "Person", listOf("id"), name = "person_id_unique")

        assertEquals(
            listOf(SchemaStatement.Cypher("DROP INDEX `person_name_range` IF EXISTS")),
            grammar.dropIndex(index)
        )
        assertEquals(
            listOf(SchemaStatement.Cypher("DROP CONSTRAINT `person_id_unique` IF EXISTS")),
            grammar.dropConstraint(constraint)
        )
    }

    @Test
    fun `dropping an unnamed item fails loudly`() {
        val unnamed = SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("name"))
        assertThrows<DrivineException> { grammar.dropIndex(unnamed) }
    }

    // ----- Introspection parsing -----

    @Test
    fun `parses vector and range rows, skipping unmanaged index types`() {
        val rows = listOf(
            mapOf(
                "name" to "prop_embedding_vector", "type" to "VECTOR", "entityType" to "NODE",
                "labelsOrTypes" to listOf("Proposition"), "properties" to listOf("embedding"),
                "options" to mapOf(
                    "indexConfig" to mapOf(
                        "vector.dimensions" to 1536L,
                        "vector.similarity_function" to "COSINE",
                    )
                ),
            ),
            mapOf(
                "name" to "prop_context_range", "type" to "RANGE", "entityType" to "NODE",
                "labelsOrTypes" to listOf("Proposition"), "properties" to listOf("contextId"),
                "options" to null,
            ),
            // not managed — must be skipped
            mapOf(
                "name" to "token_lookup", "type" to "LOOKUP", "entityType" to "NODE",
                "labelsOrTypes" to null, "properties" to null, "options" to null,
            ),
            mapOf(
                "name" to "fulltext_idx", "type" to "FULLTEXT", "entityType" to "NODE",
                "labelsOrTypes" to listOf("Doc"), "properties" to listOf("text"), "options" to null,
            ),
        )

        val items = grammar.parseIndexRows(rows)

        assertEquals(2, items.size)
        val vector = items.first { it.kind == SchemaItemKind.VECTOR_INDEX }
        assertEquals("Proposition", vector.label)
        assertEquals(listOf("embedding"), vector.properties)
        assertEquals(1536, vector.dimensions)
        assertEquals(SimilarityFunction.COSINE, vector.similarity)
        assertEquals("prop_embedding_vector", vector.name)

        val range = items.first { it.kind == SchemaItemKind.RANGE_INDEX }
        assertEquals(listOf("contextId"), range.properties)
    }

    @Test
    fun `skips relationship indexes and constraints`() {
        val indexRows = listOf(
            mapOf(
                "name" to "rel_idx", "type" to "RANGE", "entityType" to "RELATIONSHIP",
                "labelsOrTypes" to listOf("KNOWS"), "properties" to listOf("since"), "options" to null,
            ),
        )
        val constraintRows = listOf(
            mapOf(
                "name" to "rel_unique", "type" to "RELATIONSHIP_PROPERTY_UNIQUENESS", "entityType" to "RELATIONSHIP",
                "labelsOrTypes" to listOf("KNOWS"), "properties" to listOf("id"),
            ),
        )

        assertTrue(grammar.parseIndexRows(indexRows).isEmpty())
        assertTrue(grammar.parseConstraintRows(constraintRows).isEmpty())
    }

    @Test
    fun `parses uniqueness constraint rows, skipping other constraint types`() {
        val rows = listOf(
            mapOf(
                "name" to "session_unique", "type" to "UNIQUENESS", "entityType" to "NODE",
                "labelsOrTypes" to listOf("ChatSession"), "properties" to listOf("sessionId"),
            ),
            mapOf(
                "name" to "existence", "type" to "NODE_PROPERTY_EXISTENCE", "entityType" to "NODE",
                "labelsOrTypes" to listOf("ChatSession"), "properties" to listOf("createdAt"),
            ),
        )

        val items = grammar.parseConstraintRows(rows)

        assertEquals(1, items.size)
        assertEquals(SchemaItemKind.UNIQUENESS_CONSTRAINT, items[0].kind)
        assertEquals("ChatSession", items[0].label)
        assertEquals("session_unique", items[0].name)
    }

    // ----- Matching / drift -----

    @Test
    fun `dimension change is identity match but shape mismatch - drift`() {
        val existing = SchemaItemInfo(
            SchemaItemKind.VECTOR_INDEX, "Proposition", listOf("embedding"),
            name = "x", dimensions = 768, similarity = SimilarityFunction.COSINE,
        )
        val requested = VectorIndexSpec("Proposition", "embedding", 1536)

        assertTrue(grammar.matchesIdentity(existing, requested))
        assertFalse(grammar.matchesShape(existing, requested))
    }

    @Test
    fun `matching vector index is identity and shape match`() {
        val existing = SchemaItemInfo(
            SchemaItemKind.VECTOR_INDEX, "Proposition", listOf("embedding"),
            name = "x", dimensions = 1536, similarity = SimilarityFunction.COSINE,
        )
        val requested = VectorIndexSpec("Proposition", "embedding", 1536)

        assertTrue(grammar.matchesIdentity(existing, requested))
        assertTrue(grammar.matchesShape(existing, requested))
    }

    // ----- Violations -----

    @Test
    fun `recognizes constraint violation errors`() {
        assertTrue(
            grammar.isConstraintViolation(
                RuntimeException(
                    "Unable to create Constraint( name='x', type='UNIQUENESS' ): " +
                        "Both Node(0) and Node(1) have the label `Person` and property `email` = 'a@b.c'"
                )
            )
        )
        assertFalse(grammar.isConstraintViolation(RuntimeException("Connection refused")))
    }
}