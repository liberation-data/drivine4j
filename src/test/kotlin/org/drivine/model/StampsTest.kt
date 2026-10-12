package org.drivine.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a stamp is made of, what a save hands back of one, and the Cypher that reads and replaces its tokens. */
class StampsTest {

    private val node = "aaaaaaaaaaaaaaaa"
    private val links = "bbbbbbbbbbbbbbbb"
    private val now = "1111111111111111:2222222222222222"

    // ----- The stamp itself -----

    @Test
    fun `a fresh stamp is two tokens of sixteen hex digits, and no two are the same`() {
        val stamps = (1..200).map { Stamps.fresh() }

        stamps.forEach { assertTrue(Regex("[0-9a-f]{16}:[0-9a-f]{16}").matches(it), it) }
        assertEquals(stamps.size, stamps.distinct().size)
        // A token that begins with zeros keeps them: the length is what the statements cut by.
        stamps.forEach { assertTrue(Stamps.isTwoTokens(it), it) }
    }

    @Test
    fun `a stamp is two tokens only at its exact length with the colon between them`() {
        assertTrue(Stamps.isTwoTokens("$node:$links"))
        assertFalse(Stamps.isTwoTokens("$node:"))
        assertFalse(Stamps.isTwoTokens("$node:${links}b"))
        assertFalse(Stamps.isTwoTokens("$node-$links"))
        assertFalse(Stamps.isTwoTokens("${node.dropLast(1)}:${links}b"), "the colon is not where it belongs")
        assertFalse(Stamps.isTwoTokens(""))
        assertFalse(Stamps.isTwoTokens(Stamps.NEVER_STAMPED))
    }

    @Test
    fun `a stamp has a node token to compare when a token and a colon open it, whatever follows`() {
        assertTrue(Stamps.hasNodeToken("$node:$links"))
        assertTrue(Stamps.hasNodeToken("$node:"))
        assertTrue(Stamps.hasNodeToken("$node:anything at all"))
        assertFalse(Stamps.hasNodeToken(node), "a token and no colon")
        assertFalse(Stamps.hasNodeToken("short:$links"))
        assertFalse(Stamps.hasNodeToken(""))
    }

    @Test
    fun `the tokens of a stamp are what stands before and after its colon, and a stamp with nothing after it has no relationship token`() {
        assertEquals(node, Stamps.nodeToken("$node:$links"))
        assertEquals(links, Stamps.linksToken("$node:$links"))
        assertEquals(node, Stamps.nodeToken("$node:"))
        assertNull(Stamps.linksToken("$node:"))
        // A stamp of another shape is its own node token, and has no other.
        assertEquals("legacy", Stamps.nodeToken("legacy"))
        assertNull(Stamps.linksToken("legacy"))
        assertEquals("", Stamps.nodeToken(""))
        assertNull(Stamps.linksToken(""))
    }

    @Test
    fun `a stamp without its links keeps its colon, so it still has a node token to compare`() {
        assertEquals("$node:", Stamps.withoutLinks("$node:$links"))
        assertEquals("$node:", Stamps.withoutLinks("$node:"))
        assertTrue(Stamps.hasNodeToken(Stamps.withoutLinks("$node:$links")))
    }

    // ----- What a save hands back -----

    @Test
    fun `an object that carried no stamp is handed the whole stamp of a node the save made, and the node token alone of one that was there`() {
        assertEquals(now, Stamps.handedBack(carried = null, found = "", now = now))
        assertEquals("1111111111111111:", Stamps.handedBack(carried = null, found = "$node:$links", now = now))
        // A node that was there with no stamp was there all the same.
        assertEquals("1111111111111111:", Stamps.handedBack(carried = null, found = Stamps.NEVER_STAMPED, now = now))
    }

    @Test
    fun `an object whose node was found exactly as loaded is handed the new stamp, whatever shape the old one had`() {
        assertEquals(now, Stamps.handedBack("$node:$links", "$node:$links", now))
        assertEquals(now, Stamps.handedBack("legacy", "legacy", now))
        assertEquals(now, Stamps.handedBack("$node:", "$node:", now))
    }

    @Test
    fun `each token is handed back only if what it speaks for was as the object's stamp says`() {
        val otherLinks = "cccccccccccccccc"
        val otherNode = "dddddddddddddddd"

        // Its relationships changed since it was loaded: the node token is new, the links token its own.
        assertEquals("1111111111111111:$links", Stamps.handedBack("$node:$links", "$node:$otherLinks", now))
        // Its data changed: the links token is new, the node token its own, so a later save of it is refused.
        assertEquals("$node:2222222222222222", Stamps.handedBack("$node:$links", "$otherNode:$links", now))
        // Both changed: it keeps the stamp it had.
        assertEquals("$node:$links", Stamps.handedBack("$node:$links", "$otherNode:$otherLinks", now))
    }

    @Test
    fun `an object that vouched for no relationships is handed none, though its node was as it says`() {
        assertEquals("1111111111111111:", Stamps.handedBack("$node:", "$node:$links", now))
    }

    @Test
    fun `an object that carried a stamp keeps it when the node was found with none`() {
        assertEquals("$node:$links", Stamps.handedBack("$node:$links", Stamps.NEVER_STAMPED, now))
        assertEquals("$node:$links", Stamps.handedBack("$node:$links", "", now))
    }

    // ----- The Cypher -----

    private val two = "CASE WHEN size(n.`__drivine.stamp`) = 33 AND substring(n.`__drivine.stamp`, 16, 1) = ':' THEN n.`__drivine.stamp` ELSE null END"

    @Test
    fun `the lock sets and removes one property on every node it names, in one SET and one REMOVE`() {
        assertEquals("SET a.`__drivine.lock` = true REMOVE a.`__drivine.lock`", Stamps.lock("a"))
        assertEquals(
            "SET a.`__drivine.lock` = true, b.`__drivine.lock` = true REMOVE a.`__drivine.lock`, b.`__drivine.lock`",
            Stamps.lock("a", "b"),
        )
    }

    @Test
    fun `a node the statement made is told from one it found, which with no stamp reports the mark of one never stamped`() {
        assertEquals("ON CREATE SET n.`__drivine.made` = true", Stamps.onCreate("n"))
        assertEquals("n.`__drivine.made`", Stamps.unmark("n"))
        assertEquals("CASE WHEN n.`__drivine.made` IS NOT NULL THEN '' ELSE coalesce(n.`__drivine.stamp`, '-') END", Stamps.foundOf("n"))
    }

    @Test
    fun `a stored stamp counts as one only when it is two tokens, cut at the length a token has`() {
        assertEquals(two, Stamps.twoTokensOf("n"))
        assertEquals("left(n.`__drivine.stamp`, 16)", Stamps.nodeTokenOf("n"))
        assertEquals("right(n.`__drivine.stamp`, 16)", Stamps.linksTokenOf("n"))
    }

    @Test
    fun `a restamp that always holds takes the node token offered and keeps the links the node has, or takes those offered too`() {
        assertEquals(
            "n.`__drivine.stamp` = left(\$_stamp, 16) + coalesce(right($two, 17), right(\$_stamp, 17))",
            Stamps.restamp("n", Stamps.ALWAYS, "\$_stamp"),
        )
    }

    @Test
    fun `a relink that always holds keeps the node token the node has, or takes the one offered, and takes the links offered`() {
        assertEquals(
            "n.`__drivine.stamp` = coalesce(left($two, 17), left(\$_mark, 17)) + right(\$_mark, 16)",
            Stamps.relink("n", Stamps.ALWAYS, "\$_mark"),
        )
    }

    @Test
    fun `under a condition a node keeps its stamp when the condition fails, unless it has none of two tokens`() {
        val restamp = Stamps.restamp("n", "changed", "row.stamp")
        val relink = Stamps.relink("n", "_had = 0 OR _same < _had", "\$_mark")

        assertTrue(restamp.startsWith("n.`__drivine.stamp` = CASE WHEN (changed) OR $two IS NULL THEN left(row.stamp, 16) + "), restamp)
        assertTrue(restamp.endsWith(" ELSE n.`__drivine.stamp` END"), restamp)
        // The condition is one operand of the OR, whatever it holds.
        assertTrue(relink.startsWith("n.`__drivine.stamp` = CASE WHEN (_had = 0 OR _same < _had) OR $two IS NULL THEN "), relink)
        assertNotEquals(Stamps.relink("n", Stamps.ALWAYS, "\$_mark"), relink)
    }

    @Test
    fun `the clause for Cypher of the caller's replaces one token with one the engine makes, and keeps the other the node has`() {
        val token = "left(replace(randomUUID(), '-', ''), 16)"

        assertEquals("p.`__drivine.stamp` = $token + ':' + coalesce(right(${two.replace("n.", "p.")}, 16), $token)", Stamps.setClause("p"))
        assertEquals("p.`__drivine.stamp` = coalesce(left(${two.replace("n.", "p.")}, 16), $token) + ':' + $token", Stamps.linksClause("p"))
    }

    @Test
    fun `the stamp's property is quoted wherever a statement names it, for it has a dot in it`() {
        assertEquals("__drivine.stamp", Stamps.PROPERTY)
        assertEquals("`__drivine.stamp`", Stamps.QUOTED)
        assertEquals(2 * Stamps.TOKEN + 1, Stamps.fresh().length)
    }
}
