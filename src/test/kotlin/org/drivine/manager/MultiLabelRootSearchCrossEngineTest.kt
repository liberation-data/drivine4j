package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import sample.multilabel.PublishedClip
import sample.multilabel.PublishedClipView
import sample.multilabel.PublishedPaper
import sample.multilabel.PublishedPaperView
import kotlin.test.assertEquals

/**
 * A search over a root with more than one label returns only nodes with all of them, as a load does,
 * on each graph engine. Its index is on the first label, so it also yields a node with that label
 * alone: here the draft, which ranks best and must not come back. A full-text search asks its index
 * for more than `topK` hits to make up for those it drops, so one wanted is still found.
 */
private val QUERY = listOf(1.0f, 0.0f, 0.0f, 0.0f)

private fun seed(pm: PersistenceManager, vec: (String) -> String, awaitIndexes: Boolean) {
    pm.indexes.ensure(FullTextIndexSpec("Paper", "body"))
    pm.indexes.ensure(VectorIndexSpec("Clip", "embedding", 4))
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (:Paper {id: 'draft', body: 'kotlin'})
            CREATE (:Paper:Published {id: 'published', body: 'kotlin grass grass grass grass grass'})
            CREATE (:Clip {id: 'draft', embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (:Clip:Published {id: 'published', embedding: ${vec("[0.6, 0.8, 0.0, 0.0]")}})
            """.trimIndent()
        )
    )
    if (awaitIndexes) pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes()"))
}

private fun verify(gom: StatelessGraphObjectManager) {
    assertEquals(listOf("published"), gom.loadAll(PublishedPaper::class.java).map { it.id })

    assertEquals(listOf("published"), gom.loadMatching<PublishedPaper>("kotlin", topK = 5).map { it.value.id })
    assertEquals(listOf("published"), gom.loadMatching<PublishedPaper>("kotlin", topK = 1).map { it.value.id })
    assertEquals(listOf("published"), gom.loadMatching<PublishedPaperView>("kotlin", topK = 5).map { it.value.paper.id })
    assertEquals(listOf("published"), gom.loadMatching<PublishedPaperView>("kotlin", topK = 1).map { it.value.paper.id })

    assertEquals(listOf("published"), gom.loadNearest(PublishedClip::class.java, QUERY, topK = 5).map { it.value.id })
    assertEquals(listOf("published"), gom.loadNearest(PublishedClipView::class.java, QUERY, topK = 5).map { it.value.clip.id })
}

private fun buildGom(pm: PersistenceManager, registry: SubtypeRegistry) =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

class MultiLabelRootSearchNeo4jTest {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-multilabel", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            val pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            gom = buildGom(pm, registry)
            seed(pm, { it }, awaitIndexes = true)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `a search returns only nodes with every root label on Neo4j`() = verify(gom)
}

class MultiLabelRootSearchFalkorDbTest {
    companion object {
        private const val GRAPH = "multilabeltest"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-multilabel", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            val pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            gom = buildGom(pm, registry)
            // FalkorDB stores vectors via vecf32(...).
            seed(pm, { "vecf32($it)" }, awaitIndexes = false)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `a search returns only nodes with every root label on FalkorDB`() = verify(gom)
}

class MultiLabelRootSearchMemgraphTest {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-multilabel", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            val pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            gom = buildGom(pm, registry)
            seed(pm, { it }, awaitIndexes = false)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `a search returns only nodes with every root label on Memgraph`() = verify(gom)
}
