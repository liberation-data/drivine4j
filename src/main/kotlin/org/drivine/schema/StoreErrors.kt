package org.drivine.schema

import org.drivine.cypher.store.schema.Cause
import org.drivine.cypher.store.schema.StoreError
import org.neo4j.driver.exceptions.Neo4jException
import java.sql.SQLException

/**
 * A driver's error as the module's schema planner reads one ([StoreError]): the chain of [Throwable.cause]s, the
 * outermost first, each as `store.yaml`'s `errors` write a cause. What the planner decides of it (a violation, an index in
 * the way, a wait that ran out) is the module's ([org.drivine.cypher.store.schema.StoreSchema.isViolation] and the rest);
 * saying what the driver gave is drivine4j's, and only that is done here.
 *
 * - A JDBC [SQLException] (Postgres, Oracle, SQLite): its SQL state and its vendor code, [SQLException.getErrorCode],
 *   which is `0` where the driver gives none.
 * - A Neo4j driver's [Neo4jException], which Memgraph's errors are too: its code, `N/A` where the driver gives none, so
 *   that it still reads as the driver's, and its GQL status.
 * - Any other error, FalkorDB's among them: its message alone.
 */
internal object StoreErrors {

    /** The chain of [error], as the planner reads it. */
    fun of(error: Throwable): StoreError = StoreError(chain(error).map(::cause))

    /** [error] and its causes, the outermost first, each once, as drivine4j's grammars walk them. */
    private fun chain(error: Throwable): List<Throwable> {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        return generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.toList()
    }

    private fun cause(error: Throwable): Cause = when (error) {
        is SQLException -> Cause(sqlState = error.sqlState, vendorCode = error.errorCode, message = error.message)
        is Neo4jException -> Cause(code = error.code() ?: NO_CODE, gqlStatus = error.gqlStatus(), message = error.message)
        else -> Cause(message = error.message)
    }

    /** The code a Neo4j driver's error with none is given: a null code would read as no driver's error. */
    const val NO_CODE = "N/A"
}
