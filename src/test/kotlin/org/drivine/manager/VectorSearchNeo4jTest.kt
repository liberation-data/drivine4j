package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.Neo4jVectorOptions
import org.drivine.schema.VectorIndexSpec
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.vector.DocNode
import sample.vector.DocView
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end vector search against a real Neo4j, proving the headline behaviours of
 * [GraphObjectManager.loadNearest]:
 *  - results are projected `@GraphView` instances paired with a normalized score, ranked closest first;
 *  - the view's required relationship prunes candidates **after** the K-nearest search, so the result
 *    can contain fewer than `topK` rows (a Doc with no author is dropped even though it is the nearest);
 *  - a similarity `threshold` floors the results.
 *
 * Embeddings are 4-dim so the expected cosine ordering is obvious by inspection.
 */
@Testcontainers
class VectorSearchNeo4jTest {

    companion object {
        private const val PASSWORD = "vectorsearchtest"

        @Container
        @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: GraphObjectManager

        // query vector points straight at [1,0,0,0]
        private val query = listOf(1.0f, 0.0f, 0.0f, 0.0f)

        @JvmStatic
        @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-vector",
                type = DatabaseType.NEO4J,
                host = container.host,
                port = container.getMappedPort(7687),
                user = "neo4j",
                password = PASSWORD,
                database = "neo4j",
                config = emptyMap(),
                subtypeRegistry = registry,
                cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            val mapper = Neo4jObjectMapper.instance
            gom = GraphObjectManager(pm, SessionManager(mapper), mapper, registry)

            // Declare the index the @VectorIndex on DocNode describes (4 dims for the test).
            pm.indexes.ensure(VectorIndexSpec("Doc", "embedding", 4))

            pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
            pm.execute(
                QuerySpecification.withStatement(
                    """
                    CREATE (auth:Author {id: 'au1', name: 'Ada'})
                    // A and D both point exactly at the query (cosine 1.0); B is near; C is orthogonal.
                    CREATE (a:Doc {id: 'A', title: 'Alpha', embedding: [1.0, 0.0, 0.0, 0.0]})
                    CREATE (b:Doc {id: 'B', title: 'Beta',  embedding: [0.6, 0.8, 0.0, 0.0]})
                    CREATE (c:Doc {id: 'C', title: 'Gamma', embedding: [0.0, 0.0, 1.0, 0.0]})
                    CREATE (d:Doc {id: 'D', title: 'Delta', embedding: [1.0, 0.0, 0.0, 0.0]})
                    CREATE (a)-[:WRITTEN_BY]->(auth)
                    CREATE (b)-[:WRITTEN_BY]->(auth)
                    CREATE (c)-[:WRITTEN_BY]->(auth)
                    // D deliberately has NO author — the required relationship must prune it.
                    """.trimIndent()
                )
            )
            // Two per-partition indexes over the same property, differing only in quantization, so
            // tests can compare pinned-on against pinned-off without depending on the engine default.
            pm.execute(QuerySpecification.withStatement("MATCH (n:Doc) SET n:DocExact, n:DocQuantized"))
            pm.indexes.ensure(
                VectorIndexSpec(
                    "DocExact", "embedding", 4,
                    engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = false)),
                )
            )
            pm.indexes.ensure(
                VectorIndexSpec(
                    "DocQuantized", "embedding", 4,
                    engineOptions = listOf(Neo4jVectorOptions(quantizationEnabled = true)),
                )
            )

            // A second, per-partition index over the same property: A and B also carry :Corpus_one,
            // so the partitioned search below has its own index covering a subset of the same nodes.
            pm.execute(QuerySpecification.withStatement("MATCH (n:Doc) WHERE n.id IN ['A','B'] SET n:Corpus_one"))
            pm.indexes.ensure(VectorIndexSpec("Corpus_one", "embedding", 4))

            // Vector index population is async; wait for it before querying.
            pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes(300)"))
        }

        @JvmStatic
        @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `ranks views by similarity and pairs each with a normalized score`() {
        val results = gom.loadNearest(DocView::class.java, query, topK = 10)

        // D is pruned (no author); the rest come back closest-first.
        assertEquals(listOf("A", "B", "C"), results.map { it.value.doc.id })

        // Projected views are fully hydrated, including the required relationship.
        assertEquals("Ada", results.first().value.author.name)

        // Scores are descending and within the normalized [0, 1] range.
        val scores = results.map { it.score }
        assertEquals(scores, scores.sortedDescending())
        assertTrue(scores.all { it in 0.0..1.0 }, "scores should be normalized similarities: $scores")
    }

    @Test
    fun `required relationship prunes candidates after the search, so fewer than topK may return`() {
        // Four Docs exist and topK=10 retrieves them all, but the authorless D (a top-similarity
        // match) is dropped by the required WRITTEN_BY filter — leaving three.
        val results = gom.loadNearest(DocView::class.java, query, topK = 10)
        assertEquals(3, results.size)
        assertTrue(results.none { it.value.doc.id == "D" })
    }

    @Test
    fun `fragment search returns the bare nodes, including ones a view would prune`() {
        // Searching the DocNode fragment directly: no relationship filter, so the authorless D —
        // which the DocView search pruned — is included. A and D (identical embedding) rank jointly.
        val results = gom.loadNearest(DocNode::class.java, query, topK = 10)
        val ids = results.map { it.value.id }

        assertEquals(setOf("A", "B", "C", "D"), ids.toSet())
        assertEquals(setOf("A", "D"), ids.take(2).toSet())
        assertEquals("C", ids.last())
        assertEquals("Alpha", results.first { it.value.id == "A" }.value.title)
        val scores = results.map { it.score }
        assertEquals(scores, scores.sortedDescending())
    }

    @Test
    fun `threshold floors the results by similarity`() {
        val all = gom.loadNearest(DocView::class.java, query, topK = 10)
        val scoreA = all.first { it.value.doc.id == "A" }.score
        val scoreB = all.first { it.value.doc.id == "B" }.score
        val between = (scoreA + scoreB) / 2.0

        val filtered = gom.loadNearest(DocView::class.java, query, topK = 10, threshold = between)
        assertEquals(listOf("A"), filtered.map { it.value.doc.id })
    }

    // ----- Runtime partition targeting -----

    @Test
    fun `a partition label searches that partition's own index`() {
        // The whole point of per-partition indexes: the ANN search runs INSIDE the partition, so the
        // filter no longer has to thin a globally-nearest result set. Neo4j must accept the index name
        // as a bound parameter for this to work at all — if it required a literal, every partition
        // would compile to its own query plan.
        val results = gom.loadNearest(
            DocNode::class.java, null, query, topK = 10, threshold = null,
            searchK = null, partitionLabel = "Corpus_one",
        )

        // Only the partition's members can come back — C and D were never in that index.
        assertEquals(listOf("A", "B"), results.map { it.value.id }.sorted())
    }

    @Test
    fun `the unpartitioned search still sees everything`() {
        // Targeting is not identity: adding :Corpus_one to A and B must not change the default search.
        val results = gom.loadNearest(DocNode::class.java, query, topK = 10)

        assertEquals(listOf("A", "B", "C", "D"), results.map { it.value.id }.sorted())
    }

    @Test
    fun `a partition composes with an over-fetch`() {
        val results = gom.loadNearest(
            DocNode::class.java, null, query, topK = 1, threshold = null,
            searchK = 10, partitionLabel = "Corpus_one",
        )

        // Searched wide inside the partition, trimmed to one row.
        assertEquals(1, results.size)
        assertEquals("A", results.single().value.id)
    }

    // ----- Why the rescore is load-bearing -----

    @Test
    fun `the rescored score is on the index's scale, not a different normalization`() {
        // The threshold filter compares against whichever score is in scope, and over-fetching swaps
        // the index's yielded score for an exactly recomputed one. A different NORMALIZATION would
        // make the same threshold mean different things depending on whether searchK is set.
        val fromIndex = gom.loadNearest(DocNode::class.java, query, topK = 10)
            .associate { it.value.id to it.score }
        val rescored = gom.loadNearest(
            DocNode::class.java, null, query, topK = 10, threshold = null, searchK = 10,
        )

        assertTrue(rescored.isNotEmpty(), "expected rows to compare")
        rescored.forEach { hit ->
            val indexScore = assertNotNull(fromIndex[hit.value.id])
            assertEquals(
                indexScore, hit.score, 1e-3,
                "rescored score for ${hit.value.id} is not on the index's scale " +
                    "(index=$indexScore, rescored=${hit.score})",
            )
        }
    }

    @Test
    fun `the rescore is exact whatever the index was configured to do`() {
        // B is [0.6, 0.8, 0, 0] against a query of [1, 0, 0, 0]: cosine 0.6, normalized to exactly 0.8.
        // The re-rank recomputes from the stored vector, so it yields 0.8 whether or not the index it
        // read from quantizes. That independence is the point: it is what makes over-fetch-then-trim
        // safe to apply without knowing how the index was built.
        listOf("DocQuantized", "DocExact").forEach { partition ->
            val exact = gom.loadNearest(
                DocNode::class.java, null, query, topK = 10, threshold = null,
                searchK = 10, partitionLabel = partition,
            ).single { it.value.id == "B" }.score

            assertEquals(0.8, exact, 1e-6, "the rescore should be exact on $partition")
        }
    }

    @Test
    fun `an index with quantization pinned off reports the exact score`() {
        val unquantized = gom.loadNearest(
            DocNode::class.java, null, query, topK = 10, threshold = null,
            searchK = null, partitionLabel = "DocExact",
        ).single { it.value.id == "B" }.score

        assertEquals(0.8, unquantized, 1e-6, "with quantization off the index score should be exact")
    }

    @Test
    fun `whether the DEFAULT quantizes is the engine's business, and it changes`() {
        // Deliberately asserts nothing about the unpinned index's score. An earlier version of this
        // test asserted that the default quantizes — true on a neo4j:latest image pulled in June 2026,
        // false on one pulled in August — so it passed locally and failed in CI, which pulls fresh.
        // That is precisely the failure VectorIndexSpec's pinning exists to prevent, and asserting an
        // unpinned default here would have been the same mistake one layer up. What IS invariant is
        // that pinning wins, which the two tests above cover.
        val fromDefaultIndex = gom.loadNearest(DocNode::class.java, query, topK = 10)
            .single { it.value.id == "B" }.score

        assertEquals(
            0.8, fromDefaultIndex, 1e-3,
            "an unpinned index may quantize or not, but must stay on the same scale",
        )
    }

    @Test
    fun `a threshold selects the same rows with and without over-fetching`() {
        val plain = gom.loadNearest(DocNode::class.java, query, topK = 10, threshold = 0.9)
        val tuned = gom.loadNearest(
            DocNode::class.java, null, query, topK = 10, threshold = 0.9, searchK = 10,
        )

        assertEquals(plain.map { it.value.id }.sorted(), tuned.map { it.value.id }.sorted())
    }
}
