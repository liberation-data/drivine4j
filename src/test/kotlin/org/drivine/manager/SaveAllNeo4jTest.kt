package org.drivine.manager

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
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
import sample.proposition.Mention
import sample.proposition.PropositionNode
import sample.proposition.PropositionView
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Acceptance tests for [GraphObjectManager.saveAll] against a real Neo4j. Covers the documented
 * contract: per-item cascade & MERGE identity preserved, input order returned, all-or-nothing
 * atomicity (a mid-batch failure rolls the whole call back), equivalence with sequential [save],
 * empty no-op, and the sub-linear round-trip count of the homogeneous-fragment UNWIND path.
 */
@Testcontainers
class SaveAllNeo4jTest {
    companion object {
        private const val PASSWORD = "saveAllTest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var pm: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-saveall", type = DatabaseType.NEO4J,
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

    private fun gom(pmOverride: PersistenceManager = pm): GraphObjectManager {
        val mapper = Neo4jObjectMapper.instance
        return GraphObjectManager(pmOverride, SessionManager(mapper), mapper, SubtypeRegistry())
    }

    private fun proposition(id: String, status: String = "active") =
        PropositionNode(id = id, contextId = "ctx", status = status, level = 1)

    private fun view(id: String, mentionIds: List<String>, status: String = "active") =
        PropositionView(
            proposition = proposition(id, status),
            mentions = mentionIds.map { Mention(id = it, resolvedId = null, role = "subject") },
        )

    /** id -> sorted mention ids, for whole-graph equivalence comparison. */
    private fun fingerprint(gom: GraphObjectManager): Map<String, List<String>> =
        gom.loadAll(PropositionView::class.java)
            .associate { it.proposition.id to it.mentions.map(Mention::id).sorted() }

    // ---- (1) N nodes persist; returned list matches input order ----------------------------------
    @Test
    fun `saveAll persists every node and returns them in input order`() {
        val gom = gom()
        val input = (1..5).map { proposition("p$it") }
        val returned = gom.saveAll(input)

        assertEquals(input.map { it.id }, returned.map { it.id }, "input order is preserved")
        assertEquals(5L, gom.count(PropositionNode::class.java))
    }

    // ---- (2) relationships persist with cascade: DELETE_ORPHAN reconciles, re-save is idempotent --
    @Test
    fun `saveAll persists relationships with per-item cascade and is idempotent`() {
        val gom = gom()
        // Initial: p1 -> [m1, m2]
        gom.saveAll(listOf(view("p1", listOf("m1", "m2"))), CascadeType.DELETE_ORPHAN)
        assertEquals(listOf("m1", "m2"), gom.load("p1", PropositionView::class.java)!!.mentions.map(Mention::id).sorted())
        assertEquals(2L, gom.count(Mention::class.java))

        // Re-saveAll the SAME set: idempotent, no duplicate mentions.
        gom.saveAll(listOf(view("p1", listOf("m1", "m2"))), CascadeType.DELETE_ORPHAN)
        assertEquals(2L, gom.count(Mention::class.java), "re-saving the same set must not duplicate")

        // Drop m2 with DELETE_ORPHAN: the now-orphaned mention is reconciled away.
        gom.saveAll(listOf(view("p1", listOf("m1"))), CascadeType.DELETE_ORPHAN)
        assertEquals(listOf("m1"), gom.load("p1", PropositionView::class.java)!!.mentions.map(Mention::id))
        assertEquals(1L, gom.count(Mention::class.java), "orphaned m2 deleted")
    }

    // ---- (3) mid-batch failure rolls the whole call back -----------------------------------------
    @Test
    fun `saveAll is atomic - a mid-batch failure persists nothing`() {
        val gom = gom()
        // A good homogeneous group runs first, then a node with an unstorable (nested-map) property
        // forces a Cypher error on its UNWIND. The whole batch must roll back.
        val batch = listOf<Any>(
            proposition("ok1"),
            proposition("ok2"),
            UnstorableNode("bad", mapOf("nested" to mapOf("x" to 1))),
        )

        assertFails { gom.saveAll(batch) }

        assertEquals(0L, gom.count(PropositionNode::class.java), "earlier good writes rolled back with the failure")
        assertEquals(0L, pm.getOne(
            QuerySpecification.withStatement("MATCH (n:BatchFail) RETURN count(n) AS c").transform(Long::class.java)
        ))
    }

    // ---- (4) equivalence: saveAll(listOf(a, b)) == save(a); save(b) ------------------------------
    @Test
    fun `saveAll yields the same graph as sequential save`() {
        val a = view("p1", listOf("m1", "m2"))
        val b = view("p2", listOf("m3"))

        val batched = gom()
        batched.saveAll(listOf(a, b), CascadeType.DELETE_ORPHAN)
        val batchedFingerprint = fingerprint(batched)

        clean()

        val sequential = gom()
        sequential.save(a, CascadeType.DELETE_ORPHAN)
        sequential.save(b, CascadeType.DELETE_ORPHAN)
        val sequentialFingerprint = fingerprint(sequential)

        assertEquals(sequentialFingerprint, batchedFingerprint)
        assertEquals(mapOf("p1" to listOf("m1", "m2"), "p2" to listOf("m3")), batchedFingerprint)
    }

    // ---- (5) empty collection is a no-op ---------------------------------------------------------
    @Test
    fun `saveAll of an empty collection is a no-op returning an empty list`() {
        val counting = CountingPersistenceManager(pm)
        val gom = gom(counting)
        assertTrue(gom.saveAll(emptyList<PropositionNode>()).isEmpty())
        assertEquals(0, counting.batchCalls, "no statements issued for an empty batch")
    }

    // ---- (6) homogeneous fragment batch uses a sub-linear number of statements -------------------
    @Test
    fun `saveAll of a homogeneous fragment batch is sub-linear in round trips`() {
        val counting = CountingPersistenceManager(pm)
        val gom = gom(counting)
        val n = 50
        gom.saveAll((1..n).map { proposition("p$it") })

        assertEquals(n.toLong(), gom.count(PropositionNode::class.java))
        assertEquals(1, counting.batchCalls, "one atomic batch")
        assertTrue(counting.batchSpecCount < n, "fragment roots collapse into UNWIND: ${counting.batchSpecCount} statements for $n nodes")
        assertEquals(1, counting.batchSpecCount, "a single UNWIND chunk covers the whole homogeneous fragment batch")
    }
}

/** A fragment whose Map field is a regular (non-`@PropertyBag`) property — unstorable, used to force a failure. */
@NodeFragment(labels = ["BatchFail"])
data class UnstorableNode(@NodeId val id: String, val payload: Map<String, Any?>)

/** Decorates a [PersistenceManager], counting [executeBatch] calls and the statements they carry. */
private class CountingPersistenceManager(
    private val delegate: PersistenceManager,
) : PersistenceManager by delegate {
    var batchCalls = 0
    var batchSpecCount = 0
    override fun executeBatch(specs: List<QuerySpecification<*>>) {
        batchCalls++
        batchSpecCount += specs.size
        delegate.executeBatch(specs)
    }
}