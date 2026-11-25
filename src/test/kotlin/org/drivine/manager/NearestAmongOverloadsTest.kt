package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import sample.vector.DocNode
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A Kotlin caller of `loadNearestAmong` that names the neighbours kept, positionally, by name or through the reified
 * extension, reaches the form that keeps them, and one that names none reaches the form it reached before
 * (liberation-data/drivine4j#868). With no searchK, the manager asks the store for none, for every neighbour as for each pair
 * once.
 */
class NearestAmongOverloadsTest {

    private val ids = listOf("A", "B")
    private val operations: GraphObjectOperations = mock(GraphObjectOperations::class.java)

    @Test
    fun `the neighbours given by position or by name reach the form that keeps them`() {
        operations.loadNearestAmong(Any::class.java, ids, 5, AmongNeighbours.AFTER)
        operations.loadNearestAmong(Any::class.java, ids, topK = 5, neighbours = AmongNeighbours.AFTER, searchK = 50)

        verify(operations).loadNearestAmong(Any::class.java, ids, 5, AmongNeighbours.AFTER, null, null, null)
        verify(operations).loadNearestAmong(Any::class.java, ids, 5, AmongNeighbours.AFTER, null, null, 50)
    }

    @Test
    fun `a call that names no neighbours reaches the form it reached before`() {
        operations.loadNearestAmong(Any::class.java, ids, 5, 0.5)
        operations.loadNearestAmong(Any::class.java, ids, topK = 5, threshold = null, searchK = 50)

        verify(operations).loadNearestAmong(Any::class.java, ids, 5, 0.5)
        verify(operations).loadNearestAmong(Any::class.java, ids, 5, null, null, 50)
    }

    @Test
    fun `the reified extension hands the neighbours on`() {
        operations.loadNearestAmong<Any>(ids, 5, AmongNeighbours.AFTER, threshold = 0.5)

        verify(operations).loadNearestAmong(Any::class.java, ids, 5, AmongNeighbours.AFTER, 0.5, null, null)
    }

    /**
     * With no searchK, every neighbour is measured through no index, as for each pair once: the manager asks for no searchK
     * where it once asked for `topK + 1`, and only a searchK the caller gives goes through the index (drivine4j#868).
     */
    @Test
    fun `with no searchK every neighbour is measured through no index, and only a searchK asks the index`() {
        val store = RecordingStore(DatabaseType.NEO4J)
        val manager = StatelessGraphObjectManager(store, Neo4jObjectMapper.instance, SubtypeRegistry())

        manager.loadNearestAmong(DocNode::class.java, ids, topK = 1)
        manager.loadNearestAmong(DocNode::class.java, ids, topK = 1, searchK = 3)

        val (exact, indexed) = store.queried.map { it.statement }
        assertFalse(exact.contains("queryNodes"), exact)
        assertTrue(indexed.contains("db.index.vector.queryNodes"), indexed)
    }
}
