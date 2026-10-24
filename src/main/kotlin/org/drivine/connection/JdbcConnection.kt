package org.drivine.connection

import org.drivine.DrivineException
import org.drivine.backend.sqlite.SqliteSql
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
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
) : Connection {

    private val logger = LoggerFactory.getLogger(JdbcConnection::class.java)
    private var inTransaction = false

    override fun sessionId(): String = "jdbc-${System.identityHashCode(connection)}"

    override fun parameterCoercers(): List<ParameterCoercer> = listOf(JdbcParameterCoercer)

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val finalizedSpec = cypherCompiled(spec.finalizedCopy(QueryLanguage.SQL))
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
                positional.names.forEachIndexed { index, name ->
                    if (!parameters.containsKey(name)) throw DrivineException("The statement has a parameter \$$name, and no value is bound to it.", spec = spec)
                    bind(statement, index + 1, parameters[name])
                }
                // A query answers with a result set; a block that returns one is asked for it.
                if (statement.execute() || statement.moreResults) {
                    if (type == DatabaseType.SQLITE) {
                        val json = positional.statement.trimStart().startsWith(SqliteSql.JSON_ROWS)
                        statement.resultSet.use { JdbcResultMapper.sqliteRows(it, json) }
                    } else {
                        statement.resultSet.use(JdbcResultMapper::rows)
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
            throw e
        }
    }

    /**
     * A Cypher statement compiled to the store's SQL over the graph's tables, so a statement written for a
     * graph engine runs here and gives the same rows; anything else as it is.
     */
    private fun <T : Any> cypherCompiled(spec: QuerySpecification<T>): QuerySpecification<T> {
        if (spec.statement!!.language != QueryLanguage.CYPHER) return spec
        val compiled = when (type) {
            DatabaseType.POSTGRES -> PostgresCypherCompiler(graphName).compile(spec.statement!!.text, spec.parameters)
            DatabaseType.ORACLE -> OracleCypherCompiler(graphName).compile(spec.statement!!.text, spec.parameters)
            else -> throw DrivineException("A Cypher statement does not run on $type yet.", spec = spec)
        }
        return spec.withSql(compiled.sql, compiled.parameters)
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
        connection.autoCommit = false
        inTransaction = true
    }

    override fun commitTransaction() {
        if (!inTransaction) throw DrivineException("There is no transaction to commit.")
        if (type == DatabaseType.SQLITE) endSqlite("COMMIT") else connection.commit()
        endTransaction()
    }

    override fun rollbackTransaction() {
        if (!inTransaction) throw DrivineException("There is no transaction to rollback.")
        rollback()
        endTransaction()
    }

    /** Rolls the open transaction back. */
    private fun rollback() {
        if (type == DatabaseType.SQLITE) endSqlite("ROLLBACK") else connection.rollback()
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

    /** Leaves the connection as a pool expects it back: committing each statement as it runs. */
    private fun endTransaction() {
        inTransaction = false
        connection.autoCommit = true
    }

    /** Returns the connection to its pool. A transaction left open is rolled back: nothing is committed by closing. */
    override fun release(err: Throwable?) {
        err?.let { logger.warn("Closing connection with error: $it") }
        if (inTransaction) {
            runCatching { rollback() }
            runCatching { endTransaction() }
        }
        connection.close()
    }

    private companion object {
        /** Past this a string is bound as a CLOB: the longest a PL/SQL `VARCHAR2` holds. */
        const val MAX_STRING = 32_767
    }
}
