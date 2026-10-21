package org.drivine.test

import org.junit.jupiter.api.Test
import redis.clients.jedis.Jedis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a class that shares [SharedFalkorDb] finds: no graph another class left, whatever it named it. */
class SharedFalkorDbTest {

    @Test
    fun `a class is handed the shared FalkorDB with no graph of an earlier class, nor its indexes`() {
        val container = SharedFalkorDb.forClass()
        Jedis(container.host, container.getMappedPort(6379)).use { jedis ->
            for (graph in listOf("first", "second")) {
                jedis.sendCommand({ "GRAPH.QUERY".toByteArray() }, graph, "CREATE INDEX FOR (n:A) ON (n.x)")
                jedis.sendCommand({ "GRAPH.QUERY".toByteArray() }, graph, "CREATE (:A {x: 1})")
            }
            // FalkorDB keeps a telemetry key beside each graph, which goes too.
            assertTrue(jedis.keys("*").containsAll(setOf("first", "second")))

            SharedFalkorDb.forClass()

            assertEquals(emptySet(), jedis.keys("*"))
        }
    }
}
