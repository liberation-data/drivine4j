package org.drivine.manager

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@NodeFragment(labels = ["GraphIdLong"])
data class GraphIdLong(@NodeId val id: Long, val name: String)

@NodeFragment(labels = ["GraphIdDate"])
data class GraphIdDate(@NodeId val id: LocalDate, val name: String)

@NodeFragment(labels = ["GraphIdUuid"])
data class GraphIdUuid(@NodeId val id: UUID, val name: String)

@NodeFragment(labels = ["GraphIdString"])
data class GraphIdString(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["GraphIdTarget"])
data class GraphIdTarget(@NodeId val id: String, val name: String)

/** Days, each with the days that are part of it, one level deep: a cascade delete refuses a tree deeper than that. */
@GraphView
data class GraphIdDateTree(
    @Root val day: GraphIdDate,
    @GraphRelationship(type = "DAY_PART_OF", direction = Direction.INCOMING, maxDepth = 1)
    val parts: List<GraphIdDateTree> = emptyList(),
)

/**
 * A node found by its id's text on a graph engine (liberation-data/drivine4j#806), as on the SQL stores
 * (`NodeIdLookupContract`): a load, a delete by id, a cascade delete's depth check, a relate, an unrelate and `loadRelated`
 * read the text as the `@NodeId` field's type and bind it as a save writes the field. So a node saved with a `Long`, a
 * `LocalDate` or a `UUID` id is found again by its text, and text that is no value of the type finds nothing. A `String`
 * id is the text given.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class NodeIdTextGraphContract {

    protected abstract val pm: PersistenceManager
    private val manager by lazy { StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry()) }

    private fun count(cypher: String): Long = pm.query(QuerySpecification.withStatement(cypher).transform(Long::class.java)).single()

    @BeforeAll
    fun seed() {
        listOf("GraphIdLong", "GraphIdDate", "GraphIdUuid", "GraphIdString", "GraphIdTarget").forEach {
            pm.execute(QuerySpecification.withStatement("MATCH (n:$it) DETACH DELETE n"))
        }
        manager.save(GraphIdTarget("x", "the target"))
    }

    /**
     * Saves [node], then finds it by [text] alone: a load gives it back, a relate and `loadRelated` join it to the target and
     * read the target back, an unrelate removes the join, and a delete deletes it.
     */
    private fun <T : Any> roundTrip(node: T, text: String) {
        val type = node.javaClass
        val ref = NodeRef(type, text)
        val target = NodeRef(GraphIdTarget::class.java, "x")
        manager.save(node)

        assertEquals(node, manager.load(text, type), "a load by the id's text gives the node saved")
        assertTrue(manager.edges.relate(ref, target, "POINTS_TO"), "a ref by the id's text finds the node")
        assertEquals(listOf("x"), manager.edges.loadRelated(ref, "POINTS_TO", Direction.OUTGOING, GraphIdTarget::class.java).map { it.id })
        assertEquals(1, manager.edges.unrelate(ref, target, "POINTS_TO"))
        assertEquals(emptyList(), manager.edges.loadRelated(ref, "POINTS_TO", Direction.OUTGOING, GraphIdTarget::class.java))
        assertEquals(1, manager.delete(text, type))
        assertNull(manager.load(text, type))
    }

    @Test
    fun `a node of a Long id is found by its text`() = roundTrip(GraphIdLong(5, "five"), "5")

    @Test
    fun `a node of a LocalDate id is found by its text`() = roundTrip(GraphIdDate(LocalDate.of(2024, 3, 4), "march"), "2024-03-04")

    @Test
    fun `a node of a UUID id is found by its text`() {
        val id = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
        roundTrip(GraphIdUuid(id, "uuid"), id.toString())
    }

    @Test
    fun `a node of a String id is found by its text`() = roundTrip(GraphIdString("t5", "text"), "t5")

    @Test
    fun `an unrelate of all by a Long id's text removes its relationships`() {
        manager.save(GraphIdLong(8, "eight"))
        val ref = NodeRef(GraphIdLong::class.java, "8")
        assertTrue(manager.edges.relate(ref, NodeRef(GraphIdTarget::class.java, "x"), "POINTS_TO"))

        assertEquals(1, manager.edges.unrelateAll(ref, "POINTS_TO", Direction.OUTGOING))
        assertEquals(0L, count("MATCH (:GraphIdLong {name: 'eight'})-[r:POINTS_TO]->() RETURN count(r)"))
    }

    /**
     * A cascade delete by a `LocalDate` id's text finds its root as a load does, both in the check that refuses a tree
     * deeper than the view reads and in the delete itself.
     */
    @Test
    fun `a cascade delete by a LocalDate id's text checks the depth and deletes the tree`() {
        val days = (0..2).map { GraphIdDate(LocalDate.of(2026, 2, 1 + it), "tree day $it") }
        days.forEach { manager.save(it) }
        days.zipWithNext { parent, part ->
            assertTrue(manager.edges.relate(NodeRef(GraphIdDate::class.java, part.id.toString()), NodeRef(GraphIdDate::class.java, parent.id.toString()), "DAY_PART_OF"))
        }
        val inTree = "MATCH (n:GraphIdDate) WHERE n.name STARTS WITH 'tree day' RETURN count(n)"

        val refused = assertFailsWith<IllegalStateException> { manager.delete("2026-02-01", GraphIdDateTree::class.java, CascadeType.DELETE_ALL) }
        assertTrue("1 node below that would be left behind" in refused.message.orEmpty(), refused.message)
        assertEquals(3L, count(inTree))

        assertEquals(1, manager.delete("2026-02-03", GraphIdDate::class.java))
        assertEquals(2, manager.delete("2026-02-01", GraphIdDateTree::class.java, CascadeType.DELETE_ALL))
        assertEquals(0L, count(inTree))
    }

    @Test
    fun `text on a String id finds only the string, not a number of the same text`() {
        pm.execute(QuerySpecification.withStatement("CREATE (:GraphIdString {id: 6, name: 'number'})"))
        manager.save(GraphIdString("6", "string"))

        assertEquals(GraphIdString("6", "string"), manager.load("6", GraphIdString::class.java))
        assertEquals(1, manager.delete("6", GraphIdString::class.java))
        assertEquals(1L, count("MATCH (n:GraphIdString) WHERE n.id = 6 RETURN count(n)"))
        assertNull(manager.load("6", GraphIdString::class.java))
    }

    @Test
    fun `text that is no number finds nothing on a Long id, and nothing is thrown`() {
        manager.save(GraphIdLong(7, "seven"))
        val nowhere = NodeRef(GraphIdLong::class.java, "abc")

        assertNull(manager.load("abc", GraphIdLong::class.java))
        assertEquals(0, manager.delete("abc", GraphIdLong::class.java))
        assertEquals(emptyList(), manager.edges.loadRelated(nowhere, "POINTS_TO", Direction.OUTGOING, GraphIdTarget::class.java))
        assertTrue(!manager.edges.relate(nowhere, NodeRef(GraphIdTarget::class.java, "x"), "POINTS_TO"))
        assertEquals(GraphIdLong(7, "seven"), manager.load("7", GraphIdLong::class.java))
    }

    @Test
    fun `a patch of a node of a LocalDate id finds it`() {
        val day = LocalDate.of(2024, 5, 6)
        manager.save(GraphIdDate(day, "before"))

        assertEquals(PatchResult(found = true, applied = true, changed = true), manager.patchAllFields(listOf(GraphIdDate(day, "after")), only = setOf("name")).single())
        assertEquals(GraphIdDate(day, "after"), manager.load("2024-05-06", GraphIdDate::class.java))
    }
}

/**
 * Each engine's manager is made when the contract's `@BeforeAll` first asks for it, which runs before a subclass's own
 * static `@BeforeAll` would.
 */
class NodeIdTextGraphNeo4jTest : NodeIdTextGraphContract() {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private val registry = SubtypeRegistry()
        private val provider by lazy {
            Neo4jConnectionProvider(
                name = "neo-graph-id-text", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j", config = emptyMap(),
                subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
        }
        private val manager by lazy { NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry) }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager
}

class NodeIdTextGraphFalkorDbTest : NodeIdTextGraphContract() {
    companion object {
        private const val GRAPH = "graphidtext"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private val registry = SubtypeRegistry()
        private val provider by lazy {
            FalkorDbConnectionProvider(
                name = "falkor-graph-id-text", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
        }
        private val manager by lazy { NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry) }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager
}

class NodeIdTextGraphMemgraphTest : NodeIdTextGraphContract() {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private val registry = SubtypeRegistry()
        private val provider by lazy {
            Neo4jConnectionProvider(
                name = "memgraph-graph-id-text", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
        }
        private val manager by lazy { NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry) }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager
}
