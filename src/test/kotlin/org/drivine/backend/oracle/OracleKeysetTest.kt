package org.drivine.backend.oracle

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.PropertyBag
import org.drivine.connection.CypherCompile
import org.drivine.cypher.sql.FragmentVariable
import org.drivine.model.Stamps
import org.drivine.query.dsl.OrderDirection.ASC
import org.drivine.query.dsl.OrderDirection.DESC
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.SeekValueSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A fragment with a number, a moment, a stamp and a bag, so an order by each kind can be written. */
@NodeFragment(labels = ["Moment"])
data class Moment(
    @NodeId val id: String,
    val title: String,
    val count: Long,
    val at: Instant,
    @PropertyBag val extra: Map<String, Any?> = emptyMap(),
    @NodeStamp val stamp: String? = null,
)

/**
 * The order of the roots and the predicate that seeks past a keyset cursor, as written on Oracle: the Cypher of each, over
 * the root `n`, compiled by the module as a fragment, so a value is compared and ordered as Cypher compares and orders it,
 * whatever kind it is and whatever field declares it.
 */
class OracleKeysetTest {

    private fun select() = OracleSelect(OracleGraph("g"), Moment::class.java)

    private val fragments = CypherCompile.oracleFragments("g", emptySet())

    /**
     * The seek the module compiles [cypher] to over the root `n_`, a Moment, with [values] as the statement's first fragment
     * binds them (`$f1_<name>`).
     */
    private fun seek(cypher: String, values: Map<String, Any?>): String =
        fragments.condition(cypher, mapOf("n" to FragmentVariable.Node("n_", listOf("Moment"))), values.mapKeys { (name, _) -> "f1_$name" }).terms.single()

    /** The terms the module orders the root `n_` by [cypher]. */
    private fun terms(cypher: String, descending: Boolean): List<String> =
        fragments.order(cypher, descending, mapOf("n" to FragmentVariable.Node("n_"))).terms

    /** The values a statement binds for the cursor's [values], by the names the module gives the first fragment's: as JSON text. */
    private fun bound(vararg values: Pair<String, String>): Map<String, Any?> = values.associate { (name, json) -> "_cy_f1_$name" to json }

    // ----- after() -----

    @Test
    fun `an ascending order seeks beyond the cursor, a descending one before it`() {
        val up = select()
        assertEquals(seek("n.`title` > \$f1_c0", mapOf("c0" to "m")), up.after(listOf(OrderSpec("n.title", ASC)), listOf(SeekValueSpec("n.title", "m")), 0))
        assertEquals(bound("c0" to "\"m\""), up.bindings())

        val down = select()
        assertEquals(seek("n.`title` < \$f1_c0", mapOf("c0" to "m")), down.after(listOf(OrderSpec("n.title", DESC)), listOf(SeekValueSpec("n.title", "m")), 0))
    }

    @Test
    fun `two keys expand to the row beyond the first, or level on it and beyond the second`() {
        val select = select()
        val sql = select.after(
            listOf(OrderSpec("n.title", ASC), OrderSpec("n.count", DESC)),
            listOf(SeekValueSpec("n.title", "m"), SeekValueSpec("n.count", 7L)),
            0,
        )
        val cypher = "(n.`title` > \$f1_c0 OR (n.`title` = \$f1_c0 AND n.`count` < \$f1_c1))"
        assertEquals(seek(cypher, mapOf("c0" to "m", "c1" to 7L)), sql)
        assertEquals(bound("c0" to "\"m\"", "c1" to "7"), select.bindings())
    }

    @Test
    fun `the cursor's values are bound in the order of the keys`() {
        val select = select()
        select.after(
            listOf(OrderSpec("n.count", ASC), OrderSpec("n.title", ASC), OrderSpec("n.id", ASC)),
            listOf(SeekValueSpec("n.count", 1L), SeekValueSpec("n.title", "t"), SeekValueSpec("n.id", "i")),
            0,
        )
        assertEquals(bound("c0" to "1", "c1" to "\"t\"", "c2" to "\"i\""), select.bindings())
        assertEquals(listOf("_cy_f1_c0", "_cy_f1_c1", "_cy_f1_c2"), select.bindings().keys.toList())
    }

    @Test
    fun `a seek with no root order is refused`() {
        val failure = assertThrows<IllegalArgumentException> { select().after(emptyList(), listOf(SeekValueSpec("n.title", "m")), 0) }
        assertTrue(failure.message!!.contains("seek requires at least one root orderBy property"), failure.message)
        assertFalse(failure.message!!.contains("relationship collection"), failure.message)
    }

    @Test
    fun `a cursor of the wrong arity is refused, and told when some orders sort a collection`() {
        val plain = assertThrows<IllegalArgumentException> {
            select().after(listOf(OrderSpec("n.title", ASC)), listOf(SeekValueSpec("n.title", "m"), SeekValueSpec("n.count", 1L)), 0)
        }
        assertTrue(plain.message!!.contains("seek supplied 2 cursor values for 1 root orderBy properties"), plain.message)
        assertFalse(plain.message!!.contains("relationship collection"), plain.message)

        val hinted = assertThrows<IllegalArgumentException> {
            select().after(listOf(OrderSpec("n.title", ASC)), listOf(SeekValueSpec("n.title", "m"), SeekValueSpec("n.count", 1L)), 1)
        }
        assertTrue(hinted.message!!.contains("1 declared orderBy properties sort a relationship collection, which cannot participate in a keyset cursor"), hinted.message)

        val none = assertThrows<IllegalArgumentException> { select().after(emptyList(), emptyList(), 2) }
        assertTrue(none.message!!.contains("2 declared orderBy properties sort a relationship collection"), none.message)
    }

    @Test
    fun `a cursor value on another property than the order is refused by position`() {
        val failure = assertThrows<IllegalArgumentException> {
            select().after(
                listOf(OrderSpec("n.title", ASC), OrderSpec("n.count", ASC)),
                listOf(SeekValueSpec("n.title", "m"), SeekValueSpec("n.id", "x")),
                0,
            )
        }
        assertEquals("seek property #2 is n.id, but orderBy property #2 is n.count", failure.message)
    }

    @Test
    fun `a property no field declares is compared as Cypher compares it, whatever kind its cursor value is`() {
        val numeric = select()
        assertEquals(seek("n.`score` > \$f1_c0", mapOf("c0" to 42)), numeric.after(listOf(OrderSpec("n.score", ASC)), listOf(SeekValueSpec("n.score", 42)), 0))
        val textual = select()
        assertEquals(seek("n.`score` > \$f1_c0", mapOf("c0" to "b")), textual.after(listOf(OrderSpec("n.score", ASC)), listOf(SeekValueSpec("n.score", "b")), 0))
        // One condition for either kind: the cursor's value decides only what is bound.
        assertEquals(seek("n.`score` > \$f1_c0", mapOf("c0" to 42)), seek("n.`score` > \$f1_c0", mapOf("c0" to "b")))
        assertEquals(bound("c0" to "42"), numeric.bindings())
        assertEquals(bound("c0" to "\"b\""), textual.bindings())
    }

    @Test
    fun `the stamp is sought as the module reads it, from its column`() {
        val sql = select().after(listOf(OrderSpec("n.${Stamps.PROPERTY}", ASC)), listOf(SeekValueSpec("n.${Stamps.PROPERTY}", "s")), 0)
        assertEquals(seek("n.`${Stamps.PROPERTY}` > \$f1_c0", mapOf("c0" to "s")), sql)
        assertTrue("n_.stamp" in sql && "${Stamps.PROPERTY}" !in sql, sql)
    }

    // ----- orderTerms(), through load -----

    private fun orderClause(select: OracleSelect, vararg orders: OrderSpec, predicates: List<String> = emptyList()): String =
        select.load(predicates, orders.toList()).statement.substringAfter("\nORDER BY ")

    @Test
    fun `a declared number, a declared moment and a declared string are each ordered as Cypher orders a value`() {
        assertEquals(terms("n.`count`", true).joinToString(", "), orderClause(select(), OrderSpec("n.count", DESC)))
        // A moment by its tag's sort key, as the module orders a temporal value.
        val at = orderClause(select(), OrderSpec("n.at", ASC))
        assertEquals(terms("n.`at`", false).joinToString(", "), at)
        assertTrue("'$.at.s'" in at, at)
        assertEquals(terms("n.`title`", false).joinToString(", "), orderClause(select(), OrderSpec("n.title", ASC)))
    }

    @Test
    fun `the stamp is ordered as the module reads it, from its column`() {
        val order = orderClause(select(), OrderSpec("n.${Stamps.PROPERTY}", DESC))
        assertEquals(terms("n.`${Stamps.PROPERTY}`", true).joinToString(", "), order)
        assertTrue("n_.stamp" in order, order)
    }

    @Test
    fun `a property no field declares is ordered as Cypher orders it, by kind and then by value`() {
        // The regression: a page of numbers ordered as text is not in the order a seek with one of them compares in.
        assertEquals(terms("n.`score`", false).joinToString(", "), orderClause(select(), OrderSpec("n.score", ASC)))
    }

    @Test
    fun `after a seek, a property no field declares is ordered as the seek compares it`() {
        val select = select()
        val seek = select.after(listOf(OrderSpec("n.score", DESC)), listOf(SeekValueSpec("n.score", 10)), 0)
        val statement = select.load(listOf(seek), listOf(OrderSpec("n.score", DESC))).statement
        assertEquals(terms("n.`score`", true).joinToString(", "), statement.substringAfter("\nORDER BY "))
        assertTrue(statement.contains("WHERE $seek\nORDER BY"), statement)
    }

    @Test
    fun `several orders are written in turn`() {
        assertEquals(
            (terms("n.`title`", false) + terms("n.`count`", true)).joinToString(", "),
            orderClause(select(), OrderSpec("n.title", ASC), OrderSpec("n.count", DESC)),
        )
    }
}
