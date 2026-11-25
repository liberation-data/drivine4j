package org.drivine.sqlite

import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.sqlStatement
import org.drivine.test.SqlCypherFixtures
import java.nio.file.Files

/**
 * The test JVM's SQLite: a file of its own in a temporary directory, removed when the JVM ends. SQLite
 * needs no server, so its tests run wherever the suite does.
 */
object SqliteTestDatabase {

    /** The file every graph of the test JVM is kept in. */
    val path: String by lazy {
        val directory = Files.createTempDirectory("drivine-sqlite")
        directory.toFile().deleteOnExit()
        directory.resolve("drivine.db").toString()
    }

    /** One pool for the test JVM, closed when it ends. */
    val provider: JdbcConnectionProvider by lazy {
        JdbcConnectionProvider.sqlite("sqlite", path).also { pool -> Runtime.getRuntime().addShutdownHook(Thread { pool.end() }) }
    }

    val pm: NonTransactionalPersistenceManager by lazy {
        NonTransactionalPersistenceManager(provider, "sqlite", DatabaseType.SQLITE, SubtypeRegistry())
    }

    /** Runs each SQL statement on its own, committed as it runs. */
    fun run(vararg statements: String) = statements.forEach { pm.execute(QuerySpecification.withStatement(sqlStatement(it.trimIndent()))) }

    /** A manager for the graph [graph], with its tables made. Its connections come from [provider]'s pool. */
    fun graphManager(graph: String, registry: SubtypeRegistry = SubtypeRegistry()): NonTransactionalPersistenceManager =
        NonTransactionalPersistenceManager(
            JdbcConnectionProvider(graph, DatabaseType.SQLITE, provider.nativeDriver, registry, graphName = graph), graph, DatabaseType.SQLITE, registry,
        ).also { SqliteGraph(graph).ensure(it) }

    /** Removes every node and relationship of the graph [pm] speaks to. */
    fun clear(pm: NonTransactionalPersistenceManager) = SqlCypherFixtures.clear(pm)

    /** Writes the nodes and relationships a Cypher `CREATE` fixture describes to the graph [pm] speaks to. */
    fun seed(pm: PersistenceManager, cypher: String) {
        SqlCypherFixtures.run(pm, cypher)
    }

    /** [pm] as the cross-engine tests use a manager: see [SqlCypherFixtures.over]. */
    fun fixtures(pm: NonTransactionalPersistenceManager): PersistenceManager = SqlCypherFixtures.over(pm)

    /** The provider of [pm]'s connections, for a test that runs a statement on a connection of its own. */
    fun connections(pm: NonTransactionalPersistenceManager): ConnectionProvider =
        JdbcConnectionProvider(pm.database, DatabaseType.SQLITE, SqliteTestDatabase.provider.nativeDriver, graphName = requireNotNull(pm.graphName))

    /** Drops the tables of the graph [graph], when they are there. */
    fun drop(graph: String) = run("DROP TABLE IF EXISTS ${graph}_edge", "DROP TABLE IF EXISTS ${graph}_node", "DROP TABLE IF EXISTS ${graph}_schema")
}
