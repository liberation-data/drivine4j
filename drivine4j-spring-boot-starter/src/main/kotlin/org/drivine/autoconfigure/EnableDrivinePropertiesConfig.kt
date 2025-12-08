package org.drivine.autoconfigure

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS

/**
 * Enable property-based DataSource configuration for Drivine.
 *
 * Use this annotation (in addition to `@EnableDrivine`) to configure datasources
 * via application.yml/properties instead of defining a `DataSourceMap` bean manually.
 *
 * Example configuration:
 * ```yaml
 * database:
 *   datasources:
 *     neo:
 *       host: localhost
 *       port: 7687
 *       username: neo4j
 *       password: password
 *       type: NEO4J
 *       database-name: neo4j
 *     analytics:
 *       host: analytics.example.com
 *       port: 7687
 *       username: readonly
 *       password: secret
 *       type: NEO4J
 *       database-name: analytics
 * ```
 *
 * Example usage:
 * ```kotlin
 * @Configuration
 * @EnableDrivine
 * @EnableDrivinePropertiesConfig
 * class AppConfig
 * ```
 *
 * This will automatically create a `DataSourceMap` bean from your properties,
 * so you don't need to define it manually.
 */
@Target(CLASS)
@Retention(RUNTIME)
@EnableConfigurationProperties(DrivineProperties::class)
@Import(DrivinePropertiesConfiguration::class)
annotation class EnableDrivinePropertiesConfig
