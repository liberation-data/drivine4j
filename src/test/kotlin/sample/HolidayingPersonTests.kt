package sample

import drivine.connection.Holiday
import drivine.connection.HolidayingPerson
import drivine.query.QuerySpecification
import drivine.manager.PersistenceManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

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
                    .withStatement<Unit>(query)
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
                .withStatement<Unit>(query)
                .bind(mapOf("holiday" to holiday)))
        }

        // Create BOOKED_HOLIDAY relationships
        // Alice booked Independence Day and Christmas
        manager.execute(QuerySpecification
            .withStatement<Unit>("""
                MATCH (p:Person {uuid: ${'$'}personId}), (h:Holiday {uuid: ${'$'}holidayId})
                CREATE (p)-[:BOOKED_HOLIDAY {createdBy: 'test'}]->(h)
            """.trimIndent())
            .bind(mapOf("personId" to aliceId, "holidayId" to independenceDayId)))

        manager.execute(QuerySpecification
            .withStatement<Unit>("""
                MATCH (p:Person {uuid: ${'$'}personId}), (h:Holiday {uuid: ${'$'}holidayId})
                CREATE (p)-[:BOOKED_HOLIDAY {createdBy: 'test'}]->(h)
            """.trimIndent())
            .bind(mapOf("personId" to aliceId, "holidayId" to christmasId)))

        // Bob booked Canada Day and Christmas
        manager.execute(QuerySpecification
            .withStatement<Unit>("""
                MATCH (p:Person {uuid: ${'$'}personId}), (h:Holiday {uuid: ${'$'}holidayId})
                CREATE (p)-[:BOOKED_HOLIDAY {createdBy: 'test'}]->(h)
            """.trimIndent())
            .bind(mapOf("personId" to bobId, "holidayId" to canadaDayId)))

        manager.execute(QuerySpecification
            .withStatement<Unit>("""
                MATCH (p:Person {uuid: ${'$'}personId}), (h:Holiday {uuid: ${'$'}holidayId})
                CREATE (p)-[:BOOKED_HOLIDAY {createdBy: 'test'}]->(h)
            """.trimIndent())
            .bind(mapOf("personId" to bobId, "holidayId" to christmasId)))
    }

    @Test
    fun testHolidayingPersonComplexQuery() {
        val spec = QuerySpecification
            .withStatement<Any>("""
                MATCH (person:Person {firstName: ${'$'}firstName})
                WITH person, [(person)-[:BOOKED_HOLIDAY]->(holiday:Holiday) | holiday {.name, .date, .country, .type, .description, .isPublicHoliday, .tags}] as holidays
                RETURN {
                    person: properties(person),
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
            .withStatement<Any>("""
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
}
