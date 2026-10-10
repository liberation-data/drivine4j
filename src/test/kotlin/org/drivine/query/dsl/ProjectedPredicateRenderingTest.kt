package org.drivine.query.dsl

import org.drivine.model.GraphViewModel
import org.drivine.query.GraphViewQueryBuilder
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.sort.CallSubqueryEmitter
import org.drivine.schema.SchemaGrammar
import org.junit.jupiter.api.Test
import sample.mapped.view.LocationHierarchy
import sample.readpath.KeptShelfView
import sample.readpath.ShelfView
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a statement reads after a view's projection, as text: the element of a projected collection
 * by the shape the view gave it, a root property only when the projection carries it, and a name
 * between quotes it cannot end. And where the `WHERE` of a load stands among its prologs.
 */
class ProjectedPredicateRenderingTest {

    private val shelf = GraphViewModel.from(ShelfView::class.java)

    private fun eq(path: String, value: Any?) = WhereCondition.PropertyCondition(path, ComparisonOperator.EQUALS, value)

    private fun projected(vararg conditions: WhereCondition): String =
        CypherGenerator.buildWhereClause(conditions.toList(), shelf, projectedCollectionMode = true).whereClause!!

    @Test
    fun `the element of a collection of nested views is read through the view's root`() {
        assertEquals(
            "any(_e0 IN entries WHERE _e0.entry.title = \$param_entries_title_0)",
            projected(WhereCondition.RelationshipCondition("entries", listOf(eq("entries.title", "alpha")))),
        )
        // A field of the root stored under another name is keyed by its field name.
        assertEquals(
            "NOT any(_e0 IN entries WHERE _e0.entry.order = \$param_entries_entry_order_0)",
            projected(WhereCondition.RelationshipCondition("entries", listOf(eq("entries.entry_order", 2)), negate = true)),
        )
    }

    @Test
    fun `a relationship of a nested view is read as a list of its own, its conditions of one target`() {
        val rendered = projected(
            WhereCondition.RelationshipCondition(
                "entries",
                listOf(eq("entries_markers.display_name", "red"), eq("entries.title", "alpha"), eq("entries_markers.id", "m-red")),
            )
        )

        assertEquals(
            "any(_e0 IN entries WHERE " +
                "any(_e0_0 IN _e0.markers WHERE _e0_0.displayName = \$param_entries_markers_display_name_0 " +
                "AND _e0_0.id = \$param_entries_markers_id_2) " +
                "AND _e0.entry.title = \$param_entries_title_1)",
            rendered,
        )
    }

    @Test
    fun `the parameters of a nested-view predicate are the ones its bindings name`() {
        val conditions = listOf(
            eq("shelf.title", "shelf one"),
            WhereCondition.RelationshipCondition(
                "entries",
                listOf(eq("entries_markers.display_name", "red"), eq("entries.title", "alpha"), eq("entries_markers.id", "m-red")),
            ),
            WhereCondition.RelationshipCondition("keeper", listOf(eq("keeper.display_name", "kim"))),
        )
        val rendered = CypherGenerator.buildWhereClause(conditions, shelf, projectedCollectionMode = true).whereClause!!
        val named = Regex("\\$(param_\\w+)").findAll(rendered).map { it.groupValues[1] }.toSet()

        assertEquals(CypherGenerator.extractBindings(conditions, shelf).keys, named)
    }

    @Test
    fun `a relationship that holds one node is read as a list of that node, or of none`() {
        assertEquals(
            "any(_e0 IN CASE WHEN keeper IS NULL THEN [] ELSE [keeper] END WHERE _e0.displayName = \$param_keeper_display_name_0)",
            projected(WhereCondition.RelationshipCondition("keeper", listOf(eq("keeper.display_name", "kim")))),
        )
    }

    @Test
    fun `a relationship of a target that is no view is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            projected(WhereCondition.RelationshipCondition("keeper", listOf(eq("keeper_friends.name", "x"))))
        }
        assertContains(refused.message.orEmpty(), "keeper_friends.name")
    }

    @Test
    fun `a root property the view does not project is refused after the projection and read on the node before it`() {
        assertEquals(
            "shelf.created_at = \$param_shelf_created_at_0",
            CypherGenerator.buildWhereClause(listOf(eq("shelf.created_at", 1)), shelf).whereClause,
        )
        val filtered = assertFailsWith<IllegalArgumentException> { projected(eq("shelf.created_at", 1)) }
        assertContains(filtered.message.orEmpty(), "shelf.created_at")
        val ordered = assertFailsWith<IllegalArgumentException> {
            CypherGenerator.processOrders(listOf(OrderSpec("shelf.created_at", OrderDirection.ASC)), setOf("entries", "keeper"), shelf)
        }
        assertContains(ordered.message.orEmpty(), "Shelf declares no field stored as 'created_at'")
        // A declared field is ordered by as before.
        assertEquals(
            "shelf.title ASC",
            CypherGenerator.processOrders(listOf(OrderSpec("shelf.title", OrderDirection.ASC)), setOf("entries", "keeper"), shelf).orderByClause,
        )
    }

    @Test
    fun `a not over a relationship target's property is a predicate on the relationship`() {
        val conditions = listOf(
            eq("shelf.id", "s1"),
            WhereCondition.NotCondition(listOf(eq("keeper.display_name", "kim"), eq("shelf.title", "t"))),
        )
        val rendered = CypherGenerator.buildWhereClause(conditions, shelf).whereClause!!

        assertEquals(
            "shelf.id = \$param_shelf_id_0 AND NOT (" +
                "EXISTS { (shelf)-[:KEPT_BY]->(keeper) WHERE keeper.display_name = \$param_keeper_display_name_1 } " +
                "AND shelf.title = \$param_shelf_title_2)",
            rendered,
        )
        val named = Regex("\\$(param_\\w+)").findAll(rendered).map { it.groupValues[1] }.toSet()
        assertEquals(CypherGenerator.extractBindings(conditions, shelf).keys, named)
    }

    @Test
    fun `a name cannot end its quotes with a backtick or with the escape of one`() {
        assertEquals("`a``b`", SchemaGrammar.identifier("a`b"))
        assertEquals("`a``b`", SchemaGrammar.identifier("a\\u0060b"))
        assertEquals("`a``b`", SchemaGrammar.identifier("a\\uu0060b"))
        assertEquals("`x`` IS NULL OR n.``id`", CypherGenerator.quoteProperty("x\\u0060 IS NULL OR n.\\u0060id"))
        // Any other escape is part of the name.
        assertEquals("`a\\u0041b`", SchemaGrammar.identifier("a\\u0041b"))
        assertEquals("plain_name", SchemaGrammar.identifier("plain_name"))
    }

    @Test
    fun `an empty property name is refused`() {
        val refused = assertFailsWith<IllegalArgumentException> { CypherGenerator.renderPropertyPath("n.") }
        assertContains(refused.message.orEmpty(), "empty")
    }

    @Test
    fun `a recursive collection is not sorted in the query`() {
        val sort = listOf(CollectionSortSpec("subLocations", "name", ascending = true))
        listOf(CypherDialect.NEO4J_5.grammar(), CypherDialect.NEO4J_5.grammar(CallSubqueryEmitter()), CypherDialect.FALKORDB.grammar()).forEach { grammar ->
            val refused = assertFailsWith<UnsupportedOperationException> {
                GraphViewQueryBuilder.forView(LocationHierarchy::class, grammar).buildQuery(null, null, sort)
            }
            assertContains(refused.message.orEmpty(), "subLocations.name")
            assertContains(refused.message.orEmpty(), "@SortedBy")
        }
        // Unsorted, it loads as before.
        assertTrue("subLocations_d1" in GraphViewQueryBuilder.forView(LocationHierarchy::class, CypherDialect.NEO4J_5.grammar()).buildQuery(null, null))
    }

    @Test
    fun `a collection inside a nested view projected in a subquery is collected in the order asked of it`() {
        val sorts = listOf(
            CollectionSortSpec("entries_markers", "display_name", ascending = false),
            CollectionSortSpec("entries", "entry_order", ascending = true),
        )
        val query = GraphViewQueryBuilder.forView(ShelfView::class, CypherDialect.FALKORDB.grammar()).buildQuery(null, null, sorts)

        val nested = query.indexOf("WITH entries, markers ORDER BY markers.display_name DESC")
        val collected = query.indexOf("collect(DISTINCT CASE WHEN markers IS NOT NULL")
        assertTrue(nested > 0 && nested < collected, query)
        // The view's own collection is ordered after its nested ones are collected.
        assertTrue(query.indexOf("ORDER BY entries.entry_order ASC") > collected, query)
    }

    @Test
    fun `the where of a load stands before the prologs of its projection`() {
        val sort = listOf(CollectionSortSpec("stock", "display_name", ascending = true))
        listOf(CypherDialect.MEMGRAPH, CypherDialect.FALKORDB).forEach { dialect ->
            val query = GraphViewQueryBuilder.forView(KeptShelfView::class, dialect.grammar(CallSubqueryEmitter()))
                .buildQuery("shelf.id = \$id", null, sort)

            val where = query.indexOf("WHERE shelf.id = \$id\n  AND (shelf)-[:KEPT_BY]->(:Keeper)")
            assertTrue(where > 0, query)
            assertTrue(query.startsWith("MATCH (shelf:Shelf)\nWHERE "), query)
            assertTrue(where < query.indexOf("CALL {"), query)
            assertTrue("stock_sorted AS stock" in query, query)
        }
    }
}
