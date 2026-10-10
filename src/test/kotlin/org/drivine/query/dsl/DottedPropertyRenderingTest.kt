package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
import org.drivine.model.Stamps
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.sort.CallSubqueryEmitter
import org.drivine.query.sort.TopLevelSortContext
import org.junit.jupiter.api.Test
import sample.proposition.PropositionView
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * A dotted property — the node stamp, a `@PropertyBag` key — is one property, so it is quoted
 * wherever the DSL writes it into a statement, not only in a `where`. Unquoted, `n.__drivine.stamp`
 * is the `stamp` of `n.__drivine`: null for every row.
 */
class DottedPropertyRenderingTest {

    private val stamp = "n.${Stamps.PROPERTY}"

    @Test
    fun `an order by a dotted property quotes it`() {
        val clause = CypherGenerator.buildOrderByClause(
            listOf(OrderSpec(stamp, OrderDirection.DESC), OrderSpec("n.id", OrderDirection.ASC))
        )

        assertEquals("n.`__drivine.stamp` DESC, n.id ASC", clause)
    }

    @Test
    fun `an order by a property bag key quotes it`() {
        val clause = CypherGenerator.buildOrderByClause(listOf(OrderSpec("doc.metadata.source", OrderDirection.ASC)))

        assertEquals("doc.`metadata.source` ASC", clause)
    }

    @Test
    fun `a single-key seek on a dotted property quotes it in the null guard and the comparison`() {
        val plan = KeysetPlanner.plan(
            orders = listOf(OrderSpec(stamp, OrderDirection.ASC)),
            values = listOf(SeekValueSpec(stamp, "s-1")),
        )

        assertEquals("(n.`__drivine.stamp` IS NOT NULL AND n.`__drivine.stamp` > \$_seek_0)", plan.predicate)
        assertEquals(mapOf("_seek_0" to "s-1"), plan.bindings)
    }

    @Test
    fun `a compound seek quotes a dotted property in the bound, the equal prefix and the tail`() {
        val plan = KeysetPlanner.plan(
            orders = listOf(OrderSpec(stamp, OrderDirection.DESC), OrderSpec("n.metadata.rank", OrderDirection.DESC)),
            values = listOf(SeekValueSpec(stamp, "s-1"), SeekValueSpec("n.metadata.rank", 3)),
        )

        assertEquals(
            "(n.`__drivine.stamp` IS NOT NULL AND n.`metadata.rank` IS NOT NULL AND " +
                "n.`__drivine.stamp` <= \$_seek_0 AND " +
                "(n.`__drivine.stamp` < \$_seek_0 OR " +
                "(n.`__drivine.stamp` = \$_seek_0 AND n.`metadata.rank` < \$_seek_1)))",
            plan.predicate,
        )
    }

    @Test
    fun `a quantifier over a projected collection quotes a dotted property of its element`() {
        val conditions = listOf(
            WhereCondition.RelationshipCondition(
                relationshipName = "mentions",
                targetConditions = listOf(
                    WhereCondition.PropertyCondition("mentions.${Stamps.PROPERTY}", ComparisonOperator.EQUALS, "s-1")
                ),
            )
        )

        val where = CypherGenerator.buildWhereClause(
            conditions,
            GraphViewModel.from(PropositionView::class.java),
            Neo4j5Grammar(ApocSortMapsEmitter()),
            projectedCollectionMode = true,
        ).whereClause

        assertEquals("any(_e0 IN mentions WHERE _e0.`__drivine.stamp` = \$param_mentions___drivine_stamp_0)", where)
    }

    @Test
    fun `a collection sorted in a subquery orders by the quoted property`() {
        val emission = CallSubqueryEmitter().emitTopLevel(
            TopLevelSortContext(
                rootAlias = "issue",
                direction = "-[:ASSIGNED_TO]->",
                targetAlias = "assignedTo",
                targetLabelString = "Person",
                projection = "assignedTo {.*}",
                sort = CollectionSortSpec("assignedTo", Stamps.PROPERTY, ascending = true),
            )
        )

        assertContains(emission.prolog!!, "ORDER BY assignedTo.`__drivine.stamp` ASC")
    }

    @Test
    fun `a backtick in a dotted property is doubled, so it cannot close the quotes`() {
        assertEquals("n.`a``b.c` ASC", CypherGenerator.buildOrderByClause(listOf(OrderSpec("n.a`b.c", OrderDirection.ASC))))
    }
}
