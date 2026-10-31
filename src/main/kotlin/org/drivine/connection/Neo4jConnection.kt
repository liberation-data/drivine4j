package org.drivine.connection

import org.drivine.DrivineException
import org.drivine.logger.StatementLogger
import org.drivine.mapper.ResultMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.ParameterCoercer
import org.drivine.query.QueryLanguage
import org.drivine.query.SpecCompiler
import org.drivine.query.QuerySpecification
import org.neo4j.driver.Session
import org.neo4j.driver.Transaction
import org.neo4j.driver.exceptions.TransientException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant

class Neo4jConnection(
    private val session: Session,
    private val resultMapper: ResultMapper,
    val subtypeRegistry: SubtypeRegistry? = null,
    private val coercers: List<ParameterCoercer> = emptyList(),
) : Connection {

    private val logger: Logger = LoggerFactory.getLogger(Neo4jConnection::class.java)
    private var transaction: Transaction? = null

    override fun parameterCoercers(): List<ParameterCoercer> = coercers

    override fun sessionId(): String {
        return session.toString() // Neo4j Java driver doesn't expose session ID directly.
    }

    override fun <T: Any> query(spec: QuerySpecification<T>): List<T> {
        val finalizedSpec = spec.finalizedCopy(QueryLanguage.CYPHER)
        val compiledSpec = SpecCompiler(finalizedSpec).compile()
        val coercedParams = applyParameterCoercers(finalizedSpec, compiledSpec.parameters)
        val startTime = Instant.now()
        val statementLogger = StatementLogger(sessionId())

        try {
            val result = transaction?.run(compiledSpec.statement, coercedParams)
                ?: session.run(compiledSpec.statement, coercedParams)

            val mapped = resultMapper.mapQueryResults(result.list(), finalizedSpec)
            statementLogger.log(spec, startTime)
            return mapped
        } catch (e: Exception) {
            statementLogger.log(spec, startTime, e)
            throw e
        }
    }

//    suspend fun <T> openCursor(spec: CursorSpecification<T>): Neo4jCursor<T> {
//        return Neo4jCursor(sessionId(), spec.finalizedCopy("CYPHER"), this)
//    }

    override fun startTransaction() {
        transaction = this.session.beginTransaction()
    }

    override fun commitTransaction() {
        transaction?.commit() ?: throw DrivineException("There is no transaction to commit.")
        transaction = null
    }

    override fun rollbackTransaction() {
        transaction?.rollback() ?: throw DrivineException("There is no transaction to rollback.")
        transaction = null
    }

    /**
     * Closes the session. A failure is logged, at debug when the engine turned the statement away for another
     * session's (a deadlock or conflict): its caller runs it again, as a save is (`SaveExecutor`) and the store
     * identity's constraint is (#473), and one that still fails is thrown or warned of there. Otherwise each manager
     * of several starting at once would warn of each deadlock it got past.
     */
    override fun release(err: Throwable?) {
        when (err) {
            null -> Unit
            is TransientException -> logger.debug("Closing session with error: {}", err.toString())
            else -> logger.warn("Closing session with error: $err")
        }
        session.close()
    }

}
