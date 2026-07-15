package org.drivine.schema

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * End-to-end schema management against a real FalkorDB instance.
 *
 * Exercises the FalkorDB-specific paths:
 *  - unnamed indexes with per-label coverage semantics
 *  - uniqueness constraints via the native Redis command `GRAPH.CONSTRAINT` (not Cypher),
 *    including the required backing index and asynchronous creation (PENDING → OPERATIONAL/FAILED)
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FalkorDbSchemaManagementIntegrationTest {

    companion object {
        private const val GRAPH = "schematest"

        @Container
        @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = FalkorDbConnectionProvider(
                name = "falkordb-schema-test",
                host = container.host,
                port = container.getMappedPort(6379),
                password = null,
                graphName = GRAPH,
            )
            manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.FALKORDB, SubtypeRegistry())

            // FalkorDB creates graphs lazily — make sure the graph exists before schema operations
            manager.execute(QuerySpecification.withStatement("CREATE (:_Init {created: true})"))
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
    fun `range index - single property and composite, with coverage semantics`() {
        val created = manager.indexes.ensure(RangeIndexSpec("Proposition", "contextId"))
        assertTrue(created is EnsureResult.Created, "expected Created, got $created")

        val again = manager.indexes.ensure(RangeIndexSpec("Proposition", "contextId"))
        assertTrue(again is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $again")

        // Composite on the same label: FalkorDB keeps one index per label, so adding more
        // properties extends coverage; the composite spec is then satisfied
        val composite = manager.indexes.ensure(RangeIndexSpec("Proposition", listOf("contextId", "status")))
        assertTrue(
            composite is EnsureResult.Created || composite is EnsureResult.AlreadyMatching,
            "expected Created or AlreadyMatching, got $composite"
        )

        val compositeAgain = manager.indexes.ensure(RangeIndexSpec("Proposition", listOf("contextId", "status")))
        assertTrue(compositeAgain is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $compositeAgain")
    }

    @Test
    @Order(3)
    fun `range index - recreate one property of a composite index drops and rebuilds only that property`() {
        // FalkorDB keeps a single per-label index; build coverage over (a, b)
        manager.indexes.ensure(RangeIndexSpec("Widget", "a"))
        manager.indexes.ensure(RangeIndexSpec("Widget", listOf("a", "b")))
        assertEquals(
            setOf("a", "b"),
            manager.indexes.find(RangeIndexSpec("Widget", "a"))!!.properties.toSet()
        )

        // Recreate only property 'a' — drops are per-property, so 'b' must survive untouched
        val recreated = manager.indexes.recreate(RangeIndexSpec("Widget", "a"))
        assertEquals(EnsureResult.Recreated::class, recreated::class)

        // Both properties remain indexed on the label after the drop-and-rebuild of 'a'
        val after = manager.indexes.find(RangeIndexSpec("Widget", "a"))!!
        assertEquals(setOf("a", "b"), after.properties.toSet())
        assertNotNull(manager.indexes.find(RangeIndexSpec("Widget", "b")))
    }

    // ----- Uniqueness constraints (native GRAPH.CONSTRAINT path) -----

    @Test
    @Order(4)
    fun `uniqueness constraint - created via native Redis command with backing index and async build`() {
        val created = manager.constraints.ensure(UniquenessConstraintSpec("ChatSession", "sessionId"))
        assertTrue(created is EnsureResult.Created, "expected Created, got $created")

        // The backing exact-match index was auto-created
        assertNotNull(manager.indexes.find(RangeIndexSpec("ChatSession", "sessionId")))

        val again = manager.constraints.ensure(UniquenessConstraintSpec("ChatSession", "sessionId"))
        assertTrue(again is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $again")

        // The constraint is real: duplicate inserts must fail
        execute("CREATE (:ChatSession {sessionId: 'session-1'})")
        assertThrows<Exception> {
            execute("CREATE (:ChatSession {sessionId: 'session-1'})")
        }
    }

    @Test
    @Order(5)
    fun `composite uniqueness constraint via native command`() {
        val created = manager.constraints.ensure(
            UniquenessConstraintSpec("Membership", listOf("tenantId", "userId"))
        )
        assertTrue(created is EnsureResult.Created, "expected Created, got $created")
    }

    // ----- Violation (async FAILED path) -----

    @Test
    @Order(6)
    fun `constraint on violating data returns Violation after async build fails`() {
        execute("CREATE (:Person {email: 'dup@example.com', name: 'A'})")
        execute("CREATE (:Person {email: 'dup@example.com', name: 'B'})")

        val result = manager.constraints.ensure(UniquenessConstraintSpec("Person", "email"))

        assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
        assertTrue((result as EnsureResult.Violation).conflictingSample.isNotEmpty())

        // The failed constraint was cleaned up — nothing is left behind
        assertNull(manager.constraints.find(UniquenessConstraintSpec("Person", "email")))
    }

    // ----- Drop -----

    @Test
    @Order(7)
    fun `drop constraint and index`() {
        assertTrue(manager.constraints.drop(UniquenessConstraintSpec("ChatSession", "sessionId")))
        assertNull(manager.constraints.find(UniquenessConstraintSpec("ChatSession", "sessionId")))

        assertTrue(manager.indexes.drop(RangeIndexSpec("ChatSession", "sessionId")))
        assertNull(manager.indexes.find(RangeIndexSpec("ChatSession", "sessionId")))
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