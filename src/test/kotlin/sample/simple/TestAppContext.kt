package sample.simple

import org.springframework.beans.factory.annotation.Value
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.springframework.context.annotation.*

@Configuration
@EnableDrivine
@EnableDrivineTestConfig
@ComponentScan(basePackages = ["sample"])
@EnableAspectJAutoProxy(proxyTargetClass = true)
class TestAppContext {

    @Bean
    fun persistenceManager(
        factory: PersistenceManagerFactory,
        @Value("\${drivine.default-datasource:#{null}}") datasource: String?
    ): PersistenceManager {
        return factory.get(datasource ?: "default")
    }

    @Bean
    fun graphObjectManager(
        factory: GraphObjectManagerFactory,
        @Value("\${drivine.default-datasource:#{null}}") datasource: String?
    ): GraphObjectManager {
        return factory.get(datasource ?: "default")
    }
}
