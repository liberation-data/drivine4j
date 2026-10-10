package org.drivine.oracle

import org.drivine.backend.oracle.OracleGraph
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.oracle.OracleContainer

/**
 * Marks a test class that needs an Oracle database. They run only with `ORACLE_TESTS=true`: the
 * image is large, and the database wants two gigabytes beside the graph engines' containers.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS", matches = "true")
annotation class OracleTests

/** One Oracle Free container for the test JVM, started when a test first asks for it. */
object OracleTestDatabase {

    val container: OracleContainer by lazy { OracleContainer("gvenzl/oracle-free:23-slim-faststart").also { it.start() } }

    val provider: JdbcConnectionProvider by lazy {
        JdbcConnectionProvider.pooled("oracle", DatabaseType.ORACLE, container.jdbcUrl, container.username, container.password)
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
    fun clear(pm: NonTransactionalPersistenceManager) {
        val graph = requireNotNull(pm.graphName)
        pm.execute(QuerySpecification.withStatement("DELETE FROM ${graph}_edge"))
        pm.execute(QuerySpecification.withStatement("DELETE FROM ${graph}_node"))
    }

    /** Writes the nodes and relationships a Cypher `CREATE` fixture describes to the graph [pm] speaks to. */
    fun seed(pm: NonTransactionalPersistenceManager, cypher: String) {
        val graph = requireNotNull(pm.graphName)
        val seed = CypherSeed.parse(cypher)
        val insert = """
            DECLARE
                v_nid NUMBER;
                v_rows SYS_REFCURSOR;
            BEGIN
                INSERT INTO ${graph}_node (labels, props) VALUES (${'$'}labels, ${'$'}props) RETURNING nid INTO v_nid;
                OPEN v_rows FOR SELECT v_nid FROM dual;
                DBMS_SQL.RETURN_RESULT(v_rows);
            END;
        """.trimIndent()
        val ids = seed.nodes.map { node ->
            pm.getOne(QuerySpecification.withStatement(insert).bind(mapOf("labels" to node.labels.sorted(), "props" to node.properties)).transform(Long::class.java))
        }
        seed.edges.forEach { edge ->
            pm.execute(
                QuerySpecification.withStatement("INSERT INTO ${graph}_edge (src, dst, type, props) VALUES (\$src, \$dst, \$type, \$props)")
                    .bind(mapOf("src" to ids[edge.from], "dst" to ids[edge.to], "type" to edge.type, "props" to edge.properties))
            )
        }
    }

    /**
     * [pm] as the cross-engine tests use a manager for their fixtures: a Cypher `CREATE` is written
     * as rows, and the statement that empties a graph empties this one. Anything else is the store's.
     */
    fun fixtures(pm: NonTransactionalPersistenceManager): PersistenceManager = object : PersistenceManager by pm {
        override fun execute(spec: QuerySpecification<*>) {
            val text = spec.statement!!.text.trim()
            when {
                text.startsWith("CREATE", ignoreCase = true) -> seed(pm, text)
                text == "MATCH (n) DETACH DELETE n" -> clear(pm)
                else -> pm.execute(spec)
            }
        }
    }

    /** Drops what [names] name, when it is there: `TABLE x`, `PROPERTY GRAPH g`. A graph goes before its tables. */
    fun drop(vararg names: String) = names.forEach {
        val kind = it.substringBeforeLast(' ')
        run("DROP $kind IF EXISTS ${it.substringAfterLast(' ')}${if (kind == "TABLE") " CASCADE CONSTRAINTS PURGE" else ""}")
    }
}
