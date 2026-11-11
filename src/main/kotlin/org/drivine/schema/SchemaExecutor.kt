package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnection
import org.drivine.connection.NativeSchemaCommandExecutor
import org.drivine.connection.PinnedConnections
import org.drivine.query.QuerySpecification

/**
 * Executes schema DDL and introspection queries in auto-commit mode.
 *
 * Schema operations must never run inside an open data transaction — Neo4j rejects schema DDL in
 * a transaction that has performed data writes, and other engines have no transactional DDL at
 * all. This executor therefore always obtains a connection of its own from the [ConnectionProvider]
 * (the same auto-commit path [org.drivine.manager.NonTransactionalPersistenceManager] uses) and
 * releases it when done: the one a schema command holds for its length, where one does (see
 * [PinnedConnections]), and otherwise one borrowed for the statement.
 */
class SchemaExecutor internal constructor(
    private val connectionProvider: ConnectionProvider,
) {

    /**
     * The lock that serialises the schema DDL of this graph's managers, held until it's closed: on Oracle, where the graph
     * has its lock table; null elsewhere, and where it isn't had within its wait, when the DDL runs unlocked. See [SchemaLock].
     */
    internal fun schemaLock(): SchemaLock? = SchemaLock.take(connectionProvider)

    /** The gate this provider's plans pass, one at a time, around taking [schemaLock] and running their DDL. See [SchemaLock.gate]. */
    internal fun schemaGate(): java.util.concurrent.locks.ReentrantLock? = SchemaLock.gate(connectionProvider)

    /** Runs an introspection query and returns its raw rows (maps, positional lists, or scalars). */
    fun query(statement: String): List<Any?> = query(statement, emptyMap())

    /** Runs [statement] with [parameters] bound, and returns its raw rows. */
    fun query(statement: String, parameters: Map<String, Any?>): List<Any?> =
        traced(statement, parameters) {
            PinnedConnections.on(connectionProvider) { it.query(QuerySpecification.withStatement(statement).bind(parameters)) }
        }

    /** Executes a single DDL statement. */
    fun execute(statement: SchemaStatement) {
        when (statement) {
            is SchemaStatement.Cypher -> if (connectionProvider.type == DatabaseType.POSTGRES && INDEX_BUILD.containsMatchIn(statement.statement)) {
                // A build in parallel leaves its session holding shared memory: see JdbcConnection.buildingIndex.
                traced(statement.statement, emptyMap()) {
                    PinnedConnections.on(connectionProvider) { connection ->
                        val build = { connection.query(QuerySpecification.withStatement(statement.statement).bind(emptyMap())) }
                        (connection as? JdbcConnection)?.buildingIndex(build) ?: build()
                    }
                }
            } else {
                query(statement.statement)
            }

            is SchemaStatement.Native -> {
                val nativeExecutor = connectionProvider as? NativeSchemaCommandExecutor
                    ?: throw DrivineException(
                        "Schema operation requires native command support, but connection provider " +
                            "'${connectionProvider.name}' (${connectionProvider.type}) does not implement " +
                            "NativeSchemaCommandExecutor"
                    )
                SchemaStatementTrace.traced(
                    connectionProvider, SchemaStatementTrace.Path.SCHEMA_CONNECTION, SchemaStatementTrace.Kind.NATIVE,
                    statement.args.joinToString(" "), emptyMap(),
                ) { nativeExecutor.executeNativeSchemaCommand(statement.args) }
            }
        }
    }

    /** Executes DDL statements in order. */
    fun execute(statements: List<SchemaStatement>) {
        statements.forEach { execute(it) }
    }

    /** Runs [poll], a loop that polls the store until [what] ends, told to a [SchemaStatementTrace] observer as one. */
    internal fun <T> poll(what: String, outcome: (T) -> String, poll: () -> T): T =
        SchemaStatementTrace.poll(connectionProvider, what, outcome, poll)

    /** Tells a [SchemaStatementTrace] observer a poll until [what] ends has begun, a run of the planner's rounds: see [pollEnded]. */
    internal fun pollStarted(what: String) = SchemaStatementTrace.pollStarted(connectionProvider, what)

    /** Tells a [SchemaStatementTrace] observer the poll [pollStarted] told of has ended, as [outcome] says. */
    internal fun pollEnded(what: String, outcome: String) = SchemaStatementTrace.pollEnded(connectionProvider, what, outcome)

    /** The name of the database this runs on, as the planner's requests name it. */
    internal val database: String get() = connectionProvider.name

    private inline fun <T> traced(statement: String, parameters: Map<String, Any?>, run: () -> T): T =
        SchemaStatementTrace.traced(
            connectionProvider, SchemaStatementTrace.Path.SCHEMA_CONNECTION, SchemaStatementTrace.Kind.STATEMENT, statement, parameters, run,
        )

    private companion object {
        /** A Postgres statement that builds an index, as PostgresSchemaGrammar writes one: a uniqueness constraint's too. */
        val INDEX_BUILD = Regex("""^\s*CREATE\s+(UNIQUE\s+)?INDEX\b""", RegexOption.IGNORE_CASE)
    }
}