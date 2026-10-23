package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A node or relationship inside a list or a map comes back as its properties, as one that is a whole
 * column does, on Neo4j, FalkorDB and Memgraph. On the Bolt engines these used to come back as the
 * driver's `InternalNode` and `InternalRelationship` (drivine4j#121). A path inside a list or a map
 * comes back as `{nodes, edges}`, the shape FalkorDB gives a path.
 *
 * FalkorDB's maps carry `labels` on a node and `_type`, `_source`, `_destination` on a relationship
 * besides the properties, and a Bolt engine's carry only the properties, so the checks read properties.
 */
private fun verify(pm: PersistenceManager, pathColumn: (Any) -> Unit) {
    fun one(statement: String): Any = pm.query(QuerySpecification.withStatement(statement)).single()
    fun props(value: Any?, key: String): Any? = assertIs<Map<*, *>>(value)[key]
    fun names(value: Any?) = assertIs<List<*>>(value).map { props(it, "name") }
    fun sinces(value: Any?) = assertIs<List<*>>(value).map { props(it, "since") }

    val path = "MATCH p = (:Person {name: 'Laurence'})-[:KNOWS*2]->(:Person)"

    assertEquals(listOf(1L, 2L), sinces(one("$path RETURN relationships(p)")))
    assertEquals(listOf("Laurence", "Keanu", "Carrie"), names(one("$path RETURN nodes(p)")))
    assertEquals(
        listOf("Carrie", "Keanu", "Laurence"),
        names(one("MATCH (n:Person) WITH n ORDER BY n.name RETURN collect(n)")),
    )
    assertEquals(listOf("Laurence", "Keanu"), names(one("MATCH (n:Person {name: 'Laurence'})-[:KNOWS]->(m) RETURN [n, m]")))
    assertEquals("Laurence", props(props(one("MATCH (n:Person {name: 'Laurence'}) RETURN {actor: n}"), "actor"), "name"))
    assertEquals(1L, props(props(one("MATCH ()-[r:KNOWS {since: 1}]->() RETURN {knows: r}"), "knows"), "since"))

    // Deeper: a map in a list in a map.
    val deep = one("MATCH (n:Person {name: 'Laurence'})-[r:KNOWS]->(m) RETURN {pairs: [{from: n, via: r, to: m}]}")
    val pair = assertIs<List<*>>(props(deep, "pairs")).single()
    assertEquals("Laurence", props(props(pair, "from"), "name"))
    assertEquals(1L, props(props(pair, "via"), "since"))
    assertEquals("Keanu", props(props(pair, "to"), "name"))

    // A path inside a list or a map is its nodes and its relationships, in order.
    fun verifyPath(value: Any?) {
        assertEquals(listOf("Laurence", "Keanu", "Carrie"), names(props(value, "nodes")))
        assertEquals(listOf(1L, 2L), sinces(props(value, "edges")))
    }
    verifyPath(assertIs<List<*>>(one("$path RETURN [p]")).single())
    verifyPath(props(one("$path RETURN {path: p}"), "path"))

    // A node or relationship that is a whole column is unchanged: its properties.
    assertEquals("Keanu", props(one("MATCH (n:Person {name: 'Keanu'}) RETURN n"), "name"))
    assertEquals(2L, props(one("MATCH ()-[r:KNOWS {since: 2}]->() RETURN r"), "since"))

    // A path that is a whole column is left as each engine gave it before.
    pathColumn(one("$path RETURN p"))
}

/** A whole path column on a Bolt engine is the driver's own Path. */
private fun boltPath(value: Any) {
    val path = assertIs<org.neo4j.driver.types.Path>(value)
    assertEquals(listOf("Laurence", "Keanu", "Carrie"), path.nodes().map { it["name"].asString() })
}

/** A whole path column on FalkorDB is `{nodes, edges}`. */
private fun falkorPath(value: Any) {
    val map = assertIs<Map<*, *>>(value)
    assertEquals(listOf("Laurence", "Keanu", "Carrie"), assertIs<List<*>>(map["nodes"]).map { (it as Map<*, *>)["name"] })
    assertEquals(listOf(1L, 2L), assertIs<List<*>>(map["edges"]).map { (it as Map<*, *>)["since"] })
}

private const val SEED = """
    CREATE (:Person {name: 'Laurence'})-[:KNOWS {since: 1}]->(:Person {name: 'Keanu'})-[:KNOWS {since: 2}]->(:Person {name: 'Carrie'})
"""

private fun seed(pm: PersistenceManager) {
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    pm.execute(QuerySpecification.withStatement(SEED.trimIndent()))
}

class NestedEntityNeo4jTest {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-nested", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun seed() = seed(pm)

    @Test
    fun `nodes and relationships inside lists and maps come back as properties on Neo4j`() = verify(pm, ::boltPath)
}

class NestedEntityFalkorDbTest {
    companion object {
        private const val GRAPH = "nestedtest"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-nested", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun seed() = seed(pm)

    @Test
    fun `nodes and relationships inside lists and maps come back as properties on FalkorDB`() = verify(pm, ::falkorPath)
}

class NestedEntityMemgraphTest {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-nested", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun seed() = seed(pm)

    @Test
    fun `nodes and relationships inside lists and maps come back as properties on Memgraph`() = verify(pm, ::boltPath)
}
