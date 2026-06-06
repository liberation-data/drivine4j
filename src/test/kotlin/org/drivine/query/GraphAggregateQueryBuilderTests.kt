package org.drivine.query

import org.drivine.annotation.Aggregate
import org.drivine.annotation.AggregateFunction
import org.drivine.annotation.Count
import org.drivine.annotation.GraphView
import org.drivine.annotation.Root
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Reuses PActor from GraphPathQueryBuilderTests.
@GraphView
data class ActorStats(
    @Root val actor: PActor,
    @Count("ACTED_IN") val movieCount: Long,
    @Aggregate(AggregateFunction.AVG, type = "RATED", property = "score") val avgScore: Double,
    @Aggregate(AggregateFunction.SUM, type = "RATED", property = "score") val totalScore: Double,
)

/**
 * Cypher-generation tests for `@Count` / `@Aggregate`. COUNT is inline `size([… | 1])`;
 * SUM/AVG/MIN/MAX are a uniform CALL subquery (no per-engine divergence — verified live that
 * Memgraph accepts `CALL { … RETURN avg(…) }`).
 */
class GraphAggregateQueryBuilderTests {

    private fun query(dialect: CypherDialect): String =
        GraphViewQueryBuilder.forView(ActorStats::class, dialect.grammar()).buildQuery()

    @Test
    fun `count is an inline size comprehension, no CALL`() {
        val q = query(CypherDialect.NEO4J_5)
        assertTrue(q.contains("size([(actor)-[:ACTED_IN]->(movieCount_x) | 1]) AS movieCount"), q)
        assertTrue(q.contains("movieCount: movieCount"), q)  // projected in RETURN
    }

    @Test
    fun `avg and sum emit CALL subqueries bridged into the projection`() {
        val q = query(CypherDialect.NEO4J_5)
        assertTrue(q.contains("CALL {"), q)
        assertTrue(q.contains("OPTIONAL MATCH (actor)-[:RATED]->(avgScore_x)"), q)
        assertTrue(q.contains("RETURN avg(avgScore_x.score) AS avgScore"), q)
        assertTrue(q.contains("RETURN sum(totalScore_x.score) AS totalScore"), q)
        // Risk-1: bridge vars reach the projection WITH and the RETURN map
        assertTrue(q.contains("avgScore AS avgScore"), q)
        assertTrue(q.contains("avgScore: avgScore"), q)
    }

    @Test
    fun `aggregate emission is identical across engines (no grammar divergence)`() {
        val neo = query(CypherDialect.NEO4J_5)
        val memgraph = query(CypherDialect.MEMGRAPH)
        val falkor = query(CypherDialect.FALKORDB)
        // The aggregate fragments are engine-independent
        listOf(memgraph, falkor).forEach { q ->
            assertTrue(q.contains("size([(actor)-[:ACTED_IN]->(movieCount_x) | 1]) AS movieCount"), q)
            assertTrue(q.contains("RETURN avg(avgScore_x.score) AS avgScore"), q)
        }
        assertTrue(neo.contains("RETURN avg(avgScore_x.score) AS avgScore"))
    }

    @Test
    fun `avg without a property fails fast at model time`() {
        // @Aggregate(AVG) with no property → IllegalArgumentException during model build
        val ex = runCatching {
            GraphViewQueryBuilder.forView(BadAggregate::class, CypherDialect.NEO4J_5.grammar()).buildQuery()
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IllegalArgumentException, got $ex")
        assertTrue(ex!!.message!!.contains("requires a"), ex.message)
    }
}

@GraphView
data class BadAggregate(
    @Root val actor: PActor,
    @Aggregate(AggregateFunction.AVG, type = "RATED") val avgScore: Double,  // missing property
)