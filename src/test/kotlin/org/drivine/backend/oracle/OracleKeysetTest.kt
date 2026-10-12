package org.drivine.backend.oracle

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.NodeStamp
import org.drivine.annotation.PropertyBag
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

/** The order of the roots and the predicate that seeks past a keyset cursor, as written on Oracle. */
class OracleKeysetTest {

    private fun select() = OracleSelect(OracleGraph("g"), Moment::class.java)

    private val title = "JSON_VALUE(n_.props, '$.title')"
    private val count = "JSON_VALUE(n_.props, '$.count' RETURNING NUMBER)"

    // ----- after() -----

    @Test
    fun `an ascending order seeks beyond the cursor, a descending one before it`() {
        val up = select()
        assertEquals("$title > \$_p0", up.after(listOf(OrderSpec("n.title", ASC)), listOf(SeekValueSpec("n.title", "m")), 0))
        assertEquals(mapOf<String, Any?>("_p0" to "m"), up.bindings())

        val down = select()
        assertEquals("$title < \$_p0", down.after(listOf(OrderSpec("n.title", DESC)), listOf(SeekValueSpec("n.title", "m")), 0))
    }

    @Test
    fun `two keys expand to the row beyond the first, or level on it and beyond the second`() {
        val select = select()
        val sql = select.after(
            listOf(OrderSpec("n.title", ASC), OrderSpec("n.count", DESC)),
            listOf(SeekValueSpec("n.title", "m"), SeekValueSpec("n.count", 7L)),
            0,
        )
        assertEquals("($title > \$_p0 OR ($title = \$_p0 AND $count < \$_p1))", sql)
        assertEquals(mapOf<String, Any?>("_p0" to "m", "_p1" to 7L), select.bindings())
    }

    @Test
    fun `the cursor's values are bound in the order of the keys`() {
        val select = select()
        select.after(
            listOf(OrderSpec("n.count", ASC), OrderSpec("n.title", ASC), OrderSpec("n.id", ASC)),
            listOf(SeekValueSpec("n.count", 1L), SeekValueSpec("n.title", "t"), SeekValueSpec("n.id", "i")),
            0,
        )
        assertEquals(listOf("_p0", "_p1", "_p2"), select.bindings().keys.toList())
        assertEquals(listOf<Any?>(1L, "t", "i"), select.bindings().values.toList())
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
    fun `a property no field declares is compared as the kind of its cursor value`() {
        val numeric = select()
        assertEquals("JSON_VALUE(n_.props, '$.score.numberOnly()' RETURNING NUMBER) > \$_p0", numeric.after(listOf(OrderSpec("n.score", ASC)), listOf(SeekValueSpec("n.score", 42)), 0))

        val textual = select()
        assertEquals("JSON_VALUE(n_.props, '$.score') > \$_p0", textual.after(listOf(OrderSpec("n.score", ASC)), listOf(SeekValueSpec("n.score", "b")), 0))
    }

    @Test
    fun `the stamp is sought by its column`() {
        val select = select()
        assertEquals("n_.stamp > \$_p0", select.after(listOf(OrderSpec("n.${Stamps.PROPERTY}", ASC)), listOf(SeekValueSpec("n.${Stamps.PROPERTY}", "s")), 0))
    }

    // ----- orderTerms(), through load -----

    private fun orderClause(select: OracleSelect, vararg orders: OrderSpec, predicates: List<String> = emptyList()): String =
        select.load(predicates, orders.toList()).statement.substringAfter("\nORDER BY ")

    @Test
    fun `a declared number is ordered as a number and a declared moment as a timestamp`() {
        assertEquals("$count DESC", orderClause(select(), OrderSpec("n.count", DESC)))
        assertEquals("JSON_VALUE(n_.props, '$.at' RETURNING TIMESTAMP WITH TIME ZONE) ASC", orderClause(select(), OrderSpec("n.at", ASC)))
    }

    @Test
    fun `a declared string is ordered as text`() {
        assertEquals("$title ASC", orderClause(select(), OrderSpec("n.title", ASC)))
    }

    @Test
    fun `the stamp is ordered by its column`() {
        assertEquals("n_.stamp DESC", orderClause(select(), OrderSpec("n.${Stamps.PROPERTY}", DESC)))
    }

    @Test
    fun `a property no field declares, with no cursor, is ordered as a number and then as text`() {
        // The regression: a page of numbers ordered as text is not in the order a seek with one of them compares in.
        assertEquals(
            "JSON_VALUE(n_.props, '$.score.numberOnly()' RETURNING NUMBER) ASC, JSON_VALUE(n_.props, '$.score') ASC",
            orderClause(select(), OrderSpec("n.score", ASC)),
        )
    }

    @Test
    fun `after a seek, a property no field declares is ordered as the cursor's kind alone`() {
        val select = select()
        val seek = select.after(listOf(OrderSpec("n.score", DESC)), listOf(SeekValueSpec("n.score", 10)), 0)
        val statement = select.load(listOf(seek), listOf(OrderSpec("n.score", DESC))).statement
        val order = statement.substringAfter("\nORDER BY ")
        assertEquals("JSON_VALUE(n_.props, '$.score.numberOnly()' RETURNING NUMBER) DESC", order)
        assertEquals(1, Regex(Regex.escape("$.score")).findAll(order).count(), "one term, not a number form and then a text form: $order")
        assertTrue(statement.contains("WHERE JSON_VALUE(n_.props, '$.score.numberOnly()' RETURNING NUMBER) < \$_p0\nORDER BY"), statement)
    }

    @Test
    fun `several orders are written in turn`() {
        assertEquals("$title ASC, $count DESC", orderClause(select(), OrderSpec("n.title", ASC), OrderSpec("n.count", DESC)))
    }
}
