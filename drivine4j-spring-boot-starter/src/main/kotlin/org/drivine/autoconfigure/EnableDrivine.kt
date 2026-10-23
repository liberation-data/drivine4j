package org.drivine.autoconfigure

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS

/**
 * Enable Drivine4j infrastructure beans explicitly.
 *
 * A Spring Boot application does not need this: with the starter on the classpath and a datasource
 * under `database.datasources` (or a `DataSourceMap` bean), [DrivineAutoConfiguration] provides the
 * same beans. Use this annotation where auto-configuration does not run — a plain `@Configuration`
 * context, or a test that names its classes — or to keep the wiring explicit. Having both is fine:
 * each bean is still registered once.
 *
 * Add this annotation to your `@Configuration` class, and provide a `DataSourceMap` bean (or add
 * `@EnableDrivinePropertiesConfig` to build one from properties).
 *
 * Example:
 * ```kotlin
 * @Configuration
 * @EnableDrivine
 * class AppConfig {
 *     @Bean
 *     fun dataSourceMap(): DataSourceMap {
 *         val neo4jProps = ConnectionProperties(
 *             host = "localhost",
 *             port = 7687,
 *             userName = "neo4j",
 *             password = "password",
 *             type = DatabaseType.NEO4J,
 *             databaseName = "neo4j"
 *         )
 *         return DataSourceMap(mapOf("neo" to neo4jProps))
 *     }
 * }
 * ```
 *
 * This imports:
 * - `SubtypeRegistry`
 * - `DatabaseRegistry`
 * - `TransactionContextHolder`
 * - `DrivineTransactionManager`
 * - `PersistenceManagerFactory`
 * - `GraphObjectManagerFactory`
 * - `SchemaManager` (applies any registered `SchemaCatalog` beans; enforced on startup unless
 *   `drivine.schema.enabled=false`)
 *
 * All beans can be overridden by defining your own.
 */
@Target(CLASS)
@Retention(RUNTIME)
@EnableConfigurationProperties(DrivineSchemaProperties::class, DrivineQueryProperties::class)
@Import(DrivineConfiguration::class, DrivineSchemaConfiguration::class)
annotation class EnableDrivine
