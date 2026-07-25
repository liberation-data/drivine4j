package org.drivine.sample

import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.instanceOf
import org.drivine.query.dsl.query
import org.drivine.sample.fragment.AnonymousWebUser
import org.drivine.sample.fragment.AnonymousWebUserQueryDsl
import org.drivine.sample.fragment.RegisteredWebUser
import org.drivine.sample.fragment.RegisteredWebUserQueryDsl
import org.drivine.sample.fragment.WebUser
import org.drivine.sample.fragment.count
import org.drivine.sample.fragment.deleteAll
import org.drivine.sample.fragment.loadAll
import org.drivine.test.TestCleanup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The **generated** standalone `@NodeFragment` query DSL, exercised end-to-end: `loadAll` / `count` /
 * `deleteAll` bound to `<Fragment>QueryDsl.INSTANCE`, a `where` targeting node properties directly,
 * `instanceOf()` on a sealed fragment, and the parameterized binding path (no string splice).
 */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class FragmentDslTest @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager,
) {

    private val anonToken = "anon-tok"
    private val regEmail = "reg@example.com"

    @BeforeEach
    fun seed() {
        TestCleanup.beforeEach(persistenceManager, "fragment-dsl-test")
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (:WebUser:Anonymous {uuid: '${UUID.randomUUID()}', displayName: 'Anon', anonymousToken: '$anonToken', createdBy: 'fragment-dsl-test'})
                CREATE (:WebUser:Registered {uuid: '${UUID.randomUUID()}', displayName: 'Reg', email: '$regEmail', createdBy: 'fragment-dsl-test'})
                """.trimIndent()
            )
        )
    }

    @Test
    fun `loadAll on a sealed fragment dispatches each node to its subtype`() {
        val all = graphObjectManager.loadAll<WebUser> { where { query.displayName neq "nobody" } }
        assertEquals(2, all.size)
        assertTrue(all.any { it is AnonymousWebUser } && all.any { it is RegisteredWebUser })
    }

    @Test
    fun `instanceOf filters a sealed fragment to one subtype`() {
        val anon = graphObjectManager.loadAll<WebUser> { where { query.instanceOf<AnonymousWebUser>() } }
        assertEquals(1, anon.size)
        assertTrue(anon.single() is AnonymousWebUser)
    }

    @Test
    fun `subtype-property filter uses the generated subtype DSL via the explicit form`() {
        // `loadAll<RegisteredWebUser> { }` is ambiguous with the base extension; the subtype's own
        // property surface is reached through its generated DSL + the explicit 3-arg form.
        val regs = graphObjectManager.loadAll(
            RegisteredWebUser::class.java, RegisteredWebUserQueryDsl.INSTANCE
        ) { where { query.email eq regEmail } }
        assertEquals(1, regs.size)
        assertEquals(regEmail, regs.single().email)

        val none = graphObjectManager.loadAll(
            RegisteredWebUser::class.java, RegisteredWebUserQueryDsl.INSTANCE
        ) { where { query.email eq "missing@example.com" } }
        assertTrue(none.isEmpty())
    }

    @Test
    fun `count and deleteAll bind through the generated DSL`() {
        assertEquals(2, graphObjectManager.count<WebUser> { where { query.displayName neq "nobody" } })
        assertEquals(
            1,
            graphObjectManager.count(AnonymousWebUser::class.java, AnonymousWebUserQueryDsl.INSTANCE) {
                where { query.anonymousToken eq anonToken }
            },
        )

        val deleted = graphObjectManager.deleteAll(
            RegisteredWebUser::class.java, RegisteredWebUserQueryDsl.INSTANCE
        ) { where { query.email eq regEmail } }
        assertEquals(1, deleted)
        assertTrue(
            graphObjectManager.loadAll(RegisteredWebUser::class.java, RegisteredWebUserQueryDsl.INSTANCE) {
                where { query.email eq regEmail }
            }.isEmpty()
        )
    }
}
