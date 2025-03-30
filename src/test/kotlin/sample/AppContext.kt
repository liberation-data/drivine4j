package sample

import drivine.connection.DataSourceMap
import drivine.connection.DatabaseRegistry
import drivine.manager.PersistenceManager
import drivine.manager.PersistenceManagerFactory
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.*


@Configuration
@ComponentScan(basePackages = ["drivine", "sample"])
@PropertySource("classpath:application.yaml")
@EnableAspectJAutoProxy(proxyTargetClass = true)
@ConfigurationProperties
@EnableConfigurationProperties
class AppContext {

    @Autowired
    lateinit var databaseRegistry: DatabaseRegistry

    @Autowired
    lateinit var persistenceManagerFactory: PersistenceManagerFactory

    @Autowired
    lateinit var dataSourceMap: DataSourceMap

    @Bean("neo")
    fun neoManager(): PersistenceManager {
        return persistenceManagerFactory.get("neo")
    }

    @PostConstruct
    fun init() {
        dataSourceMap.dataSources.forEach { (key, value) ->
            databaseRegistry.withProperties(value).register(key)
        }
    }

}