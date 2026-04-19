package org.drivine.query.dsl

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.drivine.connection.NeptuneSigV4AuthProvider
import org.neo4j.driver.AuthTokenManagers
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Config
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Session
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probe tests for Amazon Neptune openCypher compatibility.
 * Only runs when NEPTUNE_BOLT_URL is set.
 *
 * Supports two auth modes:
 * - IAM SigV4 (public endpoint): set AWS credentials in env
 * - No auth (SSH tunnel to VPC): set NEPTUNE_NO_AUTH=true
 */
@EnabledIfEnvironmentVariable(named = "NEPTUNE_BOLT_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NeptuneProbeTest {

    companion object {
        private lateinit var driver: Driver
        private lateinit var session: Session

        @JvmStatic
        @BeforeAll
        fun setup() {
            val url = System.getenv("NEPTUNE_BOLT_URL")
            val useIamAuth = System.getenv("NEPTUNE_IAM_AUTH")?.toBoolean() ?: false
            val region = System.getenv("AWS_REGION") ?: "us-east-1"

            val config = Config.builder()
                .withEncryption()
                .withTrustStrategy(Config.TrustStrategy.trustAllCertificates())
                .build()

            driver = if (useIamAuth) {
                val neptuneHost = url.removePrefix("bolt://").substringBefore(":")
                val port = url.substringAfterLast(":").toIntOrNull() ?: 8182
                val sigV4 = NeptuneSigV4AuthProvider(neptuneHost, port, region)
                // Pass token directly — Neptune expects basic auth with signed URL as password
                GraphDatabase.driver(url, sigV4.authToken(), config)
            } else {
                GraphDatabase.driver(url, AuthTokens.none(), config)
            }
            session = driver.session()

            // Clean
            session.run("MATCH (n) DETACH DELETE n")

            // Seed
            session.run("""
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
            session.run("MATCH (n) DETACH DELETE n")
            session.close()
            driver.close()
        }

        fun queryStrings(cypher: String): List<String> {
            return session.run(cypher).list { it.get(0).asString() }
        }

        fun queryMaps(cypher: String): List<Map<String, Any>> {
            return session.run(cypher).list { record ->
                record.keys().associateWith { key -> record.get(key).asObject() }
            }
        }
    }

    // =========================================================================
    // Probe 1: CALL { } subquery
    // =========================================================================

    @Test
    @Order(1)
    fun `CALL subquery - sort assignees ascending`() {
        val names = queryStrings("""
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
        val names = queryStrings("""
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
    // Probe 2: Nested sort via CALL
    // =========================================================================

    @Test
    @Order(3)
    fun `CALL subquery - sort nested worksFor ascending`() {
        val names = queryStrings("""
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

    // =========================================================================
    // Probe 3: Existence checks — which forms does Neptune support?
    // =========================================================================

    @Test
    @Order(10)
    fun `EXISTS subquery — NOT supported on Neptune`() {
        // Neptune does not support EXISTS { } subquery syntax.
        // The grammar uses CALL subquery prologs instead (inherited from OpenCypherGrammar).
        val ex = assertThrows<Exception> {
            queryStrings("""
                MATCH (issue:Issue)
                WHERE EXISTS { (issue)-[:RAISED_BY]->(:Person) }
                RETURN issue.title ORDER BY issue.id
            """.trimIndent())
        }
        assertTrue(ex.message?.contains("Invalid input") == true)
    }

    @Test
    @Order(11)
    fun `inline pattern predicate`() {
        val results = queryStrings("""
            MATCH (issue:Issue)
            WHERE (issue)-[:RAISED_BY]->(:Person)
            RETURN issue.title ORDER BY issue.id
        """.trimIndent())
        assertEquals(2, results.size)
    }

    @Test
    @Order(12)
    fun `filtered EXISTS subquery — NOT supported on Neptune`() {
        val ex = assertThrows<Exception> {
            queryStrings("""
                MATCH (issue:Issue)
                WHERE EXISTS { (issue)-[:ASSIGNED_TO]->(p:Person) WHERE p.name = 'Charlie' }
                RETURN issue.title ORDER BY issue.id
            """.trimIndent())
        }
        assertTrue(ex.message?.contains("Invalid input") == true)
    }

    // =========================================================================
    // Probe 4: Nested pattern comprehensions (FalkorDB fails this)
    // =========================================================================

    @Test
    @Order(20)
    fun `nested pattern comprehension resolves outer scope variable`() {
        val results = queryMaps("""
            MATCH (i:Issue {id: 1001})
            RETURN [(i)-[:RAISED_BY]->(p:Person) | p {
                .name,
                worksFor: [(p)-[:WORKS_FOR]->(o:Organization) | o { .name }]
            }][0] AS raisedBy
        """.trimIndent())

        assertEquals(1, results.size)
        @Suppress("UNCHECKED_CAST")
        val raisedBy = results[0]["raisedBy"] as Map<String, Any>
        assertEquals("Alice", raisedBy["name"])
        @Suppress("UNCHECKED_CAST")
        val worksFor = raisedBy["worksFor"] as List<Map<String, Any>>
        assertTrue(worksFor.isNotEmpty(), "worksFor should not be empty (FalkorDB returns NULL here — FalkorDB#1888)")
    }

    // =========================================================================
    // Probe 5: Orphan delete pattern (FalkorDB fails this)
    // =========================================================================

    @Test
    @Order(30)
    fun `DELETE visible to subsequent WHERE pattern predicate`() {
        session.run("CREATE (:ProbeA {id: 'orphan'})-[:PROBE_REL]->(:ProbeB {id: 'orphan'})")

        session.run("""
            MATCH (a:ProbeA {id: 'orphan'})-[r:PROBE_REL]->(b:ProbeB)
            DELETE r
            WITH b
            WHERE NOT (b)<-[]-() AND NOT (b)-[]-()
            DELETE b
        """.trimIndent())

        val bExists = queryStrings("MATCH (b:ProbeB {id: 'orphan'}) RETURN b.id")
        assertTrue(bExists.isEmpty(), "ProbeB should be deleted (orphan delete)")

        session.run("MATCH (n) WHERE n.id = 'orphan' DETACH DELETE n")
    }

    // =========================================================================
    // Probe 6: Neptune collSortMaps built-in
    // =========================================================================

    @Test
    @Order(40)
    fun `Neptune collSortMaps built-in function`() {
        // Neptune's collSortMaps takes a MAP config: {key: 'prop', order: 'asc'|'desc'}
        val results = queryMaps("""
            MATCH (issue:Issue {id: 1003})
            WITH issue, [(issue)-[:ASSIGNED_TO]->(u:Person) | {name: u.name}] AS assignees
            RETURN collSortMaps(assignees, {key: 'name', order: 'asc'}) AS sorted
        """.trimIndent())

        assertEquals(1, results.size)
        @Suppress("UNCHECKED_CAST")
        val sorted = results[0]["sorted"] as List<Map<String, Any>>
        assertEquals("Charlie", sorted[0]["name"])
        assertEquals("Diana", sorted[1]["name"])
    }

    // =========================================================================
    // Probe 7: collect() null handling (FalkorDB fails this)
    // =========================================================================

    @Test
    @Order(50)
    fun `collect skips null from OPTIONAL MATCH`() {
        session.run("CREATE (:ProbeX {id: 'collect-null'})")

        val results = queryMaps("""
            MATCH (x:ProbeX {id: 'collect-null'})
            OPTIONAL MATCH (x)-[:NONEXISTENT]->(y:ProbeY)
            WITH x, collect(y { .id }) AS ys
            RETURN {id: x.id, ys: ys} AS result
        """.trimIndent())

        assertEquals(1, results.size)
        @Suppress("UNCHECKED_CAST")
        val ys = (results[0]["result"] as Map<String, Any>)["ys"] as List<*>
        assertTrue(ys.isEmpty() || (ys.size == 1 && ys[0] == null),
            "collect() on null should produce [] or [null], not [{id: NULL}]")

        session.run("MATCH (n:ProbeX {id: 'collect-null'}) DELETE n")
    }
}