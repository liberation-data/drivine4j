package org.drivine.schema.ddl

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.SqlStatement
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SchemaManager
import org.drivine.schema.SchemaVersionStore
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.transaction.TransactionContextHolder
import org.drivine.transaction.Transactions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Neo4j schema DDL run as Cypher on each SQL store, through a [PersistenceManager]: applied through the schema
 * machinery, as the schema API applies a spec, under the owner `ddl`. Each test has a graph of its own, and a
 * [PersistenceManagerFactory] made again stands for the next process.
 */
abstract class SchemaDdlContract {

    /** A provider of connections to the graph [graph], which makes its tables. */
    abstract fun provider(graph: String): ConnectionProvider

    /** Makes the tables of the graph [graph], through [pm]. */
    abstract fun ensure(graph: String, pm: PersistenceManager)

    /** Drops the graph [graph]. */
    abstract fun drop(graph: String)

    /**
     * The SQL that takes `aliases` out of the details of every record in the catalog of [graph]: the details as Drivine
     * for TypeScript and drivine-python write them, which know no aliases, where they write a record over.
     */
    abstract fun withoutAliases(graph: String): String

    private lateinit var graph: String
    private lateinit var registry: DatabaseRegistry
    private lateinit var holder: TransactionContextHolder
    private lateinit var factory: PersistenceManagerFactory
    private lateinit var pm: PersistenceManager

    private val schemaLogger = LoggerFactory.getLogger(SchemaManager::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    /** A registry, a factory and managers made anew over the graph: what the next process has. */
    private fun restart() {
        registry = DatabaseRegistry(DataSourceMap()).also { it.register(provider(graph)) }
        holder = TransactionContextHolder(registry)
        factory = PersistenceManagerFactory(registry, holder)
        pm = factory.get(graph, PersistenceManagerType.NON_TRANSACTIONAL)
    }

    @BeforeEach
    fun start() {
        graph = "ddl" + Random.nextInt(0, Int.MAX_VALUE).toString(36)
        restart()
        ensure(graph, pm)
        appender.list.clear()
        appender.start()
        schemaLogger.addAppender(appender)
    }

    @AfterEach
    fun end() {
        schemaLogger.detachAppender(appender)
        drop(graph)
    }

    private fun ddl(statement: String) = pm.execute(QuerySpecification.withStatement(CypherStatement(statement)))

    private fun cypher(statement: String): List<Any?> = pm.query(QuerySpecification.withStatement(CypherStatement(statement)))

    private fun messages(failure: Throwable) = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

    private fun ddlKeys(owner: String = SchemaCommands.OWNER) = SchemaVersionStore(pm, owner).storedRecord()?.keys.orEmpty()

    private fun aliases(spec: SchemaItemSpec) = (pm.indexes.recorded() + pm.constraints.recorded())
        .single { it.info.kind == spec.kind && it.info.label == spec.label && it.info.properties == spec.properties }.aliases

    /** The shape of an APOC export's schema file: fifteen statements, Drivine's own constraint and the import's among them. */
    private val apocSchema = listOf(
        "CREATE FULLTEXT INDEX Chunk_text_fulltext IF NOT EXISTS FOR (n:Chunk) ON EACH [n.`text`];",
        "CREATE RANGE INDEX IF NOT EXISTS FOR (n:ChatSession) ON (n.lastActivityAt);",
        "CREATE RANGE INDEX IF NOT EXISTS FOR (n:ContentElement) ON (n.root_document_id, n.ordinal);",
        "CREATE RANGE INDEX IF NOT EXISTS FOR (n:Message) ON (n.sessionId);",
        "CREATE RANGE INDEX IF NOT EXISTS FOR (n:Document) ON (n.uri);",
        "CREATE CONSTRAINT Attachment_attachmentId_unique IF NOT EXISTS FOR (node:Attachment) REQUIRE (node.attachmentId) IS UNIQUE;",
        "CREATE CONSTRAINT ChatSession_sessionId_unique IF NOT EXISTS FOR (node:ChatSession) REQUIRE (node.sessionId) IS UNIQUE;",
        "CREATE CONSTRAINT Chunk_id_unique IF NOT EXISTS FOR (node:Chunk) REQUIRE (node.id) IS UNIQUE;",
        "CREATE CONSTRAINT ContentElement_id_unique IF NOT EXISTS FOR (node:ContentElement) REQUIRE (node.id) IS UNIQUE;",
        "CREATE CONSTRAINT Document_id_unique IF NOT EXISTS FOR (node:Document) REQUIRE (node.id) IS UNIQUE;",
        "CREATE CONSTRAINT Message_id_unique IF NOT EXISTS FOR (node:Message) REQUIRE (node.id) IS UNIQUE;",
        "CREATE CONSTRAINT User_id_unique IF NOT EXISTS FOR (node:User) REQUIRE (node.id) IS UNIQUE;",
        "CREATE CONSTRAINT _DrivineSchema_scope_unique IF NOT EXISTS FOR (node:_DrivineSchema) REQUIRE (node.scope) IS UNIQUE;",
        "CREATE CONSTRAINT UNIQUE_IMPORT_NAME IF NOT EXISTS FOR (node:`UNIQUE IMPORT LABEL`) REQUIRE (node.`UNIQUE IMPORT ID`) IS UNIQUE;",
        "CALL db.awaitIndexes(300);",
    )

    /** What [apocSchema] makes, as the schema API declares it. */
    private val apocSpecs: List<SchemaItemSpec> = listOf(
        FullTextIndexSpec("Chunk", listOf("text")),
        RangeIndexSpec("ChatSession", "lastActivityAt"),
        RangeIndexSpec("ContentElement", listOf("root_document_id", "ordinal")),
        RangeIndexSpec("Message", "sessionId"),
        RangeIndexSpec("Document", "uri"),
        UniquenessConstraintSpec("Attachment", "attachmentId"),
        UniquenessConstraintSpec("ChatSession", "sessionId"),
        UniquenessConstraintSpec("Chunk", "id"),
        UniquenessConstraintSpec("ContentElement", "id"),
        UniquenessConstraintSpec("Document", "id"),
        UniquenessConstraintSpec("Message", "id"),
        UniquenessConstraintSpec("User", "id"),
        UniquenessConstraintSpec("UNIQUE IMPORT LABEL", "UNIQUE IMPORT ID"),
    )

    @Test
    fun `an APOC export's schema file applies twice, as the schema API's items, and the application's enforce finds no drift and no orphans`() {
        repeat(2) { apocSchema.forEach(::ddl) }

        apocSpecs.forEach { spec ->
            val found = when (spec) {
                is UniquenessConstraintSpec -> pm.constraints.find(spec)
                is RangeIndexSpec -> pm.indexes.find(spec)
                is FullTextIndexSpec -> pm.indexes.find(spec)
                else -> null
            }
            assertEquals(spec.effectiveName, assertNotNull(found, "$spec").name)
        }
        assertEquals(listOf("UNIQUE_IMPORT_NAME"), aliases(UniquenessConstraintSpec("UNIQUE IMPORT LABEL", "UNIQUE IMPORT ID")))
        assertNotNull(pm.constraints.find(UniquenessConstraintSpec(SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY)))
        // Each item once in the ddl owner's marker, as ensured at runtime; Drivine's own constraint in none.
        val record = assertNotNull(SchemaVersionStore(pm, SchemaCommands.OWNER).storedRecord())
        assertEquals(apocSpecs.map { it.inventoryKey }.sorted(), record.keys.sorted())
        assertEquals(record.keys.toSet(), record.runtime)

        // The application declares some of them, as its fragments would.
        val appSpecs = apocSpecs.take(3) + apocSpecs[5] + apocSpecs[7]
        val app = SchemaManager(factory, registry, listOf(SchemaCatalog.of(appSpecs).named("app")))
        repeat(2) { app.enforce() }
        val warnings = appender.list.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { it.formattedMessage }
        assertTrue(warnings.none { "orphan" in it || "drift" in it.lowercase() }, warnings.joinToString("\n"))
        assertTrue(appender.list.any { "Schema for owner 'app'" in it.formattedMessage && "0 created, 5 already matching" in it.formattedMessage }, appender.list.joinToString("\n") { it.formattedMessage })
        assertEquals(appSpecs.map { it.inventoryKey }.sorted(), ddlKeys("app").sorted())
        assertEquals(apocSpecs.map { it.inventoryKey }.sorted(), ddlKeys().sorted(), "the ddl owner's marker is the application's to leave")
    }

    @Test
    fun `an item made by DDL and the same item declared through the API are one item, of one inventory key`() {
        ddl("CREATE INDEX person_name IF NOT EXISTS FOR (p:Person) ON (p.name)")
        SchemaManager(factory, registry, emptyList()).ensure("app", RangeIndexSpec("Person", "name"))

        assertEquals(1, pm.indexes.list().count { it.label == "Person" })
        assertEquals(listOf(RangeIndexSpec("Person", "name").inventoryKey), ddlKeys())
        assertEquals(ddlKeys(), ddlKeys("app"))
        assertEquals(listOf("person_name"), aliases(RangeIndexSpec("Person", "name")))
    }

    @Test
    fun `DROP by an alias finds the item after a restart, and the store no longer refuses a duplicate`() {
        val spec = UniquenessConstraintSpec("UNIQUE IMPORT LABEL", "UNIQUE IMPORT ID")
        ddl("CREATE CONSTRAINT UNIQUE_IMPORT_NAME IF NOT EXISTS FOR (node:`UNIQUE IMPORT LABEL`) REQUIRE (node.`UNIQUE IMPORT ID`) IS UNIQUE;")
        cypher("CREATE (:`UNIQUE IMPORT LABEL` {`UNIQUE IMPORT ID`: 1})")
        assertFails { cypher("CREATE (:`UNIQUE IMPORT LABEL` {`UNIQUE IMPORT ID`: 1})") }

        restart()
        ddl("DROP CONSTRAINT UNIQUE_IMPORT_NAME;")

        assertNull(pm.constraints.find(spec))
        cypher("CREATE (:`UNIQUE IMPORT LABEL` {`UNIQUE IMPORT ID`: 1})")
        assertEquals(listOf(2L), cypher("MATCH (n:`UNIQUE IMPORT LABEL`) RETURN count(n)"))
        assertEquals(emptyList(), ddlKeys())
        assertTrue("no constraint named 'UNIQUE_IMPORT_NAME'" in messages(assertFails { ddl("DROP CONSTRAINT UNIQUE_IMPORT_NAME") }))
        ddl("DROP CONSTRAINT UNIQUE_IMPORT_NAME IF EXISTS")
    }

    @Test
    fun `IF NOT EXISTS over the item adds only its alias, without it fails, a name another item goes by is refused, and DROP finds it by either`() {
        val spec = RangeIndexSpec("Person", "name")
        ddl("CREATE INDEX by_name FOR (p:Person) ON (p.name)")
        ddl("CREATE INDEX by_name IF NOT EXISTS FOR (p:Person) ON (p.name)")
        assertTrue("An equivalent index already exists" in messages(assertFails { ddl("CREATE INDEX by_name FOR (p:Person) ON (p.name)") }))
        assertFails { ddl("CREATE INDEX FOR (p:Person) ON (p.name)") }
        ddl("CREATE INDEX person_by_name IF NOT EXISTS FOR (p:Person) ON (p.name)")
        assertEquals(listOf("by_name", "person_by_name"), aliases(spec))

        assertTrue("already a schema item named 'by_name'" in messages(assertFails { ddl("CREATE INDEX by_name IF NOT EXISTS FOR (p:Pet) ON (p.name)") }))
        assertTrue("already a schema item named 'Person_name_range'" in messages(assertFails { ddl("CREATE CONSTRAINT Person_name_range IF NOT EXISTS FOR (p:Pet) REQUIRE p.name IS UNIQUE") }))
        assertNull(pm.indexes.find(RangeIndexSpec("Pet", "name")))

        ddl("CREATE CONSTRAINT pet_id FOR (p:Pet) REQUIRE p.id IS UNIQUE")
        assertTrue("DROP INDEX does not drop it" in messages(assertFails { ddl("DROP INDEX pet_id") }))
        ddl("DROP CONSTRAINT pet_id")
        assertNull(pm.constraints.find(UniquenessConstraintSpec("Pet", "id")))

        ddl("DROP INDEX person_by_name")
        assertNull(pm.indexes.find(spec))
        assertFails { ddl("DROP INDEX by_name") }
        ddl("DROP INDEX by_name IF EXISTS")
        assertEquals(emptyList(), ddlKeys())
    }

    @Test
    fun `an item recreated, or recorded again, keeps its aliases`() {
        ddl("CREATE INDEX by_name FOR (p:Person) ON (p.name)")
        ddl("CREATE CONSTRAINT person_email FOR (p:Person) REQUIRE p.email IS UNIQUE")
        pm.indexes.recreate(RangeIndexSpec("Person", "name"))
        pm.constraints.recreate(UniquenessConstraintSpec("Person", "email"))
        assertEquals(listOf("by_name"), aliases(RangeIndexSpec("Person", "name")))
        assertEquals(listOf("person_email"), aliases(UniquenessConstraintSpec("Person", "email")))

        // On Oracle a uniqueness constraint over the one property records the range index again, as served by it.
        pm.constraints.ensure(UniquenessConstraintSpec("Person", "name"))
        assertEquals(listOf("by_name"), aliases(RangeIndexSpec("Person", "name")))
        SchemaManager(factory, registry, listOf(SchemaCatalog.of(RangeIndexSpec("Person", "name")).named("app"))).recreateAll()
        assertEquals(listOf("by_name"), aliases(RangeIndexSpec("Person", "name")))
    }

    @Test
    fun `DROP of an item the application declares forgets it in the ddl owner alone, leaves it in place, and the application's enforce finds it as it was`() {
        val index = RangeIndexSpec("Person", "name")
        val constraint = UniquenessConstraintSpec("Person", "email")
        val declared = listOf(index, constraint)
        SchemaManager(factory, registry, listOf(SchemaCatalog.of(declared).named("app"))).enforce()
        restart()
        ddl("CREATE INDEX by_name IF NOT EXISTS FOR (p:Person) ON (p.name)")
        ddl("CREATE CONSTRAINT person_email IF NOT EXISTS FOR (p:Person) REQUIRE p.email IS UNIQUE")
        assertEquals(declared.map { it.inventoryKey }.sorted(), ddlKeys().sorted())

        ddl("DROP INDEX by_name")
        ddl("DROP CONSTRAINT person_email")

        assertNotNull(pm.indexes.find(index))
        assertNotNull(pm.constraints.find(constraint))
        assertEquals(emptyList(), ddlKeys())
        assertEquals(emptyList(), aliases(index))
        assertEquals(emptyList(), aliases(constraint))
        assertEquals(2, appender.list.count { it.level == Level.WARN && "left in place, as 'app' declare(s) it" in it.formattedMessage }, appender.list.joinToString("\n") { it.formattedMessage })
        assertTrue("no index named 'by_name'" in messages(assertFails { ddl("DROP INDEX by_name") }))

        appender.list.clear()
        restart()
        SchemaManager(factory, registry, listOf(SchemaCatalog.of(declared).named("app"))).enforce()
        val warnings = appender.list.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { it.formattedMessage }
        assertTrue(warnings.isEmpty(), warnings.joinToString("\n"))
        assertTrue(appender.list.any { "Schema for owner 'app'" in it.formattedMessage && "0 created, 2 already matching" in it.formattedMessage }, appender.list.joinToString("\n") { it.formattedMessage })
        assertEquals(declared.map { it.inventoryKey }.sorted(), ddlKeys("app").sorted())
    }

    @Test
    fun `DROP of an item the application declares over its properties in another order leaves it in place, and forgets it in the ddl owner`() {
        val declared = RangeIndexSpec("Person", listOf("last", "first"))
        SchemaManager(factory, registry, listOf(SchemaCatalog.of(declared).named("app"))).enforce()
        restart()

        // By an alias: the item found is the application's, of the other order.
        ddl("CREATE INDEX by_names IF NOT EXISTS FOR (p:Person) ON (p.first, p.last)")
        assertEquals(listOf(RangeIndexSpec("Person", listOf("first", "last")).inventoryKey), ddlKeys())
        ddl("DROP INDEX by_names")
        assertNotNull(pm.indexes.find(declared))
        assertEquals(emptyList(), ddlKeys())

        // By the name the schema API gives the DDL's own spec.
        ddl("CREATE INDEX IF NOT EXISTS FOR (p:Person) ON (p.first, p.last)")
        ddl("DROP INDEX Person_first_last_range")
        assertNotNull(pm.indexes.find(declared))
        assertEquals(emptyList(), ddlKeys())
        assertEquals(listOf(declared.inventoryKey), ddlKeys("app"))
    }

    @Test
    fun `a name the schema API gives the DDL's spec, over an item of its properties in another order, is kept as an alias, so a DROP by it after a restart finds the item`() {
        val declared = RangeIndexSpec("Person", listOf("last", "first"))
        SchemaManager(factory, registry, emptyList()).ensure("app", declared)
        restart()

        ddl("CREATE INDEX IF NOT EXISTS FOR (p:Person) ON (p.first, p.last)")
        ddl("CREATE INDEX Person_first_last_range IF NOT EXISTS FOR (p:Person) ON (p.first, p.last)")
        assertEquals(listOf("Person_first_last_range"), aliases(declared))
        assertTrue("already a schema item named 'Person_first_last_range'" in messages(assertFails { ddl("CREATE INDEX Person_first_last_range IF NOT EXISTS FOR (p:Pet) ON (p.name)") }))

        restart()
        ddl("DROP INDEX Person_first_last_range")
        assertNotNull(pm.indexes.find(declared))
        assertEquals(emptyList(), aliases(declared))
        assertEquals(emptyList(), ddlKeys())
        assertEquals(listOf(declared.inventoryKey), ddlKeys("app"))
    }

    @Test
    fun `a record whose details a library without aliases wrote over reads as it did but for its aliases, and the application's enforce finds no drift`() {
        val specs = listOf(
            RangeIndexSpec("Person", "name"),
            // On Oracle the range index over the one property is then recorded as served by the constraint's index.
            UniquenessConstraintSpec("Person", "name"),
            UniquenessConstraintSpec("Person", "email"),
            FullTextIndexSpec("Chunk", listOf("text"), analyzer = "english"),
            VectorIndexSpec("Chunk", "embedding", 3, SimilarityFunction.EUCLIDEAN),
        )
        ddl("CREATE INDEX by_name FOR (p:Person) ON (p.name)")
        ddl("CREATE CONSTRAINT person_name FOR (p:Person) REQUIRE p.name IS UNIQUE")
        ddl("CREATE CONSTRAINT person_email FOR (p:Person) REQUIRE p.email IS UNIQUE")
        ddl("CREATE FULLTEXT INDEX chunk_text FOR (n:Chunk) ON EACH [n.text] OPTIONS {indexConfig: {`fulltext.analyzer`: 'english'}}")
        ddl("CREATE VECTOR INDEX chunk_embedding FOR (n:Chunk) ON n.embedding OPTIONS {indexConfig: {`vector.dimensions`: 3, `vector.similarity_function`: 'euclidean'}}")
        fun listed() = (pm.indexes.list() + pm.constraints.list()).map { it.toString() }.sorted()
        val before = listed()
        specs.forEach { assertEquals(1, aliases(it).size, "$it") }

        pm.execute(QuerySpecification.withStatement(SqlStatement(withoutAliases(graph))))
        restart()

        assertEquals(before, listed())
        specs.forEach { assertEquals(emptyList(), aliases(it), "$it") }
        SchemaManager(factory, registry, listOf(SchemaCatalog.of(specs).named("app"))).enforce()
        val warnings = appender.list.filter { it.level.isGreaterOrEqual(Level.WARN) }.map { it.formattedMessage }
        assertTrue(warnings.isEmpty(), warnings.joinToString("\n"))
        assertTrue(appender.list.any { "Schema for owner 'app'" in it.formattedMessage && "0 created, 5 already matching" in it.formattedMessage }, appender.list.joinToString("\n") { it.formattedMessage })
        // Only the aliases are lost: a DROP by one no longer finds the item.
        assertTrue("no index named 'by_name'" in messages(assertFails { ddl("DROP INDEX by_name") }))
    }

    @Test
    fun `IF NOT EXISTS over an item of another shape leaves it, without it fails, a TEXT index is refused, and Drivine's own constraint is not dropped`() {
        ddl("CREATE VECTOR INDEX FOR (c:Chunk) ON c.embedding OPTIONS {indexConfig: {`vector.dimensions`: 3}}")
        ddl("CREATE VECTOR INDEX wider IF NOT EXISTS FOR (c:Chunk) ON c.embedding OPTIONS {indexConfig: {`vector.dimensions`: 4}}")
        assertEquals(3, assertNotNull(pm.indexes.find(VectorIndexSpec("Chunk", "embedding", 3))).dimensions)
        assertEquals(emptyList(), aliases(VectorIndexSpec("Chunk", "embedding", 3)))
        assertTrue("of another shape" in messages(assertFails { ddl("CREATE VECTOR INDEX FOR (c:Chunk) ON c.embedding OPTIONS {indexConfig: {`vector.dimensions`: 4}}") }))

        assertTrue("TEXT" in messages(assertFails { ddl("CREATE TEXT INDEX FOR (p:Person) ON (p.name)") }))

        ddl("CREATE CONSTRAINT IF NOT EXISTS FOR (m:_DrivineSchema) REQUIRE m.scope IS UNIQUE")
        val own = UniquenessConstraintSpec(SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY)
        assertTrue("which Drivine keeps" in messages(assertFails { ddl("DROP CONSTRAINT ${own.effectiveName}") }))
        assertNotNull(pm.constraints.find(own))
        // The vector index alone is the ddl owner's: neither the TEXT index refused nor Drivine's own constraint is recorded.
        assertEquals(listOf(VectorIndexSpec("Chunk", "embedding", 3).inventoryKey), ddlKeys())
    }

    @Test
    fun `inside an open transaction a schema command is refused, a refusal is the translator's, and db awaitIndexes does nothing`() {
        val transactions = Transactions(holder)
        val tpm = factory.get(graph, PersistenceManagerType.TRANSACTIONAL)
        val delegating = factory.get(graph)
        fun statement(text: String) = QuerySpecification.withStatement(CypherStatement(text))

        val refused = assertFails { transactions.transaction(rollback = true) { tpm.execute(statement("CREATE INDEX FOR (p:Person) ON (p.name)")) } }
        assertTrue("not run inside a transaction" in messages(refused), messages(refused))
        assertFails { transactions.transaction(rollback = true) { delegating.execute(statement("DROP INDEX x IF EXISTS")) } }
        val point = assertFails { transactions.transaction(rollback = true) { tpm.execute(statement("CREATE POINT INDEX FOR (p:Place) ON (p.at)")) } }
        assertTrue("A POINT index is Neo4j's" in messages(point), messages(point))
        assertEquals(emptyList(), transactions.transaction(rollback = true) { tpm.query(statement("CALL db.awaitIndexes(300)")) })
        assertNull(pm.indexes.find(RangeIndexSpec("Person", "name")))

        // Outside a transaction the delegating manager runs it.
        delegating.execute(statement("CREATE INDEX FOR (p:Person) ON (p.name)"))
        assertNotNull(pm.indexes.find(RangeIndexSpec("Person", "name")))
        // A batch of one runs it, and of more is refused.
        pm.executeBatch(listOf(statement("DROP INDEX Person_name_range")))
        assertNull(pm.indexes.find(RangeIndexSpec("Person", "name")))
        assertFails { pm.executeBatch(listOf(statement("CREATE INDEX FOR (p:Person) ON (p.name)"), statement("MATCH (n) RETURN n"))) }
    }

    @Test
    fun `DDL through the manager of the default database and through the manager of its own name is one database's, so a DROP through one is seen by the other`() {
        val byDefault = factory.get("default", PersistenceManagerType.NON_TRANSACTIONAL)
        fun on(manager: PersistenceManager, text: String) = manager.execute(QuerySpecification.withStatement(CypherStatement(text)))

        on(byDefault, "CREATE INDEX FOR (p:Person) ON (p.name)")
        ddl("DROP INDEX Person_name_range")
        assertNull(pm.indexes.find(RangeIndexSpec("Person", "name")))

        val again = assertFails { on(byDefault, "DROP INDEX Person_name_range") }
        assertTrue("There is no index named 'Person_name_range'" in messages(again), messages(again))
        assertEquals(emptyList(), ddlKeys())
    }

    @Test
    fun `remove drops what no one else declares, leaves what a startup catalog declares, and forgets both in the owner's marker`() {
        val shared = RangeIndexSpec("Shared", "p")
        val own = RangeIndexSpec("Own", "p")
        val manager = SchemaManager(factory, registry, listOf(SchemaCatalog.of(shared).named("app")))
        manager.enforce()
        manager.ensure("lib", shared, own)
        assertEquals(listOf(own.inventoryKey, shared.inventoryKey).sorted(), ddlKeys("lib").sorted())

        manager.remove("lib", shared, own)

        assertNotNull(pm.indexes.find(shared))
        assertNull(pm.indexes.find(own))
        assertEquals(emptyList(), ddlKeys("lib"))
        assertTrue(appender.list.any { "left in place, as 'app' declare(s) it" in it.formattedMessage })
        // The item removed is no longer the owner's at runtime, so an enforce does not make it again.
        manager.enforce()
        assertNull(pm.indexes.find(own))
        assertEquals(listOf(shared.inventoryKey), ddlKeys("app"))
    }
}

@PostgresTests
class SchemaDdlPostgresTest : SchemaDdlContract() {
    override fun provider(graph: String): ConnectionProvider =
        JdbcConnectionProvider(graph, DatabaseType.POSTGRES, PostgresTestDatabase.provider.nativeDriver, SubtypeRegistry(), graphName = graph)

    override fun ensure(graph: String, pm: PersistenceManager) = PostgresGraph(graph).ensure(pm)

    override fun drop(graph: String) = PostgresTestDatabase.drop(graph)

    override fun withoutAliases(graph: String) = "UPDATE ${PostgresGraph(graph).catalog} SET details = details - 'aliases'"
}

@OracleTests
class SchemaDdlOracleTest : SchemaDdlContract() {
    override fun provider(graph: String): ConnectionProvider =
        JdbcConnectionProvider(graph, DatabaseType.ORACLE, OracleTestDatabase.provider.nativeDriver, SubtypeRegistry(), graphName = graph)

    override fun ensure(graph: String, pm: PersistenceManager) = OracleGraph(graph).ensure(pm)

    override fun drop(graph: String) = OracleTestDatabase.drop(
        "PROPERTY GRAPH $graph", "TABLE ${OracleGraph(graph).texts("Chunk", listOf("text"))}", "TABLE ${OracleGraph(graph).vectors("Chunk", "embedding")}",
        "TABLE ${graph}_edge", "TABLE ${graph}_node", "TABLE ${graph}_schema",
        "FUNCTION ${graph}_nulls_equal", "FUNCTION ${graph}_era", "FUNCTION ${graph}_era_date",
    )

    override fun withoutAliases(graph: String) = "UPDATE ${OracleGraph(graph).catalog} SET details = JSON_TRANSFORM(details, REMOVE '$.aliases')"
}

class SchemaDdlSqliteTest : SchemaDdlContract() {
    override fun provider(graph: String): ConnectionProvider =
        JdbcConnectionProvider(graph, DatabaseType.SQLITE, SqliteTestDatabase.provider.nativeDriver, SubtypeRegistry(), graphName = graph)

    override fun ensure(graph: String, pm: PersistenceManager) = SqliteGraph(graph).ensure(pm)

    override fun drop(graph: String) = SqliteTestDatabase.drop(graph)

    override fun withoutAliases(graph: String) = "UPDATE ${SqliteGraph(graph).catalog} SET details = json_remove(details, '$.aliases')"
}
