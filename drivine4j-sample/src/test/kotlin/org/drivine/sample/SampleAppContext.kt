package org.drivine.sample

import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.test.SqlCypherFixtures
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.*

@Configuration
@EnableDrivine
@EnableDrivineTestConfig
@ComponentScan(basePackages = ["org.drivine.sample"])
@EnableAspectJAutoProxy(proxyTargetClass = true)
class SampleAppContext {

    /**
     * The manager the tests seed and read back through, in Cypher. On Oracle or Postgres the Cypher is
     * worked out over the graph's tables (see [SqlCypherFixtures]), so the tests run on every store.
     */
    @Bean
    fun persistenceManager(
        factory: PersistenceManagerFactory,
        @Value("\${drivine.default-datasource:#{null}}") datasource: String?
    ): PersistenceManager {
        return SqlCypherFixtures.forStore(factory.get(datasource ?: "default"))
    }

    @Bean
    fun graphObjectManager(
        factory: GraphObjectManagerFactory,
        @Value("\${drivine.default-datasource:#{null}}") datasource: String?
    ): GraphObjectManager {
        return factory.get(datasource ?: "default")
    }

    @Bean
    fun statelessGraphObjectManager(
        factory: GraphObjectManagerFactory,
        @Value("\${drivine.default-datasource:#{null}}") datasource: String?
    ): StatelessGraphObjectManager {
        return factory.stateless(datasource ?: "default")
    }
}
