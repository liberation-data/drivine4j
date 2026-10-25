package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import sample.multilabel.Essay
import sample.multilabel.PublishedClip
import sample.multilabel.PublishedClipView
import sample.multilabel.PublishedNote
import sample.multilabel.Snippet
import sample.multilabel.SnippetQueryDsl
import sample.multilabel.SnippetView
import sample.multilabel.PublishedPaper
import sample.multilabel.PublishedPaperQueryDsl
import sample.multilabel.PublishedPaperView
import kotlin.test.assertEquals

/**
 * A search over a root with more than one label returns only nodes with all of them, as a load does,
 * on each graph engine and each SQL store. Its index is on the first label, so it also yields a node
 * with that label alone: here the draft, which ranks best and must not come back. A full-text search
 * asks its index for more than `topK` hits to make up for those it drops, so one wanted is still found.
 */
private val QUERY = listOf(1.0f, 0.0f, 0.0f, 0.0f)

private fun seed(pm: PersistenceManager, vec: (String) -> String, awaitIndexes: Boolean) {
    pm.indexes.ensure(FullTextIndexSpec("Paper", "body"))
    pm.indexes.ensure(VectorIndexSpec("Clip", "embedding", 4))
    pm.indexes.ensure(FullTextIndexSpec("Essay", "body"))
    pm.indexes.ensure(VectorIndexSpec("Corpus_one", "embedding", 4))
    pm.indexes.ensure(VectorIndexSpec("Corpus_two", "embedding", 4))
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (:Paper {id: 'draft', body: 'kotlin'})
            CREATE (:Paper:Published {id: 'published', body: 'kotlin grass grass grass grass grass'})
            CREATE (:Clip {id: 'draft', embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (:Clip:Published {id: 'published', embedding: ${vec("[0.6, 0.8, 0.0, 0.0]")}})
            CREATE (:Essay {id: 'draft', body: 'kotlin'})
            CREATE (:Essay:Writing {id: 'published', body: 'kotlin grass grass grass grass grass'})
            CREATE (:Note:Corpus_one {id: 'draft', embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (:Note:Published:Corpus_one {id: 'published', embedding: ${vec("[0.6, 0.8, 0.0, 0.0]")}})
            CREATE (:Corpus_two {id: 'stray', embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (:Snippet:Corpus_two {id: 'kept', embedding: ${vec("[0.6, 0.8, 0.0, 0.0]")}})
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

    // A caller filter over the multi-label root still drops the draft, and over-fetches with it.
    assertEquals(
        listOf("published"),
        gom.loadMatching(PublishedPaper::class.java, PublishedPaperQueryDsl.INSTANCE, "kotlin", topK = 1) {
            where { query.id eq "published" }
        }.map { it.value.id },
    )

    // The draft outranks the paper, but is no PublishedPaper: the scores are scaled by the best hit the
    // search can return, so the paper scores 1, as it does on a store that narrows by labels first, and
    // a threshold just under 1 keeps it.
    assertEquals(1.0, gom.loadMatching<PublishedPaper>("kotlin", topK = 5).single().score, 1e-9)
    assertEquals(1.0, gom.loadMatching<PublishedPaperView>("kotlin", topK = 5).single().score, 1e-9)
    assertEquals(listOf("published"), gom.loadMatching<PublishedPaper>("kotlin", topK = 5, threshold = 0.99).map { it.value.id })
    assertEquals(
        listOf(1.0),
        gom.loadMatching(PublishedPaper::class.java, PublishedPaperQueryDsl.INSTANCE, "kotlin", topK = 5) {
            where { query.id eq "published" }
        }.map { it.score },
    )

    // A fragment's inherited label is checked, and counted when the search over-fetches.
    assertEquals(listOf("published"), gom.loadAll(Essay::class.java).map { it.id })
    assertEquals(listOf("published"), gom.loadMatching<Essay>("kotlin", topK = 1).map { it.value.id })

    // A partition's index is on the partition label, and a node in it must still have every root label.
    assertEquals(
        listOf("published"),
        gom.loadNearest(PublishedNote::class.java, QUERY, topK = 5, partitionLabel = "Corpus_one").map { it.value.id },
    )
    assertEquals(
        listOf("published"),
        gom.loadNearest(PublishedNote::class.java, QUERY, topK = 1, searchK = 5, partitionLabel = "Corpus_one").map { it.value.id },
    )

    // A partition's index is on its own label, so it holds a node with none of a one-label root's, which
    // ranks best here and must not come back.
    assertEquals(
        listOf("kept"),
        gom.loadNearest(Snippet::class.java, QUERY, topK = 5, partitionLabel = "Corpus_two").map { it.value.id },
    )
    assertEquals(
        listOf("kept"),
        gom.loadNearest(SnippetView::class.java, null, QUERY, topK = 5, threshold = null, searchK = null, partitionLabel = "Corpus_two")
            .map { it.value.snippet.id },
    )
    assertEquals(
        listOf("kept"),
        gom.loadNearest(Snippet::class.java, QUERY, topK = 1, searchK = 5, partitionLabel = "Corpus_two").map { it.value.id },
    )
    assertEquals(
        emptyList(),
        gom.loadNearest(Snippet::class.java, SnippetQueryDsl.INSTANCE, QUERY, topK = 5, partitionLabel = "Corpus_two") {
            where { query.id eq "stray" }
        }.map { it.value.id },
    )

    // Among a set, a neighbour must have every root label too, though its id is one of the set's.
    assertEquals(
        mapOf("published" to emptyList()),
        gom.loadNearestAmong(PublishedClip::class.java, listOf("published", "draft"), topK = 5),
    )
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

/** A SQL store narrows by the root's labels before it scales the scores: the graph engines are held to its answers. */
class MultiLabelRootSearchSqliteTest {
    private val registry = SubtypeRegistry()
    private val pm = SqliteTestDatabase.graphManager("contracts", registry)

    @Test fun `a search returns only nodes with every root label on SQLite`() {
        seed(SqliteTestDatabase.fixtures(pm), { it }, awaitIndexes = false)
        verify(buildGom(pm, registry))
    }
}

@PostgresTests
class MultiLabelRootSearchPostgresTest {
    private val registry = SubtypeRegistry()
    private val pm = PostgresTestDatabase.graphManager("contracts", registry)

    @Test fun `a search returns only nodes with every root label on Postgres`() {
        seed(PostgresTestDatabase.fixtures(pm), { it }, awaitIndexes = false)
        verify(buildGom(pm, registry))
    }
}

@OracleTests
class MultiLabelRootSearchOracleTest {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    @Test fun `a search returns only nodes with every root label on Oracle`() {
        seed(OracleTestDatabase.fixtures(pm), { it }, awaitIndexes = false)
        verify(buildGom(pm, registry))
    }
}
