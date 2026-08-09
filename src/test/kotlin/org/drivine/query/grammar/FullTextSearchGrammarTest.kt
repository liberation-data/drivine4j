package org.drivine.query.grammar

import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.sort.CallSubqueryEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit coverage for each grammar's [CypherGrammar.fullTextSearchHead] — the engine-specific
 * divergence point for full-text search. Every head must address the index the right way (name vs
 * label), bind the matched node to the root alias, and expose a `[0, 1]`-normalized score (with the
 * `maxScore == 0` guard). Engines without a native full-text index must throw.
 */
class FullTextSearchGrammarTest {

    private fun spec() = FullTextQuerySpec(
        label = "Article",
        indexName = "Article_body_fulltext",
        queryParam = "q",
        topKParam = "topK",
    )

    /** The shared normalization tail every engine routes through. */
    private fun assertNormalizedTail(head: String, rootAlias: String, scoreAlias: String) {
        assertTrue(head.contains("YIELD node, score"), head)
        assertTrue(head.contains("WITH collect({node: node, score: score}) AS _ftResults, max(score) AS _ftMax"), head)
        assertTrue(head.contains("UNWIND _ftResults AS _ftRow"), head)
        assertTrue(
            head.contains("WITH _ftRow.node AS $rootAlias, (CASE WHEN _ftMax > 0 THEN _ftRow.score / _ftMax ELSE 1.0 END) AS $scoreAlias"),
            head,
        )
    }

    @Test
    fun `Neo4j queries the full-text index by name`() {
        val grammar = Neo4j5Grammar(ApocSortMapsEmitter())
        assertTrue(grammar.supportsFullTextSearch)

        val head = grammar.fullTextSearchHead(spec(), rootAlias = "article", scoreAlias = "_score")
        assertTrue(head.contains("CALL db.index.fulltext.queryNodes('Article_body_fulltext', \$q)"), head)
        assertNormalizedTail(head, "article", "_score")
    }

    @Test
    fun `FalkorDB queries the full-text index by label`() {
        val grammar = FalkorDbCypherGrammar(CallSubqueryEmitter())
        assertTrue(grammar.supportsFullTextSearch)

        val head = grammar.fullTextSearchHead(spec(), "article", "_score")
        // FalkorDB has no index names — addressed by label, and searches every indexed property.
        assertTrue(head.contains("CALL db.idx.fulltext.queryNodes('Article', \$q)"), head)
        assertNormalizedTail(head, "article", "_score")
    }

    @Test
    fun `Memgraph queries the text index by name via search_all`() {
        val grammar = MemgraphGrammar(CallSubqueryEmitter())
        assertTrue(grammar.supportsFullTextSearch)

        val head = grammar.fullTextSearchHead(spec(), "article", "_score")
        assertTrue(head.contains("CALL text_search.search_all('Article_body_fulltext', \$q)"), head)
        assertNormalizedTail(head, "article", "_score")
    }

    @Test
    fun `Neptune has no native full-text index and throws`() {
        val grammar = NeptuneCypherGrammar(CallSubqueryEmitter())
        assertFalse(grammar.supportsFullTextSearch)
        assertThrows<UnsupportedOperationException> {
            grammar.fullTextSearchHead(spec(), "article", "_score")
        }
    }

    @Test
    fun `base openCypher grammar has no full-text search by default and throws`() {
        val grammar = OpenCypherGrammar(CallSubqueryEmitter())
        assertFalse(grammar.supportsFullTextSearch)
        assertThrows<UnsupportedOperationException> {
            grammar.fullTextSearchHead(spec(), "article", "_score")
        }
    }

    @Test
    fun `the normalization guard is shared verbatim across engines`() {
        // Neo4j and Memgraph key by name, FalkorDB by label, but the [0,1] normalization tail — the
        // part that bit the consumers — is identical, produced by the single shared helper.
        val neo = Neo4j5Grammar(ApocSortMapsEmitter()).fullTextSearchHead(spec(), "a", "_score")
        val falkor = FalkorDbCypherGrammar(CallSubqueryEmitter()).fullTextSearchHead(spec(), "a", "_score")
        val memgraph = MemgraphGrammar(CallSubqueryEmitter()).fullTextSearchHead(spec(), "a", "_score")
        val tail = "WITH _ftRow.node AS a, (CASE WHEN _ftMax > 0 THEN _ftRow.score / _ftMax ELSE 1.0 END) AS _score"
        listOf(neo, falkor, memgraph).forEach { assertTrue(it.endsWith(tail), it) }
        // Sanity: the CALL lines actually differ per engine.
        assertEquals(3, listOf(neo, falkor, memgraph).map { it.lineSequence().first() }.toSet().size)
    }
}