package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.neo4j.driver.exceptions.Neo4jException

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

    @Test
    fun `vector index DDL derives the default name when the explicit name is EMPTY, not just null`() {
        // An empty name MUST derive the default — otherwise the engine rejects the DDL with "name cannot
        // be the empty string" (the dice Proposition_embedding_vector boot failure on a fresh Neo4j).
        // FragmentSchemaScanner already maps `@VectorIndex(name = "")` to null, so the reachable source of
        // an empty name is a hand-written SchemaCatalog spec or ensureVector(name = ""). See
        // SchemaItemSpecNameTest for the empty-vs-whitespace rules.
        val spec = VectorIndexSpec("Proposition", "embedding", 1536, name = "")
        val statement = grammar.createIndex(spec).single() as SchemaStatement.Cypher

        assertTrue(
            statement.statement.contains("CREATE VECTOR INDEX `Proposition_embedding_vector` IF NOT EXISTS"),
            statement.statement,
        )
        assertFalse(statement.statement.contains("INDEX `` "), "must not emit an empty index name: ${statement.statement}")
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
    fun `a name with a dot, a space and a backtick is one quoted name, its backtick doubled`() {
        val name = "my.odd `name"
        val range = grammar.createIndex(RangeIndexSpec(name, name)).single() as SchemaStatement.Cypher
        val vector = grammar.createIndex(VectorIndexSpec(name, name, 3, name = name)).single() as SchemaStatement.Cypher
        val fulltext = grammar.createIndex(FullTextIndexSpec(name, listOf(name), name = name)).single() as SchemaStatement.Cypher
        val constraint = grammar.createConstraint(UniquenessConstraintSpec(name, name)).single() as SchemaStatement.Cypher
        val item = SchemaItemInfo(SchemaItemKind.RANGE_INDEX, name, listOf(name), name)
        val dropIndex = grammar.dropIndex(item).single() as SchemaStatement.Cypher
        val dropConstraint = grammar.dropConstraint(item.copy(kind = SchemaItemKind.UNIQUENESS_CONSTRAINT)).single() as SchemaStatement.Cypher

        assertEquals(
            "CREATE INDEX `my.odd ``name_my.odd ``name_range` IF NOT EXISTS FOR (n:`my.odd ``name`) ON (n.`my.odd ``name`)",
            range.statement,
        )
        assertTrue(vector.statement.contains("CREATE VECTOR INDEX `my.odd ``name` IF NOT EXISTS"), vector.statement)
        assertTrue(vector.statement.contains("FOR (n:`my.odd ``name`) ON (n.`my.odd ``name`)"), vector.statement)
        assertTrue(fulltext.statement.contains("CREATE FULLTEXT INDEX `my.odd ``name` IF NOT EXISTS"), fulltext.statement)
        assertTrue(fulltext.statement.contains("FOR (n:`my.odd ``name`) ON EACH [n.`my.odd ``name`]"), fulltext.statement)
        assertEquals(
            "CREATE CONSTRAINT `my.odd ``name_my.odd ``name_unique` IF NOT EXISTS " +
                "FOR (n:`my.odd ``name`) REQUIRE (n.`my.odd ``name`) IS UNIQUE",
            constraint.statement,
        )
        assertEquals("DROP INDEX `my.odd ``name` IF EXISTS", dropIndex.statement)
        assertEquals("DROP CONSTRAINT `my.odd ``name` IF EXISTS", dropConstraint.statement)
    }

    // ----- DDL: fulltext index -----

    @Test
    fun `fulltext index DDL uses ON EACH with a bracketed property list`() {
        val statement = grammar.createIndex(
            FullTextIndexSpec("Chunk", listOf("title", "body"))
        ).single() as SchemaStatement.Cypher

        assertTrue(statement.statement.contains("CREATE FULLTEXT INDEX `Chunk_title_body_fulltext` IF NOT EXISTS"))
        assertTrue(statement.statement.contains("FOR (n:`Chunk`) ON EACH [n.`title`, n.`body`]"))
        // no analyzer declared → empty indexConfig, so Neo4j applies its own default
        assertTrue(statement.statement.contains("OPTIONS {indexConfig: {}}"))
    }

    @Test
    fun `fulltext index DDL respects explicit name and analyzer`() {
        val statement = grammar.createIndex(
            FullTextIndexSpec("Chunk", "body", name = "my_ft", analyzer = "english")
        ).single() as SchemaStatement.Cypher

        assertTrue(statement.statement.contains("CREATE FULLTEXT INDEX `my_ft` IF NOT EXISTS"))
        assertTrue(statement.statement.contains("`fulltext.analyzer`: 'english'"))
    }

    @Test
    fun `analyzer drifts only when both the spec and the engine report one`() {
        val spec = FullTextIndexSpec("Chunk", listOf("title"), analyzer = "english")
        val existingEnglish = SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Chunk", listOf("title"), analyzer = "english")
        val existingStandard = SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Chunk", listOf("title"), analyzer = "standard")
        val existingUnknown = SchemaItemInfo(SchemaItemKind.FULLTEXT_INDEX, "Chunk", listOf("title"), analyzer = null)

        assertTrue(grammar.matchesShape(existingEnglish, spec))
        assertFalse(grammar.matchesShape(existingStandard, spec))
        // engine reports no analyzer → unobservable, never drift
        assertTrue(grammar.matchesShape(existingUnknown, spec))
        // spec declares no analyzer → never drift whatever the engine reports
        assertTrue(grammar.matchesShape(existingStandard, FullTextIndexSpec("Chunk", listOf("title"))))
    }

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
    fun `parses vector, range and fulltext rows, skipping unmanaged index types`() {
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
            // fulltext is a managed kind as of 0.0.60, incl. its analyzer
            mapOf(
                "name" to "doc_text_fulltext", "type" to "FULLTEXT", "entityType" to "NODE",
                "labelsOrTypes" to listOf("Doc"), "properties" to listOf("title", "body"),
                "options" to mapOf("indexConfig" to mapOf("fulltext.analyzer" to "english")),
            ),
            // not managed — must be skipped
            mapOf(
                "name" to "token_lookup", "type" to "LOOKUP", "entityType" to "NODE",
                "labelsOrTypes" to null, "properties" to null, "options" to null,
            ),
            mapOf(
                "name" to "point_idx", "type" to "POINT", "entityType" to "NODE",
                "labelsOrTypes" to listOf("Place"), "properties" to listOf("location"), "options" to null,
            ),
        )

        val items = grammar.parseIndexRows(rows)

        assertEquals(3, items.size)
        val vector = items.first { it.kind == SchemaItemKind.VECTOR_INDEX }
        assertEquals("Proposition", vector.label)
        assertEquals(listOf("embedding"), vector.properties)
        assertEquals(1536, vector.dimensions)
        assertEquals(SimilarityFunction.COSINE, vector.similarity)
        assertEquals("prop_embedding_vector", vector.name)

        val range = items.first { it.kind == SchemaItemKind.RANGE_INDEX }
        assertEquals(listOf("contextId"), range.properties)

        val fulltext = items.first { it.kind == SchemaItemKind.FULLTEXT_INDEX }
        assertEquals("Doc", fulltext.label)
        assertEquals(listOf("title", "body"), fulltext.properties)
        assertEquals("doc_text_fulltext", fulltext.name)
        assertEquals("english", fulltext.analyzer)
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

    /** An error as the driver gives it, with a GQL status and the error beneath it. */
    private fun error(status: String, code: String, message: String, cause: Neo4jException? = null) =
        Neo4jException(status, message, code, message, emptyMap(), cause)

    @Test
    fun `a violation is known by the GQL status a server gives beneath the refusal`() {
        val refusal = error(
            "50N11", "Neo.DatabaseError.Schema.ConstraintCreationFailed", "Unable to create Constraint( name='x', type='NODE PROPERTY UNIQUENESS' ):",
            error("22N79", "N/A", "Property uniqueness constraint violated: Both Node(0) and Node(1) have the label `Person` and property `email` = 'a@b.c'."),
        )

        assertTrue(grammar.isConstraintViolation(DrivineException(refusal.message!!, refusal)))
    }

    @Test
    fun `an index entry conflict beneath the refusal is a violation too`() {
        val refusal = error(
            "50N11", "Neo.DatabaseError.Schema.ConstraintCreationFailed", "Unable to create Constraint( name='x', type='NODE PROPERTY UNIQUENESS' ):",
            error("22N80", "N/A", "Index entry conflict: Both Node(0) and Node(1) have the label `Person` and property `email` = 'a@b.c'."),
        )

        assertTrue(grammar.isConstraintViolation(DrivineException(refusal.message!!, refusal)))
    }

    @Test
    fun `a refusal to make a constraint whose GQL status names another cause is not a violation`() {
        val refusal = error(
            "50N11", "Neo.DatabaseError.Schema.ConstraintCreationFailed", "Unable to create Constraint( type='NODE KEY', schema=(:U {e}) ):",
            error("51N27", "N/A", "Key constraint is not supported in community edition."),
        )

        assertFalse(grammar.isConstraintViolation(DrivineException(refusal.message!!, refusal)))
    }

    @Test
    fun `a server older than GQL statuses names a violation by its code alone`() {
        assertTrue(grammar.isConstraintViolation(Neo4jException("Neo.DatabaseError.Schema.ConstraintCreationFailed", "Unable to create Constraint( name='x' ):")))
        assertFalse(grammar.isConstraintViolation(Neo4jException("Neo.ClientError.Schema.IndexAlreadyExists", "There already exists an index (:Person {email}).")))
    }

    @Test
    fun `a write the constraint refuses is a violation whatever GQL status it carries`() {
        assertTrue(
            grammar.isConstraintViolation(
                error("22N41", "Neo.ClientError.Schema.ConstraintValidationFailed", "Node(0) already exists with label `Person` and property `email` = 'a@b.c'")
            )
        )
    }

    @Test
    fun `an index in the way of a constraint is known by its code`() {
        assertTrue(
            grammar.isBlockedByIndex(
                DrivineException("wrapped", Neo4jException("Neo.ClientError.Schema.IndexAlreadyExists", "There already exists an index (:Person {email}). A constraint cannot be created until the index has been dropped."))
            )
        )
        assertFalse(grammar.isBlockedByIndex(Neo4jException("Neo.ClientError.Schema.IndexWithNameAlreadyExists", "There already exists an index called 'person_email'.")))
        assertFalse(grammar.isBlockedByIndex(RuntimeException("There already exists an index (:Person {email}).")))
    }

    @Test
    fun `an error without a driver's code is not a violation, whatever it says`() {
        assertFalse(grammar.isConstraintViolation(RuntimeException("Unable to create Constraint: both nodes have the same value")))
        assertFalse(grammar.isConstraintViolation(RuntimeException("Connection refused")))
    }

    // ----- Conflict sampling -----

    @Test
    fun `conflict sampling groups the labelled nodes that hold every key, and returns the keys that repeat as a map with their count and nodes`() {
        val query = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", listOf("email", "tenant")), 5)

        assertEquals(
            """
            MATCH (n:Person)
            WHERE n.email IS NOT NULL AND n.tenant IS NOT NULL
            WITH n.email AS _k0, n.tenant AS _k1, count(*) AS _count, collect({elementId: elementId(n)}) AS _nodes
            WHERE _count > 1
            RETURN {properties: {email: _k0, tenant: _k1}, count: _count, nodes: _nodes[0..5]}
            LIMIT 5
            """.trimIndent(),
            query,
        )
    }

    @Test
    fun `a conflicting node is named by the fragment's id too, unless that is a key`() {
        val byUuid = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "uuid"), 3)
        assertTrue(byUuid.contains("collect({elementId: elementId(n), uuid: n.uuid}) AS _nodes"), byUuid)

        val byKey = grammar.sampleConflictsQuery(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "email"), 3)
        assertTrue(byKey.contains("collect({elementId: elementId(n)}) AS _nodes"), byKey)
    }

    @Test
    fun `Neo4j 4 names a node by id, having no elementId`() {
        val query = CypherDialect.NEO4J_4.schemaGrammar().sampleConflictsQuery(UniquenessConstraintSpec("Person", "email"), 3)

        assertTrue(query.contains("collect({elementId: id(n)}) AS _nodes"), query)
    }
}