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

/**
 * What a statement gave: its columns, by name, and a row of values for each, in the columns' order; what it wrote, as the
 * store [counts] it (see [org.drivine.query.QueryResult.counts]); and how long it took, in milliseconds, which `table` prints.
 */
data class Result(val columns: List<String>, val rows: List<List<Any?>>, val counts: Map<String, Int> = emptyMap(), val millis: Long = 0)

/** The store couldn't be reached, or it refused the user and password: exit code [ExitCode.UNREACHABLE]. */
class Unreachable(cause: Throwable) : Exception(cause.message ?: cause.javaClass.name, cause)

/**
 * One store, reached as an application reaches it: [Drivine] over the [ConnectionProperties] the address gives, and
 * the [PersistenceManager] that makes. So a statement runs as it would in an application, and on Postgres, Oracle and
 * SQLite Drivine compiles it to SQL over the graph's tables, which the provider makes when the store has none.
 */
class Store private constructor(private val drivine: Drivine, private val scratch: Scratch?, val type: StoreType) : AutoCloseable {

    private val manager: PersistenceManager = drivine.persistenceManager(NAME)

    /** Whether the store has multi-statement transactions, as all but FalkorDB have: there `:begin` opens none. */
    val transactions: Boolean get() = type != StoreType.FALKORDB

    /**
     * Runs [cypher], given [parameters], in a transaction of its own, or in the one [transaction] has open, and gives its
     * columns, as the store names them, and its rows, with each
     * node, relationship and path in them as a [org.drivine.query.GraphNode], [org.drivine.query.GraphRelationship] and
     * [org.drivine.query.GraphPath], as [PersistenceManager.queryResult] gives them, on every store. A failure the store
     * gave because it couldn't be reached is thrown as [Unreachable]; any other is the statement's, and is thrown as it is.
     */
    fun run(cypher: String, parameters: Map<String, Any?> = emptyMap()): Result {
        val started = System.nanoTime()
        val result = try {
            manager.queryResult(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
        } catch (e: Exception) {
            throw if (Failures.unreachable(e)) Unreachable(e) else e
        }
        return Result(result.columns, result.rows, result.counts, (System.nanoTime() - started) / 1_000_000)
    }

    /** The SQL [cypher] compiles to on this store, given [parameters], with nothing run: see [PersistenceManager.explain]. */
    fun explain(cypher: String, parameters: Map<String, Any?> = emptyMap()): String = try {
        manager.explain(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
    } catch (e: Exception) {
        throw if (Failures.unreachable(e)) Unreachable(e) else e
    }

    /**
     * The store's plan of [cypher], given [parameters], with nothing run: see [PersistenceManager.plan]. Its lines are the
     * store's, or none and why, where the store can't plan what the statement compiles to.
     */
    fun plan(cypher: String, parameters: Map<String, Any?> = emptyMap()): org.drivine.query.QueryPlan = try {
        manager.plan(QuerySpecification.withStatement(CypherStatement(cypher)).bind(parameters))
    } catch (e: Exception) {
        throw if (Failures.unreachable(e)) Unreachable(e) else e
    }

    /** Whether this store runs Cypher itself, as Neo4j, Memgraph and FalkorDB do, so has no SQL to show. */
    val runsCypher: Boolean get() = type in setOf(StoreType.NEO4J, StoreType.MEMGRAPH, StoreType.FALKORDB)

    /**
     * Runs [block] in one transaction, which [run] joins: committed where [block] returns, and rolled back where it throws,
     * the throw then [block]'s. Where the store can't be reached as it begins or ends, that is [Unreachable].
     */
    fun <T> transaction(block: () -> T): T = try {
        drivine.transaction { block() }
    } catch (e: Exception) {
        throw if (e !is Unreachable && Failures.unreachable(e)) Unreachable(e) else e
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
                Store(Drivine(DataSourceMap(mapOf(NAME to properties))), scratch, plan.address.store)
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
                // A database or service the address names wins, as Invocation.check says. FalkorDB's graph is
                // its database, as Drivine names it; the SQL stores' is the graph's path.
                databaseName = when (address.store) {
                    StoreType.SQLITE -> file ?: address.database
                    // As libpq does, a Postgres address that names no database names the user's.
                    StoreType.POSTGRES -> address.database ?: plan.database ?: plan.user?.ifEmpty { null } ?: "postgres"
                    StoreType.ORACLE -> address.database ?: plan.database
                    StoreType.NEO4J, StoreType.MEMGRAPH -> plan.database
                    StoreType.FALKORDB -> plan.graph
                },
                defaultGraphPath = plan.graph?.takeIf { address.store in setOf(StoreType.POSTGRES, StoreType.ORACLE, StoreType.SQLITE) },
                // One statement runs at a time. SQLite's provider makes the graph's tables on a connection of its own.
                poolMax = 2,
            )
        }
    }
}
