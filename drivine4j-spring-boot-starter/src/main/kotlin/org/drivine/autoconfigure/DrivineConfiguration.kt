package org.drivine.autoconfigure

import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.mapper.Neo4jObjectMapper
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

    @Bean
    @ConditionalOnMissingBean
    fun databaseRegistry(dataSourceMap: DataSourceMap, subtypeRegistry: org.drivine.mapper.SubtypeRegistry): DatabaseRegistry {
        return DatabaseRegistry(dataSourceMap, subtypeRegistry)
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
        persistenceManagerFactory: PersistenceManagerFactory
    ): GraphObjectManagerFactory {
        return GraphObjectManagerFactory(persistenceManagerFactory, Neo4jObjectMapper.instance)
    }
}
