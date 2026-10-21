package org.drivine.manager

import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.drivine.annotation.Direction
import org.drivine.annotation.Hop
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.Aggregate
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.GroupRows
import org.drivine.query.dsl.Key
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.any
import org.drivine.query.dsl.hasAnyLabel
import org.drivine.query.dsl.hasItem
import org.drivine.query.dsl.instanceOf
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.session.SessionManager
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.groups.Bot
import sample.patch.BaggedNodeQueryDsl
import sample.patch.PatchSession
import sample.propertybag.BaggedNode
import sample.groups.Feedback
import sample.groups.FeedbackQueryDsl
import sample.groups.Message
import sample.groups.MessageQueryDsl
import sample.groups.Owner
import sample.groups.OwnerQueryDsl
import sample.groups.Reading
import sample.groups.ReadingQueryDsl
import sample.groups.Role
import sample.groups.Session
import sample.groups.SessionView
import sample.groups.SessionViewQueryDsl
import sample.groups.Tag
import sample.groups.Team
import sample.groups.User

/**
 * `groups`: the nodes of a fragment by a key, with numbers about each group, in one statement. The
 * same rows are expected of Neo4j, FalkorDB, Memgraph and Oracle, which is the point of it.
 */
abstract class GroupsContract {

    abstract val pm: PersistenceManager

    private val stateless: StatelessGraphObjectManager
        get() = StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry())

    private val groups: GroupOperations
        get() = stateless.groups

    private fun <R> feedback(spec: GroupQuerySpec<FeedbackQueryDsl>.() -> GroupRows<R>): List<R> =
        groups.load(Feedback::class.java, FeedbackQueryDsl.INSTANCE, spec)

    private fun <R> messages(spec: GroupQuerySpec<MessageQueryDsl>.() -> GroupRows<R>): List<R> =
        groups.load(Message::class.java, MessageQueryDsl.INSTANCE, spec)

    private val january: Instant = Instant.parse("2026-01-15T10:00:00Z")
    private val march: Instant = Instant.parse("2026-03-01T00:00:00Z")

    /** Three votes on `/install`, one of them in January, and two on `/agents`. */
    private fun votes() {
        stateless.saveAll(
            listOf(
                Feedback("f1", "/install", helpful = true, createdAt = january, stars = 5, weight = 1.5),
                Feedback("f2", "/install", helpful = true, stars = 3, weight = 0.5),
                Feedback("f3", "/install", helpful = false),
                Feedback("f4", "/agents", helpful = true, stars = 4, section = "intro"),
                Feedback("f5", "/agents", helpful = false, stars = 2, section = "intro"),
            )
        )
    }

    private fun relate(from: NodeRef, type: String, to: NodeRef) {
        assertEquals(true, stateless.edges.relate(from, to, type, mode = RelateMode.CREATE))
    }

    /** Alice wrote two messages and was answered once, in one session; Bob wrote one; `m5` is in no session. */
    private fun chat() {
        stateless.saveAll(listOf(User("alice", "al"), User("bob")))
        stateless.saveAll(listOf(Session("s1"), Session("s2")))
        stateless.saveAll(
            listOf(
                Message("m1", Role.USER, tokens = 10),
                Message("m2", Role.USER, createdAt = january, tokens = 20),
                Message("m3", Role.ASSISTANT, tokens = 5),
                Message("m4", Role.USER, tokens = 7),
                Message("m5", Role.USER, tokens = 100),
            )
        )
        relate(nodeRef<Session>("s1"), "OWNED_BY", nodeRef<User>("alice"))
        relate(nodeRef<Session>("s2"), "OWNED_BY", nodeRef<User>("bob"))
        listOf("m1", "m2", "m3").forEach { relate(nodeRef<Session>("s1"), "HAS_MESSAGE", nodeRef<Message>(it)) }
        relate(nodeRef<Session>("s2"), "HAS_MESSAGE", nodeRef<Message>("m4"))
    }

    private val owner = arrayOf(Hop("HAS_MESSAGE", Direction.INCOMING, label = "GroupSession"), Hop("OWNED_BY"))

    @BeforeEach
    fun clean() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    // ----- By a property -----

    @Test
    fun `countBy counts the nodes of each value, in the order of the values`() {
        votes()

        val counted: Map<String, Long> = groups.countBy(Feedback::page)

        assertEquals(listOf("/agents" to 2L, "/install" to 3L), counted.toList())
        val bySection: Map<String?, Long> = groups.countBy(Feedback::section)
        assertEquals(mapOf("intro" to 2L, null to 3L), bySection)
        assertEquals(mapOf<Any?, Long>(true to 3L, false to 2L), groups.countBy(Feedback::class.java, "helpful"))
    }

    @Test
    fun `a group has several numbers, each read through its handle`() {
        votes()

        val rows = feedback {
            val page = key(query.page)
            val votes = count()
            val likes = count { query.helpful eq true }
            rows { Triple(it[page], it[votes], it[likes]) }
        }

        assertEquals(listOf(Triple("/agents", 2L, 1L), Triple("/install", 3L, 2L)), rows)
    }

    @Test
    fun `where takes nodes away, a filter decides what one number counts, and having takes groups away`() {
        votes()

        val since = feedback {
            where { query.createdAt gte march }
            val page = key(query.page)
            val votes = count()
            val likes = count { query.helpful eq true }
            rows { Triple(it[page], it[votes], it[likes]) }
        }
        assertEquals(listOf(Triple("/agents", 2L, 1L), Triple("/install", 2L, 1L)), since)

        val busy = feedback {
            val page = key(query.page)
            val votes = count()
            having { votes gte 3 }
            rows { it[page] to it[votes] }
        }
        assertEquals(listOf("/install" to 3L), busy)

        val together = feedback {
            where { query.createdAt gte march }
            val page = key(query.page)
            val votes = count()
            val likes = count { query.helpful eq true }
            having { votes gte 3 }
            orderBy { votes.desc() }
            limit(10)
            rows { Triple(it[page], it[votes], it[likes]) }
        }
        assertEquals(emptyList(), together)

        // The mistake to look for: the filter moved up into the where, and every vote left is a like.
        val misplaced = feedback {
            where { query.helpful eq true }
            val page = key(query.page)
            val votes = count()
            rows { it[page] to it[votes] }
        }
        assertEquals(listOf("/agents" to 1L, "/install" to 2L), misplaced)
    }

    @Test
    fun `two filters on one property count different things`() {
        votes()
        stateless.save(Feedback("f6", "/agents"))

        val rows = feedback {
            val page = key(query.page)
            val votes = count()
            val likes = count { query.helpful eq true }
            val dislikes = count { query.helpful eq false }
            rows { listOf(it[page], it[votes], it[likes], it[dislikes]) }
        }

        // A vote that does not say is neither: it is in `votes`, and so in `votes - likes`.
        assertEquals(listOf(listOf("/agents", 3L, 1L, 1L), listOf("/install", 3L, 2L, 1L)), rows)
    }

    @Test
    fun `a filtered count of nothing is zero, and its group is still there`() {
        votes()

        val rows = feedback {
            val page = key(query.page)
            val old = count { query.createdAt lt march }
            rows { it[page] to it[old] }
        }

        assertEquals(listOf("/agents" to 0L, "/install" to 1L), rows)
    }

    @Test
    fun `the nodes with no value are one group under keyOrNull, and key refuses a nullable property`() {
        votes()

        val rows = feedback {
            val section = keyOrNull(query.section)
            val votes = count()
            rows { it[section] to it[votes] }
        }
        assertEquals(listOf("intro" to 2L, null to 3L), rows)

        val refused = assertFailsWith<IllegalArgumentException> {
            feedback {
                val section = key(query.section)
                rows { it[section] }
            }
        }
        assertContains(refused.message!!, "'section' of Feedback is declared nullable")
        assertContains(refused.message!!, "keyOrNull")
    }

    @Test
    fun `having takes an Int, and several are joined by AND`() {
        votes()
        val minimum = 2

        val rows = feedback {
            val page = key(query.page)
            val votes = count()
            val likes = count { query.helpful eq true }
            having {
                votes gte minimum
                likes lt 2
            }
            rows { it[page] }
        }

        assertEquals(listOf("/agents"), rows)
    }

    @Test
    fun `groups that tie come back in key order, with a limit and without`() {
        stateless.saveAll(listOf("/d", "/b", "/c", "/a").mapIndexed { i, page -> Feedback("t$i", page) } + Feedback("t9", "/c"))

        fun ranked(top: Int?) = feedback {
            val page = key(query.page)
            val votes = count()
            orderBy { votes.desc() }
            top?.let { limit(it) }
            rows { it[page] to it[votes] }
        }

        assertEquals(listOf("/c" to 2L, "/a" to 1L, "/b" to 1L, "/d" to 1L), ranked(null))
        assertEquals(listOf("/c" to 2L, "/a" to 1L, "/b" to 1L), ranked(3))
    }

    @Test
    fun `skip and limit page the groups, and the pages together are the whole ranking`() {
        stateless.saveAll(listOf("/a", "/b", "/c", "/d", "/e").mapIndexed { i, page -> Feedback("p$i", page) })

        fun page(skipped: Int) = feedback {
            val page = key(query.page)
            count()
            skip(skipped)
            limit(2)
            rows { it[page] }
        }

        assertEquals(listOf(listOf("/a", "/b"), listOf("/c", "/d"), listOf("/e")), listOf(page(0), page(2), page(4)))
    }

    @Test
    fun `a key reads as the type its field declares, and two keys are a compound key`() {
        votes()

        val byStars = feedback {
            where { query.stars.isNotNull() }
            val stars = keyOrNull(query.stars)
            val votes = count()
            orderBy { stars.desc() }
            rows { it[stars] to it[votes] }
        }
        assertEquals(listOf<Pair<Long?, Long>>(5L to 1L, 4L to 1L, 3L to 1L, 2L to 1L), byStars)

        val byMoment = feedback {
            val at = key(query.createdAt)
            val votes = count()
            rows { it[at] to it[votes] }
        }
        assertEquals(listOf(january to 1L, march to 4L), byMoment)

        val compound = feedback {
            val page = key(query.page)
            val helpful = keyOrNull(query.helpful)
            val votes = count()
            rows { Triple(it[page], it[helpful], it[votes]) }
        }
        assertEquals(
            listOf(Triple("/agents", false, 1L), Triple("/agents", true, 1L), Triple("/install", false, 1L), Triple("/install", true, 2L)),
            compound,
        )
    }

    @Test
    fun `sum, avg, min and max skip a node with no value, and over no values sum is 0 and the others null`() {
        votes()

        val rows = feedback {
            val page = key(query.page)
            val total = sum(query.stars)
            val mean = avg(query.stars)
            val least = min(query.stars)
            val most = max(query.stars)
            val weight = sum(query.weight)
            val first = min(query.createdAt)
            rows { listOf(it[page], it[total], it[mean], it[least], it[most], it[weight], it[first]) }
        }
        assertEquals(
            listOf(listOf("/agents", 6L, 3.0, 2L, 4L, 0.0, march), listOf("/install", 8L, 4.0, 3L, 5L, 2.0, january)),
            rows,
        )

        val none = feedback {
            val page = key(query.page)
            val total = sum(query.stars) { query.helpful.isNull() }
            val mean = avg(query.stars) { query.helpful.isNull() }
            val most = max(query.stars) { query.helpful.isNull() }
            val liked = sum(query.stars) { query.helpful eq true }
            rows { listOf(it[page], it[total], it[mean], it[most], it[liked]) }
        }
        assertEquals(listOf(listOf("/agents", 0L, null, null, 4L), listOf("/install", 0L, null, null, 8L)), none)
    }

    @Test
    fun `an order places its nulls where it is told`() {
        votes()

        fun sections(last: Boolean) = feedback {
            val section = keyOrNull(query.section)
            count()
            orderBy { if (last) section.desc().nullsLast() else section.asc().nullsFirst() }
            rows { it[section] }
        }

        assertEquals(listOf("intro", null), sections(last = true))
        assertEquals(listOf(null, "intro"), sections(last = false))
    }

    @Test
    fun `a sum of an Int is a Long and of a Float a Double, and 0 over no values`() {
        stateless.saveAll(
            listOf(
                Reading("r1", "north", ticks = 2, level = 0.5f),
                Reading("r2", "north", ticks = 3, level = 1.5f),
                Reading("r3", "south"),
            )
        )

        val rows = groups.load(Reading::class.java, ReadingQueryDsl.INSTANCE) {
            val sensor = key(query.sensor)
            val ticks = sum(query.ticks)
            val level = sum(query.level)
            val least = min(query.ticks)
            rows { listOf(it[sensor], it[ticks], it[level], it[least]) }
        }

        assertEquals(listOf(listOf("north", 5L, 2.0, 2), listOf("south", 0L, 0.0, null)), rows)
    }

    @Test
    fun `having leaves out the groups a number equals, and a limit of none gives none`() {
        votes()

        val notThree = feedback {
            val page = key(query.page)
            val votes = count()
            having { votes neq 3 }
            rows { it[page] to it[votes] }
        }
        assertEquals(listOf("/agents" to 2L), notThree)

        val none = feedback {
            val page = key(query.page)
            limit(0)
            rows { it[page] }
        }
        assertEquals(emptyList(), none)
    }

    @Test
    fun `a read of keys alone gives each key once`() {
        votes()
        chat()

        assertEquals(listOf("/agents", "/install"), feedback { val page = key(query.page); rows { it[page] } })
        assertEquals(
            listOf("/agents" to false, "/agents" to true, "/install" to false, "/install" to true),
            feedback { val page = key(query.page); val helpful = keyOrNull(query.helpful); limit(4); rows { it[page] to it[helpful] } },
        )
        assertEquals(listOf("alice", "bob"), messages { val user = key(path<User>(*owner)); rows { it[user].id } })
    }

    @Test
    fun `having compares a least or greatest value of any kind`() {
        votes()

        val early = feedback {
            val page = key(query.page)
            val first = min(query.createdAt)
            having { first lt march }
            rows { it[page] to it[first] }
        }
        assertEquals(listOf("/install" to january), early)

        val liked = feedback {
            where { query.helpful eq false }
            val page = key(query.page)
            val any = max(query.helpful)
            having { any eq false }
            rows { it[page] to it[any] }
        }
        assertEquals(listOf<Pair<String, Boolean?>>("/agents" to false, "/install" to false), liked)
    }

    @Test
    fun `an empty text is a key of its own, apart from no value, and before every other text`() {
        stateless.saveAll(
            listOf(
                Feedback("e1", "", section = ""),
                Feedback("e2", "", section = "intro"),
                Feedback("e3", "/a", section = ""),
                Feedback("e4", "/a"),
            )
        )

        assertEquals(listOf("" to 2L, "/a" to 2L), feedback { val page = key(query.page); val votes = count(); rows { it[page] to it[votes] } })

        fun sections(build: GroupQuerySpec<FeedbackQueryDsl>.(Key<String?>) -> Unit) = feedback {
            val section = keyOrNull(query.section)
            val votes = count()
            build(section)
            rows { it[section] to it[votes] }
        }
        assertEquals(listOf("" to 2L, "intro" to 1L, null to 1L), sections { })
        assertEquals(listOf(null to 1L, "intro" to 1L, "" to 2L), sections { section -> orderBy { section.desc() } })
        assertEquals(listOf(null to 1L, "" to 2L, "intro" to 1L), sections { section -> orderBy { section.asc().nullsFirst() } })
        assertEquals(listOf("intro" to 1L, "" to 2L, null to 1L), sections { section -> orderBy { section.desc().nullsLast() } })
    }

    @Test
    fun `a key no field declares reads as what is stored, and numbers order as numbers`() {
        stateless.saveAll(listOf(9, 10, 10).mapIndexed { i, priority -> BaggedNode("b$i", "bagged", mapOf("priority" to priority)) })

        val rows = groups.load(BaggedNode::class.java, BaggedNodeQueryDsl.INSTANCE) {
            val priority = keyOrNull(query.metadata.key("priority"))
            val nodes = count()
            rows { it[priority] to it[nodes] }
        }

        assertEquals(listOf<Pair<Any?, Long>>(9L to 1L, 10L to 2L), rows)
    }

    @Test
    fun `countBy counts by the stamp, which is stored under a name of its own`() {
        stateless.saveAll(listOf(PatchSession("ps1"), PatchSession("ps2")))

        val stamps = groups.countBy(PatchSession::stamp)

        assertEquals(setOf(1L), stamps.values.toSet())
        assertEquals(stateless.loadAll(PatchSession::class.java).map { it.stamp }.toSet(), stamps.keys)
    }

    @Test
    fun `an order places the nulls of an aggregate where it is told`() {
        votes()
        stateless.save(Feedback("f9", "/none"))

        fun means(last: Boolean) = feedback {
            val page = key(query.page)
            val mean = avg(query.stars)
            orderBy { if (last) mean.desc().nullsLast() else mean.asc().nullsFirst() }
            rows { it[page] to it[mean] }
        }

        assertEquals(listOf("/install" to 4.0, "/agents" to 3.0, "/none" to null), means(last = true))
        assertEquals(listOf("/none" to null, "/agents" to 3.0, "/install" to 4.0), means(last = false))
    }

    @Test
    fun `a test of a list for a value goes with a path key`() {
        chat()
        stateless.save(Message("m1", Role.USER, tokens = 10, tags = listOf("x", "y")))
        stateless.save(Message("m4", Role.USER, tokens = 7, tags = listOf("y")))

        val rows = messages {
            where { query.tags hasItem "y" }
            val user = key(path<User>(*owner))
            val tagged = count()
            val both = count { query.tags hasItem "x" }
            rows { Triple(it[user].id, it[tagged], it[both]) }
        }

        assertEquals(listOf(Triple("alice", 1L, 1L), Triple("bob", 1L, 0L)), rows)
    }

    // ----- By a node some hops away -----

    @Test
    fun `a path key groups by the node reached, which reads as its fragment`() {
        chat()

        val rows = messages {
            where { query.role eq Role.USER }
            val user = key(path<User>(*owner))
            val written = count()
            orderBy { written.desc() }
            rows { it[user] to it[written] }
        }

        // m3 is no USER message, and m5 reaches no user, so it is in no group.
        assertEquals(listOf(User("alice", "al") to 2L, User("bob") to 1L), rows)
    }

    @Test
    fun `the most active users since a moment, as Guide asks`() {
        chat()

        val top = messages {
            where { query.role eq Role.USER; query.createdAt gte march }
            val user = key(path<User>(*owner))
            val written = count()
            orderBy { written.desc() }
            limit(5)
            rows { (it[user].username ?: it[user].id) to it[written] }
        }

        // Each has one message since March, so the tie is broken by the user's id.
        assertEquals(listOf("al" to 1L, "bob" to 1L), top)
    }

    @Test
    fun `a node that reaches its key node by two ways counts once, in every aggregate`() {
        chat()
        // m1 is in a second session of Alice's, so two ways lead from it to her.
        stateless.save(Session("s3"))
        relate(nodeRef<Session>("s3"), "OWNED_BY", nodeRef<User>("alice"))
        relate(nodeRef<Session>("s3"), "HAS_MESSAGE", nodeRef<Message>("m1"))

        val rows = messages {
            val user = key(path<User>(*owner))
            val all = count()
            val written = count { query.role eq Role.USER }
            val tokens = sum(query.tokens)
            val mean = avg(query.tokens) { query.role eq Role.USER }
            val most = max(query.tokens)
            rows { listOf(it[user].id, it[all], it[written], it[tokens], it[mean], it[most]) }
        }

        assertEquals(listOf(listOf("alice", 3L, 2L, 35L, 15.0, 20L), listOf("bob", 1L, 1L, 7L, 7.0, 7L)), rows)
    }

    @Test
    fun `a node that reaches several key nodes is in each group`() {
        chat()
        // m4 is also in a session of Alice's.
        relate(nodeRef<Session>("s1"), "HAS_MESSAGE", nodeRef<Message>("m4"))

        val rows = messages {
            val user = key(path<User>(*owner))
            val all = count()
            rows { it[user].id to it[all] }
        }

        assertEquals(listOf("alice" to 4L, "bob" to 1L), rows)
    }

    @Test
    fun `a path key and a property key are a compound key`() {
        chat()

        val rows = messages {
            val user = key(path<User>(*owner))
            val role = key(query.role)
            val tokens = sum(query.tokens)
            having { tokens gt 5 }
            rows { Triple(it[user].id, it[role], it[tokens]) }
        }

        assertEquals(listOf(Triple("alice", Role.USER, 30L), Triple("bob", Role.USER, 7L)), rows)
    }

    @Test
    fun `two path keys are a compound key of nodes`() {
        chat()
        stateless.save(Team("t1", "Docs"))
        stateless.save(Bot("b1", "small"))
        relate(nodeRef<Message>("m1"), "AUTHORED_BY", nodeRef<Owner>("t1"))
        relate(nodeRef<Message>("m2"), "AUTHORED_BY", nodeRef<Owner>("b1"))
        relate(nodeRef<Message>("m4"), "AUTHORED_BY", nodeRef<Owner>("b1"))

        val rows = messages {
            val user = key(path<User>(*owner))
            val author = key(path<Owner>(Hop("AUTHORED_BY")))
            val written = count()
            val tokens = sum(query.tokens)
            rows { listOf(it[user].id, it[author].id, it[written], it[tokens]) }
        }

        // m3 has an owner and no author, so it is in no group.
        assertEquals(listOf(listOf("alice", "b1", 1L, 20L), listOf("alice", "t1", 1L, 10L), listOf("bob", "b1", 1L, 7L)), rows)
    }

    @Test
    fun `two path keys may be reached over the same relationship`() {
        chat()

        val rows = messages {
            val user = key(path<User>(*owner))
            val session = key(path<Session>(Hop("HAS_MESSAGE", Direction.INCOMING)))
            val all = count()
            rows { Triple(it[user].id, it[session].id, it[all]) }
        }

        assertEquals(listOf(Triple("alice", "s1", 3L), Triple("bob", "s2", 1L)), rows)
    }

    @Test
    fun `a path does not walk back over the relationship it came by`() {
        chat()

        val rows = messages {
            val sibling = key(path<Message>(Hop("HAS_MESSAGE", Direction.INCOMING), Hop("HAS_MESSAGE")))
            val others = count()
            rows { it[sibling].id to it[others] }
        }

        // A message is no sibling of its own, and m4 is alone in its session.
        assertEquals(listOf("m1" to 2L, "m2" to 2L, "m3" to 2L), rows)
    }

    @Test
    fun `a test against a list goes with a path key, in the where and in a filter`() {
        chat()

        val rows = messages {
            where { query.tokens inList listOf(10L, 20L, 5L, 7L) }
            val user = key(path<User>(*owner))
            val all = count()
            val big = count { query.tokens inList listOf(20L, 7L) }
            val small = sum(query.tokens) { query.tokens inList listOf(5L, 10L) }
            rows { listOf(it[user].id, it[all], it[big], it[small]) }
        }

        assertEquals(listOf(listOf("alice", 3L, 1L, 15L), listOf("bob", 1L, 1L, 0L)), rows)
    }

    @Test
    fun `a node key is hydrated once for a row`() {
        chat()

        val same = messages {
            val user = key(path<User>(*owner))
            rows { it[user] === it[user] }
        }

        assertEquals(listOf(true, true), same)
    }

    @Test
    fun `a node key of a sealed class reads as its subtype, with its renamed property, its bag and its labels`() {
        stateless.save(Team("t1", "Docs", mapOf("region" to "emea")))
        stateless.save(Bot("b1", "small"))
        stateless.saveAll(listOf(Message("m1", Role.USER), Message("m2", Role.USER), Message("m3", Role.ASSISTANT)))
        relate(nodeRef<Message>("m1"), "AUTHORED_BY", nodeRef<Owner>("t1"))
        relate(nodeRef<Message>("m2"), "AUTHORED_BY", nodeRef<Owner>("t1"))
        relate(nodeRef<Message>("m3"), "AUTHORED_BY", nodeRef<Owner>("b1"))

        val rows = messages {
            val author = key(path<Owner>(Hop("AUTHORED_BY")))
            val written = count()
            rows { it[author] to it[written] }
        }

        assertEquals(listOf<Pair<Owner, Long>>(Bot("b1", "small") to 1L, Team("t1", "Docs", mapOf("region" to "emea")) to 2L), rows)
    }

    @Test
    fun `a hop of either direction is walked both ways`() {
        chat()

        val rows = messages {
            val session = key(path<Session>(Hop("HAS_MESSAGE", Direction.UNDIRECTED)))
            val all = count()
            rows { it[session].id to it[all] }
        }

        assertEquals(listOf("s1" to 3L, "s2" to 1L), rows)
    }

    @Test
    fun `three path keys are a compound key of nodes`() {
        chat()
        stateless.save(Team("t1", "Docs"))
        stateless.save(Bot("b1", "small"))
        relate(nodeRef<Message>("m1"), "AUTHORED_BY", nodeRef<Owner>("t1"))
        relate(nodeRef<Message>("m2"), "AUTHORED_BY", nodeRef<Owner>("t1"))
        relate(nodeRef<Message>("m4"), "AUTHORED_BY", nodeRef<Owner>("b1"))

        val rows = messages {
            val user = key(path<User>(*owner))
            val session = key(path<Session>(Hop("HAS_MESSAGE", Direction.INCOMING)))
            val author = key(path<Owner>(Hop("AUTHORED_BY")))
            val all = count()
            val tokens = sum(query.tokens)
            rows { listOf(it[user].id, it[session].id, it[author].id, it[all], it[tokens]) }
        }

        // m3 has no author, so it reaches two of the three and is in no group.
        assertEquals(listOf(listOf("alice", "s1", "t1", 2L, 30L), listOf("bob", "s2", "b1", 1L, 7L)), rows)
    }

    @Test
    fun `a test of the labels goes in the where and in a filter, with a path key and without`() {
        stateless.save(Team("t1", "Docs"))
        stateless.save(Bot("b1", "small"))
        stateless.saveAll(listOf(Message("m1", Role.USER), Message("m2", Role.USER), Message("m3", Role.ASSISTANT)))
        relate(nodeRef<Message>("m1"), "AUTHORED_BY", nodeRef<Owner>("t1"))
        relate(nodeRef<Message>("m2"), "AUTHORED_BY", nodeRef<Owner>("t1"))
        relate(nodeRef<Message>("m3"), "AUTHORED_BY", nodeRef<Owner>("b1"))

        fun <R> owners(spec: GroupQuerySpec<OwnerQueryDsl>.() -> GroupRows<R>): List<R> = groups.load(Owner::class.java, OwnerQueryDsl.INSTANCE, spec)

        val byMessage = owners {
            where { query.hasAnyLabel("GroupTeam", "GroupBot") }
            val message = key(path<Message>(Hop("AUTHORED_BY", Direction.INCOMING)))
            val teams = count { query.instanceOf<Team>() }
            val bots = count { query.hasAnyLabel("GroupBot") }
            rows { Triple(it[message].id, it[teams], it[bots]) }
        }
        assertEquals(listOf(Triple("m1", 1L, 0L), Triple("m2", 1L, 0L), Triple("m3", 0L, 1L)), byMessage)

        val teamsOnly = owners {
            where { query.instanceOf<Team>() }
            val message = key(path<Message>(Hop("AUTHORED_BY", Direction.INCOMING)))
            rows { it[message].id }
        }
        assertEquals(listOf("m1", "m2"), teamsOnly)

        val byId = owners {
            where { query.hasAnyLabel("GroupTeam", "GroupBot") }
            val id = key(query.id)
            val teams = count { query.instanceOf<Team>() }
            val bots = count { query.hasAnyLabel("GroupBot") }
            rows { Triple(it[id], it[teams], it[bots]) }
        }
        assertEquals(listOf(Triple("b1", 0L, 1L), Triple("t1", 1L, 0L)), byId)
    }

    // ----- Either manager, and one statement -----

    @Test
    fun `the manager that tracks what it loads groups as the stateless one does, and tracks a node key`() {
        chat()
        val mapper = Neo4jObjectMapper.instance
        val sessions = SessionManager(mapper)
        @Suppress("DEPRECATION")
        val stateful = GraphObjectManager(pm, sessions, mapper, SubtypeRegistry())

        val rows = stateful.groups.load(Message::class.java, MessageQueryDsl.INSTANCE) {
            where { query.role eq Role.USER }
            val user = key(path<User>(*owner))
            val written = count()
            val tokens = sum(query.tokens)
            orderBy { written.desc() }
            rows { Triple(it[user], it[written], it[tokens]) }
        }

        assertEquals(listOf(Triple(User("alice", "al"), 2L, 30L), Triple(User("bob"), 1L, 7L)), rows)
        // A node key is an object the manager loaded, so a save of it writes what has changed since.
        assertEquals(listOf(true, true), listOf("alice", "bob").map { sessions.isTracked(User::class.java, it) })
        assertEquals(mapOf(Role.ASSISTANT to 1L, Role.USER to 4L), stateful.groups.countBy(Message::role))
    }

    @Test
    fun `a grouped read is one statement, whatever it groups by`() {
        chat()
        stateless.save(Team("t1", "Docs", mapOf("region" to "emea")))
        relate(nodeRef<Message>("m1"), "AUTHORED_BY", nodeRef<Owner>("t1"))
        val counting = StatementCounter(pm)
        val counted = StatelessGraphObjectManager(counting, Neo4jObjectMapper.instance, SubtypeRegistry()).groups

        counted.load(Message::class.java, MessageQueryDsl.INSTANCE) {
            where { query.role eq Role.USER }
            val user = key(path<User>(*owner))
            val author = key(path<Owner>(Hop("AUTHORED_BY")))
            val role = key(query.role)
            val written = count { query.tokens gte 10L }
            having { written gte 1 }
            orderBy { written.desc() }
            limit(5)
            rows { listOf(it[user], it[author], it[role], it[written]) }
        }
        assertEquals(1, counting.statements)

        counted.countBy(Message::role)
        assertEquals(2, counting.statements)
    }

    // ----- What is refused -----

    @Test
    fun `a read that cannot be grouped is refused before anything is sent`() {
        fun refused(block: () -> Unit): String = assertFailsWith<IllegalArgumentException> { block() }.message!!

        assertContains(refused { feedback { count(); rows { it } } }, "declares no key")
        assertContains(
            refused {
                groups.load(SessionView::class.java, SessionViewQueryDsl.INSTANCE) {
                    val id = key(query.session)
                    rows { it[id] }
                }
            },
            "SessionView is a @GraphView",
        )
        assertContains(refused { feedback { val tags = key(query.tags); rows { it[tags] } } }, "'tags' of Feedback holds several values")
        assertContains(
            refused {
                groups.load(Feedback::class.java, SessionViewQueryDsl.INSTANCE) {
                    val id = key(query.session)
                    rows { it[id] }
                }
            },
            "names 'session'",
        )
        assertContains(
            refused {
                groups.load(Message::class.java, SessionViewQueryDsl.INSTANCE) {
                    val role = key(query.messages.role)
                    rows { it[role] }
                }
            },
            "names 'messages'",
        )
        val own = PropertyReference<String>("n", "id")
        assertContains(
            refused {
                groups.load(Message::class.java, SessionViewQueryDsl.INSTANCE) {
                    where { query.messages.role eq Role.USER }
                    val id = key(own)
                    rows { it[id] }
                }
            },
            "The where of a grouped read of Message names 'messages'",
        )
        assertContains(
            refused {
                groups.load(Message::class.java, SessionViewQueryDsl.INSTANCE) {
                    val id = key(own)
                    count { query.messages.any { role eq Role.USER } }
                    rows { it[id] }
                }
            },
            "An aggregate's filter in a grouped read of Message names the relationship 'messages'",
        )
        assertContains(
            refused { feedback { val page = key(query.page); having(count(), ComparisonOperator.NOT_IN, listOf(1)); rows { it[page] } } },
            "NOT_IN is none of them",
        )
        assertContains(refused { feedback { val page = key(query.page); min(query.tags); rows { it[page] } } }, "'tags' of Feedback holds several values, and min reads one")
        assertContains(refused { messages { val to = key(path<User>()); rows { it[to] } } }, "has no hops")
        assertContains(refused { messages { val to = key(path<User>(Hop("OWNED_BY", label = "Admin"))); rows { it[to] } } }, "asks for the label 'Admin'")
        assertContains(refused { messages { val to = key(path<SessionView>(Hop("X"))); rows { it[to] } } }, "ends at a @NodeFragment")
        assertContains(refused { groups.countBy(Feedback::class.java, "nothing") }, "no field 'nothing'")
        assertContains(refused { messages { val to = key(path<Tag>(Hop("TAGGED"))); rows { it[to] } } }, "Tag has no @NodeId field")
        assertContains(refused { feedback { val page = key(query.page); count { }; rows { it[page] } } }, "An aggregate's filter holds no condition")
        assertContains(refused { feedback { val page = key(query.page); countWhere(); rows { it[page] } } }, "countWhere needs a condition")
        assertContains(refused { feedback { val page = key(query.page); filtered(count()); rows { it[page] } } }, "filtered needs a condition")
        assertContains(refused { feedback { val page = key(query.page); limit(-1); rows { it[page] } } }, "limit must be >= 0")
        assertContains(refused { feedback { val page = key(query.page); skip(-1); rows { it[page] } } }, "skip must be >= 0")
    }

    @Test
    fun `key reports a node with no value when its row is read, and points to keyOrNull`() {
        votes()
        // No field declares it, so nothing says it may be null until a node without it is read.
        val undeclared = PropertyReference<String>("n", "nothing")

        val refused = assertFailsWith<IllegalStateException> {
            feedback { val nothing = key(undeclared); rows { it[nothing] } }
        }
        assertContains(refused.message!!, "A Feedback has no value for 'nothing', which key(…) reads as never null. Use keyOrNull(…).")

        assertEquals(listOf<String?>(null), feedback { val nothing = keyOrNull(undeclared); rows { it[nothing] } })
    }

    @Test
    fun `a column is declared before rows, and not as a row is read`() {
        votes()

        val refused = assertFailsWith<IllegalStateException> {
            feedback { val page = key(query.page); rows { it[page] to it[count()] } }
        }

        assertContains(refused.message!!, "A key or an aggregate is declared before rows { }")
    }

    @Test
    fun `every clause is given before rows, and not as a row is read`() {
        votes()

        fun late(clause: GroupQuerySpec<FeedbackQueryDsl>.(Aggregate<Long>) -> Unit): String = assertFailsWith<IllegalStateException> {
            feedback { val page = key(query.page); val votes = count(); rows { clause(votes); it[page] } }
        }.message!!

        assertContains(late { where { query.helpful eq true } }, "A where is given before rows { }")
        assertContains(late { votes -> having { votes gte 3 } }, "A having is given before rows { }")
        assertContains(late { votes -> orderBy { votes.desc() } }, "An order is given before rows { }")
        assertContains(late { limit(1) }, "A limit is given before rows { }")
        assertContains(late { skip(1) }, "A skip is given before rows { }")
        assertContains(late { onlyIf(query.helpful.isEqualTo(true)) }, "A where is given before rows { }")
        assertContains(late { votes -> having(votes, ComparisonOperator.EQUALS, 3) }, "A having is given before rows { }")
        assertContains(late { votes -> filtered(votes, query.helpful.isEqualTo(true)) }, "An aggregate's filter is given before rows { }")
    }

    @Test
    fun `a handle of another read is not a column of this one`() {
        votes()
        lateinit var other: Key<String>
        feedback { other = key(query.page); rows { it[other] } }

        val refused = assertFailsWith<IllegalArgumentException> {
            feedback { key(query.page); rows { it[other] } }
        }

        assertContains(refused.message!!, "declared in another grouped read")
        assertNull(refused.cause)
    }

    @Test
    fun `a having and an order take the handles of their own read only`() {
        lateinit var elsewhere: Aggregate<Long>
        feedback { val page = key(query.page); elsewhere = count(); rows { it[page] } }

        val filtered = assertFailsWith<IllegalArgumentException> {
            feedback { val page = key(query.page); count(); having { elsewhere gte 1 }; rows { it[page] } }
        }
        val ordered = assertFailsWith<IllegalArgumentException> {
            feedback { val page = key(query.page); count(); orderBy { elsewhere.desc() }; rows { it[page] } }
        }

        assertContains(filtered.message!!, "A having of a grouped read of Feedback compares an aggregate that was declared in another grouped read")
        assertContains(ordered.message!!, "The order of a grouped read of Feedback names a column that was declared in another grouped read")
    }
}

class GroupsNeo4jTest : GroupsContract() {
    companion object {
        private const val PASSWORD = SharedNeo4j.PASSWORD

        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-groups", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
}

class GroupsFalkorDbTest : GroupsContract() {
    companion object {
        private const val GRAPH = "groupsone"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-groups", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
}

class GroupsMemgraphTest : GroupsContract() {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-groups", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            manager = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: NonTransactionalPersistenceManager get() = manager
}

@OracleTests
class GroupsOracleTest : GroupsContract() {
    override val pm: PersistenceManager = OracleTestDatabase.fixtures(OracleTestDatabase.graphManager("contracts"))
}

/** Counts the statements a manager sends. */
private class StatementCounter(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    var statements = 0

    override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = delegate.query(spec).also { statements++ }

    override fun execute(spec: QuerySpecification<*>) = delegate.execute(spec).also { statements++ }

    override fun <T : Any> getOne(spec: QuerySpecification<T>): T = delegate.getOne(spec).also { statements++ }

    override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? = delegate.maybeGetOne(spec).also { statements++ }
}
