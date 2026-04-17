package org.drivine.query.dsl

import org.junit.jupiter.api.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import redis.clients.jedis.Jedis
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probe test: verify CALL { } subqueries work on FalkorDB.
 * Uses raw Jedis + GRAPH.QUERY — no Drivine driver needed.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FalkorDbCallSubqueryProbeTest {

    companion object {
        private const val GRAPH_NAME = "probe"

        @Container
        @JvmField
        val falkorDb: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var jedis: Jedis

        @JvmStatic
        @BeforeAll
        fun setup() {
            jedis = Jedis(falkorDb.host, falkorDb.getMappedPort(6379))

            // Seed data — same fixture as the Neo4j probe
            graphQuery("""
                CREATE (issue1:Issue {id: 1001, title: 'Test issue 1', state: 'open'})
                CREATE (issue3:Issue {id: 1003, title: 'Test issue 3', state: 'open'})
                CREATE (alice:Person {name: 'Alice'})
                CREATE (charlie:Person {name: 'Charlie'})
                CREATE (diana:Person {name: 'Diana'})
                CREATE (acme:Organization {name: 'Acme Corp'})
                CREATE (beta:Organization {name: 'Beta Inc'})
                CREATE (issue1)-[:RAISED_BY]->(alice)
                CREATE (issue1)-[:ASSIGNED_TO]->(charlie)
                CREATE (issue3)-[:RAISED_BY]->(alice)
                CREATE (issue3)-[:ASSIGNED_TO]->(charlie)
                CREATE (issue3)-[:ASSIGNED_TO]->(diana)
                CREATE (alice)-[:WORKS_FOR]->(acme)
                CREATE (alice)-[:WORKS_FOR]->(beta)
            """.trimIndent())
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            jedis.close()
        }

        fun graphQuery(cypher: String): List<Any> {
            @Suppress("UNCHECKED_CAST")
            return jedis.sendCommand(
                { "GRAPH.QUERY".toByteArray() },
                GRAPH_NAME.toByteArray(),
                cypher.toByteArray()
            ) as? List<Any> ?: emptyList()
        }

        fun graphQueryResults(cypher: String): List<List<Any>> {
            val response = graphQuery(cypher)
            if (response.size < 2) return emptyList()
            @Suppress("UNCHECKED_CAST")
            val rows = response[1] as? List<*> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            return rows.map { row -> (row as List<Any>) }
        }

        fun graphQueryStrings(cypher: String): List<String> {
            return graphQueryResults(cypher).map { row ->
                String((row[0] as? ByteArray) ?: row[0].toString().toByteArray())
            }
        }
    }

    // =========================================================================
    // Probe 1: Flat collection sort
    // =========================================================================

    @Test
    @Order(1)
    fun `CALL subquery - sort assignees ascending`() {
        val names = graphQueryStrings("""
            MATCH (issue:Issue {id: 1003})
            CALL {
                WITH issue
                MATCH (issue)-[:ASSIGNED_TO]->(u:Person)
                WITH u ORDER BY u.name ASC
                RETURN collect(u.name) AS sortedNames
            }
            UNWIND sortedNames AS name
            RETURN name
        """.trimIndent())

        assertEquals(listOf("Charlie", "Diana"), names)
    }

    @Test
    @Order(2)
    fun `CALL subquery - sort assignees descending`() {
        val names = graphQueryStrings("""
            MATCH (issue:Issue {id: 1003})
            CALL {
                WITH issue
                MATCH (issue)-[:ASSIGNED_TO]->(u:Person)
                WITH u ORDER BY u.name DESC
                RETURN collect(u.name) AS sortedNames
            }
            UNWIND sortedNames AS name
            RETURN name
        """.trimIndent())

        assertEquals(listOf("Diana", "Charlie"), names)
    }

    // =========================================================================
    // Probe 2: Nested two-hop sort
    // =========================================================================

    @Test
    @Order(3)
    fun `CALL subquery - sort nested worksFor ascending`() {
        val names = graphQueryStrings("""
            MATCH (issue:Issue {id: 1001})-[:RAISED_BY]->(raiser:Person)
            CALL {
                WITH raiser
                MATCH (raiser)-[:WORKS_FOR]->(org:Organization)
                WITH org ORDER BY org.name ASC
                RETURN collect(org.name) AS sortedOrgs
            }
            UNWIND sortedOrgs AS name
            RETURN name
        """.trimIndent())

        assertEquals(listOf("Acme Corp", "Beta Inc"), names)
    }

    @Test
    @Order(4)
    fun `CALL subquery - sort nested worksFor descending`() {
        val names = graphQueryStrings("""
            MATCH (issue:Issue {id: 1001})-[:RAISED_BY]->(raiser:Person)
            CALL {
                WITH raiser
                MATCH (raiser)-[:WORKS_FOR]->(org:Organization)
                WITH org ORDER BY org.name DESC
                RETURN collect(org.name) AS sortedOrgs
            }
            UNWIND sortedOrgs AS name
            RETURN name
        """.trimIndent())

        assertEquals(listOf("Beta Inc", "Acme Corp"), names)
    }

    // =========================================================================
    // Probe 3: Combined root ORDER BY + collection sort
    // =========================================================================

    @Test
    @Order(5)
    fun `CALL subquery - combined root order and collection sort`() {
        val results = graphQueryStrings("""
            MATCH (issue:Issue)
            WHERE issue.state = 'open'
            CALL {
                WITH issue
                MATCH (issue)-[:ASSIGNED_TO]->(u:Person)
                WITH u ORDER BY u.name ASC
                RETURN collect(u.name) AS assignees
            }
            WITH issue, assignees
            ORDER BY issue.id DESC
            RETURN issue.id + ':' + reduce(s = '', n IN assignees | s + CASE WHEN s = '' THEN '' ELSE ',' END + n)
        """.trimIndent())

        assertEquals(2, results.size)
        assertEquals("1003:Charlie,Diana", results[0])
        assertEquals("1001:Charlie", results[1])
    }

    // =========================================================================
    // Probe 4: List comprehension support
    // =========================================================================

    @Test
    @Order(6)
    fun `list comprehension works`() {
        val results = graphQueryStrings("""
            MATCH (issue:Issue {id: 1003})
            RETURN reduce(s = '', n IN [(issue)-[:ASSIGNED_TO]->(u:Person) | u.name] |
                s + CASE WHEN s = '' THEN '' ELSE ',' END + n)
        """.trimIndent())

        assertEquals(1, results.size)
        assertTrue(results[0].contains("Charlie"))
        assertTrue(results[0].contains("Diana"))
    }

    // =========================================================================
    // Probe 5: Pattern comprehension support
    // =========================================================================

    @Test
    @Order(7)
    fun `pattern comprehension works`() {
        val results = graphQueryStrings("""
            MATCH (issue:Issue {id: 1003})
            RETURN [(issue)-[:ASSIGNED_TO]->(u:Person) WHERE u.name STARTS WITH 'C' | u.name] AS filtered
        """.trimIndent())

        assertTrue(results.isNotEmpty())
    }

    // =========================================================================
    // Probe 6: Performance comparison (same shape as Neo4j probe)
    // =========================================================================

    @Test
    @Order(8)
    fun `PERF - CALL subquery on larger dataset`() {
        // Seed perf data
        val names = listOf(
            "Zara", "Yuki", "Xander", "Wendy", "Victor", "Uma", "Trent", "Suki",
            "Raj", "Quinn", "Pablo", "Olivia", "Nora", "Miles", "Luna", "Koji",
            "Jada", "Ivan", "Hugo", "Grace"
        )

        for (i in 1..50) {
            graphQuery("CREATE (:PerfIssue {id: ${2000 + i}})")
        }
        for (name in names) {
            graphQuery("CREATE (:PerfPerson {name: '$name'})")
        }
        graphQuery("""
            MATCH (issue:PerfIssue), (person:PerfPerson)
            CREATE (issue)-[:PERF_ASSIGNED]->(person)
        """.trimIndent())

        val callQuery = """
            MATCH (issue:PerfIssue)
            CALL {
                WITH issue
                MATCH (issue)-[:PERF_ASSIGNED]->(u:PerfPerson)
                WITH u ORDER BY u.name ASC
                RETURN collect(u.name) AS assignees
            }
            RETURN {id: issue.id, firstName: assignees[0]}
        """.trimIndent()

        // Warmup
        repeat(3) { graphQuery(callQuery) }

        val iterations = 20
        val times = mutableListOf<Long>()
        repeat(iterations) {
            val start = System.nanoTime()
            val results = graphQueryResults(callQuery)
            times.add(System.nanoTime() - start)
            assertEquals(50, results.size)
        }

        val medianMs = times.sorted()[iterations / 2] / 1_000_000.0

        println("========================================")
        println("FALKORDB PERF: 50 issues x 20 assignees, sorted by name")
        println("========================================")
        println("CALL  median: ${"%.2f".format(medianMs)} ms")
        println("CALL  all (ms): ${times.map { "%.1f".format(it / 1_000_000.0) }}")
        println("========================================")
    }
}