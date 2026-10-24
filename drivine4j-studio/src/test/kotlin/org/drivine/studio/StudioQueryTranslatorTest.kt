package org.drivine.studio

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.WhereCondition
import org.drivine.sample.fragment.Person
import org.drivine.sample.view.RaisedAndAssignedIssue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudioQueryTranslatorTest {

    private val translator = StudioQueryTranslator(
        StudioCatalog.scan(listOf("org.drivine.sample")),
        jacksonObjectMapper().findAndRegisterModules(),
    )
    private val issue = RaisedAndAssignedIssue::class.java.name

    private fun property(name: String, op: ComparisonOperator, value: Any? = null) =
        StudioCondition.Property(name, op, value)

    @Test
    fun `a root property is read under the root's alias`() {
        val translated = translator.translate(StudioQuery(issue, where = listOf(property("state", ComparisonOperator.EQUALS, "open"))))

        assertEquals(RaisedAndAssignedIssue::class.java, translated.type)
        assertEquals(listOf<WhereCondition>(WhereCondition.PropertyCondition("issue.state", ComparisonOperator.EQUALS, "open")), translated.where)
    }

    @Test
    fun `a value becomes its property's type`() {
        val uuid = UUID.randomUUID()
        val translated = translator.translate(
            StudioQuery(
                issue,
                where = listOf(
                    property("id", ComparisonOperator.GREATER_THAN, 100),
                    property("uuid", ComparisonOperator.IN, listOf(uuid.toString())),
                    property("locked", ComparisonOperator.EQUALS, "true"),
                ),
            )
        )

        assertEquals(
            listOf<Any?>(100L, listOf(uuid), true),
            translated.where.map { (it as WhereCondition.PropertyCondition).value },
        )
    }

    @Test
    fun `a condition on a relationship is on the related node`() {
        val translated = translator.translate(
            StudioQuery(
                issue,
                where = listOf(
                    StudioCondition.Related("assignedTo", none = true, conditions = listOf(property("name", ComparisonOperator.CONTAINS, "Alice")))
                ),
            )
        )

        assertEquals(
            listOf<WhereCondition>(
                WhereCondition.RelationshipCondition(
                    "assignedTo",
                    listOf(WhereCondition.PropertyCondition("assignedTo.name", ComparisonOperator.CONTAINS, "Alice")),
                    negate = true,
                )
            ),
            translated.where,
        )
    }

    @Test
    fun `anyOf and not keep their shape`() {
        val translated = translator.translate(
            StudioQuery(
                issue,
                where = listOf(
                    StudioCondition.AnyOf(listOf(property("state", ComparisonOperator.EQUALS, "open"), property("locked", ComparisonOperator.EQUALS, true))),
                    StudioCondition.Not(listOf(property("title", ComparisonOperator.IS_NULL))),
                ),
            )
        )

        assertTrue(translated.where[0] is WhereCondition.OrCondition)
        assertEquals(
            WhereCondition.NotCondition(listOf(WhereCondition.PropertyCondition("issue.title", ComparisonOperator.IS_NULL, null))),
            translated.where[1],
        )
    }

    @Test
    fun `a fragment's properties are read under the fragment alias, and orders with them`() {
        val translated = translator.translate(
            StudioQuery(
                Person::class.java.name,
                where = listOf(property("name", ComparisonOperator.STARTS_WITH, "A")),
                orderBy = listOf(StudioOrder("name", OrderDirection.DESC)),
                limit = 10,
            )
        )

        assertEquals("n.name", (translated.where.single() as WhereCondition.PropertyCondition).propertyPath)
        assertEquals(listOf(OrderSpec("n.name", OrderDirection.DESC)), translated.orderBy)
        assertEquals(10, translated.limit)
    }

    @Test
    fun `a query with no limit is given one`() {
        assertEquals(50, translator.translate(StudioQuery(issue)).limit)
    }

    @Test
    fun `what the catalogue does not hold is refused, and the refusal says what it does hold`() {
        fun refused(query: StudioQuery) = assertFailsWith<StudioQueryException> { translator.translate(query) }.message!!

        assertTrue("state" in refused(StudioQuery(issue, where = listOf(property("status", ComparisonOperator.EQUALS, "open")))))
        assertTrue("assignedTo" in refused(StudioQuery(issue, where = listOf(StudioCondition.Related("assignee", conditions = listOf(property("name", ComparisonOperator.IS_NULL)))))))
        assertTrue("Long" in refused(StudioQuery(issue, where = listOf(property("id", ComparisonOperator.EQUALS, "abc")))))
        assertTrue("text" in refused(StudioQuery(issue, where = listOf(property("id", ComparisonOperator.CONTAINS, "1")))))
        assertTrue("catalogue" in refused(StudioQuery("java.lang.Runtime")))
        assertTrue("limit" in refused(StudioQuery(issue, limit = 100_000)))
        assertTrue("fragment" in refused(StudioQuery(Person::class.java.name, where = listOf(StudioCondition.Related("worksFor", conditions = listOf(property("name", ComparisonOperator.IS_NULL)))))))
    }
}
