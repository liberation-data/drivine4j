package drivine

import drivine.connection.DatabaseRegistry
import drivine.manager.PersistenceManagerFactory
import drivine.transaction.TransactionContextHolder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackages = ["drivine"])
class DrivineConfiguration {

    @Autowired
    lateinit var databaseRegistry: DatabaseRegistry

    @Autowired
    lateinit var contextHolder: TransactionContextHolder

    @Bean
    fun factory(): PersistenceManagerFactory {
        return PersistenceManagerFactory(databaseRegistry, contextHolder)
    }

}