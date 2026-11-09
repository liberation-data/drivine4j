package org.drivine.autoconfigure

import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration

/**
 * Configuration imported by @EnableDrivine and by [DrivineAutoConfiguration].
 *
 * Wires the [SchemaManager] from all [SchemaCatalog] beans the consumer registers, plus a startup
 * runner that calls [SchemaManager.enforce]. The manager bean is always available (so an application
 * can call `enforce()` / `recreateAll()` at runtime); only the startup run is gated by
 * `drivine.schema.enabled`. Consumers without catalog beans pay nothing — the manager no-ops, and
 * says so at INFO.
 *
 * The catalogs are read **once**, here, when the manager bean is made: a [SchemaCatalog] bean made
 * after that (a lazy bean, one in another context) is never seen by the manager, and a later
 * `enforce()` applies the same list. Schema known only later is given to `SchemaManager.ensure`, under
 * its owner.
 *
 * The manager waits `drivine.schema.index-build-timeout` (a duration, `60s` unless set) for an index that
 * Neo4j or FalkorDB builds in the background.
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
        environment: Environment,
    ): SchemaManager = SchemaManager(
        persistenceManagerFactory = persistenceManagerFactory,
        databaseRegistry = databaseRegistry,
        catalogs = catalogs.orderedStream().toList(),
        policy = properties.toPolicy(),
        indexBuildTimeout = Binder.get(environment).bind(INDEX_BUILD_TIMEOUT, Duration::class.java)
            .orElse(DEFAULT_INDEX_BUILD_TIMEOUT),
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

    private companion object {
        /** Read here rather than held in [DrivineSchemaProperties], whose constructor and `copy` stay as they were. */
        const val INDEX_BUILD_TIMEOUT = "drivine.schema.index-build-timeout"

        val DEFAULT_INDEX_BUILD_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
