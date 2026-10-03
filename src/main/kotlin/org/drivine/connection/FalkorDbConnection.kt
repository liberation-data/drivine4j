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
        val (statement, params) = inlineUnsendableValues(compiled.statement, coercedParams)
        val startTime = Instant.now()
        val statementLogger = StatementLogger(sessionId())

        try {
            logger.info("FalkorDB query:\n{}", statement)

            // Failures are reported once, by the StatementLogger in the outer catch. A second
            // report here would duplicate the line and, because it prints the raw parameter map,
            // would dump unbounded values that QuerySpecification.toString() deliberately bounds.
            val resultSet = if (params.isEmpty()) {
                graph.query(statement)
            } else {
                graph.query(statement, params)
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
     * Splice into the query text, as a Cypher literal, every parameter jfalkordb cannot carry in
     * its `CYPHER key=value ...` prefix, and drop it from the parameter map.
     *
     * Three things cannot go in the prefix:
     *
     * - A string containing `$` (FalkorDB/JFalkorDB#251). `$` isn't escaped, and FalkorDB's server
     *   then reads `${...}` in a prefix value as a parameter expression, causing
     *   `query with more than one statement is not supported`. Length-dependent, so short values
     *   pass but real-world RAG chunks blow up.
     *
     * - A string containing a backslash (FalkorDB/JFalkorDB#252). Backslashes aren't escaped before
     *   `"` is, so `{\"rows\": 5}` reaches the server as a quoted string that ends early.
     *
     * - A map (FalkorDB/JFalkorDB#68). jfalkordb writes it with Java's `toString()`, which is not
     *   Cypher, and the server answers `Failed to parse the value of parameter`. This is what a
     *   batched save sends: `UNWIND $rows`, where each row is a map.
     *
     * Any of these may sit inside a list or a map, so the test is made on the whole value. A value
     * that never reaches the prefix cannot trip any of the three, and a literal in the query body
     * is read by the Cypher parser alone, which is what [toCypherLiteral] writes for.
     *
     * Every reference is replaced in ONE pass over the original statement. Replacing one key at a
     * time rescans text that has already been spliced in, so a value containing `$name` would have
     * that `$name` replaced as if it were a reference to the parameter `name`.
     *
     * The cost is that a spliced statement is a different query text each time, so the server
     * cannot reuse its plan. Remove the workaround for each case as jfalkordb fixes it.
     */
    private fun inlineUnsendableValues(
        statement: String,
        parameters: Map<String, Any?>
    ): Pair<String, Map<String, Any?>> {
        val unsendable = parameters.filterValues(::cannotGoInPrefix)
        if (unsendable.isEmpty()) return statement to parameters

        // Longest key first, so `$rows` is not taken for `$row` followed by an `s`.
        val keys = unsendable.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val reference = Regex("\\$($keys)(?![A-Za-z0-9_])")
        val rewritten = reference.replace(statement) { toCypherLiteral(unsendable[it.groupValues[1]]) }
        return rewritten to parameters - unsendable.keys
    }

    private fun cannotGoInPrefix(value: Any?): Boolean = when (value) {
        is String -> value.contains('$') || value.contains('\\')
        is Map<*, *> -> true
        is Iterable<*> -> value.any(::cannotGoInPrefix)
        is Array<*> -> value.any(::cannotGoInPrefix)
        else -> false
    }

    private fun toCypherLiteral(value: Any?): String = when (value) {
        null -> "null"
        is String -> toCypherStringLiteral(value)
        is Char -> toCypherStringLiteral(value.toString())
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (key, item) ->
            "`${key.toString().replace("`", "``")}`: ${toCypherLiteral(item)}"
        }
        is Iterable<*> -> value.joinToString(", ", "[", "]", transform = ::toCypherLiteral)
        is Array<*> -> value.joinToString(", ", "[", "]", transform = ::toCypherLiteral)
        is FloatArray -> value.joinToString(", ", "[", "]")
        is DoubleArray -> value.joinToString(", ", "[", "]")
        is IntArray -> value.joinToString(", ", "[", "]")
        is LongArray -> value.joinToString(", ", "[", "]")
        else -> throw IllegalArgumentException(
            "FalkorDB cannot take a ${value::class.qualifiedName} inside a map or list parameter; it must be a string, number, boolean, list or map"
        )
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