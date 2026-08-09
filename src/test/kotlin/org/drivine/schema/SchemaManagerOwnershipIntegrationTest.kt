package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.transform
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 2 — named schema catalogs (library / app coexistence) against a real Neo4j instance.
 *
 * Every test is **self-contained**: `@BeforeEach` wipes all data, markers, indexes, and constraints,
 * so the tests carry no ordering and run standalone from the IDE. Behaviours verified:
 *  - each owner gets its own `_DrivineSchema` marker (`scope: <name>` / `scope: 'schema'`);
 *  - versioning and recreation are scoped per owner (an app bump never rebuilds a library's items);
 *  - co-owned (identically declared) items are deduped and never recreated by one owner's version
 *    bump, but `recreateAll(name)` does rebuild them (the documented escape hatch);
 *  - cross-owner conflicts fail fast naming both owners;
 *  - one owner's FAIL_FAST problem does not stop another owner's version being recorded;
 *  - `enforce(name)` / `recreateAll(name)` act selectively;
 *  - an unnamed catalog still uses the historical `scope: 'schema'` marker (backward compatibility).
 */
@Testcontainers
class SchemaManagerOwnershipIntegrationTest {

    companion object {
        private const val PASSWORD = "ownertest"

        @Container
        @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var registry: DatabaseRegistry
        private lateinit var factory: PersistenceManagerFactory
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @org.junit.jupiter.api.BeforeAll
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

    @BeforeEach
    fun clean() {
        manager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
        // Drop every index and constraint so each test starts from a bare schema.
        manager.constraints.list().forEach { manager.constraints.drop(specFor(it)) }
        manager.indexes.list().forEach { manager.indexes.drop(indexSpecFor(it)) }
    }

    private fun schemaManager(catalogs: List<SchemaCatalog>, policy: SchemaPolicy = SchemaPolicy()) =
        SchemaManager(factory, registry, catalogs, policy)

    private fun markerVersion(scope: String): String? = manager.maybeGetOne(
        QuerySpecification
            .withStatement("MATCH (m:`${SchemaVersionStore.LABEL}` {scope: \$scope}) RETURN m.version")
            .bind(mapOf("scope" to scope))
            .transform(String::class.java)
    )

    private fun markerScopes(): List<String> = manager.query(
        QuerySpecification
            .withStatement("MATCH (m:`${SchemaVersionStore.LABEL}`) RETURN m.scope ORDER BY m.scope")
            .transform(String::class.java)
    )

    private fun vectorDims(label: String, property: String): Int? =
        manager.indexes.find(VectorIndexSpec(label, property, 1))?.dimensions

    // ----- Per-owner markers -----

    @Test
    fun `each named owner gets its own marker, unnamed owner uses scope schema`() {
        val app = SchemaCatalog.of(RangeIndexSpec("AppThing", "slug")).forDatabase("neo").withVersion("app-1")
        val rag = SchemaCatalog.of(RangeIndexSpec("RagThing", "slug")).forDatabase("neo")
            .named("rag").withVersion("rag-1")
        val anon = SchemaCatalog.of(RangeIndexSpec("AnonThing", "slug")).forDatabase("neo").withVersion("anon-1")

        schemaManager(listOf(app, rag, anon)).enforce()

        // 'schema' (both app and anon are unnamed → same anonymous owner) and 'rag'
        assertEquals(listOf("rag", "schema"), markerScopes())
        assertEquals("rag-1", markerVersion("rag"))
        // app-1 and anon-1 are combined under the anonymous owner's single marker
        assertEquals("anon-1|app-1", markerVersion("schema"))
    }

    // ----- Independent versioning -----

    @Test
    fun `an app version bump does not change the library's recorded version`() {
        val app1 = SchemaCatalog.of(RangeIndexSpec("AppThing", "slug")).forDatabase("neo").named("app").withVersion("app-1")
        val rag1 = SchemaCatalog.of(RangeIndexSpec("RagThing", "slug")).forDatabase("neo").named("rag").withVersion("rag-1")
        schemaManager(listOf(app1, rag1)).enforce()
        assertEquals("app-1", markerVersion("app"))
        assertEquals("rag-1", markerVersion("rag"))

        // Bump only the app; the library's catalog is unchanged.
        val app2 = SchemaCatalog.of(RangeIndexSpec("AppThing", "slug")).forDatabase("neo").named("app").withVersion("app-2")
        schemaManager(listOf(app2, rag1)).enforce()

        assertEquals("app-2", markerVersion("app"))
        assertEquals("rag-1", markerVersion("rag")) // untouched
    }

    // ----- Recreate is scoped to the owner whose version changed -----

    @Test
    fun `a version bump recreates only that owner's items, not another owner's drifted items`() {
        // WARN so drift doesn't throw; recreateOnDrift=false so only a version change recreates.
        val warn = SchemaPolicy(mode = SchemaMode.WARN)

        val app1 = SchemaCatalog.of(VectorIndexSpec("AppDoc", "embedding", 768)).forDatabase("neo").named("app").withVersion("app-1")
        val rag1 = SchemaCatalog.of(VectorIndexSpec("RagDoc", "embedding", 768)).forDatabase("neo").named("rag").withVersion("rag-1")
        schemaManager(listOf(app1, rag1), warn).enforce()
        assertEquals(768, vectorDims("AppDoc", "embedding"))
        assertEquals(768, vectorDims("RagDoc", "embedding"))

        // Both specs now drift to 1536, but only the app bumps its version.
        val app2 = SchemaCatalog.of(VectorIndexSpec("AppDoc", "embedding", 1536)).forDatabase("neo").named("app").withVersion("app-2")
        val rag1Drift = SchemaCatalog.of(VectorIndexSpec("RagDoc", "embedding", 1536)).forDatabase("neo").named("rag").withVersion("rag-1")
        schemaManager(listOf(app2, rag1Drift), warn).enforce()

        // App's version changed → its vector was recreated to 1536.
        assertEquals(1536, vectorDims("AppDoc", "embedding"))
        // Rag's version is unchanged and recreateOnDrift=false → its drifted vector is left at 768.
        assertEquals(768, vectorDims("RagDoc", "embedding"))
    }

    // ----- Co-ownership -----

    @Test
    fun `identically co-owned items dedupe and are not recreated by one owner's version bump`() {
        val warn = SchemaPolicy(mode = SchemaMode.WARN)

        // Both owners declare the SAME Shared.embedding vector — co-owned.
        val app1 = SchemaCatalog.of(VectorIndexSpec("Shared", "embedding", 768)).forDatabase("neo").named("app").withVersion("app-1")
        val rag1 = SchemaCatalog.of(VectorIndexSpec("Shared", "embedding", 768)).forDatabase("neo").named("rag").withVersion("rag-1")
        schemaManager(listOf(app1, rag1), warn).enforce()
        assertEquals(768, vectorDims("Shared", "embedding"))

        // Both agree the shape should be 1536 (still co-owned, identical), and the app bumps.
        val app2 = SchemaCatalog.of(VectorIndexSpec("Shared", "embedding", 1536)).forDatabase("neo").named("app").withVersion("app-2")
        val rag2 = SchemaCatalog.of(VectorIndexSpec("Shared", "embedding", 1536)).forDatabase("neo").named("rag").withVersion("rag-1")
        schemaManager(listOf(app2, rag2), warn).enforce()

        // Co-owned → app's version bump does NOT recreate it; the index stays 768 (drift only).
        assertEquals(768, vectorDims("Shared", "embedding"))

        // The escape hatch: recreateAll("app") force-rebuilds the co-owned item to 1536.
        schemaManager(listOf(app2, rag2), warn).recreateAll("app")
        assertEquals(1536, vectorDims("Shared", "embedding"))
    }

    // ----- Cross-owner conflict -----

    @Test
    fun `conflicting declarations across owners fail fast naming both owners`() {
        val app = SchemaCatalog.of(VectorIndexSpec("Doc", "embedding", 768)).forDatabase("neo").named("app")
        val rag = SchemaCatalog.of(VectorIndexSpec("Doc", "embedding", 1536)).forDatabase("neo").named("rag")

        val e = assertThrows<DrivineException> { schemaManager(listOf(app, rag)).enforce() }
        assertTrue(e.message!!.contains("'app'"), "message should name owner app: ${e.message}")
        assertTrue(e.message!!.contains("'rag'"), "message should name owner rag: ${e.message}")
    }

    @Test
    fun `a conflict between two other owners does not block a selective enforce`() {
        // 'app' and 'other' conflict on Doc.embedding; 'rag' is unrelated and must still converge.
        val app = SchemaCatalog.of(VectorIndexSpec("Doc", "embedding", 768)).forDatabase("neo").named("app")
        val other = SchemaCatalog.of(VectorIndexSpec("Doc", "embedding", 1536)).forDatabase("neo").named("other")
        val rag = SchemaCatalog.of(RangeIndexSpec("RagThing", "slug")).forDatabase("neo").named("rag").withVersion("rag-1")

        schemaManager(listOf(app, other, rag)).enforce("rag")

        assertNotNull(manager.indexes.find(RangeIndexSpec("RagThing", "slug")))
        assertEquals("rag-1", markerVersion("rag"))

        // ...but a full enforce still surfaces the conflict.
        assertThrows<DrivineException> { schemaManager(listOf(app, other, rag)).enforce() }
    }

    // ----- Per-owner FAIL_FAST isolation -----

    @Test
    fun `one owner's fail-fast problem does not stop another owner recording its version`() {
        // The app declares a constraint that existing data violates; the library is healthy.
        manager.execute(QuerySpecification.withStatement("CREATE (:AppEntity {code: 'x'}), (:AppEntity {code: 'x'})"))

        val app = SchemaCatalog.of(UniquenessConstraintSpec("AppEntity", "code")).forDatabase("neo").named("app").withVersion("app-1")
        val rag = SchemaCatalog.of(RangeIndexSpec("RagEntity", "slug")).forDatabase("neo").named("rag").withVersion("rag-1")

        // FAIL_FAST: the app owner throws, but the aggregate is raised only after every owner ran.
        assertThrows<DrivineException> { schemaManager(listOf(app, rag)).enforce() }

        // The library still converged and recorded its version despite the app's failure.
        assertNotNull(manager.indexes.find(RangeIndexSpec("RagEntity", "slug")))
        assertEquals("rag-1", markerVersion("rag"))
        // The app's marker was NOT written (its version is held back on failure).
        assertNull(markerVersion("app"))
    }

    // ----- Selective enforce / recreateAll -----

    @Test
    fun `enforce(name) applies only the named owner`() {
        val app = SchemaCatalog.of(RangeIndexSpec("AppThing", "slug")).forDatabase("neo").named("app").withVersion("app-1")
        val rag = SchemaCatalog.of(RangeIndexSpec("RagThing", "slug")).forDatabase("neo").named("rag").withVersion("rag-1")

        schemaManager(listOf(app, rag)).enforce("rag")

        assertNotNull(manager.indexes.find(RangeIndexSpec("RagThing", "slug")))
        assertNull(manager.indexes.find(RangeIndexSpec("AppThing", "slug"))) // app not applied
        assertEquals("rag-1", markerVersion("rag"))
        assertNull(markerVersion("app"))
    }

    @Test
    fun `recreateAll(name) rebuilds only the named owner`() {
        val warn = SchemaPolicy(mode = SchemaMode.WARN)
        val app = SchemaCatalog.of(VectorIndexSpec("AppDoc", "embedding", 768)).forDatabase("neo").named("app")
        val rag = SchemaCatalog.of(VectorIndexSpec("RagDoc", "embedding", 768)).forDatabase("neo").named("rag")
        schemaManager(listOf(app, rag), warn).enforce()

        // recreateAll on 'rag' only — app's index is untouched (and rag's is rebuilt, still present).
        schemaManager(listOf(app, rag), warn).recreateAll("rag")

        assertNotNull(vectorDims("RagDoc", "embedding"))
        assertEquals(768, vectorDims("AppDoc", "embedding"))
    }

    // ----- Helpers to rebuild specs from introspected items for cleanup -----

    private fun specFor(info: SchemaItemInfo): ConstraintSpec =
        UniquenessConstraintSpec(info.label, info.properties, info.name)

    private fun indexSpecFor(info: SchemaItemInfo): IndexSpec = when (info.kind) {
        SchemaItemKind.VECTOR_INDEX -> VectorIndexSpec(info.label, info.properties.first(), info.dimensions ?: 1, name = info.name)
        SchemaItemKind.FULLTEXT_INDEX -> FullTextIndexSpec(info.label, info.properties, info.name)
        else -> RangeIndexSpec(info.label, info.properties, info.name)
    }
}