package org.drivine.autoconfigure

import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.test.DrivineTestContainer
import org.drivine.test.FalkorDbTestContainer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Test configuration that automatically handles Testcontainers vs local instance switching
 * for all supported database types.
 *
 * For each datasource in the configuration, detects the type and starts the appropriate
 * Testcontainer (unless use-local is set for that database type).
 *
 * ## Neo4j
 * - `test.neo4j.use-local=true` or `USE_LOCAL_NEO4J=true` → uses local instance
 * - Otherwise starts a Neo4j Testcontainer
 *
 * ## FalkorDB
 * - `test.falkordb.use-local=true` or `USE_LOCAL_FALKORDB=true` → uses local instance
 * - Otherwise starts a FalkorDB Testcontainer
 */
@Configuration
class DrivineTestConfiguration {

    private val log = LoggerFactory.getLogger(DrivineTestConfiguration::class.java)

    @Bean
    @Primary
    @ConditionalOnMissingBean
    fun dataSourceMap(
        properties: DrivineProperties,
        @Value("\${test.neo4j.use-local:#{null}}") useLocalNeo4jFromSpring: String?,
        @Value("\${test.falkordb.use-local:#{null}}") useLocalFalkorFromSpring: String?
    ): DataSourceMap {
        val datasources = properties.datasources.toMutableMap()

        datasources.forEach { (name, props) ->
            when (props.type) {
                DatabaseType.NEO4J -> {
                    val useLocal = useLocalNeo4jFromSpring?.toBoolean()
                        ?: System.getenv("USE_LOCAL_NEO4J")?.toBoolean()
                        ?: false

                    log.info("Drivine Test Config [$name]: Neo4j use-local=$useLocal")

                    if (!useLocal) {
                        log.info("Starting Neo4j Testcontainer...")
                        datasources[name] = props.copy(
                            host = extractHost(DrivineTestContainer.getConnectionUrl()),
                            port = extractPort(DrivineTestContainer.getConnectionUrl()),
                            password = DrivineTestContainer.getConnectionPassword()
                        )
                    }
                }

                DatabaseType.FALKORDB -> {
                    val useLocal = useLocalFalkorFromSpring?.toBoolean()
                        ?: System.getenv("USE_LOCAL_FALKORDB")?.toBoolean()
                        ?: false

                    log.info("Drivine Test Config [$name]: FalkorDB use-local=$useLocal")

                    if (!useLocal) {
                        log.info("Starting FalkorDB Testcontainer...")
                        datasources[name] = props.copy(
                            host = FalkorDbTestContainer.getConnectionHost(),
                            port = FalkorDbTestContainer.getConnectionPort(),
                        )
                    }
                }

                else -> {
                    log.info("Drivine Test Config [$name]: ${props.type} — no testcontainer support, using properties as-is")
                }
            }
        }

        return DataSourceMap(datasources)
    }

    private fun extractHost(boltUrl: String): String {
        return boltUrl.substringAfter("://").substringBefore(":")
    }

    private fun extractPort(boltUrl: String): Int {
        val portPart = boltUrl.substringAfter("://").substringAfter(":")
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