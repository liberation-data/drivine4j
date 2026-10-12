package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.annotation.Direction
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.connection.DatabaseType
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.session.SessionManager
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import org.neo4j.driver.exceptions.TransientException
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Human
import java.sql.SQLException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What [EdgeOperations] makes of the count a statement returns, and what it does when the store
 * turns a statement away. The store is a [RecordingStore]: what the statements do to a graph is the
 * cross-engine tests' to say.
 */
@Suppress("DEPRECATION") // built on GraphObjectManager, which is deprecated for callers
class EdgeOperationsTest {

    private val mapper = Neo4jObjectMapper.instance
    private val claim = nodeRef<Claim>("c1")
    private val human = nodeRef<Human>("h1")

    private fun edges(store: RecordingStore) = GraphObjectManager(store, SessionManager(mapper), mapper, SubtypeRegistry()).edges

    private fun counting(count: Long) = RecordingStore(DatabaseType.NEO4J) { listOf(count) }

    @Test
    fun `relate says whether the two nodes were found and joined, from the count the statement returns`() {
        assertTrue(edges(counting(1)).relate(claim, human, "MENTIONS"))
        // Several relationships took the properties: the nodes were joined all the same.
        assertTrue(edges(counting(3)).relate(claim, human, "MENTIONS", mapOf("page" to 1)))
        assertFalse(edges(counting(0)).relate(claim, human, "MENTIONS"), "a node was absent, so nothing was written")
    }

    @Test
    fun `relate merges unless it is told to create`() {
        val merging = counting(1)
        val creating = counting(1)
        edges(merging).relate(claim, human, "MENTIONS")
        edges(creating).relate(claim, human, "MENTIONS", mode = RelateMode.CREATE)

        assertContains(merging.queried.single().statement, "\nMERGE (a)-[r:`MENTIONS`]->(b)")
        assertContains(creating.queried.single().statement, "\nCREATE (a)-[r:`MENTIONS`]->(b)")
    }

    @Test
    fun `unrelate and unrelateAll give how many relationships were removed, none when there was none`() {
        assertEquals(2, edges(counting(2)).unrelate(claim, human, "MENTIONS"))
        assertEquals(0, edges(counting(0)).unrelate(claim, human, "MENTIONS"))
        assertEquals(5, edges(counting(5)).unrelateAll(human, "MENTIONS", Direction.INCOMING))
    }

    @Test
    fun `unrelateAll follows the outgoing relationships unless it is told another direction`() {
        val store = counting(0)
        edges(store).unrelateAll(claim, "MENTIONS")

        assertContains(store.queried.single().statement, "-[r:`MENTIONS`]->(b)")
    }

    @Test
    fun `a statement the engine turned away is run again, and its count is the one that came back`() {
        var attempts = 0
        val store = RecordingStore(DatabaseType.NEO4J) {
            if (attempts++ < 2) DrivineException.withRootCause(TransientException("Neo.TransientError.Transaction.DeadlockDetected", "deadlock")) else listOf(1L)
        }

        assertTrue(edges(store).relate(claim, human, "MENTIONS"))
        assertEquals(3, store.queried.size)
    }

    @Test
    fun `a failure that is not contention is thrown at once`() {
        val failure = DrivineException.withRootCause(IllegalStateException("syntax"))
        val store = RecordingStore(DatabaseType.NEO4J) { failure }

        assertSame(failure, assertFailsWith<DrivineException> { edges(store).unrelate(claim, human, "MENTIONS") })
        assertEquals(1, store.queried.size)
    }

    @Test
    fun `a relationship with no type is refused before anything is sent`() {
        val store = counting(1)

        assertFailsWith<IllegalArgumentException> { edges(store).relate(claim, human, " ") }
        assertFailsWith<IllegalArgumentException> { edges(store).unrelate(claim, human, "") }
        assertFailsWith<IllegalArgumentException> { edges(store).unrelateAll(claim, "") }
        assertFailsWith<IllegalArgumentException> { edges(store).loadRelated<Human>(claim, "") }
        assertTrue(store.queried.isEmpty())
    }

    @Test
    fun `loadRelated loads a fragment, and is refused for a view by its name`() {
        val store = RecordingStore(DatabaseType.NEO4J)

        val refused = assertFailsWith<IllegalArgumentException> { edges(store).loadRelated(claim, "MENTIONS", Direction.OUTGOING, ClaimView::class.java) }

        assertEquals("loadRelated loads a @NodeFragment; ClaimView is not one.", refused.message)
        assertTrue(store.queried.isEmpty())
    }

    @Test
    fun `loadRelated gives what the store returned, and follows the outgoing relationships unless told another direction`() {
        val people = listOf(Human("h1", "Ada"), Human("h2", "Grace"))
        val store = RecordingStore(DatabaseType.NEO4J) { people }

        assertEquals(people, edges(store).loadRelated<Human>(claim, "MENTIONS"))
        assertContains(store.queried.single().statement, "MATCH (a:`Claim` {`id`: \$_fromId})-[:`MENTIONS`]->(n:`Human`)")
        assertEquals(mapOf<String, Any?>("_fromId" to "c1"), store.queried.single().parameters)
        assertEquals(emptyList(), edges(RecordingStore(DatabaseType.NEO4J)).loadRelated<Human>(claim, "MENTIONS", Direction.INCOMING))
    }

    @Test
    fun `on Oracle the same calls are write blocks, and a deadlock there is run again too`() {
        var attempts = 0
        val store = RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(OracleGraph("g")), graphName = "g") {
            if (it.statement.trimStart().startsWith("DECLARE") && "user_pg_prop_definitions" !in it.statement && attempts++ == 0) {
                DrivineException.withRootCause(SQLException("ORA-00060: deadlock detected while waiting for resource", "61000", 60))
            } else {
                listOf(1L)
            }
        }

        assertTrue(edges(store).relate(claim, human, "MENTIONS"))
        assertEquals(2, store.queried.size)
        store.queried.forEach { assertFalse("MATCH (a" in it.statement, "no Cypher is sent to a store that does not speak it") }
    }
}
