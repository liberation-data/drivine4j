package org.drivine.manager

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.drivine.DrivineException
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import sample.simple.TestAppContext

/**
 * A batch inside a transaction joins it: its statements run in order on the transaction's
 * connection, see what the transaction wrote, and are kept or undone with it. A statement that fails
 * leaves the rollback to the caller.
 */
@SpringBootTest(classes = [TestAppContext::class])
class QueryBatchInTransactionTests @Autowired constructor(
    private val persistenceManager: PersistenceManager,
    transactionManager: PlatformTransactionManager,
) {

    private val tx = TransactionTemplate(transactionManager)
    private val run = "qb-" + UUID.randomUUID().toString().take(8)

    @AfterEach
    fun clean() {
        persistenceManager.execute(
            QuerySpecification.withStatement("MATCH (n:BatchProbe) WHERE n.id STARTS WITH \$run DETACH DELETE n").bind(mapOf("run" to run))
        )
    }

    private fun create(id: String) =
        QuerySpecification.withStatement("CREATE (n:BatchProbe {id: \$id}) RETURN n.id").bind(mapOf("id" to "$run-$id")).transform(String::class.java)

    private fun count() = QuerySpecification.withStatement("MATCH (n:BatchProbe) WHERE n.id STARTS WITH \$run RETURN count(n)")
        .bind(mapOf("run" to run)).transform(Long::class.java)

    private fun stored(): Long = persistenceManager.getOne(count())

    @Test
    fun `a batch in a transaction returns each statement's rows in order and sees what the transaction wrote`() {
        val rows = tx.execute {
            persistenceManager.execute(create("before"))
            persistenceManager.queryBatch(listOf(create("a"), count(), create("b"), count()))
        }

        assertEquals(listOf(listOf<Any?>("$run-a"), listOf<Any?>(2L), listOf<Any?>("$run-b"), listOf<Any?>(3L)), rows)
        assertEquals(3, stored())
    }

    @Test
    fun `a batch whose statement fails is undone with the transaction it joined`() {
        assertFailsWith<DrivineException> {
            tx.execute {
                persistenceManager.execute(create("before"))
                persistenceManager.queryBatch(listOf(create("a"), QuerySpecification.withStatement("THIS IS NOT CYPHER"), create("b")))
            }
        }

        assertEquals(0, stored(), "neither the batch's first statement nor what came before it is kept")
    }

    @Test
    fun `a batch is kept or undone with the transaction it joined`() {
        tx.execute { status ->
            persistenceManager.execute(create("before"))
            status.setRollbackOnly()
            persistenceManager.executeBatch(listOf(create("a")))
        }
        assertEquals(0, stored(), "rolled back by the caller, batch and all")

        tx.execute {
            persistenceManager.executeBatch(listOf(create("kept")))
        }
        assertEquals(1, stored())
    }
}
