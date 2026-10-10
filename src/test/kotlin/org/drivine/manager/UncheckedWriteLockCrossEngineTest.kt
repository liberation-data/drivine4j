package org.drivine.manager

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
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
import sample.stateless.Claim
import sample.stateless.Human
import sample.stateless.HumanClaims

/**
 * A write that is not checked still says truly whether it changed the node, when another writer
 * changes the same node at the same moment. Verified on Neo4j, FalkorDB and Memgraph.
 *
 * Two writers save the same node at once, one with the text it has and one with another. A stamp
 * speaks for one state of the node's data: if both are handed the same token, one of them holds a
 * stamp for a text the node does not have, and its next checked save would pass over the other's.
 */
abstract class UncheckedWriteLockContract {

    abstract val pm: NonTransactionalPersistenceManager

    private val stateless: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    private fun run(cypher: String) = pm.execute(QuerySpecification.withStatement(cypher))

    private fun stored(id: String): Pair<String, String> = pm.query(
        QuerySpecification.withStatement("MATCH (c:Claim {id: \$id}) RETURN c.text + '|' + c.${Stamps.QUOTED}")
            .bind(mapOf("id" to id)).transform(String::class.java)
    ).single().let { it.substringBefore('|') to it.substringAfter('|').substringBefore(':') }

    @BeforeEach
    fun clean() {
        run("MATCH (n) DETACH DELETE n")
        runCatching { run(index) }
    }

    /** An index on the claim's id, so a write finds its node without reading every other. */
    abstract val index: String

    /**
     * Runs [write] from two writers at once, [ROUNDS] times, each round on a claim of its own that
     * holds [SAME]: one writes [SAME] again, the other [OTHER]. [write] returns the stamp it was handed.
     */
    private fun race(write: (id: String, text: String, writer: Int) -> String?) {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(ROUNDS) { round ->
                val id = "c$round"
                stateless.save(Claim(id, SAME))
                val barrier = CyclicBarrier(2)
                val handed = listOf(SAME, OTHER).mapIndexed { writer, text ->
                    pool.submit<Pair<String, String>> {
                        barrier.await(30, TimeUnit.SECONDS)
                        text to assertNotNull(write(id, text, writer)).substringBefore(':')
                    }
                }.map { it.get(60, TimeUnit.SECONDS) }

                val (text, token) = stored(id)
                val texts = (handed + (text to token)).groupBy({ it.second }, { it.first }).mapValues { it.value.toSet() }
                assertEquals(emptyMap(), texts.filterValues { it.size > 1 }, "round $round: one token was handed out for two texts")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `two saves of a node at once are not handed one token for two texts`() =
        race { id, text, _ -> stateless.save(Claim(id, text)).stamp }

    @Test
    fun `two batches that save a node at once are not handed one token for two texts`() =
        race { id, text, _ -> stateless.saveAll(listOf(Claim(id, text))).single().stamp }

    @Test
    fun `two views that save a related node at once are not handed one token for two texts`() =
        race { id, text, writer ->
            stateless.save(HumanClaims(Human("h$writer-$id", "Human"), claims = listOf(Claim(id, text)))).claims.single().stamp
        }

    private companion object {
        const val ROUNDS = 300
        const val SAME = "as it was"
        const val OTHER = "changed"
    }
}

@Testcontainers
class UncheckedWriteLockNeo4jTest : UncheckedWriteLockContract() {
    companion object {
        private const val PASSWORD = "uncheckedlock"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-unchecked-lock", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
    override val index = "CREATE INDEX claim_id IF NOT EXISTS FOR (c:Claim) ON (c.id)"
}

@Testcontainers
class UncheckedWriteLockFalkorDbTest : UncheckedWriteLockContract() {
    companion object {
        private const val GRAPH = "uncheckedlock"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-unchecked-lock", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
    override val index = "CREATE INDEX FOR (c:Claim) ON (c.id)"
}

@Testcontainers
class UncheckedWriteLockMemgraphTest : UncheckedWriteLockContract() {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-unchecked-lock", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
    override val index = "CREATE INDEX ON :Claim(id)"
}
