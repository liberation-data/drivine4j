package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.session.SessionManager
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
import sample.propertybag.BaggedNode
import sample.propertybag.BaggedView
import sample.propertybag.BaggedViewQueryDsl
import sample.propertybag.TaggedNode
import sample.propertybag.TypedBagNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `@PropertyBag` round-trip verified on Neo4j, FalkorDB, and Memgraph: a multi-entry bag persists as
 * flat prefixed properties and reads back; an update that drops a key removes the stale property;
 * empty bags read back empty; and bags round-trip through a `@GraphView` (root + relationship target).
 */
private fun verify(gom: GraphObjectManager) {
    // ----- Fragment: save → load round-trip (multi-entry, incl. a homogeneous list) -----
    gom.save(BaggedNode("n1", "Title", mapOf("source" to "wiki", "score" to 3, "tags" to listOf("a", "b"))))
    val loaded = gom.load("n1", BaggedNode::class.java)!!
    assertEquals("Title", loaded.title)
    assertEquals("wiki", loaded.metadata["source"])
    assertEquals(3L, (loaded.metadata["score"] as Number).toLong()) // read asymmetry: Int written, Long read
    assertEquals(listOf("a", "b"), loaded.metadata["tags"])

    // ----- Update that removes a key: stale property is gone -----
    gom.save(loaded.copy(metadata = mapOf("source" to "blog", "tags" to listOf("a", "b"))))
    val updated = gom.load("n1", BaggedNode::class.java)!!
    assertEquals("blog", updated.metadata["source"])
    assertFalse(updated.metadata.containsKey("score"), "stale key should be removed: ${updated.metadata}")

    // ----- Empty bag reads back empty -----
    gom.save(BaggedNode("n2", "Empty", emptyMap()))
    assertTrue(gom.load("n2", BaggedNode::class.java)!!.metadata.isEmpty())

    // ----- Bags round-trip through a @GraphView (root + relationship target, distinct prefixes) -----
    gom.save(
        BaggedView(
            node = BaggedNode("doc", "Doc", mapOf("source" to "kb")),
            tags = listOf(TaggedNode("t1", "important", mapOf("weight" to 5, "color" to "red"))),
        )
    )
    val view = gom.load("doc", BaggedView::class.java)!!
    assertEquals("kb", view.node.metadata["source"])
    val tag = view.tags.single()
    assertEquals("important", tag.name)
    assertEquals(5L, (tag.attributes["weight"] as Number).toLong())
    assertEquals("red", tag.attributes["color"])

    // ----- Filter on a bag key via where { metadata.key(...) } -----
    val matched = gom.loadAll(BaggedView::class.java, BaggedViewQueryDsl.INSTANCE) {
        where { query.node.metadata.key("source") eq "kb" }
    }
    assertEquals(listOf("doc"), matched.map { it.node.id })

    val unmatched = gom.loadAll(BaggedView::class.java, BaggedViewQueryDsl.INSTANCE) {
        where { query.node.metadata.key("source") eq "nope" }
    }
    assertTrue(unmatched.isEmpty(), "no doc has source=nope")

    // ----- Typed bags round-trip to their DECLARED value type, not the driver's widened one -----
    verifyTypedBags(gom)
}

/**
 * A bag declared with a concrete value type (`Map<String, Int>`, `Map<String, Instant>`, …) reads
 * back as that type: Jackson resolves the field's declared generic when converting the reassembled
 * map. This is what narrows the documented read asymmetry to `Map<String, Any?>` alone.
 */
private fun verifyTypedBags(gom: GraphObjectManager) {
    val moment = java.time.Instant.parse("2026-07-24T10:15:30Z")
    gom.save(
        TypedBagNode(
            id = "t1",
            scores = mapOf("relevance" to 3, "rank" to 42),
            ratios = mapOf("confidence" to 0.75),
            labels = mapOf("source" to "wiki"),
            flags = mapOf("published" to true),
            timestamps = mapOf("indexedAt" to moment),
        )
    )
    val loaded = gom.load("t1", TypedBagNode::class.java)!!

    // Int stays Int — the Int→Long widening is NOT observable through a typed bag
    assertEquals(3, loaded.scores["relevance"])
    assertEquals(42, loaded.scores["rank"])
    assertEquals(
        Int::class.javaObjectType, loaded.scores["relevance"]!!::class.javaObjectType,
        "typed bag value should be Int, got ${loaded.scores["relevance"]!!::class.java}"
    )

    assertEquals(0.75, loaded.ratios["confidence"])
    assertEquals("wiki", loaded.labels["source"])
    assertEquals(true, loaded.flags["published"])
    assertEquals(moment, loaded.timestamps["indexedAt"])
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class PropertyBagNeo4jTest {
    companion object {
        private const val PASSWORD = "propbagtest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-bag", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `property bag round-trips on Neo4j`() = verify(buildGom(pm, SubtypeRegistry()))

    @Test
    fun `Java fragment property bag round-trips on Neo4j`() {
        val gom = buildGom(pm, SubtypeRegistry())
        gom.save(sample.propertybag.JavaBaggedNode("j1", "JT", mapOf<String, Any?>("source" to "wiki", "score" to 3)))
        val loaded = gom.load("j1", sample.propertybag.JavaBaggedNode::class.java)!!
        assertEquals("JT", loaded.title)
        assertEquals("wiki", loaded.metadata["source"])
        assertEquals(3L, (loaded.metadata["score"] as Number).toLong())
    }
}

@Testcontainers
class PropertyBagFalkorDbTest {
    companion object {
        private const val GRAPH = "propbagtest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-bag", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `property bag round-trips on FalkorDB`() = verify(buildGom(pm, SubtypeRegistry()))
}

@Testcontainers
class PropertyBagMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-bag", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `property bag round-trips on Memgraph`() = verify(buildGom(pm, SubtypeRegistry()))
}