package org.drivine.manager

import java.time.Instant
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
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.PatchSpec
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.StringPropertyReference
import org.drivine.query.dsl.any
import org.drivine.query.dsl.none
import org.drivine.query.dsl.not
import org.drivine.query.dsl.anyOf
import org.drivine.query.dsl.query
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
import sample.patch.BaggedNodeQueryDsl
import sample.patch.ClaimQueryDsl
import sample.patch.ClaimViewQueryDsl
import sample.patch.DocNodeQueryDsl
import sample.patch.Pace
import sample.patch.PatchSession
import sample.patch.PatchSessionQueryDsl
import sample.graphproperty.ChunkNode
import sample.propertybag.BaggedNode
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Human
import sample.stateless.Memo
import sample.vector.DocNode

/**
 * `patch`: named fields of one node by its id, written if the node meets a predicate, in one
 * statement; verified on Neo4j, FalkorDB and Memgraph, and on Oracle.
 */
abstract class StatelessPatchContract {

    abstract val pm: PersistenceManager

    /** Whether the engine searches vectors: Neo4j 4 has no vector index. */
    open val searchesVectors: Boolean = true

    /** Makes the index a vector search of [DocNode] reads, where the engine needs one to search. */
    open fun ensureVectorIndex() {
        pm.indexes.ensure(VectorIndexSpec("Doc", "embedding", 4))
    }

    private fun manager(over: PersistenceManager = pm) = StatelessGraphObjectManager(over, Neo4jObjectMapper.instance, SubtypeRegistry())

    private val stateless: StatelessGraphObjectManager
        get() = manager()

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    /** The stamp the node with [id] carries; null when it has none. */
    private fun stamp(id: String): String? =
        pm.query(QuerySpecification.withStatement("MATCH (n {id: '$id'}) RETURN n.`${Stamps.PROPERTY}`").transform(String::class.java)).singleOrNull()

    private fun claim(id: String): Claim = assertNotNull(stateless.load(id, Claim::class.java))

    private fun session(id: String): PatchSession = assertNotNull(stateless.load(id, PatchSession::class.java))

    private fun patchClaim(id: Any, spec: PatchSpec<ClaimQueryDsl>.() -> Unit): PatchResult =
        stateless.patch(id, Claim::class.java, ClaimQueryDsl.INSTANCE, spec)

    private fun patchView(id: String, spec: PatchSpec<ClaimViewQueryDsl>.() -> Unit): PatchResult =
        stateless.patch(id, ClaimView::class.java, ClaimViewQueryDsl.INSTANCE, spec)

    private fun patchSession(id: String, over: StatelessGraphObjectManager = stateless, spec: PatchSpec<PatchSessionQueryDsl>.() -> Unit): PatchResult =
        over.patch(id, PatchSession::class.java, PatchSessionQueryDsl.INSTANCE, spec)

    /** Moves the session's activity to [at], unless it is already later: the write `patch` is for. */
    private fun touch(id: String, at: Instant, over: StatelessGraphObjectManager = stateless): PatchResult = patchSession(id, over) {
        fields { query.lastActivityAt setTo at }
        where { anyOf { query.lastActivityAt.isNull(); query.lastActivityAt lt at } }
    }

    private val changed = PatchResult(found = true, applied = true, changed = true)
    private val unchanged = PatchResult(found = true, applied = true, changed = false)
    private val unmet = PatchResult(found = true, applied = false, changed = false)
    private val absent = PatchResult(found = false, applied = false, changed = false)

    private val noon: Instant = Instant.parse("2026-03-01T12:00:00Z")

    @BeforeEach
    fun clean() = run("MATCH (n) DETACH DELETE n")

    // ----- The answers -----

    @Test
    fun `a patch says whether the node was found, whether the predicate held and whether a value differed`() {
        stateless.save(Claim("c1", "one", "first"))

        assertEquals(absent, patchClaim("nobody") { fields { query.note setTo "patched" }; where { query.text eq "one" } })
        assertEquals(unmet, patchClaim("c1") { fields { query.note setTo "patched" }; where { query.text eq "two" } })
        assertEquals("first", claim("c1").note)
        assertEquals(changed, patchClaim("c1") { fields { query.note setTo "patched" }; where { query.text eq "one" } })
        assertEquals("one" to "patched", claim("c1").let { it.text to it.note })
        assertEquals(unchanged, patchClaim("c1") { fields { query.note setTo "patched" }; where { query.text eq "one" } })
    }

    @Test
    fun `a patch with no predicate is applied wherever the node is`() {
        stateless.save(Claim("c1", "one"))

        assertEquals(changed, patchClaim("c1") { fields { query.note setTo "patched"; query.text setTo "two" } })
        assertEquals("two" to "patched", claim("c1").let { it.text to it.note })
        assertEquals(absent, patchClaim("nobody") { fields { query.note setTo "patched" } })
    }

    @Test
    fun `a patch never makes a node`() {
        stateless.save(Claim("c1", "one"))

        assertEquals(absent, patchClaim("c2") { fields { query.text setTo "made" } })

        assertEquals(1, stateless.count(Claim::class.java))
        assertNull(stateless.load("c2", Claim::class.java))
    }

    @Test
    fun `a class with no stamp field is patched as one with a stamp is`() {
        stateless.save(Memo("m1", "one"))

        val result = stateless.patch("m1", Memo::class.java, ClaimQueryDsl.INSTANCE) { fields { query.text setTo "two" } }

        assertEquals(changed, result)
        assertEquals("two", assertNotNull(stateless.load("m1", Memo::class.java)).text)
    }

    // ----- The stamp -----

    @Test
    fun `a patch that changes nothing leaves the stamp as it was`() {
        stateless.save(Claim("c1", "one", "first"))
        val before = stamp("c1")

        assertEquals(unchanged, patchClaim("c1") { fields { query.note setTo "first" } })
        assertEquals(unmet, patchClaim("c1") { fields { query.note setTo "patched" }; where { query.text eq "two" } })

        assertEquals(before, stamp("c1"))
    }

    @Test
    fun `a change renews the first token only, and an object loaded before it is then stale`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("h1", "Ada"))))
        val loaded = assertNotNull(stateless.load("c1", ClaimView::class.java))
        val before = assertNotNull(stamp("c1"))

        assertEquals(changed, patchClaim("c1") { fields { query.text setTo "patched" } })

        val after = assertNotNull(stamp("c1"))
        assertNotEquals(Stamps.nodeToken(before), Stamps.nodeToken(after))
        assertEquals(Stamps.linksToken(before), Stamps.linksToken(after))
        assertFailsWith<StaleObjectException> { stateless.save(loaded.copy(claim = loaded.claim.copy(note = "late"))) }
        assertEquals("patched", claim("c1").text)
    }

    @Test
    fun `a node that never had a stamp is given one, whatever the patch did to it`() {
        listOf("bare", "bare2", "bare3").forEach { run("CREATE (:Claim {id: '$it', text: 'as it is'})") }

        assertEquals(unchanged, patchClaim("bare") { fields { query.text setTo "as it is" } })
        assertEquals(unmet, patchClaim("bare2") { fields { query.text setTo "other" }; where { query.text eq "not this" } })
        assertEquals(changed, patchClaim("bare3") { fields { query.text setTo "other" } })

        listOf("bare", "bare2", "bare3").forEach { assertTrue(Stamps.isTwoTokens(assertNotNull(stamp(it))), it) }
        assertEquals(listOf("as it is", "as it is", "other"), listOf("bare", "bare2", "bare3").map { claim(it).text })
    }

    // ----- What is written -----

    @Test
    fun `clear removes a property, and is a change only where it held a value`() {
        stateless.save(Claim("c1", "one", "first"))

        assertEquals(changed, patchClaim("c1") { fields { query.note.clear() } })
        assertNull(claim("c1").note)
        assertEquals(unchanged, patchClaim("c1") { fields { query.note.clear() } })
        assertEquals("one", claim("c1").text)
    }

    @Test
    fun `a field is stored as a save stores it`() {
        val tags = listOf("a", "b c")
        stateless.save(PatchSession("patched"))
        stateless.save(PatchSession("saved", lastActivityAt = noon, pace = Pace.BRISK, tags = tags, turns = 3))

        val result = patchSession("patched") {
            fields {
                query.lastActivityAt setTo noon
                query.pace setTo Pace.BRISK
                query.tags setTo tags
                query.turns setTo 3L
            }
        }

        assertEquals(changed, result)
        assertEquals(session("saved").copy(id = "patched", stamp = null), session("patched").copy(stamp = null))
        // Written again it is no change: each value compares equal to what the patch stored.
        assertEquals(unchanged, patchSession("patched") { fields { query.lastActivityAt setTo noon; query.pace setTo Pace.BRISK; query.tags setTo tags; query.turns setTo 3L } })
        // And to what a save stored.
        assertEquals(unchanged, patchSession("saved") { fields { query.lastActivityAt setTo noon; query.pace setTo Pace.BRISK; query.tags setTo tags; query.turns setTo 3L } })
        // A predicate compares like with like, whichever wrote the value.
        listOf("patched", "saved").forEach { id ->
            assertEquals(unmet, touch(id, noon.minusSeconds(1)), id)
            assertEquals(changed, touch(id, noon.plusSeconds(1)), id)
        }
    }

    @Test
    fun `a vector is written so that a search finds it, and written again is no change`() {
        if (searchesVectors) ensureVectorIndex()
        stateless.save(DocNode("d1", "Alpha"))
        val vector = listOf(1.0f, 0.0f, 0.0f, 0.0f)
        fun embed() = stateless.patch("d1", DocNode::class.java, DocNodeQueryDsl.INSTANCE) { fields { query.embedding setTo vector } }

        assertEquals(changed, embed())

        if (searchesVectors) assertEquals(listOf("d1"), stateless.loadNearest(DocNode::class.java, vector, 5).map { it.value.id })
        assertEquals(vector, assertNotNull(stateless.load("d1", DocNode::class.java)).embedding)
        assertEquals("Alpha", assertNotNull(stateless.load("d1", DocNode::class.java)).title)
        assertEquals(unchanged, embed())
        assertEquals(changed, stateless.patch("d1", DocNode::class.java, DocNodeQueryDsl.INSTANCE) { fields { query.embedding.clear() } })
        assertNull(assertNotNull(stateless.load("d1", DocNode::class.java)).embedding)
    }

    // ----- A predicate the store evaluates -----

    @Test
    fun `a moment moves forward and never back`() {
        stateless.save(PatchSession("s1"))

        assertEquals(changed, touch("s1", noon))
        assertEquals(unmet, touch("s1", noon.minusSeconds(60)))
        assertEquals(unmet, touch("s1", noon))
        assertEquals(changed, touch("s1", noon.plusSeconds(60)))

        assertEquals(noon.plusSeconds(60), session("s1").lastActivityAt)
    }

    @Test
    fun `many writers move one moment forward in any order, and it ends at the latest`() {
        stateless.save(PatchSession("s1"))
        val times = (1..40).map { noon.plusSeconds(it.toLong()) }.shuffled()
        val failures = mutableListOf<Throwable>()
        val results = mutableListOf<PatchResult>()

        val writers = times.chunked(10).map { mine ->
            thread {
                try {
                    val own = manager()
                    mine.forEach { at -> touch("s1", at, own).let { synchronized(results) { results += it } } }
                } catch (failure: Throwable) {
                    synchronized(failures) { failures += failure }
                }
            }
        }
        writers.forEach { it.join(120_000) }

        assertEquals(emptyList(), failures)
        assertEquals(noon.plusSeconds(40), session("s1").lastActivityAt)
        assertTrue(results.all { it.found } && results.size == times.size)
        // A patch was applied exactly when it changed the node: no two wrote the same moment.
        assertTrue(results.all { it.applied == it.changed })
    }

    @Test
    fun `a predicate on a relationship is met by a root that has such a node, and by no other`() {
        stateless.save(ClaimView(Claim("mine", "one"), people = listOf(Human("h1", "Ada"), Human("h2", "Bob"))))
        stateless.save(ClaimView(Claim("theirs", "two"), people = listOf(Human("h2", "Bob"))))
        stateless.save(Claim("alone", "three"))
        fun narrate(id: String) = patchView(id) {
            fields { query.claim.note setTo "narrated" }
            where { query.people.name eq "Ada" }
        }

        assertEquals(changed, narrate("mine"))
        assertEquals(unmet, narrate("theirs"))
        // The root is there, though the view would not find the relationship: found, and not applied.
        assertEquals(unmet, narrate("alone"))
        assertEquals(absent, narrate("nobody"))

        assertEquals(listOf("narrated", null, null), listOf("mine", "theirs", "alone").map { claim(it).note })
        // The related nodes are not written.
        assertEquals("Ada", assertNotNull(stateless.load("h1", Human::class.java)).name)
    }

    @Test
    fun `a predicate may name the root and a relationship together`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("h1", "Ada"), Human("h2", "Bob"))))
        fun narrate(text: String, name: String) = patchView("c1") {
            fields { query.claim.note setTo "$text by $name" }
            where { query.claim.text eq text; query.people.name eq name }
        }

        assertEquals(unmet, narrate("two", "Ada"))
        assertEquals(unmet, narrate("one", "Cy"))
        assertEquals(changed, narrate("one", "Bob"))
        assertEquals("one by Bob", claim("c1").note)
    }

    @Test
    fun `a predicate may negate, quantify and choose, over the root and its relationships`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("h1", "Ada"), Human("h2", "Bob"))))
        stateless.save(Claim("alone", "one"))
        var round = 0
        fun note(id: String, where: context(org.drivine.query.dsl.WhereBuilder<ClaimViewQueryDsl>) () -> Unit) =
            patchView(id) { fields { query.claim.note setTo "round ${round++}" }; where(where) }.applied

        // No related node of the name: so for a root with other people, and for one with none.
        assertEquals(listOf(false, true, true), listOf(note("c1") { query.people.none { name eq "Ada" } }, note("c1") { query.people.none { name eq "Cy" } }, note("alone") { query.people.none { name eq "Ada" } }))
        // A negation of the root's own, and of a relationship: a comparison with no value is not met.
        assertEquals(listOf(true, false, false), listOf(note("c1") { not { query.claim.text eq "two" } }, note("c1") { not { query.claim.text eq "one" } }, note("c1") { not { query.people.name eq "Ada" } }))
        // Either the root's or a relationship's.
        assertEquals(listOf(true, true, false), listOf(
            note("c1") { anyOf { query.claim.text eq "two"; query.people.name eq "Bob" } },
            note("alone") { anyOf { query.claim.text eq "one"; query.people.name eq "Bob" } },
            note("alone") { anyOf { query.claim.text eq "two"; query.people.name eq "Bob" } },
        ))
        // One related node that meets two conditions together.
        assertEquals(listOf(true, false), listOf(note("c1") { query.people.any { name eq "Ada"; id eq "h1" } }, note("c1") { query.people.any { name eq "Ada"; id eq "h2" } }))
    }

    @Test
    fun `an id is found as a save stores it, whatever type the caller holds it as`() {
        val uuid = java.util.UUID.randomUUID()
        stateless.save(Claim(uuid.toString(), "one"))
        stateless.save(Claim("k1", "one"))

        assertEquals(changed, patchClaim(uuid) { fields { query.note setTo "by uuid" } })
        // A value class is stored as its value.
        assertEquals(changed, patchClaim(ClaimKey("k1")) { fields { query.note setTo "by key" } })

        assertEquals(listOf("by uuid", "by key"), listOf(uuid.toString(), "k1").map { claim(it).note })
    }

    // ----- Refusals -----

    @Test
    fun `the id, the stamp, a related node's field and a bag key cannot be written`() {
        stateless.save(ClaimView(Claim("c1", "one", "first"), people = listOf(Human("h1", "Ada"))))
        stateless.save(BaggedNode("b1", "bagged", mapOf("source" to "web")))
        val before = stamp("c1")

        assertContains(assertFailsWith<IllegalArgumentException> { patchClaim("c1") { fields { query.id setTo "c2" } } }.message!!, "the id")
        assertContains(assertFailsWith<IllegalArgumentException> { patchClaim("c1") { fields { query.stamp setTo "mine" } } }.message!!, "the stamp")
        assertContains(
            assertFailsWith<IllegalArgumentException> { patchView("c1") { fields { query.people.name setTo "Eve" } } }.message!!,
            "a field of a related node",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> {
                stateless.patch("b1", BaggedNode::class.java, BaggedNodeQueryDsl.INSTANCE) { fields { query.metadata.key("source") setTo "patched" } }
            }.message!!,
            "@PropertyBag",
        )
        assertContains(assertFailsWith<IllegalArgumentException> { patchClaim("c1") { where { query.text eq "one" } } }.message!!, "names none")
        assertContains(
            assertFailsWith<IllegalArgumentException> { patchClaim("c1") { fields { query.note setTo "a"; query.note.clear() } } }.message!!,
            "named 2 times",
        )

        assertEquals(before, stamp("c1"))
        assertEquals("Ada", assertNotNull(stateless.load("h1", Human::class.java)).name)
        assertEquals("web", assertNotNull(stateless.load("b1", BaggedNode::class.java)).metadata["source"])
    }

    // ----- One statement -----

    @Test
    fun `a patch is one statement, whatever it finds`() {
        stateless.save(ClaimView(Claim("c1", "one"), people = listOf(Human("h1", "Ada"))))
        val counting = CountingPatchStatements(pm)
        val counted = manager(counting)

        counted.patch("c1", Claim::class.java, ClaimQueryDsl.INSTANCE) { fields { query.note setTo "patched" }; where { query.text eq "one" } }
        counted.patch("c1", ClaimView::class.java, ClaimViewQueryDsl.INSTANCE) { fields { query.claim.note setTo "again" }; where { query.people.name eq "Ada" } }
        counted.patch("c1", Claim::class.java, ClaimQueryDsl.INSTANCE) { fields { query.note setTo "not this" }; where { query.text eq "two" } }
        counted.patch("nobody", Claim::class.java, ClaimQueryDsl.INSTANCE) { fields { query.note setTo "patched" } }

        assertEquals(4, counting.statements)
        assertEquals("again", claim("c1").note)
    }

    @Test
    fun `a field is written and tested under the property it is stored as`() {
        stateless.save(ChunkNode("k1", "text", containerSectionId = "s1", sequenceNumber = 1))
        val sequence = PropertyReference<Long>("n", "sequence_number")
        val section = StringPropertyReference("n", "container_section_id")
        fun renumber(inSection: String) = stateless.patch("k1", ChunkNode::class.java, Any()) { set(sequence.assign(2L)).onlyIf(section.isEqualTo(inSection)) }

        assertEquals(unmet, renumber("s2"))
        assertEquals(changed, renumber("s1"))

        val stored = assertNotNull(stateless.load("k1", ChunkNode::class.java))
        assertEquals(Triple("text", "s1", 2L), Triple(stored.text, stored.containerSectionId, stored.sequenceNumber))
    }

    @Test
    fun `a predicate written without a context holds when any of its conditions does`() {
        stateless.save(PatchSession("s1"))
        val dsl = PatchSessionQueryDsl.INSTANCE
        fun touch(at: Instant) = stateless.patch(
            "s1", PatchSession::class.java, dsl,
            java.util.function.Consumer { it.set(dsl.lastActivityAt.assign(at)).onlyIfAny(dsl.lastActivityAt.isAbsent(), dsl.lastActivityAt.isLessThan(at)) },
        )

        assertEquals(listOf(changed, unmet, changed), listOf(touch(noon), touch(noon.minusSeconds(5)), touch(noon.plusSeconds(5))))
        assertEquals(noon.plusSeconds(5), session("s1").lastActivityAt)
    }

    @Test
    fun `a patch is written without a context, by the methods Java uses`() {
        stateless.save(Claim("c1", "one", "first"))
        val dsl = ClaimQueryDsl.INSTANCE

        val applied = stateless.patch("c1", Claim::class.java, dsl, java.util.function.Consumer { it.set(dsl.note.assign("patched")).onlyIf(dsl.text.isEqualTo("one")) })
        val cleared = stateless.patch("c1", Claim::class.java, dsl, java.util.function.Consumer { it.set(dsl.note.cleared()).onlyIf(dsl.text.isEqualTo("two")) })

        assertEquals(changed to unmet, applied to cleared)
        assertEquals("patched", claim("c1").note)
    }
}

/** An id held as a type of the caller's own. */
@JvmInline
value class ClaimKey(val value: String)

/** Decorates a [PersistenceManager], counting the statements run through it, whichever way they are sent. */
private class CountingPatchStatements(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    var statements = 0

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = delegate.query(spec).also { statements++ }

    override fun execute(spec: QuerySpecification<*>) = delegate.execute(spec).also { statements++ }

    override fun <T : Any> getOne(spec: QuerySpecification<T>): T = delegate.getOne(spec).also { statements++ }

    override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? = delegate.maybeGetOne(spec).also { statements++ }

    override fun <T : Any> optionalGetOne(spec: QuerySpecification<T>): java.util.Optional<T> = delegate.optionalGetOne(spec).also { statements++ }

    override fun executeBatch(specs: List<QuerySpecification<*>>) = delegate.executeBatch(specs).also { statements += specs.size }

    override fun queryBatch(specs: List<QuerySpecification<*>>): List<List<Any?>> = delegate.queryBatch(specs).also { statements += specs.size }
}

@Testcontainers
class StatelessPatchNeo4jTest : StatelessPatchContract() {
    companion object {
        private const val PASSWORD = "patchone1"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-patch", type = DatabaseType.NEO4J,
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

/** Neo4j 4 takes `EXISTS { }` only in a `WHERE`, so a predicate on a relationship is read another way there. */
@Testcontainers
class StatelessPatchNeo4j4Test : StatelessPatchContract() {
    companion object {
        private const val PASSWORD = "patchfour1"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:4.4"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo4-patch", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_4,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
    override val searchesVectors: Boolean = false
}

@Testcontainers
class StatelessPatchFalkorDbTest : StatelessPatchContract() {
    companion object {
        private const val GRAPH = "patchone"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-patch", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
}

@Testcontainers
class StatelessPatchMemgraphTest : StatelessPatchContract() {
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
                name = "memgraph-patch", type = DatabaseType.MEMGRAPH,
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
class StatelessPatchOracleTest : StatelessPatchContract() {
    override val pm: PersistenceManager = OracleTestDatabase.fixtures(OracleTestDatabase.graphManager("contracts"))

    /** A search with no index measures every node. */
    override fun ensureVectorIndex() = Unit
}
