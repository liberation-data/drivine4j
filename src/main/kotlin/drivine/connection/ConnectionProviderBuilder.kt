package drivine.connection

import drivine.DrivineException
import org.slf4j.LoggerFactory

class ConnectionProviderBuilder(private val registry: DatabaseRegistry) {
    private val logger = LoggerFactory.getLogger(ConnectionProviderBuilder::class.java)

    // Common properties
    private var type: DatabaseType? = null
    private var host: String? = null
    private var port: Int? = null
    private var userName: String? = null
    private var password: String? = null
    private var protocol: String? = null
    private var poolMax: Int? = 40

    // AgensGraph properties
    private var idleTimeout: Int? = null
    private var connectionTimeout: Int? = 5000
    private var name: String? = null
    private var defaultGraphPath: String? = null

    fun withType(type: DatabaseType): ConnectionProviderBuilder {
        requireNotNull(type) { "Database type argument is required" }
        this.type = type
        return this
    }

    fun host(host: String): ConnectionProviderBuilder = apply { this.host = host }
    fun port(port: Int): ConnectionProviderBuilder = apply { this.port = port }
    fun userName(userName: String): ConnectionProviderBuilder = apply { this.userName = userName }
    fun password(password: String): ConnectionProviderBuilder = apply { this.password = password }
    fun protocol(protocol: String): ConnectionProviderBuilder = apply { this.protocol = protocol }
    fun idleTimeout(idleTimeout: Int): ConnectionProviderBuilder = apply { this.idleTimeout = idleTimeout }
    fun databaseName(name: String): ConnectionProviderBuilder = apply { this.name = name }
    fun defaultGraphPath(path: String): ConnectionProviderBuilder = apply { this.defaultGraphPath = path }

    fun withProperties(properties: ConnectionProperties): ConnectionProviderBuilder {
        properties.type.let { withType(it) }
        properties.userName?.let {userName(it) }
        properties.password?.let { password(it) }
        properties.host.let { host(it) }
        properties.port?.let { port(it) }
        properties.databaseName?.let { databaseName(it) }
        return this
    }

    fun register(name: String = "default"): ConnectionProvider {
        registry.connectionProvider(name)?.let { return it }
        requireNotNull(host) { "Host config is required" }

        val provider = when (type) {
//            DatabaseType.AGENS_GRAPH, DatabaseType.POSTGRES -> buildAgensGraphAndPostgresProvider(name)
            DatabaseType.NEO4J -> buildNeo4jProvider(name)
//            DatabaseType.NEPTUNE -> buildNeptuneProvider(name)
            else -> throw DrivineException("Type $type is not supported by ConnectionProviderBuilder")
        }

        registry.register(provider)
        return registry.connectionProvider(name)!!
    }

//    private fun buildAgensGraphAndPostgresProvider(name: String): ConnectionProvider {
//        val resolvedPort = port ?: 5432
//        if (resolvedPort != 5432) {
//            logger.warn("$resolvedPort is a non-standard port for AgensGraph/Postgres.")
//        }
//        requireNotNull(this.name) { "Database name is required" }
//
//        return AgensGraphConnectionProvider(
//            name, type!!, defaultGraphPath,
//            ConnectionConfig(
//                host!!, userName, password, this.name!!, resolvedPort, idleTimeout ?: 500,
//                connectionTimeout, poolMax
//            )
//        )
//    }

    private fun buildNeo4jProvider(name: String): ConnectionProvider {
        if (protocol == null) {
            protocol = "bolt"
        }
        requireNotNull(userName) { "Neo4j requires a username" }

        if (idleTimeout != null) {
            logger.warn("idleTimeout is not supported by Neo4j")
        }

        val resolvedPort = port ?: 7687
        if (resolvedPort != 7687) {
            logger.warn("$resolvedPort is a non-standard port for Neo4j")
        }

        return Neo4jConnectionProvider(
            name, type!!, host!!, resolvedPort, userName!!, password, this.name, protocol!!,
            mapOf(
                "connectionTimeout" to connectionTimeout,
                "maxConnectionPoolSize" to poolMax
            )
                .filterValues { it != null }
                .mapValues { it.value as Any }
        )
    }

//    private fun buildNeptuneProvider(name: String): ConnectionProvider {
//        userName?.let { logger.warn("userName is not supported by Neptune") }
//        password?.let { logger.warn("password is not supported by Neptune") }
//        idleTimeout?.let { logger.warn("idleTimeout is not supported by Neptune") }
//
//        val resolvedPort = port ?: 8182
//        if (resolvedPort != 8182) {
//            logger.warn("$resolvedPort is a non-standard port for Neptune")
//        }
//
//        return NeptuneConnectionProvider(
//            name, type!!, host!!, resolvedPort, protocol,
//            ConnectionOptions(connectionTimeout)
//        )
//    }
}


