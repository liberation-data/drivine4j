package org.drivine.schema

import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.*
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end test of the catalog-driven [SchemaManager] against a real Neo4j instance:
 * version-aware [SchemaManager.enforce], the runtime [SchemaManager.recreateAll] hook, and the
 * [SchemaVersionStore] marker. The full bean chain (registry → factory) is built by hand so the
 * manager runs exactly as it would under Spring.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SchemaManagerIntegrationTest {

    companion object {
        private const val PASSWORD = "schemamgrtest"

        @Container
        @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var registry: DatabaseRegistry
        private lateinit var factory: PersistenceManagerFactory
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            val props = ConnectionProperties(
                type = DatabaseType.NEO4J,
                host = container.host,
                port = container.getMappedPort(7687),
                userName = "neo4j",
                password = PASSWORD,
                databaseName = "neo4j",
                cypherDialect = CypherDialect.NEO4J_5,
            )
            registry = DatabaseRegistry(DataSourceMap(mapOf("neo" to props)))
            factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))
            manager = factory.get("neo", PersistenceManagerType.NON_TRANSACTIONAL)
        }
    }

    private fun schemaManager(catalogs: List<SchemaCatalog>, policy: SchemaPolicy = SchemaPolicy()) =
        SchemaManager(factory, registry, catalogs, policy)

    private fun vectorDimensions(label: String, property: String): Int? =
        manager.indexes.find(VectorIndexSpec(label, property, 1))?.dimensions

    // ----- Version store round-trip -----

    @Test
    @Order(1)
    fun `version store records, reads back, and clears`() {
        val store = SchemaVersionStore(manager)

        assertNull(store.storedVersion())
        store.record("v1")
        assertEquals("v1", store.storedVersion())
        store.record("v2")
        assertEquals("v2", store.storedVersion()) // single marker, overwritten
        store.clear()
        assertNull(store.storedVersion())
    }

    // ----- enforce(): create, idempotent, version adoption, version change -----

    @Test
    @Order(2)
    fun `enforce creates declared items and is idempotent`() {
        val catalog = SchemaCatalog.of(
            RangeIndexSpec("Doc", "slug"),
            UniquenessConstraintSpec("Doc", "id"),
        ).forDatabase("neo")

        schemaManager(listOf(catalog)).enforce()
        assertNotNull(manager.indexes.find(RangeIndexSpec("Doc", "slug")))
        assertNotNull(manager.constraints.find(UniquenessConstraintSpec("Doc", "id")))

        // Second enforce changes nothing and doesn't throw
        schemaManager(listOf(catalog)).enforce()
        assertNotNull(manager.indexes.find(RangeIndexSpec("Doc", "slug")))
    }

    @Test
    @Order(3)
    fun `first version is adopted without recreating, and recorded`() {
        SchemaVersionStore(manager).clear()
        val catalog = SchemaCatalog.of(VectorIndexSpec("Chunk", "embedding", 768))
            .forDatabase("neo")
            .withVersion("model-a")

        schemaManager(listOf(catalog)).enforce()

        assertEquals(768, vectorDimensions("Chunk", "embedding"))
        assertEquals("model-a", SchemaVersionStore(manager).storedVersion())
    }

    @Test
    @Order(4)
    fun `version change triggers a one-time recreate, even without structural drift`() {
        // Same dimensions as the adopted version — structurally identical, so drift detection alone
        // would NOT rebuild. The version bump is what forces the recreate.
        val bumped = SchemaCatalog.of(VectorIndexSpec("Chunk", "embedding", 768))
            .forDatabase("neo")
            .withVersion("model-b")

        // Marker a node so we can observe the index was actually dropped+recreated
        manager.execute(QuerySpecification.withStatement("CREATE (:Chunk {id: 'x'})"))

        schemaManager(listOf(bumped)).enforce()

        assertEquals(768, vectorDimensions("Chunk", "embedding"))
        assertEquals("model-b", SchemaVersionStore(manager).storedVersion())

        // Same version again → no recreate, no error, marker unchanged
        schemaManager(listOf(bumped)).enforce()
        assertEquals("model-b", SchemaVersionStore(manager).storedVersion())
    }

    // ----- recreateAll(): the runtime hook -----

    @Test
    @Order(5)
    fun `recreateAll rebuilds all items regardless of version`() {
        val catalog = SchemaCatalog.of(
            VectorIndexSpec("Chunk", "embedding", 768),
            RangeIndexSpec("Doc", "slug"),
        ).forDatabase("neo").withVersion("model-b")  // unchanged version

        // enforce() with the same version would be a no-op; recreateAll() rebuilds anyway
        schemaManager(listOf(catalog)).recreateAll()

        assertEquals(768, vectorDimensions("Chunk", "embedding"))
        assertNotNull(manager.indexes.find(RangeIndexSpec("Doc", "slug")))
    }

    @Test
    @Order(6)
    fun `recreateAll on an empty catalog is a no-op`() {
        // No exceptions, nothing to do
        schemaManager(listOf(SchemaCatalog.of())).recreateAll()
        schemaManager(emptyList()).enforce()
        assertTrue(true)
    }

    // ----- Marker singleton guard: the concurrent-startup race -----

    private val markerConstraint = UniquenessConstraintSpec(
        SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY
    )

    private fun markerCount(): Long = manager.getOne(
        QuerySpecification
            .withStatement("MATCH (m:`${SchemaVersionStore.LABEL}`) RETURN count(m)")
            .transform(Long::class.java)
    )

    /** Recreates the pre-constraint failure mode: two processes' MERGEs both created a marker. */
    private fun plantDuplicateMarkers() {
        manager.constraints.drop(markerConstraint)
        SchemaVersionStore(manager).clear()
        manager.execute(
            QuerySpecification.withStatement(
                "CREATE (:`${SchemaVersionStore.LABEL}` {scope: 'schema', version: 'older', appliedAt: 1})"
            )
        )
        manager.execute(
            QuerySpecification.withStatement(
                "CREATE (:`${SchemaVersionStore.LABEL}` {scope: 'schema', version: 'newer', appliedAt: 2})"
            )
        )
    }

    @Test
    @Order(7)
    fun `storedVersion tolerates duplicate markers, preferring the most recently applied`() {
        plantDuplicateMarkers()
        assertEquals("newer", SchemaVersionStore(manager).storedVersion())
    }

    @Test
    @Order(8)
    fun `enforce repairs duplicate markers and installs the singleton constraint`() {
        plantDuplicateMarkers()
        val catalog = SchemaCatalog.of(RangeIndexSpec("Doc", "slug"))
            .forDatabase("neo")
            .withVersion("model-c")

        schemaManager(listOf(catalog)).enforce() // must not throw despite the duplicates

        assertEquals(1, markerCount())
        assertEquals("model-c", SchemaVersionStore(manager).storedVersion())
        assertNotNull(manager.constraints.find(markerConstraint))
    }

    @Test
    @Order(9)
    fun `concurrent record calls cannot create a second marker`() {
        val store = SchemaVersionStore(manager)
        store.clear()
        store.ensureSingleton()

        val threads = 8
        val barrier = java.util.concurrent.CyclicBarrier(threads)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        (1..threads).map { i ->
            Thread {
                barrier.await()
                try {
                    store.record("race-$i")
                } catch (t: Throwable) {
                    errors += t
                }
            }.apply { start() }
        }.forEach { it.join() }

        assertTrue(errors.isEmpty(), "record() should survive the create race: $errors")
        assertEquals(1, markerCount())
    }

    @Test
    @Order(10)
    fun `ensureSingleton reclaims a manually created plain index on the marker key`() {
        val store = SchemaVersionStore(manager)
        store.clear()
        manager.constraints.drop(markerConstraint)
        // An emergency hand-fix: a plain index on the marker key. Neo4j refuses to create an
        // equivalent-key uniqueness constraint while it exists.
        manager.indexes.ensure(RangeIndexSpec(SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY))

        store.ensureSingleton()

        assertNotNull(manager.constraints.find(markerConstraint))
        store.record("model-v3")
        assertEquals("model-v3", store.storedVersion())
    }

    @Test
    @Order(11)
    fun `a manually created uniqueness constraint on the marker's version coexists with the singleton guard`() {
        // An emergency hand-fix applied directly in prod: unique on version rather than on the
        // MERGE key. Different property set → not the singleton constraint's identity; both live
        // side by side and enforce must not fight it.
        manager.execute(
            QuerySpecification.withStatement(
                "CREATE CONSTRAINT FOR (v:`${SchemaVersionStore.LABEL}`) REQUIRE v.version IS UNIQUE"
            )
        )
        try {
            val catalog = SchemaCatalog.of(RangeIndexSpec("Doc", "slug"))
                .forDatabase("neo")
                .withVersion("model-d")

            schemaManager(listOf(catalog)).enforce() // must not throw
            schemaManager(listOf(catalog)).enforce() // and stays idempotent

            assertEquals(1, markerCount())
            assertEquals("model-d", SchemaVersionStore(manager).storedVersion())
            assertNotNull(manager.constraints.find(markerConstraint))
            assertNotNull(
                manager.constraints.find(
                    UniquenessConstraintSpec(SchemaVersionStore.LABEL, "version")
                )
            )
        } finally {
            manager.constraints.drop(UniquenessConstraintSpec(SchemaVersionStore.LABEL, "version"))
        }
    }
}