package sample

import org.drivine.connection.Event
import org.drivine.query.QuerySpecification
import org.drivine.manager.PersistenceManager
import org.drivine.utils.ObjectUtils
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.util.UUID

@SpringBootTest(classes = [TestAppContext::class])
class EventRepositoryTests @Autowired constructor(
    private val manager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        manager.execute(QuerySpecification
            .withStatement("MATCH (e:Event) WHERE e.createdBy = 'test' DELETE e"))
    }

    @Test
    fun `save entity with Instant fields`() {
        val now = Instant.now()
        val event = Event(
            uuid = UUID.randomUUID(),
            name = "Test Event",
            description = "Testing Instant serialization",
            occurredAt = now,
            createdAt = now,
            updatedAt = null
        )

        // Convert event to map for Neo4j using primitiveProps
        val eventProps = ObjectUtils.primitiveProps(mapOf(
            "uuid" to event.uuid.toString(),
            "name" to event.name,
            "description" to event.description,
            "occurredAt" to event.occurredAt,
            "createdAt" to event.createdAt,
            "updatedAt" to event.updatedAt,
            "createdBy" to "test"
        ))

        val query = """
            CREATE (e:Event)
            SET e = ${'$'}event
            RETURN properties(e)
        """.trimIndent()

        val result = manager.getOne(
            QuerySpecification
                .withStatement(query)
                .bind(mapOf("event" to eventProps))
                .transform(Event::class.java)
        )

        println("Saved event: $result")
        assert(result.name == "Test Event")
    }

    @Test
    fun `load entity with Instant fields`() {
        // First, create an event directly with epoch millis (as Neo4j would store it)
        val now = Instant.now()
        val eventId = UUID.randomUUID().toString()

        val createQuery = """
            CREATE (e:Event {
                uuid: ${'$'}uuid,
                name: ${'$'}name,
                description: ${'$'}description,
                occurredAt: ${'$'}occurredAt,
                createdAt: ${'$'}createdAt,
                createdBy: 'test'
            })
        """.trimIndent()

        manager.execute(
            QuerySpecification
                .withStatement(createQuery)
                .bind(mapOf(
                    "uuid" to eventId,
                    "name" to "Load Test Event",
                    "description" to "Testing Instant deserialization",
                    "occurredAt" to now.toEpochMilli(),
                    "createdAt" to now.toEpochMilli()
                ))
        )

        // Now try to load it back
        val loadQuery = """
            MATCH (e:Event {uuid: ${'$'}uuid})
            RETURN properties(e)
        """.trimIndent()

        val result = manager.getOne(
            QuerySpecification
                .withStatement(loadQuery)
                .bind(mapOf("uuid" to eventId))
                .transform(Event::class.java)
        )

        println("Loaded event: $result")
        assert(result.name == "Load Test Event")
        assert(result.occurredAt != null)
    }

    @Test
    fun `query with Instant parameter in WHERE clause`() {
        val now = Instant.now()
        val eventId = UUID.randomUUID().toString()

        // Create event
        val createQuery = """
            CREATE (e:Event {
                uuid: ${'$'}uuid,
                name: 'Query Test Event',
                occurredAt: ${'$'}occurredAt,
                createdAt: ${'$'}createdAt,
                createdBy: 'test'
            })
        """.trimIndent()

        manager.execute(
            QuerySpecification
                .withStatement(createQuery)
                .bind(ObjectUtils.primitiveProps(mapOf(
                    "uuid" to eventId,
                    "occurredAt" to now,
                    "createdAt" to now
                )) as Map<String, Any>)
        )

        // Query with Instant in WHERE clause - use primitiveProps to convert
        val queryWithInstant = """
            MATCH (e:Event)
            WHERE e.occurredAt = ${'$'}timestamp AND e.createdBy = 'test'
            RETURN properties(e)
        """.trimIndent()

        val result = manager.getOne(
            QuerySpecification
                .withStatement(queryWithInstant)
                .bind(ObjectUtils.primitiveProps(mapOf("timestamp" to now)) as Map<String, Any>)
                .transform(Event::class.java)
        )

        println("Queried event: $result")
        assert(result.name == "Query Test Event")
    }
}