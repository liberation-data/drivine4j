package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.field
import org.drivine.query.dsl.property
import org.drivine.query.dsl.query
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
import sample.vectorfilter.VecChunkNode
import sample.vectorfilter.VecContentNode
import sample.vectorfilter.VecContentNodeQueryDsl
import sample.vectorfilter.VecDocNode
import sample.vectorfilter.VecDocNodeQueryDsl
import sample.vectorfilter.VecImageNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Filtered fragment vector search — `loadNearest(class, Q, vector, topK, threshold) { where { } }` over a
 * bare `@NodeFragment` — verified cross-engine (Neo4j / FalkorDB / Memgraph). The vector mirror of the
 * filtered `loadMatching` fragment path: a `where { }` predicate on a promoted `@GraphProperty` field
 * and on a `@PropertyBag` key, ranked results, `topK`, and polymorphic dispatch on a sealed base. Also a
 * regression that the old "not a @GraphView" `IllegalArgumentException` is gone.
 */
private val QUERY = listOf(1.0f, 0.0f, 0.0f, 0.0f)

private fun verify(gom: GraphObjectManager, pm: NonTransactionalPersistenceManager, vec: (String) -> String) {
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (:VecDoc {id: 'a', section_id: 's1', `metadata.source`: 'web',  embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (:VecDoc {id: 'b', section_id: 's2', `metadata.source`: 'book', embedding: ${vec("[0.0, 1.0, 0.0, 0.0]")}})
            CREATE (:VecDoc {id: 'c', section_id: 's1', `metadata.source`: 'web',  embedding: ${vec("[0.9, 0.1, 0.0, 0.0]")}})
            CREATE (:VecContent:VecChunk {id: 'ch1', chunkText: 'hello', embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (:VecContent:VecImage {id: 'im1', caption: 'pic',    embedding: ${vec("[0.9, 0.1, 0.0, 0.0]")}})
            """.trimIndent()
        )
    )
    val doc = VecDocNodeQueryDsl.INSTANCE

    // Filtered by a promoted @GraphProperty field: b (section s2) is pruned; a and c ranked closest-first.
    val s1 = gom.loadNearest(VecDocNode::class.java, doc, QUERY, topK = 10) { where { query.field("sectionId") eq "s1" } }
    assertEquals(listOf("a", "c"), s1.map { it.value.id })
    assertEquals(s1.map { it.score }, s1.map { it.score }.sortedDescending())
    assertTrue(s1.all { it.score in 0.0..1.0 && !it.score.isNaN() }, "normalized scores: ${s1.map { it.score }}")

    // Filtered by a @PropertyBag key (stored as `metadata.source`).
    val book = gom.loadNearest(VecDocNode::class.java, doc, QUERY, topK = 10) { where { query.property("metadata.source") eq "book" } }
    assertEquals(setOf("b"), book.map { it.value.id }.toSet())

    // field() resolves the bag key from the fragment's annotations, matching the explicit stored path.
    val web = gom.loadNearest(VecDocNode::class.java, doc, QUERY, topK = 10) { where { query.field("source") eq "web" } }
    val webStored = gom.loadNearest(VecDocNode::class.java, doc, QUERY, topK = 10) { where { query.property("metadata.source") eq "web" } }
    assertEquals(setOf("a", "c"), web.map { it.value.id }.toSet())
    assertEquals(webStored.map { it.value.id }.toSet(), web.map { it.value.id }.toSet())

    // Regression: filtered loadNearest on a @NodeFragment no longer throws "not a @GraphView".
    val one = gom.loadNearest(VecDocNode::class.java, doc, QUERY, topK = 10) { where { query.id eq "a" } }
    assertEquals(listOf("a"), one.map { it.value.id })

    // Polymorphic dispatch on a sealed base, through the filtered fragment vector path.
    val poly = gom.loadNearest(VecContentNode::class.java, VecContentNodeQueryDsl.INSTANCE, QUERY, topK = 10) {
        where { query.id neq "none" }
    }
    assertEquals(setOf("ch1", "im1"), poly.map { it.value.id }.toSet())
    assertTrue(poly.any { it.value is VecChunkNode } && poly.any { it.value is VecImageNode })
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

private fun ensureIndexes(pm: NonTransactionalPersistenceManager) {
    pm.indexes.ensure(VectorIndexSpec("VecDoc", "embedding", 4))
    pm.indexes.ensure(VectorIndexSpec("VecContent", "embedding", 4))
}

@Testcontainers
class FilteredFragmentVectorSearchFalkorDbTest {
    companion object {
        private const val GRAPH = "filtfragvec"

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
                name = "falkor-filtfragvec", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            ensureIndexes(pm)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `filtered fragment vector search on FalkorDB`() = verify(buildGom(pm, registry), pm) { "vecf32($it)" }
}

@Testcontainers
class FilteredFragmentVectorSearchMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-filtfragvec", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            ensureIndexes(pm)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `filtered fragment vector search on Memgraph`() = verify(buildGom(pm, registry), pm) { it }
}

@Testcontainers
class FilteredFragmentVectorSearchNeo4jTest {
    companion object {
        private const val PW = "filtfragvec"

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
                name = "neo-filtfragvec", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PW, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            ensureIndexes(pm)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `filtered fragment vector search on Neo4j`() = verify(buildGom(pm, registry), pm) { it }
}
