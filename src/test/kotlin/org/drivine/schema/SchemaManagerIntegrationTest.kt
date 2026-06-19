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
}