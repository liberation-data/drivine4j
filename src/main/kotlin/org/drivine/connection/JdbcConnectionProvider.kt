package org.drivine.connection

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sqlite.SqliteDataSource
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteSchemaGrammar
import org.drivine.mapper.JdbcResultMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.UnsupportedSchemaGrammar
import javax.sql.DataSource

/**
 * Hands out [JdbcConnection]s from a [DataSource]: the application's own, or a pool this provider
 * makes and closes (see [pooled]).
 *
 * The JDBC driver is the application's to supply. For Oracle that is `com.oracle.database.jdbc:ojdbc11`,
 * for Postgres `org.postgresql:postgresql`, and for SQLite `org.xerial:sqlite-jdbc` (see [sqlite]).
 */
class JdbcConnectionProvider(
    override val name: String,
    override val type: DatabaseType,
    private val dataSource: DataSource,
    override val subtypeRegistry: SubtypeRegistry? = null,
    /** Whether [end] closes [dataSource]. False for a data source the application made. */
    private val ownsDataSource: Boolean = false,
    /** The graph the object managers read and write: it names the property graph and its tables. */
    override val graphName: String = DEFAULT_GRAPH,
) : ConnectionProvider, NativeDriverSource<DataSource> {

    /** Oracle, Postgres and SQLite keep a graph's indexes and constraints over its tables; another JDBC store has no graph. */
    override val schemaGrammar: SchemaGrammar by lazy {
        when (type) {
            DatabaseType.ORACLE -> OracleSchemaGrammar(OracleGraph(graphName))
            DatabaseType.POSTGRES -> PostgresSchemaGrammar(PostgresGraph(graphName))
            DatabaseType.SQLITE -> SqliteSchemaGrammar(SqliteGraph(graphName))
            else -> UnsupportedSchemaGrammar(type.value)
        }
    }

    /** The data source behind this provider. Closed by [end] only if this provider made it. */
    override val nativeDriver: DataSource
        get() = dataSource

    override fun connect(): Connection = JdbcConnection(dataSource.connection, JdbcResultMapper(subtypeRegistry), type, graphName)

    override fun end() {
        if (ownsDataSource) (dataSource as? AutoCloseable)?.close()
    }

    companion object {
        /** The graph of a provider that is given no name for one. */
        const val DEFAULT_GRAPH = "drivine"

        /** The statements the Oracle driver keeps prepared on each connection. */
        private const val STATEMENT_CACHE = 64

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
            graphName: String = DEFAULT_GRAPH,
        ): JdbcConnectionProvider {
            val config = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.username = user
                this.password = password
                this.poolName = "drivine-$name"
                poolMax?.let { maximumPoolSize = it }
                connectionTimeoutMillis?.let { connectionTimeout = it.toLong() }
                idleTimeoutMillis?.let { idleTimeout = it.toLong() }
                // A save is the same block each time. Kept by the driver, it is neither sent nor parsed again.
                if (type == DatabaseType.ORACLE) addDataSourceProperty("oracle.jdbc.implicitStatementCacheSize", STATEMENT_CACHE.toString())
                // A string is sent with no type, as a literal is, so the statement says what it is: `jsonb`, a number, a date.
                if (type == DatabaseType.POSTGRES) addDataSourceProperty("stringtype", "unspecified")
            }
            return JdbcConnectionProvider(name, type, HikariDataSource(config), subtypeRegistry, ownsDataSource = true, graphName = graphName)
        }

        /**
         * A provider over a pool of connections to the SQLite file at [path], made if it is not there,
         * closed by [end]. Each connection is opened as Drivine's statements want it, with the functions
         * they call registered on it (see `SqliteDataSource`): the driver is xerial's,
         * `org.xerial:sqlite-jdbc`, which the application adds. SQLite 3.45 or later.
         *
         * A path that names no file is refused: blank, or an in-memory database. Each connection of the
         * pool would open a database of its own, and a graph made on one would not be there on the next.
         *
         * @param busyTimeoutMillis how long a connection waits for another to let go of the file
         */
        @JvmStatic
        @JvmOverloads
        fun sqlite(
            name: String,
            path: String,
            poolMax: Int? = null,
            busyTimeoutMillis: Int? = null,
            subtypeRegistry: SubtypeRegistry? = null,
            graphName: String = DEFAULT_GRAPH,
        ): JdbcConnectionProvider {
            require(path.isNotBlank() && ":memory:" !in path && "mode=memory" !in path) {
                "SQLite's path '$path' names no file: each pooled connection would open a database of its own. Give the path of a file."
            }
            val config = HikariConfig().apply {
                dataSource = busyTimeoutMillis?.let { SqliteDataSource(path, it) } ?: SqliteDataSource(path)
                poolName = "drivine-$name"
                poolMax?.let { maximumPoolSize = it }
            }
            return JdbcConnectionProvider(name, DatabaseType.SQLITE, HikariDataSource(config), subtypeRegistry, ownsDataSource = true, graphName = graphName)
        }

        /** The thin-driver URL of an Oracle service. */
        @JvmStatic
        fun oracleUrl(host: String, port: Int, service: String): String = "jdbc:oracle:thin:@//$host:$port/$service"

        /** The URL of a Postgres database. */
        @JvmStatic
        fun postgresUrl(host: String, port: Int, database: String): String = "jdbc:postgresql://$host:$port/$database"
    }
}
