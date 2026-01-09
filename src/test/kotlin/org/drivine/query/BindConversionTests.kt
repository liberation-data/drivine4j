package org.drivine.query

import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.simple.TestAppContext
import java.time.Instant
import java.time.ZonedDateTime
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for automatic type conversion in .bind() method.
 *
 * Prior to this fix, .bind() passed values directly to Neo4j, which failed for types like:
 * - java.time.Instant (Neo4j driver doesn't support it directly)
 * - java.util.UUID (Neo4j prefers String)
 * - Enum values
 *
 * The fix applies Neo4j-compatible conversions to all values in .bind().
 */
@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class BindConversionTests @Autowired constructor(
    private val manager: PersistenceManager
) {

    @BeforeEach
    fun setup() {
        manager.execute(
            QuerySpecification.withStatement(
                "MATCH (n) WHERE n.testMarker = 'bind-conversion-test' DETACH DELETE n"
            )
        )
    }

    @Test
    fun `bind should convert Instant to ZonedDateTime`() {
        val testInstant = Instant.parse("2024-01-15T10:30:00Z")
        val uuid = UUID.randomUUID().toString()

        // This used to fail with: Unable to convert java.time.Instant to Neo4j Value
        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (n:TestNode {
                        uuid: ${'$'}uuid,
                        createdAt: ${'$'}createdAt,
                        testMarker: 'bind-conversion-test'
                    })
                """.trimIndent())
                .bind(mapOf(
                    "uuid" to uuid,
                    "createdAt" to testInstant
                ))
        )

        // Verify it was saved correctly - single column results are unwrapped
        val savedValue = manager.getOne(
            QuerySpecification
                .withStatement("MATCH (n:TestNode {uuid: \$uuid}) RETURN n.createdAt")
                .bind(mapOf("uuid" to uuid))
        )

        assertNotNull(savedValue, "createdAt should be saved")
        assertTrue(savedValue is ZonedDateTime, "Instant should be stored as ZonedDateTime, got ${savedValue::class}")
    }

    @Test
    fun `bind should convert UUID to String`() {
        val testUuid = UUID.randomUUID()

        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (n:TestNode {
                        id: ${'$'}id,
                        testMarker: 'bind-conversion-test'
                    })
                """.trimIndent())
                .bind(mapOf("id" to testUuid))
        )

        // Verify it was saved as a string - query by the converted string value
        val savedValue = manager.getOne(
            QuerySpecification
                .withStatement("MATCH (n:TestNode {id: \$id}) RETURN n.id")
                .bind(mapOf("id" to testUuid.toString()))
        )

        assertEquals(testUuid.toString(), savedValue, "UUID should be stored as String")
    }

    enum class TestStatus { ACTIVE, INACTIVE }

    @Test
    fun `bind should convert Enum to String`() {
        val uuid = UUID.randomUUID().toString()

        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (n:TestNode {
                        uuid: ${'$'}uuid,
                        status: ${'$'}status,
                        testMarker: 'bind-conversion-test'
                    })
                """.trimIndent())
                .bind(mapOf(
                    "uuid" to uuid,
                    "status" to TestStatus.ACTIVE
                ))
        )

        val savedValue = manager.getOne(
            QuerySpecification
                .withStatement("MATCH (n:TestNode {uuid: \$uuid}) RETURN n.status")
                .bind(mapOf("uuid" to uuid))
        )

        assertEquals("ACTIVE", savedValue, "Enum should be stored as String")
    }

    @Test
    fun `bind should convert java util Date to ZonedDateTime`() {
        val uuid = UUID.randomUUID().toString()
        val testDate = Date()

        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (n:TestNode {
                        uuid: ${'$'}uuid,
                        createdAt: ${'$'}createdAt,
                        testMarker: 'bind-conversion-test'
                    })
                """.trimIndent())
                .bind(mapOf(
                    "uuid" to uuid,
                    "createdAt" to testDate
                ))
        )

        val savedValue = manager.getOne(
            QuerySpecification
                .withStatement("MATCH (n:TestNode {uuid: \$uuid}) RETURN n.createdAt")
                .bind(mapOf("uuid" to uuid))
        )

        assertNotNull(savedValue, "createdAt should be saved")
        assertTrue(savedValue is ZonedDateTime, "Date should be stored as ZonedDateTime, got ${savedValue::class}")
    }

    @Test
    fun `bind should handle nested maps with special types`() {
        val uuid = UUID.randomUUID().toString()
        val testInstant = Instant.now()

        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (n:TestNode ${'$'}props)
                    SET n.testMarker = 'bind-conversion-test'
                """.trimIndent())
                .bind(mapOf(
                    "props" to mapOf(
                        "uuid" to uuid,
                        "createdAt" to testInstant,
                        "status" to TestStatus.INACTIVE
                    )
                ))
        )

        // Single column results are unwrapped, so result is the properties map directly
        val props = manager.getOne(
            QuerySpecification
                .withStatement("MATCH (n:TestNode {uuid: \$uuid}) RETURN properties(n)")
                .bind(mapOf("uuid" to uuid))
        )

        assertNotNull(props, "Node should be found")
        @Suppress("UNCHECKED_CAST")
        val propsMap = props as Map<String, Any?>
        assertEquals(uuid, propsMap["uuid"])
        assertEquals("INACTIVE", propsMap["status"])
        assertTrue(propsMap["createdAt"] is ZonedDateTime)
    }

    @Test
    fun `bind should preserve primitives and strings unchanged`() {
        val uuid = UUID.randomUUID().toString()

        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (n:TestNode {
                        uuid: ${'$'}uuid,
                        name: ${'$'}name,
                        count: ${'$'}count,
                        active: ${'$'}active,
                        score: ${'$'}score,
                        testMarker: 'bind-conversion-test'
                    })
                """.trimIndent())
                .bind(mapOf(
                    "uuid" to uuid,
                    "name" to "Test Name",
                    "count" to 42,
                    "active" to true,
                    "score" to 3.14
                ))
        )

        // Single column results are unwrapped, so result is the properties map directly
        val props = manager.getOne(
            QuerySpecification
                .withStatement("MATCH (n:TestNode {uuid: \$uuid}) RETURN properties(n)")
                .bind(mapOf("uuid" to uuid))
        )

        assertNotNull(props, "Node should be found")
        @Suppress("UNCHECKED_CAST")
        val propsMap = props as Map<String, Any?>
        assertEquals("Test Name", propsMap["name"])
        assertEquals(42L, propsMap["count"])  // Neo4j returns Long
        assertEquals(true, propsMap["active"])
        assertEquals(3.14, propsMap["score"])
    }

    @Test
    fun `bind should handle lists with special types`() {
        val uuid = UUID.randomUUID().toString()
        val timestamps = listOf(
            Instant.parse("2024-01-01T00:00:00Z"),
            Instant.parse("2024-06-15T12:00:00Z")
        )

        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (n:TestNode {
                        uuid: ${'$'}uuid,
                        timestamps: ${'$'}timestamps,
                        testMarker: 'bind-conversion-test'
                    })
                """.trimIndent())
                .bind(mapOf(
                    "uuid" to uuid,
                    "timestamps" to timestamps
                ))
        )

        // Single column results are unwrapped, so result is the list directly
        val savedTimestamps = manager.getOne(
            QuerySpecification
                .withStatement("MATCH (n:TestNode {uuid: \$uuid}) RETURN n.timestamps")
                .bind(mapOf("uuid" to uuid))
        )

        assertNotNull(savedTimestamps, "timestamps should be saved")
        @Suppress("UNCHECKED_CAST")
        val timestampsList = savedTimestamps as List<*>
        assertEquals(2, timestampsList.size)
        assertTrue(timestampsList.all { it is ZonedDateTime })
    }
}