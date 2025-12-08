package org.drivine.autoconfigure

import org.drivine.connection.DataSourceMap
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration imported by @EnableDrivinePropertiesConfig.
 * Creates a DataSourceMap bean from DrivineProperties.
 */
@Configuration
class DrivinePropertiesConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun dataSourceMap(properties: DrivineProperties): DataSourceMap {
        return DataSourceMap(properties.datasources)
    }
}
