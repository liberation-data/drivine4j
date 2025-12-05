package org.drivine.sample

import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.connection.PropertyProvidedDataSourceMap
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.test.DrivineTestContainer
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.*

@Configuration
@ComponentScan(basePackages = ["org.drivine", "org.drivine.sample"])
@PropertySource("classpath:application.yaml")
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(value = [PropertyProvidedDataSourceMap::class])
class SampleAppContext {

    @Bean
    @Profile("!local")
    fun dataSourceMap(): DataSourceMap {
        val neo4jProperties = ConnectionProperties(
            host = extractHost(DrivineTestContainer.getConnectionUrl()),
            port = extractPort(DrivineTestContainer.getConnectionUrl()),
            userName = DrivineTestContainer.getConnectionUsername(),
            password = DrivineTestContainer.getConnectionPassword(),
            type = DatabaseType.NEO4J,
            databaseName = "neo4j"
        )
        return DataSourceMap(mapOf("neo" to neo4jProperties))
    }

    @Bean
    @Profile("local")
    fun propertyDataSources(map: PropertyProvidedDataSourceMap): DataSourceMap {
        return DataSourceMap(map.dataSources)
    }

    @Bean
    fun neoManager(factory: PersistenceManagerFactory): PersistenceManager {
        return factory.get("neo")
    }

    @Bean
    fun neoGraphObjectManager(factory: GraphObjectManagerFactory): GraphObjectManager {
        return factory.get("neo")
    }

    private fun extractHost(boltUrl: String): String {
        return boltUrl.substringAfter("bolt://").substringBefore(":")
    }

    private fun extractPort(boltUrl: String): Int {
        val portPart = boltUrl.substringAfter("bolt://").substringAfter(":")
        return try {
            if (portPart.contains("/")) {
                portPart.substringBefore("/").toInt()
            } else {
                portPart.toIntOrNull() ?: 7687
            }
        } catch (e: Exception) {
            7687
        }
    }
}
