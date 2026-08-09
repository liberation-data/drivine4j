package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.flatexpand.ElemChainView
import sample.flatexpand.ImageElem
import sample.flatexpand.SeqExpandView
import sample.flatexpand.SeqExpandViewQueryDsl
import sample.flatexpand.SeqOneHopView
import sample.flatexpand.SeqWindow2View
import sample.flatexpand.TextElem
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `maxDepth` on a flat `List<Fragment>` relationship — a variable-length `*1..N` traversal returning
 * the transitive N-hop neighbourhood as a de-duplicated flat list. Verified on Neo4j, FalkorDB, and
 * Memgraph.
 */
private fun verify(gom: GraphObjectManager, pm: NonTransactionalPersistenceManager) {
    // Chain c1 → c2 → c3 → c4 → c5, plus a diamond a → {b,c} → d, and a polymorphic chain.
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (c1:Seq {id:'c1'}), (c2:Seq {id:'c2'}), (c3:Seq {id:'c3'}), (c4:Seq {id:'c4'}), (c5:Seq {id:'c5'})
            CREATE (c1)-[:NEXT]->(c2), (c2)-[:NEXT]->(c3), (c3)-[:NEXT]->(c4), (c4)-[:NEXT]->(c5)
            CREATE (a:Seq {id:'a'}), (b:Seq {id:'b'}), (bc:Seq {id:'bc'}), (dd:Seq {id:'dd'})
            CREATE (a)-[:NEXT]->(b), (a)-[:NEXT]->(bc), (b)-[:NEXT]->(dd), (bc)-[:NEXT]->(dd)
            CREATE (h:ChainHead {id:'h'}), (t1:Elem:TextElem {id:'t1', body:'mid'}), (i1:Elem:ImageElem {id:'i1', url:'u'})
            CREATE (h)-[:FOLLOWED_BY]->(t1), (t1)-[:FOLLOWED_BY]->(i1)
            """.trimIndent()
        )
    )

    // ----- Full multi-hop set, forward and backward, flat -----
    val mid = gom.loadAll(SeqExpandView::class.java, "node.id = 'c3'").single()
    assertEquals(setOf("c4", "c5"), mid.following.map { it.id }.toSet(), "following = all within 1..10 hops")
    assertEquals(setOf("c1", "c2"), mid.preceding.map { it.id }.toSet(), "preceding = incoming *1..10")

    // ----- maxDepth bounds the traversal -----
    val windowed = gom.loadAll(SeqWindow2View::class.java, "node.id = 'c1'").single()
    assertEquals(setOf("c2", "c3"), windowed.following.map { it.id }.toSet(), "maxDepth=2 truncates before c4/c5")

    // ----- De-duplication: a node reachable by two paths appears once -----
    val fromA = gom.loadAll(SeqExpandView::class.java, "node.id = 'a'").single()
    assertEquals(setOf("b", "bc", "dd"), fromA.following.map { it.id }.toSet())
    assertEquals(1, fromA.following.count { it.id == "dd" }, "the diamond's far node appears once")

    // ----- depth() runtime override tightens the window -----
    val overridden = gom.loadAll(SeqExpandView::class.java, SeqExpandViewQueryDsl.INSTANCE) {
        where { query.node.id eq "c1" }
        depth("following", 2)
    }.single()
    assertEquals(setOf("c2", "c3"), overridden.following.map { it.id }.toSet(), "depth('following',2) overrides maxDepth=10")

    // ----- Polymorphic flat list: each reached node dispatched by type -----
    val chain = gom.loadAll(ElemChainView::class.java, "head.id = 'h'").single()
    assertEquals(setOf("t1", "i1"), chain.rest.map { it.id }.toSet())
    assertTrue(chain.rest.single { it.id == "t1" } is TextElem)
    assertTrue(chain.rest.single { it.id == "i1" } is ImageElem)

    // ----- Regression: default maxDepth=1 flat list is still a single hop -----
    val oneHop = gom.loadAll(SeqOneHopView::class.java, "node.id = 'c1'").single()
    assertEquals(setOf("c2"), oneHop.next.map { it.id }.toSet(), "maxDepth default = 1 returns only the direct neighbour")
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class FlatVariableLengthNeo4jTest {
    companion object {
        private const val PW = "flatexpandtest"

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
                name = "neo-flat", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PW, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `flat variable-length list on Neo4j`() = verify(buildGom(pm, registry), pm)
}

@Testcontainers
class FlatVariableLengthFalkorDbTest {
    companion object {
        private const val GRAPH = "flatexpandtest"

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
                name = "falkor-flat", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `flat variable-length list on FalkorDB`() = verify(buildGom(pm, registry), pm)
}

@Testcontainers
class FlatVariableLengthMemgraphTest {
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
                name = "memgraph-flat", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `flat variable-length list on Memgraph`() = verify(buildGom(pm, registry), pm)
}
