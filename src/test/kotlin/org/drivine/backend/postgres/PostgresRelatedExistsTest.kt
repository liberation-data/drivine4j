package org.drivine.backend.postgres

import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.any
import org.drivine.query.dsl.none
import org.drivine.query.dsl.query
import org.junit.jupiter.api.Test
import sample.relatedrows.RrShelfView
import sample.relatedrows.RrShelfViewQueryDsl
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
