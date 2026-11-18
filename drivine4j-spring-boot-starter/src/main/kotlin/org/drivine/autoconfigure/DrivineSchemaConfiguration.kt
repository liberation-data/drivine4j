package org.drivine.autoconfigure

import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaManager
import org.drivine.schema.VectorDimensionProvider
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.bind.Bindable
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
 * `drivine.schema.enabled`. Consumers with neither catalog beans nor `@NodeFragment` classes that declare schema pay
 * nothing — the manager no-ops, and says so at INFO.
 *
 * The catalogs are read **once**, here, when the manager bean is made: a [SchemaCatalog] bean made
 * after that (a lazy bean, one in another context) is never seen by the manager, and a later
 * `enforce()` applies the same list. Schema known only later is given to `SchemaManager.ensure`, under
 * its owner.
 *
 * The manager waits `drivine.schema.index-build-timeout` (a duration, `60s` unless set) for an index that
 * Neo4j or FalkorDB builds in the background.
 *
 * Beside the catalog beans, the manager is given the schema the application's `@NodeFragment` classes declare, scanned
 * from its packages ([NodeFragmentScan]): `drivine.schema.scan-fragments=false` turns that off, and
 * `drivine.schema.scan-packages` names the packages in place of Spring Boot's auto-configuration packages. An item a
 * catalog bean declares is that bean's to make, on the databases it is for.
 *
 * `drivine.schema.index-node-ids=false` gives the manager its catalogs without the range index each fragment's `@NodeId`
 * is given by default ([SchemaCatalog.withoutNodeIdIndexes]), leaving only what is declared.
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
        beanFactory: BeanFactory,
        dimensions: ObjectProvider<VectorDimensionProvider>,
    ): SchemaManager {
        val binder = Binder.get(environment)
        val indexNodeIds = binder.bind(INDEX_NODE_IDS, Boolean::class.javaObjectType).orElse(true)
        val declared = catalogs.orderedStream().toList()
        // The scanned catalogs are read from fragments too, and their defaults go with the declared ones' where they're off.
        return SchemaManager(
            persistenceManagerFactory = persistenceManagerFactory,
            databaseRegistry = databaseRegistry,
            catalogs = (declared + scanned(binder, beanFactory, dimensions, declared, databaseRegistry))
                .map { if (indexNodeIds) it else it.withoutNodeIdIndexes() },
            policy = properties.toPolicy(),
            indexBuildTimeout = binder.bind(INDEX_BUILD_TIMEOUT, Duration::class.java).orElse(DEFAULT_INDEX_BUILD_TIMEOUT),
        )
    }

    /**
     * The schema the application's `@NodeFragment` classes declare, beyond what its own [declared] catalogs do
     * ([NodeFragmentScan]); none where `drivine.schema.scan-fragments` is `false`, or nothing is found.
     */
    private fun scanned(
        binder: Binder,
        beanFactory: BeanFactory,
        dimensions: ObjectProvider<VectorDimensionProvider>,
        declared: List<SchemaCatalog>,
        databaseRegistry: DatabaseRegistry,
    ): List<SchemaCatalog> {
        if (!binder.bind(SCAN_FRAGMENTS, Boolean::class.javaObjectType).orElse(true)) return emptyList()
        val configured = binder.bind(SCAN_PACKAGES, Bindable.listOf(String::class.java)).orElse(emptyList())
        val packages = NodeFragmentScan.packages(beanFactory, configured)
        val fragments = NodeFragmentScan.fragments(packages, (beanFactory as? ConfigurableBeanFactory)?.beanClassLoader)
        // ifUnique: an application may hold several providers, for catalogs of its own, which must not fail startup here.
        return NodeFragmentScan.catalogs(
            fragments, dimensions.ifUnique, declared,
            NodeFragmentScan.neo4jDatabases(databaseRegistry), NodeFragmentScan.schemaDatabases(databaseRegistry),
            resolve = { databaseRegistry.resolveDatabaseName(it) ?: it },
        )
    }

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

        /** Whether the application's `@NodeFragment` classes are scanned for their schema: see [NodeFragmentScan]. Read as [INDEX_BUILD_TIMEOUT] is. */
        const val SCAN_FRAGMENTS = "drivine.schema.scan-fragments"

        /** The packages scanned for `@NodeFragment` classes, in place of Spring Boot's auto-configuration packages. */
        const val SCAN_PACKAGES = "drivine.schema.scan-packages"

        /** Whether the range indexes fragments' `@NodeId`s are given by default are made: see [SchemaCatalog.nodeIdIndexes]. Read as [INDEX_BUILD_TIMEOUT] is. */
        const val INDEX_NODE_IDS = "drivine.schema.index-node-ids"

        val DEFAULT_INDEX_BUILD_TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
