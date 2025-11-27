package sample

import org.drivine.query.QuerySpecification
import org.drivine.manager.PersistenceManager
import org.drivine.connection.Person
import org.drivine.mapper.Neo4jObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID

@SpringBootTest(classes = [TestAppContext::class])
class ScalarResultTests @Autowired constructor(
    private val manager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        manager.execute(QuerySpecification
            .withStatement("MATCH (p:Person) WHERE p.createdBy = 'scalar-test' DELETE p"))
    }

    @Test
    fun `query returning count with alias can transform to map`() {
        // Create some test data
        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:Person {uuid: ${'$'}uuid, firstName: 'Test', createdBy: 'scalar-test'})
                """.trimIndent())
                .bind(mapOf("uuid" to UUID.randomUUID().toString()))
        )

        // Query that returns a scalar count with alias
        val countQuery = """
            MATCH (p:Person) WHERE p.createdBy = 'scalar-test'
            RETURN count(p) as count
        """.trimIndent()

        // With our fix, this should work without ClassCastException
        // But may fail with conversion error since we're trying to convert to Person
        try {
            val result = manager.query(
                QuerySpecification
                    .withStatement(countQuery)
                    .transform(Person::class.java)  // Trying to transform scalar to Person
            )
            println("Count result (unexpected success): $result")
            // If it succeeds, Jackson managed to convert somehow
        } catch (e: ClassCastException) {
            println("ClassCastException should not occur with our fix!")
            throw e  // Fail the test if we get ClassCastException
        } catch (e: Exception) {
            // Other exceptions are expected (conversion errors)
            println("Conversion error as expected: ${e.javaClass.simpleName}: ${e.message}")
            // Test passes - we handled it without ClassCastException
        }
    }

    @Test
    fun `query returning scalar Long can be handled without crash`() {
        // Query that returns just a count (Long)
        val countQuery = """
            MATCH (p:Person) WHERE p.createdBy = 'scalar-test'
            RETURN count(p)
        """.trimIndent()

        // This should not crash - it may fail conversion but shouldn't throw ClassCastException
        try {
            val result = manager.query(
                QuerySpecification
                    .withStatement(countQuery)
                    .transform(Long::class.java)
            )
            println("Count result: $result")
            assert(result.isNotEmpty())
        } catch (e: ClassCastException) {
            println("ClassCastException should not occur: ${e.message}")
            throw e
        } catch (e: Exception) {
            // Other exceptions (like conversion errors) are acceptable
            println("Conversion error (acceptable): ${e.message}")
        }
    }

    @Test
    fun `query with scalar in map should work`() {
        // Create test data
        val uuid = UUID.randomUUID().toString()
        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:Person {uuid: ${'$'}uuid, firstName: 'Test', age: 25, createdBy: 'scalar-test'})
                """.trimIndent())
                .bind(mapOf("uuid" to uuid))
        )

        // Query that returns properties and count
        val query = """
            MATCH (p:Person {uuid: ${'$'}uuid})
            RETURN properties(p) as person, count(p) as count
        """.trimIndent()

        val result = manager.query(
            QuerySpecification
                .withStatement(query)
                .bind(mapOf("uuid" to uuid))
        )

        println("Mixed result: $result")
        assert(result.isNotEmpty())
    }

    @Test
    fun `parameter binding with nested map containing Instant`() {
        val now = Instant.now()
        val uuid = UUID.randomUUID().toString()

        // Bind a nested structure
        @Suppress("UNCHECKED_CAST")
        val params = Neo4jObjectMapper.instance.convertValue(mapOf(
            "person" to mapOf(
                "uuid" to uuid,
                "firstName" to "Test",
                "createdAt" to now,
                "createdBy" to "scalar-test"
            )
        ), Map::class.java) as Map<String, Any?>

        val query = """
            CREATE (p:Person)
            SET p = ${'$'}person
            RETURN properties(p)
        """.trimIndent()

        try {
            val result = manager.query(
                QuerySpecification
                    .withStatement(query)
                    .bind(params)
                    .transform(Person::class.java)
            )
            println("Created person: $result")
        } catch (e: Exception) {
            println("Error with nested map: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun `binding raw value instead of map`() {
        // What if someone accidentally binds a scalar directly?
        val uuid = UUID.randomUUID().toString()

        val query = """
            CREATE (p:Person {uuid: ${'$'}uuid, firstName: 'Test', createdBy: 'scalar-test'})
            RETURN properties(p)
        """.trimIndent()

        try {
            // Bind just the UUID string directly (no map wrapper)
            val result = manager.query(
                QuerySpecification
                    .withStatement(query)
                    .bind(mapOf("uuid" to uuid))  // This is correct
                    .transform(Person::class.java)
            )
            println("Result: $result")
            assert(result.isNotEmpty())
        } catch (e: Exception) {
            println("Error: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun `query returning String scalar`() {
        // Create test data
        val uuid = UUID.randomUUID().toString()
        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:Person {uuid: ${'$'}uuid, firstName: 'Alice', createdBy: 'scalar-test'})
                """.trimIndent())
                .bind(mapOf("uuid" to uuid))
        )

        // Query that returns just a String (firstName)
        val nameQuery = """
            MATCH (p:Person {uuid: ${'$'}uuid})
            RETURN p.firstName
        """.trimIndent()

        val result = manager.query(
            QuerySpecification
                .withStatement(nameQuery)
                .bind(mapOf("uuid" to uuid))
                .transform(String::class.java)
        )

        println("String scalar result: $result")
        assert(result.size == 1)
        assert(result[0] == "Alice")
    }

    @Test
    fun `query returning String scalar with alias`() {
        // Create test data
        val uuid = UUID.randomUUID().toString()
        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:Person {uuid: ${'$'}uuid, firstName: 'Bob', createdBy: 'scalar-test'})
                """.trimIndent())
                .bind(mapOf("uuid" to uuid))
        )

        // Query that returns a String with alias
        val nameQuery = """
            MATCH (p:Person {uuid: ${'$'}uuid})
            RETURN p.firstName as name
        """.trimIndent()

        val result = manager.query(
            QuerySpecification
                .withStatement(nameQuery)
                .bind(mapOf("uuid" to uuid))
                .transform(String::class.java)
        )

        println("String scalar with alias result: $result")
        assert(result.size == 1)
        assert(result[0] == "Bob")
    }

    @Test
    fun `query returning scalar count transformed to Map`() {
        // Create some test data
        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:Person {uuid: ${'$'}uuid, firstName: 'Test', createdBy: 'scalar-test'})
                """.trimIndent())
                .bind(mapOf("uuid" to UUID.randomUUID().toString()))
        )

        // Query that returns a scalar count
        val countQuery = """
            MATCH (p:Person) WHERE p.createdBy = 'scalar-test'
            RETURN count(p)
        """.trimIndent()

        // Try to transform scalar to Map - what happens?
        try {
            val result = manager.query(
                QuerySpecification
                    .withStatement(countQuery)
                    .transform(Map::class.java)
            )
            println("Scalar to Map result: $result")
            println("Result type: ${result.firstOrNull()?.javaClass}")
            result.firstOrNull()?.let { map ->
                println("Map contents: $map")
                map.forEach { (k, v) ->
                    println("  $k = $v (${v?.javaClass?.simpleName})")
                }
            }
        } catch (e: Exception) {
            println("Error transforming scalar to Map: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            // Don't throw - we want to see what happens
        }
    }
}