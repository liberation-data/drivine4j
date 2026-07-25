package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.transform
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
import sample.graphproperty.ChunkNode
import sample.graphproperty.ChunkWithSection
import sample.graphproperty.Element
import sample.graphproperty.ParagraphElement
import sample.graphproperty.SectionNode
import sample.graphproperty.WidgetNode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `@GraphProperty` verified on Neo4j, FalkorDB, and Memgraph: a field maps to a differently-named
 * on-disk property, round-trips into the correct field, is stored under the on-disk name (and *not*
 * the field name), an overridden `@NodeId` keys save + load, and a subtype's overridden field loads
 * correctly through the polymorphic `.*` path.
 */
private fun verify(gom: GraphObjectManager, pm: NonTransactionalPersistenceManager) {
    // ----- Concrete round-trip: on-disk name stored, field name absent -----
    gom.save(ChunkNode(id = "c1", text = "hello", containerSectionId = "sec-1", sequenceNumber = 7, rootDocumentId = "doc-1"))

    val keys = nodeKeys(pm, "MATCH (n:Chunk {id: 'c1'}) UNWIND keys(n) AS k RETURN k")
    assertTrue("container_section_id" in keys, "on-disk name must be stored: $keys")
    assertTrue("sequence_number" in keys && "root_document_id" in keys, keys.toString())
    assertFalse("containerSectionId" in keys, "field name must NOT be a stored property: $keys")
    assertFalse("sequenceNumber" in keys, keys.toString())

    val loaded = gom.load("c1", ChunkNode::class.java)!!
    assertEquals("hello", loaded.text)
    assertEquals("sec-1", loaded.containerSectionId)
    assertEquals(7L, loaded.sequenceNumber)
    assertEquals("doc-1", loaded.rootDocumentId)

    // Filtering by the on-disk property finds it (proves reads/writes agree on the name)
    val byRaw = pm.getOne(
        QuerySpecification
            .withStatement("MATCH (n:Chunk {container_section_id: 'sec-1'}) RETURN count(n)")
            .transform(Long::class.java)
    )
    assertEquals(1L, byRaw)

    // ----- @NodeId override: MERGE key + load both target the on-disk name -----
    gom.save(WidgetNode(key = "w1", name = "Widget One"))
    val widgetKeys = nodeKeys(pm, "MATCH (n:Widget {widget_key: 'w1'}) UNWIND keys(n) AS k RETURN k")
    assertTrue("widget_key" in widgetKeys, widgetKeys.toString())
    assertFalse("key" in widgetKeys, "the id field name must not be stored: $widgetKeys")
    val widget = gom.load("w1", WidgetNode::class.java)!!
    assertEquals("Widget One", widget.name)
    assertEquals("w1", widget.key)

    // ----- Polymorphic .* path: a subtype's overridden field loads correctly -----
    pm.registerSubtype(Element::class.java, listOf("Element", "ParagraphElement"), ParagraphElement::class.java)
    gom.save(ParagraphElement(id = "p1", body = "para", leafSectionId = "leaf-9"))

    val paraKeys = nodeKeys(pm, "MATCH (n:ParagraphElement {id: 'p1'}) UNWIND keys(n) AS k RETURN k")
    assertTrue("leaf_section_id" in paraKeys, paraKeys.toString())
    assertFalse("leafSectionId" in paraKeys, paraKeys.toString())

    val elements = gom.loadAll(Element::class.java)
    val para = elements.filterIsInstance<ParagraphElement>().single { it.id == "p1" }
    assertEquals("para", para.body)
    assertEquals("leaf-9", para.leafSectionId, "overridden field must reconstruct through the .* path")

    // ----- GraphView: overridden fields on both the root and a relationship-target fragment -----
    gom.save(
        ChunkWithSection(
            chunk = ChunkNode(id = "cv1", text = "in a section", containerSectionId = "sec-42"),
            section = SectionNode(id = "s1", displayTitle = "Chapter One"),
        )
    )
    val sectionKeys = nodeKeys(pm, "MATCH (n:Section {id: 's1'}) UNWIND keys(n) AS k RETURN k")
    assertTrue("display_title" in sectionKeys, sectionKeys.toString())
    assertFalse("displayTitle" in sectionKeys, sectionKeys.toString())

    val view = gom.load("cv1", ChunkWithSection::class.java)!!
    assertEquals("sec-42", view.chunk.containerSectionId, "root fragment override round-trips in a view")
    assertEquals("Chapter One", view.section.displayTitle, "relationship-target override round-trips in a view")
}

private fun nodeKeys(pm: NonTransactionalPersistenceManager, cypher: String): Set<String> =
    pm.query(QuerySpecification.withStatement(cypher).transform(String::class.java)).toSet()

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class GraphPropertyNeo4jTest {
    companion object {
        private const val PASSWORD = "graphproptest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager
        lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-gp", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `graph property mapping round-trips on Neo4j`() = verify(buildGom(pm, registry), pm)
}

@Testcontainers
class GraphPropertyFalkorDbTest {
    companion object {
        private const val GRAPH = "graphproptest"

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
                name = "falkor-gp", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `graph property mapping round-trips on FalkorDB`() = verify(buildGom(pm, registry), pm)
}

@Testcontainers
class GraphPropertyMemgraphTest {
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
                name = "memgraph-gp", type = DatabaseType.MEMGRAPH,
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
    fun clean() = pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    @Test
    fun `graph property mapping round-trips on Memgraph`() = verify(buildGom(pm, registry), pm)
}
