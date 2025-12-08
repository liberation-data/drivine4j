package org.drivine.sample

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
@ComponentScan(basePackages = ["org.drivine.sample"])
@EnableAspectJAutoProxy(proxyTargetClass = true)
class SampleAppContext {

    @Bean
    fun neoManager(factory: PersistenceManagerFactory): PersistenceManager {
        return factory.get("neo")
    }

    @Bean
    fun neoGraphObjectManager(factory: GraphObjectManagerFactory): GraphObjectManager {
        return factory.get("neo")
    }
}
