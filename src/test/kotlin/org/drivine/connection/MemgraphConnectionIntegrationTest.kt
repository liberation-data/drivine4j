package org.drivine.connection

import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.transform
import org.junit.jupiter.api.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Integration tests for Memgraph via Drivine.
 *
 * Memgraph speaks Bolt natively and is Neo4j-compatible enough that we reuse [Neo4jConnection]
 * — this suite exercises that reuse and confirms the Memgraph-specific
 * [CypherDialect.MEMGRAPH] grammar.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MemgraphConnectionIntegrationTest {

    companion object {
        @Container
        @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687)
            .waitingFor(Wait.forLogMessage(".*You are running Memgraph.*", 1))

        private lateinit var provider: Neo4jConnectionProvider

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = Neo4jConnectionProvider(
                name = "memgraph-test",
                type = DatabaseType.MEMGRAPH,
                host = container.host,
                port = container.getMappedPort(7687),
                user = "",
                password = "",
                database = null,
                config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH,
            )
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            provider.end()
        }
    }

    @BeforeEach
    fun cleanGraph() {
        val conn = provider.connect()
        try {
            conn.query<Any>(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(1)
    fun `create and read a node with bind parameters`() {
        val conn = provider.connect()
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Person {name: \$name, age: \$age})")
                    .bind(mapOf("name" to "Ada", "age" to 36))
            )

            val names = conn.query(
                QuerySpecification
                    .withStatement("MATCH (p:Person) RETURN p.name")
                    .transform<String>()
            )
            assertEquals(listOf("Ada"), names)
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(2)
    fun `Instant bind parameter round-trips through Memgraph natively`() {
        // Unlike FalkorDB, Memgraph's Bolt driver handles temporal types on the wire, so
        // TemporalCoercer is NOT attached for this connection. The Instant should flow
        // through unmodified and come back as a ZonedDateTime (Neo4j driver's native type).
        val conn = provider.connect()
        val ts = Instant.parse("2026-04-24T05:00:00Z")
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Event {id: \$id, occurredAt: \$ts})")
                    .bind(mapOf("id" to "e1", "ts" to ts))
            )

            val results = conn.query(
                QuerySpecification
                    .withStatement("MATCH (e:Event) RETURN {id: e.id, occurredAt: e.occurredAt}")
                    .transform(Map::class.java)
            )
            assertEquals(1, results.size)
            @Suppress("UNCHECKED_CAST")
            val row = results[0] as Map<String, Any?>
            assertEquals("e1", row["id"])
            assertNotNull(row["occurredAt"], "Memgraph should preserve the temporal value")
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(10)
    fun `explicit transaction commits multiple writes atomically`() {
        val conn = provider.connect()
        try {
            conn.startTransaction()
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Person {name: \$name})")
                    .bind(mapOf("name" to "Alice"))
            )
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Person {name: \$name})")
                    .bind(mapOf("name" to "Bob"))
            )
            conn.commitTransaction()

            val names = conn.query(
                QuerySpecification
                    .withStatement("MATCH (p:Person) RETURN p.name ORDER BY p.name")
                    .transform<String>()
            )
            assertEquals(listOf("Alice", "Bob"), names)
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(11)
    fun `rollback discards writes - Memgraph has real transactions unlike FalkorDB`() {
        val conn = provider.connect()
        try {
            conn.startTransaction()
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Person {name: 'Carol'})")
            )
            conn.rollbackTransaction()

            val names = conn.query(
                QuerySpecification
                    .withStatement("MATCH (p:Person) RETURN p.name")
                    .transform<String>()
            )
            assertEquals(emptyList(), names, "rolled-back write must not be visible")
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(20)
    fun `Memgraph grammar emits inline pattern predicate for existence checks`() {
        // Sanity check: grammar built from CypherDialect.MEMGRAPH emits an inline predicate
        // (like OpenCypherGrammar) rather than EXISTS { ... }, because Memgraph rejects
        // unbounded variables inside EXISTS and disallows EXISTS within WITH.
        val grammar = CypherDialect.MEMGRAPH.grammar()
        val check = grammar.existenceCheck(rootAlias = "n", direction = "-[:REL]->", targetLabels = "Other")
        assertEquals("(n)-[:REL]->(:Other)", check)
    }
}