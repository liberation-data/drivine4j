package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.VectorIndexSpec
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.graphproperty.EmbeddedNode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The flagged interaction: an overridden `@VectorIndex` property name and the `vecf32`-on-save write
 * path. The index must be created on the **on-disk** name, `save()` must write the embedding there
 * (wrapped in `vecf32(...)` on FalkorDB), and `loadNearest` must resolve the same on-disk name — the
 * whole loop keyed by `embedding_vec`, never the field name `embedding`.
 */
private fun verifyVectorOverride(gom: GraphObjectManager, pm: NonTransactionalPersistenceManager) {
    // Index is created on the on-disk property name (from @GraphProperty + @VectorIndex).
    val created = pm.indexes.ensure(VectorIndexSpec("Embedded", "embedding_vec", 4))
    val info = pm.indexes.find(VectorIndexSpec("Embedded", "embedding_vec", 4))!!
    assertEquals(SchemaItemKind.VECTOR_INDEX, info.kind)
    assertEquals(listOf("embedding_vec"), info.properties)

    // save() writes the embedding to embedding_vec (vecf32-wrapped on FalkorDB).
    gom.save(EmbeddedNode(id = "e1", embedding = listOf(1.0f, 0.0f, 0.0f, 0.0f)))
    gom.save(EmbeddedNode(id = "e2", embedding = listOf(0.0f, 1.0f, 0.0f, 0.0f)))

    // loadNearest resolves the on-disk name and finds the nodes (empty on FalkorDB if the write had
    // not gone through vecf32, or if the index/search used the field name).
    val hits = gom.loadNearest(EmbeddedNode::class.java, listOf(1.0f, 0.0f, 0.0f, 0.0f), topK = 10)
    assertEquals("e1", hits.first().value.id, "nearest must be e1; hits=${hits.map { it.value.id }}")
    assertNotNull(hits.first().value.embedding)
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class GraphPropertyVectorFalkorDbTest {
    companion object {
        private const val GRAPH = "gpvectortest"

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
                name = "falkor-gpvec", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `overridden vector property round-trips through vecf32 on FalkorDB`() = verifyVectorOverride(buildGom(pm, registry), pm)
}

@Testcontainers
class GraphPropertyVectorNeo4jTest {
    companion object {
        private const val PASSWORD = "gpvectortest"

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
                name = "neo-gpvec", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `overridden vector property round-trips on Neo4j`() = verifyVectorOverride(buildGom(pm, registry), pm)
}
