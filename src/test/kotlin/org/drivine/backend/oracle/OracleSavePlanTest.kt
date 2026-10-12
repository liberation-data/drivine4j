package org.drivine.backend.oracle

import com.fasterxml.jackson.databind.JsonNode
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.manager.NullPolicy
import org.drivine.manager.RemovedTargets
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.FragmentModel
import org.drivine.query.JdbcParameterCoercer
import org.drivine.query.SaveRequest
import org.drivine.query.SaveStatement
import org.drivine.session.SnapshotDigest
import org.junit.jupiter.api.Test
import sample.nodelabels.MemberNode
import sample.nodelabels.Role
import sample.nodelabels.ThingNode
import sample.stateless.Citation
import sample.stateless.Claim
import sample.stateless.ClaimCitations
import sample.stateless.ClaimDossier
import sample.stateless.ClaimPair
import sample.stateless.ClaimRemarks
import sample.stateless.ClaimSupports
import sample.stateless.ClaimView
import sample.stateless.Human
import sample.stateless.HumanClaims
import sample.stateless.Pals
import sample.stateless.Remark
import sample.stateless.Tagged
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A node whose id is a floating-point number, which Oracle reads back from JSON as `3`, not `3.0`. */
@NodeFragment(labels = ["Gauge"])
data class Gauge(@NodeId val id: Double, val name: String)

@GraphView
data class ClaimGauges(
    @Root val claim: Claim,
    @GraphRelationship(type = "READS", direction = Direction.OUTGOING)
    val gauges: List<Gauge> = emptyList(),
)

/** A node with a temporal and a list, which the plan carries as JSON. */
@NodeFragment(labels = ["Beacon"])
data class Beacon(@NodeId val id: String, val seenAt: Instant, val tags: List<String>)

/** A node whose id may be missing, which no save can find a node by. */
@NodeFragment(labels = ["Loose"])
data class Loose(@NodeId val id: String?, val name: String)

/** A node of a class that another extends with a label of its own. */
@NodeFragment(labels = ["Vessel"])
open class Vessel(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["Vessel", "Tanker"])
class Tanker(id: String, name: String, val tonnes: Int) : Vessel(id, name)

@GraphView
data class ClaimVessels(
    @Root val claim: Claim,
    @GraphRelationship(type = "NAMES", direction = Direction.OUTGOING)
    val vessels: List<Vessel> = emptyList(),
)

/** The steps a save on Oracle plans: what `SaveStatementBuilder` decides for the graph engines, written for the write block. */
class OracleSavePlanTest {

    private val planner = OracleSavePlan(OracleGraph("g"), Neo4jObjectMapper.instance)
    private val digests = SnapshotDigest(Neo4jObjectMapper.instance)

    private val stamp = "aaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb"

    private fun build(
        obj: Any,
        checked: Boolean = true,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        replaced: RemovedTargets? = null,
        before: JsonNode? = null,
        after: JsonNode? = null,
        createsRoot: Boolean = true,
        rootWriteFields: Set<String>? = null,
    ): SaveStatement = planner.build(SaveRequest(obj, checked, nullPolicy, rootWriteFields, replaced = { replaced }, before, after, createsRoot))

    @Suppress("UNCHECKED_CAST")
    private fun steps(statement: SaveStatement): List<Map<String, Any?>> = statement.bindings[OracleWriteBlock.PLAN] as List<Map<String, Any?>>

    private fun stepsOf(obj: Any, replaced: RemovedTargets? = null, nullPolicy: NullPolicy = NullPolicy.IGNORE, checked: Boolean = true) =
        steps(build(obj, checked = checked, nullPolicy = nullPolicy, replaced = replaced))

    private fun List<Map<String, Any?>>.ofKind(op: String) = filter { it["op"] == op }

    // The root

    @Test
    fun `a checked save of a fragment expects the node token of the stamp it carries, and says it is the root`() {
        val root = stepsOf(Claim("c1", "one", stamp = stamp)).single()

        assertEquals("node", root["op"])
        assertEquals(true, root["root"])
        assertEquals("aaaaaaaaaaaaaaaa", root["expect"])
        assertEquals(stamp, root["carried"])
        assertFalse("whole" in root, "the node token alone is compared: $root")
        assertFalse("create" in root, "a save makes the root it does not find: $root")
    }

    @Test
    fun `a stamp that is not two tokens is expected whole`() {
        val root = stepsOf(Claim("c1", "one", stamp = "legacy")).single()

        assertEquals("legacy", root["expect"])
        assertEquals(true, root["whole"])
    }

    @Test
    fun `an unchecked save expects nothing, and still says what the object carried`() {
        val root = stepsOf(Claim("c1", "one", stamp = stamp), checked = false).single()

        assertFalse("expect" in root, root.toString())
        assertFalse("whole" in root, root.toString())
        assertEquals(stamp, root["carried"])
        assertEquals(true, root["root"])
    }

    @Test
    fun `a save that does not make the root says so`() {
        val root = steps(build(Claim("c1", "one", stamp = stamp), createsRoot = false)).single()

        assertEquals(false, root["create"])
        assertFalse(build(Claim("c1", "one"), createsRoot = true).createsRoot.not())
    }

    @Test
    fun `the root step carries the node as the block finds it, with its key, its stored id, its labels and the properties to write`() {
        val root = stepsOf(Claim("c1", "one")).single()

        assertEquals(0, root["r"])
        assertEquals(0, root["s"])
        assertEquals("id", root["key"])
        assertEquals("c1", root["id"])
        assertEquals("c1", root["k"])
        assertEquals(listOf("Claim"), root["labels"])
        assertEquals(mapOf("text" to "one"), root["set"])
        assertNotNull(root["fresh"])
    }

    // A view

    @Test
    fun `a view is its root, then each related node and the relationship to it, numbered as the steps are`() {
        val steps = stepsOf(ClaimCitations(Claim("c1", "one", stamp = stamp), cited = listOf(Citation(3, Human("ada", "Ada")), Citation(7, Human("bob", "Bob")))))

        assertEquals(listOf("node", "node", "link", "node", "link"), steps.map { it["op"] })
        val (root, ada, cites, bob, citesBob) = steps
        assertEquals(0, root["r"])
        assertEquals(1, ada["r"])
        assertEquals("ada", ada["k"])
        assertEquals(listOf("Human"), ada["labels"])
        assertFalse("root" in ada, "only the root says it is the root: $ada")
        assertEquals(mapOf("op" to "link", "from" to 0, "to" to 1, "type" to "CITES", "dir" to "OUT", "props" to mapOf("page" to 3)), cites)
        assertEquals(2, bob["r"])
        assertEquals(mapOf("op" to "link", "from" to 0, "to" to 2, "type" to "CITES", "dir" to "OUT", "props" to mapOf("page" to 7)), citesBob)
    }

    @Test
    fun `a relationship property that is null is not written, and a relationship with none has no properties`() {
        val steps = stepsOf(ClaimRemarks(Claim("c1", "one"), remarks = listOf(Remark(null, Human("ada", "Ada")), Remark("fine", Human("bob", "Bob")))))

        val (silent, spoken) = steps.ofKind("link")
        assertFalse("props" in silent, silent.toString())
        assertFalse("clear" in silent, "a save that is not of a change clears nothing: $silent")
        assertEquals(mapOf("note" to "fine"), spoken["props"])

        val plain = stepsOf(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada")))).ofKind("link").single()
        assertFalse("props" in plain, plain.toString())
    }

    @Test
    fun `a relationship is written in the direction its field reads it`() {
        val incoming = stepsOf(HumanClaims(Human("ada", "Ada"), claims = listOf(Claim("c1", "one")))).ofKind("link").single()
        val either = stepsOf(Pals(Human("ada", "Ada"), pals = listOf(Human("bob", "Bob")))).ofKind("link").single()

        assertEquals("IN", incoming["dir"])
        assertEquals("MENTIONS", incoming["type"])
        assertEquals("BOTH", either["dir"])
        assertEquals("KNOWS", either["type"])
    }

    @Test
    fun `two fields over one relationship type find their targets by shape, and the shapes are numbered as they are met`() {
        val steps = stepsOf(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada")), companies = listOf(sample.stateless.Company("acme", "Acme"))))

        // The fields are saved in the order of their names: the companies before the people.
        val shapes = steps.ofKind("node").map { it["labels"] to it["s"] }
        assertEquals(listOf(listOf("Claim") to 0, listOf("Company") to 1, listOf("Human") to 2), shapes)
    }

    // Replacing a list

    @Test
    fun `a replaced list first removes the relationships to every target but those held, and deletes what is left unreferenced`() {
        val statement = build(ClaimSupports(Claim("c1", "one", stamp = stamp), supports = listOf(Claim("k1", "a"), Claim("k2", "b"))), replaced = RemovedTargets.DELETE_UNREFERENCED)
        val steps = steps(statement)

        assertEquals(listOf("node", "unlink", "node", "link", "node", "link"), steps.map { it["op"] })
        val unlink = steps.ofKind("unlink").single()
        assertEquals(0, unlink["from"])
        assertEquals("SUPPORTS", unlink["type"])
        assertEquals("OUT", unlink["dir"])
        assertEquals(0, unlink["s"], "the supported claims are of the root's own shape")
        assertEquals(listOf("k1", "k2"), unlink["ids"])
        assertEquals(true, unlink["keep"])
        assertEquals(true, unlink["delete"])
        // Replacing a list overwrites the root's relationships, so the whole stamp is checked.
        val root = steps.first()
        assertEquals(stamp, root["expect"])
        assertEquals(true, root["whole"])
    }

    @Test
    fun `a replaced list whose dropped targets are kept does not say to delete, and names the nodes another field holds`() {
        val steps = stepsOf(ClaimPair(Claim("c1", "one"), lead = Claim("lead", "L"), supports = listOf(Claim("s1", "S"))), replaced = RemovedTargets.KEEP)

        val unlinks = steps.ofKind("unlink")
        assertEquals(listOf("LED_BY", "SUPPORTS"), unlinks.map { it["type"] })
        unlinks.forEach { assertFalse("delete" in it, it.toString()) }
        unlinks.forEach { assertEquals(true, it["keep"]) }
        val supports = unlinks.last()
        assertEquals(listOf("s1"), supports["ids"])
        assertEquals(listOf("lead", "s1"), supports["held"], "every claim the object holds, in whichever field, is held")

        val deleting = stepsOf(ClaimPair(Claim("c1", "one"), lead = Claim("lead", "L"), supports = listOf(Claim("s1", "S"))), replaced = RemovedTargets.DELETE_UNREFERENCED)
        assertTrue(deleting.ofKind("unlink").all { it["delete"] == true })
    }

    @Test
    fun `a field that holds nothing in a replaced save still removes what the store has`() {
        val steps = stepsOf(ClaimSupports(Claim("c1", "one")), replaced = RemovedTargets.DELETE_UNREFERENCED)

        val unlink = steps.ofKind("unlink").single()
        assertEquals(emptyList<String>(), unlink["ids"])
        assertEquals(true, unlink["keep"])
        assertFalse("held" in unlink, "nothing is held: $unlink")
    }

    @Test
    fun `a save that does not replace removes nothing`() {
        val steps = stepsOf(ClaimSupports(Claim("c1", "one"), supports = listOf(Claim("k1", "a"))))

        assertTrue(steps.ofKind("unlink").isEmpty(), steps.toString())
    }

    // A save of what a change altered

    @Test
    fun `a change removes the relationship to a target that is gone, and leaves one that is as it was loaded`() {
        val loaded = ClaimSupports(Claim("c1", "one", stamp = stamp), supports = listOf(Claim("k1", "a"), Claim("k2", "b")))
        val changed = loaded.copy(supports = listOf(Claim("k2", "b")))
        val steps = steps(build(changed, before = digests.of(loaded), after = digests.of(changed)))

        assertEquals(listOf("node", "unlink"), steps.map { it["op"] }, "k2 is as it was: no step for it")
        val unlink = steps.ofKind("unlink").single()
        assertEquals(listOf("k1"), unlink["ids"])
        assertFalse("keep" in unlink, "the named targets go, not every other: $unlink")
        assertFalse("delete" in unlink, unlink.toString())
        assertFalse("held" in unlink, unlink.toString())
    }

    @Test
    fun `a change writes only the fields of a related node it altered, clears one set to null, and does not join it again`() {
        val loaded = ClaimSupports(Claim("c1", "one", stamp = stamp), supports = listOf(Claim("k2", "b", note = "was")))
        val changed = loaded.copy(supports = listOf(Claim("k2", "b2", note = null)))
        val steps = steps(build(changed, before = digests.of(loaded), after = digests.of(changed)))

        assertEquals(listOf("node", "node"), steps.map { it["op"] })
        val k2 = steps.last()
        assertEquals("k2", k2["k"])
        assertEquals(mapOf("text" to "b2"), k2["set"])
        assertEquals(listOf("note"), k2["unset"])
        assertTrue(steps.ofKind("link").isEmpty(), "the relationship was loaded, and is not made again")
    }

    @Test
    fun `a change joins a related node again only when a property of the relationship differs`() {
        val loaded = ClaimCitations(Claim("c1", "one", stamp = stamp), cited = listOf(Citation(1, Human("ada", "Ada")), Citation(2, Human("bob", "Bob"))))
        val changed = loaded.copy(cited = listOf(Citation(9, Human("ada", "Ada")), Citation(2, Human("bob", "Bob"))))
        val steps = steps(build(changed, before = digests.of(loaded), after = digests.of(changed)))

        assertEquals(listOf("node", "node", "link"), steps.map { it["op"] })
        assertEquals("ada", steps[1]["k"])
        assertFalse("set" in steps[1], "ada is as she was: nothing of her is written: ${steps[1]}")
        val link = steps.last()
        assertEquals(mapOf("page" to 9), link["props"])
        assertEquals(1, link["to"])
    }

    @Test
    fun `a change clears a relationship property set to null`() {
        val loaded = ClaimRemarks(Claim("c1", "one", stamp = stamp), remarks = listOf(Remark("said", Human("ada", "Ada"))))
        val changed = loaded.copy(remarks = listOf(Remark(null, Human("ada", "Ada"))))
        val link = steps(build(changed, before = digests.of(loaded), after = digests.of(changed))).ofKind("link").single()

        assertEquals(listOf("note"), link["clear"])
        assertFalse("props" in link, link.toString())
    }

    @Test
    fun `a target whose id is a floating-point number is named as Oracle reads the number back`() {
        val loaded = ClaimGauges(Claim("c1", "one", stamp = stamp), gauges = listOf(Gauge(3.0, "three"), Gauge(4.5, "four and a half")))
        val changed = loaded.copy(gauges = listOf(Gauge(4.5, "four and a half")))
        val steps = steps(build(changed, before = digests.of(loaded), after = digests.of(changed)))

        assertEquals(listOf("3"), steps.ofKind("unlink").single()["ids"])

        val made = stepsOf(ClaimGauges(Claim("c1", "one"), gauges = listOf(Gauge(3.0, "three"), Gauge(4.5, "x")))).ofKind("node").drop(1)
        assertEquals(listOf("3", "4.5"), made.map { it["k"] })
        assertEquals(listOf(3.0, 4.5), made.map { it["id"] }, "the id itself is written as the object holds it")
    }

    // One node held more than once

    @Test
    fun `a field that holds a node twice writes it once, as the last says, and reports the other as a repeat`() {
        val (first, last) = Claim("k", "first") to Claim("k", "last")
        val statement = build(ClaimSupports(Claim("c1", "one"), supports = listOf(first, last)))
        val steps = steps(statement)

        assertEquals(listOf("node", "node", "link"), steps.map { it["op"] })
        assertEquals(mapOf("text" to "last"), steps[1]["set"])
        assertEquals(listOf<Any>(first), statement.repeats.map { it.fragment })
        assertEquals(listOf<Any>(last), statement.stamped)
        assertEquals("0", steps[1]["i"], "the one written hands its stamp back under the first index")
    }

    @Test
    fun `a node two fields hold is a step of each, found by one key and one shape`() {
        val steps = stepsOf(ClaimPair(Claim("c1", "one"), lead = Claim("x", "lead"), supports = listOf(Claim("x", "lead"))))

        val x = steps.ofKind("node").filter { it["k"] == "x" }
        assertEquals(2, x.size)
        assertEquals(1, x.map { it["s"] }.distinct().size)
        assertEquals(listOf(1, 2), x.map { it["r"] }, "each is a node of its own to the steps that join it")
        assertEquals(listOf("0", "1"), x.map { it["i"] }, "each hands its stamp back under its own index")
    }

    // Null policy, bags and labels

    @Test
    fun `a root saved under CLEAR with a property bag has the bag's stale keys cleared by the block`() {
        val root = stepsOf(Tagged("t1", "x", meta = mapOf("k" to "v")), nullPolicy = NullPolicy.CLEAR, checked = false).single()

        @Suppress("UNCHECKED_CAST")
        val bag = (root["bags"] as List<Map<String, Any?>>).single()
        assertEquals(setOf("prefix", "flat", "keep", "reservedKeys", "reservedPrefixes"), bag.keys)
        assertEquals("meta.", bag["prefix"], "the prefix as stored keys begin with it, which the block's owns compares raw")
        assertEquals(false, bag["flat"])
        assertEquals(listOf("meta.k"), bag["keep"])
        assertEquals("v", (root["set"] as Map<*, *>)["meta.k"])
    }

    @Test
    fun `a root saved under IGNORE leaves a null and the stale keys of a bag alone`() {
        val tagged = stepsOf(Tagged("t1", "x", meta = mapOf("k" to "v")), nullPolicy = NullPolicy.IGNORE).single()
        val claim = stepsOf(Claim("c1", "one", note = null), nullPolicy = NullPolicy.IGNORE).single()

        assertFalse("bags" in tagged, tagged.toString())
        assertFalse("unset" in tagged, tagged.toString())
        assertFalse("unset" in claim, claim.toString())
    }

    @Test
    fun `a root saved under CLEAR unsets a field that is null`() {
        val root = stepsOf(Claim("c1", "one", note = null), nullPolicy = NullPolicy.CLEAR).single()

        assertEquals(listOf("note"), root["unset"])
        assertEquals(mapOf("text" to "one"), root["set"])
    }

    @Test
    fun `an open label field saved under CLEAR has what it recorded cleared, keeping the labels of the class`() {
        val record = FragmentModel.from(ThingNode::class.java).nodeLabels!!.ownedProperty
        val root = stepsOf(ThingNode("t1", "thing", labels = setOf("Extra")), nullPolicy = NullPolicy.CLEAR).single()

        assertEquals(listOf("Extra"), root["add"])
        assertEquals(mapOf("prop" to record, "clear" to true, "base" to listOf("Thing")), root["owned"])
        assertFalse("drop" in root, "an open set drops only what the block finds recorded: $root")
    }

    @Test
    fun `an open label field saved under IGNORE records what it adds, and nothing when it adds nothing`() {
        val record = FragmentModel.from(ThingNode::class.java).nodeLabels!!.ownedProperty
        val adding = stepsOf(ThingNode("t1", "thing", labels = setOf("Extra")), nullPolicy = NullPolicy.IGNORE).single()
        val plain = stepsOf(ThingNode("t1", "thing"), nullPolicy = NullPolicy.IGNORE).single()

        assertEquals(mapOf("prop" to record), adding["owned"])
        assertEquals(listOf("Extra"), adding["add"])
        assertFalse("owned" in plain, plain.toString())
        assertFalse("add" in plain, plain.toString())
    }

    @Test
    fun `a closed label field adds the labels it holds, and under CLEAR drops the rest of its set`() {
        val clearing = stepsOf(MemberNode("m1", "Mo", roles = setOf(Role.Admin)), nullPolicy = NullPolicy.CLEAR).single()
        val ignoring = stepsOf(MemberNode("m1", "Mo", roles = setOf(Role.Admin)), nullPolicy = NullPolicy.IGNORE).single()

        assertEquals(listOf("Admin"), clearing["add"])
        assertEquals(setOf("Reviewer", "Author"), (clearing["drop"] as List<*>).toSet())
        assertFalse("owned" in clearing, "a closed set records nothing: $clearing")
        assertEquals(listOf("Admin"), ignoring["add"])
        assertFalse("drop" in ignoring, ignoring.toString())
    }

    @Test
    fun `a node reached through a relationship is written as under IGNORE whatever the policy`() {
        val steps = stepsOf(ClaimSupports(Claim("c1", "one"), supports = listOf(Claim("k", "a", note = null))), nullPolicy = NullPolicy.CLEAR)

        val related = steps.ofKind("node").last()
        assertFalse("unset" in related, related.toString())
        assertFalse("bags" in related, related.toString())
    }

    @Test
    fun `a save told which root fields to write touches no other, a bag and a label field among them`() {
        val claim = steps(build(Claim("c1", "one", note = null), nullPolicy = NullPolicy.CLEAR, rootWriteFields = setOf("note"))).single()
        assertEquals(listOf("note"), claim["unset"])
        assertFalse("set" in claim, "the text is not among the fields to write: $claim")

        val tagged = steps(build(Tagged("t1", "x", meta = mapOf("k" to "v")), checked = false, nullPolicy = NullPolicy.CLEAR, rootWriteFields = setOf("text"))).single()
        assertEquals(mapOf("text" to "x"), tagged["set"])
        assertFalse("bags" in tagged, "a bag that is not written is not cleared either: $tagged")

        val thing = steps(build(ThingNode("t1", "thing", labels = setOf("Extra")), nullPolicy = NullPolicy.CLEAR, rootWriteFields = setOf("name"))).single()
        assertEquals(setOf("add", "drop", "owned", "bags").intersect(thing.keys), emptySet<String>(), thing.toString())
    }

    @Test
    fun `a null in a bag is left alone under IGNORE and unset under CLEAR, by the key it is stored under`() {
        val ignoring = stepsOf(Tagged("t1", "x", meta = mapOf("k" to null, "j" to 1)), nullPolicy = NullPolicy.IGNORE, checked = false).single()
        val clearing = stepsOf(Tagged("t1", "x", meta = mapOf("k" to null, "j" to 1)), nullPolicy = NullPolicy.CLEAR, checked = false).single()

        assertEquals(mapOf("text" to "x", "meta.j" to 1), ignoring["set"])
        assertFalse("unset" in ignoring, ignoring.toString())
        assertEquals(listOf("meta.k"), clearing["unset"])
        @Suppress("UNCHECKED_CAST")
        val keep = (clearing["bags"] as List<Map<String, Any?>>).single()["keep"]
        assertEquals(listOf("meta.k", "meta.j"), keep, "a key the object holds as null is the object's still, and unset by name")
    }

    @Test
    fun `a change to a loaded root unsets the keys its bag has dropped by name, and leaves the store unread`() {
        val loaded = Tagged("t1", "x", meta = mapOf("a" to 1, "b" to 2), stamp = stamp)
        val changed = loaded.copy(meta = mapOf("a" to 1))
        val root = steps(build(changed, nullPolicy = NullPolicy.CLEAR, before = digests.of(loaded), after = digests.of(changed))).single()

        assertEquals(listOf("meta.b"), root["unset"])
        assertFalse("bags" in root, "the digest says what was dropped: $root")
    }

    @Test
    fun `a flat bag is cleared as what is no other field's, which the step names`() {
        val root = stepsOf(ThingNode("t1", "thing", properties = mapOf("colour" to "red")), nullPolicy = NullPolicy.CLEAR).single()

        @Suppress("UNCHECKED_CAST")
        val bag = (root["bags"] as List<Map<String, Any?>>).single()
        val model = FragmentModel.from(ThingNode::class.java).propertyBags.single()
        assertEquals(true, bag["flat"])
        assertEquals(listOf("colour"), bag["keep"])
        assertEquals(model.reservedKeys.toList(), bag["reservedKeys"])
        assertTrue((bag["reservedKeys"] as List<*>).containsAll(listOf("id", "name")), bag.toString())
        assertEquals("red", (root["set"] as Map<*, *>)["colour"])
    }

    @Test
    fun `what cannot be stored is refused before a step is planned for it`() {
        fun refused(obj: Any, nullPolicy: NullPolicy = NullPolicy.IGNORE) =
            assertFailsWith<IllegalArgumentException> { stepsOf(obj, nullPolicy = nullPolicy, checked = false) }.message!!

        assertContains(refused(Tagged("t1", "x", meta = mapOf("k" to mapOf("nested" to 1)))), "'meta.k' is not a storable node property")
        assertContains(refused(Tagged("t1", "x", meta = mapOf("k" to listOf(listOf(1))))), "'meta.k' is not a storable node property")
        assertContains(refused(ThingNode("t1", "thing", labels = setOf("Extra", " "))), "holds a blank label")
        assertContains(refused(ThingNode("t1", "thing", properties = mapOf("name" to "other"))), "has an entry 'name'")
        assertContains(refused(Loose(null, "nameless")), "null id")
        // A list of scalars and a temporal are properties as they are.
        val stored = stepsOf(Tagged("t1", "x", meta = mapOf("tags" to listOf("a", "b"), "at" to Instant.parse("2026-10-12T09:30:00Z"))), checked = false).single()
        assertEquals(setOf("text", "meta.tags", "meta.at"), (stored["set"] as Map<*, *>).keys)
    }

    // Views in views, and subtypes

    @Test
    fun `a view held in a field is saved root first, then its own fields, and joined to the outer root last`() {
        val nested = ClaimView(Claim("k1", "covered", stamp = stamp), people = listOf(Human("ada", "Ada")))
        val statement = build(ClaimDossier(Claim("c1", "one", stamp = stamp), covered = listOf(nested)))
        val steps = steps(statement)

        assertEquals(listOf("node", "node", "node", "link", "link"), steps.map { it["op"] })
        val (root, inner, ada, mentions, covers) = steps
        assertEquals(true, root["root"])
        assertEquals("k1", inner["k"])
        assertFalse("root" in inner || "expect" in inner, "the root of a nested view is saved as any related node is: $inner")
        assertEquals("0", inner["i"], "and its stamp is handed back under its index")
        assertEquals(listOf(stamp), statement.carried)
        assertFalse("i" in ada, "a fragment that declares no stamp is handed none: $ada")
        assertEquals(mapOf("op" to "link", "from" to 1, "to" to 2, "type" to "MENTIONS", "dir" to "OUT"), mentions)
        assertEquals(mapOf("op" to "link", "from" to 0, "to" to 1, "type" to "COVERS", "dir" to "OUT"), covers)
    }

    @Test
    fun `a related node is saved as the class it is, with the labels and the fields of a subtype, and found by a shape of its own`() {
        val steps = stepsOf(ClaimVessels(Claim("c1", "one"), vessels = listOf(Vessel("v1", "Skiff"), Tanker("v2", "Big", tonnes = 9))))

        val (skiff, tanker) = steps.ofKind("node").drop(1)
        assertEquals(listOf("Vessel"), skiff["labels"])
        assertEquals(listOf("Tanker", "Vessel"), tanker["labels"])
        assertEquals(mapOf("name" to "Big", "tonnes" to 9), tanker["set"])
        assertTrue(skiff["s"] != tanker["s"], "a tanker is looked for under both its labels")
    }

    // A batch of fragments

    @Test
    fun `fragments saved together are a step each, returned under the index each was given, by the block that returns each stamp`() {
        val (text, bindings) = planner.together(listOf(Claim("a", "A"), Claim("b", "B"), Claim("c", "C")).withIndex().toList(), NullPolicy.IGNORE)

        @Suppress("UNCHECKED_CAST")
        val steps = bindings[OracleWriteBlock.PLAN] as List<Map<String, Any?>>
        assertEquals(listOf("node", "node", "node"), steps.map { it["op"] })
        assertEquals(listOf("0", "1", "2"), steps.map { it["i"] })
        assertEquals(listOf("a", "b", "c"), steps.map { it["k"] })
        steps.forEach { assertFalse("root" in it, "a batch has no root: $it") }
        assertEquals(setOf(OracleWriteBlock.PLAN, OracleWriteBlock.MARK), bindings.keys)
        assertContains(text, "JSON_TABLE(v_result")
    }

    // What the statement says of itself

    @Test
    fun `the statement offers the mark and one fresh stamp for each node step, and names the root as the block finds it`() {
        val statement = build(ClaimSupports(Claim("c1", "one", stamp = stamp), supports = listOf(Claim("k1", "a"), Claim("k2", "b"))))
        val steps = steps(statement)

        assertEquals(listOf(statement.bindings[OracleWriteBlock.MARK]) + steps.ofKind("node").map { it["fresh"] }, statement.offered)
        assertEquals("c1", statement.root.id)
        assertEquals("Claim", statement.root.labels)
        assertEquals("id", statement.root.idProperty)
        assertEquals("aaaaaaaaaaaaaaaa", statement.root.expected.let { org.drivine.model.Stamps.nodeToken(it!!) })
        assertEquals(stamp, statement.root.carried)
        assertEquals(listOf<String?>(null, null), statement.carried)
        assertTrue(statement.keys.all { it != null })
    }

    @Test
    fun `the block's text is the same however many nodes of a shape the plan holds`() {
        fun claims(count: Int) = (1..count).map { Claim("k$it", "Claim $it") }
        val one = build(ClaimSupports(Claim("c1", "one"), supports = claims(1))).statement
        val fifty = build(ClaimSupports(Claim("c1", "one"), supports = claims(50))).statement

        assertEquals(one, fifty)
    }

    // Serialisation

    @Test
    fun `the plan is bound as JSON text, a temporal in it as an ISO string and a list as an array`() {
        val seen = Instant.parse("2026-10-12T09:30:00Z")
        val statement = build(Beacon("b1", seen, tags = listOf("red", "blue")), checked = false)
        val bound = JdbcParameterCoercer.coerce(statement.bindings)

        val plan = bound[OracleWriteBlock.PLAN]
        assertTrue(plan is String, "the plan is JSON text, not a list: ${plan?.javaClass}")
        assertTrue(bound[OracleWriteBlock.MARK] is String)
        val steps = Neo4jObjectMapper.instance.readTree(plan)
        assertTrue(steps.isArray)
        val set = steps[0]["set"]
        assertEquals("2026-10-12T09:30:00Z", set["seenAt"].textValue())
        assertTrue(set["tags"].isArray)
        assertEquals(listOf("red", "blue"), set["tags"].map { it.textValue() })
    }
}
