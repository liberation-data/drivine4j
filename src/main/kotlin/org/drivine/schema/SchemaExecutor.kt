package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.NativeSchemaCommandExecutor
import org.drivine.query.QuerySpecification

/**
 * Executes schema DDL and introspection queries in auto-commit mode.
 *
 * Schema operations must never run inside an open data transaction — Neo4j rejects schema DDL in
 * a transaction that has performed data writes, and other engines have no transactional DDL at
 * all. This executor therefore always obtains a fresh connection from the [ConnectionProvider]
 * (the same auto-commit path [org.drivine.manager.NonTransactionalPersistenceManager] uses) and
 * releases it when done.
 */
class SchemaExecutor internal constructor(
    private val connectionProvider: ConnectionProvider,
) {

    /** Runs an introspection query and returns its raw rows (maps, positional lists, or scalars). */
    fun query(statement: String): List<Any?> {
        val connection = connectionProvider.connect()
        return try {
            val result = connection.query(QuerySpecification.withStatement(statement))
            connection.release()
            result
        } catch (e: Exception) {
            connection.release(e)
            throw e
        }
    }

    /** Executes a single DDL statement. */
    fun execute(statement: SchemaStatement) {
        when (statement) {
            is SchemaStatement.Cypher -> query(statement.statement)

            is SchemaStatement.Native -> {
                val nativeExecutor = connectionProvider as? NativeSchemaCommandExecutor
                    ?: throw DrivineException(
                        "Schema operation requires native command support, but connection provider " +
                            "'${connectionProvider.name}' (${connectionProvider.type}) does not implement " +
                            "NativeSchemaCommandExecutor"
                    )
                nativeExecutor.executeNativeSchemaCommand(statement.args)
            }
        }
    }

    /** Executes DDL statements in order. */
    fun execute(statements: List<SchemaStatement>) {
        statements.forEach { execute(it) }
    }
}