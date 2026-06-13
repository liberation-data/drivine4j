package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.any
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.VectorIndexSpec
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.proposition.PropositionView
import sample.proposition.PropositionViewQueryDsl
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Feature 2 end-to-end — filtered vector search: `loadNearest(...) { where { } }` AND-ing caller
 * property predicates into the post-projection filter, verified on Neo4j, FalkorDB, and Memgraph.
 *
 * The proof that the predicate is genuinely applied: p2 and p3 are joint-nearest (identical
 * embedding to the query) but fail the predicate, so they are excluded despite ranking at the top;
 * p4 matches the predicate but is far, so it ranks last yet is included.
 */
private val QUERY = listOf(1.0f, 0.0f, 0.0f, 0.0f)

private fun verify(gom: GraphObjectManager) {
    // Unfiltered: p1/p2/p3 are joint-nearest (cosine 1.0), p4 is far.
    val all = gom.loadNearest(PropositionView::class.java, QUERY, topK = 10)
    assertEquals(setOf("p1", "p2", "p3"), all.take(3).map { it.value.proposition.id }.toSet())
    assertEquals("p4", all.last().value.proposition.id)

    // Filtered: contextId = ctx-a AND status = active. p2 (archived) and p3 (ctx-b) are excluded
    // despite ranking top; p4 (ctx-a, active, but far) is included.
    val filtered = gom.loadNearest(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, QUERY, topK = 10) {
        where {
            query.proposition.contextId eq "ctx-a"
            query.proposition.status eq "active"
        }
    }
    assertEquals(listOf("p1", "p4"), filtered.map { it.value.proposition.id })
    assertEquals(filtered.map { it.score }, filtered.map { it.score }.sortedDescending())

    // Relationship predicates aren't supported on the vector path (root is a map by filter time).
    assertThrows<UnsupportedOperationException> {
        gom.loadNearest(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, QUERY, topK = 10) {
            where { query.mentions.any { resolvedId eq "ent-1" } }
        }
    }
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

private fun seed(pm: NonTransactionalPersistenceManager, vec: (String) -> String) {
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (p1:Proposition {id: 'p1', contextId: 'ctx-a', status: 'active',   level: 0, embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (p2:Proposition {id: 'p2', contextId: 'ctx-a', status: 'archived', level: 0, embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (p3:Proposition {id: 'p3', contextId: 'ctx-b', status: 'active',   level: 0, embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (p4:Proposition {id: 'p4', contextId: 'ctx-a', status: 'active',   level: 0, embedding: ${vec("[0.0, 1.0, 0.0, 0.0]")}})
            """.trimIndent()
        )
    )
}

@Testcontainers
class FilteredVectorSearchNeo4jTest {
    companion object {
        private const val PASSWORD = "filteredvectortest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:5.26.1-community"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: GraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-fvs", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            gom = buildGom(pm, registry)
            pm.indexes.ensure(VectorIndexSpec("Proposition", "embedding", 4))
            seed(pm) { it }
            pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes(300)"))
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `filtered vector search on Neo4j`() = verify(gom)
}

@Testcontainers
class FilteredVectorSearchFalkorDbTest {
    companion object {
        private const val GRAPH = "fvstest"

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
                name = "falkor-fvs", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            gom = buildGom(pm, registry)
            pm.indexes.ensure(VectorIndexSpec("Proposition", "embedding", 4))
            seed(pm) { "vecf32($it)" }
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `filtered vector search on FalkorDB`() = verify(gom)
}

@Testcontainers
class FilteredVectorSearchMemgraphTest {
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
                name = "memgraph-fvs", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            gom = buildGom(pm, registry)
            pm.indexes.ensure(VectorIndexSpec("Proposition", "embedding", 4))
            seed(pm) { it }
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `filtered vector search on Memgraph`() = verify(gom)
}