package org.drivine.connection

import org.drivine.DrivineException
import org.drivine.logger.StatementLogger
import org.drivine.mapper.JdbcResultMapper
import org.drivine.mapper.ResultMapper
import org.drivine.query.JdbcParameterCoercer
import org.drivine.query.ParameterCoercer
import org.drivine.query.QueryLanguage
import org.drivine.query.QuerySpecification
import org.drivine.query.SpecCompiler
import org.drivine.query.SqlParameters
import org.slf4j.LoggerFactory
import java.io.StringReader
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant

/**
 * A connection to a database that speaks SQL, over JDBC.
 *
 * A statement names its parameters `$name`, as a Cypher statement does; they are bound by position.
 * What it returns is its result set, or for a PL/SQL block the first result set the block hands back
 * with `DBMS_SQL.RETURN_RESULT`. A block is how a statement that writes returns rows: it is one
 * statement to the database, applied whole or not at all.
 */
class JdbcConnection(
    private val connection: java.sql.Connection,
    private val resultMapper: ResultMapper,
) : Connection {

    private val logger = LoggerFactory.getLogger(JdbcConnection::class.java)
    private var inTransaction = false

    override fun sessionId(): String = "jdbc-${System.identityHashCode(connection)}"

    override fun parameterCoercers(): List<ParameterCoercer> = listOf(JdbcParameterCoercer)

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val finalizedSpec = spec.finalizedCopy(QueryLanguage.SQL)
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
                    statement.resultSet.use(JdbcResultMapper::rows)
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

    private fun bind(statement: PreparedStatement, index: Int, value: Any?) = when (value) {
        // A null of no declared type: Oracle converts a character null to the type the statement wants.
        null -> statement.setNull(index, Types.VARCHAR)
        // Oracle measures a bound string in bytes, so a long one goes as a CLOB when its bytes, not its characters, are too many.
        is String -> if (value.length > MAX_STRING || value.toByteArray(Charsets.UTF_8).size > MAX_STRING) statement.setClob(index, StringReader(value)) else statement.setString(index, value)
        is Char -> statement.setString(index, value.toString())
        else -> statement.setObject(index, value)
    }

    override fun startTransaction() {
        if (inTransaction) throw DrivineException("A transaction is already open on this connection.")
        connection.autoCommit = false
        inTransaction = true
    }

    override fun commitTransaction() {
        if (!inTransaction) throw DrivineException("There is no transaction to commit.")
        connection.commit()
        endTransaction()
    }

    override fun rollbackTransaction() {
        if (!inTransaction) throw DrivineException("There is no transaction to rollback.")
        connection.rollback()
        endTransaction()
    }

    private fun endTransaction() {
        inTransaction = false
        connection.autoCommit = true
    }

    /** Returns the connection to its pool. A transaction left open is rolled back: nothing is committed by closing. */
    override fun release(err: Throwable?) {
        err?.let { logger.warn("Closing connection with error: $it") }
        if (inTransaction) {
            runCatching { connection.rollback() }
            runCatching { endTransaction() }
        }
        connection.close()
    }

    private companion object {
        /** Past this a string is bound as a CLOB: the longest a PL/SQL `VARCHAR2` holds. */
        const val MAX_STRING = 32_767
    }
}
