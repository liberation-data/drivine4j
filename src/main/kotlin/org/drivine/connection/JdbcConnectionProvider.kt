package org.drivine.connection

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.pool.ProxyConnection
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sqlite.SqliteDataSource
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteSchemaGrammar
import org.drivine.backend.sqlite.SqliteVersion
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
 * is for its graph over its data source, on a connection of its own set to auto-commit mode for it,
 * whatever the data source's connections start in, and never on a caller's transaction: where that connection is a
 * Spring transaction's, handed out by a transaction-aware data source, they are left for the first connection had
 * outside one, and until then a Cypher statement that finds them missing fails saying so (#395). Threads that ask for their first connections at once wait for the one that
 * makes the tables; two processes are kept apart by the statements themselves, as two object
 * managers are: a lock of the graph's own on Postgres, `ORA-00955` taken as made and `ORA-00054`
 * waited out on Oracle, and SQLite's lock on the file. A block turned away all the same by a
 * deadlock with another session's, as with other pools' first writes (`ORA-04020`, `ORA-00060`,
 * `40P01`), is run again after a pause (#450).
 *
 * Where making them fails once a connection is had, it is logged as a warning, and the connection is
 * handed out: a statement over tables that are not there says so. The graph is not taken as made, so
 * the provider's next connection tries again; not where the user may not make them, or the store may
 * not be written (see [cannotMake]), which would be refused alike on every connection. A store reached
 * as a user that cannot make tables, where the check finds nothing to go on, works as before. So does a graph name that cannot name tables: plain SQL works whatever the name, and
 * Cypher over the graph is refused with why. So does an Oracle older than 23.9, found from the first
 * connection's metadata: plain SQL works, and Cypher, which needs Oracle's `UUID()`, is refused with why. So does a SQLite
 * older than 3.53.2, found the first time a Cypher statement is compiled (see [SqliteVersion]).
 *
 * Each connection it hands out commits each statement run outside a transaction, as a connection that commits each
 * one of itself does, also where the data source's connections start with `autoCommit = false`: it is turned on as
 * the connection is had, and back off as it is given back. Not where the connection is a transaction's of the
 * application's own, a Spring transaction's handed out by a transaction-aware data source or a distributed (JTA)
 * one's: what Drivine runs on it is that transaction's to commit (see [committingEach], #395).
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

    /** A graph name that cannot name tables holds no schema: plain SQL over this provider works whatever the name (#364). */
    override val schemaRefusal: String?
        get() = graphRefusal(type, graphName)

    /** Whether the graph's tables have been made, or found, by this provider: see [ensureGraph]. Set after a failure only where [cannotMake]. */
    @Volatile
    private var graphEnsured = false

    private val ensuringGraph = Any()

    /**
     * Why this store runs no Cypher, found as the graph's tables are made: an Oracle older than 23.9 (see [oracleRefusal]).
     * Each connection is still handed out, and plain SQL runs; a Cypher statement is refused with this.
     */
    @Volatile
    private var cypherRefusal: String? = null

    /** On SQLite, the version its connections run, read once for the provider: compiled Cypher needs 3.53.2 (see [SqliteVersion]). */
    private val sqliteVersion = SqliteVersion()

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

    /**
     * A connection from [dataSource], its Oracle session set to make no adaptive plan (see [withoutAdaptivePlans]), that
     * commits each statement run outside a transaction (see [committingEach]). A session whose mode cannot be read is
     * closed and its error thrown, rather than given out.
     */
    private fun open(): JdbcConnection {
        val session = withoutAdaptivePlans(dataSource.connection)
        val turned = try {
            committingEach(session)
        } catch (e: Exception) {
            runCatching { session.close() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        return JdbcConnection(session, JdbcResultMapper(subtypeRegistry), type, graphName, cypherRefusal = cypherRefusal)
            .also {
                it.restoresAutoCommit = turned
                if (type == DatabaseType.SQLITE) it.sqliteVersion = sqliteVersion
                if (type == DatabaseType.POSTGRES) evictable(it, session)
                if (tablesHeldBack && !graphEnsured) it.tablesPending = tablesPending()
            }
    }

    /** The HikariCP pool [dataSource] is, or wraps; null where it is another, which has no way to close a session for good. */
    private val hikari: HikariDataSource? by lazy {
        runCatching { if (dataSource.isWrapperFor(HikariDataSource::class.java)) dataSource.unwrap(HikariDataSource::class.java) else null }.getOrNull()
    }

    /**
     * Sets whether an index build on [connection] closes its [session] for good after it, out of its pool, or runs with
     * no parallel workers (see [JdbcConnection.buildingIndex]): it is closed only where the session is HikariCP's and no
     * caller's transaction holds it. One that commits nothing of itself after [committingEach] is a Spring or distributed
     * transaction's.
     */
    private fun evictable(connection: JdbcConnection, session: java.sql.Connection) {
        val pool = hikari
        connection.noEviction = when {
            !session.autoCommit -> "its connection is a transaction's that Drivine did not begin"
            pool == null -> "its data source is not a HikariCP pool, which can close a session for good"
            session !is ProxyConnection -> "its data source hands out HikariCP's sessions wrapped, and only one of HikariCP's own can be closed for good"
            else -> null
        }
        connection.evictor = pool?.let { p -> { s -> p.evictConnection(s) } }
        connection.evictionRefused = ::refusedEviction
    }

    /** Whether a build that ran with no parallel workers has been logged: see [refusedEviction]. */
    @Volatile
    private var loggedRefusedEviction = false

    /** Logs, once, that an index build ran with no parallel workers, and why ([reason]). */
    private fun refusedEviction(reason: String) {
        if (loggedRefusedEviction) return
        loggedRefusedEviction = true
        logger.debug(
            "An index build on Postgres database '{}' runs with no parallel workers, as {}: a session that built in parallel keeps shared memory as long as its pool keeps it (liberation-data/drivine4j#626).",
            name, reason,
        )
    }

    /** Whether the graph's tables were last left unmade, as the connection they were to be made on was a Spring transaction's. */
    @Volatile
    private var tablesHeldBack = false

    /** Why a Cypher statement on a store with no graph finds no tables, while [tablesHeldBack], and what makes them. */
    private fun tablesPending(): String =
        "The tables of graph '$graphName' on $type database '$name' are not made yet: they are not made on a Spring transaction's connection, which making them would commit partway through, and this provider has had no connection outside one. Have a connection of it outside a Spring transaction first, as with a statement at the application's start, and they are made."

    /** Whether a connection that is a distributed transaction's, which refused to commit each statement, has been logged. */
    @Volatile
    private var warnedOfEnlisted = false

    /**
     * Turns [session] to commit each statement as it runs, where its pool gave it committing nothing of itself
     * (`autoCommit = false`, as `spring.datasource.hikari.auto-commit=false` sets): a statement Drivine runs outside a
     * transaction would otherwise be rolled back as the connection goes back to the pool, after it was reported done
     * (#395). Whether it did: the connection is then given back as the pool gave it (see [JdbcConnection.release]). A
     * Drivine transaction on it turns it off and back on, as on any other.
     *
     * Turning it on commits what the session has pending, so it is not turned on where the session is another
     * transaction's, and what Drivine runs on it is left to that transaction to commit or roll back:
     * - a Spring transaction's, handed out by Spring's `TransactionAwareDataSourceProxy` (see [SpringTransactions]);
     * - a distributed (JTA) transaction's, where JDBC refuses to turn it on: that refusal is logged once, as a warning.
     *
     * A connection that is neither, and which its pool hands out with nothing pending, as a pool does, commits nothing
     * of anyone's as it is turned on. One that commits each statement already is left as it is, at the cost of reading
     * its mode, which HikariCP and the drivers answer without asking the database.
     */
    private fun committingEach(session: java.sql.Connection): Boolean {
        if (session.autoCommit) return false
        if (SpringTransactions.holds(session)) {
            logger.debug("A connection of {} database '{}' is a Spring transaction's: what Drivine runs on it outside a transaction of its own is the Spring transaction's to commit.", type, name)
            return false
        }
        try {
            session.autoCommit = true
        } catch (e: java.sql.SQLException) {
            if (!warnedOfEnlisted) {
                warnedOfEnlisted = true
                logger.warn(
                    "A connection of {} database '{}' commits nothing of itself, and refused to commit each statement, as JDBC refuses on a connection in a distributed (JTA) transaction: what Drivine runs on it outside a transaction of its own is left to that transaction to commit.",
                    type, name, e,
                )
            }
            return false
        }
        return true
    }

    /**
     * Makes the graph's tables when they are not there, once for this provider: after the first time
     * they are made, a connection costs a read of a flag. A failure to get or to close the connection
     * they are made on, or to set it to commit each statement, is thrown, and the next connection tries
     * again. A failure of the statements, once the graph has run them again for as long as another
     * session's deadlocked with them (see `DdlContention`), is logged, the connection is handed out, and
     * the next connection tries again: the graph is marked made only once they have run (#450), or once
     * they are refused as the user's or the store's to make none ([cannotMake]). Threads
     * that ask for a connection meanwhile wait for the one making the tables, and after a failure the
     * first of them tries again.
     *
     * A graph name that cannot name tables makes none, and is logged: every connection is handed out,
     * as before the provider made tables, and a Cypher statement over the graph is refused, naming the
     * graph and why (#364).
     *
     * A SQLite graph whose tables an earlier Drivine made, which give a deleted row's id again, is refused with
     * [ReusedIdsException] on every connection, and no connection is handed out (#713).
     */
    private fun ensureGraph() {
        if (graphEnsured) return
        synchronized(ensuringGraph) {
            if (graphEnsured) return
            val refusal = graphRefusal(type, graphName)
            if (refusal != null) {
                logger.warn("No tables made for graph '{}' on {} database '{}': {} Plain SQL works; Cypher, object managers and schema over the graph do not. Give the provider a graph name that can name tables.", graphName, type, name, refusal)
                graphEnsured = true
            } else {
                graphEnsured = graph?.let(::makeTables) ?: true
            }
        }
    }

    /** Whether a failure to make the tables has been logged as a warning: those after it are logged at debug. */
    private var warnedOfTables = false

    /**
     * Runs [graph]'s statements on a connection of its own that commits each statement, whatever the data source's
     * connections do of themselves: on one that commits nothing (`autoCommit = false`, as an application's pool may be
     * set), what they make would be rolled back as the connection goes back to its pool (#365). The connection is put
     * back as it was before it is closed. Where the data source hands out a Spring transaction's connection instead, only the
     * reads of [SqlGraph.refuse] are run on it, so that a store [ReusedIdsException] refuses is refused there too (#713),
     * and the tables are left for the next connection ([tablesHeldBack], #395).
     *
     * On Oracle it first reads the database's version from the session's metadata, which needs no grant: see
     * [oracleRefusal].
     *
     * Whether the statements ran, or are not to be run again ([cannotMake]): a failure of theirs is logged, but for a
     * [ReusedIdsException], which is thrown. Called under [ensuringGraph].
     */
    private fun makeTables(graph: SqlGraph): Boolean {
        val session = withoutAdaptivePlans(dataSource.connection)
        if (type == DatabaseType.ORACLE) checkVersion(session)
        val connection = JdbcConnection(session, JdbcResultMapper(subtypeRegistry), type, graphName)
        tablesHeldBack = false
        val failure = try {
            val committing = session.autoCommit
            // A Spring transaction's connection, from a transaction-aware data source, is not turned to commit each
            // statement: that would commit the transaction partway through (#395). The tables are made on the next
            // connection had outside one.
            if (!committing && SpringTransactions.holds(session)) {
                // A look alone, which commits nothing: a store an earlier Drivine made is refused here too (#713). A look
                // that fails refuses nothing, as where the tables are made: the next connection looks again.
                val looked = try {
                    graph.refuse(connection)
                    null
                } catch (e: Exception) {
                    if (e is ReusedIdsException) throw e
                    e
                }
                connection.release()
                tablesHeldBack = true
                logger.debug("The tables of graph '{}' on {} database '{}' are not made on a Spring transaction's connection: the next connection tries again.", graphName, type, name, looked)
                return false
            }
            // Turning auto-commit on commits what the session has pending. A connection just had from the pool has
            // nothing pending: only the provider's own `ALTER SESSION`, which commits nothing.
            if (!committing) session.autoCommit = true
            val refused = try {
                graph.ensure(connection)
                null
            } catch (e: Exception) {
                e
            }
            if (!committing) {
                try {
                    session.autoCommit = false
                } catch (e: Exception) {
                    refused?.let(e::addSuppressed)
                    throw e
                }
            }
            refused
        } catch (e: Exception) {
            runCatching { connection.release(e) }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        connection.release(failure)
        if (failure == null) return true
        // A SQLite file whose tables give a deleted row's id again is refused, on every connection: not a failure to make them.
        if (failure is ReusedIdsException) throw failure
        if (cannotMake(type, failure)) {
            logger.warn(
                "Could not make the tables of graph '{}' on {} database '{}': the user may not make them, or the database may not be written. Statements over them will fail if they are not there. The provider does not try again.",
                graphName, type, name, failure,
            )
            return true
        }
        val message = "Could not make the tables of graph '{}' on {} database '{}'; statements over them will fail if they are not there. The next connection tries again."
        if (warnedOfTables) {
            logger.debug(message, graphName, type, name, failure)
        } else {
            logger.warn(message, graphName, type, name, failure)
            warnedOfTables = true
        }
        return false
    }

    /**
     * Finds, from [session]'s metadata, whether the Oracle behind it is too old for Cypher, and logs it if so. Metadata that
     * cannot be read refuses nothing: the database says what is missing when a statement needs it.
     */
    private fun checkVersion(session: java.sql.Connection) {
        cypherRefusal = try {
            session.metaData?.let(::oracleRefusal)
        } catch (e: Exception) {
            logger.debug("Could not read the version of {} database '{}'.", type, name, e)
            null
        }
        cypherRefusal?.let { logger.warn("{} database '{}': {}", type, name, it) }
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

        /**
         * Why Drivine runs no Cypher on the Oracle [metaData] describes: one older than 23.9, which has no `UUID()`, the
         * random version 4 UUID compiled Cypher's `randomUUID()` is (#401). Null for 23.9 or later. The driver gives 23.26.3
         * as major version 23, minor version 26.
         */
        internal fun oracleRefusal(metaData: java.sql.DatabaseMetaData): String? {
            val major = metaData.databaseMajorVersion
            val minor = metaData.databaseMinorVersion
            if (major > ORACLE_MAJOR || (major == ORACLE_MAJOR && minor >= ORACLE_MINOR)) return null
            // "... Release 23.8.0.25.04 - ...\nVersion 23.8.0.25.04": the full version, where the driver gives it.
            val version = Regex("""\d+(?:\.\d+)+""").findAll(metaData.databaseProductVersion.orEmpty()).lastOrNull()?.value ?: "$major.$minor"
            return "Drivine needs Oracle $ORACLE_MAJOR.$ORACLE_MINOR or later (it uses UUID()); this database is $version. " +
                "Upgrade, or use an Oracle Free image $ORACLE_MAJOR.$ORACLE_MINOR or newer. Plain SQL over this provider still runs; Cypher does not."
        }

        /**
         * Whether [failure], or a cause under it, says the tables of a [type] store are not this provider's to make: the
         * user may not make them (`ORA-01031`, `42501`), or the database may not be written (`ORA-16000`, a hot standby's
         * `25006`). Run again on the next connection, the block would be refused alike, on every connection: a user that
         * may read a store but not make it, where the block finds it to make, would pay for it on each.
         *
         * It is taken so for this provider only, not for the store: a grant given later, or a standby made primary, is
         * found by the next provider made over the store, as when the application restarts.
         */
        internal fun cannotMake(type: DatabaseType, failure: Throwable): Boolean =
            generateSequence(failure) { it.cause }.take(MAX_CAUSE_DEPTH).any {
                it is java.sql.SQLException && when (type) {
                    DatabaseType.ORACLE -> it.errorCode in ORACLE_CANNOT_MAKE
                    DatabaseType.POSTGRES -> it.sqlState in POSTGRES_CANNOT_MAKE
                    else -> false
                }
            }

        /** Insufficient privileges, and a database open for reading only. */
        private val ORACLE_CANNOT_MAKE = setOf(1031, 16000)

        /** Insufficient privilege, and a statement that writes in a transaction that may only read. */
        private val POSTGRES_CANNOT_MAKE = setOf("42501", "25006")

        /** How far down a failure's causes the store's own error is looked for. */
        private const val MAX_CAUSE_DEPTH = 10

        /** The oldest Oracle Drivine runs Cypher on, 23.9: see [oracleRefusal]. */
        private const val ORACLE_MAJOR = 23
        private const val ORACLE_MINOR = 9

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
         * `org.xerial:sqlite-jdbc`, which the application adds. SQLite 3.53.2 or later, `sqlite-jdbc` 3.53.2.0 or later:
         * on an older SQLite, Cypher is refused with why, and plain SQL runs (see [SqliteVersion]).
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
