package org.drivine.query

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ---- Fixtures: Actor -[:ACTED_IN]-> Movie -[:DIRECTED_BY]-> Director ----

@NodeFragment(labels = ["PActor"])
data class PActor(@NodeId val id: String, val name: String)

@NodeFragment(labels = ["PMovie"])
data class PMovie(@NodeId val id: String, val title: String)

@NodeFragment(labels = ["PDirector"])
data class PDirector(@NodeId val id: String, val name: String)

@GraphView
data class ActorDirectors(
    @Root val actor: PActor,
    @GraphPath([
        Hop("ACTED_IN", Direction.OUTGOING, label = "PMovie"),
        Hop("DIRECTED_BY", Direction.OUTGOING),
    ])
    val directors: List<PDirector>,
)

@GraphView
data class ActorTopDirector(
    @Root val actor: PActor,
    @GraphPath([
        Hop("ACTED_IN", Direction.OUTGOING, label = "PMovie"),
        Hop("DIRECTED_BY", Direction.OUTGOING),
    ])
    val director: PDirector?,   // single optional
)

@GraphView
data class ActorRequiredDirector(
    @Root val actor: PActor,
    @GraphPath([
        Hop("ACTED_IN", Direction.OUTGOING, label = "PMovie"),
        Hop("DIRECTED_BY", Direction.OUTGOING),
    ])
    val director: PDirector,    // required single
)

/**
 * Cypher-generation tests for `@GraphPath`. Paths emit a uniform CALL-subquery prolog
 * (OPTIONAL MATCH through the intermediate, `collect(DISTINCT … null-guard …)`), `head()` for a
 * single target, and a per-dialect existence check for a required path.
 */
class GraphPathQueryBuilderTests {

    private fun query(view: kotlin.reflect.KClass<*>, dialect: CypherDialect): String =
        GraphViewQueryBuilder.forView(view, dialect.grammar()).buildQuery()

    @Test
    fun `collection path emits a CALL-subquery that traverses and dedups`() {
        val q = query(ActorDirectors::class, CypherDialect.NEO4J_5)

        assertTrue(q.contains("CALL {"), q)
        assertTrue(
            q.contains("OPTIONAL MATCH (actor)-[:ACTED_IN]->(:PMovie)-[:DIRECTED_BY]->(directors:PDirector)"),
            q,
        )
        assertTrue(q.contains("collect(DISTINCT CASE WHEN directors IS NOT NULL"), q)
        // Risk-1 guard: the bridge var must reach the projection WITH so RETURN resolves
        assertTrue(q.contains("directors AS directors"), q)
        assertTrue(q.contains("directors: directors"), q)
        // CALL prolog precedes the final RETURN projection
        assertTrue(q.indexOf("CALL {") < q.indexOf("RETURN {"), q)
    }

    @Test
    fun `single path unwraps with head, not index-zero`() {
        val q = query(ActorTopDirector::class, CypherDialect.NEO4J_5)

        assertTrue(q.contains("head(collect(DISTINCT CASE WHEN director IS NOT NULL"), q)
        assertFalse(q.contains("][0]"), "single path must use head(), not [0]: $q")
    }

    @Test
    fun `required path filters on the computed value, portably across engines`() {
        // A required single path is computed by the CALL prolog via head(collect(…)); a null value
        // means no path. The required check is a plain value null-check (no pattern predicate after
        // a WITH, which Memgraph rejects) — identical on every engine.
        listOf(
            CypherDialect.NEO4J_5, CypherDialect.NEO4J_4,
            CypherDialect.MEMGRAPH, CypherDialect.FALKORDB, CypherDialect.NEPTUNE,
        ).forEach { d ->
            val q = query(ActorRequiredDirector::class, d)
            assertTrue(q.contains("WHERE director IS NOT NULL"), "$d: $q")
        }
    }

    @Test
    fun `path projection is identical across engines (uniform CALL)`() {
        // CALL-subquery path works on all three (verified live on Memgraph + FalkorDB)
        listOf(CypherDialect.NEO4J_5, CypherDialect.MEMGRAPH, CypherDialect.FALKORDB).forEach { d ->
            val q = query(ActorDirectors::class, d)
            assertTrue(q.contains("CALL {") && q.contains("collect(DISTINCT"), "$d: $q")
        }
    }
}