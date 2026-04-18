package org.drivine.connection

import org.drivine.mapper.FalkorDbResultMapper
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FalkorDbConnectionIntegrationTest {

    companion object {
        private const val GRAPH = "test"

        @Container
        @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider

        @JvmStatic
        @BeforeAll
        fun setup() {
            provider = FalkorDbConnectionProvider(
                name = "falkor-test",
                host = container.host,
                port = container.getMappedPort(6379),
                password = null,
                graphName = GRAPH,
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
            conn.query<Any>(
                QuerySpecification.withStatement("MATCH (n) DETACH DELETE n")
            )
        } finally {
            conn.release()
        }
    }

    // =========================================================================
    // Basic CRUD — no transaction
    // =========================================================================

    @Test
    @Order(1)
    fun `non-transactional create and read`() {
        val conn = provider.connect()
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Person {name: \$name})")
                    .bind(mapOf("name" to "Alice"))
            )

            val names = conn.query(
                QuerySpecification
                    .withStatement("MATCH (p:Person) RETURN p.name")
                    .transform<String>()
            )
            assertEquals(listOf("Alice"), names)
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(2)
    fun `non-transactional map return`() {
        val conn = provider.connect()
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Person {name: 'Bob', age: 30})")
            )

            val results = conn.query(
                QuerySpecification
                    .withStatement("MATCH (p:Person) RETURN {name: p.name, age: p.age}")
                    .transform(Map::class.java)
            )
            assertEquals(1, results.size)
            @Suppress("UNCHECKED_CAST")
            val row = results[0] as Map<String, Any?>
            assertEquals("Bob", row["name"])
            assertEquals(30L, row["age"])
        } finally {
            conn.release()
        }
    }

    // =========================================================================
    // Transaction passthrough (WARN mode — default)
    // =========================================================================

    @Test
    @Order(10)
    fun `WARN mode - transaction methods are no-ops, writes execute immediately`() {
        val conn = provider.connect()
        try {
            conn.startTransaction()

            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Person {name: 'Alice'})")
            )

            // Write is immediately visible — not buffered
            val names = conn.query(
                QuerySpecification
                    .withStatement("MATCH (p:Person) RETURN p.name")
                    .transform<String>()
            )
            assertEquals(listOf("Alice"), names)

            conn.commitTransaction()
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(11)
    fun `WARN mode - rollback is a no-op, writes already committed`() {
        val conn = provider.connect()
        try {
            conn.startTransaction()

            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Person {name: 'Alice'})")
            )

            conn.rollbackTransaction()

            // Data is still there — rollback was a no-op
            val names = conn.query(
                QuerySpecification
                    .withStatement("MATCH (p:Person) RETURN p.name")
                    .transform<String>()
            )
            assertEquals(listOf("Alice"), names)
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(12)
    fun `WARN mode - multiple writes within transaction all execute`() {
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

            val names = conn.query(
                QuerySpecification
                    .withStatement("MATCH (p:Person) RETURN p.name ORDER BY p.name")
                    .transform<String>()
            )
            assertEquals(listOf("Alice", "Bob"), names)

            conn.commitTransaction()
        } finally {
            conn.release()
        }
    }

    // =========================================================================
    // Transaction STRICT mode
    // =========================================================================

    @Test
    @Order(20)
    fun `STRICT mode - startTransaction throws`() {
        val strictProvider = FalkorDbConnectionProvider(
            name = "falkor-strict",
            host = container.host,
            port = container.getMappedPort(6379),
            password = null,
            graphName = GRAPH,
            transactionMode = FalkorDbTransactionMode.STRICT,
        )

        val conn = strictProvider.connect()
        try {
            assertFailsWith<UnsupportedOperationException> {
                conn.startTransaction()
            }
        } finally {
            conn.release()
            strictProvider.end()
        }
    }
}