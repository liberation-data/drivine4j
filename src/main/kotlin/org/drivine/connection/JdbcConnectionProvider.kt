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
import org.drivine.backend.sql.SqlGraph
import org.drivine.mapper.JdbcResultMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.UnsupportedSchemaGrammar
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.WeakHashMap
import javax.sql.DataSource

/**
 * Hands out [JdbcConnection]s from a [DataSource]: the application's own, or a pool this provider
 * makes and closes (see [pooled]).
 *
 * The JDBC driver is the application's to supply. For Oracle that is `com.oracle.database.jdbc:ojdbc11`,
 * for Postgres `org.postgresql:postgresql`, and for SQLite `org.xerial:sqlite-jdbc` (see [sqlite]).
 *
 * On Oracle, Postgres and SQLite the provider makes the graph's tables (see [SqlGraph]), when they are
 * not there, before it hands out its first connection: whatever comes first to a new store, a Cypher
 * statement through a persistence manager, an object manager or a schema statement, finds the graph
 * there. It runs the statements an object manager's backend runs (`OracleGraph.ensured` and the
 * others), each of which looks for the store before it makes anything, so a user who may read the
 * tables but not make them works with a store that is there. It does so once for the provider, that
 * is for its graph over its data source, on a connection of its own in auto-commit mode, and never on
 * a caller's transaction. Threads that ask for their first connections at once wait for the one that
 * makes the tables; two processes are kept apart by the statements themselves, as two object
 * managers are: a lock of the graph's own on Postgres, `ORA-00955` taken as made and `ORA-00054`
 * waited out on Oracle, and SQLite's lock on the file.
 *
 * Where making them fails once a connection is had, it is logged as a warning and not tried again by
 * this provider: the connection is handed out, and a statement over tables that are not there says
 * so. A store reached as a user that cannot make tables, where the check finds nothing to go on, works
 * as before.
 *
 * Java sees a constructor for each arity, so a caller compiled against fewer parameters still builds after one is added
 * last.
 */
class JdbcConnectionProvider @JvmOverloads constructor(
    override val name: String,
    override val type: DatabaseType,
    private val dataSource: DataSource,
    override val subtypeRegistry: SubtypeRegistry? = null,
    /** Whether [end] closes [dataSource]. False for a data source the application made. */
    private val ownsDataSource: Boolean = false,
    /** The graph the object managers read and write: it names the property graph and its tables. */
    override val graphName: String = DEFAULT_GRAPH,
    /**
     * Whether [connect] sets each Oracle session of the application's [dataSource] to make no adaptive plan, the first
     * time it connects with it (see [NO_ADAPTIVE_PLANS]). On by default: an adaptive plan can give a read of the graph
     * no rows (#289). False leaves the application's sessions as it set them. A pool this provider made runs the
     * statement as it opens a session either way.
     */
    private val setsNoAdaptivePlans: Boolean = true,
) : ConnectionProvider, NativeDriverSource<DataSource> {

    /** The graph Oracle, Postgres and SQLite keep in tables; null for another JDBC store, which keeps none. */
    private val graph: SqlGraph? by lazy {
        when (type) {
            DatabaseType.ORACLE -> OracleGraph(graphName)
            DatabaseType.POSTGRES -> PostgresGraph(graphName)
            DatabaseType.SQLITE -> SqliteGraph(graphName)
            else -> null
        }
    }

    /** Oracle, Postgres and SQLite keep a graph's indexes and constraints over its tables; another JDBC store has no graph. */
    override val schemaGrammar: SchemaGrammar by lazy {
        when (val graph = graph) {
            is OracleGraph -> OracleSchemaGrammar(graph)
            is PostgresGraph -> PostgresSchemaGrammar(graph)
            is SqliteGraph -> SqliteSchemaGrammar(graph)
            else -> UnsupportedSchemaGrammar(type.value)
        }
    }

    /** Whether the graph's tables have been made, or found, by this provider: see [ensureGraph]. */
    @Volatile
    private var graphEnsured = false

    private val ensuringGraph = Any()

    /** The data source behind this provider. Closed by [end] only if this provider made it. */
    override val nativeDriver: DataSource
        get() = dataSource

    /**
     * Whether [connect] leaves each session of [dataSource] as it is: not Oracle, already set to make no adaptive plan (a
     * pool this provider made, or a HikariCP pool that runs [NO_ADAPTIVE_PLANS] as it opens a session, as [pooled] makes
     * one), or the application asked for its sessions to be left alone ([setsNoAdaptivePlans]).
     */
    private val leavesSessions: Boolean =
        !setsNoAdaptivePlans || type != DatabaseType.ORACLE || ownsDataSource || (dataSource as? HikariDataSource)?.connectionInitSql == NO_ADAPTIVE_PLANS

    /**
     * The sessions of the application's [dataSource] that [connect] has set, by the driver's connection behind a pool's
     * handle, so a session a pool hands out again is not set again. Weak, so a session the pool closes is let go.
     */
    private val setSessions: MutableSet<java.sql.Connection> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /**
     * Sets a session of the application's Oracle data source to make no adaptive plan, once (see [NO_ADAPTIVE_PLANS]).
     * A session that cannot be set is closed and its error thrown, rather than given out unset.
     */
    private fun withoutAdaptivePlans(connection: java.sql.Connection): java.sql.Connection {
        if (leavesSessions) return connection
        val session = runCatching { connection.unwrap(java.sql.Connection::class.java) }.getOrNull() ?: connection
        if (session in setSessions) return connection
        try {
            connection.createStatement().use { it.execute(NO_ADAPTIVE_PLANS) }
        } catch (e: Exception) {
            runCatching { connection.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        setSessions.add(session)
        return connection
    }

    /** A connection, the graph's tables made first if this is the provider's first (see the class). */
    override fun connect(): Connection {
        ensureGraph()
        return open()
    }

    /** A connection from [dataSource], its Oracle session set to make no adaptive plan (see [withoutAdaptivePlans]). */
    private fun open(): JdbcConnection = JdbcConnection(withoutAdaptivePlans(dataSource.connection), JdbcResultMapper(subtypeRegistry), type, graphName)

    /**
     * Makes the graph's tables when they are not there, once for this provider: after the first time,
     * a connection costs a read of a flag. A failure to get or to close the connection they are made
     * on is thrown, and the next connection tries again; a failure of the statements is logged, and
     * not tried again.
     */
    private fun ensureGraph() {
        if (graphEnsured) return
        synchronized(ensuringGraph) {
            if (graphEnsured) return
            graph?.let { graph ->
                val connection = open()
                val failure = try {
                    graph.ensure(connection)
                    null
                } catch (e: Exception) {
                    e
                }
                connection.release(failure)
                failure?.let {
                    logger.warn(
                        "Could not make the tables of graph '{}' on {} database '{}'; statements over them will fail if they are not there.",
                        graphName, type, name, it,
                    )
                }
            }
            graphEnsured = true
        }
    }

    override fun end() {
        if (ownsDataSource) (dataSource as? AutoCloseable)?.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(JdbcConnectionProvider::class.java)

        /** The graph of a provider that is given no name for one. */
        const val DEFAULT_GRAPH = "drivine"

        /**
         * Run in each Oracle session Drivine opens or is given: Oracle makes no adaptive plan in it. An adaptive plan
         * picks its joins as it runs, and once the graph's tables had grown and shrunk without statistics, one gave no
         * rows for a join that filters a node by a label and a property, where the same plan, fixed, gave them all
         * (liberation-data/drivine4j#289). A walk's step already asks for no adaptive plan (#182); this covers every
         * statement. One statement can ask for an adaptive plan again: `/*+ OPT_PARAM('optimizer_adaptive_plans' 'true') */`.
         */
        const val NO_ADAPTIVE_PLANS = "ALTER SESSION SET OPTIMIZER_ADAPTIVE_PLANS = FALSE"

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
                // Each session the pool opens makes no adaptive plan. One whose statement fails is a connection that
                // failed: the pool closes it, and never hands it out.
                if (type == DatabaseType.ORACLE) connectionInitSql = NO_ADAPTIVE_PLANS
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
