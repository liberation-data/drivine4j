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
