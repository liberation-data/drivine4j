package org.drivine.test

import org.testcontainers.containers.Neo4jContainer
import java.nio.file.Paths

/**
 * Drivine test container for graph databases.
 *
 * Currently supports Neo4j, but designed to support multiple graph databases in the future
 * (following the pattern of Drivine TypeScript which supports Neo4j, ArangoDB, and others).
 *
 * ## Usage in Tests
 *
 * ### Default (Testcontainers)
 * ```kotlin
 * @SpringBootTest
 * class MyTest {
 *     companion object {
 *         @JvmField
 *         @Container
 *         val container = DrivineTestContainer.instance
 *     }
 * }
 * ```
 *
 * ### Using Local Neo4j Instance
 *
 * Set environment variable or system property:
 * ```bash
 * # Environment variable
 * export USE_LOCAL_NEO4J=true
 *
 * # Or system property
 * ./gradlew test -Dtest.neo4j.use-local=true
 * ```
 *
 * Configure connection (optional, defaults shown):
 * ```bash
 * export NEO4J_LOCAL_URL=bolt://localhost:7687
 * export NEO4J_LOCAL_USERNAME=neo4j
 * export NEO4J_LOCAL_PASSWORD=brahmsian
 * ```
 *
 * Or via system properties:
 * ```bash
 * ./gradlew test \
 *   -Dtest.neo4j.use-local=true \
 *   -Dtest.neo4j.local.url=bolt://localhost:7687 \
 *   -Dtest.neo4j.local.username=neo4j \
 *   -Dtest.neo4j.local.password=mypassword
 * ```
 *
 * ## Spring Configuration
 *
 * ```kotlin
 * @Configuration
 * class TestConfig {
 *     @Bean
 *     fun dataSourceMap(): DataSourceMap {
 *         val props = ConnectionProperties(
 *             url = DrivineTestContainer.getConnectionUrl(),
 *             username = DrivineTestContainer.getConnectionUsername(),
 *             password = DrivineTestContainer.getConnectionPassword()
 *         )
 *         return DataSourceMap(mapOf("neo" to props))
 *     }
 * }
 * ```
 *
 * ## Future: Multi-Database Support
 *
 * When support for other graph databases is added, you'll be able to specify the database type:
 * ```kotlin
 * DrivineTestContainer.forDatabase(GraphDatabase.ARANGO_DB)
 * DrivineTestContainer.forDatabase(GraphDatabase.NEO4J) // Default
 * ```
 */
class DrivineTestContainer private constructor(imageName: String) : Neo4jContainer<DrivineTestContainer>(imageName) {

    companion object {
        private const val USE_LOCAL_NEO4J_PROPERTY = "test.neo4j.use-local"
        private const val LOCAL_NEO4J_URL_PROPERTY = "test.neo4j.local.url"
        private const val LOCAL_NEO4J_USERNAME_PROPERTY = "test.neo4j.local.username"
        private const val LOCAL_NEO4J_PASSWORD_PROPERTY = "test.neo4j.local.password"

        // Environment variable alternatives for easier CI/local dev setup
        private const val USE_LOCAL_NEO4J_ENV = "USE_LOCAL_NEO4J"
        private const val LOCAL_NEO4J_URL_ENV = "NEO4J_LOCAL_URL"
        private const val LOCAL_NEO4J_USERNAME_ENV = "NEO4J_LOCAL_USERNAME"
        private const val LOCAL_NEO4J_PASSWORD_ENV = "NEO4J_LOCAL_PASSWORD"

        /**
         * Singleton test container instance.
         * Null if using local Neo4j, otherwise a Testcontainer instance.
         * Lazy initialization to avoid starting container when using local Neo4j.
         */
        @JvmStatic
        val instance: DrivineTestContainer? by lazy {
            if (useLocalNeo4j()) null else createTestContainer()
        }

        /**
         * Check if tests should use a local Neo4j instance instead of Testcontainers.
         */
        @JvmStatic
        fun useLocalNeo4j(): Boolean {
            return System.getProperty(USE_LOCAL_NEO4J_PROPERTY, "false").toBoolean() ||
                   System.getenv(USE_LOCAL_NEO4J_ENV)?.toBoolean() ?: false
        }

        /**
         * Get the Bolt URL for Neo4j connection.
         * Returns local URL if configured, otherwise Testcontainer URL.
         */
        @JvmStatic
        fun getConnectionUrl(): String {
            return if (useLocalNeo4j()) {
                System.getProperty(LOCAL_NEO4J_URL_PROPERTY)
                    ?: System.getenv(LOCAL_NEO4J_URL_ENV)
                    ?: "bolt://localhost:7687"
            } else {
                instance?.boltUrl ?: throw IllegalStateException("Neo4j test container not initialized")
            }
        }

        /**
         * Get the username for Neo4j connection.
         */
        @JvmStatic
        fun getConnectionUsername(): String {
            return if (useLocalNeo4j()) {
                System.getProperty(LOCAL_NEO4J_USERNAME_PROPERTY)
                    ?: System.getenv(LOCAL_NEO4J_USERNAME_ENV)
                    ?: "neo4j"
            } else {
                "neo4j"
            }
        }

        /**
         * Get the password for Neo4j connection.
         */
        @JvmStatic
        fun getConnectionPassword(): String {
            return if (useLocalNeo4j()) {
                System.getProperty(LOCAL_NEO4J_PASSWORD_PROPERTY)
                    ?: System.getenv(LOCAL_NEO4J_PASSWORD_ENV)
                    ?: "brahmsian"
            } else {
                instance?.adminPassword ?: throw IllegalStateException("Neo4j test container not initialized")
            }
        }

        private fun createTestContainer(): DrivineTestContainer {
            val container = DrivineTestContainer("neo4j:5.26.1-community")
                .withNeo4jConfig("dbms.logs.query.enabled", "INFO")
                .withNeo4jConfig("dbms.logs.query.parameter_logging_enabled", "true")
                .withAdminPassword("testpassword")

            // Try to find and mount APOC if available (optional)
            try {
                val apocJar = findApocJar()
                if (apocJar != null) {
                    container
                        .withFileSystemBind(apocJar, "/plugins/apoc-5.26.0.jar")
                        .withNeo4jConfig("dbms.security.procedures.unrestricted", "apoc.*")
                        .withEnv("NEO4J_PLUGINS", "[\"apoc\"]")
                }
            } catch (e: Exception) {
                println("APOC not found, continuing without it: ${e.message}")
            }

            container.start()
            return container
        }

        private fun findApocJar(): String? {
            // Try different common APOC locations
            val possiblePaths = listOf(
                Paths.get(
                    System.getProperty("user.home"),
                    ".m2", "repository", "org", "neo4j", "procedure", "apoc",
                    "5.26.1", "apoc-5.26.1.jar"
                ),
                Paths.get(
                    System.getProperty("user.home"),
                    ".m2", "repository", "org", "neo4j", "procedure", "apoc-core",
                    "5.26.1", "apoc-core-5.26.1.jar"
                )
            )

            return possiblePaths
                .map { it.toString() }
                .firstOrNull { Paths.get(it).toFile().exists() }
        }

        init {
            // Container is initialized lazily in the instance property
        }
    }
}