package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
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
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.polytree.ChunkNode
import sample.polytree.ContentElementNode
import sample.polytree.ContentTreeView
import sample.polytree.DocumentNode
import sample.polytree.FolderWithHead
import sample.polytree.LeafSectionNode
import sample.polytree.TypedContentTreeView
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A **polymorphic root fragment inside a recursive `@GraphView`** loads a mixed-label tree with each
 * node deserialized to its concrete subtype, verified on Neo4j, FalkorDB, and Memgraph. Guards:
 *  - the recursive polymorphic view dispatches per node (`is DocumentNode` / `LeafSectionNode` /
 *    `ChunkNode`) at depth ≥ 2, and a subtype-only `@GraphProperty` round-trips through dispatch;
 *  - the existing **concrete** recursive view over the same nodes still loads structure (regression);
 *  - a non-recursive polymorphic relationship target dispatches too.
 *
 * Tree seeded (HAS_PARENT points child → parent; the view's INCOMING edge makes them children):
 *   Document(d) ← LeafSection(s) ← Chunk(c)
 */
private fun seed(pm: NonTransactionalPersistenceManager) {
    pm.execute(
        QuerySpecification.withStatement(
            """
            CREATE (d:ContentElement:Document {id: 'd', title: 'Doc'})
            CREATE (s:ContentElement:LeafSection {id: 's', heading: 'Sec'})
            CREATE (c:ContentElement:Chunk {id: 'c', text: 'Chunk', container_section_id: 'sec-1'})
            CREATE (s)-[:HAS_PARENT]->(d)
            CREATE (c)-[:HAS_PARENT]->(s)
            CREATE (f:Folder {id: 'f', name: 'F'})
            CREATE (h:ContentElement:Chunk {id: 'h', text: 'Head'})
            CREATE (f)-[:HAS_HEAD]->(h)
            """.trimIndent()
        )
    )
}

private fun verify(gom: GraphObjectManager, pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry) {
    seed(pm)

    // ----- Polymorphic recursive view: each node its concrete subtype -----
    val roots = gom.loadAll(TypedContentTreeView::class.java, "element.id = 'd'")

    // Auto-registration must fire for the view's *root* fragment (not just relationship targets), so
    // registry-based dispatch works without an explicit registerSubtype — the reported gap.
    assertEquals(
        ChunkNode::class.java,
        registry.resolveByLabels(ContentElementNode::class.java, listOf("ContentElement", "Chunk")),
        "auto-registration should populate the sealed root fragment's subtypes",
    )
    assertEquals(1, roots.size)
    val root = roots.single()
    assertTrue(root.element is DocumentNode, "root is a Document, got ${root.element::class.simpleName}")
    assertEquals("Doc", (root.element as DocumentNode).title)

    val section = root.children.single()
    assertTrue(section.element is LeafSectionNode, "depth-1 is a LeafSection, got ${section.element::class.simpleName}")

    val chunk = section.children.single()
    assertTrue(chunk.element is ChunkNode, "depth-2 is a Chunk, got ${chunk.element::class.simpleName}")
    // subtype-only @GraphProperty reconstructs through polymorphic dispatch
    assertEquals("sec-1", (chunk.element as ChunkNode).containerSectionId)
    assertEquals("c", chunk.element.id)
    assertTrue(chunk.children.isEmpty(), "leaf has no children")

    // ----- Concrete recursive view over the same nodes still loads structure (regression) -----
    val concrete = gom.loadAll(ContentTreeView::class.java, "element.id = 'd'").single()
    assertEquals("d", concrete.element.id)
    assertEquals("s", concrete.children.single().element.id)
    assertEquals("c", concrete.children.single().children.single().element.id)

    // ----- Non-recursive polymorphic relationship target dispatches -----
    val folder = gom.loadAll(FolderWithHead::class.java, "folder.id = 'f'").single()
    assertTrue(folder.head is ChunkNode, "relationship-target head is a Chunk, got ${folder.head::class.simpleName}")
    assertEquals("Head", (folder.head as ChunkNode).text)
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class PolymorphicRecursiveViewNeo4jTest {
    companion object {
        private const val PW = "polytreetest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PW) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-poly", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PW, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `polymorphic recursive view on Neo4j`() = verify(buildGom(pm, registry), pm, registry)
}

@Testcontainers
class PolymorphicRecursiveViewFalkorDbTest {
    companion object {
        private const val GRAPH = "polytreetest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-poly", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `polymorphic recursive view on FalkorDB`() = verify(buildGom(pm, registry), pm, registry)
}

@Testcontainers
class PolymorphicRecursiveViewMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-poly", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test fun `polymorphic recursive view on Memgraph`() = verify(buildGom(pm, registry), pm, registry)
}
