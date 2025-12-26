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

        private const val NEO4J_VERSION = "5.26.1"
        private const val APOC_EXTENDED_VERSION = "5.26.0"

        private fun createTestContainer(): DrivineTestContainer {
            val container = DrivineTestContainer("neo4j:$NEO4J_VERSION-community")
                .withNeo4jConfig("dbms.logs.query.enabled", "INFO")
                .withNeo4jConfig("dbms.logs.query.parameter_logging_enabled", "true")
                .withAdminPassword("testpassword")
                // APOC Core is auto-installed by Neo4j via NEO4J_PLUGINS
                .withEnv("NEO4J_PLUGINS", "[\"apoc\"]")
                .withNeo4jConfig("dbms.security.procedures.unrestricted", "apoc.*,gds.*")

            // Mount APOC Extended from .m2 if available
            findPluginJar("org.neo4j.procedure", "apoc-extended", APOC_EXTENDED_VERSION)?.let { jarPath ->
                container.withFileSystemBind(jarPath, "/plugins/apoc-extended-$APOC_EXTENDED_VERSION.jar")
                println("Mounted APOC Extended from: $jarPath")
            } ?: println("APOC Extended not found in .m2 - run './gradlew dependencies' to download")

            container.start()
            return container
        }

        /**
         * Find a plugin JAR in the local Maven repository (.m2) or Gradle cache.
         *
         * @param groupId Maven group ID (e.g., "org.neo4j.procedure")
         * @param artifactId Maven artifact ID (e.g., "apoc-extended")
         * @param version Version (e.g., "5.26.0")
         * @return Path to the JAR file, or null if not found
         */
        private fun findPluginJar(groupId: String, artifactId: String, version: String): String? {
            val jarName = "$artifactId-$version.jar"
            val groupPath = groupId.replace('.', '/')
            val userHome = System.getProperty("user.home")

            // Check Maven .m2 repository
            val m2Path = Paths.get(userHome, ".m2", "repository", groupPath, artifactId, version, jarName)
            if (m2Path.toFile().exists()) {
                return m2Path.toString()
            }

            // Check Gradle cache (structure: ~/.gradle/caches/modules-2/files-2.1/{group}/{artifact}/{version}/{hash}/{jar})
            val gradleCacheBase = Paths.get(userHome, ".gradle", "caches", "modules-2", "files-2.1", groupId, artifactId, version)
            if (gradleCacheBase.toFile().exists()) {
                // Search subdirectories for the jar (hash directories)
                gradleCacheBase.toFile().walkTopDown()
                    .filter { it.isFile && it.name == jarName }
                    .firstOrNull()
                    ?.let { return it.absolutePath }
            }

            return null
        }

        init {
            // Container is initialized lazily in the instance property
        }
    }
}