package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.StaleObjectException
import org.drivine.annotation.Direction
import org.drivine.annotation.Hop
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.NodeReference
import org.drivine.test.SqlCypherFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import sample.idkind.KeyedAny
import sample.idkind.KeyedBoolean
import sample.idkind.KeyedDate
import sample.idkind.KeyedDecimal
import sample.idkind.KeyedDouble
import sample.idkind.KeyedDuration
import sample.idkind.KeyedEnum
import sample.idkind.KeyedInstant
import sample.idkind.KeyedInt
import sample.idkind.KeyedLocalDateTime
import sample.idkind.KeyedLocalTime
import sample.idkind.KeyedLong
import sample.idkind.KeyedOffsetDateTime
import sample.idkind.KeyedOffsetTime
import sample.idkind.KeyedPeriod
import sample.idkind.KeyedSub
import sample.idkind.KeyedText
import sample.idkind.KeyedUtilDate
import sample.idkind.KeyedUuid
import sample.idkind.KeyedView
import sample.idkind.KeyedZonedDateTime
import sample.idkind.Owned
import sample.idkind.OwnedAny
import sample.idkind.OwnedByFragment
import sample.idkind.OwnedByPath
import sample.idkind.OwnedNested
import sample.idkind.Owner
import sample.idkind.Ownership
import sample.idkind.RenamedId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A node whose `@NodeId` a SQL store holds as a temporal tag, loaded by a class whose declared id can't save the tag back
 * (liberation-data/drivine4j#860, #861): every read that maps the node into a model refuses it, by name, before anything is
 * written. Before, the load gave the id as a value whose own tag is another (the tag's text for a `String`, a `LocalDate`
 * for a datetime), a save's lookup missed the node, and the save made a second node, with no error. An id declared `Any`
 * is loaded as the text, and saved back as the tag it was loaded from while it is unchanged; an id declared the type that
 * holds the tag exactly loads and saves back as before.
 *
 * Each SQL store's test gives a graph of its own.
 */
abstract class IdKindContract {

    /** A manager for the graph this store's test was given, its tables made. */
    protected abstract val pm: NonTransactionalPersistenceManager

    private val gom by lazy { StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry()) }

    private fun cypher(statement: String): List<Any?> = SqlCypherFixtures.run(pm, statement)

    @BeforeEach
    fun clear() = SqlCypherFixtures.clear(pm)

    /**
     * The owner, owning one Keyed node whose id [literal] gives: saved by the object manager with a text id, so its vector
     * and text are written as a save writes them, then given the id by Cypher, as another writer would.
     */
    private fun reset(literal: String) {
        SqlCypherFixtures.clear(pm)
        gom.save(
            OwnedByFragment(
                Owner("o", "owner"),
                listOf(Ownership("always", KeyedText("seed", "x", body = "zebra grass", embedding = listOf(1f, 0f)))),
            ),
        )
        cypher("MATCH (n:IdKindRun:Keyed {id: 'seed'}) SET n.id = $literal")
    }

    /** Every node and relationship of the run, as Cypher reads them: the same before and after a refused read. */
    private fun stored(): List<Any?> =
        cypher("MATCH (n:IdKindRun) RETURN [labels(n), n.id, n.name] AS node ORDER BY n.name") +
            cypher("MATCH (:IdKindRun)-[r]->(:IdKindRun) RETURN [type(r), r.since] AS edge")

    private fun keyedCount(): Long = gom.count(KeyedAny::class.java)

    /** How many Keyed nodes hold their id as [literal] gives it: the tag, and not its text. */
    private fun heldAs(literal: String): Long = (cypher("MATCH (n:IdKindRun:Keyed) WHERE n.id = $literal RETURN count(n)").single() as Number).toLong()

    private fun ownsEdges(): Long = (cypher("MATCH (:IdKindRun)-[r:OWNS]->(:IdKindRun) RETURN count(r)").single() as Number).toLong()

    /** The query object a grouped read of owners is given: it reads no property of its own. */
    private object Owners : NodeReference {
        override val nodeAlias: String = "n"
    }

    // ----- Declared a non-temporal scalar (#860) -----

    @ParameterizedTest(name = "held as {0}")
    @MethodSource("kinds")
    fun `refuses every read that maps the node, by name, and writes nothing`(literal: String, kind: String, text: String) {
        reset(literal)
        val asText = refusal("KeyedText", "a String", text, kind)
        val reads: List<Triple<String, () -> Any?, String>> = listOf(
            Triple("loadAll", { gom.loadAll(KeyedText::class.java) }, asText),
            Triple("loadAll with a where", { gom.loadAll(KeyedText::class.java, "n.name = 'x'") }, asText),
            Triple("loadAll of a Long id", { gom.loadAll(KeyedLong::class.java) }, refusal("KeyedLong", "a Long", text, kind)),
            Triple("loadAll of an Int id", { gom.loadAll(KeyedInt::class.java) }, refusal("KeyedInt", "an Int", text, kind)),
            Triple("loadAll of a Double id", { gom.loadAll(KeyedDouble::class.java) }, refusal("KeyedDouble", "a Double", text, kind)),
            Triple("loadAll of a BigDecimal id", { gom.loadAll(KeyedDecimal::class.java) }, refusal("KeyedDecimal", "a BigDecimal", text, kind)),
            Triple("loadAll of a Boolean id", { gom.loadAll(KeyedBoolean::class.java) }, refusal("KeyedBoolean", "a Boolean", text, kind)),
            Triple("loadAll of a UUID id", { gom.loadAll(KeyedUuid::class.java) }, refusal("KeyedUuid", "a UUID", text, kind)),
            Triple("loadAll of an enum id", { gom.loadAll(KeyedEnum::class.java) }, refusal("KeyedEnum", "a Code", text, kind)),
            Triple("loadNearest", { gom.loadNearest(KeyedText::class.java, listOf(1f, 0f), 1) }, asText),
            Triple("loadMatching", { gom.loadMatching(KeyedText::class.java, "zebra", 1) }, asText),
            Triple("edges.loadRelated", { gom.edges.loadRelated(NodeRef(Owner::class.java, "o"), "OWNS", Direction.OUTGOING, KeyedText::class.java) }, asText),
            Triple("a grouped read's path key", {
                gom.groups.load(Owner::class.java, Owners) {
                    val keyed = key(path<KeyedText>(Hop("OWNS")))
                    count()
                    rows { it[keyed] }
                }
            }, asText),
            Triple("a view's root", { gom.loadAll(KeyedView::class.java) }, asText),
            Triple("a view's related node, by load", { gom.load("o", Owned::class.java) }, asText),
            Triple("a view's related node, by loadAll", { gom.loadAll(Owned::class.java) }, asText),
            Triple("a nested view", { gom.load("o", OwnedNested::class.java) }, asText),
            Triple("a relationship fragment's node", { gom.load("o", OwnedByFragment::class.java) }, asText),
            Triple("a path's node", { gom.load("o", OwnedByPath::class.java) }, asText),
            Triple("update's load", { gom.update("o", Owned::class.java) { it.copy(owner = it.owner.copy(name = "changed")) } }, asText),
        )
        reads.forEach { (path, read, message) ->
            val before = stored()
            val failure = assertFailsWith<DrivineException>(path) { read() }
            assertEquals(message, failure.message, path)
            assertEquals(before, stored(), "$path wrote nothing")
        }
    }

    @ParameterizedTest(name = "held as {0}")
    @MethodSource("kinds")
    fun `refuses an id stored under another name, and one a subclass inherits, by the field`(literal: String, kind: String, text: String) {
        cypher("CREATE (:IdKindRun:Renamed {key: $literal}), (:IdKindRun:Base:Sub {id: $literal})")
        assertEquals(refusal("RenamedId", "a String", text, kind), assertFailsWith<DrivineException> { gom.loadAll(RenamedId::class.java) }.message)
        assertEquals(refusal("KeyedSub", "a String", text, kind), assertFailsWith<DrivineException> { gom.loadAll(KeyedSub::class.java) }.message)
    }

    @ParameterizedTest(name = "held as {0}")
    @MethodSource("kinds")
    fun `made a second node when an object as the load gave it was saved, which the refusal prevents`(literal: String, kind: String, text: String) {
        reset(literal)
        // The object a load gave before #860: the id the tag's text, which the save's lookup misses.
        gom.save(KeyedText(text, "resaved"))
        assertEquals(2, keyedCount())
        // A load by the text finds no node, so it has nothing to refuse.
        reset(literal)
        assertEquals(null, gom.load(text, KeyedText::class.java))
        assertEquals(1, keyedCount())
    }

    @Test
    fun `loads a text id as it always has`() {
        reset("'2026-01-05'")
        val loaded = gom.loadAll(KeyedText::class.java).single()
        assertEquals("2026-01-05", loaded.id)
        gom.save(loaded.copy(name = "resaved"))
        assertEquals(1, keyedCount())
    }

    // ----- Declared Any (#861) -----

    @ParameterizedTest(name = "held as {0}")
    @MethodSource("kinds")
    fun `loads an id declared Any as the text, and saves it back onto the same node, held as it was`(literal: String, kind: String, text: String) {
        reset(literal)
        val loaded = gom.loadAll(KeyedAny::class.java).single()
        assertEquals(text, loaded.id)
        gom.save(loaded.copy(name = "resaved"))
        assertEquals(1, keyedCount())
        assertEquals(1, heldAs(literal))
        gom.saveAll(listOf(gom.loadAll(KeyedAny::class.java).single().copy(name = "batched")))
        assertEquals(1, keyedCount())
        assertEquals(1, heldAs(literal))
        assertEquals(listOf("batched"), gom.loadAll(KeyedAny::class.java).map { it.name })
    }

    @ParameterizedTest(name = "held as {0}")
    @MethodSource("kinds")
    fun `saves a view's related node whose id is declared Any onto the same node, by update too`(literal: String, kind: String, text: String) {
        reset(literal)
        val view = gom.load("o", OwnedAny::class.java)!!
        gom.save(view.copy(keyed = view.keyed.map { it.copy(name = "related") }))
        gom.update("o", OwnedAny::class.java) { it.copy(owner = it.owner.copy(name = "renamed"), keyed = it.keyed.map { k -> k.copy(name = "updated") }) }
        assertEquals(1, keyedCount())
        assertEquals(1, ownsEdges())
        assertEquals(1, heldAs(literal))
        assertEquals(listOf("updated"), gom.load("o", OwnedAny::class.java)!!.keyed.map { it.name })
    }

    @ParameterizedTest(name = "held as {0}")
    @MethodSource("kinds")
    fun `finds the node by an id declared Any as it was loaded, to patch, load, relate, unlink and delete it`(literal: String, kind: String, text: String) {
        reset(literal)
        val loaded = gom.loadAll(KeyedAny::class.java).single()
        assertEquals(listOf(PatchResult(found = true, applied = true, changed = true)), gom.patchAll(listOf(loaded.copy(name = "patched")), only = setOf(KeyedAny::name)))
        assertEquals("patched", gom.load(loaded.id.toString(), KeyedAny::class.java)?.name)
        assertTrue(gom.edges.relate(NodeRef(Owner::class.java, "o"), NodeRef(KeyedAny::class.java, loaded.id), "LIKES"))
        assertEquals(1, gom.edges.unrelate(NodeRef(Owner::class.java, "o"), NodeRef(KeyedAny::class.java, loaded.id), "LIKES"))
        // An update that drops the related node unlinks it, as the load gave its id.
        gom.update("o", OwnedAny::class.java) { it.copy(keyed = emptyList()) }
        assertEquals(0, ownsEdges())
        assertEquals(1, gom.delete(loaded.id.toString(), KeyedAny::class.java))
        assertEquals(0, keyedCount())
    }

    @ParameterizedTest(name = "held as {0}")
    @MethodSource("kinds")
    fun `finds a stale save's node by the tag an id declared Any was loaded from`(literal: String, kind: String, text: String) {
        reset(literal)
        val first = gom.loadAll(KeyedAny::class.java).single()
        val second = gom.loadAll(KeyedAny::class.java).single()
        gom.save(first.copy(name = "first"))
        val stale = assertFailsWith<StaleObjectException> { gom.save(second.copy(name = "second")) }
        assertFalse(stale.deleted)
        assertEquals(1, keyedCount())
    }

    @ParameterizedTest(name = "held as {0}")
    @MethodSource("kinds")
    fun `writes an id declared Any changed since the load, or given afresh as the text, as given`(literal: String, kind: String, text: String) {
        reset(literal)
        val loaded = gom.loadAll(KeyedAny::class.java).single()
        gom.save(loaded.copy(id = "changed", name = "moved", stamp = null))
        assertEquals(2, keyedCount())
        assertEquals(1, heldAs("'changed'"))
        // The same text, not read from the node: the text's node, never the tag's.
        gom.save(KeyedAny(String(text.toCharArray()), "fresh"))
        assertEquals(3, keyedCount())
        assertEquals(1, heldAs(literal))
    }

    @Test
    fun `saves a date's node and a text's node of the same text, both loaded into Any, each onto its own node`() {
        cypher("CREATE (:IdKindRun:Keyed {id: date('2026-01-05'), name: 'date'}), (:IdKindRun:Keyed {id: '2026-01-05', name: 'text'})")
        val loaded = gom.loadAll(KeyedAny::class.java).sortedBy { it.name }
        assertEquals(listOf("date", "text"), loaded.map { it.name })
        gom.saveAll(loaded.map { it.copy(name = "${it.name}-batched") })
        gom.loadAll(KeyedAny::class.java).forEach { gom.save(it.copy(name = "${it.name}-saved")) }
        assertEquals(2, keyedCount())
        assertEquals(listOf("date-batched-saved"), cypher("MATCH (n:IdKindRun:Keyed) WHERE n.id = date('2026-01-05') RETURN n.name"))
        assertEquals(listOf("text-batched-saved"), cypher("MATCH (n:IdKindRun:Keyed) WHERE n.id = '2026-01-05' RETURN n.name"))
    }

    // ----- Declared a java.time type (#861) -----

    @ParameterizedTest(name = "{1} over {0}")
    @MethodSource("exact")
    fun `loads a java_time id that saves its tag back exactly, onto the same node`(literal: String, type: Class<*>) {
        reset(literal)
        val loaded = gom.loadAll(type).single()
        val copy = loaded.javaClass.methods.single { it.name == "copy" && it.parameterCount == 2 }
        gom.save(copy.invoke(loaded, loaded.javaClass.getMethod("getId").invoke(loaded), "resaved"))
        assertEquals(1, keyedCount())
        assertEquals(1, heldAs(literal))
    }

    @ParameterizedTest(name = "{1} over {0}")
    @MethodSource("inexact")
    fun `refuses a java_time id that can't save its tag back, by name, and writes nothing`(literal: String, type: Class<*>, message: String) {
        reset(literal)
        val before = stored()
        assertEquals(message, assertFailsWith<DrivineException> { gom.loadAll(type) }.message)
        assertEquals(before, stored())
    }

    @Test
    fun `refuses a time id at an offset with seconds as a save does, and writes nothing`() {
        // A zone's local mean time, as Europe/Dublin's before 1916: Cypher has no such offset, and the module refuses its text
        // (liberation-data/drivine-cypher-spec#357). The write plan's key refuses it as the save does, not with the module's
        // own exception (#840).
        val lmt = OffsetDateTime.of(1971, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHoursMinutesSeconds(0, -44, -30))
        val refused = assertFailsWith<DrivineException> { gom.save(KeyedOffsetDateTime(lmt, "lmt")) }
        assertTrue(refused.message.orEmpty().startsWith("A value given as a DATE_TIME isn't one: "), "$refused")
        assertTrue(refused.message.orEmpty().endsWith("(the store holds an offset to the minute)."), "$refused")
        assertEquals(0, keyedCount())
    }

    @Test
    fun `made a second node when a LocalDate id over a localdatetime was saved, which the refusal prevents`() {
        reset("localdatetime('2026-01-05T10:11:12')")
        // The object a load gave before #861: Jackson keeps the date of the datetime's text, which binds a date.
        gom.save(KeyedDate(LocalDate.parse("2026-01-05"), "resaved"))
        assertEquals(2, keyedCount())
        reset("date('2026-01-05')")
        gom.save(KeyedLocalDateTime(LocalDateTime.parse("2026-01-05T00:00"), "resaved"))
        assertEquals(2, keyedCount())
        assertTrue(gom.loadAll(KeyedAny::class.java).map { it.id }.containsAll(listOf("2026-01-05", "2026-01-05T00:00:00")))
    }

    companion object {
        /** Each temporal kind an id may be held as, as Cypher writes it, its tag's kind and the text a read gives it as. */
        @JvmStatic
        fun kinds(): List<Arguments> = listOf(
            Arguments.of("date('2026-01-05')", "date", "2026-01-05"),
            Arguments.of("datetime('2026-01-05T10:11:12Z')", "datetime", "2026-01-05T10:11:12Z"),
            Arguments.of("datetime('2026-01-05T10:11:12+02:00')", "datetime", "2026-01-05T10:11:12+02:00"),
            Arguments.of("localdatetime('2026-01-05T10:11:12')", "localdatetime", "2026-01-05T10:11:12"),
            Arguments.of("time('10:11:12+02:00')", "time", "10:11:12+02:00"),
            Arguments.of("localtime('10:11:12')", "localtime", "10:11:12"),
            Arguments.of("duration('P1DT2H')", "duration", "P1DT2H"),
        )

        /** Each java.time id whose save writes the tag it was loaded from again: of its kind, and of its text. */
        @JvmStatic
        fun exact(): List<Arguments> = listOf(
            Arguments.of("date('2026-01-05')", KeyedDate::class.java),
            Arguments.of("localdatetime('2026-01-05T10:11:12')", KeyedLocalDateTime::class.java),
            Arguments.of("localdatetime('2026-01-05T00:00')", KeyedLocalDateTime::class.java),
            Arguments.of("datetime('2026-01-05T10:11:12Z')", KeyedInstant::class.java),
            Arguments.of("datetime('2026-01-05T10:11:12.123456789Z')", KeyedInstant::class.java),
            Arguments.of("datetime('2026-01-05T10:11:12Z')", KeyedOffsetDateTime::class.java),
            Arguments.of("datetime('2026-01-05T10:11:12.123456789Z')", KeyedOffsetDateTime::class.java),
            Arguments.of("datetime('2026-01-05T10:11:12Z')", KeyedZonedDateTime::class.java),
            Arguments.of("datetime('2026-01-05T10:11:12.123Z')", KeyedUtilDate::class.java),
            Arguments.of("localtime('10:11:12')", KeyedLocalTime::class.java),
            Arguments.of("localtime('10:11:12.123456789')", KeyedLocalTime::class.java),
        )

        /**
         * Each java.time id a save would write another tag of: of another kind, at UTC where the node held an offset, to the
         * millisecond where it held a finer fraction, or not as a tag at all (an `OffsetTime`, a `Duration` and a `Period`
         * are written as their text or a number). A pair Jackson can't read is refused so too.
         */
        @JvmStatic
        fun inexact(): List<Arguments> = listOf(
            inexact("localdatetime('2026-01-05T10:11:12')", KeyedDate::class.java, "a LocalDate", "2026-01-05T10:11:12", "a localdatetime", "a LocalDateTime"),
            inexact("datetime('2026-01-05T10:11:12Z')", KeyedDate::class.java, "a LocalDate", "2026-01-05T10:11:12Z", "a datetime", "an OffsetDateTime"),
            inexact("datetime('2026-01-05T10:11:12Z')", KeyedLocalDateTime::class.java, "a LocalDateTime", "2026-01-05T10:11:12Z", "a datetime", "an OffsetDateTime"),
            inexact("date('2026-01-05')", KeyedLocalDateTime::class.java, "a LocalDateTime", "2026-01-05", "a date", "a LocalDate"),
            inexact("localdatetime('2026-01-05T10:11:12')", KeyedLocalTime::class.java, "a LocalTime", "2026-01-05T10:11:12", "a localdatetime", "a LocalDateTime"),
            inexact("datetime('2026-01-05T10:11:12+02:00')", KeyedInstant::class.java, "an Instant", "2026-01-05T10:11:12+02:00", "a datetime at the offset +02:00", "Any"),
            inexact("datetime('2026-01-05T10:11:12+02:00')", KeyedOffsetDateTime::class.java, "an OffsetDateTime", "2026-01-05T10:11:12+02:00", "a datetime at the offset +02:00", "Any"),
            inexact("datetime('2026-01-05T10:11:12+02:00')", KeyedZonedDateTime::class.java, "a ZonedDateTime", "2026-01-05T10:11:12+02:00", "a datetime at the offset +02:00", "Any"),
            inexact("datetime('2026-01-05T10:11:12.123456789Z')", KeyedUtilDate::class.java, "a Date", "2026-01-05T10:11:12.123456789Z", "a datetime below the millisecond", "an OffsetDateTime"),
            inexact("date('2026-01-05')", KeyedUtilDate::class.java, "a Date", "2026-01-05", "a date", "a LocalDate"),
            inexact("time('10:11:12+02:00')", KeyedOffsetTime::class.java, "an OffsetTime", "10:11:12+02:00", "a time", "Any"),
            inexact("duration('P1DT2H')", KeyedDuration::class.java, "a Duration", "P1DT2H", "a duration", "Any"),
            inexact("duration('P1Y2M')", KeyedPeriod::class.java, "a Period", "P1Y2M", "a duration", "Any"),
            // Pairs Jackson can't read, refused by name before it is asked.
            inexact("localdatetime('2026-01-05T10:11:12')", KeyedInstant::class.java, "an Instant", "2026-01-05T10:11:12", "a localdatetime", "a LocalDateTime"),
            inexact("time('10:11:12+02:00')", KeyedLocalTime::class.java, "a LocalTime", "10:11:12+02:00", "a time", "Any"),
        )

        private fun inexact(literal: String, type: Class<*>, declared: String, text: String, held: String, suggested: String): Arguments =
            Arguments.of(
                literal, type,
                "${type.simpleName}'s @NodeId id is declared $declared, but the node whose id is '$text' holds it as $held, which $declared " +
                    "can't save back: declare id as $suggested to load it.",
            )

        /** #860's message: [declared] with its article. */
        private fun refusal(type: String, declared: String, text: String, kind: String): String =
            "$type's @NodeId id is declared $declared, but the node whose id is '$text' holds it as a $kind: declare id as a $kind to load it."
    }
}
