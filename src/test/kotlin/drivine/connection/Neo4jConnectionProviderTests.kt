package drivine.connection

import drivine.query.QuerySpecification
import drivine.query.cypherStatement
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import sample.TestAppContext

@SpringBootTest(classes = [TestAppContext::class])
class Neo4jConnectionProviderTests @Autowired constructor(
    private val dataSourceMap: DataSourceMap
) {

    @Test
    fun contextLoads() {
        val connectionProperties = dataSourceMap.dataSources["neo"]!!
        val provider = Neo4jConnectionProvider(
            name = "test",
            type = connectionProperties.type,
            host = connectionProperties.host,
            port = connectionProperties.port!!,
            user = connectionProperties.userName!!,
            password = connectionProperties.password,
            database = connectionProperties.databaseName,
            protocol = "bolt",
            config = emptyMap()
        )

        val connection = provider.connect()

        val spec = QuerySpecification
            .withStatement<Any>("match (n:BusinessPartner) return n")
            .limit(10)
        val result = connection.query<Any>(spec)
        connection.release()
    }

}
