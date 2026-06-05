package org.drivine.autoconfigure

import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration imported by @EnableDrivine.
 *
 * Wires the [SchemaManager] from all [SchemaCatalog] beans the consumer registers, plus a startup
 * runner that calls [SchemaManager.enforce]. The manager bean is always available (so an application
 * can call `enforce()` / `recreateAll()` at runtime); only the startup run is gated by
 * `drivine.schema.enabled`. Consumers without catalog beans pay nothing — the manager no-ops.
 */
@Configuration
class DrivineSchemaConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun schemaManager(
        persistenceManagerFactory: PersistenceManagerFactory,
        databaseRegistry: DatabaseRegistry,
        catalogs: ObjectProvider<SchemaCatalog>,
        properties: DrivineSchemaProperties,
    ): SchemaManager = SchemaManager(
        persistenceManagerFactory = persistenceManagerFactory,
        databaseRegistry = databaseRegistry,
        catalogs = catalogs.orderedStream().toList(),
        policy = properties.toPolicy(),
    )

    @Bean
    @ConditionalOnProperty(
        prefix = "drivine.schema",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun drivineSchemaStartupRunner(schemaManager: SchemaManager): ApplicationRunner =
        ApplicationRunner { schemaManager.enforce() }
}