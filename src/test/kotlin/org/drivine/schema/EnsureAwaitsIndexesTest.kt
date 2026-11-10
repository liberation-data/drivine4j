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
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Nodes enough that an index over them takes a while to build. */
private const val SEEDED = 20_000

private fun PersistenceManager.run(cypher: String, params: Map<String, Any?> = emptyMap()) =
    execute(QuerySpecification.withStatement(cypher).bind(params))

private fun PersistenceManager.rows(cypher: String, params: Map<String, Any?> = emptyMap()): List<Any?> =
    query(QuerySpecification.withStatement(cypher).bind(params))

/** Seeds [SEEDED] nodes of [label], each with a number `k`, a text `s` and an embedding `e` of four dimensions. */
private fun PersistenceManager.seed(label: String) =
    run("UNWIND range(1, $SEEDED) AS i CREATE (:$label {k: i, s: 'text of node ' + toString(i), e: [toFloat(i), 1.0, 2.0, 3.0]})")

/**
 * Neo4j builds an index in the background: `CREATE INDEX … IF NOT EXISTS` returns while it is still POPULATING.
 * A write too large for a range index that commits just as one comes online can stop the database, so `ensure`
 * and `recreate` wait until the index is ONLINE, and a build that fails is an error that names the index.
 */
class Neo4jEnsureAwaitsIndexesTest {

    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "neo4j-await-indexes", type = DatabaseType.NEO4J, host = container.host,
                port = container.getMappedPort(7687), user = "neo4j", password = SharedNeo4j.PASSWORD,
                database = null, config = emptyMap(), cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.NEO4J, SubtypeRegistry())
        }

        @JvmStatic
        @AfterAll
        fun teardown() = provider.end()
    }

    /** The state SHOW INDEXES gives the index [name]. */
    private fun state(name: String): String =
        manager.rows("SHOW INDEXES YIELD name, state WHERE name = \$name RETURN state", mapOf("name" to name)).single() as String

    @Test
    fun `ensure returns once the range, text, fulltext and vector indexes it made are ONLINE`() {
        manager.seed("AwaitNeo")
        val specs = listOf(
            RangeIndexSpec("AwaitNeo", "k"),
            TextIndexSpec("AwaitNeo", "s"),
            FullTextIndexSpec("AwaitNeo", listOf("s")),
            VectorIndexSpec("AwaitNeo", "e", 4),
        )
        specs.forEach { spec ->
            val created = manager.indexes.ensure(spec)
            assertTrue(created is EnsureResult.Created, "$created")
            assertEquals("ONLINE", state(created.info.name!!), "${spec.kind} right after ensure")
        }
    }

    @Test
    fun `recreate returns once the index it made again is ONLINE`() {
        manager.seed("AwaitNeoAgain")
        val spec = RangeIndexSpec("AwaitNeoAgain", "k")
        manager.indexes.ensure(spec)
        val recreated = manager.indexes.recreate(spec)
        assertEquals("ONLINE", state(recreated.current.name!!))
    }

    @Test
    fun `an index another process made, found by ensure, is waited for by the name it was made with`() {
        manager.seed("AwaitNeoFound")
        manager.run("CREATE FULLTEXT INDEX AwaitNeoFoundElsewhere FOR (n:AwaitNeoFound) ON EACH [n.s]")
        val spec = FullTextIndexSpec("AwaitNeoFound", listOf("s"))
        try {
            val found = manager.indexes.ensure(spec)
            assertTrue(found is EnsureResult.AlreadyMatching, "$found")
            assertEquals("ONLINE", state("AwaitNeoFoundElsewhere"))
        } finally {
            manager.indexes.drop(spec)
        }
    }

    @Test
    fun `a constraint's own index is ONLINE once ensure returns`() {
        manager.seed("AwaitNeoUnique")
        val created = manager.constraints.ensure(UniquenessConstraintSpec("AwaitNeoUnique", "k"))
        assertTrue(created is EnsureResult.Created, "$created")
        assertEquals("ONLINE", state(created.info.name!!))
    }

    @Test
    fun `a wait that fails for a reason other than the build is thrown as it is, not taken for a failed build`() {
        // A wait on an index that is not there: Neo4j fails it, and the index ensure found is ONLINE, not FAILED.
        // The index is made and waited for first, so it can't still be POPULATING when the wait fails, which would
        // rightly make it an index still being built (drivine4j#743).
        val noSuchIndex = object : SchemaGrammar by Neo4jSchemaGrammar() {
            override val awaitIndexStatement = "CALL db.awaitIndex('AwaitNeoNoSuchIndex', \$timeout)"
        }
        val indexes = IndexManager(SchemaExecutor(provider), noSuchIndex)
        val spec = RangeIndexSpec("AwaitNeoOtherError", "k")
        try {
            assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
            assertEquals("ONLINE", state(spec.effectiveName))
            val reported = mutableListOf<UnbuiltIndex>()
            val thrown = assertFails { indexes.ensure(spec, IndexBuild(Duration.ofSeconds(5)) { reported += it }) }
            assertTrue(reported.isEmpty(), "not reported as unbuilt: ${reported.map { it.error.message }}")
            assertFalse("failed to build" in thrown.message.orEmpty(), thrown.message)
            assertContains(thrown.message.orEmpty(), "AwaitNeoNoSuchIndex")
        } finally {
            manager.indexes.drop(spec)
        }
    }

    @Test
    fun `a wait that fails for a reason other than its timeout, with the index still POPULATING, is an index still being built`() {
        // The timeout's code not recognised: the state SHOW INDEXES gives, POPULATING, says it is still being built.
        val codeUnknown = object : SchemaGrammar by Neo4jSchemaGrammar() {
            override fun isBuildTimeout(e: Throwable) = false
        }
        val indexes = IndexManager(SchemaExecutor(provider), codeUnknown)
        manager.seed("AwaitNeoPopulating")
        val spec = FullTextIndexSpec("AwaitNeoPopulating", listOf("s"))
        try {
            val reported = mutableListOf<UnbuiltIndex>()
            val created = indexes.ensure(spec, IndexBuild(Duration.ZERO) { reported += it })
            assertTrue(created is EnsureResult.Created, "$created")
            val unbuilt = reported.single()
            assertTrue(unbuilt.stillBuilding, unbuilt.error.message)
            assertContains(unbuilt.error.message!!, "still being built")
            assertContains(unbuilt.error.message!!, spec.effectiveName)
        } finally {
            manager.run("CALL db.awaitIndexes(60)")
            manager.indexes.drop(spec)
        }
    }

    // A constraint's own index is listed, as an index over the same property, so it is the one waited for, by its name.
    @Test
    fun `a range index over what a uniqueness constraint's own index covers is ensured, with nothing more to wait for`() {
        manager.seed("AwaitNeoCovered")
        val constraint = UniquenessConstraintSpec("AwaitNeoCovered", "k")
        val index = RangeIndexSpec("AwaitNeoCovered", "k")
        try {
            manager.constraints.ensure(constraint)
            val ensured = manager.indexes.ensure(index)
            assertTrue(ensured !is EnsureResult.Drift, "$ensured")
            assertEquals(ensured, manager.indexes.ensure(index), "and again")
        } finally {
            manager.constraints.drop(constraint)
            manager.indexes.drop(index)
        }
    }

    @Test
    fun `an index whose build fails is an error naming it and giving Neo4j's reason, on ensure and on every ensure after`() {
        // A text too large for a range index, there before the index is: its build fails, and the index stays FAILED.
        manager.run("CREATE (:AwaitNeoFailed {s: \$s})", mapOf("s" to "x".repeat(40_000)))
        val spec = RangeIndexSpec("AwaitNeoFailed", "s")
        try {
            val failed = assertFailsWith<DrivineException> { manager.indexes.ensure(spec) }
            assertContains(failed.message!!, spec.effectiveName)
            assertContains(failed.message!!, "too large", ignoreCase = true)
            assertEquals("FAILED", state(spec.effectiveName))

            // Found again, it is not taken for an index that serves: it is still the error.
            val again = assertFailsWith<DrivineException> { manager.indexes.ensure(spec) }
            assertContains(again.message!!, spec.effectiveName)
        } finally {
            manager.indexes.drop(spec)
        }
    }
}

/**
 * FalkorDB builds an index in the background, `CALL db.indexes()` giving its label UNDER CONSTRUCTION meanwhile,
 * and a vector query answers from the half-built index (FalkorDB/FalkorDB#3382). `ensure` waits until it is
 * OPERATIONAL.
 */
class FalkorDbEnsureAwaitsIndexesTest {

    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = FalkorDbConnectionProvider(
                name = "falkordb-await-indexes", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = "awaitindexes",
            )
            manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.FALKORDB, SubtypeRegistry())
        }

        @JvmStatic
        @AfterAll
        fun teardown() = provider.end()
    }

    /** The status `db.indexes()` gives the indexes of [label]. */
    private fun status(label: String): String =
        manager.rows("CALL db.indexes() YIELD label, status RETURN {label: label, status: status}")
            .map { it as Map<*, *> }.single { it["label"] == label }["status"] as String

    @Test
    fun `ensure returns once the range, fulltext and vector indexes it made are OPERATIONAL`() {
        listOf(
            RangeIndexSpec("AwaitFalkorRange", "k"),
            FullTextIndexSpec("AwaitFalkorText", listOf("s")),
            VectorIndexSpec("AwaitFalkorVector", "e", 4),
        ).forEach { spec ->
            manager.seed(spec.label)
            val created = manager.indexes.ensure(spec)
            assertTrue(created is EnsureResult.Created, "$created")
            assertEquals("OPERATIONAL", status(spec.label), "${spec.kind} right after ensure")
        }
    }

    @Test
    fun `each index ensure makes on a label that has others is OPERATIONAL when it returns`() {
        // db.indexes() gives one row, and one status, for a label's range, fulltext and vector indexes alike.
        manager.seed("AwaitFalkorShared")
        listOf(
            RangeIndexSpec("AwaitFalkorShared", listOf("k", "s")),
            FullTextIndexSpec("AwaitFalkorShared", listOf("s")),
            VectorIndexSpec("AwaitFalkorShared", "e", 4),
        ).forEach { spec ->
            val created = manager.indexes.ensure(spec)
            assertTrue(created is EnsureResult.Created, "$created")
            assertEquals("OPERATIONAL", status("AwaitFalkorShared"), "${spec.kind} right after ensure")
        }
    }

    @Test
    fun `recreate returns once the index it made again is OPERATIONAL`() {
        manager.seed("AwaitFalkorAgain")
        val spec = RangeIndexSpec("AwaitFalkorAgain", "k")
        manager.indexes.ensure(spec)
        manager.indexes.recreate(spec)
        assertEquals("OPERATIONAL", status("AwaitFalkorAgain"))
    }

    @Test
    fun `an index another process made, found by ensure, is OPERATIONAL when it returns`() {
        manager.seed("AwaitFalkorFound")
        manager.run("CREATE VECTOR INDEX FOR (n:AwaitFalkorFound) ON (n.e) OPTIONS {dimension: 4, similarityFunction: 'cosine'}")
        val spec = VectorIndexSpec("AwaitFalkorFound", "e", 4)
        try {
            val found = manager.indexes.ensure(spec)
            assertTrue(found is EnsureResult.AlreadyMatching, "$found")
            assertEquals("OPERATIONAL", status("AwaitFalkorFound"))
        } finally {
            manager.indexes.drop(spec)
        }
    }

    @Test
    fun `an index FalkorDB lists as FAILED is an error naming it, not an index taken as built`() {
        // FalkorDB has not been seen to fail an index's build; one listed so is not handed back as made.
        val failed = object : SchemaGrammar by FalkorDbSchemaGrammar() {
            override fun parseIndexRows(rows: List<Any?>) = FalkorDbSchemaGrammar().parseIndexRows(rows).map { it.copy(status = "FAILED") }
        }
        val indexes = IndexManager(SchemaExecutor(provider), failed)
        val spec = RangeIndexSpec("AwaitFalkorFailed", "k")
        try {
            val thrown = assertFailsWith<DrivineException> { indexes.ensure(spec) }
            assertContains(thrown.message!!, "failed to build")
            assertContains(thrown.message!!, "AwaitFalkorFailed")

            val reported = mutableListOf<UnbuiltIndex>()
            val found = indexes.ensure(spec, IndexBuild(Duration.ofSeconds(5)) { reported += it })
            assertTrue(found is EnsureResult.AlreadyMatching, "$found")
            assertFalse(reported.single().stillBuilding, reported.single().error.message)
        } finally {
            manager.indexes.drop(spec)
        }
    }
}

/**
 * Memgraph builds an index inside the `CREATE INDEX` itself: it registers, populates and publishes the index in
 * the one transaction, letting other writes run meanwhile, and plans with an index only once it is published
 * (Memgraph's ADR 004, concurrent index creation). So `ensure` returns an index Memgraph plans with, and waits
 * for nothing more.
 */
class MemgraphEnsureAwaitsIndexesTest {

    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "memgraph-await-indexes", type = DatabaseType.MEMGRAPH, host = container.host,
                port = container.getMappedPort(7687), user = "", password = "", database = null,
                config = emptyMap(), cypherDialect = CypherDialect.MEMGRAPH,
            )
            manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.MEMGRAPH, SubtypeRegistry())
        }

        @JvmStatic
        @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `the range index ensure made is planned with and holds every node once ensure returns`() {
        manager.seed("AwaitMemgraph")
        val created = manager.indexes.ensure(RangeIndexSpec("AwaitMemgraph", "k"))
        assertTrue(created is EnsureResult.Created, "$created")
        val plan = manager.rows("EXPLAIN MATCH (n:AwaitMemgraph) WHERE n.k = 7 RETURN n").joinToString("\n")
        assertContains(plan, "ScanAllByLabelPropert")
        val info = manager.rows("SHOW INDEX INFO").map { it.toString() }.single { "AwaitMemgraph" in it }
        assertContains(info, "$SEEDED")
    }
}


/** What [SchemaManager] logs, kept for a test to read. */
private class SchemaManagerLog {
    private val logger = LoggerFactory.getLogger(SchemaManager::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    fun start() {
        appender.list.clear()
        appender.start()
        logger.addAppender(appender)
    }

    fun stop() {
        logger.detachAppender(appender)
        appender.stop()
    }

    fun warnings(): List<String> = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    fun summaries(): List<String> = appender.list.map { it.formattedMessage }.filter { it.startsWith("Schema for owner") }
}

/**
 * [SchemaManager] waits for each index it applies to be built, as [IndexManager.ensure] does, as long as its
 * `indexBuildTimeout` says. An index whose build failed, or that is still being built when the wait runs out, fails
 * its owner under `FAIL_FAST`, and is logged under `WARN`, which goes on.
 */
class Neo4jSchemaManagerAwaitsIndexesTest {

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
                type = DatabaseType.NEO4J, host = container.host, port = container.getMappedPort(7687),
                userName = "neo4j", password = SharedNeo4j.PASSWORD, databaseName = "neo4j",
                cypherDialect = CypherDialect.NEO4J_5,
            )
            registry = DatabaseRegistry(DataSourceMap(mapOf("neo" to props)))
            factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))
            manager = factory.get("neo", PersistenceManagerType.NON_TRANSACTIONAL)
        }
    }

    private val log = SchemaManagerLog()

    @BeforeEach
    fun startLog() = log.start()

    @AfterEach
    fun stopLog() = log.stop()

    private fun schemaManager(mode: SchemaMode, timeout: Duration? = null, vararg specs: SchemaItemSpec): SchemaManager {
        val catalogs = listOf(SchemaCatalog.of(specs.toList()).named("await").forDatabase("neo"))
        return if (timeout == null) SchemaManager(factory, registry, catalogs, SchemaPolicy(mode = mode))
        else SchemaManager(factory, registry, catalogs, SchemaPolicy(mode = mode), timeout)
    }

    /** The state SHOW INDEXES gives the index [name], or null where there is none. */
    private fun state(name: String): String? =
        manager.rows("SHOW INDEXES YIELD name, state WHERE name = \$name RETURN state", mapOf("name" to name)).singleOrNull() as String?

    /** A text too large for a range index, there before the index is: its build fails, and the index stays FAILED. */
    private fun tooLarge(label: String) = manager.run("CREATE (:$label {s: \$s, k: 1})", mapOf("s" to "x".repeat(40_000)))

    @Test
    fun `under WARN an index whose build failed is logged with Neo4j's reason, and the rest is applied`() {
        tooLarge("AwaitWarnFailed")
        val failing = RangeIndexSpec("AwaitWarnFailed", "s")
        val other = RangeIndexSpec("AwaitWarnFailed", "k")
        try {
            val schema = schemaManager(SchemaMode.WARN, null, failing, other)
            schema.enforce()

            assertEquals("FAILED", state(failing.effectiveName))
            assertEquals("ONLINE", state(other.effectiveName), "the item after it is applied")
            val warning = log.warnings().single { "BUILD FAILED" in it }
            assertContains(warning, failing.effectiveName)
            assertContains(warning, "too large", ignoreCase = true)
            assertEquals("Schema for owner 'await' on 'neo': 2 created, 0 already matching, 0 recreated, 1 needing attention", log.summaries().single())
            assertNotNull(SchemaVersionStore(manager, SchemaVersionStore.scopeFor("await")).storedRecord(), "the owner is recorded")

            // Found again, the failed index is not taken for one that serves: it is logged again.
            schema.enforce()
            assertEquals(2, log.warnings().count { "BUILD FAILED" in it && failing.effectiveName in it })
            assertEquals("Schema for owner 'await' on 'neo': 0 created, 2 already matching, 0 recreated, 1 needing attention", log.summaries().last())
        } finally {
            manager.indexes.drop(failing)
            manager.indexes.drop(other)
        }
    }

    @Test
    fun `under FAIL_FAST an index whose build failed fails the owner, naming it and giving Neo4j's reason`() {
        tooLarge("AwaitStrictFailed")
        val failing = RangeIndexSpec("AwaitStrictFailed", "s")
        val other = RangeIndexSpec("AwaitStrictFailed", "k")
        val marker = SchemaVersionStore(manager, SchemaVersionStore.scopeFor("await"))
        val recorded = marker.storedRecord() // another test of the class may have recorded the owner
        try {
            val failed = assertFailsWith<DrivineException> { schemaManager(SchemaMode.FAIL_FAST, null, failing, other).enforce() }
            assertContains(failed.message!!, "BUILD FAILED")
            assertContains(failed.message!!, failing.effectiveName)
            assertContains(failed.message!!, "too large", ignoreCase = true)
            assertEquals("FAILED", state(failing.effectiveName))
            // As with a drift, the owner's other items are applied before it fails, and its marker is not written.
            assertEquals("ONLINE", state(other.effectiveName), "the item after it is applied")
            assertEquals(recorded, marker.storedRecord(), "the owner's marker is left as it was")
        } finally {
            manager.indexes.drop(failing)
            manager.indexes.drop(other)
        }
    }

    @Test
    fun `under WARN an index still being built when the wait runs out is logged, and left to be built`() {
        manager.seed("AwaitWarnSlow")
        val slow = FullTextIndexSpec("AwaitWarnSlow", listOf("s"))
        try {
            // Zero: db.awaitIndex checks once. A timeout under a second is waited as a whole second.
            schemaManager(SchemaMode.WARN, Duration.ZERO, slow).enforce()

            val warning = log.warnings().single { "STILL BUILDING" in it }
            assertContains(warning, slow.effectiveName)
            assertContains(warning, "PT0S")
            assertEquals("Schema for owner 'await' on 'neo': 1 created, 0 already matching, 0 recreated, 1 needing attention", log.summaries().single())
            manager.run("CALL db.awaitIndexes(60)")
            assertEquals("ONLINE", state(slow.effectiveName), "left to be built")
        } finally {
            manager.indexes.drop(slow)
        }
    }

    @Test
    fun `under WARN a drifted index that recreate-on-drift makes again, still being built when the wait runs out, is logged`() {
        manager.seed("AwaitWarnDrift")
        val drifted = FullTextIndexSpec("AwaitWarnDrift", listOf("s"), analyzer = "english")
        manager.indexes.ensure(drifted)
        val declared = FullTextIndexSpec("AwaitWarnDrift", listOf("s"), analyzer = "standard")
        try {
            val catalogs = listOf(SchemaCatalog.of(listOf(declared)).named("await").forDatabase("neo"))
            SchemaManager(factory, registry, catalogs, SchemaPolicy(mode = SchemaMode.WARN, recreateOnDrift = true), Duration.ZERO).enforce()

            val warning = log.warnings().single { "STILL BUILDING" in it }
            assertContains(warning, declared.effectiveName)
            assertEquals("Schema for owner 'await' on 'neo': 0 created, 0 already matching, 1 recreated, 1 needing attention", log.summaries().single())
        } finally {
            manager.run("CALL db.awaitIndexes(60)")
            manager.indexes.drop(declared)
        }
    }

    @Test
    fun `under FAIL_FAST an index still being built when the wait runs out fails the owner, naming it`() {
        manager.seed("AwaitStrictSlow")
        val slow = FullTextIndexSpec("AwaitStrictSlow", listOf("s"))
        try {
            val failed = assertFailsWith<DrivineException> { schemaManager(SchemaMode.FAIL_FAST, Duration.ZERO, slow).enforce() }
            assertContains(failed.message!!, "STILL BUILDING")
            assertContains(failed.message!!, slow.effectiveName)
            assertContains(failed.message!!, "index-build-timeout")
        } finally {
            manager.run("CALL db.awaitIndexes(60)")
            manager.indexes.drop(slow)
        }
    }
}

/** [Neo4jSchemaManagerAwaitsIndexesTest]'s wait that runs out, on FalkorDB, which is polled until the index is OPERATIONAL. */
class FalkorDbSchemaManagerAwaitsIndexesTest {

    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var registry: DatabaseRegistry
        private lateinit var factory: PersistenceManagerFactory
        private lateinit var manager: PersistenceManager

        @JvmStatic
        @BeforeAll
        fun setup() {
            val props = ConnectionProperties(
                type = DatabaseType.FALKORDB, host = container.host, port = container.getMappedPort(6379),
                databaseName = "awaitschema",
            )
            registry = DatabaseRegistry(DataSourceMap(mapOf("falkor" to props)))
            factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))
            manager = factory.get("falkor", PersistenceManagerType.NON_TRANSACTIONAL)
        }
    }

    private val log = SchemaManagerLog()

    @BeforeEach
    fun startLog() = log.start()

    @AfterEach
    fun stopLog() = log.stop()

    private fun schemaManager(mode: SchemaMode, spec: SchemaItemSpec) = SchemaManager(
        factory, registry, listOf(SchemaCatalog.of(spec).named("await").forDatabase("falkor")),
        SchemaPolicy(mode = mode), Duration.ofMillis(2),
    )

    @Test
    fun `under WARN an index still being built when the wait runs out is logged`() {
        manager.seed("AwaitFalkorWarnSlow")
        val slow = VectorIndexSpec("AwaitFalkorWarnSlow", "e", 4)
        try {
            schemaManager(SchemaMode.WARN, slow).enforce()
            val warning = log.warnings().single { "STILL BUILDING" in it }
            assertContains(warning, "AwaitFalkorWarnSlow")
            assertContains(warning, "UNDER CONSTRUCTION")
        } finally {
            manager.indexes.ensure(slow) // waits, so the drop is of a built index
            manager.indexes.drop(slow)
        }
    }

    @Test
    fun `under FAIL_FAST an index still being built when the wait runs out fails the owner`() {
        manager.seed("AwaitFalkorStrictSlow")
        val slow = VectorIndexSpec("AwaitFalkorStrictSlow", "e", 4)
        try {
            val failed = assertFailsWith<DrivineException> { schemaManager(SchemaMode.FAIL_FAST, slow).enforce() }
            assertContains(failed.message!!, "STILL BUILDING")
            assertContains(failed.message!!, "AwaitFalkorStrictSlow")
        } finally {
            manager.indexes.ensure(slow)
            manager.indexes.drop(slow)
        }
    }
}

/** The timeout a [SchemaManager] is given, as Neo4j's `db.awaitIndex` is given it. No database is spoken to. */
class IndexBuildTimeoutTest {

    private val registry = DatabaseRegistry(DataSourceMap(emptyMap()))
    private val factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))

    @Test
    fun `the timeout is rounded up to the whole seconds db awaitIndex takes, so the wait is at least what was asked`() {
        assertEquals(0L, IndexBuild(Duration.ZERO).timeoutSeconds, "zero checks once")
        assertEquals(1L, IndexBuild(Duration.ofMillis(1)).timeoutSeconds)
        assertEquals(1L, IndexBuild(Duration.ofMillis(500)).timeoutSeconds)
        assertEquals(1L, IndexBuild(Duration.ofSeconds(1)).timeoutSeconds)
        assertEquals(2L, IndexBuild(Duration.ofMillis(1001)).timeoutSeconds)
        assertEquals(60L, IndexBuild(Duration.ofSeconds(60)).timeoutSeconds)
    }

    @Test
    fun `a manager built as before waits 60 seconds, and a negative timeout is refused`() {
        assertEquals(Duration.ofSeconds(60), SchemaManager(factory, registry, emptyList()).indexBuildTimeout)
        assertEquals(Duration.ofSeconds(60), SchemaManager(factory, registry, emptyList(), SchemaPolicy()).indexBuildTimeout)
        val refused = assertFailsWith<IllegalArgumentException> {
            SchemaManager(factory, registry, emptyList(), SchemaPolicy(), Duration.ofSeconds(-1))
        }
        assertContains(refused.message!!, "indexBuildTimeout")
    }
}
