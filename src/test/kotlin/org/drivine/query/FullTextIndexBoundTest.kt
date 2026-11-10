package org.drivine.query

import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.FalkorDbCypherGrammar
import org.drivine.query.grammar.FullTextQuerySpec
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

    /** What [plan] binds the index limit as: the parameter the head's cut, before the hits are collected, names. */
    private fun indexLimit(plan: ScoredSearchPlan): Any? {
        val name = checkNotNull(Regex("""ORDER BY score DESC\nLIMIT \$(\w+)""").find(plan.cypher)) { plan.cypher }.groupValues[1]
        assertTrue(name in plan.bindings, "$name is not bound: ${plan.bindings}")
        return plan.bindings[name]
    }

    @Test
    fun `the Neo4j index call is asked for a limit`() {
        val plan = filtered(topK = 8)

        // The shared module's head, with the parameters it binds: the query, and the limit.
        assertTrue(
            plan.cypher.contains("CALL db.index.fulltext.queryNodes('Article_body_fulltext', \$_cy_ft1, {limit: \$_cy_ft2})"),
            plan.cypher,
        )
        assertEquals("graph", plan.bindings["_cy_ft1"])
        assertEquals(80, plan.bindings["_cy_ft2"])
    }

    @Test
    fun `every engine cuts the hits before collecting them`() {
        val engines = listOf(neo4j, FalkorDbCypherGrammar(CallSubqueryEmitter()), MemgraphGrammar(CallSubqueryEmitter()))
        engines.forEach { grammar ->
            val cypher = FullTextSearchPlanner.plan(ArticleNode::class.java, null, "graph", 8, 0.0, grammar).cypher
            val cut = cypher.indexOf("ORDER BY score DESC\nLIMIT \$_cy_ft")
            val collect = cypher.indexOf("collect(")

            assertTrue(cut in 0 until collect, "${grammar::class.simpleName} collects before it cuts:\n$cypher")
        }
    }

    @Test
    fun `an unfiltered fragment search asks the index for exactly topK`() {
        // Nothing removes a hit after the index, so its best topK hits are the answer.
        val plan = FullTextSearchPlanner.plan(ArticleNode::class.java, null, "graph", 8, 0.5, neo4j)

        assertEquals(8, indexLimit(plan))
        assertEquals(8, plan.indexK)
    }

    @Test
    fun `a filtered search over-fetches by the stated factor`() {
        val plan = filtered(topK = 8)

        assertEquals(8 * FullTextSearchLimits.FILTERED_OVER_FETCH, indexLimit(plan))
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

        assertEquals(8, indexLimit(plan))
    }

    @Test
    fun `a filtered call over a view over-fetches for its required relationships alone`() {
        val authored = FullTextSearchPlanner.planFiltered(
            AuthoredArticleView::class.java, GraphQuerySpec(ArticleNodeQueryDsl.INSTANCE), "graph", 8, 0.0, neo4j,
        )
        val unauthored = FullTextSearchPlanner.planFiltered(
            ArticleView::class.java, GraphQuerySpec(ArticleNodeQueryDsl.INSTANCE), "graph", 8, 0.0, neo4j,
        )

        assertEquals(8 * FullTextSearchLimits.FILTERED_OVER_FETCH, indexLimit(authored))
        assertEquals(8, indexLimit(unauthored))
    }

    @Test
    fun `a view search over-fetches, because its required relationships filter the hits`() {
        val plan = FullTextSearchPlanner.plan(AuthoredArticleView::class.java, null, "graph", 8, 0.0, neo4j)

        assertEquals(8 * FullTextSearchLimits.FILTERED_OVER_FETCH, indexLimit(plan))
    }

    @Test
    fun `a view without a required relationship asks the index for exactly topK`() {
        // Nothing prunes its hits after the index, so it is bounded as a fragment is.
        val plan = FullTextSearchPlanner.plan(ArticleView::class.java, null, "graph", 8, 0.0, neo4j)

        assertEquals(8, indexLimit(plan))
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

    @Test
    fun `every engine binds the query and the limit the shared module's head names`() {
        val engines = listOf(neo4j, FalkorDbCypherGrammar(CallSubqueryEmitter()), MemgraphGrammar(CallSubqueryEmitter()))
        engines.forEach { grammar ->
            val plan = FullTextSearchPlanner.planFiltered(
                ArticleNode::class.java,
                GraphQuerySpec(ArticleNodeQueryDsl.INSTANCE).apply { where { query.title eq "Graphs" } },
                "graph", 8, 0.0, grammar,
            )
            val named = Regex("""\$(_cy_ft\w+)""").findAll(plan.cypher).map { it.groupValues[1] }.toSet()

            // Every parameter the head names is bound, the query as the caller gave it; the grammar's own names are not bound.
            assertTrue(named.isNotEmpty() && named.all { it in plan.bindings }, "${grammar::class.simpleName}: $named not in ${plan.bindings}")
            assertTrue("graph" in named.map { plan.bindings[it] }, "${grammar::class.simpleName}: ${plan.bindings}")
            assertEquals(80, indexLimit(plan))
            assertTrue(FullTextSearchPlanner.QUERY_PARAM !in plan.bindings && FullTextSearchPlanner.INDEX_LIMIT_PARAM !in plan.bindings, plan.bindings.toString())
            // The caller's predicate keeps its own parameter beside the head's.
            assertTrue(plan.bindings.keys.any { it.startsWith("param_") }, plan.bindings.toString())
        }
    }

    @Test
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    fun `a grammar of another engine still writes its own head, bound under the planner's names`() {
        val asked = mutableListOf<FullTextQuerySpec>()
        val custom = object : CypherGrammar by neo4j {
            override fun fullTextSearchHead(spec: FullTextQuerySpec, rootAlias: String, scoreAlias: String): String {
                asked += spec
                return "CALL custom.search(\$${spec.queryParam}, \$${spec.indexLimitParam}) YIELD node, score\n" +
                    "WITH node AS $rootAlias, score AS $scoreAlias"
            }
        }
        val plan = FullTextSearchPlanner.planFiltered(
            ArticleNode::class.java,
            GraphQuerySpec(ArticleNodeQueryDsl.INSTANCE).apply { where { query.title eq "Graphs" } },
            "graph", 8, 0.0, custom,
        )

        assertTrue(plan.cypher.startsWith("CALL custom.search(\$_fullTextQuery, \$_fullTextIndexLimit)"), plan.cypher)
        assertEquals(listOf("Article"), asked.single().labels)
        assertEquals("graph", plan.bindings[FullTextSearchPlanner.QUERY_PARAM])
        assertEquals(80, plan.bindings[FullTextSearchPlanner.INDEX_LIMIT_PARAM])
        assertEquals(80, plan.indexK)
        assertTrue(plan.bindings.keys.none { it.startsWith("_cy_ft") }, plan.bindings.toString())
    }
}
