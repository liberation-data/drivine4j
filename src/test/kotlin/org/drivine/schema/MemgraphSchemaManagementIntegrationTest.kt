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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * End-to-end schema management against a real Memgraph instance (Bolt protocol via
 * [Neo4jConnectionProvider] with the MEMGRAPH dialect).
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MemgraphSchemaManagementIntegrationTest {

    companion object {

        @Container
        @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687)
            .waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "memgraph-schema-test",
                type = DatabaseType.MEMGRAPH,
                host = container.host,
                port = container.getMappedPort(7687),
                user = "",
                password = "",
                database = null,
                config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH,
            )
            manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.MEMGRAPH, SubtypeRegistry())
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

        val again = manager.indexes.ensure(spec)
        assertTrue(again is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $again")

        val drifted = manager.indexes.ensure(VectorIndexSpec("Proposition", "embedding", 1536))
        assertTrue(drifted is EnsureResult.Drift, "expected Drift, got $drifted")

        val recreated = manager.indexes.recreate(VectorIndexSpec("Proposition", "embedding", 1536))
        assertEquals(1536, recreated.current.dimensions)
    }

    // ----- Range index lifecycle -----

    @Test
    @Order(2)
    fun `range index - single property`() {
        val created = manager.indexes.ensure(RangeIndexSpec("Proposition", "contextId"))
        assertTrue(created is EnsureResult.Created, "expected Created, got $created")

        val again = manager.indexes.ensure(RangeIndexSpec("Proposition", "contextId"))
        assertTrue(again is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $again")
    }

    @Test
    @Order(3)
    fun `range index - composite`() {
        val created = manager.indexes.ensure(RangeIndexSpec("Message", listOf("sessionId", "createdAt")))
        assertTrue(created is EnsureResult.Created, "expected Created, got $created")

        val again = manager.indexes.ensure(RangeIndexSpec("Message", listOf("sessionId", "createdAt")))
        assertTrue(again is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $again")
    }

    // ----- Fulltext (text) index lifecycle -----

    @Test
    fun `fulltext index - named multi-property text index create, idempotent, drop`() {
        val spec = FullTextIndexSpec("Article", listOf("title", "body"))

        val created = manager.indexes.ensure(spec)
        assertTrue(created is EnsureResult.Created, "expected Created, got $created")

        val info = manager.indexes.find(spec)!!
        assertEquals(SchemaItemKind.FULLTEXT_INDEX, info.kind)
        assertEquals("Article_title_body_fulltext", info.name)
        assertEquals(setOf("title", "body"), info.properties.toSet())
        // Memgraph does not report an analyzer, so a spec's analyzer is never drift here
        assertNull(info.analyzer)

        val again = manager.indexes.ensure(spec)
        assertTrue(again is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $again")

        // An analyzer on the spec is silently unverified (not drift) on Memgraph
        val withAnalyzer = manager.indexes.ensure(
            FullTextIndexSpec("Article", listOf("title", "body"), analyzer = "english")
        )
        assertTrue(withAnalyzer is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $withAnalyzer")

        assertTrue(manager.indexes.drop(spec))
        assertNull(manager.indexes.find(spec))
    }

    // ----- Uniqueness constraint lifecycle -----

    @Test
    @Order(4)
    fun `uniqueness constraint - ensure creates and is enforced by the engine`() {
        val created = manager.constraints.ensure(UniquenessConstraintSpec("ChatSession", "sessionId"))
        assertTrue(created is EnsureResult.Created, "expected Created, got $created")

        val again = manager.constraints.ensure(UniquenessConstraintSpec("ChatSession", "sessionId"))
        assertTrue(again is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $again")

        execute("CREATE (:ChatSession {sessionId: 'session-1'})")
        assertThrows<Exception> {
            execute("CREATE (:ChatSession {sessionId: 'session-1'})")
        }
    }

    @Test
    @Order(5)
    fun `composite uniqueness constraint`() {
        val created = manager.constraints.ensure(
            UniquenessConstraintSpec("Membership", listOf("tenantId", "userId"))
        )
        assertTrue(created is EnsureResult.Created, "expected Created, got $created")
    }

    // ----- Violation -----

    @Test
    @Order(6)
    fun `constraint on violating data returns Violation with conflicting sample`() {
        execute("CREATE (:Person {email: 'dup@example.com', name: 'A'})")
        execute("CREATE (:Person {email: 'dup@example.com', name: 'B'})")

        val result = manager.constraints.ensure(UniquenessConstraintSpec("Person", "email"))

        assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
        assertTrue((result as EnsureResult.Violation).conflictingSample.isNotEmpty())
    }

    // ----- Drop -----

    @Test
    @Order(7)
    fun `drop index and constraint`() {
        assertTrue(manager.indexes.drop(RangeIndexSpec("Proposition", "contextId")))
        assertNull(manager.indexes.find(RangeIndexSpec("Proposition", "contextId")))

        assertTrue(manager.constraints.drop(UniquenessConstraintSpec("ChatSession", "sessionId")))
        assertNull(manager.constraints.find(UniquenessConstraintSpec("ChatSession", "sessionId")))
    }

    @Test
    @Order(8)
    fun `schema version marker round-trips`() {
        val store = SchemaVersionStore(manager)
        store.clear()
        assertNull(store.storedVersion())
        store.record("model-v1")
        assertEquals("model-v1", store.storedVersion())
        store.clear()
        assertNull(store.storedVersion())
    }

    @Test
    @Order(9)
    fun `ensureSingleton repairs duplicate markers and constrains the marker key`() {
        val store = SchemaVersionStore(manager)
        store.clear()
        // The pre-constraint race: two processes' MERGEs both created a marker
        listOf("'older', appliedAt: 1", "'newer', appliedAt: 2").forEach {
            manager.execute(
                QuerySpecification.withStatement(
                    "CREATE (:`${SchemaVersionStore.LABEL}` {scope: 'schema', version: $it})"
                )
            )
        }

        store.ensureSingleton()

        val count = manager.getOne(
            QuerySpecification
                .withStatement("MATCH (m:`${SchemaVersionStore.LABEL}`) RETURN count(m)")
                .transform(Long::class.java)
        )
        assertEquals(1, count)
        assertEquals("newer", store.storedVersion())
        assertNotNull(
            manager.constraints.find(
                UniquenessConstraintSpec(SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY)
            )
        )
        store.record("model-v2")
        assertEquals("model-v2", store.storedVersion())
    }
}