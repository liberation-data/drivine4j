package org.drivine.connection

import org.drivine.DrivineException
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.grammar.CypherDialect
import org.slf4j.LoggerFactory

class ConnectionProviderBuilder(
    private val registry: DatabaseRegistry,
    private val subtypeRegistry: SubtypeRegistry? = null
) {
    private val logger = LoggerFactory.getLogger(ConnectionProviderBuilder::class.java)

    private var type: DatabaseType? = null
    private var host: String? = null
    private var port: Int? = null
    private var userName: String? = null
    private var password: String? = null
    private var protocol: String? = null
    private var poolMax: Int? = 40
    private var idleTimeout: Int? = null
    private var connectionTimeout: Int? = 5000
    private var name: String? = null
    private var defaultGraphPath: String? = null
    private var cypherDialect: CypherDialect? = null
    private var falkorDbTransactionMode: FalkorDbTransactionMode? = null

    fun withType(type: DatabaseType): ConnectionProviderBuilder = apply { this.type = type }
    fun host(host: String): ConnectionProviderBuilder = apply { this.host = host }
    fun port(port: Int): ConnectionProviderBuilder = apply { this.port = port }
    fun userName(userName: String): ConnectionProviderBuilder = apply { this.userName = userName }
    fun password(password: String): ConnectionProviderBuilder = apply { this.password = password }
    fun protocol(protocol: String): ConnectionProviderBuilder = apply { this.protocol = protocol }
    fun idleTimeout(idleTimeout: Int): ConnectionProviderBuilder = apply { this.idleTimeout = idleTimeout }
    fun databaseName(name: String): ConnectionProviderBuilder = apply { this.name = name }
    fun defaultGraphPath(path: String): ConnectionProviderBuilder = apply { this.defaultGraphPath = path }
    fun cypherDialect(dialect: CypherDialect): ConnectionProviderBuilder = apply { this.cypherDialect = dialect }

    fun withProperties(properties: ConnectionProperties): ConnectionProviderBuilder {
        properties.type.let { withType(it) }
        properties.userName?.let { userName(it) }
        properties.password?.let { password(it) }
        properties.host.let { host(it) }
        properties.port?.let { port(it) }
        properties.databaseName?.let { databaseName(it) }
        properties.protocol?.let { protocol(it) }
        properties.cypherDialect?.let { cypherDialect(it) }
        properties.falkorDbTransactionMode?.let { falkorDbTransactionMode = it }
        return this
    }

    fun register(name: String = "default"): ConnectionProvider {
        registry.connectionProvider(name)?.let { return it }
        requireNotNull(host) { "Host config is required" }

        val provider = when (type) {
            DatabaseType.NEO4J -> buildNeo4jProvider(name)
            DatabaseType.FALKORDB -> buildFalkorDbProvider(name)
            else -> throw DrivineException("Type $type is not supported by ConnectionProviderBuilder")
        }

        registry.register(provider)
        return registry.connectionProvider(name)!!
    }

    private fun buildNeo4jProvider(name: String): ConnectionProvider {
        if (protocol == null) protocol = "bolt"
        requireNotNull(userName) { "Neo4j requires a username" }
        if (idleTimeout != null) logger.warn("idleTimeout is not supported by Neo4j")

        val resolvedPort = port ?: 7687
        if (resolvedPort != 7687) logger.warn("$resolvedPort is a non-standard port for Neo4j")

        return Neo4jConnectionProvider(
            name = name,
            type = type!!,
            host = host!!,
            port = resolvedPort,
            user = userName!!,
            password = password,
            database = this.name,
            protocol = protocol!!,
            config = mapOf(
                "connectionTimeout" to connectionTimeout,
                "maxConnectionPoolSize" to poolMax
            ).filterValues { it != null }.mapValues { it.value as Any },
            subtypeRegistry = subtypeRegistry,
            cypherDialect = cypherDialect ?: CypherDialect.NEO4J_5,
        )
    }

    private fun buildFalkorDbProvider(name: String): ConnectionProvider {
        val resolvedPort = port ?: 6379
        if (resolvedPort != 6379) logger.warn("$resolvedPort is a non-standard port for FalkorDB")

        val resolvedDialect = cypherDialect ?: CypherDialect.OPEN_CYPHER
        if (resolvedDialect == CypherDialect.NEO4J_4 || resolvedDialect == CypherDialect.NEO4J_5) {
            throw DrivineException("FalkorDB does not support $resolvedDialect. Use OPEN_CYPHER instead.")
        }

        return FalkorDbConnectionProvider(
            name = name,
            host = host!!,
            port = resolvedPort,
            password = password,
            graphName = this.name ?: "graph",
            transactionMode = falkorDbTransactionMode ?: FalkorDbTransactionMode.WARN,
            subtypeRegistry = subtypeRegistry,
            cypherDialect = resolvedDialect,
        )
    }
}
