package drivine.connection

import drivine.logger.StatementLogger
import drivine.mapper.ResultMapper
import drivine.query.Neo4jSpecCompiler
import drivine.query.QueryLanguage
import drivine.query.QuerySpecification
import drivine.transaction.Transaction
import org.neo4j.driver.Session
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant

class Neo4jConnection(
    private val session: Session,
    private val resultMapper: ResultMapper
) : Connection {

    private val logger: Logger = LoggerFactory.getLogger(Neo4jConnection::class.java)
    private var transaction: Transaction? = null

    override fun sessionId(): String {
        return session.toString() // Neo4j Java driver doesn't expose session ID directly.
    }

    override fun <T: Any> query(spec: QuerySpecification<T>): List<T> {
        val finalizedSpec = spec.finalizedCopy(QueryLanguage.CYPHER)
        val compiledSpec = Neo4jSpecCompiler(finalizedSpec).compile()
        val startTime = Instant.now()
        val statementLogger = StatementLogger(sessionId())

//        val result: Result = transaction?.run(compiledSpec.statement, compiledSpec.parameters)
//            ?: session.run(compiledSpec.statement, compiledSpec.parameters)

        val result = this.session.run(compiledSpec.statement, compiledSpec.parameters)

        statementLogger.log(spec, startTime)
        return resultMapper.mapQueryResults(result.list(), finalizedSpec)
    }

//    suspend fun <T> openCursor(spec: CursorSpecification<T>): Neo4jCursor<T> {
//        return Neo4jCursor(sessionId(), spec.finalizedCopy("CYPHER"), this)
//    }

    override fun startTransaction() {
//        transaction = this.session.beginTransaction()
    }

    override fun commitTransaction() {
//        transaction?.commit() ?: throw DrivineException("There is no transaction to commit.")
//        transaction = null
    }

    override fun rollbackTransaction() {
//        transaction?.rollback() ?: throw DrivineException("There is no transaction to rollback.")
//        transaction = null
    }

    override fun release(error: Throwable?) {
        error?.let { logger.warn("Closing session with error: $it") }
        session.close()
    }
}
