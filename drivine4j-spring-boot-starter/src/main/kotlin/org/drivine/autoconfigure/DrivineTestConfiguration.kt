package org.drivine.autoconfigure

import org.drivine.connection.DataSourceMap
import org.drivine.test.DrivineTestContainer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Test configuration that automatically handles Testcontainers vs local Neo4j switching.
 *
 * This configuration:
 * 1. Loads datasource properties from application.yaml (test profile) or application-test.yml
 * 2. Checks test.neo4j.use-local property (supports system property, environment variable, or Spring property)
 * 3. If false (default): Starts Testcontainer and overrides host/port/password
 * 4. If true: Uses properties as-is for local Neo4j
 * 5. Creates DataSourceMap bean for DatabaseRegistry
 *
 * To use local Neo4j, set one of:
 * - System property: -Dtest.neo4j.use-local=true
 * - Environment variable: USE_LOCAL_NEO4J=true
 * - Spring property in application.yaml:
 *   test:
 *     neo4j:
 *       use-local: true
 */
@Configuration
class DrivineTestConfiguration {

    private val log = LoggerFactory.getLogger(DrivineTestConfiguration::class.java)

    @Bean
    @ConditionalOnMissingBean
    fun dataSourceMap(
        properties: DrivineProperties,
        @Value("\${test.neo4j.use-local:#{null}}") useLocalFromSpring: String?
    ): DataSourceMap {
        val datasources = properties.datasources.toMutableMap()

        // Check if we should use local Neo4j - check multiple sources in order of precedence:
        // 1. Spring property (test.neo4j.use-local)
        // 2. Environment variable (USE_LOCAL_NEO4J)
        val useLocal = useLocalFromSpring?.toBoolean()
            ?: System.getenv("USE_LOCAL_NEO4J")?.toBoolean()
            ?: false

        log.info("Drivine Test Config: use-local=$useLocal (Spring=${useLocalFromSpring}, env=${System.getenv("USE_LOCAL_NEO4J")})")

        if (!useLocal) {
            log.info("Starting Neo4j Testcontainer...")
            // Start Testcontainer and override connection settings
            datasources.forEach { (name, props) ->
                datasources[name] = props.copy(
                    host = extractHost(DrivineTestContainer.getConnectionUrl()),
                    port = extractPort(DrivineTestContainer.getConnectionUrl()),
                    password = DrivineTestContainer.getConnectionPassword()
                    // Keep username, type, database-name from properties
                )
            }
        } else {
            log.info("Using local Neo4j with datasource properties as-is")
        }

        return DataSourceMap(datasources)
    }

    private fun extractHost(boltUrl: String): String {
        return boltUrl.substringAfter("bolt://").substringBefore(":")
    }

    private fun extractPort(boltUrl: String): Int {
        val portPart = boltUrl.substringAfter("bolt://").substringAfter(":")
        return try {
            if (portPart.contains("/")) {
                portPart.substringBefore("/").toInt()
            } else {
                portPart.toIntOrNull() ?: 7687
            }
        } catch (e: Exception) {
            7687
        }
    }
}
