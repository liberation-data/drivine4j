package org.drivine.schema

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * End-to-end schema management against a real Neo4j instance:
 * the full public path persistenceManager.indexes / .constraints → SchemaGrammar → engine.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Neo4jSchemaManagementIntegrationTest {

    companion object {
        private const val PASSWORD = "schematest"

        @Container
        @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(
            DockerImageName.parse("neo4j:5.26.1-community")
        ).apply {
            withAdminPassword(PASSWORD)
        }

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "neo4j-schema-test",
                type = DatabaseType.NEO4J,
                host = container.host,
                port = container.getMappedPort(7687),
                user = "neo4j",
                password = PASSWORD,
                database = null,
                config = emptyMap(),
                cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.NEO4J, SubtypeRegistry())
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            provider.end()
        }
    }

    private fun execute(cypher: String) {
        manager.execute(QuerySpecification.withStatement(cypher))
    }

    // ----- Vector index lifecycle -----

    @Test
    @Order(1)
    fun `vector index - ensure creates, ensure again matches, dimension change drifts, recreate replaces`() {
        val spec = VectorIndexSpec("Proposition", "embedding", 768)

        val created = manager.indexes.ensure(spec)
        assertTrue(created is EnsureResult.Created, "expected Created, got $created")
        val createdInfo = (created as EnsureResult.Created).info
        assertEquals(768, createdInfo.dimensions)
        assertEquals(SimilarityFunction.COSINE, createdInfo.similarity)

        val again = manager.indexes.ensure(spec)
        assertTrue(again is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $again")

        // Same label/property, different dimensions → drift, nothing changed
        val drifted = manager.indexes.ensure(VectorIndexSpec("Proposition", "embedding", 1536))
        assertTrue(drifted is EnsureResult.Drift, "expected Drift, got $drifted")
        assertEquals(768, (drifted as EnsureResult.Drift).existing.dimensions)

        // Explicit recreate replaces the index
        val recreated = manager.indexes.recreate(VectorIndexSpec("Proposition", "embedding", 1536))
        assertEquals(768, recreated.previous?.dimensions)
        assertEquals(1536, recreated.current.dimensions)

        val found = manager.indexes.find(VectorIndexSpec("Proposition", "embedding", 1536))
        assertNotNull(found)
        assertEquals(1536, found!!.dimensions)
    }

    // ----- Range index lifecycle -----

    @Test
    @Order(2)
    fun `range index - single property and composite`() {
        val single = manager.indexes.ensure(RangeIndexSpec("Proposition", "contextId"))
        assertTrue(single is EnsureResult.Created, "expected Created, got $single")

        val singleAgain = manager.indexes.ensure(RangeIndexSpec("Proposition", "contextId"))
        assertTrue(singleAgain is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $singleAgain")

        val composite = manager.indexes.ensure(RangeIndexSpec("Message", listOf("sessionId", "createdAt")))
        assertTrue(composite is EnsureResult.Created, "expected Created, got $composite")
        assertEquals(
            listOf("sessionId", "createdAt"),
            (composite as EnsureResult.Created).info.properties
        )

        val compositeAgain = manager.indexes.ensure(RangeIndexSpec("Message", listOf("sessionId", "createdAt")))
        assertTrue(compositeAgain is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $compositeAgain")
    }

    // ----- Uniqueness constraint lifecycle -----

    @Test
    @Order(3)
    fun `uniqueness constraint - ensure creates and is enforced by the engine`() {
        val created = manager.constraints.ensure(UniquenessConstraintSpec("ChatSession", "sessionId"))
        assertTrue(created is EnsureResult.Created, "expected Created, got $created")

        val again = manager.constraints.ensure(UniquenessConstraintSpec("ChatSession", "sessionId"))
        assertTrue(again is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $again")

        // The constraint is real: duplicate inserts must fail
        execute("CREATE (:ChatSession {sessionId: 'session-1'})")
        assertThrows<Exception> {
            execute("CREATE (:ChatSession {sessionId: 'session-1'})")
        }
    }

    @Test
    @Order(4)
    fun `composite uniqueness constraint`() {
        val created = manager.constraints.ensure(
            UniquenessConstraintSpec("Membership", listOf("tenantId", "userId"))
        )
        assertTrue(created is EnsureResult.Created, "expected Created, got $created")
        assertEquals(listOf("tenantId", "userId"), (created as EnsureResult.Created).info.properties)
    }

    // ----- Violation -----

    @Test
    @Order(5)
    fun `constraint on violating data returns Violation with conflicting sample`() {
        execute("CREATE (:Person {email: 'dup@example.com', name: 'A'})")
        execute("CREATE (:Person {email: 'dup@example.com', name: 'B'})")

        val result = manager.constraints.ensure(UniquenessConstraintSpec("Person", "email"))

        assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
        val violation = result as EnsureResult.Violation
        assertTrue(violation.conflictingSample.isNotEmpty(), "expected a conflicting-row sample")

        // Nothing was created
        assertNull(manager.constraints.find(UniquenessConstraintSpec("Person", "email")))
    }

    // ----- Drop -----

    @Test
    @Order(6)
    fun `drop index and constraint`() {
        assertTrue(manager.indexes.drop(RangeIndexSpec("Proposition", "contextId")))
        assertNull(manager.indexes.find(RangeIndexSpec("Proposition", "contextId")))
        // Dropping again is a no-op
        assertFalse(manager.indexes.drop(RangeIndexSpec("Proposition", "contextId")))

        assertTrue(manager.constraints.drop(UniquenessConstraintSpec("ChatSession", "sessionId")))
        assertNull(manager.constraints.find(UniquenessConstraintSpec("ChatSession", "sessionId")))
    }

    // ----- List -----

    @Test
    @Order(7)
    fun `list reflects managed items`() {
        val indexes = manager.indexes.list()
        val constraints = manager.constraints.list()

        // From earlier tests: Proposition.embedding vector, Message composite range
        // (plus constraint-backing indexes Neo4j creates internally, which appear as RANGE)
        assertTrue(indexes.any { it.kind == SchemaItemKind.VECTOR_INDEX && it.label == "Proposition" })
        assertTrue(indexes.any { it.kind == SchemaItemKind.RANGE_INDEX && it.label == "Message" })
        assertTrue(constraints.any { it.label == "Membership" })
    }
}