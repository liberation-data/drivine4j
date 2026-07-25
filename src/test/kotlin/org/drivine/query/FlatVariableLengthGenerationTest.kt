package org.drivine.query

import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import sample.flatexpand.SeqExpandView
import sample.flatexpand.SeqOneHopView
import sample.mapped.view.LocationHierarchy

/**
 * Generation guards for the flat `List<Fragment>` variable-length branch: `maxDepth > 1` emits a
 * `*1..N` CALL-subquery collecting a de-duplicated flat list; `maxDepth == 1` and recursive views are
 * unchanged.
 */
class FlatVariableLengthGenerationTest {

    private val neo4j = CypherDialect.NEO4J_5.grammar()

    @Test
    fun `flat list with maxDepth greater than 1 emits variable-length with node-identity dedup`() {
        val q = GraphViewQueryBuilder.forView(SeqExpandView::class, neo4j).buildQuery()

        // Direction applies to the whole *1..N path, both ways.
        assertTrue(q.contains("(node)-[:NEXT*1..10]->(following:Seq)"), q)
        assertTrue(q.contains("(node)<-[:NEXT*1..10]-(preceding:Seq)"), q)
        // De-dup on node identity, then project each.
        assertTrue(q.contains("collect(DISTINCT following)"), q)
        assertTrue(Regex("""\[following IN _following_nodes \| following \{""").containsMatchIn(q), q)
    }

    @Test
    fun `flat list with default maxDepth stays a single-hop comprehension`() {
        val q = GraphViewQueryBuilder.forView(SeqOneHopView::class, neo4j).buildQuery()
        assertFalse(q.contains("*1.."), "default maxDepth must not become variable-length:\n$q")
        assertTrue(q.contains("[(node)-[:NEXT]->(next:Seq) |"), q)
        assertFalse(Regex("""collect\(DISTINCT next\)""").containsMatchIn(q), q)
    }

    @Test
    fun `recursive self-view is unchanged - nested comprehensions, no flat variable-length`() {
        val q = GraphViewQueryBuilder.forView(LocationHierarchy::class, neo4j).buildQuery()
        // Recursive views keep their per-level nested comprehensions; they do not use `*1..N`.
        assertFalse(q.contains("*1.."), "recursive view must not switch to variable-length:\n$q")
        assertTrue(q.contains("subLocations_d1:Location"), q)
    }
}
