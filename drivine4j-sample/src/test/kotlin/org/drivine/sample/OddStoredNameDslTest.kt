package org.drivine.sample

import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.query
import org.drivine.sample.fragment.OddlyStored
import org.drivine.sample.fragment.OddlyStoredQueryDsl
import org.drivine.sample.fragment.StampedNoteQueryDsl
import org.drivine.sample.fragment.loadAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The generated DSL carries a stored name as it is declared, whatever it holds: a `$`, a `%`, a
 * quote or a backslash is part of the name, and not of the source the generator writes.
 */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class OddStoredNameDslTest @Autowired constructor(
    private val stateless: StatelessGraphObjectManager,
    private val persistenceManager: PersistenceManager,
) {
    @Test
    fun `the generated DSL names each property as it is stored`() {
        assertEquals(
            mapOf(
                "id" to "id",
                "price" to "price\$usd", "price\$usd" to "price\$usd",
                "rate" to "rate%", "rate%" to "rate%",
                "greeting" to "say \"hi\"", "say \"hi\"" to "say \"hi\"",
                "path" to "back\\slash", "back\\slash" to "back\\slash",
            ),
            OddlyStoredQueryDsl.INSTANCE.fieldKeyPaths,
        )
    }

    @Test
    fun `the generated DSL names the stamp's property as the library stores it`() {
        assertEquals(Stamps.PROPERTY, StampedNoteQueryDsl.INSTANCE.fieldKeyPaths["stamp"])
    }

    @Test
    fun `a filter and an order on such a property read the stored property`() {
        val batch = UUID.randomUUID().toString()
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (:OddlyStored {id: ${'$'}a, `price${'$'}usd`: 30, `rate%`: 3, `say "hi"`: 'hello', `back\slash`: ${'$'}batch})
                CREATE (:OddlyStored {id: ${'$'}b, `price${'$'}usd`: 10, `rate%`: 1, `say "hi"`: 'hey', `back\slash`: ${'$'}batch})
                CREATE (:OddlyStored {id: ${'$'}c, `price${'$'}usd`: 20, `rate%`: 2, `say "hi"`: 'hello', `back\slash`: ${'$'}batch})
                """.trimIndent()
            ).bind(mapOf("a" to "$batch-a", "b" to "$batch-b", "c" to "$batch-c", "batch" to batch))
        )

        val byPrice = stateless.loadAll<OddlyStored> {
            where { query.path eq batch }
            orderBy { query.price.asc() }
        }
        assertEquals(listOf(10L, 20L, 30L), byPrice.map { it.price })
        assertEquals(listOf(1L, 2L, 3L), byPrice.map { it.rate })

        val greeted = stateless.loadAll<OddlyStored> {
            where { query.path eq batch; query.greeting eq "hello"; query.rate gte 3L }
        }
        assertEquals(listOf(OddlyStored("$batch-a", price = 30, rate = 3, greeting = "hello", path = batch)), greeted)
    }
}
