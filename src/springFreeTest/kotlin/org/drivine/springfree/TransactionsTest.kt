package org.drivine.springfree

import org.drivine.Drivine
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.manager.load
import org.drivine.query.QuerySpecification
import org.drivine.transaction.IllegalTransactionStateException
import org.drivine.transaction.Propagation
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.utility.DockerImageName
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@NodeFragment(labels = ["SpringFreePerson"])
data class SpringFreePerson(@NodeId val id: String, val name: String)

/** Drivine's own transactions on Neo4j, made as an application with no framework makes them: with [Drivine]. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionsTest {

    private val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
        .withAdminPassword(PASSWORD)
        .withStartupTimeout(Duration.ofMinutes(5))
        .withStartupAttempts(2)
        .also { it.start() }

    private val drivine = Drivine(
        DataSourceMap(
            mapOf(
                "neo" to ConnectionProperties(
                    type = DatabaseType.NEO4J,
                    host = container.host,
                    port = container.getMappedPort(7687),
                    userName = "neo4j",
                    password = PASSWORD,
                    databaseName = "neo4j",
                )
            )
        )
    )

    private val pm: PersistenceManager = drivine.persistenceManager("neo")
    private val run = "run-" + UUID.randomUUID().toString().take(8)

    private fun create(name: String) = pm.execute(
        QuerySpecification.withStatement("CREATE (:Marker {run: \$run, name: \$name})").bind(mapOf("run" to run, "name" to name))
    )

    /** What the database holds, read outside any transaction, so only what was committed. */
    private fun committed(): List<String> = drivine.transaction(Propagation.NOT_SUPPORTED) {
        pm.query(
            QuerySpecification.withStatement("MATCH (m:Marker {run: \$run}) RETURN m.name ORDER BY m.name")
                .bind(mapOf("run" to run)).transform(String::class.java)
        )
    }

    @AfterEach
    fun clean() {
        assertNull(drivine.transactions.current, "a test left a transaction on the thread")
        pm.execute(QuerySpecification.withStatement("MATCH (m:Marker {run: \$run}) DELETE m").bind(mapOf("run" to run)))
    }

    @AfterAll
    fun close() {
        drivine.close()
        container.stop()
    }

    @Test
    fun `a block's writes commit when it returns, and it returns what the block does`() {
        val result = drivine.transaction {
            create("a")
            create("b")
            assertNotNull(drivine.transactions.current)
            "done"
        }

        assertEquals("done", result)
        assertEquals(listOf("a", "b"), committed())
    }

    @Test
    fun `a throw out of the block rolls its writes back, and is thrown on`() {
        val thrown = IllegalStateException("no")

        val caught = assertFailsWith<IllegalStateException> {
            drivine.transaction {
                create("a")
                throw thrown
            }
        }

        assertSame(thrown, caught)
        assertEquals(emptyList(), committed())
    }

    @Test
    fun `a checked exception from a Java block rolls back too`() {
        val block = Callable<Unit> {
            create("a")
            throw IOException("no")
        }

        assertFailsWith<IOException> { drivine.transaction(block = block) }
        assertEquals(emptyList(), committed())
    }

    @Test
    fun `REQUIRED joins the transaction in progress, which commits or rolls back as one`() {
        drivine.transaction {
            create("outer")
            drivine.transaction(Propagation.REQUIRED) { create("inner") }
            assertEquals(emptyList(), committed(), "the inner block's write waits for the outer's end")
        }
        assertEquals(listOf("inner", "outer"), committed())

        clean()
        assertFailsWith<IllegalStateException> {
            drivine.transaction {
                drivine.transaction { create("inner") }
                error("the outer block fails")
            }
        }
        assertEquals(emptyList(), committed())
    }

    @Test
    fun `a joined block that throws leaves the end to the block that began the transaction`() {
        drivine.transaction {
            create("outer")
            assertFailsWith<IllegalStateException> {
                drivine.transaction {
                    create("inner")
                    error("caught by the outer block")
                }
            }
            assertTrue(drivine.transactions.current!!.rollbackOnly)
        }

        // As Spring's @Transactional through DrivineTransactionManager has always done: a failure
        // the caller catches does not undo the transaction.
        assertEquals(listOf("inner", "outer"), committed())
    }

    @Test
    fun `REQUIRES_NEW commits by itself, and the transaction it set aside carries on`() {
        assertFailsWith<IllegalStateException> {
            drivine.transaction {
                val outer = drivine.transactions.current
                create("outer")
                drivine.transaction(Propagation.REQUIRES_NEW) {
                    assertTrue(drivine.transactions.current !== outer)
                    create("new")
                }
                assertEquals(listOf("new"), committed())
                assertSame(outer, drivine.transactions.current)
                error("the outer block fails")
            }
        }

        assertEquals(listOf("new"), committed())
    }

    @Test
    fun `SUPPORTS joins a transaction, and without one each statement commits as it runs`() {
        assertFailsWith<IllegalStateException> {
            drivine.transaction(Propagation.SUPPORTS) {
                assertNull(drivine.transactions.current)
                create("alone")
                error("nothing to roll back")
            }
        }
        assertEquals(listOf("alone"), committed())

        clean()
        assertFailsWith<IllegalStateException> {
            drivine.transaction {
                drivine.transaction(Propagation.SUPPORTS) { create("joined") }
                error("the outer block fails")
            }
        }
        assertEquals(emptyList(), committed())
    }

    @Test
    fun `MANDATORY joins a transaction, and refuses to run without one`() {
        var ran = false
        assertFailsWith<IllegalTransactionStateException> {
            drivine.transaction(Propagation.MANDATORY) { ran = true }
        }
        assertEquals(false, ran)

        drivine.transaction {
            val outer = drivine.transactions.current
            drivine.transaction(Propagation.MANDATORY) { assertSame(outer, drivine.transactions.current) }
        }
    }

    @Test
    fun `NEVER runs without a transaction, and refuses to run in one`() {
        drivine.transaction(Propagation.NEVER) { assertNull(drivine.transactions.current) }

        drivine.transaction {
            var ran = false
            assertFailsWith<IllegalTransactionStateException> {
                drivine.transaction(Propagation.NEVER) { ran = true }
            }
            assertEquals(false, ran)
        }
    }

    @Test
    fun `NOT_SUPPORTED sets the transaction aside, and its writes commit as they run`() {
        assertFailsWith<IllegalStateException> {
            drivine.transaction {
                val outer = drivine.transactions.current
                drivine.transaction(Propagation.NOT_SUPPORTED) {
                    assertNull(drivine.transactions.current)
                    create("aside")
                }
                assertSame(outer, drivine.transactions.current)
                create("outer")
                error("the outer block fails")
            }
        }

        assertEquals(listOf("aside"), committed())
    }

    @Test
    fun `NESTED is refused`() {
        assertFailsWith<IllegalTransactionStateException> { drivine.transaction(Propagation.NESTED) { create("a") } }
        assertEquals(emptyList(), committed())
    }

    @Test
    fun `rollback undoes a block that returns, and from a joined block the whole transaction`() {
        drivine.transaction(rollback = true) { create("a") }
        assertEquals(emptyList(), committed())

        drivine.transaction {
            create("outer")
            drivine.transaction(rollback = true) { create("inner") }
        }
        assertEquals(emptyList(), committed())
    }

    @Test
    fun `a block that returns nothing, as Java writes one`() {
        drivine.transactionWithoutResult(Propagation.REQUIRED, false) { create("a") }
        assertEquals(listOf("a"), committed())
    }

    @Test
    fun `the object manager saves in the block's transaction`() {
        val gom = drivine.graphObjectManager("neo")
        val id = "$run-ada"
        try {
            assertFailsWith<IllegalStateException> {
                drivine.transaction {
                    gom.save(SpringFreePerson(id, "Ada"))
                    assertEquals("Ada", gom.load<SpringFreePerson>(id)?.name)
                    error("rolled back")
                }
            }
            assertNull(gom.load<SpringFreePerson>(id))

            drivine.transaction { gom.save(SpringFreePerson(id, "Ada")) }
            assertEquals("Ada", gom.load<SpringFreePerson>(id)?.name)
        } finally {
            pm.execute(QuerySpecification.withStatement("MATCH (p:SpringFreePerson {id: \$id}) DELETE p").bind(mapOf("id" to id)))
        }
    }

    private companion object {
        const val PASSWORD = "springfreetest"
    }
}
