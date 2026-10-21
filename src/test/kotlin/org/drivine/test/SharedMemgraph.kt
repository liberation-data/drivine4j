package org.drivine.test

import org.drivine.connection.DatabaseType
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.query.grammar.CypherDialect
import org.testcontainers.containers.GenericContainer

/**
 * One Memgraph for the test JVM, which the cross-engine test classes share in place of a container
 * of their own each: see [SharedNeo4j]. It is the image the classes started for themselves,
 * `memgraph/memgraph:latest` as `gradle/test-images.txt` pins it. [MemgraphTestContainer] is the one
 * the Spring tests use.
 */
object SharedMemgraph {

    private val container: GenericContainer<*> by lazy { sharedContainer("memgraph/memgraph:latest", 7687) }

    /**
     * The shared container, its graph emptied by [TestCleanup.resetGraph] for the class that asks:
     * each class calls this once, from its companion, before anything of its own runs.
     */
    @JvmStatic
    fun forClass(): GenericContainer<*> {
        withManager(TestCleanup::resetGraph)
        return container
    }

    /** Runs [use] with an auto-commit manager on the shared container. */
    internal fun <T> withManager(use: (NonTransactionalPersistenceManager) -> T): T =
        overBolt(container, DatabaseType.MEMGRAPH, CypherDialect.MEMGRAPH, "", "", null, use)
}
