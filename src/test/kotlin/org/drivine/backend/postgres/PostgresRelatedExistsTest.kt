package org.drivine.backend.postgres

import org.drivine.annotation.Direction
import org.drivine.backend.sql.StatementScope
import org.drivine.backend.sql.Step
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.any
import org.drivine.query.dsl.none
import org.drivine.query.dsl.query
import org.junit.jupiter.api.Test
import sample.conditiondepth.CdWindowView
import sample.conditiondepth.CdWindowViewQueryDsl
import sample.relatedrows.RrShelfView
import sample.relatedrows.RrShelfViewQueryDsl
import sample.relatedrows.RrShelvedBook
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A condition on a related node is an `EXISTS` that joins the relationship and the node in itself, with the test that
 * ties it to the root in its own `WHERE`, so Postgres can make it a semi-join (liberation-data/drivine4j#460). Written
 * without a database; `RelatedRowsCrossEngineTest` checks the rows.
 */
class PostgresRelatedExistsTest {

    private val select = PostgresSelect(PostgresGraph("g"), RrShelfView::class.java)

    private fun predicate(spec: GraphQuerySpec<RrShelfViewQueryDsl>.() -> Unit): String =
        assertNotNull(select.predicate(GraphQuerySpec(RrShelfViewQueryDsl.INSTANCE).apply(spec).conditions))

    /** How many `EXISTS` [sql] holds: one for each level of related nodes. */
    private fun exists(sql: String) = Regex("""\bEXISTS \(""").findAll(sql).count()

    /**
     * The test that ties a relationship row of [sql] to the node [row], in the `WHERE` of its `EXISTS`, which makes it a
     * semi-join: by the edge's source, or by its near end where it is read either way.
     */
    private fun tied(sql: String, row: String) = Regex("""\be\d+\.(src|a) = $row\.nid\b""").findAll(sql).count()

    @Test
    fun `a condition on a related node joins it in the EXISTS`() {
        val sql = predicate { where { query.books.any { title eq "alpha" } } }
        assertTrue(sql.startsWith("EXISTS (SELECT 1 FROM g_node "), sql)
        assertEquals(1, exists(sql), sql)
        assertEquals(1, tied(sql, "shelf_"), sql)
        assertTrue("type = 'HOLDS'" in sql && "labels @> '[\"RrBook\"]'" in sql, sql)
        // The title compared as the module compares a value: the property's JSON with the parameter's.
        assertTrue(Regex("""\(n\d+\.props -> 'title'\) = CAST\(\${'$'}_cy_param_books_title_0 AS jsonb\)""").containsMatchIn(sql), sql)
        assertEquals(mapOf<String, Any?>("_cy_param_books_title_0" to "\"alpha\""), select.bindings())
    }

    @Test
    fun `none is NOT EXISTS of the same`() {
        val sql = predicate { where { query.books.none { } } }
        assertTrue(sql.startsWith("(NOT (EXISTS (SELECT 1 FROM g_node "), sql)
        assertEquals(1, exists(sql), sql)
        assertEquals(1, tied(sql, "shelf_"), sql)
        assertTrue("type = 'HOLDS'" in sql && "labels @> '[\"RrBook\"]'" in sql, sql)
    }

    @Test
    fun `a nested condition joins each level in its own EXISTS`() {
        val sql = predicate { where { query.books.any { tags.name eq "red" } } }
        assertEquals(2, exists(sql), sql)
        // Each level tied to the one above it: the books to the shelf, the tags to the book.
        assertEquals(1, tied(sql, "shelf_"), sql)
        val book = assertNotNull(Regex("""JOIN g_edge e\d+ WHERE (n\d+)\.labels @> '\["RrBook"\]'""").find(sql), sql).groupValues[1]
        assertEquals(1, tied(sql, book), sql)
        assertTrue(sql.indexOf("'TAGGED'") > sql.indexOf("'HOLDS'") && "labels @> '[\"RrTag\"]'" in sql, sql)
    }

    @Test
    fun `a path joins each hop in the EXISTS`() {
        val sql = predicate { where { query.marks.any { name eq "blue" } } }
        // One EXISTS that joins both hops, the first tied to the shelf.
        assertEquals(1, exists(sql), sql)
        assertEquals(1, tied(sql, "shelf_"), sql)
        assertTrue(sql.indexOf("'TAGGED'") > sql.indexOf("'HOLDS'"), sql)
        assertTrue("labels @> '[\"RrBook\"]'" in sql && "labels @> '[\"RrTag\"]'" in sql, sql)
    }

    @Test
    fun `a relationship either way reads each edge from both ends, tied to the root in the EXISTS`() {
        val sql = predicate { where { query.neighbours.any { } } }
        assertEquals(1, exists(sql), sql)
        // Each edge read from either end, a loop once, and its near end tied to the shelf.
        assertTrue("UNION ALL" in sql && "src <> " in sql, sql)
        assertEquals(1, tied(sql, "shelf_"), sql)
        assertTrue("type = 'NEXT_TO'" in sql && "labels @> '[\"RrShelf\"]'" in sql, sql)
    }

    @Test
    fun `a relationship a view requires is joined in the EXISTS a load makes`() {
        assertEquals(
            """
            SELECT COUNT(*)
            FROM g_node book_
            WHERE book_.labels @> '["RrBook"]'
              AND EXISTS (
                SELECT 1
                FROM g_edge e
                    JOIN g_node shelf_ ON shelf_.nid = e.src
                WHERE e.dst = book_.nid
                  AND e.type = 'HOLDS'
                  AND shelf_.labels @> '["RrShelf"]')
            """.trimIndent(),
            PostgresSelect(PostgresGraph("g"), RrShelvedBook::class.java).count(emptyList()).statement,
        )
    }

    @Test
    fun `a step taken to a depth is still the recursive walk, read through its derived table`() {
        // A DSL condition spells such a step as paths of single hops before it asks (Step.anyPath), so only a direct caller gets here.
        val walked = Step("NEXT", Direction.OUTGOING, listOf("RrShelf"), maxDepth = 2)
        fun exists(vararg steps: Step) = PostgresRowSources(PostgresGraph("g"), StatementScope(55)).exists("p", steps.toList(), "r")
        assertEquals(
            """
            EXISTS (
                SELECT 1
                FROM (
                    WITH RECURSIVE walk_ (src, nid, depth, eids) AS (
                        SELECT e.src, e.dst, 1, ARRAY[e.eid]
                        FROM g_edge e
                        WHERE e.type = 'NEXT' AND e.src = p.nid
                        UNION ALL
                        SELECT w.src, e.dst, w.depth + 1, w.eids || e.eid
                        FROM walk_ w JOIN g_edge e ON e.src = w.nid
                        WHERE e.type = 'NEXT' AND w.depth < 2 AND e.eid <> ALL (w.eids)
                    )
                    SELECT w.src, r.nid, r.labels, r.props, r.stamp
                    FROM walk_ w
                        JOIN g_node r ON r.nid = w.nid
                    WHERE w.depth >= 1
                      AND r.labels @> '["RrShelf"]'
                ) r)
            """.trimIndent(),
            exists(walked),
        )
        // A hop beside a step to a depth is a chain of derived tables too, not joined in the EXISTS.
        val mixed = exists(Step("HOLDS", Direction.OUTGOING, emptyList()), walked)
        assertTrue(mixed.startsWith("EXISTS (\n    SELECT 1\n    FROM (") && "WITH RECURSIVE" in mixed, mixed)
    }

    @Test
    fun `a condition on a field read to a depth walks the relationship to that depth in its EXISTS`() {
        val select = PostgresSelect(PostgresGraph("g"), CdWindowView::class.java)
        val sql = assertNotNull(select.predicate(GraphQuerySpec(CdWindowViewQueryDsl.INSTANCE).apply { where { query.following.any { } } }.conditions))
        assertEquals(1, exists(sql), sql)
        // The walk begins at the root and takes a relationship once in a path, from one hop to the field's depth.
        assertTrue("WITH RECURSIVE" in sql && "SELECT node_.nid, 0" in sql, sql)
        assertTrue("depth < 2" in sql && "depth >= 1" in sql && "<> ALL (" in sql, sql)
        assertTrue("type = 'NEXT'" in sql && "labels @> '[\"CdNode\"]'" in sql, sql)
    }
}
