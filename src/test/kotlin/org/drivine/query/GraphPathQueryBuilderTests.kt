package org.drivine.query

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphPath
import org.drivine.annotation.GraphView
import org.drivine.annotation.Hop
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.ReadOnly
import org.drivine.annotation.Root
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
    @ReadOnly
    @GraphPath([
        Hop("ACTED_IN", Direction.OUTGOING, label = "PMovie"),
        Hop("DIRECTED_BY", Direction.OUTGOING),
    ])
    val directors: List<PDirector>,
)

@GraphView
data class ActorTopDirector(
    @Root val actor: PActor,
    @ReadOnly
    @GraphPath([
        Hop("ACTED_IN", Direction.OUTGOING, label = "PMovie"),
        Hop("DIRECTED_BY", Direction.OUTGOING),
    ])
    val director: PDirector?,   // single optional
)

@GraphView
data class ActorRequiredDirector(
    @Root val actor: PActor,
    @ReadOnly
    @GraphPath([
        Hop("ACTED_IN", Direction.OUTGOING, label = "PMovie"),
        Hop("DIRECTED_BY", Direction.OUTGOING),
    ])
    val director: PDirector,    // required single
)

/** The directors an actor worked with, in the order of the year of the first movie that led to each. */
@GraphView
data class ActorDirectorsByYear(
    @Root val actor: PActor,
    @ReadOnly
    @GraphPath([
        Hop("ACTED_IN", Direction.OUTGOING, label = "PMovie", orderBy = "year"),
        Hop("DIRECTED_BY", Direction.OUTGOING),
    ])
    val directors: List<PDirector>,
)

/** The director of an actor's earliest movie. */
@GraphView
data class ActorFirstDirector(
    @Root val actor: PActor,
    @ReadOnly
    @GraphPath([
        Hop("ACTED_IN", Direction.OUTGOING, label = "PMovie", orderBy = "year"),
        Hop("DIRECTED_BY", Direction.OUTGOING),
    ])
    val director: PDirector?,
)

/** The director of an actor's latest movie. */
@GraphView
data class ActorLatestDirector(
    @Root val actor: PActor,
    @ReadOnly
    @GraphPath([
        Hop("ACTED_IN", Direction.OUTGOING, label = "PMovie", orderBy = "year", ascending = false),
        Hop("DIRECTED_BY", Direction.OUTGOING),
    ])
    val director: PDirector?,
)

/**
 * o1 acted in five movies by four directors; Nolan directed two of them, so is reached twice. By
 * year: Murnau 999, Lynch 1986, Nolan 2000, Bigelow 2008, Nolan 2010. 999 is first as a number and
 * last as text, so an engine that compares the years as text puts Murnau last. o2 acted in nothing.
 */
const val ORDERED_SEED = """
    CREATE (o1:PActor {id: 'o1', name: 'Ordered', createdBy: 'path-test'})
    CREATE (o2:PActor {id: 'o2', name: 'Idle', createdBy: 'path-test'})
    CREATE (y1:PMovie {id: 'y1', title: 'Nosferatu', year: 999, createdBy: 'path-test'})
    CREATE (y2:PMovie {id: 'y2', title: 'Blue Velvet', year: 1986, createdBy: 'path-test'})
    CREATE (y3:PMovie {id: 'y3', title: 'Memento', year: 2000, createdBy: 'path-test'})
    CREATE (y4:PMovie {id: 'y4', title: 'The Hurt Locker', year: 2008, createdBy: 'path-test'})
    CREATE (y5:PMovie {id: 'y5', title: 'Inception', year: 2010, createdBy: 'path-test'})
    CREATE (murnau:PDirector {id: 'murnau', name: 'Murnau', createdBy: 'path-test'})
    CREATE (lynch:PDirector {id: 'lynch', name: 'Lynch', createdBy: 'path-test'})
    CREATE (nolan:PDirector {id: 'nolan', name: 'Nolan', createdBy: 'path-test'})
    CREATE (bigelow:PDirector {id: 'bigelow', name: 'Bigelow', createdBy: 'path-test'})
    CREATE (o1)-[:ACTED_IN]->(y5) CREATE (o1)-[:ACTED_IN]->(y3) CREATE (o1)-[:ACTED_IN]->(y1)
    CREATE (o1)-[:ACTED_IN]->(y4) CREATE (o1)-[:ACTED_IN]->(y2)
    CREATE (y1)-[:DIRECTED_BY]->(murnau) CREATE (y2)-[:DIRECTED_BY]->(lynch) CREATE (y3)-[:DIRECTED_BY]->(nolan)
    CREATE (y4)-[:DIRECTED_BY]->(bigelow) CREATE (y5)-[:DIRECTED_BY]->(nolan)
"""

/** What every engine must read from [ORDERED_SEED]: the directors by year, the first, and the latest. */
fun assertOrderedPaths(load: (String, Class<*>) -> Any?) {
    fun directors(id: String) = (load(id, ActorDirectorsByYear::class.java) as ActorDirectorsByYear).directors.map { it.name }
    fun first(id: String) = (load(id, ActorFirstDirector::class.java) as ActorFirstDirector).director?.name
    fun latest(id: String) = (load(id, ActorLatestDirector::class.java) as ActorLatestDirector).director?.name
    assertEquals(listOf("Murnau", "Lynch", "Nolan", "Bigelow"), directors("o1"))
    assertEquals("Murnau", first("o1"))
    assertEquals("Nolan", latest("o1"))
    assertEquals(emptyList(), directors("o2"))
    assertEquals(null, first("o2"))
}

/** Two hops that each name an order: refused, since the path can be read in one order only. */
@GraphView
data class ActorDirectorsOrderedTwice(
    @Root val actor: PActor,
    @ReadOnly
    @GraphPath([
        Hop("ACTED_IN", Direction.OUTGOING, label = "PMovie", orderBy = "year"),
        Hop("DIRECTED_BY", Direction.OUTGOING, orderBy = "name"),
    ])
    val directors: List<PDirector>,
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
    fun `on FalkorDB the node a path ends at is tested for its labels once the path is matched`() {
        // FalkorDB 6 plans an OPTIONAL MATCH whose far node has a label from that end: it reads every
        // relationship into the label and then joins back to the root (FalkorDB/FalkorDB#3332). A
        // label asked for in a WHERE on the match is planned the same way, so it is tested in the CASE.
        val q = query(ActorDirectors::class, CypherDialect.FALKORDB)

        assertTrue(q.contains("OPTIONAL MATCH (actor)-[:ACTED_IN]->(:PMovie)-[:DIRECTED_BY]->(directors)\n"), q)
        assertTrue(q.contains("collect(DISTINCT CASE WHEN directors:PDirector THEN"), q)
        assertFalse(q.contains("(directors:PDirector)"), q)

        val single = query(ActorTopDirector::class, CypherDialect.FALKORDB)
        assertTrue(single.contains("head(collect(DISTINCT CASE WHEN director:PDirector THEN"), single)

        // The engines that plan the pattern from the root keep the label in it.
        listOf(CypherDialect.NEO4J_5, CypherDialect.NEO4J_4, CypherDialect.MEMGRAPH, CypherDialect.NEPTUNE).forEach { d ->
            assertTrue(query(ActorDirectors::class, d).contains("-[:DIRECTED_BY]->(directors:PDirector)"), "$d")
        }
    }

    @Test
    fun `a hop that names an order sorts the path by the node it reaches before the targets are collected`() {
        listOf(CypherDialect.NEO4J_5, CypherDialect.MEMGRAPH, CypherDialect.FALKORDB).forEach { d ->
            val q = query(ActorFirstDirector::class, d)
            assertTrue(q.contains("-[:ACTED_IN]->(director_hop0:PMovie)-[:DIRECTED_BY]->"), "$d: $q")
            assertTrue(q.contains("WITH actor, director, director_hop0 ORDER BY director_hop0.year ASC"), "$d: $q")
            assertTrue(q.indexOf("ORDER BY director_hop0.year") < q.indexOf("head(collect(DISTINCT"), "$d: $q")
        }
        assertTrue(query(ActorLatestDirector::class, CypherDialect.NEO4J_5).contains("ORDER BY director_hop0.year DESC"))
    }

    @Test
    fun `a path with no order names no node it passes through`() {
        val q = query(ActorDirectors::class, CypherDialect.NEO4J_5)
        assertFalse(q.contains("ORDER BY"), q)
        assertTrue(q.contains("-[:ACTED_IN]->(:PMovie)-"), q)
    }

    @Test
    fun `a path a group key is read by may name no order`() {
        val spec = GroupQuerySpec(Unit)
        val e = runCatching { spec.path(PDirector::class.java, Hop("ACTED_IN", orderBy = "year"), Hop("DIRECTED_BY")) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "$e")
    }

    @Test
    fun `a path may be ordered by one hop only`() {
        val e = runCatching { query(ActorDirectorsOrderedTwice::class, CypherDialect.NEO4J_5) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && e.message.orEmpty().contains("directors"), "$e")
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