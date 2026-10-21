package org.drivine.test

import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * One Neo4j for the test JVM, which the cross-engine test classes share in place of a container of
 * their own each. Neo4j takes several seconds to start, and a class each made the suite start one
 * sixty-odd times; on a busy machine one of those starts overran its wait and failed the class.
 *
 * It is the image the classes started for themselves, `neo4j:latest` as `gradle/test-images.txt`
 * pins it, without APOC: [DrivineTestContainer] is a different Neo4j, with APOC, for the Spring tests.
 * The container starts when a class first asks for it, and Testcontainers stops it when the JVM ends.
 */
object SharedNeo4j {

    /** The admin password the container is started with. */
    const val PASSWORD = "sharedneo4jtest"

    private val container: Neo4jContainer<*> by lazy {
        Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .withAdminPassword(PASSWORD)
            .withStartupTimeout(Duration.ofMinutes(5))
            .withStartupAttempts(2)
            .also { it.start() }
    }

    /**
     * The shared container, its graph emptied by [TestCleanup.resetGraph] for the class that asks:
     * each class calls this once, from its companion, before anything of its own runs.
     */
    @JvmStatic
    fun forClass(): Neo4jContainer<*> {
        val provider = Neo4jConnectionProvider(
            name = "shared-neo4j-reset", type = DatabaseType.NEO4J,
            host = container.host, port = container.getMappedPort(7687),
            user = "neo4j", password = PASSWORD, database = "neo4j",
            config = emptyMap(), subtypeRegistry = SubtypeRegistry(), cypherDialect = CypherDialect.NEO4J_5,
        )
        try {
            TestCleanup.resetGraph(NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, SubtypeRegistry()))
        } finally {
            provider.end()
        }
        return container
    }
}
