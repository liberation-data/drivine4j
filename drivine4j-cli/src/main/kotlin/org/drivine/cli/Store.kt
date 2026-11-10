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
class Store private constructor(private val drivine: Drivine, private val scratch: Path?) : AutoCloseable {

    private val manager: PersistenceManager = drivine.persistenceManager(NAME)

    /**
     * Runs [cypher] in a transaction of its own and gives its rows. A failure the store gave because it couldn't be
     * reached is thrown as [Unreachable]; any other is the statement's, and is thrown as it is.
     */
    fun run(cypher: String): Result {
        val columns = Columns.of(cypher)
        val rows = try {
            manager.query(QuerySpecification.withStatement(CypherStatement(cypher)))
        } catch (e: Exception) {
            throw if (Failures.unreachable(e)) Unreachable(e) else e
        }
        val names = columns ?: Columns.guessed(rows)
        return Result(names, rows.map { row -> Columns.split(row, names.size) })
    }

    override fun close() {
        try {
            drivine.close()
        } finally {
            scratch?.let(::deleteScratch)
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
            val scratch = if (plan.address.inMemory) Files.createTempDirectory("drivine-") else null
            val properties = properties(plan, scratch?.resolve("memory.db")?.toString())
            return try {
                Store(Drivine(DataSourceMap(mapOf(NAME to properties))), scratch)
            } catch (e: Exception) {
                scratch?.let(::deleteScratch)
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

        private fun deleteScratch(dir: Path) {
            dir.toFile().deleteRecursively()
        }
    }
}
