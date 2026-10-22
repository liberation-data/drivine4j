package org.drivine.oracle

import org.drivine.backend.oracle.OracleGraph
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.drivine.test.MiniCypher
import org.drivine.test.SqlCypherFixtures
import org.drivine.test.OracleTestContainer

/**
 * Marks a test class that needs an Oracle database. They run only with `ORACLE_TESTS=true`: the
 * image is large, and the database wants two gigabytes beside the graph engines' containers.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS", matches = "true")
annotation class OracleTests

/** The test JVM's Oracle, from [OracleTestContainer]: a container started when a test first asks for it, or a local instance. */
object OracleTestDatabase {

    /**
     * One pool for the test JVM, closed when it ends. The managers [graphManager] and [connections]
     * give out share its data source, so none of them has a pool of its own to end.
     */
    val provider: JdbcConnectionProvider by lazy {
        JdbcConnectionProvider.pooled(
            "oracle", DatabaseType.ORACLE, OracleTestContainer.getJdbcUrl(),
            OracleTestContainer.getConnectionUsername(), OracleTestContainer.getConnectionPassword(),
        ).also { pool -> Runtime.getRuntime().addShutdownHook(Thread { pool.end() }) }
    }

    val pm: NonTransactionalPersistenceManager by lazy {
        NonTransactionalPersistenceManager(provider, "oracle", DatabaseType.ORACLE, SubtypeRegistry())
    }

    /** Runs each statement on its own, committed as it runs. */
    fun run(vararg statements: String) = statements.forEach { pm.execute(QuerySpecification.withStatement(it.trimIndent())) }

    /** A manager for the graph [graph], with its tables made. */
    fun graphManager(graph: String, registry: SubtypeRegistry = SubtypeRegistry()): NonTransactionalPersistenceManager =
        NonTransactionalPersistenceManager(
            JdbcConnectionProvider(graph, DatabaseType.ORACLE, provider.nativeDriver, registry, graphName = graph), graph, DatabaseType.ORACLE, registry,
        ).also { OracleGraph(graph).ensure(it) }

    /** Removes every node and relationship of the graph [pm] speaks to. */
    fun clear(pm: NonTransactionalPersistenceManager) = SqlCypherFixtures.clear(pm)

    /** Writes the nodes and relationships a Cypher `CREATE` fixture describes to the graph [pm] speaks to. */
    fun seed(pm: PersistenceManager, cypher: String) {
        SqlCypherFixtures.run(pm, cypher)
    }

    /** [pm] as the cross-engine tests use a manager: see [SqlCypherFixtures.over]. */
    fun fixtures(pm: NonTransactionalPersistenceManager): PersistenceManager = SqlCypherFixtures.over(pm)

    /**
     * The provider of [pm]'s connections, for a test that runs a statement on a connection of its own.
     * A Cypher read on one is a fixture's first read, made to open a snapshot on the connection, and is
     * answered by a count of the graph's nodes on that connection: the rows are not what the Cypher
     * asked for, and a test must not read them as such.
     */
    fun connections(pm: NonTransactionalPersistenceManager): ConnectionProvider {
        val provider = JdbcConnectionProvider(pm.database, DatabaseType.ORACLE, OracleTestDatabase.provider.nativeDriver, graphName = requireNotNull(pm.graphName))
        return object : ConnectionProvider by provider {
            override fun connect(): Connection = provider.connect().let { connection ->
                object : Connection by connection {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> =
                        if (MiniCypher.reads(spec.statement!!.text)) {
                            connection.query(QuerySpecification.withStatement("SELECT COUNT(*) FROM ${pm.graphName}_node")) as List<T>
                        } else {
                            connection.query(spec)
                        }
                }
            }
        }
    }

    /** Drops what [names] name, when it is there: `TABLE x`, `PROPERTY GRAPH g`. A graph goes before its tables. */
    fun drop(vararg names: String) = names.forEach {
        val kind = it.substringBeforeLast(' ')
        run("DROP $kind IF EXISTS ${it.substringAfterLast(' ')}${if (kind == "TABLE") " CASCADE CONSTRAINTS PURGE" else ""}")
    }
}
