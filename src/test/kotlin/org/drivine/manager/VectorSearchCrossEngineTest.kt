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
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.vector.DocNode
import sample.vector.DocView
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vector search ([GraphObjectManager.loadNearest]) verified against FalkorDB and Memgraph — the two
 * non-Neo4j engines with a native vector index. This proves the per-engine vector heads (procedure
 * name, `vecf32` wrapping, distance→similarity normalization) actually run and rank correctly, not
 * just that they generate plausible Cypher.
 *
 * The query vector points at `[1,0,0,0]`; embeddings are 4-dim so the cosine ordering is obvious.
 * `D` is the joint-nearest match but has no author, so the required `WRITTEN_BY` relationship must
 * prune it — demonstrating the post-filter (`< topK`) semantics on each engine.
 */
private val QUERY = listOf(1.0f, 0.0f, 0.0f, 0.0f)

private fun verify(gom: GraphObjectManager) {
    val results = gom.loadNearest(DocView::class.java, QUERY, topK = 10)

    // D pruned (no author); the rest ranked closest-first.
    assertEquals(listOf("A", "B", "C"), results.map { it.value.doc.id })
    assertEquals(3, results.size)
    assertTrue(results.none { it.value.doc.id == "D" })

    // Required relationship is hydrated; scores are normalized similarities, descending.
    assertEquals("Ada", results.first().value.author.name)
    val scores = results.map { it.score }
    assertEquals(scores, scores.sortedDescending())
    assertTrue(scores.all { it in 0.0..1.0 }, "normalized similarities expected: $scores")

    // Threshold between the top two scores keeps only A.
    val between = (scores[0] + scores[1]) / 2.0
    val filtered = gom.loadNearest(DocView::class.java, QUERY, topK = 10, threshold = between)
    assertEquals(listOf("A"), filtered.map { it.value.doc.id })

    // Fragment search has no relationship filter, so the authorless D is included (unlike the view).
    val frags = gom.loadNearest(DocNode::class.java, QUERY, topK = 10)
    assertEquals(setOf("A", "B", "C", "D"), frags.map { it.value.id }.toSet())
    assertEquals(frags.map { it.score }, frags.map { it.score }.sortedDescending())
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

/** Seeds the four Docs + author. [vec] renders an embedding literal in the engine's vector syntax. */
private fun seed(pm: NonTransactionalPersistenceManager, vec: (String) -> String) {
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (auth:Author {id: 'au1', name: 'Ada'})
            CREATE (a:Doc {id: 'A', title: 'Alpha', embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (b:Doc {id: 'B', title: 'Beta',  embedding: ${vec("[0.6, 0.8, 0.0, 0.0]")}})
            CREATE (c:Doc {id: 'C', title: 'Gamma', embedding: ${vec("[0.0, 0.0, 1.0, 0.0]")}})
            CREATE (d:Doc {id: 'D', title: 'Delta', embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (a)-[:WRITTEN_BY]->(auth)
            CREATE (b)-[:WRITTEN_BY]->(auth)
            CREATE (c)-[:WRITTEN_BY]->(auth)
            """.trimIndent()
        )
    )
}

@Testcontainers
class VectorSearchFalkorDbTest {
    companion object {
        private const val GRAPH = "vectortest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: GraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-vector", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            gom = buildGom(pm, registry)

            pm.indexes.ensure(VectorIndexSpec("Doc", "embedding", 4))
            // FalkorDB stores vectors via vecf32(...).
            seed(pm) { "vecf32($it)" }
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `vector search ranks, prunes, and thresholds on FalkorDB`() = verify(gom)
}

@Testcontainers
class VectorSearchMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: GraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-vector", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            gom = buildGom(pm, registry)

            pm.indexes.ensure(VectorIndexSpec("Doc", "embedding", 4))
            // Memgraph stores vectors as plain lists of floats.
            seed(pm) { it }
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `vector search ranks, prunes, and thresholds on Memgraph`() = verify(gom)
}