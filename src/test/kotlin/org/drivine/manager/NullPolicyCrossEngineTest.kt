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
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.drivine.manager.NullPolicy
import sample.nullpolicy.VecSaveNode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Null-write policy verified against Neo4j / FalkorDB / Memgraph, one uniform contract for every field.
 *
 * Default **IGNORE** (merge-patch): a null field — embedding included — is left untouched, so a
 * partially-loaded object never destroys stored data. This is the safety guarantee, on both single
 * `save` and `saveAll` (UNWIND fast path on Neo4j/Memgraph, per-item fallback on FalkorDB).
 *
 * Explicit **CLEAR**: a full overwrite — a null field clears the property, embeddings included (a plain
 * `SET`/`+= {x: null}`, never `vecf32(null)`). Consistent across single and batch.
 */
private val VEC = listOf(1.0f, 0.0f, 0.0f, 0.0f)

/** (title, hasEmbedding) read straight from the stored node, engine-agnostic. */
private fun probe(pm: NonTransactionalPersistenceManager): Pair<String?, Boolean> {
    val rows = pm.query(
        QuerySpecification
            .withStatement("MATCH (n:VecSave {id: 'x'}) RETURN {title: n.title, hasEmbedding: n.embedding IS NOT NULL} AS r")
            .transform(Map::class.java)
    )
    @Suppress("UNCHECKED_CAST")
    val row = rows.first() as Map<String, Any?>
    return (row["title"] as String?) to (row["hasEmbedding"] as Boolean)
}

private fun verify(gom: GraphObjectManager, pm: NonTransactionalPersistenceManager) {
    pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))

    // Seed: a node with a computed embedding.
    gom.save(VecSaveNode("x", "A", VEC))
    assertEquals("A" to true, probe(pm))

    // Default (IGNORE) single save: a null embedding AND a null title are both left untouched.
    gom.save(VecSaveNode("x", title = null, embedding = null))
    probe(pm).let { (title, hasEmb) -> assertEquals("A", title, "default save cleared title!"); assertTrue(hasEmb, "default save cleared embedding!") }

    // Default (IGNORE) saveAll: same — no clear (UNWIND on Neo4j/Memgraph, per-item fallback on FalkorDB).
    gom.saveAll(listOf(VecSaveNode("x", title = null, embedding = null)))
    probe(pm).let { (title, hasEmb) -> assertEquals("A", title, "default saveAll cleared title!"); assertTrue(hasEmb, "default saveAll cleared embedding!") }

    // A default save with a NEW title updates it (non-null fields always written), embedding preserved.
    gom.save(VecSaveNode("x", "B", embedding = null))
    assertEquals("B" to true, probe(pm))

    // Explicit CLEAR single save: a null title clears; a null embedding clears too (honest overwrite).
    gom.save(VecSaveNode("x", title = null, embedding = null), nullPolicy = NullPolicy.CLEAR)
    probe(pm).let { (title, hasEmb) -> assertNull(title, "CLEAR should null the title"); assertTrue(!hasEmb, "CLEAR should clear the embedding") }

    // Restore, then explicit CLEAR via saveAll clears a null title (+= {title:null} on Neo4j/Memgraph,
    // per-item SET on FalkorDB) — consistent with single save. Embedding provided, so it persists.
    gom.save(VecSaveNode("x", "C", VEC))
    gom.saveAll(listOf(VecSaveNode("x", title = null, embedding = VEC)), nullPolicy = NullPolicy.CLEAR)
    probe(pm).let { (title, hasEmb) -> assertNull(title, "saveAll CLEAR should null the title"); assertTrue(hasEmb) }
}

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry): GraphObjectManager {
    val mapper = Neo4jObjectMapper.instance
    return GraphObjectManager(pm, SessionManager(mapper), mapper, registry)
}

@Testcontainers
class NullPolicyNeo4jTest {
    companion object {
        private const val PW = "nullpolicytest"

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
                name = "neo-nullpolicy", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PW, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `null-write policy on Neo4j`() = verify(buildGom(pm, registry), pm)
}

@Testcontainers
class NullPolicyFalkorDbTest {
    companion object {
        private const val GRAPH = "nullpolicytest"

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
                name = "falkor-nullpolicy", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `null-write policy on FalkorDB`() = verify(buildGom(pm, registry), pm)
}

@Testcontainers
class NullPolicyMemgraphTest {
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
                name = "memgraph-nullpolicy", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `null-write policy on Memgraph`() = verify(buildGom(pm, registry), pm)
}
