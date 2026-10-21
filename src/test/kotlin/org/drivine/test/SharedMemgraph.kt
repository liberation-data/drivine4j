package org.drivine.test

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * One Memgraph for the test JVM, which the cross-engine test classes share in place of a container
 * of their own each: see [SharedNeo4j]. It is the image the classes started for themselves,
 * `memgraph/memgraph:latest` as `gradle/test-images.txt` pins it. [MemgraphTestContainer] is the one
 * the Spring tests use.
 */
object SharedMemgraph {

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(5)))
            .withStartupAttempts(2)
            .also { it.start() }
    }

    /**
     * The shared container, its graph emptied by [TestCleanup.resetGraph] for the class that asks:
     * each class calls this once, from its companion, before anything of its own runs.
     */
    @JvmStatic
    fun forClass(): GenericContainer<*> {
        val provider = Neo4jConnectionProvider(
            name = "shared-memgraph-reset", type = DatabaseType.MEMGRAPH,
            host = container.host, port = container.getMappedPort(7687),
            user = "", password = "", database = null, config = emptyMap(),
            cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = SubtypeRegistry(),
        )
        try {
            TestCleanup.resetGraph(NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, SubtypeRegistry()))
        } finally {
            provider.end()
        }
        return container
    }
}
