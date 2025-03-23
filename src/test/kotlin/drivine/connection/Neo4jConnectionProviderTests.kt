package drivine.connection

import drivine.query.QuerySpecification
import drivine.query.cypherStatement
import org.junit.jupiter.api.Test

class Neo4jConnectionProviderTests {

    @Test
    fun contextLoads() {
        val provider = Neo4jConnectionProvider(name = "test", type = DatabaseType.NEO4J, host = "localhost",
            port = 7687, user = "neo4j", password = "mypass", database = "neo4j", protocol = "bolt",
            config = emptyMap() )

        val connection = provider.connect()

        val spec = QuerySpecification<Any>(cypherStatement("match (n:BusinessPartner) return n"))
            .limit(10)
        val result = connection.query(spec)
        connection.release()
    }

}