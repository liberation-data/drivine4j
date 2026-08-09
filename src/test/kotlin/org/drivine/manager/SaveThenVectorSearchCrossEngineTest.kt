package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.VectorIndexSpec
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.vector.AuthorNode
import sample.vector.DocNode
import sample.vector.DocView
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The coverage gap the write-side `vecf32(...)` fix closes: embeddings persisted **through the object
 * manager** (`save` / `saveAll`), not hand-written as a raw `vecf32(...)` literal, then searched with
 * `loadNearest`.
 *
 * On FalkorDB a vector index only indexes properties stored as the native vector type. Before the fix
 * `save()` wrote a plain array, the index stayed empty, and every assertion below returned nothing —
 * `VectorSearchCrossEngineTest` masked this by seeding with `vecf32($it)` in raw Cypher. Neo4j and
 * Memgraph store a plain array and are unaffected; they run here too as a regression guard that the
 * write path stays byte-identical for them.
 *
 * The query vector points at `[1,0,0,0]`; embeddings are 4-dim so the cosine ordering is obvious.
 */
private val QUERY = listOf(1.0f, 0.0f, 0.0f, 0.0f)

private val DOCS = listOf(
    DocNode("A", "Alpha", listOf(1.0f, 0.0f, 0.0f, 0.0f)),
    DocNode("B", "Beta", listOf(0.6f, 0.8f, 0.0f, 0.0f)),
    DocNode("C", "Gamma", listOf(0.0f, 0.0f, 1.0f, 0.0f)),
    DocNode("D", "Delta", listOf(1.0f, 0.0f, 0.0f, 0.0f)),
)

/**
 * The scenarios deliberately **do not delete between runs**: they MERGE the same four stable `Doc`
 * ids, so re-running updates in place rather than accumulating. Avoiding `DETACH DELETE` sidesteps a
 * Memgraph vector-index limitation — a deleted node is not purged from the HNSW index, so a later
 * search trips over it ("Trying to get labels from a deleted node") even after the index is
 * recreated. Each engine gets its own fresh container, so the graph starts empty regardless.
 */
private val DOC_VECTOR_INDEX = VectorIndexSpec("Doc", "embedding", 4)

/**
 * Single-object `save()`: the root-fragment merge path (incl. through a `@GraphView`).
 *
 * A fresh [GraphObjectManager] (hence a fresh `SessionManager`) is built per scenario so neither
 * scenario inherits the other's session snapshot — a stale snapshot would treat the embedding as
 * unchanged and skip re-writing it. Models a fresh process saving the same ids.
 */
private fun verifySingleSave(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry) {
    val gom = buildGom(pm, registry)
    val author = AuthorNode("au1", "Ada")
    // A, B, C saved through a view (root fragment + WRITTEN_BY); D saved as a bare fragment, no author.
    gom.save(DocView(DOCS[0], author))
    gom.save(DocView(DOCS[1], author))
    gom.save(DocView(DOCS[2], author))
    gom.save(DOCS[3])

    // If the embedding was written as a plain array on FalkorDB, the index is empty and these are all
    // empty — the exact pre-fix failure.
    val view = gom.loadNearest(DocView::class.java, QUERY, topK = 10)
    assertEquals(listOf("A", "B", "C"), view.map { it.value.doc.id }, "ranked, D pruned (no author)")
    assertEquals("Ada", view.first().value.author.name)

    val frags = gom.loadNearest(DocNode::class.java, QUERY, topK = 10)
    assertEquals(setOf("A", "B", "C", "D"), frags.map { it.value.id }.toSet(), "fragment search has no rel filter")

    // Read-back round-trip: the stored embedding loads back as a 4-element numeric vector.
    val a = gom.load("A", DocNode::class.java)
    assertNotNull(a?.embedding)
    assertEquals(4, a!!.embedding!!.size)
    assertEquals(
        listOf(1.0f, 0.0f, 0.0f, 0.0f),
        a.embedding!!.map { it.toFloat() },
        "embedding round-trips to its stored values",
    )
}

/** Batch `saveAll()`: the UNWIND path, which must fall back to per-item for vector roots on FalkorDB. */
private fun verifyBatchSave(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry) {
    val gom = buildGom(pm, registry)
    gom.saveAll(DOCS) // all four as bare fragments, in one batch call

    val frags = gom.loadNearest(DocNode::class.java, QUERY, topK = 10)
    assertEquals(
        setOf("A", "B", "C", "D"), frags.map { it.value.id }.toSet(),
        "batch-saved embeddings must be indexed too (FalkorDB per-item fallback)",
    )
    assertEquals(frags.map { it.score }, frags.map { it.score }.sortedDescending())
    assertTrue(frags.first().value.id in setOf("A", "D"), "nearest is one of the exact matches")
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class SaveThenVectorSearchFalkorDbTest {
    companion object {
        private const val GRAPH = "savevectortest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-save-vector", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            pm.indexes.ensure(DOC_VECTOR_INDEX)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `save then loadNearest on FalkorDB`() = verifySingleSave(pm, registry)

    @Test fun `saveAll then loadNearest on FalkorDB`() = verifyBatchSave(pm, registry)
}

@Testcontainers
class SaveThenVectorSearchMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-save-vector", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            pm.indexes.ensure(DOC_VECTOR_INDEX)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `save then loadNearest on Memgraph`() = verifySingleSave(pm, registry)

    @Test fun `saveAll then loadNearest on Memgraph`() = verifyBatchSave(pm, registry)
}

@Testcontainers
class SaveThenVectorSearchNeo4jTest {
    companion object {
        private const val PASSWORD = "savevectortest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-save-vector", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j", config = emptyMap(),
                cypherDialect = CypherDialect.NEO4J_5, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            pm.indexes.ensure(DOC_VECTOR_INDEX)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `save then loadNearest on Neo4j`() = verifySingleSave(pm, registry)

    @Test fun `saveAll then loadNearest on Neo4j`() = verifyBatchSave(pm, registry)
}
