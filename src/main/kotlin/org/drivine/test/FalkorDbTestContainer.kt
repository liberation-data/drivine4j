package org.drivine.test

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

class FalkorDbTestContainer private constructor(
    imageName: DockerImageName
) : GenericContainer<FalkorDbTestContainer>(imageName) {

    companion object {
        private const val USE_LOCAL_PROPERTY = "test.falkordb.use-local"
        private const val LOCAL_HOST_PROPERTY = "test.falkordb.local.host"
        private const val LOCAL_PORT_PROPERTY = "test.falkordb.local.port"

        private const val USE_LOCAL_ENV = "USE_LOCAL_FALKORDB"
        private const val LOCAL_HOST_ENV = "FALKORDB_LOCAL_HOST"
        private const val LOCAL_PORT_ENV = "FALKORDB_LOCAL_PORT"

        private const val FALKORDB_IMAGE = "falkordb/falkordb:latest"
        private const val FALKORDB_PORT = 6379

        @JvmStatic
        val instance: FalkorDbTestContainer? by lazy {
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
                instance?.host ?: throw IllegalStateException("FalkorDB test container not initialized")
            }
        }

        @JvmStatic
        fun getConnectionPort(): Int {
            return if (useLocal()) {
                (System.getProperty(LOCAL_PORT_PROPERTY)
                    ?: System.getenv(LOCAL_PORT_ENV)
                    ?: "6379").toInt()
            } else {
                instance?.getMappedPort(FALKORDB_PORT)
                    ?: throw IllegalStateException("FalkorDB test container not initialized")
            }
        }

        private fun createTestContainer(): FalkorDbTestContainer {
            val container = FalkorDbTestContainer(DockerImageName.parse(FALKORDB_IMAGE))
                .withExposedPorts(FALKORDB_PORT)

            container.start()
            return container
        }
    }
}