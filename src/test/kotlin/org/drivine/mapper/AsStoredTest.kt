package org.drivine.mapper

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.PropertyBag
import org.junit.jupiter.api.Test
import sample.stateless.Claim
import sample.stateless.ClaimWithTagsView
import sample.stateless.ClaimWithTags
import sample.stateless.SetProbe
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A stamped node of the three temporal kinds a save wrote as text or a number before liberation-data/drivine4j#862. */
@NodeFragment(labels = ["AsStoredAmounts"])
data class AsStoredAmounts(
    @NodeId val id: String,
    val time: java.time.OffsetTime? = null,
    val duration: java.time.Duration? = null,
    val period: java.time.Period? = null,
    val durations: List<java.time.Duration> = emptyList(),
    val name: String? = null,
    @NodeStamp val stamp: String? = null,
)

/** A stamped node with a property bag of durations, which a save before liberation-data/drivine4j#862 wrote as seconds. */
@NodeFragment(labels = ["AsStoredBagged"])
data class AsStoredBagged(@NodeId val id: String, @PropertyBag val waits: Map<String, java.time.Duration> = emptyMap(), @NodeStamp val stamp: String? = null)

@NodeFragment(labels = ["AsStoredKeyedDuration"])
data class AsStoredKeyedDuration(@NodeId val id: java.time.Duration, @NodeStamp val stamp: String? = null)

class AsStoredTest {

    private val stamp = "0123456789abcdef:fedcba9876543210"

    /** [row] as a load hands it over, mapped to [type]. */
    private fun <T> loaded(type: Class<T>, row: Map<String, Any?>): T =
        TransformPostProcessor<Any, T>(type).apply(listOf(row)).single()

    @Test
    fun `a load keeps the text each packed property held, by field name, beside the fragment and not for an equal one`() {
        val row = mapOf("id" to "c1", "text" to "one", "stamp" to stamp, "tags" to "[\"a\", \"b\"]")
        val claim = loaded(ClaimWithTags::class.java, row)
        val equal = loaded(ClaimWithTags::class.java, row + ("tags" to "[\"a\",\"b\"]"))

        assertEquals(listOf("a", "b"), claim.tags)
        assertEquals(claim, equal)
        assertEquals(mapOf("tags" to "[\"a\", \"b\"]"), AsStored.of(claim, stamp))
        assertEquals(mapOf("tags" to "[\"a\",\"b\"]"), AsStored.of(equal, stamp))
        assertNull(AsStored.of(claim.copy(), stamp), "no load made a copy")
    }

    @Test
    fun `a load of a view keeps the text beside its root`() {
        val view = loaded(ClaimWithTagsView::class.java, mapOf("claim" to mapOf("id" to "c1", "text" to "one", "stamp" to stamp, "tags" to "[\"a\"]"), "people" to emptyList<Any>()))

        assertEquals(mapOf("tags" to "[\"a\"]"), AsStored.of(view.claim, stamp))
    }

    @Test
    fun `the text is given only to a fragment that carries the stamp it was loaded with`() {
        val claim = loaded(ClaimWithTags::class.java, mapOf("id" to "c1", "text" to "one", "stamp" to stamp, "tags" to "plain"))

        assertEquals(mapOf("tags" to "plain"), AsStored.of(claim, stamp))
        assertNull(AsStored.of(claim, "another"))
    }

    @Test
    fun `a property the node held nothing for is kept as none, and a class with no packed field or no stamp keeps nothing`() {
        val claim = loaded(ClaimWithTags::class.java, mapOf("id" to "c1", "text" to "one", "stamp" to stamp, "tags" to null))
        val unpacked = loaded(Claim::class.java, mapOf("id" to "c1", "text" to "one", "stamp" to stamp))
        val unstamped = loaded(ClaimWithTags::class.java, mapOf("id" to "c1", "text" to "one", "tags" to "[]"))

        assertEquals(mapOf("tags" to null), AsStored.of(claim, stamp))
        assertNull(AsStored.of(unpacked, stamp))
        assertNull(AsStored.of(unstamped, null))
    }

    @Test
    fun `a load keeps the list each set property held, in its order and with an item it holds twice, and loads the set in that order`() {
        val probe = loaded(SetProbe::class.java, mapOf("id" to "p1", "name" to "one", "stamp" to stamp, "tags" to listOf("b", "a", "b", "c")))

        assertEquals(listOf("b", "a", "c"), probe.tags.toList())
        assertEquals(mapOf("tags" to listOf("b", "a", "b", "c")), AsStored.of(probe, stamp))
        assertNull(AsStored.of(probe.copy(), stamp), "no load made a copy")
    }

    @Test
    fun `a load keeps what each time with an offset, duration and period property held, as a node saved before 862 holds it or as Cypher wrote it`() {
        // #862: a save compares each field as written, natively, where a node saved before holds the text or the seconds,
        // and where Cypher's duration('P1DT2H') holds a day that Java's PT26H doesn't: neither would ever be the same.
        val days = org.neo4j.driver.Values.isoDuration(0, 1, 7200, 0).asIsoDuration()
        val row = mapOf(
            "id" to "a1", "stamp" to stamp, "name" to "one",
            "time" to "10:11:12+02:00", "duration" to 93600.0, "period" to "P1Y2M", "durations" to listOf(days, 1.5),
        )
        val amounts = loaded(AsStoredAmounts::class.java, row)

        assertEquals(java.time.Duration.ofHours(26), amounts.duration)
        assertEquals(row - "id" - "stamp" - "name", AsStored.of(amounts, stamp))
        // An id is kept as the object holds it: the save finds the node by it.
        val keyed = loaded(AsStoredKeyedDuration::class.java, mapOf("id" to days, "stamp" to stamp))
        assertNull(AsStored.of(keyed, stamp))
    }

    @Test
    fun `a load keeps what a property bag of durations held, as a node saved before 862 holds it`() {
        // A checked save compares a bag's entries with what the load read too, so seconds aren't taken for a change.
        val bagged = loaded(AsStoredBagged::class.java, mapOf("id" to "b1", "stamp" to stamp, "waits.queue" to 180L))

        assertEquals(mapOf("queue" to java.time.Duration.ofMinutes(3)), bagged.waits)
        assertEquals(mapOf("waits" to mapOf("queue" to 180L)), AsStored.of(bagged, stamp))
    }
}
