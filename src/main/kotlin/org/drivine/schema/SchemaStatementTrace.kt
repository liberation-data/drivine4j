package org.drivine.schema

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification

/**
 * Where every schema statement Drivine sends is told to an [Observer], for the tests that pin the sequence of them
 * (see `SchemaStatementGolden` in the tests). There is no observer unless a test sets one, and then nothing is told
 * and the statement runs as it would: production code never sets it, and reads no property for it.
 *
 * A statement is told here as it leaves [SchemaExecutor] (the schema connection, and FalkorDB's native commands),
 * or as [SchemaVersionStore] and [LowerCopyCatalog] give it to a [PersistenceManager]. A loop that polls a build or
 * a drop until it ends is told as one [Observer.pollStarted] and [Observer.pollEnded], whatever the number of rounds.
 */
internal object SchemaStatementTrace {

    /** How a statement reached the store. */
    enum class Path {
        /** On a connection of its own, through [SchemaExecutor]. */
        SCHEMA_CONNECTION,

        /** Through the auto-commit manager ([org.drivine.manager.NonTransactionalPersistenceManager]). */
        AUTO_COMMIT,

        /** Through another manager: the one a caller gave. */
        MANAGER,
    }

    /** What was sent. */
    enum class Kind { STATEMENT, NATIVE }

    interface Observer {
        /** [statement] ran on [source], a connection provider or a manager, and failed with [failure], or did not where it is null. */
        fun ran(source: Any, path: Path, kind: Kind, statement: String, parameters: Map<String, Any?>, failure: Throwable?)

        /** A loop on [source] that polls until [what] ends has begun. */
        fun pollStarted(source: Any, what: String)

        /** The loop [pollStarted] told of has ended, as [outcome] says, or with [failure]. */
        fun pollEnded(source: Any, what: String, outcome: String?, failure: Throwable?)
    }

    @Volatile
    @JvmStatic
    var observer: Observer? = null

    inline fun <T> traced(source: Any, path: Path, kind: Kind, statement: String, parameters: Map<String, Any?>, run: () -> T): T {
        val observer = observer ?: return run()
        val result = try {
            run()
        } catch (e: Throwable) {
            observer.ran(source, path, kind, statement, parameters, e)
            throw e
        }
        observer.ran(source, path, kind, statement, parameters, null)
        return result
    }

    /** Runs [spec] with [run] on [manager], telling the observer of it. */
    inline fun <T> traced(manager: PersistenceManager, spec: QuerySpecification<*>, run: () -> T): T {
        if (observer == null) return run()
        val path = if (manager is org.drivine.manager.NonTransactionalPersistenceManager) Path.AUTO_COMMIT else Path.MANAGER
        return traced(manager, path, Kind.STATEMENT, spec.statement?.text.orEmpty(), spec.parameters, run)
    }

    /** Runs [poll], a loop on [source] that polls until [what] ends, telling the observer of it as one, with the [outcome] of its result. */
    inline fun <T> poll(source: Any, what: String, outcome: (T) -> String, poll: () -> T): T {
        val observer = observer ?: return poll()
        observer.pollStarted(source, what)
        val result = try {
            poll()
        } catch (e: Throwable) {
            observer.pollEnded(source, what, null, e)
            throw e
        }
        observer.pollEnded(source, what, outcome(result), null)
        return result
    }
}
