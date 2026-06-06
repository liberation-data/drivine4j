package org.drivine.query

import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.simple.TestAppContext
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end `@GraphPath` against real Neo4j: traverses Actor→Movie→Director skipping Movie,
 * de-duplicates the far node, and filters roots lacking a required path.
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class GraphPathE2ETest @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager,
) {

    @BeforeEach
    fun seed() {
        persistenceManager.execute(
            org.drivine.query.QuerySpecification.withStatement(
                "MATCH (n) WHERE n.createdBy = 'path-test' DETACH DELETE n"
            )
        )
        persistenceManager.execute(
            org.drivine.query.QuerySpecification.withStatement(
                """
                // a1 acted in two movies, BOTH directed by d1 → directors must dedup to [d1]
                CREATE (a1:PActor {id: 'a1', name: 'Keanu', createdBy: 'path-test'})
                CREATE (m1:PMovie {id: 'm1', title: 'Matrix', createdBy: 'path-test'})
                CREATE (m2:PMovie {id: 'm2', title: 'Matrix Reloaded', createdBy: 'path-test'})
                CREATE (d1:PDirector {id: 'd1', name: 'Wachowski', createdBy: 'path-test'})
                CREATE (a1)-[:ACTED_IN]->(m1) CREATE (a1)-[:ACTED_IN]->(m2)
                CREATE (m1)-[:DIRECTED_BY]->(d1) CREATE (m2)-[:DIRECTED_BY]->(d1)

                // a3 worked with two DIFFERENT directors → [d1, d2]
                CREATE (a3:PActor {id: 'a3', name: 'Carrie', createdBy: 'path-test'})
                CREATE (m4:PMovie {id: 'm4', title: 'Other', createdBy: 'path-test'})
                CREATE (d2:PDirector {id: 'd2', name: 'Reeves', createdBy: 'path-test'})
                CREATE (a3)-[:ACTED_IN]->(m1) CREATE (a3)-[:ACTED_IN]->(m4)
                CREATE (m4)-[:DIRECTED_BY]->(d2)

                // a2 acted in a movie with NO director → empty path / required excluded
                CREATE (a2:PActor {id: 'a2', name: 'Nobody', createdBy: 'path-test'})
                CREATE (m3:PMovie {id: 'm3', title: 'Indie', createdBy: 'path-test'})
                CREATE (a2)-[:ACTED_IN]->(m3)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `collection path de-duplicates the far node`() {
        val a1 = graphObjectManager.load("a1", ActorDirectors::class.java)!!
        assertEquals(listOf("Wachowski"), a1.directors.map { it.name })  // two movies, one director → deduped

        val a3 = graphObjectManager.load("a3", ActorDirectors::class.java)!!
        assertEquals(setOf("Wachowski", "Reeves"), a3.directors.map { it.name }.toSet())

        val a2 = graphObjectManager.load("a2", ActorDirectors::class.java)!!
        assertTrue(a2.directors.isEmpty(), "actor with no director path → empty list")
    }

    @Test
    fun `single optional path materializes one or null`() {
        val a1 = graphObjectManager.load("a1", ActorTopDirector::class.java)!!
        assertEquals("Wachowski", a1.director?.name)

        val a2 = graphObjectManager.load("a2", ActorTopDirector::class.java)!!
        assertNull(a2.director, "no director path → null single")
    }

    @Test
    fun `required path filters out roots lacking it`() {
        val ids = graphObjectManager.loadAll(ActorRequiredDirector::class.java).map { it.actor.id }.toSet()
        assertEquals(setOf("a1", "a3"), ids)  // a2 has no director path → excluded
    }

    @Test
    fun `per-root aggregates - count and avg-sum without materializing`() {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (a:PActor {id: 'stat1', name: 'Rated', createdBy: 'path-test'})
                CREATE (r1:PMovie {id: 'r1', title: 'R1', score: 1.0, createdBy: 'path-test'})
                CREATE (r2:PMovie {id: 'r2', title: 'R2', score: 2.0, createdBy: 'path-test'})
                CREATE (r3:PMovie {id: 'r3', title: 'R3', score: 3.0, createdBy: 'path-test'})
                CREATE (a)-[:ACTED_IN]->(r1) CREATE (a)-[:ACTED_IN]->(r2) CREATE (a)-[:ACTED_IN]->(r3)
                CREATE (a)-[:RATED]->(r1) CREATE (a)-[:RATED]->(r2) CREATE (a)-[:RATED]->(r3)
                """.trimIndent()
            )
        )
        val stats = graphObjectManager.load("stat1", ActorStats::class.java)!!
        assertEquals(3L, stats.movieCount)
        assertEquals(2.0, stats.avgScore)
        assertEquals(6.0, stats.totalScore)
    }
}