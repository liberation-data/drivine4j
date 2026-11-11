package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteSchemaGrammar
import org.drivine.connection.DatabaseType
import org.drivine.cypher.store.marker.MarkerStatements
import org.drivine.connection.ReusedIdsException
import org.drivine.cypher.store.plan.DeclaredCatalog
import org.drivine.cypher.store.plan.Operation
import org.drivine.cypher.store.plan.PlanRequest
import org.drivine.query.QueryLanguage
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.drivine.cypher.store.plan.ItemCall
import kotlin.test.assertTrue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFails
import kotlin.test.assertSame

/**
 * What [SchemaPlanDriver] does that `plans.yaml` doesn't pin: the language of a grammar's own statements, where the
 * grammar is one of the application's or a test's ([GrammarStoreSchema]); the grammar told of DDL that failed; and an
 * error that refuses the graph, which no round answers. No database is spoken to.
 */
class SchemaPlanDriverTest {

    @Test
    fun `a grammar's own SQL for the marker's scopes is sent as SQL, though the store isn't one the module knows`() {
        val sqlite = SqliteSchemaGrammar(SqliteGraph("g"))
        val wrapped = object : SchemaGrammar by sqlite {}
        val store = RecordingStore(DatabaseType.SQLITE, ddl = wrapped, graphName = "g")
        val scopes = assertNotNullScopes(wrapped)

        store.indexes.driver.run(PlanRequest("recording", Operation.Remove("lib", listOf(RangeIndexSpec("Removed", "k")))), store)

        val sent = store.queried.single { it.statement == scopes }
        assertEquals(QueryLanguage.SQL, sent.language, "the grammar's scopes are its store's SQL, not Cypher to compile")
    }

    @Test
    fun `the marker's scopes in Cypher are sent as Cypher where the grammar gives no SQL for them`() {
        val store = RecordingStore(DatabaseType.NEO4J, ddl = object : SchemaGrammar by Neo4jSchemaGrammar() {})
        store.indexes.driver.run(PlanRequest("recording", Operation.Remove("lib", listOf(RangeIndexSpec("Removed", "k")))), store)
        assertEquals(QueryLanguage.CYPHER, store.queried.single { it.statement == MarkerStatements.SCOPES_CYPHER }.language)
    }

    @Test
    fun `DDL the store refuses still tells the grammar the schema changed, and the store's error is thrown as it was`() {
        var changes = 0
        val grammar = object : SchemaGrammar by Neo4jSchemaGrammar() {
            override fun changed() {
                changes++
            }
        }
        val refusal = RuntimeException("The store refused the index")
        val store = RecordingStore(DatabaseType.NEO4J, ddl = grammar) { ran -> if (ran.statement.startsWith("CREATE")) refusal else emptyList<Any?>() }

        val thrown = assertFails { store.indexes.ensure(RangeIndexSpec("Refused", "k")) }

        assertSame(refusal, thrown)
        assertEquals(1, store.schema.count { it.startsWith("CREATE") })
        assertEquals(1, changes, "told once, after the DDL that failed")
    }

    @Test
    fun `a grammar of another kind tells a violation by its own rule, from the error the store threw`() {
        // An error no store's rule takes for a violation: only the grammar's own, given the error itself, does.
        val refusal = RuntimeException("the application's store refused the key")
        val grammar = object : SchemaGrammar by Neo4jSchemaGrammar() {
            override fun isConstraintViolation(e: Throwable) = e === refusal
        }
        val repeated = mapOf("properties" to mapOf("k" to "dup"), "count" to 2L)
        val store = RecordingStore(DatabaseType.NEO4J, ddl = grammar) { ran ->
            when {
                ran.statement.startsWith("CREATE CONSTRAINT") -> refusal
                "_count > 1" in ran.statement -> listOf(repeated)
                else -> emptyList<Any?>()
            }
        }
        val spec = UniquenessConstraintSpec("Violated", "k")

        assertEquals(EnsureResult.Violation(spec, listOf(repeated)), store.constraints.ensure(spec))
    }

    @Test
    fun `a grammar of another kind tells an index in the way by its own rule, from the error the store threw`() {
        // An error no store's rule takes for an index in the way: only the grammar's own, given the error itself, does.
        val refusal = RuntimeException("the application's store has an index over the key")
        val grammar = object : SchemaGrammar by Neo4jSchemaGrammar() {
            override fun isBlockedByIndex(e: Throwable) = e === refusal
        }
        val index = mapOf("name" to "blocking_k", "type" to "RANGE", "entityType" to "NODE", "labelsOrTypes" to listOf("Blocked"), "properties" to listOf("k"))
        val store = RecordingStore(DatabaseType.NEO4J, ddl = grammar) { ran ->
            when {
                ran.statement.startsWith("CREATE CONSTRAINT") -> refusal
                ran.statement.startsWith("SHOW INDEXES") -> listOf(index)
                else -> emptyList<Any?>()
            }
        }
        val spec = UniquenessConstraintSpec("Blocked", "k")

        assertEquals(
            EnsureResult.BlockedByIndex(spec, SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Blocked", listOf("k"), "blocking_k")),
            store.constraints.ensure(spec),
        )
    }

    @Test
    fun `a graph refused for every owner is thrown out of the round as it is, and nothing more is run`() {
        val refused = ReusedIdsException("g", null)
        val store = RecordingStore(DatabaseType.SQLITE, ddl = SqliteSchemaGrammar(SqliteGraph("g")), graphName = "g") { refused }
        val request = PlanRequest(
            "recording", Operation.Enforce(),
            catalogs = listOf(DeclaredCatalog(null, items = listOf(RangeIndexSpec("A", "k"))), DeclaredCatalog("lib", items = listOf(RangeIndexSpec("B", "k")))),
        )

        val thrown = assertFails { store.indexes.driver.run(request, store) }

        assertSame(refused, thrown)
        assertEquals(1, store.schema.size + store.queried.size + store.executed.size, "the first statement, and nothing after it")
    }

    @Test
    fun `a store with no schema management refuses each call with its grammar's error, and nothing is sent`() {
        val store = RecordingStore(DatabaseType.NEO4J, ddl = UnsupportedSchemaGrammar("Neptune"))
        val index = RangeIndexSpec("Unsupported", "k")
        val constraint = UniquenessConstraintSpec("Unsupported", "k")
        val calls = listOf<() -> Any?>(
            { store.indexes.ensure(index) }, { store.indexes.recreate(index) }, { store.indexes.drop(index) },
            { store.constraints.ensure(constraint) }, { store.constraints.recreate(constraint) }, { store.constraints.drop(constraint) },
        )
        for (call in calls) {
            val thrown = assertFailsWith<DrivineException> { call() }
            assertContains(thrown.message!!, "is not supported for Neptune")
        }
        assertEquals(emptyList(), store.schema)
    }

    @Test
    fun `a grammar of another kind refuses a constraint its store can't make before anything is sent, on ensure and recreate`() {
        val grammar = object : SchemaGrammar by FalkorDbSchemaGrammar() {
            override fun requireConstrainable(spec: ConstraintSpec) {
                if (spec.properties.any { "." in it }) throw DrivineException("This store can't hold ${spec.properties} to a constraint.")
            }
        }
        val store = RecordingStore(DatabaseType.FALKORDB, ddl = grammar, native = true)
        val dotted = UniquenessConstraintSpec("Person", "address.city")
        for (call in listOf<() -> Any?>({ store.constraints.ensure(dotted) }, { store.constraints.recreate(dotted) })) {
            val thrown = assertFailsWith<DrivineException> { call() }
            assertContains(thrown.message!!, "can't hold [address.city]")
        }
        assertEquals(emptyList(), store.schema)
    }

    @Test
    fun `a listing that fails, which the planner reads as none, is logged at DEBUG under the manager that read it`() {
        val logger = LoggerFactory.getLogger(IndexManager::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val level = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            val store = RecordingStore(DatabaseType.NEO4J, ddl = Neo4jSchemaGrammar()) { ran ->
                if (ran.statement.startsWith("SHOW")) RuntimeException("no listing yet") else emptyList<Any?>()
            }
            store.indexes.ensure(RangeIndexSpec("Unlisted", "k"))
        } finally {
            logger.detachAppender(appender)
            logger.level = level
        }
        val debug = appender.list.filter { it.level == Level.DEBUG }.map { it.formattedMessage }
        assertContains(debug, "Index introspection failed (treating as no indexes exist): no listing yet")
    }

    @Test
    fun `a constraint listing and a sample that fail, which the planner reads as none, are logged at DEBUG under ConstraintManager`() {
        val logger = LoggerFactory.getLogger(ConstraintManager::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val level = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        val refusal = org.neo4j.driver.exceptions.Neo4jException("Neo.DatabaseError.Schema.ConstraintCreationFailed", "Unable to create Constraint")
        try {
            val store = RecordingStore(DatabaseType.NEO4J, ddl = Neo4jSchemaGrammar()) { ran ->
                when {
                    ran.statement.startsWith("SHOW CONSTRAINTS") -> RuntimeException("no listing yet")
                    ran.statement.startsWith("CREATE CONSTRAINT") -> refusal
                    "_count > 1" in ran.statement -> RuntimeException("sample lost")
                    else -> emptyList<Any?>()
                }
            }
            assertSame(refusal, assertFails { store.constraints.ensure(UniquenessConstraintSpec("Unlisted", "k")) })
        } finally {
            logger.detachAppender(appender)
            logger.level = level
        }
        val debug = appender.list.filter { it.level == Level.DEBUG }.map { it.formattedMessage }
        assertContains(debug, "Constraint introspection failed (treating as no constraints exist): no listing yet")
        assertContains(debug, "Could not read the keys that repeat: sample lost")
    }

    @Test
    fun `a poll interrupted while it waits is thrown as it is, the thread left interrupted, and nothing more is run`() {
        val listed = StorePlanListingRows.rows("falkordb", listOf(SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Polled", listOf("k"), status = "OPERATIONAL")), constraints = false)
        val store = RecordingStore(DatabaseType.FALKORDB, ddl = FalkorDbSchemaGrammar(), native = true) { listed }
        val interrupted = InterruptedException("stopped")
        val driver = SchemaPlanDriver(store.executor, FalkorDbSchemaGrammar(), { null }, sleep = { throw interrupted })
        try {
            val thrown = assertFails { driver.item(ItemCall.DROP, RangeIndexSpec("Polled", "k")) }
            assertSame(interrupted, thrown)
            assertTrue(Thread.currentThread().isInterrupted, "the interrupt is kept")
        } finally {
            Thread.interrupted()
        }
    }

    private fun assertNotNullScopes(grammar: SchemaGrammar): String =
        checkNotNull(grammar.versionMarker(SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY).scopes) { "SQLite's grammar gives its scopes in SQL" }
}
