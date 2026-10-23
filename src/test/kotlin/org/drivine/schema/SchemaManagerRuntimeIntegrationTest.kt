package org.drivine.schema

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import org.drivine.test.SharedNeo4j
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Schema known only at runtime, ensured under an owner with [SchemaManager.ensure], against a real
 * Neo4j instance. A second [SchemaManager] built over the same database stands for the next process.
 *
 * Every test is self-contained: `@BeforeEach` wipes all data, markers, indexes and constraints.
 */
class SchemaManagerRuntimeIntegrationTest {

    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

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
                password = SharedNeo4j.PASSWORD,
                databaseName = "neo4j",
                cypherDialect = CypherDialect.NEO4J_5,
            )
            registry = DatabaseRegistry(DataSourceMap(mapOf("neo" to props)))
            factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))
            manager = factory.get("neo", PersistenceManagerType.NON_TRANSACTIONAL)
        }
    }

    private val logger = LoggerFactory.getLogger(SchemaManager::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun clean() {
        manager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
        manager.constraints.list().forEach { manager.constraints.drop(UniquenessConstraintSpec(it.label, it.properties, it.name)) }
        manager.indexes.list().forEach { manager.indexes.drop(indexSpecFor(it)) }
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun stopListening() {
        logger.detachAppender(appender)
    }

    private fun schemaManager(catalogs: List<SchemaCatalog>, policy: SchemaPolicy = SchemaPolicy()) =
        SchemaManager(factory, registry, catalogs, policy)

    private fun record(owner: String) = SchemaVersionStore(manager, owner).storedRecord()

    private fun vectorDims(label: String, property: String): Int? =
        manager.indexes.find(VectorIndexSpec(label, property, 1))?.dimensions

    private fun orphanWarnings() = appender.list.filter { it.level == Level.WARN && "orphans" in it.formattedMessage }

    private fun summaries() = appender.list.map { it.formattedMessage }.filter { it.startsWith("Schema for owner") }

    private val warn = SchemaPolicy(mode = SchemaMode.WARN)

    private val diceAtStartup = SchemaCatalog.of(RangeIndexSpec("Proposition", "contextId")).forDatabase("neo").named("dice")
    private fun propositionVector(dimensions: Int) = VectorIndexSpec("Proposition", "embedding", dimensions)

    @Test
    fun `an item ensured at runtime joins the owner's inventory, and a later enforce keeps it`() {
        val schema = schemaManager(listOf(diceAtStartup.withVersion("d-1")))
        schema.enforce()

        schema.ensure("dice", propositionVector(768))

        assertEquals(768, vectorDims("Proposition", "embedding"))
        val vector = propositionVector(768).inventoryKey
        val startup = RangeIndexSpec("Proposition", "contextId").inventoryKey
        assertEquals(listOf(startup, vector), record("dice")!!.keys)
        assertEquals(setOf(vector), record("dice")!!.runtime)
        assertEquals("d-1", SchemaVersionStore(manager, "dice").storedVersion(), "ensure records the version it finds")

        schema.enforce("dice")
        schema.enforce()

        assertEquals(listOf(startup, vector), record("dice")!!.keys)
        assertEquals(768, vectorDims("Proposition", "embedding"))
        assertTrue(orphanWarnings().isEmpty(), "no orphans: ${orphanWarnings().map { it.formattedMessage }}")
        assertContains(summaries().last(), "0 created, 2 already matching", message = "enforce applies the runtime item with the rest")
    }

    @Test
    fun `ensure applies only the items given, and says what it did`() {
        val schema = schemaManager(listOf(diceAtStartup))
        schema.enforce()
        manager.indexes.drop(RangeIndexSpec("Proposition", "contextId"))

        schema.ensure("dice", propositionVector(768))

        assertNull(manager.indexes.find(RangeIndexSpec("Proposition", "contextId")), "the owner's other items are left as they are")
        assertTrue(appender.list.any { it.formattedMessage == "Ensuring schema for owner 'dice' on 'neo': 1 runtime items, 2 declared in all" })
        assertEquals("Schema for owner 'dice' on 'neo': 1 created, 0 already matching, 0 recreated, 0 needing attention", summaries().last())
    }

    @Test
    fun `a manager built later keeps what an earlier one ensured at runtime, without calling it an orphan`() {
        schemaManager(listOf(diceAtStartup)).apply { enforce(); ensure("dice", propositionVector(768)) }

        // The next process: same catalogs, and the runtime item not ensured yet.
        schemaManager(listOf(diceAtStartup)).enforce()

        assertTrue(orphanWarnings().isEmpty(), "no orphans: ${orphanWarnings().map { it.formattedMessage }}")
        val vector = propositionVector(768).inventoryKey
        assertTrue(vector in record("dice")!!.keys)
        assertEquals(setOf(vector), record("dice")!!.runtime)
        assertEquals(768, vectorDims("Proposition", "embedding"))
    }

    @Test
    fun `an owner ensured at runtime alone is left as it was by an enforce that does not know it`() {
        schemaManager(emptyList()).ensure("tenants", RangeIndexSpec("TenantThing", "slug"))

        schemaManager(listOf(diceAtStartup)).enforce()

        assertEquals(setOf(RangeIndexSpec("TenantThing", "slug").inventoryKey), record("tenants")!!.runtime)
        assertNotNull(manager.indexes.find(RangeIndexSpec("TenantThing", "slug")))
    }

    @Test
    fun `a version change marks a runtime item it has no spec for, and the next ensure recreates it`() {
        schemaManager(listOf(diceAtStartup.withVersion("model-a")), warn).apply { enforce(); ensure("dice", propositionVector(768)) }

        // The next process changes the owner's version; the vector's spec arrives later, at a new width.
        val next = schemaManager(listOf(diceAtStartup.withVersion("model-b")), warn)
        next.enforce()
        val vector = propositionVector(768).inventoryKey
        assertEquals(setOf(vector), record("dice")!!.stale)
        assertEquals(768, vectorDims("Proposition", "embedding"), "not touched before its spec is known")

        next.ensure("dice", propositionVector(1536))

        assertEquals(1536, vectorDims("Proposition", "embedding"), "recreated, not left as drift")
        assertEquals(emptySet(), record("dice")!!.stale)
        assertEquals("Schema for owner 'dice' on 'neo': 0 created, 0 already matching, 1 recreated, 0 needing attention", summaries().last())
    }

    @Test
    fun `without a version change a runtime item of another shape is drift, handled per the policy`() {
        schemaManager(listOf(diceAtStartup), warn).apply { enforce(); ensure("dice", propositionVector(768)) }

        // WARN: reported and left.
        schemaManager(listOf(diceAtStartup), warn).ensure("dice", propositionVector(1536))
        assertEquals(768, vectorDims("Proposition", "embedding"))
        assertTrue(appender.list.any { it.level == Level.WARN && "DRIFT" in it.formattedMessage })

        // FAIL_FAST: thrown.
        val e = assertThrows<DrivineException> { schemaManager(listOf(diceAtStartup)).ensure("dice", propositionVector(1536)) }
        assertContains(e.message!!, "DRIFT")

        // recreateOnDrift: recreated.
        schemaManager(listOf(diceAtStartup), SchemaPolicy(recreateOnDrift = true)).ensure("dice", propositionVector(1536))
        assertEquals(1536, vectorDims("Proposition", "embedding"))
    }

    @Test
    fun `ensuring an item again with the same identity replaces it in the owner's items`() {
        val schema = schemaManager(listOf(diceAtStartup), SchemaPolicy(recreateOnDrift = true))
        schema.ensure("dice", propositionVector(768))
        schema.ensure("dice", propositionVector(1536))

        // Were both kept, the owner's merge would refuse them as a conflict.
        schema.enforce()

        assertEquals(1536, vectorDims("Proposition", "embedding"))
    }

    @Test
    fun `recreateAll rebuilds the runtime items too`() {
        val schema = schemaManager(listOf(diceAtStartup))
        schema.enforce()
        schema.ensure("dice", propositionVector(768))

        schema.recreateAll("dice")

        assertEquals("Schema for owner 'dice' on 'neo': 0 created, 0 already matching, 2 recreated, 0 needing attention", summaries().last())
    }

    @Test
    fun `a runtime item that conflicts with another owner's fails naming both, and nothing is kept`() {
        val app = SchemaCatalog.of(VectorIndexSpec("Doc", "embedding", 768)).forDatabase("neo").named("app")
        val schema = schemaManager(listOf(app))
        schema.enforce()

        val e = assertThrows<DrivineException> { schema.ensure("rag", VectorIndexSpec("Doc", "embedding", 1536)) }
        assertContains(e.message!!, "'app'")
        assertContains(e.message!!, "'rag'")

        schema.enforce() // would fail, were the conflicting item kept
        assertEquals(768, vectorDims("Doc", "embedding"))
        assertNull(record("rag"))
    }

    @Test
    fun `a runtime item that conflicts with the owner's own declaration fails, and nothing is kept`() {
        val dice = SchemaCatalog.of(propositionVector(768)).forDatabase("neo").named("dice")
        val schema = schemaManager(listOf(dice))
        schema.enforce()

        val e = assertThrows<DrivineException> { schema.ensure("dice", propositionVector(1536)) }
        assertContains(e.message!!, "Conflicting schema declarations")

        schema.enforce()
        assertEquals(768, vectorDims("Proposition", "embedding"))
    }

    @Test
    fun `a runtime item identical to another owner's is co-owned, and the two dedupe`() {
        val app = SchemaCatalog.of(RangeIndexSpec("Shared", "slug")).forDatabase("neo").named("app")
        val schema = schemaManager(listOf(app))
        schema.enforce()

        schema.ensure("rag", RangeIndexSpec("Shared", "slug"))

        assertEquals("Schema for owner 'rag' on 'neo': 0 created, 1 already matching, 0 recreated, 0 needing attention", summaries().last())
        assertEquals(listOf(RangeIndexSpec("Shared", "slug").inventoryKey), record("rag")!!.keys)
    }

    @Test
    fun `a runtime item renamed is an orphan of its owner under the old name`() {
        schemaManager(listOf(diceAtStartup), warn).ensure("dice", RangeIndexSpec("Tag", "name", name = "tag_one"))

        schemaManager(listOf(diceAtStartup), warn).ensure("dice", RangeIndexSpec("Tag", "name", name = "tag_two"))

        val orphan = orphanWarnings().single().formattedMessage
        assertContains(orphan, "Owner 'dice' on 'neo' previously applied 1 item(s)")
        assertContains(orphan, "tag_one")
    }

    @Test
    fun `a violation found by ensure fails under FAIL_FAST, and the marker is not written`() {
        manager.execute(QuerySpecification.withStatement("CREATE (:Member {email: 'x'}), (:Member {email: 'x'})"))

        val e = assertThrows<DrivineException> { schemaManager(emptyList()).ensure("dice", UniquenessConstraintSpec("Member", "email")) }

        assertContains(e.message!!, "VIOLATION")
        assertNull(record("dice"))
    }

    @Test
    fun `a runtime catalog may name its database, which must be registered, and may not carry a version`() {
        val schema = schemaManager(emptyList())
        schema.ensure(SchemaCatalog.of(RangeIndexSpec("Named", "slug")).named("dice").forDatabase("neo"))
        assertNotNull(manager.indexes.find(RangeIndexSpec("Named", "slug")))

        assertThrows<DrivineException> { schema.ensure(SchemaCatalog.of(RangeIndexSpec("Lost", "slug")).named("dice").forDatabase("nowhere")) }
        val versioned = assertThrows<DrivineException> { schema.ensure(SchemaCatalog.of(RangeIndexSpec("Lost", "slug")).named("dice").withVersion("v1")) }
        assertContains(versioned.message!!, "cannot carry a version")

        schema.enforce() // neither refused catalog was kept
        assertNull(manager.indexes.find(RangeIndexSpec("Lost", "slug")))
    }

    private fun indexSpecFor(info: SchemaItemInfo): IndexSpec = when (info.kind) {
        SchemaItemKind.VECTOR_INDEX -> VectorIndexSpec(info.label, info.properties.first(), info.dimensions ?: 1, name = info.name)
        SchemaItemKind.FULLTEXT_INDEX -> FullTextIndexSpec(info.label, info.properties, info.name)
        else -> RangeIndexSpec(info.label, info.properties, info.name)
    }
}
