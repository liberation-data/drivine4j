package org.drivine.test

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * Postgres, with pgvector, in a test container, for integration tests.
 *
 * Tests connect as [USER] to the database [DATABASE]. The JDBC driver is the application's to add:
 * `org.postgresql:postgresql`.
 *
 * ## Usage
 *
 * ```kotlin
 * val provider = JdbcConnectionProvider.pooled(
 *     "graph", DatabaseType.POSTGRES, PostgresTestContainer.getJdbcUrl(),
 *     PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword(),
 * )
 * ```
 *
 * ### The image
 *
 * `pgvector/pgvector:pg17` unless told otherwise, with `POSTGRES_TEST_IMAGE` or
 * `-Dtest.postgres.image`: Postgres with the pgvector extension installed, which vector search wants.
 *
 * ### Using a local Postgres instance
 *
 * ```bash
 * export USE_LOCAL_POSTGRES=true
 * export POSTGRES_LOCAL_HOST=localhost
 * export POSTGRES_LOCAL_PORT=5432
 * ```
 *
 * Or `-Dtest.postgres.use-local=true`. The local instance must hold the database [DATABASE] and the
 * user [USER] with password [PASSWORD], as the container does: only its host and port can be changed.
 */
class PostgresTestContainer private constructor(
    imageName: DockerImageName
) : GenericContainer<PostgresTestContainer>(imageName) {

    companion object {
        private const val USE_LOCAL_PROPERTY = "test.postgres.use-local"
        private const val LOCAL_HOST_PROPERTY = "test.postgres.local.host"
        private const val LOCAL_PORT_PROPERTY = "test.postgres.local.port"
        private const val IMAGE_PROPERTY = "test.postgres.image"

        private const val USE_LOCAL_ENV = "USE_LOCAL_POSTGRES"
        private const val LOCAL_HOST_ENV = "POSTGRES_LOCAL_HOST"
        private const val LOCAL_PORT_ENV = "POSTGRES_LOCAL_PORT"
        private const val IMAGE_ENV = "POSTGRES_TEST_IMAGE"

        private const val POSTGRES_IMAGE = "pgvector/pgvector:pg17"
        private const val POSTGRES_PORT = 5432

        /** The database the container makes, and tests connect to. */
        const val DATABASE = "drivine"

        /** The user the container makes, and tests connect as. */
        const val USER = "drivine"

        /** The password of [USER]. */
        const val PASSWORD = "drivine"

        /** The container, started when it is first asked for; null when a local instance is used in its place. */
        @JvmStatic
        val instance: PostgresTestContainer? by lazy {
            if (useLocal()) null else createTestContainer()
        }

        /** Whether tests speak to a local instance and start no container: `-Dtest.postgres.use-local=true` or `USE_LOCAL_POSTGRES=true`. */
        @JvmStatic
        fun useLocal(): Boolean {
            return System.getProperty(USE_LOCAL_PROPERTY, "false").toBoolean() ||
                   System.getenv(USE_LOCAL_ENV)?.toBoolean() ?: false
        }

        /** The container's host, or the local instance's: `-Dtest.postgres.local.host`, else `POSTGRES_LOCAL_HOST`, else `localhost`. */
        @JvmStatic
        fun getConnectionHost(): String {
            return if (useLocal()) {
                System.getProperty(LOCAL_HOST_PROPERTY)
                    ?: System.getenv(LOCAL_HOST_ENV)
                    ?: "localhost"
            } else {
                instance?.host ?: throw IllegalStateException("Postgres test container not initialized")
            }
        }

        /** The port the container's server is mapped to, or the local instance's: `-Dtest.postgres.local.port`, else `POSTGRES_LOCAL_PORT`, else 5432. */
        @JvmStatic
        fun getConnectionPort(): Int {
            return if (useLocal()) {
                (System.getProperty(LOCAL_PORT_PROPERTY)
                    ?: System.getenv(LOCAL_PORT_ENV)
                    ?: "5432").toInt()
            } else {
                instance?.getMappedPort(POSTGRES_PORT)
                    ?: throw IllegalStateException("Postgres test container not initialized")
            }
        }

        /** The user tests connect as, [USER], in the container and in a local instance alike. */
        @JvmStatic
        fun getConnectionUsername(): String = USER

        /** The password of that user, [PASSWORD], in the container and in a local instance alike. */
        @JvmStatic
        fun getConnectionPassword(): String = PASSWORD

        /** The JDBC URL of [DATABASE] in the container, or in the local instance. */
        @JvmStatic
        fun getJdbcUrl(): String = "jdbc:postgresql://${getConnectionHost()}:${getConnectionPort()}/$DATABASE"

        /**
         * Starts the container, and returns once the server takes connections: a minute at most. The
         * image's first start runs its setup on a server of its own and stops it, so the server is
         * ready when it has said so twice.
         */
        private fun createTestContainer(): PostgresTestContainer {
            val image = System.getProperty(IMAGE_PROPERTY) ?: System.getenv(IMAGE_ENV) ?: POSTGRES_IMAGE
            val container = PostgresTestContainer(DockerImageName.parse(image))
                .withExposedPorts(POSTGRES_PORT)
                .withEnv("POSTGRES_DB", DATABASE)
                .withEnv("POSTGRES_USER", USER)
                .withEnv("POSTGRES_PASSWORD", PASSWORD)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
                .withStartupTimeout(Duration.ofMinutes(1))

            container.start()
            return container
        }
    }
}
