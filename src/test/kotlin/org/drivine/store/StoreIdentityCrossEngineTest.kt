package org.drivine.store

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [StoreIdentity] verified on Neo4j, FalkorDB and Memgraph — the claim being that an identity is a
 * property of the DATA, not of the client that read it or the address it was reached at.
 *
 * Each engine runs [verifyStableAcrossClients] and [verifyEmptiedStoreIsANewStore]; FalkorDB adds
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
}
