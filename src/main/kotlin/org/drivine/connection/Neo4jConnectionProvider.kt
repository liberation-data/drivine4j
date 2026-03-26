package org.drivine.connection

import org.drivine.mapper.Neo4jResultMapper
import org.drivine.mapper.SubtypeRegistry
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Config
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Session
import org.neo4j.driver.SessionConfig
import java.util.concurrent.TimeUnit

class Neo4jConnectionProvider(
    override val name: String,
    override val type: DatabaseType,
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String?,
    private val database: String?,
    private val protocol: String = "bolt",
    private val config: Map<String, Any>,
    override val subtypeRegistry: SubtypeRegistry? = null
) : ConnectionProvider {

    private val driver: Driver = GraphDatabase.driver(
        "$protocol://$host:$port",
        AuthTokens.basic(user, password ?: ""),
        Config.builder().apply {
            (config["connectionTimeout"] as? Int)?.let {
                withConnectionTimeout(it.toLong(), TimeUnit.MILLISECONDS)
            }
            (config["maxConnectionPoolSize"] as? Int)?.let {
                withMaxConnectionPoolSize(it)
            }
        }.build()
    )

    override fun connect(): Connection {
        val session: Session = driver.session(SessionConfig.forDatabase(database ?: "neo4j"))
        val connection = Neo4jConnection(session, Neo4jResultMapper(subtypeRegistry), subtypeRegistry)
        return connection
    }

    override fun end() {
        driver.close()
    }
}
