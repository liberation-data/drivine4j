package org.drivine.autoconfigure

import org.drivine.connection.ConnectionProperties
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Drivine datasources.
 *
 * Activated by `@EnableDrivinePropertiesConfig`.
 */
@ConfigurationProperties(prefix = "database")
data class DrivineProperties(
    var datasources: Map<String, ConnectionProperties> = mutableMapOf()
)
