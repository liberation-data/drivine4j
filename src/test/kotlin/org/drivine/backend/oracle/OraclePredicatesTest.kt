package org.drivine.backend.oracle

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

/** The predicates the query DSL's conditions become on Oracle, written without a database. */
class OraclePredicatesTest {

    private val graph = OracleGraph("g")

    private fun fragment() = OracleSelect(graph, Claim::class.java)

    private fun view() = OracleSelect(graph, ClaimView::class.java)

    /** The predicate one condition on the root fragment `n` makes, with what it binds. */
    private fun compared(key: String, operator: ComparisonOperator, value: Any?): Pair<String, Map<String, Any?>> {
        val select = fragment()
        val sql = assertNotNull(select.predicate(listOf(PropertyCondition("n.$key", operator, value))))
        return sql to select.bindings()
    }

    private val name = "JSON_VALUE(n_.props, '$.name')"
    private val rank = "JSON_VALUE(n_.props, '$.rank' RETURNING NUMBER)"

    // ----- One case per operator -----

    @Test
    fun `equals binds the value and reads the property as text`() {
        val (sql, bound) = compared("name", ComparisonOperator.EQUALS, "ada")
        assertEquals("$name = \$_p0", sql)
        assertEquals(mapOf<String, Any?>("_p0" to "ada"), bound)
    }

    @Test
    fun `a number is read as a number when compared`() {
        val (sql, bound) = compared("rank", ComparisonOperator.EQUALS, 3)
        assertEquals("$rank = \$_p0", sql)
        assertEquals(mapOf<String, Any?>("_p0" to 3), bound)
    }

    @Test
    fun `not equals writes the inequality`() {
        val (sql, bound) = compared("name", ComparisonOperator.NOT_EQUALS, "ada")
        assertEquals("$name <> \$_p0", sql)
        assertEquals("ada", bound["_p0"])
    }

    @Test
    fun `the four orderings write their operator`() {
        assertEquals("$rank > \$_p0", compared("rank", ComparisonOperator.GREATER_THAN, 1).first)
        assertEquals("$rank >= \$_p0", compared("rank", ComparisonOperator.GREATER_THAN_OR_EQUAL, 1).first)
        assertEquals("$rank < \$_p0", compared("rank", ComparisonOperator.LESS_THAN, 1).first)
        assertEquals("$rank <= \$_p0", compared("rank", ComparisonOperator.LESS_THAN_OR_EQUAL, 1).first)
    }

    @Test
    fun `in binds the list once and reads it through JSON_TABLE`() {
        val (sql, bound) = compared("name", ComparisonOperator.IN, listOf("ada", "grace"))
        assertEquals("$name IN (SELECT v FROM JSON_TABLE(\$_p0, '$[*]' COLUMNS (v VARCHAR2(4000) PATH '$')))", sql)
        assertEquals(mapOf<String, Any?>("_p0" to listOf("ada", "grace")), bound)
    }

    @Test
    fun `in over numbers reads the list's column as a number`() {
        val (sql, bound) = compared("rank", ComparisonOperator.IN, listOf(1, 2))
        assertEquals("$rank IN (SELECT v FROM JSON_TABLE(\$_p0, '$[*]' COLUMNS (v NUMBER PATH '$')))", sql)
        assertEquals(1, bound.size)
    }

    @Test
    fun `not in negates the same membership test`() {
        val (sql, bound) = compared("name", ComparisonOperator.NOT_IN, listOf("ada"))
        assertEquals("NOT ($name IN (SELECT v FROM JSON_TABLE(\$_p0, '$[*]' COLUMNS (v VARCHAR2(4000) PATH '$'))))", sql)
        assertEquals(mapOf<String, Any?>("_p0" to listOf("ada")), bound)
    }

    @Test
    fun `a value that is not a list is still bound as one for in`() {
        val (_, bound) = compared("name", ComparisonOperator.IN, "ada")
        assertEquals(listOf("ada"), bound["_p0"])
    }

    @Test
    fun `has element passes the value into the path`() {
        val (sql, bound) = compared("tags", ComparisonOperator.HAS_ELEMENT, "x")
        assertEquals("JSON_EXISTS(n_.props, '$.tags[*]?(@ == \$v)' PASSING \$_p0 AS \"v\")", sql)
        assertEquals(mapOf<String, Any?>("_p0" to "x"), bound)
    }

    @Test
    fun `has element with a boolean writes it into the path and binds nothing`() {
        val (sql, bound) = compared("flags", ComparisonOperator.HAS_ELEMENT, true)
        assertEquals("JSON_EXISTS(n_.props, '$.flags[*]?(@ == true)')", sql)
        assertTrue(bound.isEmpty())
    }

    @Test
    fun `contains looks for the text with INSTR`() {
        val (sql, bound) = compared("name", ComparisonOperator.CONTAINS, "da")
        assertEquals("INSTR($name, \$_p0) > 0", sql)
        assertEquals("da", bound["_p0"])
    }

    @Test
    fun `starts with compares the head of the text`() {
        val (sql, bound) = compared("name", ComparisonOperator.STARTS_WITH, "ad")
        assertEquals("SUBSTR($name, 1, LENGTH(\$_p0)) = \$_p0", sql)
        assertEquals(mapOf<String, Any?>("_p0" to "ad"), bound)
    }

    @Test
    fun `ends with compares the tail of the text`() {
        val (sql, bound) = compared("name", ComparisonOperator.ENDS_WITH, "da")
        assertEquals("SUBSTR($name, -LENGTH(\$_p0)) = \$_p0", sql)
        assertEquals(mapOf<String, Any?>("_p0" to "da"), bound)
    }

    @Test
    fun `contains ignoring case lowers the text and binds the value lower-cased`() {
        val (sql, bound) = compared("name", ComparisonOperator.CONTAINS_IGNORE_CASE, "AdA")
        assertEquals("INSTR(LOWER($name), \$_p0) > 0", sql)
        assertEquals("ada", bound["_p0"])
    }

    @Test
    fun `equals ignoring case lowers the text and binds the value lower-cased`() {
        val (sql, bound) = compared("name", ComparisonOperator.EQUALS_IGNORE_CASE, "AdA")
        assertEquals("LOWER($name) = \$_p0", sql)
        assertEquals("ada", bound["_p0"])
    }

    @Test
    fun `matches anchors the expression to the whole value`() {
        val (sql, bound) = compared("name", ComparisonOperator.MATCHES, "a.*")
        assertEquals("REGEXP_LIKE($name, \$_p0)", sql)
        assertEquals("^(a.*)$", bound["_p0"])
    }

    @Test
    fun `matches ignores case only for a leading case flag, which is taken off the expression`() {
        val (sql, bound) = compared("name", ComparisonOperator.MATCHES, "(?i)a.*")
        assertEquals("REGEXP_LIKE($name, \$_p0, 'i')", sql)
        assertEquals("^(a.*)$", bound["_p0"])
    }

    @Test
    fun `is null asks whether the property is there and binds nothing`() {
        val (sql, bound) = compared("name", ComparisonOperator.IS_NULL, null)
        assertEquals("NOT JSON_EXISTS(n_.props, '$.name')", sql)
        assertTrue(bound.isEmpty())
    }

    @Test
    fun `is not null asks whether the property is there`() {
        val (sql, bound) = compared("name", ComparisonOperator.IS_NOT_NULL, null)
        assertEquals("JSON_EXISTS(n_.props, '$.name')", sql)
        assertTrue(bound.isEmpty())
    }

    @Test
    fun `a boolean is compared with the text JSON writes it as`() {
        val (sql, bound) = compared("done", ComparisonOperator.EQUALS, true)
        assertEquals("JSON_VALUE(n_.props, '$.done') = \$_p0", sql)
        assertEquals(mapOf<String, Any?>("_p0" to "true"), bound)
    }

    @Test
    fun `a key with a dot in it is quoted in the path, so it is one key`() {
        val (sql, _) = compared("metadata.source", ComparisonOperator.EQUALS, "web")
        assertEquals("JSON_VALUE(n_.props, '$.\"metadata.source\"') = \$_p0", sql)
    }

    @Test
    fun `the stamp is a column, not a property`() {
        val (sql, _) = compared("__drivine.stamp", ComparisonOperator.EQUALS, "s1")
        assertEquals("n_.stamp = \$_p0", sql)
        assertEquals("NOT n_.stamp IS NOT NULL", compared("__drivine.stamp", ComparisonOperator.IS_NULL, null).first)
    }

    // ----- The empty string, which Oracle keeps as null -----

    @Test
    fun `equals the empty string looks for a property stored empty through a JSON path`() {
        val (sql, bound) = compared("name", ComparisonOperator.EQUALS, "")
        assertEquals("JSON_EXISTS(n_.props, '$.name?(@ == \"\")')", sql)
        assertTrue(bound.isEmpty())
        assertEquals("JSON_EXISTS(n_.props, '$.name?(@ == \"\")')", compared("name", ComparisonOperator.EQUALS_IGNORE_CASE, "").first)
    }

    @Test
    fun `contains, starts with and ends with the empty string hold for any string, the empty one among them`() {
        for (operator in listOf(ComparisonOperator.CONTAINS, ComparisonOperator.STARTS_WITH, ComparisonOperator.ENDS_WITH, ComparisonOperator.CONTAINS_IGNORE_CASE)) {
            val (sql, bound) = compared("name", operator, "")
            assertEquals("($name IS NOT NULL OR JSON_EXISTS(n_.props, '$.name?(@ == \"\")'))", sql, "$operator")
            assertTrue(bound.isEmpty(), "$operator")
        }
    }

    @Test
    fun `not equals the empty string holds for any string that is there and not empty`() {
        val (sql, bound) = compared("name", ComparisonOperator.NOT_EQUALS, "")
        assertEquals("($name IS NOT NULL AND NOT JSON_EXISTS(n_.props, '$.name?(@ == \"\")'))", sql)
        assertTrue(bound.isEmpty())
    }

    // ----- Conditions on the root beyond a property -----

    @Test
    fun `a label on the root is tested through the index when the roots are scanned, and on the row once they are found by id`() {
        val scanned = fragment()
        assertEquals("JSON_EXISTS(n_.labels, '$[*]?(@ == \"Claim\")')", scanned.predicate(listOf(LabelCondition("n", listOf("Claim")))))

        val narrowed = fragment()
        narrowed.hasId("id")
        assertEquals("INSTR(JSON_SERIALIZE(n_.labels), '\"Claim\"') > 0", narrowed.predicate(listOf(LabelCondition("n", listOf("Claim")))))
    }

    @Test
    fun `any label binds the labels once and looks among the row's labels`() {
        val select = fragment()
        val sql = select.predicate(listOf(WhereCondition.AnyLabelCondition("n", listOf("A", "B"))))
        assertEquals("EXISTS (SELECT 1 FROM JSON_TABLE(n_.labels, '$[*]' COLUMNS (l VARCHAR2(4000) PATH '$')) WHERE l IN (SELECT v FROM JSON_TABLE(\$_p0, '$[*]' COLUMNS (v VARCHAR2(4000) PATH '$'))))", sql)
        assertEquals(listOf("A", "B"), select.bindings()["_p0"])
    }

    @Test
    fun `list membership on the root passes the value into the path`() {
        val select = fragment()
        assertEquals(
            "JSON_EXISTS(n_.props, '$.tags[*]?(@ == \$v)' PASSING \$_p0 AS \"v\")",
            select.predicate(listOf(WhereCondition.ListMembershipCondition("n.tags", "x"))),
        )
    }

    @Test
    fun `an or condition has a branch for each of its conditions`() {
        val select = fragment()
        val sql = select.predicate(listOf(WhereCondition.OrCondition(listOf(
            PropertyCondition("n.name", ComparisonOperator.EQUALS, "a"),
            PropertyCondition("n.name", ComparisonOperator.EQUALS, "b"),
        ))))
        assertEquals("($name = \$_p0 OR $name = \$_p1)", sql)
        assertEquals(mapOf<String, Any?>("_p0" to "a", "_p1" to "b"), select.bindings())
    }

    @Test
    fun `a not condition negates its conditions together`() {
        val sql = fragment().predicate(listOf(WhereCondition.NotCondition(listOf(
            PropertyCondition("n.name", ComparisonOperator.EQUALS, "a"),
            PropertyCondition("n.rank", ComparisonOperator.GREATER_THAN, 1),
        ))))
        assertEquals("NOT ($name = \$_p0 AND $rank > \$_p1)", sql)
    }

    @Test
    fun `no conditions make no predicate`() {
        assertEquals(null, fragment().predicate(emptyList()))
    }

    // ----- Conditions on related nodes -----

    @Test
    fun `two conditions on one relationship alias are met together by one related node`() {
        val select = view()
        val sql = assertNotNull(select.predicate(listOf(
            PropertyCondition("people.name", ComparisonOperator.EQUALS, "ada"),
            PropertyCondition("people.id", ComparisonOperator.EQUALS, "h1"),
        )))
        // The related node is the field's row, and carries what the conditions read of it.
        assertEquals(
            """
            EXISTS (
                SELECT 1
                FROM GRAPH_TABLE (g
                    MATCH (claim_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (people_ IS node)
                    COLUMNS (claim_.nid AS src, people_.props)) people_
                WHERE people_.src = claim_.nid
                  AND JSON_VALUE(people_.props, '$.name') = ${'$'}_p0
                  AND JSON_VALUE(people_.props, '$.id') = ${'$'}_p1)
            """.trimIndent(),
            sql,
        )
        assertEquals(mapOf<String, Any?>("_p0" to "ada", "_p1" to "h1"), select.bindings())
    }

    @Test
    fun `a condition on a relationship of a nested view is an EXISTS within the EXISTS`() {
        val select = OracleSelect(graph, Dossier::class.java)
        val sql = assertNotNull(select.predicate(listOf(PropertyCondition("claims_people.name", ComparisonOperator.EQUALS, "ada"))))
        assertEquals(2, Regex("EXISTS").findAll(sql).count(), sql)
        assertTrue(sql.contains("MATCH (memo_ IS node) -[e IS edge WHERE e.type = 'COVERS']-> (claims_ IS node)"), sql)
        assertTrue(sql.contains("MATCH (claims_ IS node) -[e IS edge WHERE e.type = 'MENTIONS']-> (people_ IS node)"), sql)
        assertTrue(sql.contains("WHERE people_.src = claims_.nid\n          AND JSON_VALUE(people_.props, '$.name') = \$_p0"), sql)
    }

    @Test
    fun `a negated relationship condition asks that no related node meets it`() {
        val sql = view().predicate(listOf(RelationshipCondition("people", listOf(PropertyCondition("people.name", ComparisonOperator.EQUALS, "ada")), negate = true)))
        assertTrue(assertNotNull(sql).startsWith("NOT EXISTS ("), sql)
    }

    @Test
    fun `a label condition on a relationship alias tests the related node's row`() {
        val sql = assertNotNull(view().predicate(listOf(LabelCondition("people", listOf("Human")))))
        assertTrue(sql.startsWith("EXISTS"), sql)
        assertTrue(sql.contains("AND INSTR(JSON_SERIALIZE(people_.labels), '\"Human\"') > 0"), sql)
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
        assertTrue(sql.startsWith("JSON_VALUE(claim_.props, '$.text') = \$_p0 AND EXISTS"), sql)
        assertFalse(sql.contains("n_."), sql)
    }
}
