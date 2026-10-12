package org.drivine.query

import org.drivine.annotation.Direction
import org.drivine.annotation.NodeFragment
import org.drivine.manager.NodeRef
import org.drivine.manager.RelateMode
import org.drivine.model.Stamps
import org.junit.jupiter.api.Test
import sample.graphproperty.WidgetNode
import sample.stateless.Claim
import sample.stateless.Human
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A node with no id, which a statement cannot find. */
@NodeFragment(labels = ["Adrift"])
data class Adrift(val name: String)

/** The Cypher for a relationship whose type is known only when it is written. */
class EdgeStatementsTest {

    private val claim = NodeRef(Claim::class.java, "c1")
    private val human = NodeRef(Human::class.java, "h1")

    // ----- relate -----

    @Test
    fun `a merge locks both nodes, counts what is there, and marks them only when it makes or changes a relationship`() {
        val relate = EdgeStatements.relate(claim, human, "MENTIONS", mapOf("page" to 3), RelateMode.MERGE)
        val lines = relate.statement.lines()

        assertEquals("MATCH (a:`Claim` {`id`: \$_fromId})", lines[0])
        assertEquals("MATCH (b:`Human` {`id`: \$_toId})", lines[1])
        assertEquals(Stamps.lock("a", "b"), lines[2])
        assertEquals("WITH a, b", lines[3])
        assertEquals("OPTIONAL MATCH (a)-[x:`MENTIONS`]->(b)", lines[4])
        assertEquals("WITH a, b, count(x) AS _had, sum(CASE WHEN x IS NOT NULL AND coalesce(x.`page` = \$_rel0, false) THEN 1 ELSE 0 END) AS _same", lines[5])
        assertEquals("MERGE (a)-[r:`MENTIONS`]->(b)", lines[6])
        assertTrue(lines[7].startsWith("SET r.`page` = \$_rel0, a.`__drivine.stamp` = CASE WHEN (_had = 0 OR _same < _had) OR "), lines[7])
        assertEquals("RETURN count(r)", lines[8])
        assertEquals(9, lines.size)
    }

    @Test
    fun `a create makes one more relationship without reading what is there, and always marks both nodes`() {
        val relate = EdgeStatements.relate(claim, human, "MENTIONS", emptyMap(), RelateMode.CREATE)

        assertFalse("OPTIONAL MATCH" in relate.statement)
        assertFalse(Stamps.LOCK in relate.statement)
        assertContains(relate.statement, "\nCREATE (a)-[r:`MENTIONS`]->(b)\n")
        assertContains(relate.statement, "SET ${Stamps.relink("a", Stamps.ALWAYS, "\$_mark")}, ${Stamps.relink("b", Stamps.ALWAYS, "\$_mark")}")
        assertFalse("CASE WHEN (" in relate.statement, "nothing is conditional")
    }

    @Test
    fun `a null property is left out, and the rest are bound in the order they were given`() {
        val relate = EdgeStatements.relate(claim, human, "CITES", linkedMapOf("page" to 3, "note" to null, "quote" to "q"), RelateMode.MERGE)

        assertEquals(setOf("_fromId", "_toId", "_mark", "_rel0", "_rel1"), relate.bindings.keys)
        assertEquals(3, relate.bindings["_rel0"])
        assertEquals("q", relate.bindings["_rel1"])
        assertContains(relate.statement, "SET r.`page` = \$_rel0, r.`quote` = \$_rel1, a.")
        assertFalse("note" in relate.statement)
        // A relationship counts as the same only when it carries every property that is written.
        assertContains(relate.statement, "x IS NOT NULL AND coalesce(x.`page` = \$_rel0, false) AND coalesce(x.`quote` = \$_rel1, false)")
    }

    @Test
    fun `with no property to write, a merge marks the nodes only when it makes the relationship`() {
        val relate = EdgeStatements.relate(claim, human, "MENTIONS", mapOf("note" to null), RelateMode.MERGE)

        assertContains(relate.statement, "sum(CASE WHEN x IS NOT NULL THEN 1 ELSE 0 END) AS _same")
        assertEquals(setOf("_fromId", "_toId", "_mark"), relate.bindings.keys)
    }

    @Test
    fun `the ids are bound as they were given, and the mark is a fresh stamp for each statement`() {
        val one = EdgeStatements.relate(NodeRef(Claim::class.java, 7L), human, "MENTIONS", emptyMap(), RelateMode.MERGE)
        val two = EdgeStatements.relate(NodeRef(Claim::class.java, 7L), human, "MENTIONS", emptyMap(), RelateMode.MERGE)

        assertEquals(7L, one.bindings["_fromId"])
        assertEquals("h1", one.bindings["_toId"])
        assertTrue(Stamps.isTwoTokens(one.bindings["_mark"] as String))
        assertNotEquals(one.bindings["_mark"], two.bindings["_mark"])
        assertEquals(one.statement, two.statement)
    }

    @Test
    fun `a type, a property and a label are each one identifier, whatever they hold`() {
        val relate = EdgeStatements.relate(
            NodeRef(Claim::class.java, "c1", setOf("Has Space", "Tick`ed")), human, "REL`]->(x) DETACH DELETE x//",
            mapOf("a.b" to 1, "it's" to 2), RelateMode.MERGE,
        )

        assertContains(relate.statement, "MATCH (a:`Claim`:`Has Space`:`Tick``ed` {`id`: \$_fromId})")
        assertContains(relate.statement, "MERGE (a)-[r:`REL``]->(x) DETACH DELETE x//`]->(b)")
        assertContains(relate.statement, "r.`a.b` = \$_rel0, r.`it's` = \$_rel1")
    }

    @Test
    fun `a label the ref names again is written once, and the id's stored property is the one matched`() {
        val statement = EdgeStatements.unrelate(NodeRef(WidgetNode::class.java, "w1", setOf("Widget", "Extra")), human, "USES").statement

        assertContains(statement, "MATCH (a:`Widget`:`Extra` {`widget_key`: \$_fromId})-[r:`USES`]->(b:`Human` {`id`: \$_toId})")
    }

    // ----- unrelate -----

    @Test
    fun `unrelate deletes the relationships between two nodes in the one direction, marks both and counts`() {
        val unrelate = EdgeStatements.unrelate(claim, human, "MENTIONS")

        assertEquals(
            listOf(
                "MATCH (a:`Claim` {`id`: \$_fromId})-[r:`MENTIONS`]->(b:`Human` {`id`: \$_toId})",
                "DELETE r",
                "SET ${Stamps.relink("a", Stamps.ALWAYS, "\$_mark")}, ${Stamps.relink("b", Stamps.ALWAYS, "\$_mark")}",
                "RETURN count(r)",
            ),
            unrelate.statement.lines(),
        )
        assertEquals(setOf("_fromId", "_toId", "_mark"), unrelate.bindings.keys)
    }

    @Test
    fun `unrelateAll follows the direction it is given, to a node of any label, and binds no other id`() {
        fun arrow(direction: Direction) = EdgeStatements.unrelateAll(human, "MENTIONS", direction).statement.lines().first()

        assertEquals("MATCH (a:`Human` {`id`: \$_fromId})-[r:`MENTIONS`]->(b)", arrow(Direction.OUTGOING))
        assertEquals("MATCH (a:`Human` {`id`: \$_fromId})<-[r:`MENTIONS`]-(b)", arrow(Direction.INCOMING))
        assertEquals("MATCH (a:`Human` {`id`: \$_fromId})-[r:`MENTIONS`]-(b)", arrow(Direction.UNDIRECTED))
        assertEquals(setOf("_fromId", "_mark"), EdgeStatements.unrelateAll(human, "MENTIONS", Direction.OUTGOING).bindings.keys)
    }

    // ----- relatedMatch -----

    @Test
    fun `the related nodes are bound as n with every label of the target, each once, and nothing is marked`() {
        val match = EdgeStatements.relatedMatch(claim, "MENTIONS", Direction.INCOMING, listOf("Human", "Vip Human"))

        assertEquals("MATCH (a:`Claim` {`id`: \$_fromId})<-[:`MENTIONS`]-(n:`Human`:`Vip Human`)\nWITH DISTINCT n", match.statement)
        assertEquals(mapOf<String, Any?>("_fromId" to "c1"), match.bindings)
    }

    @Test
    fun `a target of no label is any node the relationship reaches`() {
        val match = EdgeStatements.relatedMatch(claim, "MENTIONS", Direction.UNDIRECTED, emptyList())

        assertEquals("MATCH (a:`Claim` {`id`: \$_fromId})-[:`MENTIONS`]-(n)\nWITH DISTINCT n", match.statement)
    }

    // ----- What is refused -----

    @Test
    fun `a relationship with no type is refused, whichever statement it is for`() {
        val refusals = listOf(
            assertFailsWith<IllegalArgumentException> { EdgeStatements.relate(claim, human, "", emptyMap(), RelateMode.MERGE) },
            assertFailsWith<IllegalArgumentException> { EdgeStatements.relate(claim, human, "   ", emptyMap(), RelateMode.CREATE) },
            assertFailsWith<IllegalArgumentException> { EdgeStatements.unrelate(claim, human, "\n") },
            assertFailsWith<IllegalArgumentException> { EdgeStatements.unrelateAll(claim, "", Direction.OUTGOING) },
            assertFailsWith<IllegalArgumentException> { EdgeStatements.relatedMatch(claim, " ", Direction.OUTGOING, listOf("Human")) },
        )
        refusals.forEach { assertEquals("A relationship needs a type.", it.message) }
    }

    @Test
    fun `a node of a class with no id cannot be named, at either end`() {
        val adrift = NodeRef(Adrift::class.java, "a1")
        val message = "Adrift has no @NodeId field, so a node of it cannot be referred to by id."

        assertEquals(message, assertFailsWith<IllegalArgumentException> { EdgeStatements.relate(adrift, human, "SEES", emptyMap(), RelateMode.MERGE) }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { EdgeStatements.unrelate(human, adrift, "SEES") }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { EdgeStatements.unrelateAll(adrift, "SEES", Direction.INCOMING) }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { EdgeStatements.relatedMatch(adrift, "SEES", Direction.OUTGOING, emptyList()) }.message)
    }
}
