package sample.simple

import org.drivine.autoconfigure.EnableDrivine
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.test.DrivineTestContainer
import org.springframework.context.annotation.*

@Configuration
@EnableDrivine
@ComponentScan(basePackages = ["sample"])
@EnableAspectJAutoProxy(proxyTargetClass = true)
class TestAppContext {

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
    fun neoManager(factory: PersistenceManagerFactory): PersistenceManager {
        return factory.get("neo")
    }

    @Bean
    fun neoGraphObjectManager(factory: GraphObjectManagerFactory): GraphObjectManager {
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
