package org.drivine.manager

import org.drivine.connection.DatabaseType
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
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.vector.DocNode
import sample.vector.DocView
import kotlin.test.assertEquals
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
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:5.26.1-community"))
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
}