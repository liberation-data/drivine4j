package org.drivine.manager

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/**
 * A Kotlin caller of `loadNearestAmong` that names the neighbours kept, positionally, by name or through the reified
 * extension, reaches the form that keeps them, and one that names none reaches the form it reached before
 * (liberation-data/drivine4j#868).
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
}
