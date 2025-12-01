package org.drivine

import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.GraphViewManagerFactory
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.transaction.DrivineTransactionManager
import org.drivine.transaction.TransactionContextHolder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

@Configuration
@ComponentScan(basePackages = ["org.drivine"])
class DrivineConfiguration {

    @Bean
    fun databaseRegistry(dataSourceMap: DataSourceMap): DatabaseRegistry {
        return DatabaseRegistry(dataSourceMap)
    }

    @Bean
    fun transactionContextHolder(): TransactionContextHolder {
        return TransactionContextHolder()
    }

    @Bean
    fun transactionManager(contextHolder: TransactionContextHolder): PlatformTransactionManager {
        return DrivineTransactionManager(contextHolder)
    }

    @Bean
    fun factory(databaseRegistry: DatabaseRegistry): PersistenceManagerFactory {
        return PersistenceManagerFactory(databaseRegistry, transactionContextHolder())
    }

    @Bean
    fun graphViewManagerFactory(persistenceManagerFactory: PersistenceManagerFactory): GraphViewManagerFactory {
        return GraphViewManagerFactory(persistenceManagerFactory)
    }

}
