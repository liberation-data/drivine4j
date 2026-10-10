package org.drivine.query

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.drivine.manager.NullPolicy
import org.drivine.manager.RemovedTargets
import org.drivine.mapper.Neo4jObjectMapper
import org.junit.jupiter.api.Test
import sample.stateless.Citation
import sample.stateless.Claim
import sample.stateless.ClaimCitations
import sample.stateless.ClaimView
import sample.stateless.Company
import sample.stateless.Human
import sample.stateless.HumanClaims

/** The text of a save statement: an engine plans a statement once for each text it is given. */
class SaveStatementBuilderTest {

    private val builder = SaveStatementBuilder(Neo4jObjectMapper.instance, null, null)

    private fun people(count: Int) = (1..count).map { Human("h$it", "Human $it") }

    private fun statement(view: Any, replaced: RemovedTargets? = null): String =
        builder.build(view, checked = true, NullPolicy.IGNORE, replaced = { replaced }).statement

    @Test
    fun `the statement is the same text however long a list is`() {
        val few = statement(ClaimView(Claim("c1", "one"), people = people(2), companies = listOf(Company("acme", "Acme"))))
        val many = statement(ClaimView(Claim("c1", "one"), people = people(500), companies = (1..40).map { Company("co$it", "Co $it") }))

        assertEquals(few, many)
    }

    @Test
    fun `the statement is the same text however long a replaced list is`() {
        val few = statement(ClaimView(Claim("c1", "one"), people = people(2)), RemovedTargets.DELETE_UNREFERENCED)
        val many = statement(ClaimView(Claim("c1", "one"), people = people(500)), RemovedTargets.DELETE_UNREFERENCED)

        assertEquals(few, many)
    }

    @Test
    fun `the statement is the same text whatever the relationships' properties hold`() {
        val few = statement(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(1, Human("ada", "Ada")))))
        val many = statement(ClaimCitations(Claim("c1", "one"), cited = people(300).mapIndexed { page, human -> Citation(page, human) }))

        assertEquals(few, many)
    }

    @Test
    fun `the statement is the same text however long a replaced list is, when its targets are kept`() {
        val few = statement(ClaimView(Claim("c1", "one"), people = people(2)), RemovedTargets.KEEP)
        val many = statement(ClaimView(Claim("c1", "one"), people = people(500)), RemovedTargets.KEEP)

        assertEquals(few, many)
        assertNotEquals(statement(ClaimView(Claim("c1", "one"), people = people(2))), few, "a replace has a part that removes")
    }

    @Test
    fun `the statement is the same text however long a replaced list of relationships with properties is`() {
        val few = statement(ClaimCitations(Claim("c1", "one"), cited = listOf(Citation(1, Human("ada", "Ada")))), RemovedTargets.DELETE_UNREFERENCED)
        val many = statement(
            ClaimCitations(Claim("c1", "one"), cited = people(300).mapIndexed { page, human -> Citation(page, human) }),
            RemovedTargets.DELETE_UNREFERENCED,
        )

        assertEquals(few, many)
    }

    @Test
    fun `the statement is the same text however long a list of stamped nodes read against its direction is`() {
        fun claims(count: Int) = (1..count).map { Claim("k$it", "Claim $it") }
        val few = statement(HumanClaims(Human("ada", "Ada"), claims = claims(2)))
        val many = statement(HumanClaims(Human("ada", "Ada"), claims = claims(400)))

        assertEquals(few, many)
    }

    @Test
    fun `the statement is the same text for an unchecked save`() {
        fun unchecked(view: Any) = builder.build(view, checked = false, NullPolicy.IGNORE).statement

        assertEquals(
            unchecked(ClaimView(Claim("c1", "one"), people = people(2))),
            unchecked(ClaimView(Claim("c1", "one"), people = people(500))),
        )
    }

    @Test
    fun `a field that holds nothing has no part`() {
        val both = statement(ClaimView(Claim("c1", "one"), people = people(1), companies = listOf(Company("acme", "Acme"))))
        val one = statement(ClaimView(Claim("c1", "one"), people = people(1)))

        assertNotEquals(both, one)
    }
}
