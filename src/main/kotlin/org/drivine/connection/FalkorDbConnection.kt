package org.drivine.connection

import org.drivine.DrivineException
import org.drivine.logger.StatementLogger
import org.drivine.mapper.ResultMapper
import org.drivine.query.QueryLanguage
import org.drivine.query.QuerySpecification
import org.drivine.query.Neo4jSpecCompiler
import com.falkordb.Graph
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * How the FalkorDB connection handles `@Transactional` boundaries.
 */
enum class FalkorDbTransactionMode {
    /**
     * Default. `startTransaction` / `commitTransaction` are no-ops.
     * `rollbackTransaction` logs a warning (writes already executed).
     * All queries execute immediately regardless of transaction state.
     */
    WARN,

    /**
     * `startTransaction` throws [UnsupportedOperationException].
     * Use this to enforce that no code path accidentally relies on
     * multi-statement transactions against FalkorDB.
     */
    STRICT,
}

class FalkorDbConnection(
    private val graph: Graph,
    private val resultMapper: ResultMapper,
    private val transactionMode: FalkorDbTransactionMode = FalkorDbTransactionMode.WARN,
) : Connection {

    private val logger = LoggerFactory.getLogger(FalkorDbConnection::class.java)

    override fun sessionId(): String = "falkordb-${System.identityHashCode(graph)}"

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val finalizedSpec = spec.finalizedCopy(QueryLanguage.CYPHER)
        val compiled = Neo4jSpecCompiler(finalizedSpec).compile()
        val startTime = Instant.now()
        val statementLogger = StatementLogger(sessionId())

        try {
            logger.info("FalkorDB query:\n{}", compiled.statement)
            if (compiled.parameters.isNotEmpty()) {
                logger.debug("FalkorDB params: {}", compiled.parameters)
            }

            val resultSet = try {
                if (compiled.parameters.isEmpty()) {
                    graph.query(compiled.statement)
                } else {
                    graph.query(compiled.statement, compiled.parameters)
                }
            } catch (e: Exception) {
                logger.error("FalkorDB GRAPH.QUERY failed: {}\n  Statement: {}\n  Params: {}",
                    e.message, compiled.statement, compiled.parameters)
                throw e
            }

            // Write-only queries (no RETURN) have an empty header
            val schemaNames = try {
                resultSet.header.schemaNames
            } catch (e: Exception) {
                logger.debug("FalkorDB result has no parseable header (write-only query)")
                emptyList()
            }

            if (schemaNames.isEmpty()) {
                statementLogger.log(spec, startTime)
                @Suppress("UNCHECKED_CAST")
                return emptyList<Any>() as List<T>
            }

            val records = resultSet.map { it }
            val mapped = resultMapper.mapQueryResults(records, finalizedSpec)
            statementLogger.log(spec, startTime)
            return mapped
        } catch (e: Exception) {
            statementLogger.log(spec, startTime, e)
            throw e
        }
    }

    override fun startTransaction() {
        when (transactionMode) {
            FalkorDbTransactionMode.WARN -> logger.debug(
                "FalkorDB transaction started (passthrough — each query executes immediately, no multi-statement atomicity)"
            )
            FalkorDbTransactionMode.STRICT -> throw UnsupportedOperationException(
                "FalkorDB does not support multi-statement transactions. " +
                "Remove @Transactional or set falkorDbTransactionMode to WARN to allow passthrough."
            )
        }
    }

    override fun commitTransaction() {
        logger.debug("FalkorDB transaction committed (passthrough — writes already executed)")
    }

    override fun rollbackTransaction() {
        logger.warn(
            "FalkorDB rollback requested but writes already executed — " +
            "rollback is a no-op. Each query was committed on execution."
        )
    }

    override fun release(err: Throwable?) {
        err?.let { logger.warn("Closing FalkorDB connection with error: $it") }
        graph.close()
    }
}