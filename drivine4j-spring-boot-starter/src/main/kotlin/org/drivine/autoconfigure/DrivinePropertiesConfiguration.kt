package org.drivine.autoconfigure

import org.drivine.connection.DataSourceMap
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration imported by @EnableDrivinePropertiesConfig.
 * Creates a DataSourceMap bean from DrivineProperties.
 *
 * Note: If using @EnableDrivineTestConfig, that configuration's DataSourceMap
 * will take precedence (marked as @Primary).
 *
 * If you define your own DataSourceMap bean, this one will be skipped
 * (due to @ConditionalOnMissingBean).
 */
@Configuration
class DrivinePropertiesConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun dataSourceMap(properties: DrivineProperties): DataSourceMap {
        return DataSourceMap(properties.datasources)
    }
}
