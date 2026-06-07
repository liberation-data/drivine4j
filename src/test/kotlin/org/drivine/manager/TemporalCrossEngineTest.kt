package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.transform
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-engine temporal consistency: every temporal type saved via `save()` must round-trip, be
 * range-queryable, and order chronologically on each backend — natively on Memgraph, as ISO strings
 * (via [org.drivine.query.TemporalCoercer]) on FalkorDB. The write path and the bound-param path
 * must agree per backend. Uses the shared [TemporalsNode] fixture (all java.time types).
 */
private fun verifyTemporal(gom: GraphObjectManager, pm: PersistenceManager) {
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    val base = TemporalsNode.at("ce")
    gom.save(base)
    gom.save(TemporalsNode.at("later", 10))

    // round-trips, all native temporal types (FalkorDB stores them as ISO strings, still parseable)
    val loaded = gom.load("ce", TemporalsNode::class.java)!!
    assertEquals(base.instant, loaded.instant)
    assertTrue(base.zoned.isEqual(loaded.zoned), "expected same instant, got ${loaded.zoned}")
    assertEquals(base.date, loaded.date)
    assertEquals(base.dateTime, loaded.dateTime)

    // range query with a bound Instant matches (and a future bound excludes)
    fun range(since: java.time.Instant) = pm.query(
        QuerySpecification.withStatement("MATCH (n:TemporalsNode) WHERE n.instant >= \$s RETURN n.id")
            .bind(mapOf("s" to since)).transform(String::class.java)
    )
    assertEquals(setOf("ce", "later"), range(base.instant.minusSeconds(60)).toSet())
    assertEquals(emptyList(), range(base.instant.plusSeconds(60)))

    // order by a temporal is chronological (native on Memgraph; lexicographic ISO on FalkorDB)
    val ordered = pm.query(
        QuerySpecification.withStatement("MATCH (n:TemporalsNode) RETURN n.id ORDER BY n.instant")
            .transform(String::class.java)
    )
    assertEquals(listOf("ce", "later"), ordered)
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class TemporalMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())
        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var gom: GraphObjectManager
        private lateinit var pm: NonTransactionalPersistenceManager
        @JvmStatic @BeforeAll fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider("mg-temporal", DatabaseType.MEMGRAPH, container.host,
                container.getMappedPort(7687), "", "", null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry)
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            gom = buildGom(pm, registry)
        }
        @JvmStatic @AfterAll fun teardown() = provider.end()
    }

    @Test fun `temporal round-trip and range on Memgraph`() = verifyTemporal(gom, pm)
}

@Testcontainers
class TemporalFalkorDbTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)
        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var gom: GraphObjectManager
        private lateinit var pm: NonTransactionalPersistenceManager
        @JvmStatic @BeforeAll fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider("falkor-temporal", container.host,
                container.getMappedPort(6379), null, "temporaltest", subtypeRegistry = registry)
            pm = NonTransactionalPersistenceManager(provider, "temporaltest", DatabaseType.FALKORDB, registry)
            gom = buildGom(pm, registry)
        }
        @JvmStatic @AfterAll fun teardown() = provider.end()
    }

    @BeforeEach fun ensureGraph() {
        pm.execute(QuerySpecification.withStatement("CREATE (:_Init {x: 1})"))
    }

    @Test fun `temporal round-trip and range on FalkorDB (ISO string via coercer)`() = verifyTemporal(gom, pm)
}