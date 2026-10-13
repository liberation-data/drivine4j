package org.drivine.manager

import kotlin.concurrent.thread
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.drivine.StaleObjectException
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.FalkorDbTransactionMode
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.graphproperty.ChunkNode
import sample.nodelabels.ThingNode
import sample.patch.LooseNote
import sample.propertybag.BaggedNode
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Human
import sample.stateless.Memo
import sample.vector.DocNode

/**
 * `patchAll` with objects: named fields of many nodes, written only where the node is there; verified
 * on Neo4j, FalkorDB and Memgraph, and on Oracle.
 */
abstract class StatelessPatchAllContract {

    abstract val pm: PersistenceManager

    /** Whether a call that fails is rolled back whole: FalkorDB has no transactions. */
    open val atomicBatch: Boolean = true

    /** How many rows go to one statement. */
    open val chunk: Int = 1000

    /** Makes the index a vector search of [DocNode] reads, where the engine needs one to search. */
    open fun ensureVectorIndex() {
        pm.indexes.ensure(VectorIndexSpec("Doc", "embedding", 4))
    }

    private fun manager(over: PersistenceManager = pm) = StatelessGraphObjectManager(over, Neo4jObjectMapper.instance, SubtypeRegistry())

    private val stateless: StatelessGraphObjectManager
        get() = manager()

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    /** The stamp the node with [id] carries. */
    private fun stamp(id: String): String =
        pm.query(QuerySpecification.withStatement("MATCH (n {id: '$id'}) RETURN n.`${Stamps.PROPERTY}`").transform(String::class.java)).single()

    private fun claim(id: String): Claim = assertNotNull(stateless.load(id, Claim::class.java))

    private val found = PatchResult(found = true, applied = true, changed = true)
    private val unchanged = PatchResult(found = true, applied = true, changed = false)
    private val absent = PatchResult(found = false, applied = false, changed = false)

    @BeforeEach
    fun clean() = run("MATCH (n) DETACH DELETE n")

    // ----- What is written -----

    @Test
    fun `only the named fields are written, though the objects hold other values for the rest`() {
        stateless.saveAll(listOf(Claim("c1", "one", "first"), Claim("c2", "two", "second")))

        val results = stateless.patchAll(
            listOf(Claim("c1", "not written", "patched"), Claim("c2", "nor this", "patched too")),
            only = setOf(Claim::note),
        )

        assertEquals(listOf(found, found), results)
        assertEquals(listOf("one" to "patched", "two" to "patched too"), listOf(claim("c1"), claim("c2")).map { it.text to it.note })
    }

    @Test
    fun `except writes every field that can be named but those given`() {
        stateless.save(Claim("c1", "one", "first"))

        stateless.patchAll(listOf(Claim("c1", "patched", "not written")), except = setOf(Claim::note))

        assertEquals("patched" to "first", claim("c1").let { it.text to it.note })
    }

    @Test
    fun `a field is written under the property it is stored as`() {
        stateless.save(ChunkNode("k1", "text", containerSectionId = "s1", sequenceNumber = 1))

        val results = stateless.patchAll(listOf(ChunkNode("k1", "other", containerSectionId = "s2", sequenceNumber = 2)), only = setOf(ChunkNode::sequenceNumber))

        assertEquals(listOf(found), results)
        val stored = assertNotNull(stateless.load("k1", ChunkNode::class.java))
        assertEquals(Triple("text", "s1", 2L), Triple(stored.text, stored.containerSectionId, stored.sequenceNumber))
    }

    @Test
    fun `a view is patched by the fields of its root, and its relationships are not written`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("h1", "Ada"))))

        val results = stateless.patchAll(listOf(ClaimView(Claim("c1", "patched"), people = listOf(Human("h2", "Bob")))), only = setOf(Claim::text))

        assertEquals(listOf(found), results)
        val view = assertNotNull(stateless.load("c1", ClaimView::class.java))
        assertEquals("patched" to listOf("h1"), view.claim.text to view.people.map { it.id })
        assertNull(stateless.load("h2", Human::class.java))
    }

    // ----- A node that is gone -----

    @Test
    fun `a node that is gone is not made`() {
        val claims = (1..200).map { Claim("c$it", "text $it") }
        stateless.saveAll(claims)
        val gone = listOf(7, 90, 200)
        gone.forEach { stateless.delete("c$it", Claim::class.java) }

        val results = stateless.patchAll(claims.map { it.copy(note = "patched") }, only = setOf(Claim::note))

        assertEquals(197, results.count { it.found })
        assertEquals(gone.map { it - 1 }, results.indices.filter { results[it] == absent })
        assertEquals(197, stateless.count(Claim::class.java))
        assertEquals("patched", claim("c8").note)
    }

    @Test
    fun `results are in the order of the items, whichever were found`() {
        stateless.saveAll(listOf(Claim("a", "one", "same"), Claim("c", "three", "same"), Claim("e", "five")))

        val results = stateless.patchAll(
            listOf("e", "d", "c", "b", "a").map { Claim(it, "unwritten", "same") },
            only = setOf(Claim::note),
        )

        // Not the same list read backwards, which the order the rows are sent in would give.
        assertEquals(listOf(found, absent, unchanged, absent, unchanged), results)
    }

    // ----- Whether a node changed, and its stamp -----

    @Test
    fun `a node whose values were already as set is not changed, and keeps its stamp`() {
        stateless.save(Claim("c1", "one", "first"))
        val before = stamp("c1")

        val results = stateless.patchAll(listOf(Claim("c1", "one", "first")), only = setOf(Claim::text, Claim::note))

        assertEquals(listOf(unchanged), results)
        assertEquals(before, stamp("c1"))
    }

    @Test
    fun `a node a value differed on has a new first token, and its relationship token is as it was`() {
        stateless.save(Claim("c1", "one", "first"))
        val before = stamp("c1")

        val results = stateless.patchAll(listOf(Claim("c1", "one", "second")), only = setOf(Claim::text, Claim::note))

        assertEquals(listOf(found), results)
        val after = stamp("c1")
        assertNotEquals(Stamps.nodeToken(before), Stamps.nodeToken(after))
        assertEquals(Stamps.linksToken(before), Stamps.linksToken(after))
    }

    @Test
    fun `a null under IGNORE leaves the stored value, and is no change`() {
        stateless.save(Claim("c1", "one", "first"))
        val before = stamp("c1")

        val results = stateless.patchAll(listOf(Claim("c1", "one", note = null)), only = setOf(Claim::note))

        assertEquals(listOf(unchanged), results)
        assertEquals("first", claim("c1").note)
        assertEquals(before, stamp("c1"))
    }

    @Test
    fun `a null under CLEAR removes the property, and is a change only where it held a value`() {
        stateless.saveAll(listOf(Claim("c1", "one", "first"), Claim("c2", "two")))

        val results = stateless.patchAll(
            listOf(Claim("c1", "unwritten"), Claim("c2", "unwritten")),
            nullPolicy = NullPolicy.CLEAR,
            only = setOf(Claim::note),
        )

        assertEquals(listOf(found, unchanged), results)
        assertEquals("one" to null, claim("c1").let { it.text to it.note })
    }

    @Test
    fun `an object loaded before a patch that changed its node is then stale`() {
        stateless.save(Claim("c1", "one"))
        val loaded = claim("c1")

        stateless.patchAll(listOf(loaded.copy(note = "patched")), only = setOf(Claim::note))

        assertFailsWith<StaleObjectException> { stateless.save(loaded.copy(text = "two")) }
    }

    @Test
    fun `a node that never had a stamp is given one, and is changed only if a value differed`() {
        run("CREATE (:Claim {id: 'bare', text: 'as it is'})")
        run("CREATE (:Claim {id: 'bare2', text: 'as it is'})")

        val results = stateless.patchAll(listOf(Claim("bare", "as it is"), Claim("bare2", "other")), only = setOf(Claim::text))

        assertEquals(listOf(unchanged, found), results)
        assertTrue(Stamps.isTwoTokens(stamp("bare")))
        assertTrue(Stamps.isTwoTokens(stamp("bare2")))
    }

    @Test
    fun `a class with no stamp field is patched as one with a stamp is`() {
        stateless.save(Memo("m1", "one"))

        assertEquals(listOf(found, absent), stateless.patchAll(listOf(Memo("m1", "two"), Memo("m2", "none")), only = setOf(Memo::text)))
        assertEquals("two", assertNotNull(stateless.load("m1", Memo::class.java)).text)
    }

    // ----- A vector -----

    @Test
    fun `a vector is written so that a search finds it, and written again is no change`() {
        ensureVectorIndex()
        stateless.save(DocNode("d1", "Alpha"))
        val patch = listOf(DocNode("d1", "unwritten", listOf(1.0f, 0.0f, 0.0f, 0.0f)))

        assertEquals(listOf(found), stateless.patchAll(patch, only = setOf(DocNode::embedding)))

        assertEquals(listOf("d1"), stateless.loadNearest(DocNode::class.java, listOf(1.0f, 0.0f, 0.0f, 0.0f), 5).map { it.value.id })
        assertEquals("Alpha", assertNotNull(stateless.load("d1", DocNode::class.java)).title)
        assertEquals(listOf(unchanged), stateless.patchAll(patch, only = setOf(DocNode::embedding)))
        // A null vector is left alone under IGNORE, and never wrapped.
        assertEquals(listOf(unchanged), stateless.patchAll(listOf(DocNode("d1", "unwritten")), only = setOf(DocNode::embedding)))
        assertEquals(4, assertNotNull(stateless.load("d1", DocNode::class.java)).embedding?.size)
    }

    @Test
    fun `a null vector under CLEAR removes the vector, and is a change only where there was one`() {
        stateless.save(DocNode("d2", "Beta", listOf(0.0f, 1.0f, 0.0f, 0.0f)))
        val cleared = listOf(DocNode("d2", "unwritten"))

        assertEquals(listOf(found), stateless.patchAll(cleared, nullPolicy = NullPolicy.CLEAR, only = setOf(DocNode::embedding)))

        assertEquals("Beta" to null, assertNotNull(stateless.load("d2", DocNode::class.java)).let { it.title to it.embedding })
        assertEquals(listOf(unchanged), stateless.patchAll(cleared, nullPolicy = NullPolicy.CLEAR, only = setOf(DocNode::embedding)))
    }

    // ----- How many statements -----

    @Test
    fun `the rows go a chunk to a statement, in one batch`() {
        val counting = CountingBatches(pm)
        val patches = manager(counting)
        stateless.saveAll((1..2500).map { Claim("c$it", "text") })

        patches.patchAll((1..200).map { Claim("c$it", "unwritten", "few") }, only = setOf(Claim::note))
        assertEquals(listOf(1), counting.batches)

        counting.batches.clear()
        val results = patches.patchAll((1..2500).map { Claim("c$it", "unwritten", "many") }, only = setOf(Claim::note))
        assertEquals(listOf((2500 + chunk - 1) / chunk), counting.batches)
        assertEquals(2500, results.count { it.changed })
    }

    @Test
    fun `the nodes are written in the order of their ids, and by one text however the fields were named`() {
        val recording = RecordingBatches(pm)
        val patches = manager(recording)
        stateless.saveAll(listOf("a", "b", "c", "d").map { Claim(it, "text") })
        val given = listOf("c", "a", "d", "b")

        patches.patchAll(given.map { Claim(it, "one", "first") }, only = linkedSetOf(Claim::text, Claim::note))
        patches.patchAll(given.map { Claim(it, "two", "second") }, only = linkedSetOf(Claim::note, Claim::text))

        // A statement returns its rows in the order it wrote them, each under the index its item was given at.
        assertEquals(List(2) { listOf("a", "b", "c", "d") }, recording.returned.map { rows -> rows.map { given[it.substringBefore('/').toInt()] } })
        assertEquals(1, recording.texts.distinct().size)
    }

    @Test
    fun `nothing in sends no statement`() {
        val counting = CountingBatches(pm)

        assertEquals(emptyList(), manager(counting).patchAll(emptyList<Claim>(), only = setOf(Claim::note)))

        assertEquals(emptyList(), counting.batches)
    }

    // ----- What can be named -----

    @Test
    fun `the id, the stamp, a bag and the labels field cannot be named`() {
        val claims = listOf(Claim("c1", "one"))
        assertContains(assertFailsWith<IllegalArgumentException> { stateless.patchAll(claims, only = setOf(Claim::id)) }.message.orEmpty(), "the id")
        assertContains(assertFailsWith<IllegalArgumentException> { stateless.patchAll(claims, only = setOf(Claim::stamp)) }.message.orEmpty(), "the stamp")
        assertContains(
            assertFailsWith<IllegalArgumentException> { stateless.patchAll(listOf(BaggedNode("b1", "t")), only = setOf(BaggedNode::metadata)) }.message.orEmpty(),
            "@PropertyBag",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { stateless.patchAll(listOf(ThingNode("t1", "n")), only = setOf(ThingNode::labels)) }.message.orEmpty(),
            "@NodeLabels",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { stateless.patchAllFields(claims, only = setOf("nothing")) }.message.orEmpty(),
            "no field 'nothing'",
        )
        // With no object, the properties say the class.
        assertContains(assertFailsWith<IllegalArgumentException> { stateless.patchAll(emptyList<Claim>(), only = setOf(Claim::id)) }.message.orEmpty(), "the id")
        assertFailsWith<IllegalArgumentException> { stateless.patchAll(claims, only = setOf(Memo::text)) }
    }

    @Test
    fun `except on a class that has a bag writes its other fields and leaves the bag`() {
        stateless.save(BaggedNode("b1", "one", mapOf("kept" to "yes")))

        val results = stateless.patchAll(listOf(BaggedNode("b1", "two", mapOf("kept" to "no", "added" to "no"))), except = setOf(BaggedNode::id))

        assertEquals(listOf(found), results)
        val stored = assertNotNull(stateless.load("b1", BaggedNode::class.java))
        assertEquals("two" to mapOf<String, Any?>("kept" to "yes"), stored.title to stored.metadata)
    }

    // ----- Refusals, before anything is sent -----

    @Test
    fun `a call that could not be applied whole is refused before anything is sent`() {
        val counting = CountingBatches(pm)
        val patches = manager(counting)
        val only = setOf(Claim::note)

        assertContains(assertFailsWith<IllegalArgumentException> { patches.patchAll(listOf(Claim("c1", "one"))) }.message.orEmpty(), "give only or except")
        assertFailsWith<IllegalArgumentException> { patches.patchAll(listOf(Claim("c1", "one")), only = only, except = setOf(Claim::text)) }
        assertContains(
            assertFailsWith<IllegalArgumentException> { patches.patchAll(listOf(Claim("c1", "one"), Claim("c2", "two"), Claim("c1", "again")), only = only) }.message.orEmpty(),
            "'c1' is given 2 times, at 0, 2",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { patches.patchAll(listOf(LooseNote("l1", "one"), LooseNote(null, "two")), only = setOf(LooseNote::text)) }.message.orEmpty(),
            "at 1 has a null id",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { patches.patchAllFields(listOf(Claim("c1", "one"), Memo("m1", "two")), only = setOf("text")) }.message.orEmpty(),
            "one class",
        )
        assertFailsWith<IllegalArgumentException> { patches.patchAll(listOf(Claim("c1", "one")), except = setOf(Claim::text, Claim::note)) }

        assertEquals(emptyList(), counting.batches)
    }

    // ----- Atomicity and contention -----

    @Test
    fun `a statement that fails leaves nothing of the ones before it, on an engine with transactions`() {
        val count = 2 * chunk + 1
        stateless.saveAll((1..count).map { Claim("c$it", "text") })

        assertFailsWith<Exception> {
            manager(FailingLastStatement(pm)).patchAll((1..count).map { Claim("c$it", "unwritten", "patched") }, only = setOf(Claim::note))
        }

        val patched = stateless.loadAll(Claim::class.java).count { it.note == "patched" }
        if (atomicBatch) assertEquals(0, patched) else assertEquals(2 * chunk, patched)
    }

    @Test
    fun `two calls over the same nodes, given in opposite orders, both finish`() {
        val ids = (1..300).map { "c$it" }
        stateless.saveAll(ids.map { Claim(it, "text") })
        val failures = mutableListOf<Throwable>()

        val writers = listOf(ids to "up", ids.reversed() to "down").map { (order, note) ->
            thread {
                try {
                    repeat(5) { round -> manager().patchAll(order.map { Claim(it, "unwritten", "$note $round") }, only = setOf(Claim::note)) }
                } catch (failure: Throwable) {
                    synchronized(failures) { failures += failure }
                }
            }
        }
        writers.forEach { it.join(120_000) }

        assertEquals(emptyList(), failures)
        assertTrue(writers.none { it.isAlive })
        assertEquals(300, stateless.loadAll(Claim::class.java).count { it.note in setOf("up 4", "down 4") })
    }
}

/** Decorates a [PersistenceManager], recording how many statements each [queryBatch] carried. */
private class CountingBatches(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    val batches = mutableListOf<Int>()

    override fun queryBatch(specs: List<QuerySpecification<*>>): List<List<Any?>> {
        batches += specs.size
        return delegate.queryBatch(specs)
    }
}

/** Decorates a [PersistenceManager], keeping the text of each statement of a batch and the rows it returned. */
private class RecordingBatches(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    val texts = mutableListOf<String>()
    val returned = mutableListOf<List<String>>()

    override fun queryBatch(specs: List<QuerySpecification<*>>): List<List<Any?>> {
        texts += specs.map { it.statement!!.text }
        return delegate.queryBatch(specs).onEach { rows -> returned += rows.map { it as String } }
    }
}

/** Decorates a [PersistenceManager], putting a statement the store refuses in place of the last of each batch. */
private class FailingLastStatement(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    override fun queryBatch(specs: List<QuerySpecification<*>>): List<List<Any?>> =
        delegate.queryBatch(specs.dropLast(1) + QuerySpecification.withStatement("THIS IS NO STATEMENT"))
}

@Testcontainers
class StatelessPatchAllNeo4jTest : StatelessPatchAllContract() {
    companion object {
        private const val PASSWORD = "patchall1"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-patch-all", type = DatabaseType.NEO4J,
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
}

@Testcontainers
class StatelessPatchAllFalkorDbTest : StatelessPatchAllContract() {
    companion object {
        private const val GRAPH = "patchall"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-patch-all", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
    override val atomicBatch: Boolean = false

    @Test
    fun `in STRICT mode the call is refused, as a saveAll is`() {
        val registry = SubtypeRegistry()
        val strict = FalkorDbConnectionProvider(
            name = "falkor-patch-all-strict", host = container.host, port = container.getMappedPort(6379),
            password = null, graphName = GRAPH, transactionMode = FalkorDbTransactionMode.STRICT, subtypeRegistry = registry,
        )
        try {
            val patches = StatelessGraphObjectManager(
                NonTransactionalPersistenceManager(strict, GRAPH, DatabaseType.FALKORDB, registry), Neo4jObjectMapper.instance, registry,
            )
            assertFailsWith<UnsupportedOperationException> { patches.patchAll(listOf(Claim("c1", "one", "patched")), only = setOf(Claim::note)) }
        } finally {
            strict.end()
        }
    }
}

@Testcontainers
class StatelessPatchAllMemgraphTest : StatelessPatchAllContract() {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-patch-all", type = DatabaseType.MEMGRAPH,
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
}

@OracleTests
class StatelessPatchAllOracleTest : StatelessPatchAllContract() {
    override val pm: PersistenceManager = OracleTestDatabase.fixtures(OracleTestDatabase.graphManager("contracts"))
    override val chunk: Int = 500

    /** A search with no index measures every node. */
    override fun ensureVectorIndex() = Unit
}
