package org.drivine.test

import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.DockerImageName
import java.io.InputStream
import java.nio.file.Paths
import java.util.Properties

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
 * ### The image
 *
 * The Neo4j community image that Drivine's own suite runs, which the build reads from
 * `gradle/test-images.txt` (the image pinned there for `neo4j:latest`): [DEFAULT_IMAGE]. Choose
 * another with `NEO4J_TEST_IMAGE` or `-Dtest.neo4j.image`. APOC Core is installed in any image. APOC
 * Extended is mounted only into the default image, when its jar is in the local Maven or Gradle cache,
 * since an APOC Extended release does not load on every Neo4j release.
 *
 * Neo4j 5.26.0 to 5.26.3, 2025.01 and 2025.02 write the wrong relationship type for Cypher's dynamic
 * relationship type, `CREATE (a)-[r:$($type)]->(b)`, once the same statement has run with another
 * type (neo4j/neo4j#13597). Neo4j fixed it in 2025.03 and 5.26.4.
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
class DrivineTestContainer private constructor(
    imageName: DockerImageName
) : Neo4jContainer<DrivineTestContainer>(imageName) {

    companion object {
        private const val USE_LOCAL_NEO4J_PROPERTY = "test.neo4j.use-local"
        private const val LOCAL_NEO4J_URL_PROPERTY = "test.neo4j.local.url"
        private const val LOCAL_NEO4J_USERNAME_PROPERTY = "test.neo4j.local.username"
        private const val LOCAL_NEO4J_PASSWORD_PROPERTY = "test.neo4j.local.password"
        private const val IMAGE_PROPERTY = "test.neo4j.image"

        // Environment variable alternatives for easier CI/local dev setup
        private const val USE_LOCAL_NEO4J_ENV = "USE_LOCAL_NEO4J"
        private const val LOCAL_NEO4J_URL_ENV = "NEO4J_LOCAL_URL"
        private const val LOCAL_NEO4J_USERNAME_ENV = "NEO4J_LOCAL_USERNAME"
        private const val LOCAL_NEO4J_PASSWORD_ENV = "NEO4J_LOCAL_PASSWORD"
        private const val IMAGE_ENV = "NEO4J_TEST_IMAGE"

        /** The image [DEFAULT_IMAGE] falls back to where the library's classpath lost the pin: the tag the pin stands for. */
        private const val UNPINNED_IMAGE = "neo4j:latest"

        /**
         * The image the container runs unless told otherwise: the one `gradle/test-images.txt` pins
         * for `neo4j:latest`, which the build writes into the library. A classpath that lost that
         * resource (a repackaged jar, an IDE build that skipped the Gradle task) gets `neo4j:latest`
         * rather than a companion that fails to initialize.
         */
        @JvmField
        val DEFAULT_IMAGE: String =
            defaultImage(DrivineTestContainer::class.java.getResourceAsStream("test-container-images.properties"))

        /** The `neo4j` entry of the build's `test-container-images.properties`, or `neo4j:latest` where there is none. */
        internal fun defaultImage(images: InputStream?): String =
            images?.use { stream -> Properties().apply { load(stream) }.getProperty("neo4j") } ?: UNPINNED_IMAGE

        /** The image the container runs: [DEFAULT_IMAGE], or the one `test.neo4j.image` or `NEO4J_TEST_IMAGE` names. */
        @JvmStatic
        fun image(): String = System.getProperty(IMAGE_PROPERTY) ?: System.getenv(IMAGE_ENV) ?: DEFAULT_IMAGE

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

        /** The latest APOC Extended release, which loads on [DEFAULT_IMAGE]: build.gradle's `neo4jPlugins` downloads it. */
        private const val APOC_EXTENDED_VERSION = "2026.04.0"

        private fun createTestContainer(): DrivineTestContainer {
            val image = image()
            val container = DrivineTestContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("neo4j"))
                .withNeo4jConfig("dbms.logs.query.enabled", "INFO")
                .withNeo4jConfig("dbms.logs.query.parameter_logging_enabled", "true")
                .withAdminPassword("testpassword")
                // APOC Core is auto-installed by Neo4j via NEO4J_PLUGINS
                .withEnv("NEO4J_PLUGINS", "[\"apoc\"]")
                .withNeo4jConfig("dbms.security.procedures.unrestricted", "apoc.*,gds.*")

            // Mount APOC Extended from .m2 if available, into the default image only: another Neo4j
            // may not load this release of it.
            if (image == DEFAULT_IMAGE) {
                findPluginJar("org.neo4j.procedure", "apoc-extended", APOC_EXTENDED_VERSION)?.let { jarPath ->
                    container.withFileSystemBind(jarPath, "/plugins/apoc-extended-$APOC_EXTENDED_VERSION.jar")
                    println("Mounted APOC Extended from: $jarPath")
                } ?: println("APOC Extended not found in .m2 - run './gradlew dependencies' to download")
            } else {
                println("APOC Extended not mounted: $image is not the default image, $DEFAULT_IMAGE")
            }

            container.start()
            return container
        }

        /**
         * Find a plugin JAR in the local Maven repository (.m2) or Gradle cache.
         *
         * @param groupId Maven group ID (e.g., "org.neo4j.procedure")
         * @param artifactId Maven artifact ID (e.g., "apoc-extended")
         * @param version Version (e.g., "2026.04.0")
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