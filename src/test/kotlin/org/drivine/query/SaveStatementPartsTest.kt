package org.drivine.query

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.drivine.manager.NullPolicy
import org.drivine.manager.RemovedTargets
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.FragmentModel
import org.drivine.model.Stamps
import org.junit.jupiter.api.Test
import sample.stateless.Board
import sample.stateless.Citation
import sample.stateless.Claim
import sample.stateless.ClaimCitations
import sample.stateless.ClaimStaff
import sample.stateless.ClaimSupports
import sample.stateless.ClaimTags
import sample.stateless.ClaimView
import sample.stateless.Human
import sample.stateless.Manager
import sample.stateless.Memo
import sample.stateless.Tagged
import sample.stateless.Worker

/** The parts of a save statement: their parameters, what each removes, and what it reports of the nodes it saves. */
class SaveStatementPartsTest {

    private val builder = SaveStatementBuilder(Neo4jObjectMapper.instance, null, null)

    private fun build(obj: Any, replaced: RemovedTargets? = null, checked: Boolean = true, createsRoot: Boolean = true): SaveStatement =
        builder.build(obj, checked, NullPolicy.IGNORE, replaced = { replaced }, createsRoot = createsRoot)

    /** The parameters [statement] names, a quoted identifier or a string not being one. */
    private fun parameters(statement: String): Set<String> =
        Regex("\\$([A-Za-z0-9_]+)").findAll(statement.replace(Regex("`[^`]*`|'[^']*'"), "")).map { it.groupValues[1] }.toSet()

    @Test
    fun `every parameter a statement names is bound, and no part shares one with another`() {
        val statement = build(
            ClaimTags(Claim("c1", "one", stamp = "aaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbb"), tags = listOf(Tagged("t1", "x", meta = mapOf("k" to "v")), Tagged("t2", "y"))),
            RemovedTargets.DELETE_UNREFERENCED,
        )

        assertEquals(statement.bindings.keys, parameters(statement.statement))
        // The root and each tag have a text of their own.
        assertEquals(3, statement.bindings.keys.count { it.endsWith("_text") })
    }

    @Test
    fun `a bag key that holds a parameter's name is written as given`() {
        val root = build(Tagged("t1", "x", meta = mapOf("\$id" to "v", "cost\$text" to "w")))
        val related = build(ClaimTags(Claim("c1", "one"), tags = listOf(Tagged("t1", "x", meta = mapOf("\$id" to "v")))))

        assertContains(root.statement, "n.`meta.\$id` = ")
        assertContains(root.statement, "n.`meta.cost\$text` = ")
        assertContains(related.statement, "n.`meta.\$id` = ")
        assertTrue(root.bindings.keys.containsAll(parameters(root.statement)))
    }

    @Test
    fun `a fragment builder names its parameters with the prefix it is given`() {
        val statement = FragmentMergeBuilder(FragmentModel.from(Tagged::class.java), Neo4jObjectMapper.instance, parameterPrefix = "p7_")
            .buildMergeStatement(Tagged("t1", "x", meta = mapOf("k" to "v")), null)

        assertEquals(setOf("p7_id", "p7_text", "p7__bag0"), statement.bindings.keys)
        assertEquals(statement.bindings.keys, parameters(statement.statement))
    }

    @Test
    fun `a part writes a relationship when there was none or one of those there had other properties`() {
        val statement = build(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(1, Human("ada", "Ada"))))).statement

        assertContains(statement, "CASE WHEN (_had = 0 OR _same < _had) THEN")
        assertFalse(statement.contains("_same = 0"))
    }

    @Test
    fun `a replaced field keeps what a field beside it holds of the nodes both read`() {
        val statement = build(
            ClaimStaff(Claim("c1", "one"), workers = listOf(Worker("wes", "Wes")), managers = listOf(Manager("mo", "Mo"))),
            RemovedTargets.KEEP,
        )

        val kept = statement.bindings.filterKeys { it.endsWith("_ids") }.values.map { (it as List<*>).toSet() }
        // The workers keep the manager, who is a worker; the managers do not keep the worker, who is no manager.
        assertEquals(listOf(setOf("mo"), setOf("wes", "mo")), kept)
    }

    @Test
    fun `a replaced field does not delete a node a nested view holds`() {
        val statement = build(
            Board(Memo("m1", "memo"), attendees = emptyList(), claims = listOf(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))),
            RemovedTargets.DELETE_UNREFERENCED,
        )

        val held = statement.bindings.filterKeys { it.endsWith("_held") }.values.map { (it as List<*>).toSet() }
        assertTrue(setOf<Any?>("ada") in held, "held: $held")
    }

    @Test
    fun `a statement says which of its stamped fragments are one node, and which were not written`() {
        val (first, last) = Claim("k", "first") to Claim("k", "last")
        val statement = build(ClaimSupports(Claim("c1", "one"), supports = listOf(first, last, Claim("other", "other"))))

        assertEquals(listOf(last, statement.stamped.last()), statement.stamped)
        assertTrue(statement.keys[0]!!.sameNodeAs(NodeKey(setOf("Claim"), "k")))
        assertEquals(listOf<Any>(first), statement.repeats.map { it.fragment })
        assertTrue(statement.rootKey.sameNodeAs(NodeKey(setOf("Claim"), "c1")))
    }

    @Test
    fun `a node a statement makes is told from one it finds, and the mark is taken off again`() {
        val statement = build(ClaimSupports(Claim("c1", "one"), supports = listOf(Claim("k", "held")))).statement

        // The root and the related nodes: each MERGE marks what it makes, and each mark is removed.
        assertEquals(2, Regex("ON CREATE SET n\\.`__drivine\\.made` = true").findAll(statement).count())
        assertEquals(2, Regex("REMOVE n\\.`__drivine\\.made`").findAll(statement).count())
        assertContains(statement, "CASE WHEN _was = '' THEN _r0.${Stamps.QUOTED} ELSE left(_r0.${Stamps.QUOTED}, 17) END")
    }

    @Test
    fun `a statement that does not make its root matches it`() {
        val statement = build(Memo("m1", "one"), createsRoot = false)

        assertTrue(statement.statement.startsWith("MATCH (n:Memo {id: \$p0_id})"), statement.statement)
        assertFalse(statement.statement.contains("MERGE"))
        assertFalse(statement.createsRoot)
    }

    @Test
    fun `a statement offers the stamps it writes, so a token of one is known for its own`() {
        val statement = build(ClaimView(Claim("c1", "one"), people = listOf(Human("ada", "Ada"))))

        assertEquals(2, statement.offered.size)
        assertTrue(statement.bindings.values.containsAll(statement.offered))
    }
}
