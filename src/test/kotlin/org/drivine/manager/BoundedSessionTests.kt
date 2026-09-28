package org.drivine.manager

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import sample.mapped.fragment.Person
import sample.proposition.Mention
import sample.proposition.PropositionNode
import sample.proposition.PropositionView
import sample.propertybag.BaggedNode
import sample.simple.TestAppContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The session outlives transactions but is bounded: it holds a compact digest of each loaded object,
 * caps how many it tracks, is safe for concurrent use, and never makes a save's correctness depend on
 * whether the object is still tracked.
 */
@SpringBootTest(classes = [TestAppContext::class])
class BoundedSessionTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager,
    transactionManager: PlatformTransactionManager,
) {

    private val tx = TransactionTemplate(transactionManager)
    private val mapper = Neo4jObjectMapper.instance
    private val run = UUID.randomUUID().toString().take(8)

    @AfterEach
    fun cleanUp() {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = \$run OR n.id STARTS WITH \$run DETACH DELETE n")
                .bind(mapOf("run" to run))
        )
    }

    @Test
    fun `a tracked object costs bytes per field, not a copy of its text and embedding`() {
        val id = "$run-big"
        val bigText = "x".repeat(100_000)
        graphObjectManager.save(PropositionNode(id, bigText, "active", 1, embedding = List(1024) { it / 1024f }))

        assertNotNull(graphObjectManager.load(id, PropositionNode::class.java))

        val retained = assertNotNull(graphObjectManager.sessionManager.snapshotOf(PropositionNode::class.java, id))
        val bytes = mapper.writeValueAsBytes(retained).size
        assertTrue(bytes < 512, "retained $bytes bytes for one object; its text alone is ${bigText.length}")
    }

    @Test
    fun `a load in one transaction and a save in another writes only the changed field`() {
        val id = createPerson("Erich Gamma", "Gang of Four author")
        val loaded = assertNotNull(tx.execute { graphObjectManager.load(id.toString(), Person::class.java) })

        // Changed underneath the loaded object: a full write would put the stale bio back.
        setBio(id, "changed elsewhere")
        tx.executeWithoutResult { graphObjectManager.save(loaded.copy(name = "Erich Gamma (Updated)")) }

        val reloaded = assertNotNull(graphObjectManager.load(id.toString(), Person::class.java))
        assertEquals("Erich Gamma (Updated)", reloaded.name)
        assertEquals("changed elsewhere", reloaded.bio)
    }

    @Test
    fun `the session never exceeds its cap, and an evicted object saves in full`() {
        val gom = boundedGom(maxEntries = 3)
        val ids = (1..5).map { createPerson("Person $it", "bio $it") }
        val loaded = ids.map { assertNotNull(gom.load(it.toString(), Person::class.java)) }

        assertEquals(3, gom.sessionManager.size)
        assertFalse(gom.sessionManager.isTracked(Person::class.java, ids.first()), "least recently loaded was not evicted")

        // Evicted, so a full write: the stale-in-memory bio IS written back, exactly as for a detached object.
        setBio(ids.first(), "changed elsewhere")
        gom.save(loaded.first().copy(name = "Renamed"))
        val reloaded = assertNotNull(gom.load(ids.first().toString(), Person::class.java))
        assertEquals("Renamed", reloaded.name)
        assertEquals("bio 1", reloaded.bio)
        assertTrue(gom.sessionManager.size <= 3)
    }

    @Test
    fun `DELETE_ORPHAN removes a dropped target when the view has been evicted`() {
        val gom = boundedGom(maxEntries = 1)
        gom.save(view("$run-p1", listOf("$run-m1", "$run-m2")), CascadeType.DELETE_ORPHAN)
        val loaded = assertNotNull(gom.load("$run-p1", PropositionView::class.java))
        gom.save(view("$run-p2", listOf("$run-m3")))
        assertFalse(gom.sessionManager.isTracked(PropositionView::class.java, "$run-p1"))

        gom.save(loaded.copy(mentions = loaded.mentions.filter { it.id == "$run-m1" }), CascadeType.DELETE_ORPHAN)

        assertEquals(listOf("$run-m1"), mentionIds("$run-p1"))
        assertFalse(exists("$run-m2"), "orphaned mention should be deleted")
    }

    @Test
    fun `DELETE_ORPHAN removes a target added after the view was loaded`() {
        graphObjectManager.save(view("$run-p1", listOf("$run-m1")), CascadeType.DELETE_ORPHAN)
        val loaded = assertNotNull(graphObjectManager.load("$run-p1", PropositionView::class.java))

        // Another writer attaches m2; the session's snapshot of p1 knows nothing about it.
        boundedGom(maxEntries = 10).save(view("$run-p1", listOf("$run-m1", "$run-m2")))

        graphObjectManager.save(loaded, CascadeType.DELETE_ORPHAN)

        assertEquals(listOf("$run-m1"), mentionIds("$run-p1"))
        assertFalse(exists("$run-m2"), "orphaned mention should be deleted")
    }

    @Test
    fun `CLEAR removes a dropped property-bag key when the object is untracked`() {
        val id = "$run-bag"
        graphObjectManager.save(BaggedNode(id, "Title", mapOf("source" to "wiki", "score" to 3)))
        graphObjectManager.clearSession()

        graphObjectManager.save(BaggedNode(id, "Title", mapOf("source" to "blog")), nullPolicy = NullPolicy.CLEAR)

        val reloaded = assertNotNull(graphObjectManager.load(id, BaggedNode::class.java))
        assertEquals(mapOf<String, Any?>("source" to "blog"), reloaded.metadata)
    }

    private fun boundedGom(maxEntries: Int) =
        GraphObjectManager(persistenceManager, SessionManager(mapper, maxEntries), mapper, SubtypeRegistry())

    private fun view(id: String, mentionIds: List<String>) = PropositionView(
        proposition = PropositionNode(id = id, contextId = "ctx", status = "active", level = 1),
        mentions = mentionIds.map { Mention(id = it, resolvedId = null, role = "subject") },
    )

    private fun mentionIds(propositionId: String): List<String> = persistenceManager.query(
        QuerySpecification
            .withStatement("MATCH (:Proposition {id: \$id})-[:HAS_MENTION]->(m:Mention) RETURN m.id ORDER BY m.id")
            .bind(mapOf("id" to propositionId))
            .transform(String::class.java)
    )

    private fun exists(id: String): Boolean = persistenceManager.getOne(
        QuerySpecification
            .withStatement("MATCH (n {id: \$id}) RETURN count(n) > 0")
            .bind(mapOf("id" to id))
            .transform(Boolean::class.java)
    )

    private fun setBio(id: UUID, bio: String) = persistenceManager.execute(
        QuerySpecification
            .withStatement("MATCH (p:Person {uuid: \$uuid}) SET p.bio = \$bio")
            .bind(mapOf("uuid" to id.toString(), "bio" to bio))
    )

    private fun createPerson(name: String, bio: String): UUID {
        val id = UUID.randomUUID()
        persistenceManager.execute(
            QuerySpecification
                .withStatement("CREATE (:Person:Mapped {uuid: \$uuid, name: \$name, bio: \$bio, createdBy: \$run})")
                .bind(mapOf("uuid" to id.toString(), "name" to name, "bio" to bio, "run" to run))
        )
        return id
    }
}
