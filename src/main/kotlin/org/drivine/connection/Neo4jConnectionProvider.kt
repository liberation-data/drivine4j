package org.drivine.connection

import org.drivine.mapper.Neo4jResultMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.TemporalCoercer
import org.drivine.query.grammar.CypherDialect
import org.neo4j.driver.AuthTokenManagers
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
    private val user: String?,
    private val password: String?,
    private val database: String?,
    private val protocol: String = "bolt",
    private val config: Map<String, Any>,
    override val subtypeRegistry: SubtypeRegistry? = null,
    override val cypherDialect: CypherDialect = CypherDialect.NEO4J_5,
) : ConnectionProvider {

    private val driver: Driver = run {
        val driverConfig = Config.builder().apply {
            (config["connectionTimeout"] as? Int)?.let {
                withConnectionTimeout(it.toLong(), TimeUnit.MILLISECONDS)
            }
            (config["maxConnectionPoolSize"] as? Int)?.let {
                withMaxConnectionPoolSize(it)
            }
            if (type == DatabaseType.NEPTUNE) {
                withEncryption()
                withTrustStrategy(Config.TrustStrategy.trustAllCertificates())
            }
        }.build()

        when {
            // Neptune with IAM auth
            type == DatabaseType.NEPTUNE && config["neptuneAuth"] == NeptuneAuthMode.IAM -> {
                val sigV4 = NeptuneSigV4AuthProvider(host, port, config["region"] as? String ?: "us-east-1")
                val authManager = AuthTokenManagers.basic { sigV4.authToken() }
                GraphDatabase.driver("$protocol://$host:$port", authManager, driverConfig)
            }
            // Neptune without auth (tunnel)
            type == DatabaseType.NEPTUNE -> {
                GraphDatabase.driver("$protocol://$host:$port", AuthTokens.none(), driverConfig)
            }
            // Neo4j — basic auth
            else -> {
                GraphDatabase.driver("$protocol://$host:$port", AuthTokens.basic(user!!, password ?: ""), driverConfig)
            }
        }
    }

    override fun connect(): Connection {
        val session: Session = if (database != null && type != DatabaseType.NEPTUNE) {
            driver.session(SessionConfig.forDatabase(database))
        } else {
            driver.session()
        }
        // Neptune lacks native datetime support, so temporals are coerced to ISO strings — the same
        // representation FalkorDB uses — keeping write and param paths consistent. Neo4j and Memgraph
        // handle native temporals directly, so no coercer is attached.
        val coercers = if (type == DatabaseType.NEPTUNE) listOf(TemporalCoercer) else emptyList()
        return Neo4jConnection(session, Neo4jResultMapper(subtypeRegistry), subtypeRegistry, coercers)
    }

    override fun end() {
        driver.close()
    }

}