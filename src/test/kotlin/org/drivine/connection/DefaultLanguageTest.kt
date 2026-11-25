package org.drivine.connection

import org.drivine.Drivine
import org.drivine.DrivineException
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.QueryLanguage
import org.drivine.query.QuerySpecification
import org.drivine.query.cypherStatement
import org.drivine.query.sqlStatement
import org.drivine.test.SharedNeo4j
import org.drivine.test.SqlCypherFixtures
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A statement of no language is Cypher on every store, a SQL store compiling it to its SQL, unless its datasource is
 * registered with `defaultLanguage = SQL`; a statement that names its language runs as it says either way.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DefaultLanguageContract {

    /** A manager over the graph [graph], its datasource registered with [language] as its default, or none. */
    protected abstract fun manager(graph: String, language: QueryLanguage?): PersistenceManager

    /** The SQL that counts the nodes of [graph]. */
    protected open fun countSql(graph: String) = "SELECT COUNT(*) AS c FROM ${graph}_node"

    protected open val graph = "langdefault"

    private val cypherManager by lazy { manager(graph, null) }
    private val sqlManager by lazy { manager(graph, QueryLanguage.SQL) }

    @BeforeEach
    fun cleared() = SqlCypherFixtures.clear(cypherManager)

    private fun count(pm: PersistenceManager, spec: QuerySpecification<Any>): Long = (pm.query(spec).single() as Number).toLong()

    @Test
    fun `a statement of no language is Cypher, compiled to the store's SQL`() {
        cypherManager.execute(QuerySpecification.withStatement("CREATE (:Lang {id: 'a'}), (:Lang {id: 'b'})"))

        assertEquals(
            listOf("a", "b"),
            cypherManager.query(QuerySpecification.withStatement("MATCH (n:Lang) RETURN n.id ORDER BY n.id").transform(String::class.java)),
        )
    }

    @Test
    fun `a statement marked SQL runs as SQL`() {
        cypherManager.execute(QuerySpecification.withStatement("CREATE (:Lang {id: 'a'})"))

        assertEquals(1L, count(cypherManager, QuerySpecification.withStatement(sqlStatement(countSql(graph)))))
    }

    @Test
    fun `on a datasource that defaults to SQL a statement of no language is SQL, and one marked Cypher is compiled`() {
        sqlManager.execute(QuerySpecification.withStatement(cypherStatement("CREATE (:Lang {id: 'a'})")))

        assertEquals(1L, count(sqlManager, QuerySpecification.withStatement(countSql(graph))))
        assertEquals(
            listOf("a"),
            sqlManager.query(QuerySpecification.withStatement(cypherStatement("MATCH (n:Lang) RETURN n.id")).transform(String::class.java)),
        )
        // Cypher of no language is SQL there, which the store refuses.
        assertFailsWith<Exception> { sqlManager.query(QuerySpecification.withStatement("MATCH (n:Lang) RETURN n.id")) }
    }
}

/** On SQLite, through [Drivine] and the connection properties a datasource is registered with. */
class DefaultLanguageSqliteTest : DefaultLanguageContract() {

    @TempDir
    lateinit var directory: Path

    private val drivines = mutableListOf<Drivine>()

    override fun manager(graph: String, language: QueryLanguage?): PersistenceManager {
        val properties = ConnectionProperties(
            type = DatabaseType.SQLITE, databaseName = directory.resolve("lang.db").toString(), defaultGraphPath = graph,
            defaultLanguage = language,
        )
        val drivine = Drivine(DataSourceMap(mapOf("graph" to properties))).also { drivines += it }
        return drivine.persistenceManager("graph")
    }

    @AfterAll
    fun closed() = drivines.forEach { it.close() }
}

@PostgresTests
class DefaultLanguagePostgresTest : DefaultLanguageContract() {
    override fun manager(graph: String, language: QueryLanguage?): PersistenceManager {
        PostgresTestDatabase.graphManager(graph)
        val provider = JdbcConnectionProvider(
            graph, DatabaseType.POSTGRES, PostgresTestDatabase.provider.nativeDriver, graphName = graph,
            defaultLanguage = language ?: QueryLanguage.CYPHER,
        )
        return NonTransactionalPersistenceManager(provider, graph, DatabaseType.POSTGRES, SubtypeRegistry())
    }
}

@OracleTests
class DefaultLanguageOracleTest : DefaultLanguageContract() {
    override fun manager(graph: String, language: QueryLanguage?): PersistenceManager {
        OracleTestDatabase.graphManager(graph)
        val provider = JdbcConnectionProvider(
            graph, DatabaseType.ORACLE, OracleTestDatabase.provider.nativeDriver, graphName = graph,
            defaultLanguage = language ?: QueryLanguage.CYPHER,
        )
        return NonTransactionalPersistenceManager(provider, graph, DatabaseType.ORACLE, SubtypeRegistry())
    }
}

/** On Neo4j a statement of no language is Cypher, as it always was, and a datasource may say so. */
class DefaultLanguageNeo4jTest {
    companion object {
        @JvmField
        val container = SharedNeo4j.forClass()
    }

    @Test
    fun `a statement of no language is Cypher, with the default unset or set to Cypher`() {
        listOf(null, QueryLanguage.CYPHER).forEach { language ->
            val properties = ConnectionProperties(
                type = DatabaseType.NEO4J, host = container.host, port = container.getMappedPort(7687),
                userName = "neo4j", password = SharedNeo4j.PASSWORD, defaultLanguage = language,
            )
            Drivine(DataSourceMap(mapOf("neo" to properties))).use { drivine ->
                val pm = drivine.persistenceManager("neo")
                pm.execute(QuerySpecification.withStatement("CREATE (:Lang {id: 'a'})"))
                assertEquals(listOf("a"), pm.query(QuerySpecification.withStatement("MATCH (n:Lang) RETURN n.id").transform(String::class.java)))
                pm.execute(QuerySpecification.withStatement("MATCH (n:Lang) DETACH DELETE n"))
            }
        }
    }
}

/** SQL as the default of a store that runs only Cypher is refused as its provider is built, saying why and what to do. */
class DefaultLanguageRefusalTest {

    private fun message(name: String, type: DatabaseType) =
        "Datasource '$name' is ${type.value}, which runs Cypher and no SQL, so its defaultLanguage cannot be SQL. " +
            "Leave defaultLanguage unset, or set it to CYPHER, and mark a statement that is SQL with sqlStatement(...) on a store that speaks SQL."

    @Test
    fun `SQL is refused as the default of each store that speaks only Cypher, built from properties`() {
        listOf(DatabaseType.NEO4J, DatabaseType.FALKORDB, DatabaseType.MEMGRAPH, DatabaseType.NEPTUNE).forEach { type ->
            val properties = ConnectionProperties(type = type, host = "localhost", userName = "u", password = "p", defaultLanguage = QueryLanguage.SQL)
            val refused = assertFailsWith<DrivineException>(type.name) { DatabaseRegistry(DataSourceMap(mapOf("graph" to properties))) }
            assertEquals(message("graph", type), refused.message)
        }
    }

    @Test
    fun `SQL is refused as the default of an engine whose provider is the application's`() {
        val properties = ConnectionProperties(type = DatabaseType.EMBABEL, defaultLanguage = QueryLanguage.SQL)
        val refused = assertFailsWith<DrivineException> { DatabaseRegistry(DataSourceMap(mapOf("engine" to properties))) }
        assertEquals(message("engine", DatabaseType.EMBABEL), refused.message)
    }

    @Test
    fun `SQL is refused by the builder too`() {
        val builder = DatabaseRegistry(DataSourceMap()).builder()
            .withType(DatabaseType.NEO4J).host("localhost").userName("neo4j").defaultLanguage(QueryLanguage.SQL)
        val refused = assertFailsWith<DrivineException> { builder.register("neo") }
        assertEquals(message("neo", DatabaseType.NEO4J), refused.message)
    }

    @Test
    fun `Cypher, or no default, is every store's, and SQL a SQL store's`() {
        DatabaseType.entries.forEach { type ->
            assertNull(ConnectionProviderBuilder.languageRefusal("graph", type, null))
            assertNull(ConnectionProviderBuilder.languageRefusal("graph", type, QueryLanguage.CYPHER))
        }
        listOf(DatabaseType.POSTGRES, DatabaseType.ORACLE, DatabaseType.SQLITE).forEach { type ->
            assertNull(ConnectionProviderBuilder.languageRefusal("graph", type, QueryLanguage.SQL))
        }
    }
}
