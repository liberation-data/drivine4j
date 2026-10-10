package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
import org.drivine.model.Stamps
import org.drivine.query.GraphViewQueryBuilder
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.sort.CallSubqueryEmitter
import org.drivine.query.sort.NestedSortContext
import org.drivine.query.sort.TopLevelSortContext
import org.junit.jupiter.api.Test
import sample.projected.LedgerView
import sample.projected.PassageView
import sample.propertybag.BaggedView
import sample.stateless.ClaimView
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A dotted property — the node stamp, a `@PropertyBag` key — is one property, so it is quoted
 * wherever the DSL writes it into a statement, not only in a `where`. Unquoted, `n.__drivine.stamp`
 * is the `stamp` of `n.__drivine`: null for every row. So is any other name that is not a plain
 * identifier, and a key known only at runtime may be one.
 *
 * A property read after a view's projection is read from a map, by the key the projection gave it:
 * the field name, where the property is stored under another.
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
    fun `a quantifier over a projected collection reads the stamp by the field its element holds it in`() {
        val conditions = listOf(
            WhereCondition.RelationshipCondition(
                relationshipName = "markers",
                targetConditions = listOf(
                    WhereCondition.PropertyCondition("markers.${Stamps.PROPERTY}", ComparisonOperator.EQUALS, "s-1"),
                    WhereCondition.PropertyCondition("markers.display_name", ComparisonOperator.EQUALS, "alpha"),
                ),
            )
        )

        val where = projectedWhere(PassageView::class.java, conditions)

        // The element is the projected map, keyed by field name; the parameter keeps the stored path.
        assertEquals(
            "any(_e0 IN markers WHERE _e0.stamp = \$param_markers___drivine_stamp_0 AND " +
                "_e0.displayName = \$param_markers_display_name_1)",
            where,
        )
    }

    @Test
    fun `a quantifier over a projected collection quotes a dotted property its element was projected with`() {
        // A target with a property bag is projected with `.*`, so its map keeps the stored names.
        val conditions = listOf(
            WhereCondition.RelationshipCondition(
                relationshipName = "tags",
                targetConditions = listOf(
                    WhereCondition.PropertyCondition("tags.attr.colour", ComparisonOperator.EQUALS, "red")
                ),
            )
        )

        val where = projectedWhere(BaggedView::class.java, conditions)

        assertEquals("any(_e0 IN tags WHERE _e0.`attr.colour` = \$param_tags_attr_colour_0)", where)
    }

    @Test
    fun `a predicate after the projection reads a root field by its field name`() {
        val conditions = listOf(
            WhereCondition.PropertyCondition("passage.${Stamps.PROPERTY}", ComparisonOperator.EQUALS, "s-1"),
            WhereCondition.OrCondition(
                listOf(
                    WhereCondition.PropertyCondition("passage.sequence_number", ComparisonOperator.GREATER_THAN, 3),
                    WhereCondition.PropertyCondition("passage.sequence_number", ComparisonOperator.IS_NULL, null),
                )
            ),
            WhereCondition.PropertyCondition("passage.text", ComparisonOperator.CONTAINS, "a"),
        )

        assertEquals(
            "passage.stamp = \$param_passage___drivine_stamp_0 AND " +
                "(passage.sequenceNumber > \$param_passage_sequence_number_1 OR passage.sequenceNumber IS NULL) AND " +
                "passage.text CONTAINS \$param_passage_text_2",
            projectedWhere(PassageView::class.java, conditions),
        )
        // Before the projection the same predicates are on the node, by stored name.
        assertEquals(
            "passage.`__drivine.stamp` = \$param_passage___drivine_stamp_0 AND " +
                "(passage.sequence_number > \$param_passage_sequence_number_1 OR passage.sequence_number IS NULL) AND " +
                "passage.text CONTAINS \$param_passage_text_2",
            CypherGenerator.buildWhereClause(conditions, GraphViewModel.from(PassageView::class.java)).whereClause,
        )
    }

    @Test
    fun `a view is ordered by the field names of its projected root, and sought by the stored names`() {
        val orders = listOf(
            OrderSpec("passage.${Stamps.PROPERTY}", OrderDirection.DESC),
            OrderSpec("passage.sequence_number", OrderDirection.ASC),
            OrderSpec("passage.id", OrderDirection.ASC),
        )

        val result = CypherGenerator.processOrders(orders, setOf("markers"), GraphViewModel.from(PassageView::class.java))

        assertEquals("passage.stamp DESC, passage.sequenceNumber ASC, passage.id ASC", result.orderByClause)
        assertEquals(orders, result.rootOrders)
        assertContains(
            KeysetPlanner.plan(listOf(orders[0]), listOf(SeekValueSpec(orders[0].propertyPath, "s-1"))).predicate,
            "passage.`__drivine.stamp` < \$_seek_0",
        )
    }

    @Test
    fun `a view whose root is projected whole is ordered by the stored names`() {
        val result = CypherGenerator.processOrders(
            listOf(OrderSpec("ledger.entry_rank", OrderDirection.ASC), OrderSpec("ledger.meta.origin", OrderDirection.ASC)),
            emptySet(),
            GraphViewModel.from(LedgerView::class.java),
        )

        assertEquals("ledger.entry_rank ASC, ledger.`meta.origin` ASC", result.orderByClause)
    }

    @Test
    fun `the load of a view orders by the key its projection gave the stamp`() {
        val viewModel = GraphViewModel.from(ClaimView::class.java)
        val order = CypherGenerator.processOrders(
            listOf(OrderSpec("claim.${Stamps.PROPERTY}", OrderDirection.ASC)), setOf("people", "companies"), viewModel,
        )

        val query = GraphViewQueryBuilder(viewModel, Neo4j5Grammar(ApocSortMapsEmitter())).buildQuery(null, order.orderByClause)

        assertContains(query, "stamp: claim.`__drivine.stamp`")
        assertTrue(query.endsWith("ORDER BY claim.stamp ASC"), query)
    }

    @Test
    fun `a collection sorted with APOC is sorted by the field name its maps are keyed by`() {
        val grammar = Neo4j5Grammar(ApocSortMapsEmitter())
        val query = GraphViewQueryBuilder(GraphViewModel.from(PassageView::class.java), grammar).buildQuery(
            null, null, listOf(CollectionSortSpec("markers", "display_name", ascending = false)),
        )
        val byStamp = GraphViewQueryBuilder(GraphViewModel.from(PassageView::class.java), grammar).buildQuery(
            null, null, listOf(CollectionSortSpec("markers", Stamps.PROPERTY, ascending = false)),
        )

        assertContains(query, "], 'displayName') AS markers")
        assertContains(byStamp, "], 'stamp') AS markers")
    }

    @Test
    fun `a collection sorted in a subquery orders the nodes by the stored name`() {
        val grammar = Neo4j5Grammar(CallSubqueryEmitter())
        val query = GraphViewQueryBuilder(GraphViewModel.from(PassageView::class.java), grammar).buildQuery(
            null, null, listOf(CollectionSortSpec("markers", "display_name", ascending = true)),
        )
        val byStamp = GraphViewQueryBuilder(GraphViewModel.from(PassageView::class.java), grammar).buildQuery(
            null, null, listOf(CollectionSortSpec("markers", Stamps.PROPERTY, ascending = true)),
        )

        assertContains(query, "WITH markers ORDER BY markers.display_name ASC")
        assertContains(byStamp, "WITH markers ORDER BY markers.`__drivine.stamp` ASC")
    }

    @Test
    fun `a key sorted with APOC is an escaped string literal`() {
        val sorted = ApocSortMapsEmitter().emitNested(
            NestedSortContext("[x]", CollectionSortSpec("tags", "name", ascending = false), projectedKey = "it's a \\ key")
        )

        assertEquals("apoc.coll.sortMaps([x], 'it\\'s a \\\\ key')", sorted)
    }

    @Test
    fun `a property that is not a plain identifier is quoted, dotted or not`() {
        fun rendered(key: String) = CypherGenerator.buildWhereClause(
            listOf(WhereCondition.PropertyCondition("n.$key", ComparisonOperator.EQUALS, "x"))
        ).whereClause

        assertEquals("n.`source-id` = \$param_n_source_id_0", rendered("source-id"))
        assertEquals("n.`source id` = \$param_n_source_id_0", rendered("source id"))
        assertEquals("n.`tick``mark` = \$param_n_tick_mark_0", rendered("tick`mark"))
        assertEquals("n.`metadata.source-id` = \$param_n_metadata_source_id_0", rendered("metadata.source-id"))
        assertEquals("n.`metadata.source id` = \$param_n_metadata_source_id_0", rendered("metadata.source id"))
        assertEquals("n.`metadata.tick``mark` = \$param_n_metadata_tick_mark_0", rendered("metadata.tick`mark"))
        assertEquals("n.`id IS NOT NULL //` = \$param_n_id_IS_NOT_NULL____0", rendered("id IS NOT NULL //"))
        assertEquals("n.`1st` = \$param_n_1st_0", rendered("1st"))
        // A plain identifier is written as it always was.
        assertEquals("n.source_id = \$param_n_source_id_0", rendered("source_id"))
    }

    @Test
    fun `keys that differ only in what a parameter name cannot hold bind to parameters of their own`() {
        val conditions = listOf(
            WhereCondition.PropertyCondition("n.source-id", ComparisonOperator.EQUALS, "hyphen"),
            WhereCondition.PropertyCondition("n.source id", ComparisonOperator.EQUALS, "space"),
            WhereCondition.PropertyCondition("n.source_id", ComparisonOperator.EQUALS, "plain"),
        )

        val where = CypherGenerator.buildWhereClause(conditions).whereClause
        val bindings = CypherGenerator.extractBindings(conditions)

        assertEquals(
            "n.`source-id` = \$param_n_source_id_0 AND n.`source id` = \$param_n_source_id_1 AND " +
                "n.source_id = \$param_n_source_id_2",
            where,
        )
        assertEquals(
            mapOf("param_n_source_id_0" to "hyphen", "param_n_source_id_1" to "space", "param_n_source_id_2" to "plain"),
            bindings,
        )
    }

    private fun projectedWhere(view: Class<*>, conditions: List<WhereCondition>): String? =
        CypherGenerator.buildWhereClause(
            conditions,
            GraphViewModel.from(view),
            Neo4j5Grammar(ApocSortMapsEmitter()),
            projectedCollectionMode = true,
        ).whereClause

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
