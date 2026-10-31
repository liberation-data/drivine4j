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

    private fun predicate(spec: GraphQuerySpec<RrShelfViewQueryDsl>.() -> Unit): String {
        val select = PostgresSelect(PostgresGraph("g"), RrShelfView::class.java)
        return assertNotNull(select.predicate(GraphQuerySpec(RrShelfViewQueryDsl.INSTANCE).apply(spec).conditions))
    }

    @Test
    fun `a condition on a related node joins it in the EXISTS`() {
        assertEquals(
            """
            EXISTS (
                SELECT 1
                FROM g_edge e
                    JOIN g_node books_ ON books_.nid = e.dst
                WHERE e.src = shelf_.nid
                  AND e.type = 'HOLDS'
                  AND books_.labels @> '["RrBook"]'
                  AND (books_.props ->> 'title') = ${'$'}_p0)
            """.trimIndent(),
            predicate { where { query.books.any { title eq "alpha" } } },
        )
    }

    @Test
    fun `none is NOT EXISTS of the same`() {
        assertEquals(
            """
            NOT EXISTS (
                SELECT 1
                FROM g_edge e
                    JOIN g_node books_ ON books_.nid = e.dst
                WHERE e.src = shelf_.nid
                  AND e.type = 'HOLDS'
                  AND books_.labels @> '["RrBook"]')
            """.trimIndent(),
            predicate { where { query.books.none { } } },
        )
    }

    @Test
    fun `a nested condition joins each level in its own EXISTS`() {
        assertEquals(
            """
            EXISTS (
                SELECT 1
                FROM g_edge e
                    JOIN g_node books_ ON books_.nid = e.dst
                WHERE e.src = shelf_.nid
                  AND e.type = 'HOLDS'
                  AND books_.labels @> '["RrBook"]'
                  AND EXISTS (
                    SELECT 1
                    FROM g_edge e
                        JOIN g_node tags_ ON tags_.nid = e.dst
                    WHERE e.src = books_.nid
                      AND e.type = 'TAGGED'
                      AND tags_.labels @> '["RrTag"]'
                      AND (tags_.props ->> 'name') = ${'$'}_p0))
            """.trimIndent(),
            predicate { where { query.books.any { tags.name eq "red" } } },
        )
    }

    @Test
    fun `a path joins each hop in the EXISTS`() {
        assertEquals(
            """
            EXISTS (
                SELECT 1
                FROM g_edge e1
                    JOIN g_node m1 ON m1.nid = e1.dst
                    JOIN g_edge e2 ON e2.src = m1.nid
                    JOIN g_node marks_ ON marks_.nid = e2.dst
                WHERE e1.src = shelf_.nid
                  AND e1.type = 'HOLDS'
                  AND m1.labels @> '["RrBook"]'
                  AND e2.type = 'TAGGED'
                  AND marks_.labels @> '["RrTag"]'
                  AND (marks_.props ->> 'name') = ${'$'}_p0)
            """.trimIndent(),
            predicate { where { query.marks.any { name eq "blue" } } },
        )
    }

    @Test
    fun `a relationship either way reads each edge from both ends, tied to the root in the EXISTS`() {
        assertEquals(
            """
            EXISTS (
                SELECT 1
                FROM (SELECT eid, src AS near, dst AS far, type, props FROM g_edge UNION ALL SELECT eid, dst, src, type, props FROM g_edge WHERE src <> dst) e
                    JOIN g_node neighbours_ ON neighbours_.nid = e.far
                WHERE e.near = shelf_.nid
                  AND e.type = 'NEXT_TO'
                  AND neighbours_.labels @> '["RrShelf"]')
            """.trimIndent(),
            predicate { where { query.neighbours.any { } } },
        )
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
    fun `a condition on a field read to a depth joins each path of single hops in its own EXISTS`() {
        val select = PostgresSelect(PostgresGraph("g"), CdWindowView::class.java)
        assertEquals(
            """
            (EXISTS (
                SELECT 1
                FROM g_edge e
                    JOIN g_node following_ ON following_.nid = e.dst
                WHERE e.src = node_.nid
                  AND e.type = 'NEXT'
                  AND following_.labels @> '["CdNode"]') OR EXISTS (
                SELECT 1
                FROM g_edge e1
                    JOIN g_node m1 ON m1.nid = e1.dst
                    JOIN g_edge e2 ON e2.src = m1.nid
                    JOIN g_node following_ ON following_.nid = e2.dst
                WHERE e1.src = node_.nid
                  AND e1.type = 'NEXT'
                  AND e2.type = 'NEXT'
                  AND following_.labels @> '["CdNode"]'
                  AND e1.eid <> e2.eid))
            """.trimIndent(),
            select.predicate(GraphQuerySpec(CdWindowViewQueryDsl.INSTANCE).apply { where { query.following.any { } } }.conditions),
        )
    }
}
