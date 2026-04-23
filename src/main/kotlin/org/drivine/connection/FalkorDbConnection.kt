package org.drivine.connection

import org.drivine.DrivineException
import org.drivine.logger.StatementLogger
import org.drivine.mapper.ResultMapper
import org.drivine.query.ParameterCoercer
import org.drivine.query.QueryLanguage
import org.drivine.query.QuerySpecification
import org.drivine.query.SpecCompiler
import org.drivine.query.TemporalCoercer
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

    override fun parameterCoercers(): List<ParameterCoercer> = listOf(TemporalCoercer)

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> {
        val finalizedSpec = spec.finalizedCopy(QueryLanguage.CYPHER)
        val compiled = SpecCompiler(finalizedSpec).compile()
        val coercedParams = applyParameterCoercers(finalizedSpec, compiled.parameters)
        val (statement, params) = inlineDollarStrings(compiled.statement, coercedParams)
        val startTime = Instant.now()
        val statementLogger = StatementLogger(sessionId())

        try {
            logger.info("FalkorDB query:\n{}", statement)
            if (params.isNotEmpty()) {
                logger.debug("FalkorDB params: {}", params)
            }

            val resultSet = try {
                if (params.isEmpty()) {
                    graph.query(statement)
                } else {
                    graph.query(statement, params)
                }
            } catch (e: Exception) {
                logger.error("FalkorDB GRAPH.QUERY failed: {}\n  Statement: {}\n  Params: {}",
                    e.message, statement, params)
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

    /**
     * For any top-level [String] parameter whose value contains `$`, splice the value into
     * the query text as a Cypher string literal and drop the key from the parameter map.
     *
     * WORKAROUND: FalkorDB/JFalkorDB#251 — jfalkordb builds a `CYPHER key=value ...` prefix
     * using a string format that has no escape for `$` in values. FalkorDB's server then
     * interprets `${...}` patterns inside those values as parameter expressions, causing
     * `query with more than one statement is not supported` errors — length-dependent in
     * practice, so short values pass but real-world RAG chunks (docs with code samples)
     * blow up.
     *
     * The fix is to take `$`-containing strings out of the prefix entirely: Cypher's parser
     * does not interpret `$` inside a string literal in the query body, so splicing the
     * value in as `"..."` is correct by construction. Remove this workaround once jfalkordb
     * ships a fix and we bump the dependency.
     *
     * Only scalar `String` values are handled. Maps-as-parameter-values aren't supported by
     * FalkorDB anyway (JFalkorDB#68), so we don't recurse into nested structures.
     */
    private fun inlineDollarStrings(
        statement: String,
        parameters: Map<String, Any?>
    ): Pair<String, Map<String, Any?>> {
        if (parameters.isEmpty()) return statement to parameters

        var rewritten = statement
        val remaining = mutableMapOf<String, Any?>()
        for ((key, value) in parameters) {
            if (value is String && value.contains('$')) {
                val literal = toCypherStringLiteral(value)
                val reference = Regex("\\\$${Regex.escape(key)}(?![A-Za-z0-9_])")
                rewritten = reference.replace(rewritten, Regex.escapeReplacement(literal))
            } else {
                remaining[key] = value
            }
        }
        return rewritten to remaining
    }

    private fun toCypherStringLiteral(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}