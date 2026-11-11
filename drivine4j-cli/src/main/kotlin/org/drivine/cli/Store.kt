package org.drivine.cli

import org.drivine.Drivine
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import java.nio.file.Files
import java.nio.file.Path

/** What a statement gave: its columns, by name, and a row of values for each, in the columns' order. */
data class Result(val columns: List<String>, val rows: List<List<Any?>>)

/** The store couldn't be reached, or it refused the user and password: exit code [ExitCode.UNREACHABLE]. */
class Unreachable(cause: Throwable) : Exception(cause.message ?: cause.javaClass.name, cause)

/**
 * One store, reached as an application reaches it: [Drivine] over the [ConnectionProperties] the address gives, and
 * the [PersistenceManager] that makes. So a statement runs as it would in an application, and on Postgres, Oracle and
 * SQLite Drivine compiles it to SQL over the graph's tables, which the provider makes when the store has none.
 */
class Store private constructor(private val drivine: Drivine, private val scratch: Scratch?) : AutoCloseable {

    private val manager: PersistenceManager = drivine.persistenceManager(NAME)

    /**
     * Runs [cypher] in a transaction of its own and gives its columns, as the store names them, and its rows, with each
     * node, relationship and path in them as a [org.drivine.query.GraphNode], [org.drivine.query.GraphRelationship] and
     * [org.drivine.query.GraphPath], as [PersistenceManager.queryResult] gives them, on every store. A failure the store
     * gave because it couldn't be reached is thrown as [Unreachable]; any other is the statement's, and is thrown as it is.
     */
    fun run(cypher: String): Result {
        val result = try {
            manager.queryResult(QuerySpecification.withStatement(CypherStatement(cypher)))
        } catch (e: Exception) {
            throw if (Failures.unreachable(e)) Unreachable(e) else e
        }
        return Result(result.columns, result.rows)
    }

    override fun close() {
        try {
            drivine.close()
        } finally {
            scratch?.delete()
        }
    }

    /**
     * The directory that holds `sqlite::memory:`'s file, deleted when the run ends. A run that ends by a signal, as
     * Ctrl-C or `kill` ends it, runs no `finally`, so a shutdown hook deletes it then; the native image runs the hook
     * on a signal because it's built with `--install-exit-handlers`. Only a `kill -9` leaves it behind, in the temp dir.
     */
    internal class Scratch private constructor(val dir: Path) {
        private val hook = Thread { dir.toFile().deleteRecursively() }

        fun delete() {
            dir.toFile().deleteRecursively()
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (e: IllegalStateException) {
                // Already shutting down: the hook deletes it too.
            }
        }

        companion object {
            fun create(): Scratch = Scratch(Files.createTempDirectory("drivine-")).also { Runtime.getRuntime().addShutdownHook(it.hook) }
        }
    }

    companion object {
        /** The one datasource's name. */
        private const val NAME = "cli"

        /**
         * The store [plan] addresses, connected. Postgres and Oracle connect here, as their pools are made; the graph
         * engines connect at the first statement. A store that can't be reached either way is [Unreachable].
         */
        fun open(plan: Plan): Store {
            // Drivine keeps a SQLite graph in a file, which each connection of the pool opens: an in-memory database
            // would be one per connection. So sqlite::memory: is a file of the run's own, deleted when the run ends.
            val scratch = if (plan.address.inMemory) Scratch.create() else null
            val properties = properties(plan, scratch?.dir?.resolve("memory.db")?.toString())
            return try {
                Store(Drivine(DataSourceMap(mapOf(NAME to properties))), scratch)
            } catch (e: Exception) {
                scratch?.delete()
                throw Unreachable(e)
            }
        }

        /**
         * The connection properties of [plan]'s address. A store that needs a user is given an empty one where none is
         * given, so that the store, not the CLI, refuses it: no user and no password connect as nobody, as in
         * cypher-shell, and fail as a connection does.
         */
        internal fun properties(plan: Plan, file: String? = null): ConnectionProperties {
            val address = plan.address
            return ConnectionProperties(
                type = when (address.store) {
                    StoreType.NEO4J -> DatabaseType.NEO4J
                    StoreType.MEMGRAPH -> DatabaseType.MEMGRAPH
                    StoreType.FALKORDB -> DatabaseType.FALKORDB
                    StoreType.POSTGRES -> DatabaseType.POSTGRES
                    StoreType.ORACLE -> DatabaseType.ORACLE
                    StoreType.SQLITE -> DatabaseType.SQLITE
                },
                host = address.host,
                port = address.port,
                userName = if (address.store in setOf(StoreType.FALKORDB, StoreType.SQLITE)) null else plan.user.orEmpty(),
                password = if (address.store == StoreType.SQLITE) null else plan.password,
                protocol = address.protocol,
                databaseName = when (address.store) {
                    StoreType.SQLITE -> file ?: address.database
                    // As libpq does, a Postgres address that names no database names the user's.
                    StoreType.POSTGRES -> address.database ?: plan.user?.ifEmpty { null } ?: "postgres"
                    StoreType.ORACLE -> address.database
                    else -> null
                },
                // One statement runs at a time. SQLite's provider makes the graph's tables on a connection of its own.
                poolMax = 2,
            )
        }
    }
}
