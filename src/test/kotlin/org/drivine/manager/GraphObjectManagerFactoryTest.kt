package org.drivine.manager

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** The factory hands out one manager of each kind per database and persistence manager type. */
@Suppress("DEPRECATION") // get() is deprecated for callers, and still has to hand out one manager per key
class GraphObjectManagerFactoryTest {

    private val persistenceManagers = mock(PersistenceManagerFactory::class.java).also { factory ->
        listOf("default", "other").forEach { database ->
            PersistenceManagerType.entries.forEach { type ->
                `when`(factory.get(database, type)).thenReturn(mock(PersistenceManager::class.java, RETURNS_DEEP_STUBS))
            }
        }
    }
    private val factory = GraphObjectManagerFactory(persistenceManagers, Neo4jObjectMapper.instance, SubtypeRegistry())

    @Test
    fun `stateless returns the same manager for the same database and type`() {
        assertSame(factory.stateless(), factory.stateless())
        assertSame(factory.stateless(), factory.stateless("default", PersistenceManagerType.DELEGATING))
        assertSame(
            factory.stateless("other", PersistenceManagerType.TRANSACTIONAL),
            factory.stateless("other", PersistenceManagerType.TRANSACTIONAL),
        )
    }

    @Test
    fun `stateless returns a different manager for another database or type`() {
        assertNotSame(factory.stateless("default"), factory.stateless("other"))
        assertNotSame(
            factory.stateless("default", PersistenceManagerType.TRANSACTIONAL),
            factory.stateless("default", PersistenceManagerType.NON_TRANSACTIONAL),
        )
    }

    @Test
    fun `get returns the same manager for the same database and type, and a different one for another`() {
        assertSame(factory.get(), factory.get("default", PersistenceManagerType.DELEGATING))
        assertNotSame(factory.get("default"), factory.get("other"))
    }

    @Test
    fun `threads that first ask at once are all given one manager`() {
        val threads = 16
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val asked = (1..threads).map {
                pool.submit<Pair<GraphObjectManager, StatelessGraphObjectManager>> {
                    start.await()
                    factory.get("other") to factory.stateless("other")
                }
            }
            start.countDown()
            val given = asked.map { it.get() }

            assertEquals(1, given.map { System.identityHashCode(it.first) }.toSet().size)
            assertEquals(1, given.map { System.identityHashCode(it.second) }.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }
}
