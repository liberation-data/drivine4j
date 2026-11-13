package org.drivine.backend.oracle

import org.drivine.connection.CypherCompile
import org.drivine.cypher.sql.FragmentVariable
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.dsl.WhereCondition.LabelCondition
import org.drivine.query.dsl.WhereCondition.PropertyCondition
import org.drivine.query.dsl.WhereCondition.RelationshipCondition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Dossier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The predicates the query DSL's conditions become on Oracle, written without a database: the Cypher the DSL writes for
 * Neo4j, compiled whole by the module as one fragment over the root's row ([org.drivine.backend.sql.Fragments]), so each
 * value is compared as Cypher compares it and bound as JSON text.
 */
class OraclePredicatesTest {

    private val graph = OracleGraph("g")

    private fun fragment() = OracleSelect(graph, Claim::class.java)

    private fun view() = OracleSelect(graph, ClaimView::class.java)

    /** A compiler of one statement's fragments, as each statement has its own: it numbers what it shares within the statement. */
    private val fragments get() = CypherCompile.oracleFragments("g", emptySet())

    /** The predicate one condition on the root fragment `n` makes, with what it binds. */
    private fun compared(key: String, operator: ComparisonOperator, value: Any?): Pair<String, Map<String, Any?>> {
        val select = fragment()
        val sql = assertNotNull(select.predicate(listOf(PropertyCondition("n.$key", operator, value))))
        return sql to select.bindings()
    }

    /**
     * The condition the module compiles [cypher] to over the root `n_`, a Claim: `$v` in it the one value [bound] holds, by
     * the name the statement binds it under, given as [value].
     */
    private fun expected(cypher: String, bound: Map<String, Any?>, value: Any?): String {
        val name = bound.keys.singleOrNull()?.removePrefix("_cy_")
        val written = name?.let { cypher.replace("\$v", "\$$it") } ?: cypher
        return fragments.condition(written, mapOf("n" to FragmentVariable.Node("n_", listOf("Claim"))), name?.let { mapOf(it to value) }.orEmpty()).terms.single()
    }

    /** [value] as the module binds it: its JSON text. */
    private fun json(value: Any?): String = Neo4jObjectMapper.instance.writeValueAsString(value)

    // ----- One case per operator -----

    @Test
    fun `each operator is the Cypher Neo4j is given for it, compiled by the module, its value bound as JSON text`() {
        val cases = listOf(
            Triple("name", ComparisonOperator.EQUALS, "ada") to "n.name = \$v",
            Triple("rank", ComparisonOperator.EQUALS, 3) to "n.rank = \$v",
            Triple("done", ComparisonOperator.EQUALS, true) to "n.done = \$v",
            Triple("name", ComparisonOperator.NOT_EQUALS, "ada") to "n.name <> \$v",
            Triple("rank", ComparisonOperator.GREATER_THAN, 1) to "n.rank > \$v",
            Triple("rank", ComparisonOperator.GREATER_THAN_OR_EQUAL, 1) to "n.rank >= \$v",
            Triple("rank", ComparisonOperator.LESS_THAN, 1) to "n.rank < \$v",
            Triple("rank", ComparisonOperator.LESS_THAN_OR_EQUAL, 1) to "n.rank <= \$v",
            Triple("name", ComparisonOperator.IN, listOf("ada", "grace")) to "n.name IN \$v",
            Triple("rank", ComparisonOperator.IN, listOf(1, 2)) to "n.rank IN \$v",
            Triple("name", ComparisonOperator.NOT_IN, listOf("ada")) to "NOT n.name IN \$v",
            Triple("tags", ComparisonOperator.HAS_ELEMENT, "x") to "\$v IN n.tags",
            Triple("flags", ComparisonOperator.HAS_ELEMENT, true) to "\$v IN n.flags",
            Triple("name", ComparisonOperator.CONTAINS, "da") to "n.name CONTAINS \$v",
            Triple("name", ComparisonOperator.STARTS_WITH, "ad") to "n.name STARTS WITH \$v",
            Triple("name", ComparisonOperator.ENDS_WITH, "da") to "n.name ENDS WITH \$v",
            Triple("name", ComparisonOperator.CONTAINS_IGNORE_CASE, "AdA") to "toLower(n.name) CONTAINS \$v",
            Triple("name", ComparisonOperator.EQUALS_IGNORE_CASE, "AdA") to "toLower(n.name) = \$v",
            Triple("name", ComparisonOperator.MATCHES, "(?i)a.*") to "n.name =~ \$v",
            // The empty string, which Oracle keeps as null, is bound as JSON text, which keeps it apart from null.
            Triple("name", ComparisonOperator.EQUALS, "") to "n.name = \$v",
            Triple("name", ComparisonOperator.NOT_EQUALS, "") to "n.name <> \$v",
            Triple("name", ComparisonOperator.CONTAINS, "") to "n.name CONTAINS \$v",
            Triple("name", ComparisonOperator.STARTS_WITH, "") to "n.name STARTS WITH \$v",
            Triple("name", ComparisonOperator.ENDS_WITH, "") to "n.name ENDS WITH \$v",
        )
        for ((case, cypher) in cases) {
            val (key, operator, given) = case
            // An operator that ignores case binds the value lower-cased, as the DSL binds it for Neo4j, and lowers the property.
            val value = if (operator == ComparisonOperator.CONTAINS_IGNORE_CASE || operator == ComparisonOperator.EQUALS_IGNORE_CASE) (given as String).lowercase() else given
            val (sql, bound) = compared(key, operator, given)
            assertEquals(expected(cypher, bound, value), sql, "$operator $given")
            // A pattern is bound as written: the module anchors it to the whole value, a leading flag group among it.
            assertEquals(listOf<Any?>(json(value)), bound.values.toList(), "$operator $given")
        }
    }

    @Test
    fun `is null and is not null ask whether the property has a value, and bind nothing`() {
        for ((operator, cypher) in listOf(ComparisonOperator.IS_NULL to "n.name IS NULL", ComparisonOperator.IS_NOT_NULL to "n.name IS NOT NULL")) {
            val (sql, bound) = compared("name", operator, null)
            assertEquals(expected(cypher, bound, null), sql)
            assertTrue(bound.isEmpty())
        }
    }

    @Test
    fun `a key with a dot in it is quoted in the path, so it is one key`() {
        val (sql, bound) = compared("metadata.source", ComparisonOperator.EQUALS, "web")
        assertEquals(expected("n.`metadata.source` = \$v", bound, "web"), sql)
        assertTrue("'$.\"metadata.source\"" in sql, sql)
    }

    @Test
    fun `the stamp is a column, not a property`() {
        val (sql, bound) = compared("__drivine.stamp", ComparisonOperator.EQUALS, "s1")
        assertEquals(expected("n.`__drivine.stamp` = \$v", bound, "s1"), sql)
        assertTrue("n_.stamp" in sql && "__drivine.stamp" !in sql, sql)
        val (unset, _) = compared("__drivine.stamp", ComparisonOperator.IS_NULL, null)
        assertTrue("n_.stamp" in unset && "__drivine.stamp" !in unset, unset)
    }

    // ----- Conditions on the root beyond a property -----

    @Test
    fun `a label on the root is the module's test of the node's labels`() {
        val sql = fragment().predicate(listOf(LabelCondition("n", listOf("Claim"))))
        assertEquals("(JSON_EXISTS(n_.labels, '$[*]?(@ == \"Claim\")'))", sql)
    }

    @Test
    fun `any label binds the labels once and looks among the row's labels`() {
        val select = fragment()
        val sql = assertNotNull(select.predicate(listOf(WhereCondition.AnyLabelCondition("n", listOf("A", "B")))))
        val (name, value) = select.bindings().entries.single()
        assertEquals("[\"A\",\"B\"]", value)
        assertTrue("JSON_TABLE(n_.labels, '$[*]'" in sql && "JSON_TABLE(JSON(\$$name), '$[*]'" in sql, sql)
    }

    @Test
    fun `list membership on the root is the value in the list the property holds`() {
        val select = fragment()
        val sql = select.predicate(listOf(WhereCondition.ListMembershipCondition("n.tags", "x")))
        assertEquals(expected("\$v IN n.tags", select.bindings(), "x"), sql)
        assertEquals(listOf<Any?>("\"x\""), select.bindings().values.toList())
    }

    @Test
    fun `an or condition has a branch for each of its conditions`() {
        val select = fragment()
        val sql = select.predicate(listOf(WhereCondition.OrCondition(listOf(
            PropertyCondition("n.name", ComparisonOperator.EQUALS, "a"),
            PropertyCondition("n.name", ComparisonOperator.EQUALS, "b"),
        ))))
        val (a, b) = select.bindings().keys.map { it.removePrefix("_cy_") }
        val root = mapOf("n" to FragmentVariable.Node("n_", listOf("Claim")))
        assertEquals(fragments.condition("(n.name = \$$a OR n.name = \$$b)", root, mapOf(a to "a", b to "b")).terms.single(), sql)
        assertEquals(listOf<Any?>("\"a\"", "\"b\""), select.bindings().values.toList())
    }

    @Test
    fun `a not condition negates its conditions together`() {
        val select = fragment()
        val sql = assertNotNull(select.predicate(listOf(WhereCondition.NotCondition(listOf(
            PropertyCondition("n.name", ComparisonOperator.EQUALS, "a"),
            PropertyCondition("n.rank", ComparisonOperator.GREATER_THAN, 1),
        )))))
        val (name, rank) = select.bindings().keys.map { it.removePrefix("_cy_") }
        val root = mapOf("n" to FragmentVariable.Node("n_", listOf("Claim")))
        assertEquals(fragments.condition("NOT (n.name = \$$name AND n.rank > \$$rank)", root, mapOf(name to "a", rank to 1)).terms.single(), sql)
        assertTrue(sql.startsWith("(NOT ("), sql)
    }

    @Test
    fun `no conditions make no predicate`() {
        assertEquals(null, fragment().predicate(emptyList()))
    }

    // ----- Conditions on related nodes -----

    /** How many `EXISTS` [sql] holds of its own, a `JSON_EXISTS` aside. */
    private fun exists(sql: String) = Regex("""\bEXISTS \(""").findAll(sql).count()

    /** The test of a related row of the module's [EXISTS][exists] that ties its relationship to the row [from]. */
    private fun tied(sql: String, from: String) = Regex("""\be\d+\.src = ${Regex.escape(from)}\.nid\b""").containsMatchIn(sql)

    @Test
    fun `two conditions on one relationship alias are met together by one related node`() {
        val select = view()
        val sql = assertNotNull(select.predicate(listOf(
            PropertyCondition("people.name", ComparisonOperator.EQUALS, "ada"),
            PropertyCondition("people.id", ComparisonOperator.EQUALS, "h1"),
        )))
        // One related node, of the field's labels, tied to the root, meets both.
        assertEquals(1, exists(sql), sql)
        assertTrue(sql.startsWith("EXISTS (SELECT 1 FROM g_node n1 CROSS JOIN g_edge e2 WHERE JSON_EXISTS(n1.labels, '$[*]?(@ == \"Human\")')"), sql)
        assertTrue(tied(sql, "claim_") && "e2.type = 'MENTIONS'" in sql, sql)
        assertTrue("n1.props, '$.name'" in sql && "n1.props, '$.id'" in sql, sql)
        assertEquals(listOf<Any?>("\"ada\"", "\"h1\""), select.bindings().values.toList())
    }

    @Test
    fun `a test of a related node against a list reads the bound list within the related node's EXISTS`() {
        val select = view()
        val sql = assertNotNull(select.predicate(listOf(
            PropertyCondition("people.id", ComparisonOperator.IN, listOf("h1", "h2")),
            PropertyCondition("people.name", ComparisonOperator.EQUALS, "ada"),
        )))
        val list = select.bindings().entries.single { it.value == "[\"h1\",\"h2\"]" }.key
        assertEquals(1, exists(sql.replace("EXISTS (SELECT 1 FROM JSON_TABLE(JSON(\$$list)", "")), sql)
        assertTrue("JSON_TABLE(JSON(\$$list), '$[*]'" in sql, sql)
        assertEquals(2, select.bindings().size)
    }

    @Test
    fun `an or or a not on a related node is compiled whole within its EXISTS`() {
        fun asked(condition: WhereCondition) = assertNotNull(view().predicate(listOf(RelationshipCondition("people", listOf(condition)))))
        val named = PropertyCondition("people.name", ComparisonOperator.EQUALS, "ada")
        val listed = PropertyCondition("people.id", ComparisonOperator.IN, listOf("h1"))
        for (condition in listOf(
            WhereCondition.OrCondition(listOf(named, listed)),
            WhereCondition.OrCondition(listOf(named, WhereCondition.NotCondition(listOf(listed)))),
            WhereCondition.OrCondition(listOf(named, WhereCondition.NotCondition(listOf(named)))),
        )) {
            val sql = asked(condition)
            assertTrue(sql.startsWith("EXISTS (SELECT 1 FROM g_node n1 CROSS JOIN g_edge e2 WHERE JSON_EXISTS(n1.labels, '$[*]?(@ == \"Human\")')"), sql)
            assertTrue(" OR " in sql && tied(sql, "claim_"), sql)
        }
    }

    @Test
    fun `a condition on a relationship of a nested view is an EXISTS within the EXISTS`() {
        val select = OracleSelect(graph, Dossier::class.java)
        val sql = assertNotNull(select.predicate(listOf(PropertyCondition("claims_people.name", ComparisonOperator.EQUALS, "ada"))))
        assertEquals(2, exists(sql), sql)
        // Each level tests only the nodes its field loads, a claim and then a human, each tied to the level above.
        assertTrue(sql.startsWith("EXISTS (SELECT 1 FROM g_node n1 CROSS JOIN g_edge e2 WHERE JSON_EXISTS(n1.labels, '$[*]?(@ == \"Claim\")')"), sql)
        assertTrue(tied(sql, "memo_") && "e2.type = 'COVERS'" in sql, sql)
        assertTrue("JSON_EXISTS(n3.labels, '$[*]?(@ == \"Human\")')" in sql && tied(sql, "n1") && "e4.type = 'MENTIONS'" in sql, sql)
    }

    @Test
    fun `a condition on a list read over several hops walks to its depth, with no quantified step`() {
        val window = sample.conditiondepth.CdWindowView::class.java
        fun asked(depths: Map<String, Int>) = assertNotNull(
            OracleSelect(graph, window, depthOverrides = depths)
                .predicate(listOf(RelationshipCondition("following", listOf(PropertyCondition("following.text", ComparisonOperator.EQUALS, "c"))))),
        )
        val walked = asked(emptyMap())
        // No quantified step: one with a bound value in its pattern fails on Oracle Free (ORA-00600 [qctcte1]).
        assertFalse("->{" in walked, walked)
        assertEquals(1, exists(walked), walked)
        assertTrue("WITH w" in walked && "depth < 2" in walked && "depth >= 1" in walked && "e2.type = 'NEXT'" in walked, walked)
        assertTrue("depth < 3" in asked(mapOf("following" to 3)))
        // To a depth of one, a single hop.
        val one = asked(mapOf("following" to 1))
        assertEquals(1, exists(one), one)
        assertFalse("WITH " in one, one)
        assertTrue(tied(one, "node_") && "e2.type = 'NEXT'" in one, one)
    }

    @Test
    fun `a negated relationship condition asks that no related node meets it`() {
        val sql = view().predicate(listOf(RelationshipCondition("people", listOf(PropertyCondition("people.name", ComparisonOperator.EQUALS, "ada")), negate = true)))
        assertTrue(assertNotNull(sql).startsWith("(NOT (EXISTS ("), sql)
        assertEquals(1, exists(sql), sql)
    }

    @Test
    fun `a label condition on a relationship alias tests the related node's row`() {
        val sql = assertNotNull(view().predicate(listOf(LabelCondition("people", listOf("Author")))))
        assertTrue(sql.startsWith("EXISTS"), sql)
        assertTrue("JSON_EXISTS(n1.labels, '$[*]?(@ == \"Human\")')" in sql && "JSON_EXISTS(n1.labels, '$[*]?(@ == \"Author\")')" in sql, sql)
    }

    @Test
    fun `a condition on a relationship the view does not have names the ones it has`() {
        val failure = assertThrows<IllegalArgumentException> {
            view().predicate(listOf(RelationshipCondition("nobody", listOf(PropertyCondition("nobody.name", ComparisonOperator.EQUALS, "x")))))
        }
        assertTrue(failure.message!!.contains("Available relationships"), failure.message)
        assertTrue(failure.message!!.contains("people"), failure.message)
    }

    @Test
    fun `list membership, negation and any-label are refused inside a relationship block`() {
        val inner = listOf(
            WhereCondition.ListMembershipCondition("people.tags", "x"),
            WhereCondition.NotCondition(listOf(PropertyCondition("people.name", ComparisonOperator.EQUALS, "x"))),
            WhereCondition.AnyLabelCondition("people", listOf("Human")),
        )
        for (condition in inner) {
            assertThrows<UnsupportedOperationException>("$condition") {
                view().predicate(listOf(RelationshipCondition("people", listOf(condition))))
            }
        }
    }

    @Test
    fun `a condition on the root of a view and one on a relationship are joined`() {
        val select = view()
        val sql = assertNotNull(select.predicate(listOf(
            PropertyCondition("claim.text", ComparisonOperator.EQUALS, "t"),
            PropertyCondition("people.name", ComparisonOperator.EQUALS, "ada"),
        )))
        val text = select.bindings().keys.first().removePrefix("_cy_")
        val root = fragments.condition("claim.text = \$$text", mapOf("claim" to FragmentVariable.Node("claim_", listOf("Claim"))), mapOf(text to "t")).terms.single()
        assertTrue(sql.startsWith("($root AND EXISTS ("), sql)
        assertFalse(sql.contains("n_."), sql)
    }

    @Test
    fun `a property is named to the module quoted, a backtick in it doubled, and an empty name, which the graph engines' Cypher refuses, is refused before anything is compiled`() {
        assertEquals("n.`odd``name`", org.drivine.backend.sql.Fragments.property("odd`name"))
        val refused = assertThrows<IllegalArgumentException> { org.drivine.backend.sql.Fragments.property("") }
        assertTrue("empty" in refused.message!!, refused.message)
    }
}
