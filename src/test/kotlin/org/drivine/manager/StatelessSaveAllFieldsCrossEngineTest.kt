package org.drivine.manager

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.drivine.StaleObjectException
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
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
import sample.propertybag.BaggedNode
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Human
import sample.stateless.Memo
import sample.stateless.Tagged
import sample.vector.DocNode

/**
 * `saveAll` with `only` and `except`: the named fields narrow what each root writes, and everything
 * else a batch promises still holds; verified on Neo4j, FalkorDB and Memgraph, and on Oracle.
 */
abstract class StatelessSaveAllFieldsContract {

    abstract val pm: PersistenceManager

    /** Whether a fragment with a `@VectorIndex` field goes as a row of a batch: on FalkorDB each is a statement of its own. */
    open val vectorsTogether: Boolean = true

    private fun manager(over: PersistenceManager = pm) = StatelessGraphObjectManager(over, Neo4jObjectMapper.instance, SubtypeRegistry())

    private val stateless: StatelessGraphObjectManager
        get() = manager()

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    /** The stamp the node with [id] carries. */
    private fun stamp(id: String): String =
        pm.query(QuerySpecification.withStatement("MATCH (n {id: '$id'}) RETURN n.`${Stamps.PROPERTY}`").transform(String::class.java)).single()

    /** What the node with [id] holds under [key], as text: empty when it holds nothing. */
    private fun stored(id: String, key: String): String = pm.query(
        QuerySpecification.withStatement("MATCH (n {id: \$id}) RETURN coalesce(toString(n[\$key]), '')")
            .bind(mapOf("id" to id, "key" to key)).transform(String::class.java),
    ).single()

    /** The ids of the people the claim with [id] mentions. */
    private fun mentioned(id: String): List<String> = pm.query(
        QuerySpecification.withStatement("MATCH ({id: \$from})-[:MENTIONS]->(t:Human) RETURN t.id").bind(mapOf("from" to id)).transform(String::class.java),
    ).sorted()

    /** The labels the node with [id] carries. */
    private fun labels(id: String): Set<String> = pm.query(
        QuerySpecification.withStatement("MATCH (n {id: \$id}) UNWIND labels(n) AS l RETURN l").bind(mapOf("id" to id)).transform(String::class.java),
    ).toSet()

    private fun claim(id: String): Claim = assertNotNull(stateless.load(id, Claim::class.java))

    @BeforeEach
    fun clean() = run("MATCH (n) DETACH DELETE n")

    // ----- What is written -----

    @Test
    fun `only the named fields are written, though the objects hold other values for the rest`() {
        stateless.saveAll(listOf(Claim("c1", "one", "first"), Claim("c2", "two", "second")))

        stateless.saveAll(listOf(Claim("c1", "not written", "patched"), Claim("c2", "nor this", "patched too")), only = setOf(Claim::note))

        assertEquals(listOf("one" to "patched", "two" to "patched too"), listOf(claim("c1"), claim("c2")).map { it.text to it.note })
    }

    @Test
    fun `except writes every field but those named, of each class in the batch`() {
        stateless.saveAll(listOf(Claim("c1", "one", "first"), Memo("m1", "memo")))

        stateless.saveAll(listOf(Claim("c1", "not written", "second")), except = setOf(Claim::text))
        assertEquals("one" to "second", claim("c1").let { it.text to it.note })

        stateless.saveAll(listOf(Claim("c1", "nor this", "third"), Memo("m1", "nor this")), except = setOf(Claim::text))
        assertEquals("one" to "third", claim("c1").let { it.text to it.note })
        assertEquals("memo", assertNotNull(stateless.load("m1", Memo::class.java)).text)
    }

    @Test
    fun `except leaves each class its own other fields, whichever class comes first`() {
        stateless.saveAll(listOf(Human("h1", "Ada"), ChunkNode("k1", "text", sequenceNumber = 1)))

        stateless.saveAll(listOf(Human("h1", "Grace"), ChunkNode("k1", "second", sequenceNumber = 2)), except = setOf(Human::id))
        assertEquals("Grace", assertNotNull(stateless.load("h1", Human::class.java)).name)
        assertEquals("second" to 2L, assertNotNull(stateless.load("k1", ChunkNode::class.java)).let { it.text to it.sequenceNumber })

        stateless.saveAll(listOf(ChunkNode("k1", "third", sequenceNumber = 3), Human("h1", "Hedy")), except = setOf(Human::id))
        assertEquals("Hedy", assertNotNull(stateless.load("h1", Human::class.java)).name)
        assertEquals("third" to 3L, assertNotNull(stateless.load("k1", ChunkNode::class.java)).let { it.text to it.sequenceNumber })
    }

    @Test
    fun `a field renamed with GraphProperty is written when named by its field name`() {
        stateless.saveAll(listOf(ChunkNode("k1", "text", containerSectionId = "s1", sequenceNumber = 1)))

        stateless.saveAll(listOf(ChunkNode("k1", "other", containerSectionId = "s2", sequenceNumber = 2)), only = setOf(ChunkNode::sequenceNumber))

        val chunk = assertNotNull(stateless.load("k1", ChunkNode::class.java))
        assertEquals(Triple("text", "s1", 2L), Triple(chunk.text, chunk.containerSectionId, chunk.sequenceNumber))
    }

    @Test
    fun `under CLEAR a named field that is null is cleared, and a null field outside the set is left`() {
        stateless.saveAll(listOf(Claim("c1", "one", "first")))
        stateless.saveAll(listOf(ChunkNode("k1", "text", containerSectionId = "s1", sequenceNumber = 1)))

        stateless.saveAll(listOf(Claim("c1", "not written", note = null)), nullPolicy = NullPolicy.CLEAR, only = setOf(Claim::note))
        stateless.saveAll(listOf(ChunkNode("k1", "not written")), nullPolicy = NullPolicy.CLEAR, only = setOf(ChunkNode::sequenceNumber))

        assertEquals("one" to null, claim("c1").let { it.text to it.note })
        val chunk = assertNotNull(stateless.load("k1", ChunkNode::class.java))
        assertEquals(Triple("text", "s1", null), Triple(chunk.text, chunk.containerSectionId, chunk.sequenceNumber))
    }

    // ----- Stamps -----

    @Test
    fun `an object whose named fields are as stored keeps its stamp, and one that differs is handed a new one`() {
        val saved = stateless.saveAll(listOf(Claim("c1", "one", "first"), Claim("c2", "two", "second")))

        val same = stateless.saveAll(saved.map { it.copy(text = "not written") }, only = setOf(Claim::note))
        assertEquals(saved.map { it.stamp }, same.map { it.stamp })
        assertEquals(saved.map { it.stamp }, listOf(stamp("c1"), stamp("c2")))

        val changed = stateless.saveAll(same.map { it.copy(note = "changed") }, only = setOf(Claim::note))
        changed.zip(saved).forEach { (now, before) ->
            assertNotEquals(Stamps.nodeToken(assertNotNull(before.stamp)), Stamps.nodeToken(assertNotNull(now.stamp)))
            assertEquals(stamp(now.id), now.stamp)
        }
        // What was handed back is what the store holds, so each can be saved again, checked.
        changed.forEach { stateless.save(it.copy(note = "again")) }
    }

    // ----- How many statements -----

    @Test
    fun `two hundred fragments with a field named are one statement`() {
        val counting = CountingSaveBatches(pm)
        val batches = manager(counting)
        stateless.saveAll((1..200).map { Claim("c$it", "text $it") })

        batches.saveAll((1..200).map { Claim("c$it", "not written", "noted") }, only = setOf(Claim::note))

        assertEquals(listOf(1), counting.batches)
        assertEquals("text 200" to "noted", claim("c200").let { it.text to it.note })
    }

    @Test
    fun `a fragment with a vector is saved with others where the engine takes a vector from a row`() {
        val counting = CountingSaveBatches(pm)
        stateless.saveAll((1..5).map { DocNode("d$it", "title $it") })

        manager(counting).saveAll((1..5).map { DocNode("d$it", "not written", listOf(0.1f, 0.2f, 0.3f, 0.4f)) }, only = setOf(DocNode::embedding))

        assertEquals(listOf(if (vectorsTogether) 1 else 5), counting.batches)
        val doc = assertNotNull(stateless.load("d3", DocNode::class.java))
        assertEquals("title 3" to 4, doc.title to doc.embedding?.size)
    }

    // ----- Views -----

    @Test
    fun `a view writes the named fields of its root, and still adds the relationships it holds`() {
        stateless.save(ClaimView(Claim("c1", "one", "first"), people = listOf(Human("h1", "Ada"))))
        stateless.save(ClaimView(Claim("c2", "two", "second"), people = listOf(Human("h1", "Ada"))))

        stateless.saveAll(
            listOf(
                ClaimView(Claim("c1", "not written", "patched"), people = listOf(Human("h2", "Bob"))),
                ClaimView(Claim("c2", "nor this", "patched too"), people = listOf(Human("h3", "Cy"))),
            ),
            only = setOf(Claim::note),
        )

        assertEquals(listOf("one" to "patched", "two" to "patched too"), listOf(claim("c1"), claim("c2")).map { it.text to it.note })
        assertEquals(listOf(listOf("h1", "h2"), listOf("h1", "h3")), listOf(mentioned("c1"), mentioned("c2")))
    }

    @Test
    fun `a view whose list is replaced is checked and replaced, and writes the named fields of its root`() {
        val loaded = stateless.save(ClaimView(Claim("c1", "one", "first"), people = listOf(Human("h1", "Ada"), Human("h2", "Bob"))))
        val changed = loaded.copy(claim = loaded.claim.copy(text = "not written", note = "patched"), people = listOf(Human("h1", "Ada")))

        stateless.saveAll(listOf(changed), Replace(ClaimView::people), only = setOf(Claim::note))

        assertEquals("one" to "patched", claim("c1").let { it.text to it.note })
        assertEquals(listOf("h1"), mentioned("c1"))
        // The stamp the view was loaded with is behind the store's now, and a replace compares it.
        assertFailsWith<StaleObjectException> { stateless.saveAll(listOf(changed), Replace(ClaimView::people), only = setOf(Claim::note)) }
    }

    @Test
    fun `a view and a fragment of its root's class go in one batch`() {
        stateless.saveAll(listOf(Claim("c1", "one", "first"), Claim("c2", "two", "second")))

        val saved = stateless.saveAll(
            listOf(ClaimView(Claim("c1", "not written", "of the view"), people = listOf(Human("h1", "Ada"))), Claim("c2", "nor this", "of the fragment")),
            only = setOf(Claim::note),
        )

        assertEquals(listOf("one" to "of the view", "two" to "of the fragment"), listOf(claim("c1"), claim("c2")).map { it.text to it.note })
        assertEquals(listOf("h1"), mentioned("c1"))
        // Neither object carried a stamp, so each is handed the token for its node's own data.
        assertEquals(
            listOf(stamp("c1"), stamp("c2")).map(Stamps::nodeToken),
            listOf((saved[0] as ClaimView).claim.stamp, (saved[1] as Claim).stamp).map { Stamps.nodeToken(assertNotNull(it)) },
        )
    }

    // ----- What can be named -----

    @Test
    fun `a name one class of the batch lacks is refused before anything is written`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            stateless.saveAll(listOf(Claim("c1", "one", "first"), Human("h1", "Ada")), only = setOf(Claim::note))
        }

        assertContains(refused.message.orEmpty(), "Human has no field 'note'")
        assertNull(stateless.load("c1", Claim::class.java))
    }

    @Test
    fun `two unrelated classes that share a name are both written, named by a property of either or as a string`() {
        stateless.saveAll(listOf(Claim("c1", "one", "first"), Memo("m1", "memo")))

        stateless.saveAll(listOf(Claim("c1", "two", "not written"), Memo("m1", "memo two")), only = setOf(Claim::text))
        assertEquals("two" to "first", claim("c1").let { it.text to it.note })
        assertEquals("memo two", assertNotNull(stateless.load("m1", Memo::class.java)).text)

        stateless.saveAll(listOf(Claim("c1", "of the memo", "not written"), Memo("m1", "of the memo")), only = setOf(Memo::text))
        assertEquals("of the memo" to "first", claim("c1").let { it.text to it.note })
        assertEquals("of the memo", assertNotNull(stateless.load("m1", Memo::class.java)).text)

        stateless.saveAllFields(listOf(Claim("c1", "three", "not written"), Memo("m1", "memo three")), only = setOf("text"))
        assertEquals("three" to "first", claim("c1").let { it.text to it.note })
        assertEquals("memo three", assertNotNull(stateless.load("m1", Memo::class.java)).text)
    }

    @Test
    fun `the id and the stamp are refused in only, and mean nothing in except`() {
        val claims = listOf(Claim("c1", "one", "first"))

        assertContains(assertFailsWith<IllegalArgumentException> { stateless.saveAll(claims, only = setOf(Claim::id)) }.message.orEmpty(), "the id")
        assertContains(assertFailsWith<IllegalArgumentException> { stateless.saveAll(claims, only = setOf(Claim::note, Claim::stamp)) }.message.orEmpty(), "the stamp")
        // Of a view, it is the root's id that is refused.
        assertContains(
            assertFailsWith<IllegalArgumentException> { stateless.saveAll(listOf(ClaimView(Claim("c1", "one"))), only = setOf(Claim::id)) }.message.orEmpty(),
            "the id",
        )
        assertNull(stateless.load("c1", Claim::class.java))

        stateless.saveAll(claims, except = setOf(Claim::id, Claim::stamp))
        assertEquals("one" to "first", claim("c1").let { it.text to it.note })
    }

    @Test
    fun `only and except together are refused, for a batch that is empty too`() {
        assertFailsWith<IllegalArgumentException> {
            stateless.saveAll(listOf(Claim("c1", "one")), only = setOf(Claim::text), except = setOf(Claim::note))
        }
        assertFailsWith<IllegalArgumentException> {
            stateless.saveAll(emptyList<Claim>(), only = setOf(Claim::text), except = setOf(Claim::note))
        }
        assertEquals(emptyList(), stateless.saveAll(emptyList<Claim>(), only = setOf(Claim::text)))
        assertNull(stateless.load("c1", Claim::class.java))
    }

    // ----- A node that is gone: pinned, so a change to either is seen -----

    @Test
    fun `a node that is gone is made, holding its id and the named fields alone`() {
        val loaded = stateless.save(Claim("c1", "one", "first"))
        stateless.delete("c1", Claim::class.java)

        stateless.saveAll(listOf(loaded.copy(note = "written")), only = setOf(Claim::note))

        assertEquals("written", stored("c1", "note"))
        assertEquals("", stored("c1", "text"))
        // It has its labels and a stamp, as any node a save makes.
        assertEquals(setOf("Claim"), labels("c1"))
        assertNotEquals("", stored("c1", Stamps.PROPERTY))
    }

    @Test
    fun `a view whose root is gone gets its root back so, and its relationships`() {
        val loaded = stateless.save(Claim("c1", "one", "first"))
        stateless.delete("c1", Claim::class.java)

        stateless.saveAll(listOf(ClaimView(loaded.copy(note = "written"), people = listOf(Human("h1", "Ada")))), only = setOf(Claim::note))

        assertEquals("written", stored("c1", "note"))
        assertEquals("", stored("c1", "text"))
        assertEquals(listOf("h1"), mentioned("c1"))
        assertEquals("Ada", assertNotNull(stateless.load("h1", Human::class.java)).name)
        assertEquals(setOf("Claim"), labels("c1"))
        assertNotEquals("", stored("c1", Stamps.PROPERTY))
    }

    // ----- Bags and labels: on the graph engines, fragments saved a statement each -----

    @Test
    fun `a class with a bag, with a stamp and without, writes the named fields alone, and its bag only when named`() {
        stateless.saveAll(listOf(BaggedNode("b1", "one", mapOf("kept" to "yes")), Tagged("t1", "one", mapOf("kept" to "yes"))))
        val other = mapOf("kept" to "no", "added" to "no")

        stateless.saveAll(listOf(BaggedNode("b1", "two", other)), only = setOf(BaggedNode::title))
        val tagged = stateless.saveAll(listOf(Tagged("t1", "two", other)), only = setOf(Tagged::text)).single()

        val bagged = assertNotNull(stateless.load("b1", BaggedNode::class.java))
        assertEquals("two" to mapOf<String, Any?>("kept" to "yes"), bagged.title to bagged.metadata)
        val stamped = assertNotNull(stateless.load("t1", Tagged::class.java))
        assertEquals("two" to "yes", stamped.text to stamped.meta["kept"])
        assertFalse("added" in stamped.meta)
        assertEquals(Stamps.nodeToken(assertNotNull(stamped.stamp)), Stamps.nodeToken(assertNotNull(tagged.stamp)))

        // Under CLEAR a bag that is not named keeps the keys the object does not hold.
        stateless.saveAll(listOf(BaggedNode("b1", "two")), nullPolicy = NullPolicy.CLEAR, only = setOf(BaggedNode::title))
        stateless.saveAll(listOf(Tagged("t1", "two")), nullPolicy = NullPolicy.CLEAR, only = setOf(Tagged::text))
        assertEquals(mapOf<String, Any?>("kept" to "yes"), assertNotNull(stateless.load("b1", BaggedNode::class.java)).metadata)
        assertEquals("yes", assertNotNull(stateless.load("t1", Tagged::class.java)).meta["kept"])

        // Under CLEAR a bag that is named drops the keys the object does not hold.
        val fewer = mapOf("added" to "yes")
        stateless.saveAll(listOf(BaggedNode("b1", "not written", fewer)), nullPolicy = NullPolicy.CLEAR, only = setOf(BaggedNode::metadata))
        stateless.saveAll(listOf(Tagged("t1", "not written", fewer)), nullPolicy = NullPolicy.CLEAR, only = setOf(Tagged::meta))
        val cleared = assertNotNull(stateless.load("b1", BaggedNode::class.java))
        assertEquals("two" to fewer, cleared.title to cleared.metadata)
        val clearedStamped = assertNotNull(stateless.load("t1", Tagged::class.java))
        assertEquals(Triple("two", null, "yes"), Triple(clearedStamped.text, clearedStamped.meta["kept"], clearedStamped.meta["added"]))

        // A bag can be named, and is then written as a save writes it.
        stateless.saveAll(listOf(BaggedNode("b1", "not written", other)), only = setOf(BaggedNode::metadata))
        stateless.saveAll(listOf(Tagged("t1", "not written", other)), only = setOf(Tagged::meta))

        val rebagged = assertNotNull(stateless.load("b1", BaggedNode::class.java))
        assertEquals("two" to other, rebagged.title to rebagged.metadata)
        val restamped = assertNotNull(stateless.load("t1", Tagged::class.java))
        assertEquals(Triple("two", "no", "no"), Triple(restamped.text, restamped.meta["kept"], restamped.meta["added"]))
    }

    @Test
    fun `a labels field that is not named is left, and one that is named is written as a save writes it`() {
        stateless.saveAll(listOf(ThingNode("t1", "Lyre", setOf("Instrument"))))

        stateless.saveAll(listOf(ThingNode("t1", "Harp", setOf("Antique"))), only = setOf(ThingNode::name))
        assertEquals(setOf("Thing", "Instrument"), labels("t1"))
        assertEquals("Harp", stored("t1", "name"))

        // Under CLEAR a labels field that is not named keeps the labels the object does not hold.
        stateless.saveAll(listOf(ThingNode("t1", "Harp")), nullPolicy = NullPolicy.CLEAR, only = setOf(ThingNode::name))
        assertEquals(setOf("Thing", "Instrument"), labels("t1"))

        stateless.saveAll(listOf(ThingNode("t1", "not written", setOf("Antique"))), only = setOf(ThingNode::labels))
        assertEquals(setOf("Thing", "Instrument", "Antique"), labels("t1"))
        assertEquals("Harp", stored("t1", "name"))

        // Named under CLEAR, it drops the labels it gave the node that the object does not hold.
        stateless.saveAll(listOf(ThingNode("t1", "not written", setOf("Antique"))), nullPolicy = NullPolicy.CLEAR, only = setOf(ThingNode::labels))
        assertEquals(setOf("Thing", "Antique"), labels("t1"))
        assertEquals("Harp", stored("t1", "name"))
    }
}

/** Decorates a [PersistenceManager], counting the statements of each batch it is given. */
private class CountingSaveBatches(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    val batches = mutableListOf<Int>()

    override fun queryBatch(specs: List<QuerySpecification<*>>): List<List<Any?>> {
        batches += specs.size
        return delegate.queryBatch(specs)
    }
}

@Testcontainers
class StatelessSaveAllFieldsNeo4jTest : StatelessSaveAllFieldsContract() {
    companion object {
        private const val PASSWORD = "saveallfields1"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-save-all-fields", type = DatabaseType.NEO4J,
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
class StatelessSaveAllFieldsFalkorDbTest : StatelessSaveAllFieldsContract() {
    companion object {
        private const val GRAPH = "saveallfields"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-save-all-fields", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
    override val vectorsTogether: Boolean = false
}

@Testcontainers
class StatelessSaveAllFieldsMemgraphTest : StatelessSaveAllFieldsContract() {
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
                name = "memgraph-save-all-fields", type = DatabaseType.MEMGRAPH,
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
class StatelessSaveAllFieldsOracleTest : StatelessSaveAllFieldsContract() {
    override val pm: PersistenceManager = OracleTestDatabase.fixtures(OracleTestDatabase.graphManager("contracts"))
}
