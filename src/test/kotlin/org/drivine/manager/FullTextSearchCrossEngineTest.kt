package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.FullTextIndexSpec
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
import sample.fulltext.ArticleNode
import sample.fulltext.ArticleNodeQueryDsl
import sample.fulltext.ArticleView
import sample.fulltext.EntityNode
import sample.fulltext.NoteNode
import sample.fulltext.PrivateNote
import sample.fulltext.PublicNote
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full-text search ([GraphObjectManager.loadMatching]) verified against Neo4j, FalkorDB and Memgraph
 * — proving the per-engine full-text heads (procedure name, name-vs-label addressing, and the shared
 * `[0, 1]` score normalization) actually run and rank correctly, not just generate plausible Cypher.
 *
 * Covers: fragment ranking + `topK` + normalized `threshold`; the view projection path; a class-level
 * multi-property index; and polymorphic per-node dispatch on a sealed base. Because normalization
 * divides by the batch max, the top hit's score is always `1.0` — a deterministic cross-engine anchor.
 */
private fun ensureIndexes(pm: NonTransactionalPersistenceManager) {
    pm.indexes.ensure(FullTextIndexSpec("Article", "body"))
    pm.indexes.ensure(FullTextIndexSpec("Entity", listOf("name", "description")))
    pm.indexes.ensure(FullTextIndexSpec("Note", "text"))
}

private fun seed(pm: NonTransactionalPersistenceManager, awaitIndexes: Boolean) {
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (:Article {id: 'A', title: 'Graphs', body: 'graph databases store graph data'})
            CREATE (:Article {id: 'B', title: 'Cooking', body: 'recipes for soup and bread'})
            CREATE (:Article {id: 'C', title: 'Intro', body: 'a short note about a graph'})
            CREATE (:Entity {id: 'e1', name: 'Ada Lovelace', description: 'mathematician'})
            CREATE (:Entity {id: 'e2', name: 'Charles Babbage', description: 'worked with Ada'})
            CREATE (:Entity {id: 'e3', name: 'Zed', description: 'unrelated person'})
            CREATE (:Note:PublicNote {id: 'p1', text: 'kotlin is great'})
            CREATE (:Note:PrivateNote {id: 's1', text: 'kotlin secret plan'})
            CREATE (:Note:PublicNote {id: 'p2', text: 'python notes'})
            """.trimIndent()
        )
    )
    // Neo4j full-text indexes populate asynchronously — block until they catch up with the seed.
    if (awaitIndexes) pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes()"))
}

private fun verify(gom: GraphObjectManager, pm: NonTransactionalPersistenceManager, awaitIndexes: Boolean) {
    ensureIndexes(pm)
    seed(pm, awaitIndexes)

    // Fragment ranking + normalization: A and C match "graph", B does not; the top hit normalizes to 1.0.
    val arts = gom.loadMatching<ArticleNode>("graph", topK = 10)
    assertEquals(setOf("A", "C"), arts.map { it.value.id }.toSet())
    assertEquals(1.0, arts.first().score, 1e-9)
    assertTrue(arts.all { it.score in 0.0..1.0 && !it.score.isNaN() }, "normalized, non-NaN scores expected: ${arts.map { it.score }}")
    assertEquals(arts.map { it.score }, arts.map { it.score }.sortedDescending())

    // topK caps the result count.
    assertEquals(1, gom.loadMatching<ArticleNode>("graph", topK = 1).size)

    // A threshold above the normalized max (1.0) drops everything — proves the threshold is applied.
    assertTrue(gom.loadMatching<ArticleNode>("graph", topK = 10, threshold = 1.1).isEmpty())

    // View path: the hit is projected into the view.
    val views = gom.loadMatching<ArticleView>("graph", topK = 10)
    assertEquals(setOf("A", "C"), views.map { it.value.article.id }.toSet())

    // Class-level multi-property index: "Ada" matches e1 (name) and e2 (description), not e3.
    val ents = gom.loadMatching<EntityNode>("Ada", topK = 10)
    assertEquals(setOf("e1", "e2"), ents.map { it.value.id }.toSet())

    // Polymorphic dispatch on a sealed base: "kotlin" matches a public and a private note, each typed.
    val notes = gom.loadMatching<NoteNode>("kotlin", topK = 10)
    assertEquals(setOf("p1", "s1"), notes.map { it.value.id }.toSet())
    assertTrue(notes.any { it.value is PublicNote } && notes.any { it.value is PrivateNote })

    // Filtered form: full-text relevance AND a property predicate in one query.
    val filtered = gom.loadMatching(ArticleNode::class.java, ArticleNodeQueryDsl.INSTANCE, "graph", topK = 10) {
        where { query.id eq "A" }
    }
    assertEquals(listOf("A"), filtered.map { it.value.id })
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class FullTextSearchNeo4jTest {
    companion object {
        private const val PW = "fulltexttest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PW) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-fulltext", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PW, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `full-text search ranks, projects, and dispatches on Neo4j`() = verify(buildGom(pm, registry), pm, awaitIndexes = true)
}

@Testcontainers
class FullTextSearchFalkorDbTest {
    companion object {
        private const val GRAPH = "fulltexttest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-fulltext", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `full-text search ranks, projects, and dispatches on FalkorDB`() = verify(buildGom(pm, registry), pm, awaitIndexes = false)
}

@Testcontainers
class FullTextSearchMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687)
            .waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-fulltext", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `full-text search ranks, projects, and dispatches on Memgraph`() = verify(buildGom(pm, registry), pm, awaitIndexes = false)
}