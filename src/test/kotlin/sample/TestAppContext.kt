package sample

import drivine.connection.ConnectionProperties
import drivine.connection.DataSourceMap
import drivine.connection.DatabaseType
import drivine.connection.PropertyProvidedDataSourceMap
import drivine.manager.PersistenceManager
import drivine.manager.PersistenceManagerFactory
import drivine.test.Neo4jTestContainer
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.*

@Configuration
@ComponentScan(basePackages = ["drivine", "sample"])
@PropertySource("classpath:application.yaml")
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(value = [PropertyProvidedDataSourceMap::class])
class TestAppContext() {

    @Bean
    @Profile("!local")
    fun dataSourceMap(): DataSourceMap {
        val neo4jProperties = ConnectionProperties(
            host = extractHost(Neo4jTestContainer.getBoltUrl()),
            port = extractPort(Neo4jTestContainer.getBoltUrl()),
            userName = Neo4jTestContainer.getUsername(),
            password = Neo4jTestContainer.getPassword(),
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

    @Bean("neo")
    fun neoManager(factory: PersistenceManagerFactory): PersistenceManager {
        return factory.get("neo")
    }

    private fun extractHost(boltUrl: String): String {
        // Extract host from bolt://host:port
        return boltUrl.substringAfter("bolt://").substringBefore(":")
    }

    private fun extractPort(boltUrl: String): Int {
        // Extract port from bolt://host:port, default to 7687
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
