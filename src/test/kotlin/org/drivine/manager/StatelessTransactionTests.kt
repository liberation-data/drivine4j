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
}
