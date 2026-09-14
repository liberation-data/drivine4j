package org.drivine.store

import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.NonTransactionalPersistenceManager
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
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [StoreIdentity] verified on Neo4j, FalkorDB and Memgraph — the claim being that an identity is a
 * property of the DATA, not of the client that read it or the address it was reached at.
 *
 * Each engine runs [verifyStableAcrossClients], [verifyEmptiedStoreIsANewStore],
 * [verifyConcurrentFirstBootsAgree] and [verifyExistingDuplicatesStillResolve]; Neo4j and Memgraph add
 * [verifyInFlightPeerStampIsNotDuplicated]; FalkorDB adds
 * [verifyDistinctStoresDiffer], since two graphs on one container are two stores without needing a
 * second container or an enterprise licence.
 */
private fun verifyStableAcrossClients(open: () -> NonTransactionalPersistenceManager) {
    val first = open().storeIdentity

    // A second manager with its own cache, over the same store: same answer. If the identity were
    // process state rather than stored state, these would differ — which is the whole failure mode.
    val second = open().storeIdentity
    assertEquals(first.id, second.id, "same store must report one identity")
    assertEquals(first.assignedAt, second.assignedAt, "assignment happens once, not per client")

    UUID.fromString(first.id) // minted as a UUID, not derived from anything guessable
    assertEquals(open().type, first.engine, "engine reported is the one actually connected")
}

/**
 * Wiping a store mints a NEW identity, and that is correct rather than unfortunate.
 *
 * This is precisely the incident the primitive exists for: a process pointed at an empty store
 * cannot tell "fresh install" from "wrong database" by looking at emptiness — but an application
 * holding the identity its data was stamped with can, because the empty store answers with an id
 * that does not match.
 */
private fun verifyEmptiedStoreIsANewStore(
    open: () -> NonTransactionalPersistenceManager,
    wipe: () -> Unit,
) {
    val before = open().storeIdentity
    wipe()
    val after = open().storeIdentity
    assertNotEquals(before.id, after.id, "an emptied store is not the store that was stamped")
}

/** Two stores on one server: same host, same port, same credentials, different data. */
private fun verifyDistinctStoresDiffer(
    open: () -> NonTransactionalPersistenceManager,
    openOther: () -> NonTransactionalPersistenceManager,
) {
    assertNotEquals(
        open().storeIdentity.id,
        openOther().storeIdentity.id,
        "identity must distinguish stores that share an address",
    )
}

/**
 * Many processes booting against a virgin store at once must agree on ONE identity and leave ONE stamp.
 *
 * Each worker gets its own manager, so nothing is shared but the store — the replicas-starting-together
 * shape. Without the uniqueness constraint, two MERGEs can each create a node, and a worker that read
 * before the other write landed caches a different id from one that read after.
 */
private fun verifyConcurrentFirstBootsAgree(open: () -> NonTransactionalPersistenceManager) {
    val workers = 8
    val managers = List(workers) { open() }
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(workers)
    try {
        val futures = managers.map { manager ->
            pool.submit<String> {
                start.await()
                manager.storeIdentity.id
            }
        }
        start.countDown()
        val ids = futures.map { it.get(60, TimeUnit.SECONDS) }.toSet()
        assertEquals(1, ids.size, "concurrent first boots must agree on one identity, got $ids")
    } finally {
        pool.shutdownNow()
    }
    val stamps = open().query(
        QuerySpecification.withStatement("MATCH (s:DrivineStore) RETURN count(s) AS n").transform<Long>()
    ).single()
    assertEquals(1L, stamps, "the constraint must stop a second stamp from existing")
}

/**
 * A store that already carries duplicates — from a race before the constraint existed — cannot take the
 * constraint. Resolution must still succeed, and pick the oldest stamp, rather than fail boot.
 */
private fun verifyExistingDuplicatesStillResolve(open: () -> NonTransactionalPersistenceManager) {
    open().constraints.drop(StoreIdentityResolver.KEY_CONSTRAINT)
    val older = UUID.randomUUID().toString()
    val newer = UUID.randomUUID().toString()
    open().execute(
        QuerySpecification.withStatement(
            """
            CREATE (:DrivineStore { key: 'singleton', storeId: ${'$'}newer, assignedAt: '2026-02-01T00:00:00Z' })
            CREATE (:DrivineStore { key: 'singleton', storeId: ${'$'}older, assignedAt: '2026-01-01T00:00:00Z' })
            """
        ).bind(mapOf("older" to older, "newer" to newer))
    )
    assertEquals(older, open().storeIdentity.id, "duplicates resolve to the oldest stamp")
}

/**
 * Forces the interleaving that the thread-pool test can only hope for: a peer's stamp is written but not
 * yet committed while [StoreIdentity] resolves. Without uniqueness the resolver cannot see the pending
 * node, mints its own, and caches it — two stamps, two answers. With it, the engine blocks or rejects one
 * writer, and exactly one stamp survives, which is the one the resolver reports.
 *
 * Needs interactive transactions, so FalkorDB (which serializes writes per graph and cannot race here)
 * is not covered.
 */
private fun verifyInFlightPeerStampIsNotDuplicated(
    provider: ConnectionProvider,
    open: () -> NonTransactionalPersistenceManager,
    wipe: () -> Unit,
) {
    open().storeIdentity // ensures the constraint, as any earlier boot of this build would have
    wipe()

    val peer = provider.connect()
    peer.startTransaction()
    peer.query(
        QuerySpecification.withStatement(
            "MERGE (s:DrivineStore { key: 'singleton' }) ON CREATE SET s.storeId = ${'$'}id, s.assignedAt = ${'$'}at"
        ).bind(mapOf("id" to UUID.randomUUID().toString(), "at" to "2026-01-01T00:00:00Z"))
    )

    val pool = Executors.newSingleThreadExecutor()
    try {
        val resolved = pool.submit<String> { open().storeIdentity.id }
        Thread.sleep(150) // let the resolver reach its MERGE while the peer's write is still pending
        runCatching { peer.commitTransaction() }.onFailure { runCatching { peer.rollbackTransaction() } }
        peer.release()

        val id = resolved.get(60, TimeUnit.SECONDS)
        val stored = open().query(
            QuerySpecification.withStatement("MATCH (s:DrivineStore) RETURN s.storeId").transform<String>()
        )
        assertEquals(listOf(id), stored, "one stamp must survive, and it must be the one reported")
    } finally {
        pool.shutdownNow()
    }
}

@Testcontainers
class StoreIdentityNeo4jTest {
    companion object {
        private const val PASSWORD = "storeidtest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        lateinit var provider: Neo4jConnectionProvider

        @JvmStatic @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "neo-store-id", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = SubtypeRegistry(), cypherDialect = CypherDialect.NEO4J_5,
            )
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    private fun open() =
        NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, SubtypeRegistry())

    private fun wipe() {
        open().execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    @BeforeEach fun clean() = wipe()

    @Test fun `identity is stable across clients on Neo4j`() = verifyStableAcrossClients(::open)

    @Test fun `an emptied Neo4j store reports a new identity`() =
        verifyEmptiedStoreIsANewStore(::open, ::wipe)

    @Test fun `concurrent first boots agree on one identity on Neo4j`() =
        verifyConcurrentFirstBootsAgree({ open() })

    @Test fun `existing duplicate stamps still resolve on Neo4j`() =
        verifyExistingDuplicatesStillResolve({ open() })

    @Test fun `an in-flight peer stamp is not duplicated on Neo4j`() =
        verifyInFlightPeerStampIsNotDuplicated(provider, { open() }, ::wipe)

    /**
     * Repository-style code usually holds only a [GraphObjectManager]. It must reach the same
     * identity as the manager underneath it — a second, independently obtained answer is exactly
     * the drift this primitive exists to rule out.
     */
    @Test
    fun `a GraphObjectManager reports its manager's identity`() {
        val pm = open()
        val mapper = Neo4jObjectMapper.instance
        val gom = GraphObjectManager(pm, SessionManager(mapper), mapper, SubtypeRegistry())

        assertEquals(pm.storeIdentity, gom.storeIdentity)
        assertEquals(pm.database, gom.database)
    }
}

@Testcontainers
class StoreIdentityFalkorDbTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        val providers = mutableListOf<FalkorDbConnectionProvider>()

        fun provider(graph: String): FalkorDbConnectionProvider =
            FalkorDbConnectionProvider(
                name = "falkor-store-id-$graph", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = graph, subtypeRegistry = SubtypeRegistry(),
            ).also { providers += it }

        @JvmStatic @AfterAll
        fun teardown() = providers.forEach { it.end() }
    }

    private fun open(graph: String = "storeidtest") =
        NonTransactionalPersistenceManager(provider(graph), graph, DatabaseType.FALKORDB, SubtypeRegistry())

    private fun wipe() {
        open().execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    @BeforeEach fun clean() = wipe()

    @Test fun `identity is stable across clients on FalkorDB`() = verifyStableAcrossClients(::open)

    @Test fun `an emptied FalkorDB store reports a new identity`() =
        verifyEmptiedStoreIsANewStore(::open, ::wipe)

    @Test fun `concurrent first boots agree on one identity on FalkorDB`() =
        verifyConcurrentFirstBootsAgree({ open() })

    @Test fun `existing duplicate stamps still resolve on FalkorDB`() =
        verifyExistingDuplicatesStillResolve({ open() })

    @Test fun `two graphs on one FalkorDB server are two stores`() =
        verifyDistinctStoresDiffer({ open() }, { open("storeidtest-other") })
}

@Testcontainers
class StoreIdentityMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        lateinit var provider: Neo4jConnectionProvider

        @JvmStatic @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "memgraph-store-id", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = SubtypeRegistry(),
            )
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    private fun open() =
        NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, SubtypeRegistry())

    private fun wipe() {
        open().execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    @BeforeEach fun clean() = wipe()

    @Test fun `identity is stable across clients on Memgraph`() = verifyStableAcrossClients(::open)

    @Test fun `an emptied Memgraph store reports a new identity`() =
        verifyEmptiedStoreIsANewStore(::open, ::wipe)

    @Test fun `concurrent first boots agree on one identity on Memgraph`() =
        verifyConcurrentFirstBootsAgree({ open() })

    @Test fun `existing duplicate stamps still resolve on Memgraph`() =
        verifyExistingDuplicatesStillResolve({ open() })

    @Test fun `an in-flight peer stamp is not duplicated on Memgraph`() =
        verifyInFlightPeerStampIsNotDuplicated(provider, { open() }, ::wipe)
}
