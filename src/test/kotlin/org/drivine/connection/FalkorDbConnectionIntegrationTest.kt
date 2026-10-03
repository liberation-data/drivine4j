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
    // Parameters jfalkordb 0.7.0 could not carry: maps, and strings with `$` or a backslash
    // =========================================================================

    private fun names(conn: Connection): List<Any?> = conn.query(
        QuerySpecification
            .withStatement("MATCH (p:Person) RETURN {id: p.id, name: p.name} ORDER BY p.id")
            .transform(Map::class.java)
    ).map { (it as Map<*, *>)["name"] }

    @Test
    @Order(3)
    fun `a list of maps is taken as a parameter`() {
        val conn = provider.connect()
        try {
            val rows = listOf(
                mapOf("id" to 1, "props" to mapOf("name" to "Ada", "tags" to listOf("a", "b"))),
                mapOf("id" to 2, "props" to mapOf("name" to "It's \"Bob\"", "nickname" to null)),
            )
            conn.query<Any>(
                QuerySpecification
                    .withStatement("UNWIND \$rows AS row MERGE (p:Person {id: row.id}) SET p += row.props")
                    .bind(mapOf("rows" to rows))
            )

            assertEquals(listOf<Any?>("Ada", "It's \"Bob\""), names(conn))
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(4)
    fun `a value that names another parameter is not taken for a reference to it`() {
        val conn = provider.connect()
        try {
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Person {id: 1, name: \$label}), (:Person {id: 2, name: \$name})")
                    .bind(mapOf("label" to "costs \$name", "name" to "a\\b"))
            )

            assertEquals(listOf<Any?>("costs \$name", "a\\b"), names(conn))
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(5)
    fun `a map key that is not a plain name is still one key`() {
        val conn = provider.connect()
        try {
            val results = conn.query(
                QuerySpecification
                    .withStatement("WITH \$bag AS bag RETURN {value: bag[\$key]}")
                    .bind(mapOf("bag" to mapOf("metadata.source" to "upload"), "key" to "metadata.source"))
                    .transform(Map::class.java)
            )

            assertEquals("upload", (results.single() as Map<*, *>)["value"])
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(6)
    fun `a long string holding a template expression is one value`() {
        val conn = provider.connect()
        try {
            // FalkorDB/JFalkorDB#251 showed only past a certain length, so this is a chunk-sized value.
            val text = (1..400).joinToString("\n") { "val line$it = \"\${row.name} costs \$$it\"" }
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Person {id: 1, name: \$text})")
                    .bind(mapOf("text" to text))
            )

            assertTrue(text.length > 10_000)
            assertEquals(listOf<Any?>(text), names(conn))
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(7)
    fun `a string holding an escaped quote is one value`() {
        val conn = provider.connect()
        try {
            val json = """{\"rows\": 5, "path": "C:\\tmp\\"}"""
            conn.query<Any>(
                QuerySpecification
                    .withStatement("CREATE (:Person {id: 1, name: \$json})")
                    .bind(mapOf("json" to json))
            )

            assertEquals(listOf<Any?>(json), names(conn))
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(8)
    fun `a null parameter is sent as null`() {
        val conn = provider.connect()
        try {
            val results = conn.query(
                QuerySpecification
                    .withStatement("RETURN {absent: \$absent IS NULL, present: \$present}")
                    .bind(mapOf("absent" to null, "present" to "here"))
                    .transform(Map::class.java)
            )

            assertEquals(mapOf<String, Any?>("absent" to true, "present" to "here"), results.single())
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(9)
    fun `a null column comes back as null`() {
        val conn = provider.connect()
        try {
            conn.query<Any>(QuerySpecification.withStatement("CREATE (:Person {id: 1})"))

            assertEquals(listOf<Any?>(null), names(conn))
        } finally {
            conn.release()
        }
    }

    @Test
    @Order(10)
    fun `a set is sent as a list`() {
        val conn = provider.connect()
        try {
            val results = conn.query(
                QuerySpecification
                    .withStatement("RETURN {matched: 2 IN \$ids, names: \$names}")
                    .bind(mapOf("ids" to setOf(1, 2), "names" to linkedSetOf("b", "a")))
                    .transform(Map::class.java)
            )

            assertEquals(mapOf<String, Any?>("matched" to true, "names" to listOf("b", "a")), results.single())
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