package org.drivine.oracle

import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSql
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.drivine.test.SqlCypherFixtures
import org.drivine.test.OracleTestContainer
import java.sql.DriverManager

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

    /**
     * The plan Oracle makes for [sql] on [pm], its [parameters] written in as literals so that the plan is the one for them,
     * shown in the [format] `DBMS_XPLAN.DISPLAY` takes. Explained and read in one block, so on one session.
     */
    fun plan(pm: NonTransactionalPersistenceManager, sql: String, parameters: Map<String, Any?>, format: String): String {
        val literal = parameters.entries.sortedByDescending { it.key.length }
            .fold(sql) { text, (name, value) -> text.replace("\$$name", if (value == null) "NULL" else OracleSql.literal(value.toString())) }
        val block = """
            DECLARE
                v_plan SYS_REFCURSOR;
            BEGIN
                EXECUTE IMMEDIATE ${OracleSql.text("EXPLAIN PLAN FOR $literal")};
                OPEN v_plan FOR SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(format => '$format'));
                DBMS_SQL.RETURN_RESULT(v_plan);
            END;
        """.trimIndent()
        return pm.query(QuerySpecification.withStatement(block).transform(String::class.java)).joinToString("\n")
    }

    /**
     * The plan of the statement the session [sid] ran last, as the child cursor that ran it holds it, shown in the [format]
     * `DBMS_XPLAN.DISPLAY_CURSOR` takes. Read as `system`, as the test user reads no `V$` view: a plan Oracle made again
     * on a later run, from what an earlier run found (statistics feedback), is a child of its own.
     */
    fun lastPlan(sid: String, format: String): String =
        DriverManager.getConnection(OracleTestContainer.getJdbcUrl(), "system", OracleTestContainer.PASSWORD).use { admin ->
            val (sqlId, child) = admin.prepareStatement("SELECT prev_sql_id, prev_child_number FROM v\$session WHERE sid = ?").use { statement ->
                statement.setInt(1, sid.toInt())
                statement.executeQuery().use { row -> check(row.next()) { "No session $sid." }; row.getString(1) to row.getInt(2) }
            }
            admin.prepareStatement("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, ?, ?))").use { statement ->
                statement.setString(1, sqlId)
                statement.setInt(2, child)
                statement.setString(3, format)
                statement.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.joinToString("\n") }
            }
        }

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
     * The provider of [pm]'s connections, for a test that runs a statement on a connection of its own:
     * a statement of no language that is Cypher is sent as Cypher, as [SqlCypherFixtures.over] sends it.
     */
    fun connections(pm: NonTransactionalPersistenceManager): ConnectionProvider {
        val provider = JdbcConnectionProvider(pm.database, DatabaseType.ORACLE, OracleTestDatabase.provider.nativeDriver, graphName = requireNotNull(pm.graphName))
        return object : ConnectionProvider by provider {
            override fun connect(): Connection = provider.connect().let { connection ->
                object : Connection by connection {
                    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = connection.query(SqlCypherFixtures.asCypher(spec))
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
