package org.drivine.manager

import org.drivine.NonAtomicBatchException
import org.drivine.StaleObjectException
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.FalkorDbTransactionMode
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.atomic.Bookmark
import sample.atomic.BookmarkHashes
import sample.atomic.ChunkHash
import sample.atomic.Keyed
import sample.atomic.Ledger
import sample.atomic.LedgerNote
import sample.atomic.StampedTagged
import sample.atomic.Tagged
import sample.atomic.Watermark
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A `saveAll` of fragments of more than one label is one statement, on every engine, and so is
 * applied whole or not at all: on FalkorDB too, which cannot roll back a statement it has run.
 */
abstract class StatelessSaveAllAtomicContract {

    abstract val pm: PersistenceManager

    /**
     * Whether the store writes a batch as Cypher's `UNWIND`, which a fragment of some classes cannot be
     * written by, so that it is saved by a statement of its own. A SQL store's write function writes any.
     */
    protected open val unwinds: Boolean = true

    /**
     * Whether two writers over the same nodes wait on each other's locks, so that taking them in one
     * order keeps either from deadlocking the other. Memgraph turns a conflicting write away instead,
     * whatever the order.
     */
    protected open val waitsOnLocks: Boolean = true

    protected val stateless: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    private val uniqueCursor = UniquenessConstraintSpec("Bookmark", "cursor")

    protected fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    protected fun ids(label: String): Set<String> =
        pm.query(QuerySpecification.withStatement("MATCH (n:$label) RETURN n.id").transform(String::class.java)).toSet()

    private fun stamp(id: String): String? = pm.query(
        QuerySpecification.withStatement("MATCH (n {id: \$id}) RETURN n.${Stamps.QUOTED}").bind(mapOf("id" to id)).transform(String::class.java)
    ).firstOrNull()

    /** Every node, with its labels and properties, as sorted lines. */
    protected fun snapshot(): List<String> = pm.query(
        QuerySpecification.withStatement("MATCH (n) RETURN {labels: labels(n), properties: properties(n)} AS row").transform(Map::class.java)
    ).map { row ->
        val properties = (row["properties"] as Map<*, *>).entries.sortedBy { it.key.toString() }.joinToString { "${it.key}=${it.value}" }
        "(${(row["labels"] as List<*>).joinToString(":")} $properties)"
    }.sorted()

    /** The size of each batch [save] runs, through a manager of its own. */
    protected fun batches(save: (StatelessGraphObjectManager) -> Unit): List<Int> {
        val counting = BatchSizes(pm)
        save(StatelessGraphObjectManager(counting, Neo4jObjectMapper.instance, SubtypeRegistry()))
        return counting.batches
    }

    /** Runs [test] with the cursor of a bookmark unique, and the constraint dropped afterwards. */
    protected fun withUniqueCursor(test: () -> Unit) {
        pm.constraints.ensure(uniqueCursor)
        try {
            test()
        } finally {
            pm.constraints.drop(uniqueCursor)
        }
    }

    @BeforeEach
    fun clean() = run("MATCH (n) DETACH DELETE n")

    @Test
    fun `a batch of fragments of three labels is one statement`() {
        val sizes = batches { it.saveAll(listOf(ChunkHash("h1", "one"), Bookmark("b1", "c-1"), Ledger("l1", 1))) }

        assertEquals(listOf(1), sizes)
        assertEquals(setOf("h1"), ids("ChunkHash"))
        assertEquals(setOf("b1"), ids("Bookmark"))
        assertEquals(setOf("l1"), ids("Ledger"))
    }

    @Test
    fun `a batch of two labels hands each stamped fragment the stamp its node carries`() {
        stateless.save(Bookmark("b1", "c-1"))

        val saved = stateless.saveAll(listOf(ChunkHash("h1", "one"), Bookmark("b1", "c-2"), Bookmark("b2", "c-9")))

        // A node that was there is handed its data's token alone to an object that carried no stamp.
        val moved = saved[1] as Bookmark
        val made = saved[2] as Bookmark
        assertEquals("${Stamps.nodeToken(assertNotNull(stamp("b1")))}:", moved.stamp)
        assertEquals(stamp("b2"), made.stamp)
        assertEquals(ChunkHash("h1", "one"), saved[0])
    }

    @Test
    fun `a batch of four labels writes each node to its own label, over nodes that are there and new ones`() {
        stateless.saveAll(listOf(ChunkHash("h1", "one"), Ledger("l1", 1), Bookmark("b1", "c-1"), Watermark("w1", 1)))

        val saved = stateless.saveAll(
            listOf(
                Watermark("w1", 2), ChunkHash("h1", "uno"), Bookmark("b1", "c-2"), Ledger("l1", 2),
                Bookmark("b2", "c-9"), ChunkHash("h2", "two"), Watermark("w2", 9), Ledger("l2", 9),
            ),
        )

        assertEquals(
            listOf(
                "(Bookmark cursor=c-2, id=b1)", "(Bookmark cursor=c-9, id=b2)",
                "(ChunkHash chunk=two, id=h2)", "(ChunkHash chunk=uno, id=h1)",
                "(Ledger id=l1, total=2)", "(Ledger id=l2, total=9)",
                "(Watermark at=2, id=w1)", "(Watermark at=9, id=w2)",
            ),
            snapshot().map { it.replace(Regex("""__drivine\.stamp=[^,]*, """), "") }.sorted(),
        )
        assertEquals(stamp("w2"), (saved[6] as Watermark).stamp)
        assertEquals(stamp("b2"), (saved[4] as Bookmark).stamp)
    }

    @Test
    fun `a batch of two stamped labels writes each node to its own label and hands each its stamp`() {
        stateless.saveAll(listOf(Bookmark("b1", "c-1"), Watermark("w1", 1)))

        val saved = stateless.saveAll(listOf(Watermark("w1", 2), Bookmark("b1", "c-2"), Watermark("w2", 9)))

        assertEquals(
            listOf("(Bookmark cursor=c-2, id=b1)", "(Watermark at=2, id=w1)", "(Watermark at=9, id=w2)"),
            snapshot().map { it.replace(Regex("""__drivine\.stamp=[^,]*, """), "") }.sorted(),
        )
        assertEquals(stamp("w2"), (saved[2] as Watermark).stamp)
        assertEquals("${Stamps.nodeToken(assertNotNull(stamp("b1")))}:", (saved[1] as Bookmark).stamp)
    }

    @Test
    fun `a bookmark handed back by a batch of two labels saves again, and a stale copy of it is refused`() {
        val first = Bookmark("b1", "c-1")
        val saved = stateless.saveAll(listOf(ChunkHash("h1", "one"), first))[1] as Bookmark

        val again = stateless.save(saved.copy(cursor = "c-2"))
        assertFailsWith<StaleObjectException> { stateless.save(saved.copy(cursor = "c-3")) }

        assertEquals("c-2", stateless.load("b1", Bookmark::class.java)?.cursor)
        assertEquals(stamp("b1"), again.stamp)
    }

    @Test
    fun `a batch of two labels that fails on its last label writes nothing`() = withUniqueCursor {
        stateless.save(Bookmark("b0", "c-1"))
        val before = snapshot()

        assertFailsWith<RuntimeException> {
            stateless.saveAll(listOf(ChunkHash("h1", "one"), Ledger("l1", 1), Bookmark("b1", "c-1")))
        }

        assertEquals(before, snapshot())
    }

    @Test
    fun `a batch of one label is the statement it always was`() {
        val sizes = batches { it.saveAll(listOf(Bookmark("b1", "c-1"), Bookmark("b2", "c-2"))) }

        assertEquals(listOf(1), sizes)
        assertEquals(setOf("b1", "b2"), ids("Bookmark"))
    }

    @Test
    fun `a batch of two labels that holds a view is a statement for the fragments and one for the view`() {
        val sizes = batches {
            it.saveAll(listOf(ChunkHash("h1", "one"), Ledger("l1", 1), BookmarkHashes(Bookmark("b1", "c-1"), listOf(ChunkHash("h2", "two")))))
        }

        assertEquals(listOf(2), sizes)
        assertEquals(setOf("h1", "h2"), ids("ChunkHash"))
        assertEquals(setOf("l1"), ids("Ledger"))
    }

    @Test
    fun `a batch that holds an unstamped fragment an UNWIND cannot write saves it alone and the other labels as one statement`() {
        val sizes = batches {
            it.saveAll(listOf(ChunkHash("h1", "one"), Tagged("t1", mapOf("colour" to "red")), Ledger("l1", 1), Bookmark("b1", "c-1")))
        }

        assertEquals(listOf(if (unwinds) 2 else 1), sizes)
        assertEquals(mapOf("colour" to "red"), stateless.load("t1", Tagged::class.java)?.tags)
        listOf("ChunkHash" to "h1", "Ledger" to "l1", "Bookmark" to "b1").forEach { (label, id) -> assertEquals(setOf(id), ids(label)) }
    }

    @Test
    fun `a batch that holds a stamped fragment an UNWIND cannot write hands it and the fragments saved as one statement each their stamp`() {
        val saved = stateless.saveAll(listOf(StampedTagged("s1", mapOf("colour" to "red")), ChunkHash("h1", "one"), Bookmark("b1", "c-1")))

        assertEquals(stamp("s1"), (saved[0] as StampedTagged).stamp)
        assertEquals(stamp("b1"), (saved[2] as Bookmark).stamp)
        assertEquals(ChunkHash("h1", "one"), saved[1])
    }

    @Test
    fun `two fragments of one node in a batch of two labels write their fields to that node`() {
        stateless.saveAll(listOf(Ledger("l1", 1), LedgerNote("l1", "noted"), ChunkHash("h1", "one"), Ledger("l2", 2)))

        assertEquals(
            listOf("(ChunkHash chunk=one, id=h1)", "(Ledger id=l1, note=noted, total=1)", "(Ledger id=l2, total=2)"),
            snapshot().map { it.replace(Regex("""__drivine\.stamp=[^,]*, """), "") }.sorted(),
        )
    }

    @Test
    fun `a batch of two labels merges a fragment on the name its id is stored under, and clears a null under CLEAR`() {
        stateless.saveAll(listOf(Keyed("k1", "kept"), Keyed("k2", "cleared"), ChunkHash("h1", "one")))

        stateless.saveAll(listOf(Keyed("k1", null), ChunkHash("h1", "uno")))
        stateless.saveAllFields(listOf(Keyed("k2", null), ChunkHash("h1", "eins")), nullPolicy = NullPolicy.CLEAR)

        assertEquals(
            listOf("(ChunkHash chunk=eins, id=h1)", "(Keyed keyed_id=k1, note=kept)", "(Keyed keyed_id=k2)"),
            snapshot().map { it.replace(Regex("""__drivine\.stamp=[^,]*(, )?"""), "") }.sorted(),
        )
    }

    @Test
    fun `a batch of two labels writes no field it is told to leave`() {
        stateless.saveAll(listOf(Keyed("k1", "kept"), LedgerNote("l1", "kept")))

        stateless.saveAllFields(listOf(Keyed("k1", "unwritten"), LedgerNote("l1", "unwritten")), except = setOf("note"))

        assertEquals(listOf("kept", "kept"), listOf(stateless.load("k1", Keyed::class.java)?.note, stateless.load("l1", LedgerNote::class.java)?.note))
    }

    // ----- Contention -----

    @Test
    fun `two batches over the same nodes, given in opposite orders, both finish, each handing its objects back in its own order`() {
        assumeTrue(waitsOnLocks, "the store turns a conflicting write away, in whatever order it takes its nodes")
        // More than one statement's rows, so that a Cypher store writes them by statements of each class.
        val ids = (0..BatchSaveOperations.UNWIND_CHUNK_SIZE).map { "b%04d".format(it) }
        stateless.saveAll(ids.map { Bookmark(it, "start") })
        val failures = mutableListOf<Throwable>()
        val handedBack = mutableMapOf<String, List<Bookmark>>()
        val runs = mutableMapOf<String, BatchSizes>()

        val writers = listOf(ids to "up", ids.reversed() to "down").map { (order, cursor) ->
            val counting = BatchSizes(pm).also { synchronized(runs) { runs[cursor] = it } }
            val writer = StatelessGraphObjectManager(counting, Neo4jObjectMapper.instance, SubtypeRegistry())
            thread {
                try {
                    repeat(ROUNDS) { round ->
                        val saved = writer.saveAll(order.map { Bookmark(it, "$cursor $round") })
                        synchronized(handedBack) { handedBack[cursor] = saved }
                    }
                } catch (failure: Throwable) {
                    synchronized(failures) { failures += failure }
                }
            }
        }
        writers.forEach { it.join(120_000) }

        assertTrue(writers.none { it.isAlive })
        assertEquals(emptyList(), failures)
        // Where each batch is applied whole, the one that ran last left every node.
        val left = stateless.loadAll(Bookmark::class.java).map { it.cursor }.toSet()
        val last = setOf("up ${ROUNDS - 1}", "down ${ROUNDS - 1}")
        assertTrue(if (pm.atomicBatches) left.size == 1 && left.single() in last else left.isNotEmpty() && last.containsAll(left), "$left")
        assertEquals(ids, handedBack.getValue("up").map { it.id })
        assertEquals(ids.reversed(), handedBack.getValue("down").map { it.id })
        assertTrue(handedBack.values.flatten().all { it.stamp != null })
        // Taking the locks in one order, neither batch deadlocks the other, so none is run again.
        assertEquals(mapOf("up" to ROUNDS, "down" to ROUNDS), runs.mapValues { it.value.batches.size })
    }

    private companion object {
        /** The batches each writer of a contention test runs. */
        const val ROUNDS = 3
    }
}

/** Counts the statements of each batch, and passes everything to [delegate]. */
private class BatchSizes(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    val batches = mutableListOf<Int>()

    override fun queryBatch(specs: List<QuerySpecification<*>>): List<List<Any?>> {
        batches += specs.size
        return delegate.queryBatch(specs)
    }

    override fun executeBatch(specs: List<QuerySpecification<*>>) {
        batches += specs.size
        delegate.executeBatch(specs)
    }
}

class StatelessSaveAllAtomicNeo4jTest : StatelessSaveAllAtomicContract() {
    companion object {
        private const val PASSWORD = SharedNeo4j.PASSWORD

        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-atomic-save-all", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager

    @Test
    fun `Neo4j applies a batch of more than one statement whole`() {
        assertTrue(pm.atomicBatches)
    }

    @Test
    fun `a batch of two views that fails on the second writes neither`() = withUniqueCursor {
        stateless.save(Bookmark("b0", "c-1"))
        val before = snapshot()

        assertFailsWith<RuntimeException> {
            stateless.saveAll(
                listOf(
                    BookmarkHashes(Bookmark("b1", "c-2"), listOf(ChunkHash("h1", "one"))),
                    BookmarkHashes(Bookmark("b2", "c-1"), listOf(ChunkHash("h2", "two"))),
                ),
            )
        }

        assertEquals(before, snapshot())
    }
}

class StatelessSaveAllAtomicFalkorDbTest : StatelessSaveAllAtomicContract() {
    companion object {
        private const val GRAPH = "atomicsaveall"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var strictProvider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager
        lateinit var strictManager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-atomic-save-all", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            strictProvider = FalkorDbConnectionProvider(
                name = "falkor-atomic-save-all-strict", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, transactionMode = FalkorDbTransactionMode.STRICT, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            strictManager = NonTransactionalPersistenceManager(strictProvider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() {
            provider.end()
            strictProvider.end()
        }
    }

    override val pm: NonTransactionalPersistenceManager get() = manager

    private val strict: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(strictManager, Neo4jObjectMapper.instance, SubtypeRegistry())

    private fun views() = listOf(
        BookmarkHashes(Bookmark("b1", "c-1"), listOf(ChunkHash("h1", "one"))),
        BookmarkHashes(Bookmark("b2", "c-2"), listOf(ChunkHash("h2", "two"))),
    )

    @Test
    fun `FalkorDB says a batch of more than one statement is not applied whole`() {
        assertFalse(pm.atomicBatches)
        assertFalse(strictManager.atomicBatches)
    }

    @Test
    fun `in WARN mode a batch that is more than one statement is run statement by statement`() {
        val sizes = batches { it.saveAll(views()) }

        assertEquals(listOf(2), sizes)
        assertEquals(setOf("b1", "b2"), ids("Bookmark"))
    }

    @Test
    fun `in STRICT mode a batch of fragments of three labels is written`() {
        val saved = strict.saveAll(listOf(ChunkHash("h1", "one"), Bookmark("b1", "c-1"), Ledger("l1", 1)))

        assertEquals(setOf("h1"), ids("ChunkHash"))
        assertEquals(setOf("l1"), ids("Ledger"))
        assertNotNull((saved[1] as Bookmark).stamp)
    }

    @Test
    fun `in STRICT mode a single save is written`() {
        strict.save(BookmarkHashes(Bookmark("b1", "c-1"), listOf(ChunkHash("h1", "one"))))

        assertEquals(setOf("h1"), ids("ChunkHash"))
    }

    @Test
    fun `in STRICT mode a batch that is more than one statement is refused before anything is written`() {
        stateless.save(Bookmark("b0", "c-0"))
        val before = snapshot()

        val refusal = assertFailsWith<NonAtomicBatchException> { strict.saveAll(listOf(Ledger("l1", 1)) + views()) }

        assertEquals(3, refusal.statements)
        assertEquals(before, snapshot())
    }
}

class StatelessSaveAllAtomicMemgraphTest : StatelessSaveAllAtomicContract() {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-atomic-save-all", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager

    override val waitsOnLocks = false
}

/**
 * Postgres writes a batch through its write function, which makes the new nodes of a shape together
 * before its steps run: over nodes that are there and new ones, two fragments of one node, and a batch
 * that fails, which writes nothing.
 */
@PostgresTests
class StatelessSaveAllAtomicPostgresTest : StatelessSaveAllAtomicContract() {
    override val pm: PersistenceManager = PostgresTestDatabase.fixtures(PostgresTestDatabase.graphManager("contracts"))

    override val unwinds = false
}

class StatelessSaveAllAtomicSqliteTest : StatelessSaveAllAtomicContract() {
    override val pm: PersistenceManager = SqliteTestDatabase.fixtures(SqliteTestDatabase.graphManager("contracts"))

    override val unwinds = false
}
