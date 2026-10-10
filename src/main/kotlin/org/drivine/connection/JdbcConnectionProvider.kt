package org.drivine.connection

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.drivine.mapper.JdbcResultMapper
import org.drivine.mapper.SubtypeRegistry
import javax.sql.DataSource

/**
 * Hands out [JdbcConnection]s from a [DataSource]: the application's own, or a pool this provider
 * makes and closes (see [pooled]).
 *
 * The JDBC driver is the application's to supply. For Oracle that is `com.oracle.database.jdbc:ojdbc11`.
 */
class JdbcConnectionProvider(
    override val name: String,
    override val type: DatabaseType,
    private val dataSource: DataSource,
    override val subtypeRegistry: SubtypeRegistry? = null,
    /** Whether [end] closes [dataSource]. False for a data source the application made. */
    private val ownsDataSource: Boolean = false,
) : ConnectionProvider, NativeDriverSource<DataSource> {

    /** The data source behind this provider. Closed by [end] only if this provider made it. */
    override val nativeDriver: DataSource
        get() = dataSource

    override fun connect(): Connection = JdbcConnection(dataSource.connection, JdbcResultMapper(subtypeRegistry))

    override fun end() {
        if (ownsDataSource) (dataSource as? AutoCloseable)?.close()
    }

    companion object {
        /** A provider over a connection pool of its own, closed by [end]. */
        @JvmStatic
        @JvmOverloads
        fun pooled(
            name: String,
            type: DatabaseType,
            jdbcUrl: String,
            user: String?,
            password: String?,
            poolMax: Int? = null,
            connectionTimeoutMillis: Int? = null,
            idleTimeoutMillis: Int? = null,
            subtypeRegistry: SubtypeRegistry? = null,
        ): JdbcConnectionProvider {
            val config = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.username = user
                this.password = password
                this.poolName = "drivine-$name"
                poolMax?.let { maximumPoolSize = it }
                connectionTimeoutMillis?.let { connectionTimeout = it.toLong() }
                idleTimeoutMillis?.let { idleTimeout = it.toLong() }
            }
            return JdbcConnectionProvider(name, type, HikariDataSource(config), subtypeRegistry, ownsDataSource = true)
        }

        /** The thin-driver URL of an Oracle service. */
        @JvmStatic
        fun oracleUrl(host: String, port: Int, service: String): String = "jdbc:oracle:thin:@//$host:$port/$service"
    }
}
