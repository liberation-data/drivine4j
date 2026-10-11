package org.drivine.test

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * Oracle Database Free in a test container, for integration tests.
 *
 * Tests connect as [USER], an ordinary user of the pluggable database [SERVICE]: the administrators'
 * tablespace cannot hold a JSON column, which a graph's tables have. The JDBC driver is the
 * application's to add: `com.oracle.database.jdbc:ojdbc11`.
 *
 * ## Usage
 *
 * ```kotlin
 * val provider = JdbcConnectionProvider.pooled(
 *     "graph", DatabaseType.ORACLE, OracleTestContainer.getJdbcUrl(),
 *     OracleTestContainer.getConnectionUsername(), OracleTestContainer.getConnectionPassword(),
 * )
 * ```
 *
 * ### The image
 *
 * `gvenzl/oracle-free:23-faststart` unless told otherwise, with `ORACLE_TEST_IMAGE` or
 * `-Dtest.oracle.image`. It is the regular image and not the slim one, which is little smaller and has
 * no Oracle Text.
 *
 * ### Using a local Oracle instance
 *
 * ```bash
 * export USE_LOCAL_ORACLE=true
 * export ORACLE_LOCAL_HOST=localhost
 * export ORACLE_LOCAL_PORT=1521
 * ```
 *
 * Or `-Dtest.oracle.use-local=true`. The service, user and password are then the datasource's own.
 */
class OracleTestContainer private constructor(
    imageName: DockerImageName
) : GenericContainer<OracleTestContainer>(imageName) {

    companion object {
        private const val USE_LOCAL_PROPERTY = "test.oracle.use-local"
        private const val LOCAL_HOST_PROPERTY = "test.oracle.local.host"
        private const val LOCAL_PORT_PROPERTY = "test.oracle.local.port"
        private const val IMAGE_PROPERTY = "test.oracle.image"

        private const val USE_LOCAL_ENV = "USE_LOCAL_ORACLE"
        private const val LOCAL_HOST_ENV = "ORACLE_LOCAL_HOST"
        private const val LOCAL_PORT_ENV = "ORACLE_LOCAL_PORT"
        private const val IMAGE_ENV = "ORACLE_TEST_IMAGE"

        private const val ORACLE_IMAGE = "gvenzl/oracle-free:23-faststart"
        private const val ORACLE_PORT = 1521

        /** The pluggable database every Oracle Free image opens. */
        const val SERVICE = "FREEPDB1"
        const val USER = "drivine"
        const val PASSWORD = "drivine"

        @JvmStatic
        val instance: OracleTestContainer? by lazy {
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
                instance?.host ?: throw IllegalStateException("Oracle test container not initialized")
            }
        }

        @JvmStatic
        fun getConnectionPort(): Int {
            return if (useLocal()) {
                (System.getProperty(LOCAL_PORT_PROPERTY)
                    ?: System.getenv(LOCAL_PORT_ENV)
                    ?: "1521").toInt()
            } else {
                instance?.getMappedPort(ORACLE_PORT)
                    ?: throw IllegalStateException("Oracle test container not initialized")
            }
        }

        @JvmStatic
        fun getConnectionUsername(): String = USER

        @JvmStatic
        fun getConnectionPassword(): String = PASSWORD

        /** The thin-driver URL of the container's service, or of the local instance's [SERVICE]. */
        @JvmStatic
        fun getJdbcUrl(): String = "jdbc:oracle:thin:@//${getConnectionHost()}:${getConnectionPort()}/$SERVICE"

        private fun createTestContainer(): OracleTestContainer {
            val image = System.getProperty(IMAGE_PROPERTY) ?: System.getenv(IMAGE_ENV) ?: ORACLE_IMAGE
            val container = OracleTestContainer(DockerImageName.parse(image))
                .withExposedPorts(ORACLE_PORT)
                .withEnv("ORACLE_PASSWORD", PASSWORD)
                .withEnv("APP_USER", USER)
                .withEnv("APP_USER_PASSWORD", PASSWORD)
                // The port listens before the pluggable database is open and the user made.
                .waitingFor(Wait.forLogMessage(".*DATABASE IS READY TO USE!.*\\s", 1))
                .withStartupTimeout(Duration.ofMinutes(5))

            container.start()
            return container
        }
    }
}
