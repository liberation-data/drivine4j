package org.drivine.autoconfigure

import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.test.DrivineTestContainer
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Test configuration that automatically handles Testcontainers vs local Neo4j switching.
 *
 * This configuration:
 * 1. Loads datasource properties from application-test.yml
 * 2. Checks the USE_LOCAL_NEO4J flag (environment variable or system property)
 * 3. If false (default): Starts Testcontainer and overrides host/port/password
 * 4. If true: Uses properties as-is for local Neo4j
 * 5. Creates DataSourceMap bean for DatabaseRegistry
 */
@Configuration
class DrivineTestConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun dataSourceMap(properties: DrivineProperties): DataSourceMap {
        val datasources = properties.datasources.toMutableMap()

        if (!DrivineTestContainer.useLocalNeo4j()) {
            // Start Testcontainer and override connection settings
            datasources.forEach { (name, props) ->
                datasources[name] = props.copy(
                    host = extractHost(DrivineTestContainer.getConnectionUrl()),
                    port = extractPort(DrivineTestContainer.getConnectionUrl()),
                    password = DrivineTestContainer.getConnectionPassword()
                    // Keep username, type, database-name from properties
                )
            }
        }
        // else: use properties as-is for local Neo4j

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
