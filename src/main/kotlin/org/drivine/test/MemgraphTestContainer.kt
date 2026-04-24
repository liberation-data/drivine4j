package org.drivine.test

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Memgraph test container for integration tests.
 *
 * Memgraph speaks Bolt natively, so we reuse [org.drivine.connection.Neo4jConnection] —
 * only the connection's [org.drivine.connection.DatabaseType] tag and [org.drivine.query.grammar.CypherDialect]
 * differ.
 *
 * ## Usage
 *
 * ### Default (Testcontainers)
 * ```kotlin
 * @Testcontainers
 * class MyTest {
 *     companion object {
 *         @JvmField @Container val container = MemgraphTestContainer.instance
 *     }
 * }
 * ```
 *
 * ### Using Local Memgraph Instance
 *
 * ```bash
 * export USE_LOCAL_MEMGRAPH=true
 * export MEMGRAPH_LOCAL_HOST=localhost
 * export MEMGRAPH_LOCAL_PORT=7687
 * ```
 *
 * Or via system properties:
 * ```bash
 * ./gradlew test -Dtest.memgraph.use-local=true
 * ```
 */
class MemgraphTestContainer private constructor(
    imageName: DockerImageName
) : GenericContainer<MemgraphTestContainer>(imageName) {

    companion object {
        private const val USE_LOCAL_PROPERTY = "test.memgraph.use-local"
        private const val LOCAL_HOST_PROPERTY = "test.memgraph.local.host"
        private const val LOCAL_PORT_PROPERTY = "test.memgraph.local.port"

        private const val USE_LOCAL_ENV = "USE_LOCAL_MEMGRAPH"
        private const val LOCAL_HOST_ENV = "MEMGRAPH_LOCAL_HOST"
        private const val LOCAL_PORT_ENV = "MEMGRAPH_LOCAL_PORT"

        // memgraph/memgraph is the lean image (~200MB) — sufficient for CRUD, queries, and
        // transactions. If MAGE or Memgraph Lab are needed, switch to memgraph-platform.
        private const val MEMGRAPH_IMAGE = "memgraph/memgraph:latest"
        private const val MEMGRAPH_BOLT_PORT = 7687

        @JvmStatic
        val instance: MemgraphTestContainer? by lazy {
            if (useLocal()) null else createTestContainer()
        }

        @JvmStatic
        fun useLocal(): Boolean {
            return System.getProperty(USE_LOCAL_PROPERTY, "false").toBoolean() ||
                   System.getenv(USE_LOCAL_ENV)?.toBoolean() ?: false
        }

        @JvmStatic
        fun getConnectionHost(): String {
            return if (useLocal()) {
                System.getProperty(LOCAL_HOST_PROPERTY)
                    ?: System.getenv(LOCAL_HOST_ENV)
                    ?: "localhost"
            } else {
                instance?.host ?: throw IllegalStateException("Memgraph test container not initialized")
            }
        }

        @JvmStatic
        fun getConnectionPort(): Int {
            return if (useLocal()) {
                (System.getProperty(LOCAL_PORT_PROPERTY)
                    ?: System.getenv(LOCAL_PORT_ENV)
                    ?: "7687").toInt()
            } else {
                instance?.getMappedPort(MEMGRAPH_BOLT_PORT)
                    ?: throw IllegalStateException("Memgraph test container not initialized")
            }
        }

        private fun createTestContainer(): MemgraphTestContainer {
            val container = MemgraphTestContainer(DockerImageName.parse(MEMGRAPH_IMAGE))
                .withExposedPorts(MEMGRAPH_BOLT_PORT)
                // Memgraph logs "You are running Memgraph" when Bolt is ready
                .waitingFor(Wait.forLogMessage(".*You are running Memgraph.*", 1))

            container.start()
            return container
        }
    }
}