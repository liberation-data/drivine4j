package drivine.connection

import org.springframework.core.io.Resource
import java.util.*

data class ConnectionProperties(
    val databaseType: DatabaseType,
    val host: String,
    val port: Int? = null,
    val userName: String? = null,
    val password: String? = null,
    val idleTimeout: Int? = null,
    val connectionTimeout: Int? = null,
    val poolMax: Int? = null,
    val databaseName: String? = null,
    val defaultGraphPath: String? = null,
    val protocol: String? = null
) {
    companion object {
        fun fromProperties(properties: Properties, connectionName: String? = null): ConnectionProperties {
            val prefix = connectionName?.let { "${it}_" } ?: ""

            fun getString(key: String): String? = properties.getProperty("$prefix$key")
            fun getInt(key: String): Int? = properties.getProperty("$prefix$key")?.toIntOrNull()

            val databaseType = getString("DATABASE_TYPE")?.let { DatabaseType.valueOf(it) }
                ?: throw IllegalArgumentException("${prefix}DATABASE_TYPE is required.")
            val host = getString("DATABASE_HOST")
                ?: throw IllegalArgumentException("${prefix}DATABASE_HOST is required.")

            val databaseName = getString("DATABASE_NAME")
            if (databaseType == DatabaseType.AGENS_GRAPH && databaseName == null) {
                throw IllegalArgumentException("${prefix}DATABASE_NAME is required for AgensGraph.")
            }

            return ConnectionProperties(
                databaseType = databaseType,
                host = host,
                port = getInt("DATABASE_PORT"),
                userName = getString("DATABASE_USER"),
                password = getString("DATABASE_PASSWORD"),
                idleTimeout = getInt("DATABASE_IDLE_TIMEOUT"),
                connectionTimeout = getInt("DATABASE_CONNECTION_TIMEOUT"),
                poolMax = getInt("DATABASE_POOL_MAX"),
                databaseName = databaseName,
                defaultGraphPath = getString("DATABASE_DEFAULT_GRAPH_PATH"),
                protocol = getString("DATABASE_PROTOCOL")
            )
        }
    }

    fun fromResource(resource: Resource, connectionName: String? = null): ConnectionProperties {
        val file = resource.file // Extracts the file path
        val properties = Properties().apply {
            file.inputStream().use { load(it) }
        }
        return fromProperties(properties, connectionName)
    }
}