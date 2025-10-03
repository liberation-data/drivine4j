package drivine.test

import org.testcontainers.containers.Neo4jContainer
import java.nio.file.Paths

class Neo4jTestContainer : Neo4jContainer<Neo4jTestContainer> {
    private constructor(imageName: String) : super(imageName)

    companion object {
        private const val USE_LOCAL_NEO4J_PROPERTY = "test.neo4j.use-local"
        private const val LOCAL_NEO4J_URL_PROPERTY = "test.neo4j.local.url"
        private const val LOCAL_NEO4J_USERNAME_PROPERTY = "test.neo4j.local.username"
        private const val LOCAL_NEO4J_PASSWORD_PROPERTY = "test.neo4j.local.password"

        @JvmField
        val instance: Neo4jTestContainer? = if (useLocalNeo4j()) null else createTestContainer()

        fun useLocalNeo4j(): Boolean {
            return System.getProperty(USE_LOCAL_NEO4J_PROPERTY, "false").toBoolean() ||
                   System.getenv("USE_LOCAL_NEO4J")?.toBoolean() ?: false
        }

        fun getBoltUrl(): String {
            return if (useLocalNeo4j()) {
                System.getProperty(LOCAL_NEO4J_URL_PROPERTY, "bolt://localhost:7687")
            } else {
                instance?.boltUrl ?: throw IllegalStateException("Neo4j test container not initialized")
            }
        }

        fun getUsername(): String {
            return if (useLocalNeo4j()) {
                System.getProperty(LOCAL_NEO4J_USERNAME_PROPERTY, "neo4j")
            } else {
                "neo4j"
            }
        }

        fun getPassword(): String {
            return if (useLocalNeo4j()) {
                System.getProperty(LOCAL_NEO4J_PASSWORD_PROPERTY, "password")
            } else {
                instance?.adminPassword ?: throw IllegalStateException("Neo4j test container not initialized")
            }
        }

        private fun createTestContainer(): Neo4jTestContainer {
            val container = Neo4jTestContainer("neo4j:5.26.1-community")
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