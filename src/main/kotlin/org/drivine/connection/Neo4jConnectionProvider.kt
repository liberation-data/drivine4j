package org.drivine.connection

import org.drivine.mapper.Neo4jResultMapper
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Session

// TODO: Config options are not being handled
class Neo4jConnectionProvider(
    override val name: String,
    override val type: DatabaseType,
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String?,
    private val database: String?,
    private val protocol: String = "bolt",
    private val config: Map<String, Any>
) : ConnectionProvider {

    private val driver: Driver = GraphDatabase.driver(
        "$protocol://$host:$port",
        AuthTokens.basic(user, password ?: "")
    )

    override fun connect(): Connection {
        val session: Session = driver.session()
        val connection = Neo4jConnection(session, Neo4jResultMapper())
        return connection
    }

    override fun end() {
        driver.close()
    }
}
