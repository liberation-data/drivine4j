package org.drivine.migration

import org.drivine.annotation.Direction
import org.drivine.connection.DatabaseType
import org.drivine.model.Stamps
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import sample.stateless.Claim
import sample.stateless.ClaimEmployers
import sample.stateless.HumanClaims
import sample.stateless.HumanFollowers
import sample.stateless.HumanMentions
import sample.stateless.MemoCoverChain
import sample.stateless.MemoSheets
import sample.stateless.MentioningSheet
import sample.stateless.ThingEmployers
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the migration tools decide without a store: which nodes can be the same, what they ask the
 * store to count, what a repair refuses, and how many statements it runs. The store is a
 * [RecordingStore] that answers each count; what a repair does to a graph is the cross-engine test's to say.
 */
class MigrationToolsTest {

    /** A store whose counts are [counts], by the first of its keys a statement holds, and zero otherwise. */
    private fun counting(vararg counts: Pair<String, Long>) = RecordingStore(DatabaseType.NEO4J) { ran ->
        listOf(counts.firstOrNull { it.first in ran.statement }?.second ?: 0L)
    }

    private fun finding(root: List<String> = listOf("Human"), target: List<String> = listOf("Claim"), ambiguity: String? = null) =
        DirectionFinding(HumanClaims::class.java, "claims", "MENTIONS", root, target, wrongWay = 0, rightWay = 0, eitherWay = 0, ambiguity = ambiguity)

    // ----- Which nodes can be the same -----

    @Test
    fun `two kinds of node can be the same nodes when one has every label of the other, and so when either has none`() {
        assertTrue(sameLabels(listOf("Human"), listOf("Human")))
        assertTrue(sameLabels(listOf("VipHuman", "Human"), listOf("Human")))
        assertTrue(sameLabels(listOf("Human"), listOf("Human", "VipHuman")))
        assertTrue(sameLabels(emptyList(), listOf("Claim")), "a node of any label")
        assertTrue(sameLabels(emptyList(), emptyList()))
        assertFalse(sameLabels(listOf("Human"), listOf("Claim")))
        assertFalse(sameLabels(listOf("Company", "Listed"), listOf("Company", "Organization")), "each has a label the other lacks")
    }

    @Test
    fun `two kinds that share no label can still be the same nodes when the views name a kind that has the labels of both`() {
        val company = listOf("Company")
        val organization = listOf("Organization")

        assertFalse(canBeSame(company, organization, emptySet()))
        assertFalse(canBeSame(company, organization, setOf(listOf("Company"), listOf("Organization"), listOf("Human"))))
        assertTrue(canBeSame(company, organization, setOf(listOf("Company", "Organization"))))
        assertTrue(canBeSame(company, listOf("Company"), emptySet()))
    }

    @Test
    fun `a node pattern names every label, and none at all for a node of any kind`() {
        assertEquals("(root:Human:VipHuman)", nodePattern(listOf("Human", "VipHuman"), "root"))
        assertEquals("(:Claim)", nodePattern(listOf("Claim")))
        assertEquals("(target)", nodePattern(emptyList(), "target"))
        assertEquals("()", nodePattern(emptyList()))
        assertEquals("Human:VipHuman", named(listOf("Human", "VipHuman")))
        assertEquals("any node", named(emptyList()))
    }

    @Test
    fun `a relationship can point either way when each end has every label of both, which with no labels is always`() {
        assertEquals("root:Claim AND target:Human", eitherWay(listOf("Human"), listOf("Claim")))
        assertEquals("root:Claim AND target:Human AND target:VipHuman", eitherWay(listOf("Human", "VipHuman"), listOf("Claim")))
        assertEquals("root:Claim", eitherWay(emptyList(), listOf("Claim")))
        assertEquals("true", eitherWay(emptyList(), emptyList()))
    }

    // ----- What is declared -----

    @Test
    fun `a path's hops carry the root's labels at its start, the target's at its end, and each hop's own between`() {
        val path = StoredRelationships(counting()).pathFields(arrayOf(ClaimEmployers::class.java)).single()
        val (first, second) = path.pathHops()

        assertEquals(listOf("MENTIONS", "WORKS_AT"), listOf(first.type, second.type))
        assertEquals(listOf("Claim") to listOf("Human"), first.startLabels to first.endLabels)
        assertEquals(listOf("Human") to listOf("Company"), second.startLabels to second.endLabels)
        // A hop is stored between the kinds it joins, and not between others.
        assertTrue(first.storesFrom(listOf("Claim"), listOf("Human"), emptySet()))
        assertFalse(first.storesFrom(listOf("Human"), listOf("Claim"), emptySet()))
        assertFalse(first.storesFrom(listOf("Claim"), listOf("Company"), emptySet()))
    }

    @Test
    fun `a path field is not a relationship field, and a view is collected once however often it is given`() {
        val stored = StoredRelationships(counting())
        val views = arrayOf<Class<*>>(ClaimEmployers::class.java, ClaimEmployers::class.java)

        assertEquals(listOf("people"), stored.declaredFields(views).map { it.relationship.fieldName })
        assertEquals(listOf("employers"), stored.pathFields(views).map { it.relationship.fieldName })
        assertEquals(setOf(listOf("Claim"), listOf("Human"), listOf("Company")), stored.labelSets(views))
    }

    @Test
    fun `a class that is not a view is refused by name, by every tool`() {
        val message = "Claim is not a @GraphView."

        assertEquals(message, assertFailsWith<IllegalArgumentException> { RelationshipDirectionRepair(counting()).report(Claim::class.java) }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { PathRelationshipReport(counting()).report(HumanClaims::class.java, Claim::class.java) }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { PathRelationshipReport(counting()).unexamined(Claim::class.java) }.message)
    }

    @Test
    fun `an abstract view that names no subtype is reported as not looked below, until a subtype is given`() {
        val repair = RelationshipDirectionRepair(counting())

        val unexamined = repair.unexamined(MemoSheets::class.java).single()
        assertContains(unexamined, "MemoSheets.sheets holds HumanSheet, which is abstract and names no subtype")
        assertTrue(repair.unexamined(MemoSheets::class.java, MentioningSheet::class.java).isEmpty())
        assertTrue(repair.unexamined(HumanClaims::class.java).isEmpty())
    }

    // ----- The direction report -----

    @Test
    fun `the report counts an incoming field's relationships each way, those between nodes that can be either end apart`() {
        val store = counting(
            "WITH root, target, count(r) AS wrong" to 2L,
            "MATCH (root:Human)-[r:MENTIONS]->(target:Claim) WHERE NOT (root:Claim AND target:Human) RETURN count(r)" to 7L,
            "MATCH (target:Claim)-[r:MENTIONS]->(root:Human) WHERE NOT (root:Claim AND target:Human) RETURN count(r)" to 3L,
            "MATCH (root:Human)-[r:MENTIONS]->(target:Claim) WHERE root:Claim AND target:Human RETURN count(r)" to 1L,
        )

        val finding = RelationshipDirectionRepair(store).report(HumanClaims::class.java).single()

        assertEquals(
            DirectionFinding(HumanClaims::class.java, "claims", "MENTIONS", listOf("Human"), listOf("Claim"), wrongWay = 7, rightWay = 3, eitherWay = 1, ambiguity = null, collisions = 2),
            finding,
        )
        assertTrue(finding.repairable)
        assertEquals(4, store.queried.size, "four counts, and nothing else")
        assertTrue(store.executed.isEmpty(), "a report changes nothing")
    }

    @Test
    fun `a field read away from its root has no finding, nor has a list read over several hops`() {
        val repair = RelationshipDirectionRepair(counting())

        assertTrue(repair.report(HumanMentions::class.java).isEmpty())
        assertTrue(repair.report(MemoCoverChain::class.java).isEmpty())
    }

    @Test
    fun `a field is ambiguous when another of the views declares its relationship pointing away from the root, and says which`() {
        val findings = RelationshipDirectionRepair(counting()).report(HumanClaims::class.java, HumanMentions::class.java)

        assertEquals(
            "HumanMentions.claims declares MENTIONS from Human to Claim, so relationships pointing that way may be meant.",
            findings.single().ambiguity,
        )
    }

    @Test
    fun `a field between nodes of one kind is ambiguous and cannot be repaired, whatever order its labels are given in`() {
        val followers = RelationshipDirectionRepair(counting()).report(HumanFollowers::class.java).single()

        assertFalse(followers.repairable)
        assertContains(followers.ambiguity.orEmpty(), "its root and its target can be the same nodes")
        assertFalse(finding(listOf("A", "B"), listOf("B", "A")).repairable)
        assertFalse(finding(emptyList(), emptyList()).repairable)
        // One end's labels among the other's: a node can be both, yet the store can say which end is which.
        assertTrue(finding(listOf("VipHuman", "Human"), listOf("Human")).repairable)
        assertTrue(finding(emptyList(), listOf("Claim")).repairable)
    }

    // ----- Repair -----

    @Test
    fun `a batch size that is not positive is refused before anything is asked of the store`() {
        val store = counting()

        for (size in listOf(0, -1)) {
            val refused = assertFailsWith<IllegalArgumentException> { RelationshipDirectionRepair(store).repair(finding(), batchSize = size) }
            assertEquals("batchSize must be positive, was $size", refused.message)
        }
        assertTrue(store.queried.isEmpty())
    }

    @Test
    fun `a finding that cannot be repaired is refused for its labels, forced or not, though it was built by hand to say otherwise`() {
        val store = counting("RETURN count(r)" to 5L)
        val byHand = finding(listOf("Human"), listOf("Human"), ambiguity = null)

        for (force in listOf(false, true)) {
            val refused = assertFailsWith<IllegalStateException> { RelationshipDirectionRepair(store).repair(byHand, force = force) }
            assertContains(refused.message.orEmpty(), "HumanClaims.claims cannot be repaired: its root and its target can be the same nodes")
        }
        assertTrue(store.queried.isEmpty())
    }

    @Test
    fun `an ambiguous finding is refused with its reason, unless it is forced`() {
        val store = counting()
        val ambiguous = finding(ambiguity = "HumanMentions.claims declares it.")

        val refused = assertFailsWith<IllegalStateException> { RelationshipDirectionRepair(store).repair(ambiguous) }
        assertEquals("HumanClaims.claims is ambiguous: HumanMentions.claims declares it. Pass force = true to turn them round anyway.", refused.message)
        assertTrue(store.queried.isEmpty())

        assertEquals(0L, RelationshipDirectionRepair(store).repair(ambiguous, force = true))
    }

    @Test
    fun `with nothing pointing the wrong way a repair counts and runs no statement that writes`() {
        val store = counting()

        assertEquals(0L, RelationshipDirectionRepair(store).repair(finding()))
        assertEquals(1, store.queried.size)
        assertTrue(store.queried.single().statement.endsWith("RETURN count(r)"))
    }

    @Test
    fun `a repair runs no more statements than the count needs, a batch to each, and adds up what each dealt with`() {
        val dealt = ArrayDeque(listOf(10L, 10L, 5L))
        val store = RecordingStore(DatabaseType.NEO4J) { ran ->
            listOf(if (ran.statement.endsWith("RETURN count(r)")) 25L else dealt.removeFirst())
        }

        assertEquals(25L, RelationshipDirectionRepair(store).repair(finding(), batchSize = 10))

        val batches = store.queried.drop(1)
        assertEquals(3, batches.size)
        batches.forEach { assertEquals(mapOf<String, Any?>("batch" to 10), it.parameters) }
        assertEquals(1, batches.map { it.statement }.distinct().size)
    }

    @Test
    fun `a repair stops at the first batch that deals with nothing, whatever the count said`() {
        val store = RecordingStore(DatabaseType.NEO4J) { ran -> listOf(if (ran.statement.endsWith("RETURN count(r)")) 1_000L else 0L) }

        assertEquals(0L, RelationshipDirectionRepair(store).repair(finding(), batchSize = 10))
        assertEquals(2, store.queried.size, "the count, and the one batch that found nothing")
    }

    @Test
    fun `the repair statement takes the relationships in the order of their ids, leaves alone what can point either way, and marks both ends`() {
        val store = RecordingStore(DatabaseType.NEO4J) { listOf(1L) }
        RelationshipDirectionRepair(store).repair(finding(listOf("VipHuman", "Human"), listOf("Claim")))
        val statement = store.queried.last().statement.lines()

        assertEquals("MATCH (root:VipHuman:Human)-[r:MENTIONS]->(target:Claim)", statement[0])
        assertEquals("WHERE NOT (root:Claim AND target:VipHuman AND target:Human)", statement[1])
        assertEquals("WITH root, target, r ORDER BY id(r) LIMIT \$batch", statement[2])
        assertEquals("MERGE (target)-[turned:MENTIONS]->(root)", statement[4])
        assertEquals("SET turned += kept, ${Stamps.linksClause("root")}, ${Stamps.linksClause("target")}", statement[7])
        assertEquals("RETURN coalesce(sum(dealt), 0)", statement.last())
    }

    // ----- The path report -----

    @Test
    fun `the path report counts the direct relationships of the first hop's type from a root to the path's end`() {
        val store = counting("MATCH (:Claim)-[r:MENTIONS]->(:Company) RETURN count(r)" to 4L)

        val finding = PathRelationshipReport(store).report(ClaimEmployers::class.java).single()

        assertEquals("employers", finding.field)
        assertEquals("MENTIONS", finding.type)
        assertEquals(4L, finding.direct)
        assertEquals(listOf("Claim") to listOf("Company"), finding.rootLabels to finding.targetLabels)
        assertEquals("MATCH (:Claim)-[r:MENTIONS]->(:Company) RETURN count(r)", store.queried.single().statement)
    }

    @Test
    fun `a path finding gives its statement only when it is not ambiguous and both ends have a label, and gives it all the same when asked outright`() {
        val clear = PathFinding(ClaimEmployers::class.java, "employers", "MENTIONS", listOf("Claim"), listOf("Company"), direct = 4, ambiguity = null)
        val ambiguous = clear.copy(ambiguity = "ClaimView.companies declares it.")
        val unlabelled = clear.copy(rootLabels = emptyList())

        val statement = "MATCH (root:Claim)-[r:MENTIONS]->(target:Company) SET ${Stamps.linksClause("root")}, ${Stamps.linksClause("target")} DELETE r"
        assertEquals(statement, clear.removalStatement)
        assertNull(ambiguous.removalStatement)
        assertEquals(statement, ambiguous.forcedRemovalStatement())
        assertNull(unlabelled.removalStatement)
        assertNull(clear.copy(targetLabels = emptyList()).removalStatement)
        assertTrue(unlabelled.forcedRemovalStatement().startsWith("MATCH (root)-[r:MENTIONS]->(target:Company) SET "), unlabelled.forcedRemovalStatement())
    }

    @Test
    fun `a path from a node of any label is counted from any node, and gives its statement only when asked outright`() {
        val store = counting()
        val finding = PathRelationshipReport(store).report(ThingEmployers::class.java).single()

        assertEquals("MATCH ()-[r:MENTIONS]->(:Company) RETURN count(r)", store.queried.single().statement)
        assertTrue(finding.rootLabels.isEmpty())
        assertNull(finding.removalStatement)
        assertNotNull(finding.forcedRemovalStatement())
    }

    @Test
    fun `the lists read over several hops are counted with the direction they declare, and a list read over one hop is not among them`() {
        val store = counting("MATCH (:Memo)-[r:COVERS]->(:Claim) RETURN count(r)" to 6L)
        val report = PathRelationshipReport(store)

        val finding = report.reportSeveralHopLists(MemoCoverChain::class.java, HumanClaims::class.java).single()

        assertEquals(SeveralHopsFinding(MemoCoverChain::class.java, "chain", "COVERS", Direction.INCOMING, listOf("Memo"), listOf("Claim"), direct = 6), finding)
        assertEquals(1, store.queried.size)
    }
}
