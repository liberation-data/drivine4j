package org.drivine.query

import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.query
import org.drivine.query.grammar.FalkorDbCypherGrammar
import org.drivine.query.grammar.MemgraphGrammar
import org.drivine.query.grammar.Neo4j5Grammar
import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.sort.CallSubqueryEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sample.fulltext.ArticleNode
import sample.fulltext.ArticleNodeQueryDsl
import sample.fulltext.ArticleView
import sample.fulltext.AuthoredArticleView
import sample.fulltext.MemoNode

/**
 * A full-text search is bounded where the index yields, not only where the rows are returned.
 *
 * The head collects every hit the index call yields to normalize the scores, and every row after it
 * carries that collection, so the number of hits — not `topK` — is what a search costs. An ordinary
 * English query matches most of a corpus: unbounded, one `k = 8` search over ~8,500 scripture chapters
 * allocated 1.17 GB on Neo4j, and two at once exhausted its transaction memory pool (embabel/me#3124).
 */
class FullTextIndexBoundTest {

    private val neo4j = Neo4j5Grammar(ApocSortMapsEmitter())

    private fun filtered(topK: Int) = FullTextSearchPlanner.planFiltered(
        ArticleNode::class.java,
        GraphQuerySpec(ArticleNodeQueryDsl.INSTANCE).apply { where { query.title eq "Graphs" } },
        "graph", topK, 0.0, neo4j,
    )

    @Test
    fun `the Neo4j index call is asked for a limit`() {
        val cypher = filtered(topK = 8).cypher

        assertTrue(
            cypher.contains("CALL db.index.fulltext.queryNodes('Article_body_fulltext', \$_fullTextQuery, {limit: \$_fullTextIndexLimit})"),
            cypher,
        )
    }

    @Test
    fun `every engine cuts the hits before collecting them`() {
        val engines = listOf(neo4j, FalkorDbCypherGrammar(CallSubqueryEmitter()), MemgraphGrammar(CallSubqueryEmitter()))
        engines.forEach { grammar ->
            val cypher = FullTextSearchPlanner.plan(ArticleNode::class.java, null, "graph", 8, 0.0, grammar).cypher
            val cut = cypher.indexOf("ORDER BY score DESC\nLIMIT \$_fullTextIndexLimit")
            val collect = cypher.indexOf("collect(")

            assertTrue(cut in 0 until collect, "${grammar::class.simpleName} collects before it cuts:\n$cypher")
        }
    }

    @Test
    fun `an unfiltered fragment search asks the index for exactly topK`() {
        // Nothing removes a hit after the index, so its best topK hits are the answer.
        val plan = FullTextSearchPlanner.plan(ArticleNode::class.java, null, "graph", 8, 0.5, neo4j)

        assertEquals(8, plan.bindings[FullTextSearchPlanner.INDEX_LIMIT_PARAM])
        assertEquals(8, plan.indexK)
    }

    @Test
    fun `a filtered search over-fetches by the stated factor`() {
        val plan = filtered(topK = 8)

        assertEquals(8 * FullTextSearchLimits.FILTERED_OVER_FETCH, plan.bindings[FullTextSearchPlanner.INDEX_LIMIT_PARAM])
        assertEquals(8, plan.bindings[FullTextSearchPlanner.TOP_K_PARAM])
        assertEquals(8, plan.requestedRows)
        assertEquals(8 * FullTextSearchLimits.FILTERED_OVER_FETCH, plan.indexK)
    }

    @Test
    fun `a filtered call whose where is empty asks the index for exactly topK`() {
        // No condition was given, so nothing removes a hit after the index.
        val plan = FullTextSearchPlanner.planFiltered(
            ArticleNode::class.java, GraphQuerySpec(ArticleNodeQueryDsl.INSTANCE), "graph", 8, 0.0, neo4j,
        )

        assertEquals(8, plan.bindings[FullTextSearchPlanner.INDEX_LIMIT_PARAM])
    }

    @Test
    fun `a filtered call over a view over-fetches for its required relationships alone`() {
        val authored = FullTextSearchPlanner.planFiltered(
            AuthoredArticleView::class.java, GraphQuerySpec(ArticleNodeQueryDsl.INSTANCE), "graph", 8, 0.0, neo4j,
        )
        val unauthored = FullTextSearchPlanner.planFiltered(
            ArticleView::class.java, GraphQuerySpec(ArticleNodeQueryDsl.INSTANCE), "graph", 8, 0.0, neo4j,
        )

        assertEquals(8 * FullTextSearchLimits.FILTERED_OVER_FETCH, authored.bindings[FullTextSearchPlanner.INDEX_LIMIT_PARAM])
        assertEquals(8, unauthored.bindings[FullTextSearchPlanner.INDEX_LIMIT_PARAM])
    }

    @Test
    fun `a view search over-fetches, because its required relationships filter the hits`() {
        val plan = FullTextSearchPlanner.plan(AuthoredArticleView::class.java, null, "graph", 8, 0.0, neo4j)

        assertEquals(8 * FullTextSearchLimits.FILTERED_OVER_FETCH, plan.bindings[FullTextSearchPlanner.INDEX_LIMIT_PARAM])
    }

    @Test
    fun `a view without a required relationship asks the index for exactly topK`() {
        // Nothing prunes its hits after the index, so it is bounded as a fragment is.
        val plan = FullTextSearchPlanner.plan(ArticleView::class.java, null, "graph", 8, 0.0, neo4j)

        assertEquals(8, plan.bindings[FullTextSearchPlanner.INDEX_LIMIT_PARAM])
        assertEquals(8, plan.indexK)
    }

    @Test
    fun `a fragment is projected only for the rows that are returned`() {
        // MemoNode carries a property bag, so it is projected through properties(n): every property,
        // which for a chunk means its text and its embedding.
        val cypher = FullTextSearchPlanner.plan(MemoNode::class.java, null, "graph", 8, 0.0, neo4j).cypher
        val limit = cypher.indexOf("LIMIT \$_fullTextTopK")
        val projection = cypher.indexOf("properties(n)")

        assertTrue(limit in 0 until projection, "properties are read before the rows are limited:\n$cypher")
    }

    @Test
    fun `the filtered rows are limited without a second sort`() {
        // The head yields its rows in score order. A sort between the filter and the limit would hold
        // every surviving row, each carrying the head's collected hits.
        val cypher = filtered(topK = 8).cypher
        val afterHead = cypher.substringAfter("UNWIND _ftResults AS _ftRow")

        assertTrue(afterHead.contains("WITH n, _score\nLIMIT \$_fullTextTopK"), cypher)
        assertEquals(1, Regex("ORDER BY").findAll(afterHead).count(), cypher)
        assertTrue(afterHead.trimEnd().endsWith("ORDER BY _score DESC"), cypher)
    }
}
