package org.drivine.cypher

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.cypher.spec.CypherSpec
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.GraphNode
import org.drivine.query.GraphPath
import org.drivine.query.GraphRelationship
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * [PersistenceManager.queryResult] gives a statement's columns, and its nodes and relationships with their labels and type,
 * the same on every store: the statements of the spec's `cypher/results.yaml` are run on each over its graph, and give the
 * columns and rows it says.
 */
abstract class CypherResultsContract {

    abstract val pm: PersistenceManager

    /** Writes the graph a Cypher `CREATE` describes. */
    abstract fun seed(cypher: String)

    /** Removes the graph the tests wrote. */
    abstract fun clear()

    @TestFactory
    fun spec() = CypherSpec.load("results.yaml").let { spec ->
        spec.tests(CypherSpec.storeOf(pm.type), before = { clear(); seed(requireNotNull(spec.graph)) }) { step ->
            val result = pm.queryResult(QuerySpecification.withStatement(CypherStatement(step.cypher.trimIndent())).bind(step.parameters))
            step.columns?.let { assertEquals(it, result.columns, "The columns of ${step.cypher.trim()} on ${CypherSpec.storeOf(pm.type)}") }
            // A row of one column is its value, as the spec writes it.
            result.rows.map { row -> asWritten(if (row.size == 1) row.single() else row) }
        }
    }

    /** Not in the spec yet (liberation-data/drivine-cypher-spec#163), so pinned here, on the stores that give a path. */
    @Test
    fun `a path says which way it walks each relationship`() {
        assumeTrue(pm.type.speaksCypher, "A SQL store refuses a path: liberation-data/drivine-cypher-spec#163.")
        clear()
        seed(requireNotNull(CypherSpec.load("results.yaml").graph))
        fun path(cypher: String) = pm.queryResult(QuerySpecification.withStatement(CypherStatement(cypher))).rows.single().single() as GraphPath
        val against = path("MATCH p = (:Person {name: 'Carrie'})<-[:KNOWS]-(:Person) RETURN p")
        assertEquals(listOf(false), against.forward)
        assertEquals(listOf("Carrie", "Keanu"), against.nodes.map { it.properties["name"] })
        val both = path("MATCH p = (:Person {name: 'Carrie'})-[:ACTED_IN]->(:Movie)<-[:ACTED_IN]-(:Person {name: 'Keanu'}) RETURN p")
        assertEquals(listOf(true, false), both.forward)
        assertEquals(listOf("Trinity", "Neo"), both.relationships.map { it.properties["role"] })
    }

    companion object {
        /**
         * [value] as `results.yaml` writes it: a node, a relationship and a path as a map tagged `$t`, and a map with a `$t` key of
         * its own as `{$map: …}`. A path's directions are not in the spec yet (liberation-data/drivine-cypher-spec#163).
         */
        fun asWritten(value: Any?): Any? = when (value) {
            is GraphNode -> mapOf("\$t" to "node", "labels" to value.labels, "properties" to asWritten(value.properties))
            is GraphRelationship -> mapOf("\$t" to "relationship", "type" to value.type, "properties" to asWritten(value.properties))
            is GraphPath -> mapOf("\$t" to "path", "nodes" to value.nodes.map(::asWritten), "relationships" to value.relationships.map(::asWritten))
            is Map<*, *> -> value.mapValues { asWritten(it.value) }.let { if ("\$t" in value) mapOf("\$map" to it) else it }
            is List<*> -> value.map(::asWritten)
            else -> value
        }
    }
}

class CypherResultsNeo4jTest : CypherResultsContract() {
    companion object {
        @JvmField
        val container = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-results", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager
    override fun seed(cypher: String) = manager.execute(QuerySpecification.withStatement(cypher.trimIndent()))
    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
}

class CypherResultsMemgraphTest : CypherResultsContract() {
    companion object {
        @JvmField
        val container = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "memgraph-results", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null,
                config = emptyMap(), cypherDialect = CypherDialect.MEMGRAPH,
            )
            manager = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, SubtypeRegistry())
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager
    override fun seed(cypher: String) = manager.execute(QuerySpecification.withStatement(cypher.trimIndent()))
    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
}

class CypherResultsFalkorDbTest : CypherResultsContract() {
    companion object {
        @JvmField
        val container = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            provider = FalkorDbConnectionProvider(
                name = "falkor-results", host = container.host, port = container.getMappedPort(6379), password = null, graphName = "results",
            )
            manager = NonTransactionalPersistenceManager(provider, "falkordb", DatabaseType.FALKORDB, SubtypeRegistry())
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager
    override fun seed(cypher: String) = manager.execute(QuerySpecification.withStatement(cypher.trimIndent()))
    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
}

@PostgresTests
class CypherResultsPostgresTest : CypherResultsContract() {
    private val manager = PostgresTestDatabase.graphManager("results")
    override val pm: PersistenceManager get() = manager
    override fun seed(cypher: String) = PostgresTestDatabase.seed(manager, cypher)
    override fun clear() = PostgresTestDatabase.clear(manager)
}

@OracleTests
class CypherResultsOracleTest : CypherResultsContract() {
    private val manager = OracleTestDatabase.graphManager("results")
    override val pm: PersistenceManager get() = manager
    override fun seed(cypher: String) = OracleTestDatabase.seed(manager, cypher)
    override fun clear() = OracleTestDatabase.clear(manager)
}

class CypherResultsSqliteTest : CypherResultsContract() {
    private val manager = SqliteTestDatabase.graphManager("results")
    override val pm: PersistenceManager get() = manager
    override fun seed(cypher: String) = SqliteTestDatabase.seed(manager, cypher)
    override fun clear() = SqliteTestDatabase.clear(manager)
}
