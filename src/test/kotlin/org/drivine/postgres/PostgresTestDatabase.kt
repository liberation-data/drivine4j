package org.drivine.postgres

import org.drivine.backend.postgres.PostgresGraph
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.test.MiniCypher
import org.drivine.test.PostgresTestContainer
import org.drivine.test.SqlCypherFixtures
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Marks a test class that needs a Postgres database. They run only with `POSTGRES_TESTS=true`, as the
 * Oracle tests run only with `ORACLE_TESTS=true`: see `TEST_CONTAINERS.md`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TESTS", matches = "true")
annotation class PostgresTests

/** The test JVM's Postgres, from [PostgresTestContainer]: a container started when a test first asks for it, or a local instance. */
object PostgresTestDatabase {

    /** One pool for the test JVM, closed when it ends. */
    val provider: JdbcConnectionProvider by lazy {
        JdbcConnectionProvider.pooled(
            "postgres", DatabaseType.POSTGRES, PostgresTestContainer.getJdbcUrl(),
            PostgresTestContainer.getConnectionUsername(), PostgresTestContainer.getConnectionPassword(),
        ).also { pool -> Runtime.getRuntime().addShutdownHook(Thread { pool.end() }) }
    }

    val pm: NonTransactionalPersistenceManager by lazy {
        NonTransactionalPersistenceManager(provider, "postgres", DatabaseType.POSTGRES, SubtypeRegistry())
    }

    /** Runs each statement on its own, committed as it runs. */
    fun run(vararg statements: String) = statements.forEach { pm.execute(QuerySpecification.withStatement(it.trimIndent())) }

    /** A manager for the graph [graph], with its tables made. Its connections come from [provider]'s pool. */
    fun graphManager(graph: String, registry: SubtypeRegistry = SubtypeRegistry()): NonTransactionalPersistenceManager =
        NonTransactionalPersistenceManager(
            JdbcConnectionProvider(graph, DatabaseType.POSTGRES, provider.nativeDriver, registry, graphName = graph), graph, DatabaseType.POSTGRES, registry,
        ).also { PostgresGraph(graph).ensure(it) }

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
        val provider = JdbcConnectionProvider(pm.database, DatabaseType.POSTGRES, PostgresTestDatabase.provider.nativeDriver, graphName = requireNotNull(pm.graphName))
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

    /** Drops the tables of the graph [graph], when they are there, and the functions over them. */
    fun drop(graph: String) = run("DROP TABLE IF EXISTS ${graph}_edge, ${graph}_node, ${graph}_schema CASCADE")
}
