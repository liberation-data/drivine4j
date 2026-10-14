package org.drivine.sample

import org.drivine.annotation.Hop
import org.drivine.manager.RelateMode
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.nodeRef
import org.drivine.query.dsl.query
import org.drivine.sample.fragment.AnonymousWebUser
import org.drivine.sample.fragment.AnonymousWebUserQueryDsl
import org.drivine.sample.fragment.IssueCore
import org.drivine.sample.fragment.Person
import org.drivine.sample.fragment.RegisteredWebUser
import org.drivine.sample.fragment.WebUser
import org.drivine.sample.fragment.load
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals

/** What one state's issues come to: a class of the caller's own, which `rows` makes. */
private data class StateCount(val state: String?, val issues: Long, val locked: Long)

/** The generated `groups.load<T, _> { }`: the fragment is named, and the row type is what `rows` makes. */
@SpringBootTest(classes = [SampleAppContext::class])
@Transactional
@Rollback(true)
class StatelessGroupsDslTest @Autowired constructor(
    private val stateless: StatelessGraphObjectManager,
) {
    /** Marks the issues of one test, which shares its database with the others. */
    private val batch = "groups-${UUID.randomUUID()}"

    private fun issue(number: Long, state: String?, locked: Boolean = false): IssueCore =
        stateless.save(IssueCore(UUID.randomUUID(), number, state, null, batch, null, locked))

    @Test
    fun `issues are grouped by a property, with a filtered count, a having and an order`() {
        issue(1, "open", locked = true)
        issue(2, "open")
        issue(3, "closed")
        issue(4, null)

        val states: List<StateCount> = stateless.groups.load<IssueCore, _> {
            where { query.title eq batch }
            val state = keyOrNull(query.state)
            val issues = count()
            val locked = count { query.locked eq true }
            val highest = max(query.id)
            having { highest lte 3 }
            orderBy { issues.desc() }
            rows { StateCount(it[state], it[issues], it[locked]) }
        }

        assertEquals(listOf(StateCount("open", 2, 1), StateCount("closed", 1, 0)), states)
    }

    @Test
    fun `issues are grouped by the person they are assigned to, who reads as a fragment`() {
        val ada = stateless.save(Person(UUID.randomUUID(), "Ada", null))
        val bob = stateless.save(Person(UUID.randomUUID(), "Bob", null))
        val issues = (1L..3L).map { issue(it, "open") }
        listOf(issues[0] to ada, issues[1] to ada, issues[2] to bob, issues[2] to ada).forEach { (issue, person) ->
            stateless.edges.relate(nodeRef<IssueCore>(issue.uuid), nodeRef<Person>(person.uuid), "ASSIGNED_TO", mode = RelateMode.CREATE)
        }

        val assigned = stateless.groups.load<IssueCore, _> {
            where { query.title eq batch }
            val person = key(path<Person>(Hop("ASSIGNED_TO")))
            val open = count()
            val numbers = sum(query.id)
            orderBy { open.desc() }
            rows { Triple(it[person], it[open], it[numbers]) }
        }

        assertEquals(listOf(Triple(ada, 3L, 6L), Triple(bob, 1L, 3L)), assigned)
    }

    @Test
    fun `a sealed fragment has a load of its own, and a subtype of it is read by its query object`() {
        stateless.save(AnonymousWebUser(UUID.randomUUID(), batch, "t1"))
        stateless.save(AnonymousWebUser(UUID.randomUUID(), batch, "t2"))
        stateless.save(RegisteredWebUser(UUID.randomUUID(), batch, "ada@example.com"))

        val all = stateless.groups.load<WebUser, _> {
            where { query.displayName eq batch }
            val name = key(query.displayName)
            val users = count()
            rows { it[name] to it[users] }
        }
        assertEquals(listOf(batch to 3L), all)

        // `load<AnonymousWebUser, _> { }` is ambiguous with the base's, as `loadAll` is: the subtype's
        // own properties are reached by passing its query object, and its labels say which nodes are counted.
        val anonymous = stateless.groups.load(AnonymousWebUser::class.java, AnonymousWebUserQueryDsl.INSTANCE) {
            where { query.displayName eq batch }
            val token = key(query.anonymousToken)
            val users = count()
            rows { it[token] to it[users] }
        }
        assertEquals(listOf("t1" to 1L, "t2" to 1L), anonymous)
    }
}
