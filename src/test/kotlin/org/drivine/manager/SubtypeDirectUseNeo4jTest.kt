package org.drivine.manager

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.polytree.ChunkNode
import sample.polytree.DocumentNode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A concrete **sealed subtype** whose `@NodeId` is inherited from the interface getter is now directly
 * usable — `save`, `load(id)`, and rooting a `@GraphView` on it all work (they need a resolvable
 * `@GraphNodeId`, which previously came back null for such subtypes).
 */
@Testcontainers
class SubtypeDirectUseNeo4jTest {

    @NodeFragment(labels = ["ContentElement", "Document"])
    // A view rooted on a concrete sealed subtype (DocumentNode) — snapshot needs its id.
    @GraphView
    data class DocWithChildren(
        @Root val document: DocumentNode,
        @GraphRelationship(type = "HAS_PARENT", direction = Direction.INCOMING)
        val children: List<ChunkNode>,
    )

    companion object {
        private const val PW = "subtypedirect"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PW) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var gom: GraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-subtype", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PW, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            val mapper = Neo4jObjectMapper.instance
            gom = GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `save and load a sealed subtype directly by its inherited @NodeId`() {
        gom.save(DocumentNode(id = "d1", title = "Doc"))
        val loaded = gom.load("d1", DocumentNode::class.java)!!
        assertEquals("Doc", loaded.title)
        assertEquals("d1", loaded.id)
    }

    @Test
    fun `a view rooted on a concrete sealed subtype loads and snapshots`() {
        pm.execute(
            QuerySpecification.withStatement(
                """
                CREATE (d:ContentElement:Document {id:'d1', title:'Doc'})
                CREATE (c:ContentElement:Chunk {id:'c1', text:'Chunk'})
                CREATE (c)-[:HAS_PARENT]->(d)
                """.trimIndent()
            )
        )
        val view = gom.loadAll(DocWithChildren::class.java, "document.id = 'd1'").single()
        assertEquals("Doc", view.document.title)
        assertEquals(listOf("c1"), view.children.map { it.id })
        assertTrue(view.children.single().text == "Chunk")
    }
}
