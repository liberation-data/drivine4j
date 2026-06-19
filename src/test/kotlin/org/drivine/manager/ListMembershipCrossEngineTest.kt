package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.any
import org.drivine.query.dsl.hasItem
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
import sample.proposition.PropositionView
import sample.proposition.PropositionViewQueryDsl
import kotlin.test.assertEquals

/**
 * 0.0.56 end-to-end — `hasItem` (a caller value contained in a list-valued node property,
 * `$value IN proposition.grounding`) verified on Neo4j, FalkorDB, and Memgraph. Covers the **load
 * path** (alone, AND-ed with a property predicate, and composed with a relationship `any{}`) and the
 * **filtered vector path** (`loadNearest { where { } }`). The FalkorDB vector case is the careful one:
 * the predicate is plain map-property access on the projected root, never a pattern over the
 * `vecf32`-bearing node, so it does not re-trigger the vecf32 quirk.
 *
 * grounding: p1→[chunk-1, chunk-2], p2→[chunk-2], p3→[chunk-1], p4→[chunk-1].
 * mentions:  p1→entX, p2→entY, p3→entY, p4→entX.   embeddings: p1/p2/p3 near QUERY, p4 far.
 */
private val QUERY = listOf(1.0f, 0.0f, 0.0f, 0.0f)

private fun verify(gom: GraphObjectManager) {
    fun loadIds(spec: org.drivine.query.dsl.GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit): Set<String> =
        gom.loadAll(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, spec)
            .map { it.proposition.id }.toSet()

    fun nearIds(spec: org.drivine.query.dsl.GraphQuerySpec<PropositionViewQueryDsl>.() -> Unit): List<String> =
        gom.loadNearest(PropositionView::class.java, PropositionViewQueryDsl.INSTANCE, QUERY, topK = 10, spec = spec)
            .map { it.value.proposition.id }

    // ----- load path -----
    assertEquals(setOf("p1", "p3", "p4"), loadIds { where { query.proposition.grounding hasItem "chunk-1" } })
    assertEquals(setOf("p1", "p2"), loadIds { where { query.proposition.grounding hasItem "chunk-2" } })
    assertEquals(emptySet(), loadIds { where { query.proposition.grounding hasItem "absent" } })

    // AND with a property predicate: ctx 'c' AND grounding ∋ chunk-1 → p1, p4 (p3 is ctx 'd').
    assertEquals(
        setOf("p1", "p4"),
        loadIds { where { query.proposition.contextId eq "c"; query.proposition.grounding hasItem "chunk-1" } },
    )

    // Compose with a relationship any{}: grounding ∋ chunk-1 AND mentions entX → {p1,p3,p4} ∩ {p1,p4}.
    assertEquals(
        setOf("p1", "p4"),
        loadIds {
            where {
                query.proposition.grounding hasItem "chunk-1"
                query.mentions.any { resolvedId eq "entX" }
            }
        },
    )

    // ----- filtered vector path -----
    // grounding ∋ chunk-1 → p1,p3 (near) then p4 (far, last). p2 excluded (only chunk-2).
    val nearChunk1 = nearIds { where { query.proposition.grounding hasItem "chunk-1" } }
    assertEquals(setOf("p1", "p3", "p4"), nearChunk1.toSet())
    assertEquals("p4", nearChunk1.last())

    // Vector + property + list-membership: ctx 'c' AND grounding ∋ chunk-1 → p1 then p4.
    assertEquals(
        listOf("p1", "p4"),
        nearIds { where { query.proposition.contextId eq "c"; query.proposition.grounding hasItem "chunk-1" } },
    )
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
            CREATE (p1:Proposition {id: 'p1', contextId: 'c', status: 'active', level: 0, grounding: ['chunk-1', 'chunk-2'], embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (p2:Proposition {id: 'p2', contextId: 'c', status: 'active', level: 0, grounding: ['chunk-2'],            embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (p3:Proposition {id: 'p3', contextId: 'd', status: 'active', level: 0, grounding: ['chunk-1'],            embedding: ${vec("[1.0, 0.0, 0.0, 0.0]")}})
            CREATE (p4:Proposition {id: 'p4', contextId: 'c', status: 'active', level: 0, grounding: ['chunk-1'],            embedding: ${vec("[0.0, 1.0, 0.0, 0.0]")}})
            CREATE (m1:Mention {id: 'm1', resolvedId: 'entX', role: 'SUBJECT'})
            CREATE (m2:Mention {id: 'm2', resolvedId: 'entY', role: 'OBJECT'})
            CREATE (m3:Mention {id: 'm3', resolvedId: 'entY', role: 'SUBJECT'})
            CREATE (m4:Mention {id: 'm4', resolvedId: 'entX', role: 'SUBJECT'})
            CREATE (p1)-[:HAS_MENTION]->(m1)
            CREATE (p2)-[:HAS_MENTION]->(m2)
            CREATE (p3)-[:HAS_MENTION]->(m3)
            CREATE (p4)-[:HAS_MENTION]->(m4)
            """.trimIndent()
        )
    )
}

@Testcontainers
class ListMembershipNeo4jTest {
    companion object {
        private const val PASSWORD = "listmembertest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: GraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-listmember", type = DatabaseType.NEO4J,
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
    fun `hasItem load and vector paths on Neo4j`() = verify(gom)
}

@Testcontainers
class ListMembershipFalkorDbTest {
    companion object {
        private const val GRAPH = "listmembertest"

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
                name = "falkor-listmember", host = container.host, port = container.getMappedPort(6379),
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
    fun `hasItem load and vector paths on FalkorDB`() = verify(gom)
}

@Testcontainers
class ListMembershipMemgraphTest {
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
                name = "memgraph-listmember", type = DatabaseType.MEMGRAPH,
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
    fun `hasItem load and vector paths on Memgraph`() = verify(gom)
}