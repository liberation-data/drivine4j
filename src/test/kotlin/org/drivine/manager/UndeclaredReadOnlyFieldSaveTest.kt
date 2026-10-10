package org.drivine.manager

import org.drivine.annotation.Count
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.Root
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.simple.TestAppContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@NodeFragment(labels = ["Voyage"])
data class Voyage(@NodeId val id: String, val name: String, @NodeStamp val stamp: String? = null)

@NodeFragment(labels = ["Harbour"])
data class Harbour(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["Realm"])
data class Realm(@NodeId val id: String, val name: String)

/** A path field and a count field, neither declared `@ReadOnly`, beside a relationship that is written. */
@GraphView
data class VoyageRealms(
    @Root val voyage: Voyage,
    @GraphRelationship(type = "CALLS_AT", direction = Direction.OUTGOING)
    val harbours: List<Harbour> = emptyList(),
    @GraphPath([
        Hop("CALLS_AT", Direction.OUTGOING, label = "Harbour"),
        Hop("LIES_IN", Direction.OUTGOING),
    ])
    val realms: List<Realm> = emptyList(),
    @Count("CALLS_AT") val harbourCount: Long = 0,
)

/**
 * A view whose `@GraphPath` and `@Count` fields carry no `@ReadOnly` loads, and a save writes
 * nothing for them.
 *
 * The graph before each test: `(voyage)-[:CALLS_AT]->(lisbon:Harbour)-[:LIES_IN]->(portugal:Realm)`.
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class UndeclaredReadOnlyFieldSaveTest @Autowired constructor(
    private val stateless: StatelessGraphObjectManager,
    private val persistenceManager: PersistenceManager,
) {
    private val run = UUID.randomUUID().toString()
    private val voyage = "$run-voyage"
    private val lisbon = "$run-lisbon"
    private val portugal = "$run-portugal"
    private val spain = "$run-spain"

    private fun ids(cypher: String): Set<String> = persistenceManager.query(
        QuerySpecification.withStatement(cypher).bind(mapOf("voyage" to voyage, "run" to run)).transform(String::class.java)
    ).toSet()

    /** The realms a relationship of any type joins the voyage to directly. */
    private fun realmsOfVoyage() = ids("MATCH (:Voyage {id: \$voyage})--(r:Realm) RETURN r.id")

    private fun realms() = ids("MATCH (r:Realm) WHERE r.id STARTS WITH \$run RETURN r.id")

    private fun realmsAlongThePath() =
        ids("MATCH (:Voyage {id: \$voyage})-[:CALLS_AT]->(:Harbour)-[:LIES_IN]->(r:Realm) RETURN r.id")

    @BeforeEach
    fun seed() {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (v:Voyage {id: ${'$'}voyage, name: 'South', `__drivine.stamp`: '0123456789abcdef:fedcba9876543210'})
                CREATE (h:Harbour {id: ${'$'}lisbon, name: 'Lisbon'})
                CREATE (r:Realm {id: ${'$'}portugal, name: 'Portugal'})
                CREATE (v)-[:CALLS_AT]->(h)
                CREATE (h)-[:LIES_IN]->(r)
                """.trimIndent()
            ).bind(mapOf("voyage" to voyage, "lisbon" to lisbon, "portugal" to portugal))
        )
    }

    @Test
    fun `a view with an undeclared path and count field loads`() {
        val view = assertNotNull(stateless.load<VoyageRealms>(voyage))

        assertEquals(listOf(Realm(portugal, "Portugal")), view.realms)
        assertEquals(1, view.harbourCount)
    }

    @Test
    fun `a save writes no relationship for an undeclared path field`() {
        val view = assertNotNull(stateless.load<VoyageRealms>(voyage))

        stateless.save(view.copy(realms = listOf(Realm(portugal, "Lusitania"), Realm(spain, "Spain")), harbourCount = 7))

        assertEquals(emptySet(), realmsOfVoyage(), "a path is read, never written")
        assertEquals(setOf(portugal), realms(), "the nodes the field holds are not saved, and none is created")
        assertEquals(setOf(portugal), realmsAlongThePath(), "nothing along the path changes")
        assertEquals(1, assertNotNull(stateless.load<VoyageRealms>(voyage)).harbourCount, "a count is computed, never stored")
    }

    @Test
    fun `replacing every field leaves an undeclared path field alone`() {
        val view = assertNotNull(stateless.load<VoyageRealms>(voyage))

        stateless.save(view.copy(realms = emptyList()), Replace.all())

        assertEquals(setOf(portugal), realmsAlongThePath())
        assertEquals(setOf(lisbon), ids("MATCH (:Voyage {id: \$voyage})-[:CALLS_AT]->(h:Harbour) RETURN h.id"))
    }
}
