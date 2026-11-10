package org.drivine.connection

import org.drivine.DrivineException
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleStoredFunctions
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteSql
import org.drivine.backend.sqlite.SqliteVersion
import org.drivine.cypher.sql.CompiledCypher
import org.drivine.logger.StatementLogger
import org.drivine.mapper.JdbcResultMapper
import org.drivine.mapper.ResultMapper
import org.drivine.query.JdbcParameterCoercer
import org.drivine.query.ParameterCoercer
import org.drivine.query.QueryLanguage
import org.drivine.query.QuerySpecification
import org.drivine.query.SpecCompiler
import org.drivine.query.SqlParameters
import org.drivine.query.TemporalCoercer
import org.slf4j.LoggerFactory
import java.io.StringReader
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.time.temporal.Temporal

/**
 * A connection to a database that speaks SQL, over JDBC.
 *
 * A statement names its parameters `$name`, as a Cypher statement does; they are bound by position,
 * once for each place a name occurs. A value is bound as the driver takes it: a string (on Oracle as
 * a CLOB past what a `VARCHAR2` holds), a character as a string, a null as a character null the database
 * converts to the type the statement wants, and anything else through `setObject`, so a number, a
 * boolean, a `java.time` value or a byte array. A collection or a map is bound as JSON text, and a
 * `ZonedDateTime` as its offset form, by [JdbcParameterCoercer] before it gets here. An array of
 * objects, or an enum, is not a bound value: the driver refuses it.
 *
 * What it returns is its result set, or for a PL/SQL block the first result set the block hands back
 * with `DBMS_SQL.RETURN_RESULT`. A block is how a statement that writes returns rows on Oracle: it is
 * one statement to the database, applied whole or not at all. On Postgres a statement that writes
 * returns rows itself, with `RETURNING`, or as a function's result.
 *
 * SQLite has no type for a moment, a date or a decimal: one is bound as its ISO text, which SQLite's
 * date functions read, and a decimal as the number it is. Nor has it a JSON type, so a column cannot say
 * it holds a document: a statement that opens with `/* drivine:json */`, as Drivine's own statements
 * over a SQLite graph do, has each of its text values read as the JSON it is, and any other statement's
 * text is text, as JSON in a character column is on the other stores (see [JdbcResultMapper.sqliteRows]).
 */
class JdbcConnection(
    private val connection: java.sql.Connection,
    private val resultMapper: ResultMapper,
    /** The database spoken to: Oracle binds a long string as a CLOB, which Postgres would take for a large object. */
    private val type: DatabaseType = DatabaseType.ORACLE,
    /** The graph a Cypher statement is compiled against: its tables are `<graph>_node` and `<graph>_edge`. */
    private val graphName: String = JdbcConnectionProvider.DEFAULT_GRAPH,
    /**
     * On SQLite, how long a transaction may hold the file's write lock before its end is logged as a warning: the
     * lock is taken as the transaction begins, and every other writer to the file waits for it.
     */
    private val lockWarning: Duration = Duration.ofSeconds(5),
    /** Why the store runs no Cypher, an Oracle older than 23.9, as its provider found it; null where it runs Cypher. */
    private val cypherRefusal: String? = null,
) : Connection {

    private val logger = LoggerFactory.getLogger(JdbcConnection::class.java)
    private var inTransaction = false

    /**
     * Whether this connection is the connection of a transaction Drivine did not begin, which commits or rolls back what
     * runs on it: a Spring transaction's, handed out by Spring's `TransactionAwareDataSourceProxy`, or a distributed (JTA)
     * one's. Set by [JdbcConnectionProvider], where the connection still commits nothing of itself after it asked it to.
     *
     * A Drivine transaction on it joins that transaction, as a Spring `@Transactional` method joins one it is called in
     * (#724): it begins nothing, commits nothing, and leaves the connection committing nothing of itself, so the work the
     * application ran before it and after it stays the application's transaction's, to commit or roll back. Where it
     * rolls back, it marks that transaction to roll back at its end instead (see [markRollbackOnly]): the application's
     * transaction then rolls back, and Spring throws `UnexpectedRollbackException` where it was to commit.
     */
    internal var joined = false

    /**
     * Whether [release] turns the connection back to committing nothing of itself before it closes it: its pool gave it
     * so (`autoCommit = false`), and [JdbcConnectionProvider] turned it to commit each statement as it ran, as Drivine
     * runs a statement outside a transaction (#395).
     */
    internal var restoresAutoCommit = false

    /**
     * Why the graph's tables may not be there yet, as its provider left them unmade on a Spring transaction's connection
     * (#395); null where they were made, or tried for. A Cypher statement that finds them missing fails with this.
     */
    internal var tablesPending: String? = null

    /**
     * On SQLite, the version the connection runs, read the first time a Cypher statement is compiled: one older than 3.53.2
     * refuses compiled Cypher (see [SqliteVersion]). Its provider gives each of its connections the one it keeps, so it is
     * read once for the provider.
     */
    internal var sqliteVersion = SqliteVersion()

    /**
     * On Postgres, closes the session for good, out of its pool, where its pool can (HikariCP's `evictConnection`); null
     * where it cannot. See [buildingIndex].
     */
    internal var evictor: ((java.sql.Connection) -> Unit)? = null

    /** On Postgres, why an index build on this connection cannot have its session closed after it; null where it can. */
    internal var noEviction: String? = "it is not a Postgres connection"

    /** Called with [noEviction]'s reason where an index build runs with no parallel workers instead: its provider logs it once. */
    internal var evictionRefused: (String) -> Unit = {}

    /** Whether [release] closes the session for good rather than giving it back to its pool: an index build ran on it. */
    private var evicting = false

    /** When the open transaction began, by [System.nanoTime]. */
    private var startedAt = 0L

    /** Why [graphName] cannot name the tables a Cypher statement is compiled over on this store; null where it can. */
    private val refusedGraph: String? by lazy(LazyThreadSafetyMode.NONE) { graphRefusal(type, graphName) }

    override fun sessionId(): String = "jdbc-${System.identityHashCode(connection)}"

    override fun parameterCoercers(): List<ParameterCoercer> = listOf(JdbcParameterCoercer)

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val given = spec.finalizedCopy(QueryLanguage.SQL)
        val compiledFromCypher = given.statement!!.language == QueryLanguage.CYPHER
        val cypher = cypherCompiled(given)
        val finalizedSpec = cypher?.let { given.withSql(it.sql, it.parameters) } ?: given
        if (finalizedSpec.statement!!.language != QueryLanguage.SQL) {
            throw DrivineException("This database speaks SQL, and was given a ${finalizedSpec.statement!!.language} statement.", spec = spec)
        }
        val compiled = SpecCompiler(finalizedSpec).compile()
        val parameters = applyParameterCoercers(finalizedSpec, compiled.parameters)
        val positional = SqlParameters.positional(compiled.statement)
        val startTime = Instant.now()
        val statementLogger = StatementLogger(sessionId())

        try {
            val rows = connection.prepareStatement(positional.statement).use { statement ->
                // Oracle keeps a cursor's memory while the driver keeps its statement, and a compiled Cypher statement holds
                // megabytes of it: about 4 MB, and over 20 MB where it reads a text past 32 KB. Kept in the driver's cache, up
                // to 64 statements a session, a few dozen ran the instance out of PGA (ORA-04036, liberation-data/drivine4j#313).
                // Closed, its memory goes back. Drivine's own statements, a few the same each time, are still kept.
                if (compiledFromCypher && type == DatabaseType.ORACLE) statement.isPoolable = false
                positional.names.forEachIndexed { index, name ->
                    if (!parameters.containsKey(name)) throw DrivineException("The statement has a parameter \$$name, and no value is bound to it.", spec = spec)
                    bind(statement, index + 1, parameters[name])
                }
                // A query answers with a result set; a block that returns one is asked for it.
                if (statement.execute() || statement.moreResults) {
                    if (type == DatabaseType.SQLITE) {
                        val json = positional.statement.trimStart().startsWith(SqliteSql.JSON_ROWS)
                        statement.resultSet.use { JdbcResultMapper.sqliteRows(it, json, cypher?.nonFinite == true) }
                    } else {
                        statement.resultSet.use { JdbcResultMapper.rows(it, cypher?.nonFinite == true) }
                    }
                } else {
                    emptyList()
                }
            }
            val mapped = resultMapper.mapQueryResults(rows, finalizedSpec)
            statementLogger.log(spec, startTime)
            return mapped
        } catch (e: Exception) {
            statementLogger.log(spec, startTime, e)
            tablesPending?.takeIf { compiledFromCypher && missingTable(e) }?.let { throw DrivineException("$it ${e.message}", e, spec) }
            throw e
        }
    }

    /** Whether [e] is the store's error for a table that is not there. */
    private fun missingTable(e: Exception): Boolean =
        e is java.sql.SQLException && when (type) {
            DatabaseType.POSTGRES -> e.sqlState == "42P01"
            // A compiled write is a PL/SQL block, which reports the table among its own errors.
            DatabaseType.ORACLE -> e.errorCode == 942 || e.message.orEmpty().contains("ORA-00942")
            DatabaseType.SQLITE -> e.message.orEmpty().contains("no such table")
            else -> false
        }

    /**
     * A Cypher statement compiled to the store's SQL over the graph's tables, so a statement written for a
     * graph engine runs here and gives the same rows; null for anything else, which runs as it is.
     */
    private fun <T : Any> cypherCompiled(spec: QuerySpecification<T>): CompiledCypher? {
        if (spec.statement!!.language != QueryLanguage.CYPHER) return null
        // A graph name that cannot name tables is refused here, when Cypher is first run, and not as the connection is had:
        // plain SQL works whatever the name, as it did before the provider made the graph's tables (#364).
        refusedGraph?.let { throw DrivineException(it, spec = spec) }
        // So is an Oracle older than 23.9, which has no UUID() for randomUUID().
        cypherRefusal?.let { throw DrivineException(it, spec = spec) }
        return when (type) {
            DatabaseType.POSTGRES -> CypherCompile.postgres(graphName, spec.statement!!.text, spec.parameters)
            DatabaseType.ORACLE -> CypherCompile.oracle(
                graphName, spec.statement!!.text, spec.parameters,
                nullsEqualStored = { OracleStoredFunctions.nullsEqual(connection, graphName) },
                eraStored = { OracleStoredFunctions.era(connection, graphName) },
            )
            DatabaseType.SQLITE -> {
                // So is a SQLite older than 3.53.2, which the application's sqlite-jdbc may be: plain SQL runs on it.
                sqliteVersion.refusal(connection)?.let { throw DrivineException(it, spec = spec) }
                CypherCompile.sqlite(graphName, spec.statement!!.text, spec.parameters)
            }
            else -> throw DrivineException("A Cypher statement does not run on $type yet.", spec = spec)
        }
    }

    /** Binds [value] to the marker at [index], counted from one. */
    private fun bind(statement: PreparedStatement, index: Int, value: Any?) = when (value) {
        // SQLite keeps a moment, a date or a time as its ISO text, and has no decimal.
        is Temporal, is java.util.Date -> if (type == DatabaseType.SQLITE) statement.setString(index, iso(value)) else statement.setObject(index, value)
        is BigDecimal -> if (type == DatabaseType.SQLITE) bindNumber(statement, index, value) else statement.setObject(index, value)
        // A null of no declared type: Oracle converts a character null to the type the statement wants, and
        // Postgres, its strings sent untyped, takes it for whatever type the statement wants there.
        null -> statement.setNull(index, Types.VARCHAR)
        // Oracle measures a bound string in bytes, so a long one goes as a CLOB when its bytes, not its characters, are too many.
        is String -> if (type == DatabaseType.ORACLE && value.length * 3 > MAX_STRING && value.toByteArray(Charsets.UTF_8).size > MAX_STRING) {
            statement.setClob(index, StringReader(value))
        } else {
            statement.setString(index, value)
        }
        is Char -> statement.setString(index, value.toString())
        else -> statement.setObject(index, value)
    }

    /** [value], a moment, a date or a time, as the ISO text SQLite's date functions read. */
    private fun iso(value: Any): String = when (value) {
        is java.sql.Date -> value.toLocalDate().toString()
        is java.util.Date -> value.toInstant().toString()
        else -> TemporalCoercer.coerce(mapOf("v" to value))["v"].toString()
    }

    /** [value] as SQLite's integer where it is whole and fits, and as its real number otherwise. */
    private fun bindNumber(statement: PreparedStatement, index: Int, value: BigDecimal) {
        val whole = runCatching { value.longValueExact() }.getOrNull()
        if (whole != null) statement.setLong(index, whole) else statement.setDouble(index, value.toDouble())
    }

    override fun startTransaction() {
        if (inTransaction) throw DrivineException("A transaction is already open on this connection.")
        if (!joined) connection.autoCommit = false
        inTransaction = true
        startedAt = System.nanoTime()
    }

    override fun commitTransaction() {
        if (!inTransaction) throw DrivineException("There is no transaction to commit.")
        // A joined transaction is committed by the transaction it joined, with the application's own work.
        if (!joined) {
            if (type == DatabaseType.SQLITE) endSqlite("COMMIT") else connection.commit()
        }
        endTransaction()
    }

    override fun rollbackTransaction() {
        if (!inTransaction) throw DrivineException("There is no transaction to rollback.")
        rollback()
        endTransaction()
    }

    /** Rolls the open transaction back; one this joined is marked to roll back at its end instead (see [joined]). */
    private fun rollback() {
        if (joined) return markRollbackOnly()
        if (type == DatabaseType.SQLITE) endSqlite("ROLLBACK") else connection.rollback()
    }

    /**
     * Marks the transaction this connection joined to roll back at its end: a Spring transaction through its
     * `ConnectionHolder`, and a JTA one through the registry Jakarta EE binds. Both are tried, as Spring's
     * `JtaTransactionManager` binds a `ConnectionHolder` too but reads only the JTA transaction's mark. Where neither can
     * be marked, that is logged: the application's transaction may still commit what Drivine's rolled back.
     */
    private fun markRollbackOnly() {
        val spring = SpringTransactions.markRollbackOnly(connection)
        if (JtaTransactions.markRollbackOnly() || spring) return
        logger.warn(
            "A Drivine transaction rolled back on the connection of a transaction it joined, which could not be marked to roll back: " +
                "neither a Spring transaction nor a JTA TransactionSynchronizationRegistry (${JtaTransactions.REGISTRY}) was found. " +
                "What it ran is left to that transaction to commit or roll back.",
        )
    }

    /**
     * Ends SQLite's open transaction with [end], `COMMIT` or `ROLLBACK`, and opens one that takes no lock for
     * [endTransaction] to close as the driver goes back to committing each statement.
     *
     * The driver's own commit and rollback open the next transaction as they end one, and so wait for the file's
     * write lock again: with another writer holding it, a commit that was made would be reported as failed. Nor is
     * the transaction committed by turning the driver back to committing each statement: it counts itself back in
     * that mode before its `COMMIT` runs, so a commit SQLite refuses, as it does one breaking a deferred foreign
     * key, would leave the transaction open on a connection the driver, and its pool, take to have none.
     *
     * A `ROLLBACK` refused because SQLite has already rolled the transaction back, as it does on a full disk or an
     * interrupted statement, has nothing left to undo: the `BEGIN` after it then opens, and its failure is thrown
     * only when a transaction is still open.
     */
    private fun endSqlite(end: String) = connection.createStatement().use { statement ->
        val ended = runCatching { statement.execute(end) }
        if (end != "ROLLBACK") ended.getOrThrow()
        runCatching { statement.execute("BEGIN DEFERRED") }.onFailure { begin ->
            throw ended.exceptionOrNull()?.apply { addSuppressed(begin) } ?: begin
        }
    }

    /**
     * Leaves the connection as a pool expects it back: committing each statement as it runs. A SQLite transaction
     * that held the write lock past [lockWarning] is logged first, so it is logged though the driver fails to go back.
     */
    private fun endTransaction() {
        inTransaction = false
        val held = Duration.ofNanos(System.nanoTime() - startedAt)
        if (type == DatabaseType.SQLITE && held > lockWarning) {
            logger.warn("A SQLite transaction held the file's write lock for ${held.toMillis()} ms: no other writer can write while one is held.")
        }
        // A joined connection is left committing nothing of itself, as the transaction it joined has it.
        if (!joined) connection.autoCommit = true
    }

    /**
     * Runs [block], an index build on Postgres, so that its session keeps no shared memory in its pool after it
     * (liberation-data/drivine4j#626). A backend that has run anything in parallel, as a `CREATE INDEX` over a table past
     * `min_parallel_table_scan_size` is, keeps a dynamic shared memory segment for as long as it lives, and a pool keeps
     * it alive: two of them were enough that an HNSW build, which asks for nearly all of `maintenance_work_mem` as one
     * segment, failed in a 64 MB `/dev/shm`.
     *
     * Where this connection's session is the build's own, out of a HikariCP pool, the build runs as it is, in parallel,
     * and [release] closes the session for good, with whatever ended the build, and the segment goes with it. Where it
     * cannot be ([noEviction]): another pool, which has no way to close one of its sessions for good, or a caller's
     * transaction's session, which closing would end partway through, the build runs with no parallel workers
     * (`max_parallel_maintenance_workers` 0), set back after to what it was: to the session's own value where the
     * session had set one, and otherwise reset, so that a value from `postgresql.conf`, the role or the database, or the
     * default, is followed again as it was, rather than kept as the session's own. In a transaction both are set for the
     * transaction alone (`SET LOCAL`), which its end undoes, so that a value it set for itself alone is not kept after it.
     */
    internal fun <T> buildingIndex(block: () -> T): T {
        val refusal = noEviction ?: "it is in a transaction".takeIf { inTransaction } ?: "its pool cannot close a session for good".takeIf { evictor == null }
        if (refusal == null) {
            evicting = true
            return block()
        }
        evictionRefused(refusal)
        val local = !connection.autoCommit
        // The value to set back: the session's own, or, in a transaction, whatever it is; null where it follows one it did not set.
        val previous = connection.createStatement().use { st ->
            st.executeQuery("SELECT setting, source = 'session' FROM pg_settings WHERE name = '$PARALLEL_WORKERS'").use {
                it.next()
                it.getString(1).takeIf { _ -> local || it.getBoolean(2) }
            }
        }
        setParallelWorkers("0", local)
        try {
            return block()
        } finally {
            // In a caller's transaction that the build failed, this fails too, and the transaction's rollback sets it back.
            runCatching {
                if (previous != null) setParallelWorkers(previous, local) else connection.createStatement().use { it.execute("RESET $PARALLEL_WORKERS") }
            }
        }
    }

    private fun setParallelWorkers(value: String, local: Boolean) {
        connection.prepareStatement("SELECT set_config('$PARALLEL_WORKERS', ?, $local)").use { st ->
            st.setString(1, value)
            st.executeQuery().close()
        }
    }

    /**
     * Returns the connection to its pool. A transaction left open is rolled back: nothing is committed by closing. A
     * connection the pool gave committing nothing of itself is given back so ([restoresAutoCommit]); where it cannot be,
     * that is logged, and it is closed all the same. One an index build ran on is closed for good instead, out of its pool
     * (see [buildingIndex]), whether the build failed or not.
     */
    override fun release(err: Throwable?) {
        err?.let { logger.warn("Closing connection with error: $it") }
        if (inTransaction) {
            runCatching { rollback() }
            runCatching { endTransaction() }
        }
        if (evicting) {
            evictor?.let { evict ->
                runCatching { evict(connection) }.onFailure { logger.warn("Could not close the session an index build ran on out of its pool: $it") }
            }
            runCatching { connection.close() }
            return
        }
        if (restoresAutoCommit) {
            runCatching { connection.autoCommit = false }.onFailure {
                logger.warn("Could not give the connection back to its pool committing nothing of itself, as the pool gave it: $it")
            }
        }
        connection.close()
    }

    private companion object {
        /** Past this a string is bound as a CLOB: the longest a PL/SQL `VARCHAR2` holds. */
        const val MAX_STRING = 32_767

        /** The setting [buildingIndex] turns to 0 where a build's session cannot be closed after it. */
        const val PARALLEL_WORKERS = "max_parallel_maintenance_workers"
    }
}

/** Why [graphName] cannot name a graph's tables on [type]; null where it can, or where the store keeps no graph in tables. */
internal fun graphRefusal(type: DatabaseType, graphName: String): String? = when (type) {
    DatabaseType.ORACLE -> OracleGraph.refusal(graphName)
    DatabaseType.POSTGRES -> PostgresGraph.refusal(graphName)
    DatabaseType.SQLITE -> SqliteGraph.refusal(graphName)
    else -> null
}
