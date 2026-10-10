package org.drivine.sample

import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.dsl.query
import org.drivine.sample.fragment.StampedNote
import org.drivine.sample.fragment.loadAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The generated DSL filters on a `@NodeStamp` field by the property the stamp is stored under. */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class StampFilterDslTest @Autowired constructor(
    private val stateless: StatelessGraphObjectManager,
) {
    @Test
    fun `a filter on the stamp field finds the node that carries the stamp`() {
        val saved = stateless.save(StampedNote(UUID.randomUUID().toString(), "Call Ada"))
        stateless.save(StampedNote(UUID.randomUUID().toString(), "Call Bob"))
        val stamp = assertNotNull(saved.stamp)

        val found = stateless.loadAll<StampedNote> { where { query.stamp eq stamp } }

        assertEquals(listOf(saved.id), found.map { it.id })
    }
}

/**
 * The generated DSL orders and pages by a `@NodeStamp` field. The stamp's property is dotted, so it
 * must reach the statement quoted: unquoted it is null for every row, which orders arbitrarily and
 * makes every keyset page empty.
 */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class StampOrderDslTest @Autowired constructor(
    private val stateless: StatelessGraphObjectManager,
) {
    private val batch = UUID.randomUUID().toString()

    /** Five notes of this test's own batch, and their stamps in ascending order. */
    private fun savedStamps(): List<String> =
        (1..5).map { assertNotNull(stateless.save(StampedNote(UUID.randomUUID().toString(), "$batch $it")).stamp) }.sorted()

    @Test
    fun `an order by the stamp field sorts by the stamp`() {
        val stamps = savedStamps()

        val ascending = stateless.loadAll<StampedNote> {
            where { query.text startsWith batch }
            orderBy { query.stamp.asc() }
        }
        val descending = stateless.loadAll<StampedNote> {
            where { query.text startsWith batch }
            orderBy { query.stamp.desc() }
        }

        assertEquals(stamps, ascending.map { it.stamp })
        assertEquals(stamps.reversed(), descending.map { it.stamp })
    }

    @Test
    fun `a keyset on the stamp field pages through every node once`() {
        val stamps = savedStamps()

        val paged = mutableListOf<String>()
        var page = stateless.loadAll<StampedNote> {
            where { query.text startsWith batch }
            orderBy { query.stamp.asc() }
            limit(2)
        }
        while (page.isNotEmpty()) {
            paged += page.map { assertNotNull(it.stamp) }
            val last = paged.last()
            page = stateless.loadAll<StampedNote> {
                where { query.text startsWith batch }
                orderBy { query.stamp.asc() }
                seek { query.stamp after last }
                limit(2)
            }
        }

        assertEquals(stamps, paged)
    }
}
