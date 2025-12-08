package org.drivine.autoconfigure

import org.springframework.context.annotation.Import
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS

/**
 * Enable Drivine4j infrastructure beans.
 *
 * Add this annotation to your `@Configuration` class to enable Drivine.
 * You must provide a `DataSourceMap` bean for Drivine to function.
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
 *             username = "neo4j",
 *             password = "password",
 *             type = DatabaseType.NEO4J,
 *             databaseName = "neo4j"
 *         )
 *         return DataSourceMap(mapOf("neo" to neo4jProps))
 *     }
 * }
 * ```
 *
 * This will automatically configure:
 * - `Neo4jObjectMapper`
 * - `DatabaseRegistry`
 * - `TransactionContextHolder`
 * - `DrivineTransactionManager`
 * - `PersistenceManagerFactory`
 * - `GraphObjectManagerFactory`
 *
 * All beans can be overridden by defining your own.
 */
@Target(CLASS)
@Retention(RUNTIME)
@Import(DrivineConfiguration::class)
annotation class EnableDrivine
