package sample

import org.drivine.connection.HolidayingPerson
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.*

@SpringBootTest(classes = [TestAppContext::class])
class HolidayingPersonTests @Autowired constructor(
    private val manager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        manager.execute(QuerySpecification
            .withStatement("MATCH (h:Holiday) WHERE h.createdBy = 'test' DETACH DELETE h"))
        manager.execute(QuerySpecification
            .withStatement("MATCH (p:Person) WHERE p.createdBy = 'test' DETACH DELETE p"))

        // Insert test persons
        val aliceId = UUID.randomUUID().toString()
        val bobId = UUID.randomUUID().toString()

        val personData = listOf(
            mapOf(
                "uuid" to aliceId,
                "firstName" to "Alice",
                "lastName" to "Johnson",
                "email" to "alice.johnson@example.com",
                "age" to 32,
                "city" to "New York",
                "country" to "USA",
                "profession" to "Engineer",
                "isActive" to true,
                "hobbies" to listOf("reading", "hiking"),
                "createdBy" to "test"
            ),
            mapOf(
                "uuid" to bobId,
                "firstName" to "Bob",
                "lastName" to "Smith",
                "email" to "bob.smith@example.com",
                "age" to 28,
                "city" to "Toronto",
                "country" to "Canada",
                "profession" to "Designer",
                "isActive" to true,
                "hobbies" to listOf("photography", "cooking"),
                "createdBy" to "test"
            )
        )

        personData.forEach { person ->
            val query = """
                MERGE (p:Person {uuid: ${'$'}person.uuid})
                SET p.createdTimestamp = datetime().epochMillis,
                p += ${'$'}person
            """.trimIndent()
            manager.execute(
                QuerySpecification
                    .withStatement(query)
                    .bind(mapOf("person" to person)))
        }

        // Insert test holidays
        val independenceDayId = UUID.randomUUID().toString()
        val christmasId = UUID.randomUUID().toString()
        val canadaDayId = UUID.randomUUID().toString()

        val holidayData = listOf(
            mapOf(
                "uuid" to independenceDayId,
                "name" to "Independence Day",
                "date" to "2024-07-04",
                "country" to "USA",
                "type" to "national",
                "description" to "American Independence Day",
                "isPublicHoliday" to true,
                "createdBy" to "test",
                "tags" to listOf("patriotic", "fireworks")
            ),
            mapOf(
                "uuid" to christmasId,
                "name" to "Christmas Day",
                "date" to "2024-12-25",
                "country" to "USA",
                "type" to "religious",
                "description" to "Christian celebration",
                "isPublicHoliday" to true,
                "createdBy" to "test",
                "tags" to listOf("christian", "family")
            ),
            mapOf(
                "uuid" to canadaDayId,
                "name" to "Canada Day",
                "date" to "2024-07-01",
                "country" to "Canada",
                "type" to "national",
                "description" to "Canadian national day",
                "isPublicHoliday" to true,
                "createdBy" to "test",
                "tags" to listOf("patriotic", "maple")
            )
        )

        holidayData.forEach { holiday ->
            val query = """
                MERGE (h:Holiday {uuid: ${'$'}holiday.uuid})
                SET h.createdTimestamp = datetime().epochMillis,
                h += ${'$'}holiday
            """.trimIndent()
            manager.execute(QuerySpecification
                .withStatement(query)
                .bind(mapOf("holiday" to holiday)))
        }

        // Create BOOKED_HOLIDAY relationships
        // Alice booked Independence Day and Christmas
        manager.execute(QuerySpecification
            .withStatement("""
                MATCH (p:Person {uuid: ${'$'}personId}), (h:Holiday {uuid: ${'$'}holidayId})
                CREATE (p)-[:BOOKED_HOLIDAY {createdBy: 'test'}]->(h)
            """.trimIndent())
            .bind(mapOf("personId" to aliceId, "holidayId" to independenceDayId)))

        manager.execute(QuerySpecification
            .withStatement("""
                MATCH (p:Person {uuid: ${'$'}personId}), (h:Holiday {uuid: ${'$'}holidayId})
                CREATE (p)-[:BOOKED_HOLIDAY {createdBy: 'test'}]->(h)
            """.trimIndent())
            .bind(mapOf("personId" to aliceId, "holidayId" to christmasId)))

        // Bob booked Canada Day and Christmas
        manager.execute(QuerySpecification
            .withStatement("""
                MATCH (p:Person {uuid: ${'$'}personId}), (h:Holiday {uuid: ${'$'}holidayId})
                CREATE (p)-[:BOOKED_HOLIDAY {createdBy: 'test'}]->(h)
            """.trimIndent())
            .bind(mapOf("personId" to bobId, "holidayId" to canadaDayId)))

        manager.execute(QuerySpecification
            .withStatement("""
                MATCH (p:Person {uuid: ${'$'}personId}), (h:Holiday {uuid: ${'$'}holidayId})
                CREATE (p)-[:BOOKED_HOLIDAY {createdBy: 'test'}]->(h)
            """.trimIndent())
            .bind(mapOf("personId" to bobId, "holidayId" to christmasId)))
    }

    @Test
    fun testHolidayingPersonComplexQuery() {
        val spec = QuerySpecification
            .withStatement("""
                MATCH (person:Person {firstName: ${'$'}firstName})
                WITH person, [(person)-[:BOOKED_HOLIDAY]->(holiday:Holiday) | holiday {.*}] AS holidays
                RETURN {
                  person:   properties(person),
                  holidays: holidays
                }
            """.trimIndent())
            .bind(mapOf("firstName" to "Alice"))
            .transform(HolidayingPerson::class.java)

        val results = manager.query(spec)

        println("HolidayingPerson results: $results")
        assert(results.isNotEmpty())
        assert(results[0].person.firstName == "Alice")
        assert(results[0].holidays.isNotEmpty())
        assert(results[0].holidays.size == 2) // Alice booked 2 holidays

        val holidayNames = results[0].holidays.map { it.name }
        assert(holidayNames.contains("Independence Day"))
        assert(holidayNames.contains("Christmas Day"))
    }

    @Test
    fun testMultipleHolidayingPersons() {
        val spec = QuerySpecification
            .withStatement("""
                MATCH (person:Person)
                WHERE person.createdBy = 'test'
                WITH person, [(person)-[:BOOKED_HOLIDAY]->(holiday:Holiday) | holiday {.name, .date, .country, .type, .description, .isPublicHoliday, .tags}] as holidays
                RETURN {
                    person: properties(person),
                    holidays: holidays
                }
                ORDER BY person.firstName
            """.trimIndent())
            .transform(HolidayingPerson::class.java)

        val results = manager.query(spec)

        println("All HolidayingPersons: $results")
        assert(results.size == 2)

        // Alice should be first (alphabetical order)
        val alice = results[0]
        assert(alice.person.firstName == "Alice")
        assert(alice.holidays.size == 2)

        // Bob should be second
        val bob = results[1]
        assert(bob.person.firstName == "Bob")
        assert(bob.holidays.size == 2)

        val bobHolidayNames = bob.holidays.map { it.name }
        assert(bobHolidayNames.contains("Canada Day"))
        assert(bobHolidayNames.contains("Christmas Day"))
    }

    @Test
    fun testTransformToMapClass() {
        val spec = QuerySpecification
            .withStatement("""
                MATCH (person:Person {firstName: ${'$'}firstName})
                WITH person, [(person)-[:BOOKED_HOLIDAY]->(holiday:Holiday) | holiday {.*}] AS holidays
                RETURN {
                  person:   properties(person),
                  holidays: holidays
                }
            """.trimIndent())
            .bind(mapOf("firstName" to "Alice"))
            .transform(Map::class.java)

        val results = manager.query(spec)

        println("Map results: $results")
        assert(results.isNotEmpty())

        val firstResult = results[0]

        // Access person data
        val personMap = firstResult["person"] as Map<*, *>
        assert(personMap["firstName"] == "Alice")
        assert(personMap["lastName"] == "Johnson")

        // Access holidays data
        val holidays = firstResult["holidays"] as List<*>
        assert(holidays.size == 2)

        val holidayNames = holidays.map { (it as Map<*, *>)["name"] }
        assert(holidayNames.contains("Independence Day"))
        assert(holidayNames.contains("Christmas Day"))
    }

    @Test
    fun testFloatArrayParameter() {
        // Create a node with embedding vector using pure Cypher
        val embeddingVector = floatArrayOf(0.5f, 0.8f, 0.3f, 0.9f, 0.1f)

        // Create the document node
        manager.execute(QuerySpecification
            .withStatement("""
                CREATE (doc:Document {
                    id: 'doc1',
                    title: 'Test Document',
                    embedding: ${'$'}embedding,
                    createdBy: 'test'
                })
            """.trimIndent())
            .bind(mapOf("embedding" to embeddingVector)))

        // Query back the document with a FloatArray parameter for similarity search
        val queryVector = floatArrayOf(0.6f, 0.7f, 0.4f, 0.8f, 0.2f)

        val spec = QuerySpecification
            .withStatement("""
                MATCH (doc:Document {createdBy: 'test'})
                WHERE doc.embedding IS NOT NULL
                WITH doc,
                     reduce(dot = 0.0, i IN range(0, size(${'$'}queryVector) - 1) |
                        dot + (doc.embedding[i] * ${'$'}queryVector[i])
                     ) AS dotProduct
                RETURN {
                    id: doc.id,
                    title: doc.title,
                    embedding: doc.embedding,
                    dotProduct: dotProduct
                } AS result
                ORDER BY dotProduct DESC
            """.trimIndent())
            .bind(mapOf("queryVector" to queryVector))
            .transform(Map::class.java)

        val results = manager.query(spec)

        println("FloatArray results: $results")
        assert(results.isNotEmpty())

        val firstResult = results[0]
        assert(firstResult["id"] == "doc1")
        assert(firstResult["title"] == "Test Document")

        // Verify the embedding was stored and retrieved correctly
        val retrievedEmbedding = firstResult["embedding"] as List<*>
        assert(retrievedEmbedding.size == 5)
        assert((retrievedEmbedding[0] as Number).toFloat() == 0.5f)
        assert((retrievedEmbedding[1] as Number).toFloat() == 0.8f)

        // Verify dot product calculation worked
        val dotProduct = (firstResult["dotProduct"] as Number).toDouble()
        assert(dotProduct > 0.0)

        // Cleanup
        manager.execute(QuerySpecification
            .withStatement("MATCH (doc:Document {createdBy: 'test'}) DELETE doc"))
    }
}
