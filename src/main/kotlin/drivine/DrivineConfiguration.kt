package drivine

import drivine.connection.DataSourceMap
import drivine.connection.DatabaseRegistry
import drivine.manager.PersistenceManagerFactory
import drivine.transaction.TransactionContextHolder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackages = ["drivine"])
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
    fun factory(databaseRegistry: DatabaseRegistry): PersistenceManagerFactory {
        return PersistenceManagerFactory(databaseRegistry, transactionContextHolder())
    }

}
