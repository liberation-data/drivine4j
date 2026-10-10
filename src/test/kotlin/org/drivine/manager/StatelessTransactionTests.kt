package org.drivine.manager

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.drivine.StaleObjectException
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import sample.simple.TestAppContext
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Human

/** The stamp and the one-statement save inside a transaction, where the other tests of them run outside one. */
@SpringBootTest(classes = [TestAppContext::class])
class StatelessTransactionTests @Autowired constructor(
    private val stateless: StatelessGraphObjectManager,
    private val persistenceManager: PersistenceManager,
    transactionManager: PlatformTransactionManager,
) {

    private val tx = TransactionTemplate(transactionManager)
    private val run = "tx-" + UUID.randomUUID().toString().take(8)

    @AfterEach
    fun clean() {
        persistenceManager.execute(
            QuerySpecification.withStatement("MATCH (n) WHERE n.id STARTS WITH \$run DETACH DELETE n").bind(mapOf("run" to run))
        )
    }

    private fun mentioned(claim: String): List<String> = persistenceManager.query(
        QuerySpecification.withStatement("MATCH (:Claim {id: \$id})-[:MENTIONS]->(h:Human) RETURN h.id")
            .bind(mapOf("id" to claim)).transform(String::class.java)
    ).sorted()

    @Test
    fun `a save refused as stale rolls back what its transaction wrote before it`() {
        val loaded = stateless.save(Claim("$run-c1", "one"))
        stateless.save(loaded.copy(text = "two"))

        assertFailsWith<StaleObjectException> {
            tx.execute {
                stateless.save(Human("$run-ada", "Ada"))
                stateless.save(loaded.copy(text = "late"))
            }
        }

        assertNull(stateless.load<Human>("$run-ada"))
        assertEquals("two", stateless.load<Claim>("$run-c1")?.text)
    }

    @Test
    fun `a view save in a transaction that rolls back leaves the graph as it was`() {
        val saved = stateless.save(ClaimView(Claim("$run-c1", "one"), people = listOf(Human("$run-ada", "Ada"))))

        assertFailsWith<IllegalStateException> {
            tx.execute {
                stateless.save(saved.copy(people = listOf(Human("$run-bob", "Bob"))), Replace(ClaimView::people))
                error("rolled back")
            }
        }

        assertEquals(listOf("$run-ada"), mentioned("$run-c1"))
        assertNull(stateless.load<Human>("$run-bob"))
        assertEquals(saved.claim.stamp, stateless.load<Claim>("$run-c1")?.stamp)
    }

    @Test
    fun `of several transactions saving the same loaded object, exactly one commits`() {
        val writers = 6
        repeat(5) { round ->
            val id = "$run-race-$round"
            val loaded = stateless.save(Claim(id, "start"))
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(writers)
            try {
                val outcomes = (1..writers).map { writer ->
                    pool.submit<String?> {
                        start.await()
                        try {
                            tx.execute { stateless.save(loaded.copy(text = "writer $writer")) }?.stamp
                        } catch (stale: StaleObjectException) {
                            null
                        }
                    }
                }
                start.countDown()
                val stamps = outcomes.map { it.get(60, TimeUnit.SECONDS) }.filterNotNull()

                assertEquals(1, stamps.size, "round $round")
                assertEquals(stamps.single(), assertNotNull(stateless.load<Claim>(id)).stamp)
            } finally {
                pool.shutdown()
            }
        }
    }

    // ----- saveAll in a transaction -----

    @Test
    fun `saveAll joins its caller's transaction, and what it wrote goes when the transaction rolls back`() {
        assertFailsWith<IllegalStateException> {
            tx.execute {
                stateless.saveAll(
                    listOf(
                        Claim("$run-c1", "one"),
                        Human("$run-ada", "Ada"),
                        ClaimView(Claim("$run-c2", "two"), people = listOf(Human("$run-bob", "Bob"))),
                    )
                )
                assertNotNull(stateless.load<Claim>("$run-c1"), "the transaction sees what it saved")
                assertEquals(listOf("$run-bob"), mentioned("$run-c2"))
                error("rolled back")
            }
        }

        assertNull(stateless.load<Claim>("$run-c1"))
        assertNull(stateless.load<Claim>("$run-c2"))
        assertNull(stateless.load<Human>("$run-ada"))
        assertNull(stateless.load<Human>("$run-bob"))
    }

    @Test
    fun `saveAll in a transaction that commits hands back the stamps the store holds`() {
        val saved = assertNotNull(
            tx.execute {
                stateless.save(Human("$run-ada", "Ada"))
                stateless.saveAll(listOf(Claim("$run-c1", "one"), Claim("$run-c2", "two")))
            }
        )

        assertEquals(listOf("$run-c1", "$run-c2"), saved.map { it.id })
        saved.forEach { assertEquals(assertNotNull(it.stamp), stateless.load<Claim>(it.id)?.stamp) }
        assertNotNull(stateless.load<Human>("$run-ada"))
    }

    @Test
    fun `a batch refused as stale rolls back what its transaction wrote before it`() {
        val loaded = stateless.save(ClaimView(Claim("$run-c1", "one"), people = listOf(Human("$run-ada", "Ada"))))
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (c:Claim {id: \$id}) CREATE (c)-[:MENTIONS]->(:Human {id: \$bob, name: 'Bob'}) SET ${Stamps.linksClause("c")}"
            ).bind(mapOf("id" to "$run-c1", "bob" to "$run-bob"))
        )

        val failure = assertFailsWith<StaleObjectException> {
            tx.execute {
                stateless.save(Human("$run-cy", "Cy"))
                stateless.saveAll(
                    listOf(ClaimView(Claim("$run-c0", "zero"), people = listOf(Human("$run-dan", "Dan"))), loaded.copy(people = emptyList())),
                    Replace(ClaimView::people),
                )
            }
        }

        assertEquals("$run-c1", failure.id)
        assertNull(stateless.load<Human>("$run-cy"), "what the transaction saved before the batch")
        assertNull(stateless.load<Claim>("$run-c0"), "what the batch saved before the refused view")
        assertNull(stateless.load<Human>("$run-dan"))
        assertEquals(listOf("$run-ada", "$run-bob"), mentioned("$run-c1"), "no relationship the view never loaded was removed")
    }
}
