package org.drivine.autoconfigure

import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.query.dsl.IndexAdvicePolicy
import org.drivine.transaction.DrivineTransactionManager
import org.drivine.transaction.TransactionContextHolder
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Configuration imported by @EnableDrivine.
 * Provides all core Drivine infrastructure beans.
 */
@Configuration
class DrivineConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun subtypeRegistry(): org.drivine.mapper.SubtypeRegistry {
        return org.drivine.mapper.SubtypeRegistry()
    }

    /**
     * [ConnectionProvider] beans register alongside the property-built
     * providers — the seam for engines connection properties cannot describe
     * (an [org.drivine.connection.DatabaseType.EMBABEL] engine living in
     * the application itself). A bean whose name collides with a datasource
     * entry wins: application code outranks configuration.
     */
    @Bean
    @ConditionalOnMissingBean
    fun databaseRegistry(
        dataSourceMap: DataSourceMap,
        subtypeRegistry: org.drivine.mapper.SubtypeRegistry,
        providers: org.springframework.beans.factory.ObjectProvider<org.drivine.connection.ConnectionProvider>,
    ): DatabaseRegistry {
        val registry = DatabaseRegistry(dataSourceMap, subtypeRegistry)
        providers.orderedStream().forEach { registry.register(it) }
        return registry
    }

    @Bean
    @ConditionalOnMissingBean
    fun transactionContextHolder(databaseRegistry: DatabaseRegistry): TransactionContextHolder {
        return TransactionContextHolder(databaseRegistry)
    }

    @Bean
    @ConditionalOnMissingBean
    fun drivineTransactionManager(contextHolder: TransactionContextHolder): PlatformTransactionManager {
        return DrivineTransactionManager(contextHolder)
    }

    @Bean
    @ConditionalOnMissingBean
    fun persistenceManagerFactory(
        databaseRegistry: DatabaseRegistry,
        contextHolder: TransactionContextHolder
    ): PersistenceManagerFactory {
        return PersistenceManagerFactory(databaseRegistry, contextHolder)
    }

    @Bean
    @ConditionalOnMissingBean
    fun graphObjectManagerFactory(
        persistenceManagerFactory: PersistenceManagerFactory,
        subtypeRegistry: org.drivine.mapper.SubtypeRegistry,
        queryProperties: DrivineQueryProperties?,
    ): GraphObjectManagerFactory {
        return GraphObjectManagerFactory(
            persistenceManagerFactory,
            Neo4jObjectMapper.instance,
            subtypeRegistry,
            queryProperties?.indexAdvice ?: IndexAdvicePolicy.WARN,
        )
    }
}
