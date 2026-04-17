package org.drivine.query.dsl

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.simple.TestAppContext
import org.junit.jupiter.api.RepeatedTest
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probe test: Can CALL { } subqueries replace apoc.coll.sortMaps()
 * for collection sorting?
 *
 * Tests three scenarios against Neo4j to verify correctness before
 * changing the CypherGenerator.
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class CallSubqueryProbeTest @Autowired constructor(
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'call-subquery-probe' DETACH DELETE n")
        )

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (issue1:Issue {
                        uuid: ${'$'}issue1Uuid, id: 1001, title: 'Test issue 1',
                        state: 'open', createdBy: 'call-subquery-probe'
                    })
                    CREATE (issue3:Issue {
                        uuid: ${'$'}issue3Uuid, id: 1003, title: 'Test issue 3',
                        state: 'open', createdBy: 'call-subquery-probe'
                    })
                    CREATE (alice:Person:Mapped {
                        uuid: ${'$'}aliceUuid, name: 'Alice', createdBy: 'call-subquery-probe'
                    })
                    CREATE (charlie:Person:Mapped {
                        uuid: ${'$'}charlieUuid, name: 'Charlie', createdBy: 'call-subquery-probe'
                    })
                    CREATE (diana:Person:Mapped {
                        uuid: ${'$'}dianaUuid, name: 'Diana', createdBy: 'call-subquery-probe'
                    })
                    CREATE (acme:Organization {
                        uuid: ${'$'}acmeUuid, name: 'Acme Corp', createdBy: 'call-subquery-probe'
                    })
                    CREATE (beta:Organization {
                        uuid: ${'$'}betaUuid, name: 'Beta Inc', createdBy: 'call-subquery-probe'
                    })
                    CREATE (issue1)-[:RAISED_BY]->(alice)
                    CREATE (issue1)-[:ASSIGNED_TO]->(charlie)
                    CREATE (issue3)-[:RAISED_BY]->(alice)
                    CREATE (issue3)-[:ASSIGNED_TO]->(charlie)
                    CREATE (issue3)-[:ASSIGNED_TO]->(diana)
                    CREATE (alice)-[:WORKS_FOR]->(acme)
                    CREATE (alice)-[:WORKS_FOR]->(beta)
                """.trimIndent())
                .bind(mapOf(
                    "issue1Uuid" to UUID.randomUUID().toString(),
                    "issue3Uuid" to UUID.randomUUID().toString(),
                    "aliceUuid" to UUID.randomUUID().toString(),
                    "charlieUuid" to UUID.randomUUID().toString(),
                    "dianaUuid" to UUID.randomUUID().toString(),
                    "acmeUuid" to UUID.randomUUID().toString(),
                    "betaUuid" to UUID.randomUUID().toString()
                ))
        )
    }

    // =========================================================================
    // Probe 1: Flat collection sort — CALL { } vs apoc.coll.sortMaps()
    // Issue 1003 has assignees Charlie and Diana
    // =========================================================================

    @Test
    fun `APOC - sort assignees ascending`() {
        val names = persistenceManager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {id: 1003, createdBy: 'call-subquery-probe'})
                    WITH issue,
                        reverse(apoc.coll.sortMaps(
                            [(issue)-[:ASSIGNED_TO]->(u:Person) | u { .name }],
                            'name'
                        )) AS assignees
                    UNWIND assignees AS a
                    RETURN a.name AS name
                """.trimIndent())
                .transform<String>()
        )
        assertEquals(listOf("Charlie", "Diana"), names)
    }

    @Test
    fun `CALL subquery - sort assignees ascending`() {
        val names = persistenceManager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {id: 1003, createdBy: 'call-subquery-probe'})
                    CALL {
                        WITH issue
                        MATCH (issue)-[:ASSIGNED_TO]->(u:Person)
                        WITH u ORDER BY u.name ASC
                        RETURN collect(u.name) AS sortedNames
                    }
                    UNWIND sortedNames AS name
                    RETURN name
                """.trimIndent())
                .transform<String>()
        )
        assertEquals(listOf("Charlie", "Diana"), names)
    }

    @Test
    fun `CALL subquery - sort assignees descending`() {
        val names = persistenceManager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {id: 1003, createdBy: 'call-subquery-probe'})
                    CALL {
                        WITH issue
                        MATCH (issue)-[:ASSIGNED_TO]->(u:Person)
                        WITH u ORDER BY u.name DESC
                        RETURN collect(u.name) AS sortedNames
                    }
                    UNWIND sortedNames AS name
                    RETURN name
                """.trimIndent())
                .transform<String>()
        )
        assertEquals(listOf("Diana", "Charlie"), names)
    }

    // =========================================================================
    // Probe 2: Nested collection sort — two hops deep
    // Alice WORKS_FOR [Acme Corp, Beta Inc]
    // Issue 1001 RAISED_BY Alice
    // =========================================================================

    @Test
    fun `APOC - sort nested worksFor ascending`() {
        val names = persistenceManager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {id: 1001, createdBy: 'call-subquery-probe'})-[:RAISED_BY]->(raiser:Person)
                    WITH issue, raiser,
                        reverse(apoc.coll.sortMaps(
                            [(raiser)-[:WORKS_FOR]->(org:Organization) | org { .name }],
                            'name'
                        )) AS orgs
                    UNWIND orgs AS o
                    RETURN o.name AS name
                """.trimIndent())
                .transform<String>()
        )
        assertEquals(listOf("Acme Corp", "Beta Inc"), names)
    }

    @Test
    fun `CALL subquery - sort nested worksFor ascending`() {
        val names = persistenceManager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {id: 1001, createdBy: 'call-subquery-probe'})-[:RAISED_BY]->(raiser:Person)
                    CALL {
                        WITH raiser
                        MATCH (raiser)-[:WORKS_FOR]->(org:Organization)
                        WITH org ORDER BY org.name ASC
                        RETURN collect(org.name) AS sortedOrgs
                    }
                    UNWIND sortedOrgs AS name
                    RETURN name
                """.trimIndent())
                .transform<String>()
        )
        assertEquals(listOf("Acme Corp", "Beta Inc"), names)
    }

    @Test
    fun `CALL subquery - sort nested worksFor descending`() {
        val names = persistenceManager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {id: 1001, createdBy: 'call-subquery-probe'})-[:RAISED_BY]->(raiser:Person)
                    CALL {
                        WITH raiser
                        MATCH (raiser)-[:WORKS_FOR]->(org:Organization)
                        WITH org ORDER BY org.name DESC
                        RETURN collect(org.name) AS sortedOrgs
                    }
                    UNWIND sortedOrgs AS name
                    RETURN name
                """.trimIndent())
                .transform<String>()
        )
        assertEquals(listOf("Beta Inc", "Acme Corp"), names)
    }

    // =========================================================================
    // Probe 3: Combined root ORDER BY + collection sort
    // Two open issues (1003, 1001), root ordered by id DESC
    // Each issue's assignees sorted by name ASC
    // =========================================================================

    @Test
    fun `CALL subquery - combined root order and collection sort`() {
        // Return a concatenated string per row to sidestep transform limitations
        val results = persistenceManager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {state: 'open', createdBy: 'call-subquery-probe'})
                    CALL {
                        WITH issue
                        MATCH (issue)-[:ASSIGNED_TO]->(u:Person)
                        WITH u ORDER BY u.name ASC
                        RETURN collect(u.name) AS assigneeNames
                    }
                    WITH issue, assigneeNames
                    ORDER BY issue.id DESC
                    RETURN issue.id + ':' + reduce(s = '', n IN assigneeNames | s + CASE WHEN s = '' THEN '' ELSE ',' END + n) AS result
                """.trimIndent())
                .transform<String>()
        )

        assertEquals(2, results.size)
        assertEquals("1003:Charlie,Diana", results[0])
        assertEquals("1001:Charlie", results[1])
    }

    // =========================================================================
    // Probe 3b: COLLECT { } subquery EXPRESSION
    // Can it be used inline in a RETURN projection as a drop-in for apoc.coll.sortMaps()?
    // =========================================================================

    @Test
    fun `COLLECT subquery expression - inline sorted collection`() {
        val names = persistenceManager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {id: 1003, createdBy: 'call-subquery-probe'})
                    UNWIND COLLECT {
                        MATCH (issue)-[:ASSIGNED_TO]->(u:Person)
                        WITH u ORDER BY u.name ASC
                        RETURN u.name
                    } AS name
                    RETURN name
                """.trimIndent())
                .transform<String>()
        )
        assertEquals(listOf("Charlie", "Diana"), names)
    }

    @Test
    fun `COLLECT subquery expression - inside map projection`() {
        val results = persistenceManager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {id: 1003, createdBy: 'call-subquery-probe'})
                    RETURN issue.id + '|' + reduce(s = '', n IN
                        COLLECT {
                            MATCH (issue)-[:ASSIGNED_TO]->(u:Person)
                            WITH u ORDER BY u.name ASC
                            RETURN u.name
                        }
                        | s + CASE WHEN s = '' THEN '' ELSE ',' END + n) AS result
                """.trimIndent())
                .transform<String>()
        )
        assertEquals(listOf("1003|Charlie,Diana"), results)
    }

    // =========================================================================
    // Probe 4: Performance comparison — APOC vs CALL { }
    //
    // Creates 50 issues, each with 20 assignees (random names).
    // Sorts assignees by name for every issue. Compares wall-clock time
    // and PROFILE dbHits for both approaches.
    // =========================================================================

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `PERF - compare APOC vs CALL subquery on larger dataset`() {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'perf-probe' DETACH DELETE n")
        )

        val names = listOf(
            "Zara", "Yuki", "Xander", "Wendy", "Victor", "Uma", "Trent", "Suki",
            "Raj", "Quinn", "Pablo", "Olivia", "Nora", "Miles", "Luna", "Koji",
            "Jada", "Ivan", "Hugo", "Grace"
        )

        for (i in 1..50) {
            persistenceManager.execute(
                QuerySpecification
                    .withStatement("""
                        CREATE (issue:Issue {
                            uuid: ${'$'}uuid, id: ${'$'}id, title: 'Perf issue ' + ${'$'}id,
                            state: 'open', createdBy: 'perf-probe'
                        })
                    """.trimIndent())
                    .bind(mapOf("uuid" to UUID.randomUUID().toString(), "id" to (2000L + i)))
            )
        }

        for (name in names) {
            persistenceManager.execute(
                QuerySpecification
                    .withStatement("""
                        CREATE (:Person:Mapped {
                            uuid: ${'$'}uuid, name: ${'$'}name, createdBy: 'perf-probe'
                        })
                    """.trimIndent())
                    .bind(mapOf("uuid" to UUID.randomUUID().toString(), "name" to name))
            )
        }

        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    MATCH (issue:Issue {createdBy: 'perf-probe'})
                    MATCH (person:Person {createdBy: 'perf-probe'})
                    CREATE (issue)-[:ASSIGNED_TO]->(person)
                """.trimIndent())
        )

        val apocQuery = """
            MATCH (issue:Issue {createdBy: 'perf-probe'})
            WITH issue,
                reverse(apoc.coll.sortMaps(
                    [(issue)-[:ASSIGNED_TO]->(u:Person) | u { .name }],
                    'name'
                )) AS assignees
            RETURN {id: issue.id, firstName: assignees[0].name}
        """.trimIndent()

        val callQuery = """
            MATCH (issue:Issue {createdBy: 'perf-probe'})
            CALL {
                WITH issue
                MATCH (issue)-[:ASSIGNED_TO]->(u:Person)
                WITH u ORDER BY u.name ASC
                RETURN collect(u.name) AS assignees
            }
            RETURN {id: issue.id, firstName: assignees[0]}
        """.trimIndent()

        // Warmup
        repeat(3) {
            persistenceManager.query(QuerySpecification.withStatement(apocQuery).transform(Map::class.java))
            persistenceManager.query(QuerySpecification.withStatement(callQuery).transform(Map::class.java))
        }

        val iterations = 20

        val apocTimes = mutableListOf<Long>()
        repeat(iterations) {
            val start = System.nanoTime()
            val results = persistenceManager.query(
                QuerySpecification.withStatement(apocQuery).transform(Map::class.java)
            )
            apocTimes.add(System.nanoTime() - start)
            assertEquals(50, results.size)
        }

        val callTimes = mutableListOf<Long>()
        repeat(iterations) {
            val start = System.nanoTime()
            val results = persistenceManager.query(
                QuerySpecification.withStatement(callQuery).transform(Map::class.java)
            )
            callTimes.add(System.nanoTime() - start)
            assertEquals(50, results.size)
        }

        val apocMedianMs = apocTimes.sorted()[iterations / 2] / 1_000_000.0
        val callMedianMs = callTimes.sorted()[iterations / 2] / 1_000_000.0
        val ratio = callMedianMs / apocMedianMs

        println("========================================")
        println("PERF RESULTS: 50 issues x 20 assignees, sorted by name")
        println("========================================")
        println("APOC  median: ${"%.2f".format(apocMedianMs)} ms")
        println("CALL  median: ${"%.2f".format(callMedianMs)} ms")
        println("Ratio (CALL/APOC): ${"%.2f".format(ratio)}x")
        println("APOC  all (ms): ${apocTimes.map { "%.1f".format(it / 1_000_000.0) }}")
        println("CALL  all (ms): ${callTimes.map { "%.1f".format(it / 1_000_000.0) }}")
        println("========================================")
    }
}