package org.drivine.backend.sql

import org.drivine.backend.sql.Fragments.Companion.named
import org.drivine.connection.CypherCompile
import org.drivine.cypher.sql.FragmentVariable
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.SeekValueSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** How a fragment's Cypher has its parameters named apart (`$f<n>_<name>`): outside quoted text only (#841). */
class FragmentNamesTest {

    private val names = Fragments.Names(1)

    private fun String.renamed(vararg parameters: String) = named(names, parameters.toSet())

    @Test
    fun `a parameter is renamed, each time it occurs`() {
        assertEquals("n.a = \$f1_k OR n.b = \$f1_k", "n.a = \$k OR n.b = \$k".renamed("k"))
    }

    @Test
    fun `a property whose backtick-quoted key spells a parameter is still read by its key`() {
        assertEquals("n.`\$k` = \$f1_k", "n.`\$k` = \$k".renamed("k"))
        assertEquals("n.`a``\$k` = \$f1_k", "n.`a``\$k` = \$k".renamed("k"))
    }

    @Test
    fun `a string literal that spells a parameter is left as it is, an escaped quote and all`() {
        assertEquals("n.a = '\$k' AND n.b = \"\$k\" AND n.c = 'it\\'s \$k' AND n.d = \$f1_k", "n.a = '\$k' AND n.b = \"\$k\" AND n.c = 'it\\'s \$k' AND n.d = \$k".renamed("k"))
    }

    @Test
    fun `a comment is left as it is`() {
        assertEquals("n.a = \$f1_k /* \$k */ // \$k", "n.a = \$k /* \$k */ // \$k".renamed("k"))
    }

    @Test
    fun `a parameter is renamed by its whole name, not as the prefix of a longer one`() {
        assertEquals("n.a = \$f1_id OR n.b IN \$idList", "n.a = \$id OR n.b IN \$idList".renamed("id"))
        assertEquals("n.a = \$f1_id OR n.b IN \$f1_idList", "n.a = \$id OR n.b IN \$idList".renamed("id", "idList"))
    }

    @Test
    fun `quoted text that is never closed is taken to the end`() {
        assertEquals("n.a = \$f1_k AND n.b = '\$k", "n.a = \$k AND n.b = '\$k".renamed("k"))
        assertEquals("n.a = \$f1_k AND n.`\$k", "n.a = \$k AND n.`\$k".renamed("k"))
    }

    /** A compiler of its own for each statement, as each numbers its subqueries from one. */
    private val compiler get() = CypherCompile.postgresFragments("g")

    @Test
    fun `a keyset seek over a property whose key spells the cursor's parameter reads that property`() {
        val scope = StatementScope(55)
        val sql = Fragments(compiler, scope).after("n_", listOf("Moment"), listOf(OrderSpec("n.\$c0", OrderDirection.ASC)), listOf(SeekValueSpec("n.\$c0", "m")), 0)
        val expected = compiler.condition("n.`\$c0` > \$f1_c0", mapOf("n" to FragmentVariable.Node("n_", listOf("Moment"))), mapOf("f1_c0" to "m"))
        assertEquals(expected.terms.single(), sql)
        assertEquals(expected.parameters, scope.bindings())
    }

    @Test
    fun `an order over a property whose key spells its parameter reads that property`() {
        val terms = Fragments(compiler, StatementScope(55)).ordered("n_", "n.`\$k` + \$k", false, null, mapOf("k" to 1))
        assertEquals(compiler.order("n.`\$k` + \$f1_k", false, mapOf("n" to FragmentVariable.Node("n_")), mapOf("f1_k" to 1)).terms, terms)
    }
}
