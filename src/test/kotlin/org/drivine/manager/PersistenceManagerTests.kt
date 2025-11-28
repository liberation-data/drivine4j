package org.drivine.manager

import org.drivine.connection.Holiday
import org.drivine.connection.Person
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import sample.simple.HolidayRepository
import sample.simple.PersonRepository
import sample.simple.TestAppContext
import java.util.UUID

@SpringBootTest(classes = [TestAppContext::class])
class PersistenceManagerTests @Autowired constructor(
    private val manager: PersistenceManager,
    private val holidayRepository: HolidayRepository,
    private val personRepository: PersonRepository
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        manager.execute(
            QuerySpecification.Companion
            .withStatement("MATCH (h:Holiday) WHERE h.createdBy = 'test' detach DELETE h"))
        manager.execute(
            QuerySpecification.Companion
            .withStatement("MATCH (p:Person) WHERE p.createdBy = 'test' detach DELETE p"))

        // Insert test holidays
        val holidayData = listOf(
            mapOf(
                "uuid" to UUID.randomUUID().toString(),
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
                "uuid" to UUID.randomUUID().toString(),
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
                "uuid" to UUID.randomUUID().toString(),
                "name" to "Canada Day",
                "date" to "2024-07-01",
                "country" to "Canada",
                "type" to "national",
                "description" to "Canadian national day",
                "isPublicHoliday" to true,
                "createdBy" to "test",
                "tags" to listOf("patriotic", "maple")
            ),
            mapOf(
                "uuid" to UUID.randomUUID().toString(),
                "name" to "Diwali",
                "date" to "2024-11-01",
                "country" to "India",
                "type" to "religious",
                "description" to "Festival of lights",
                "isPublicHoliday" to false,
                "createdBy" to "test",
                "tags" to listOf("hindu", "lights")
            )
        )

        holidayData.forEach { holiday ->
            val query = """
                MERGE (h:Holiday {uuid: ${'$'}holiday.uuid})
                SET h.createdTimestamp = datetime().epochMillis,
                h += ${'$'}holiday
            """.trimIndent()
            manager.execute(
                QuerySpecification.Companion
                .withStatement(query)
                .bind(mapOf("holiday" to holiday)))
        }

        // Insert test persons
        val personData = listOf(
            mapOf(
                "uuid" to UUID.randomUUID().toString(),
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
                "uuid" to UUID.randomUUID().toString(),
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
            ),
            mapOf(
                "uuid" to UUID.randomUUID().toString(),
                "firstName" to "Carol",
                "lastName" to "Davis",
                "email" to "carol.davis@example.com",
                "age" to 45,
                "city" to "New York",
                "country" to "USA",
                "profession" to "Engineer",
                "isActive" to true,
                "hobbies" to listOf("cycling", "music"),
                "createdBy" to "test"
            ),
            mapOf(
                "uuid" to UUID.randomUUID().toString(),
                "firstName" to "David",
                "lastName" to "Wilson",
                "email" to "david.wilson@example.com",
                "age" to 22,
                "city" to "Boston",
                "country" to "USA",
                "profession" to "Student",
                "isActive" to true,
                "hobbies" to listOf("gaming", "sports"),
                "createdBy" to "test"
            ),
            mapOf(
                "uuid" to UUID.randomUUID().toString(),
                "firstName" to "Emma",
                "lastName" to "Brown",
                "email" to "emma.brown@example.com",
                "age" to 29,
                "city" to "Vancouver",
                "country" to "Canada",
                "profession" to "Designer",
                "isActive" to false,
                "hobbies" to listOf("painting", "travel"),
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
                QuerySpecification.Companion
                    .withStatement(query)
                    .bind(mapOf("person" to person)))
        }
    }

    @Test
    fun testQuerySpecificationWithHolidays() {
        val spec = QuerySpecification.Companion
            .withStatement("""
                MATCH (h:Holiday) WHERE h.createdBy = 'test' RETURN properties(h)
                """.trimIndent())
            .limit(10)
            .transform(Holiday::class.java)
            .filter { it.isPublicHoliday }

        val results = manager.query(spec)
        println("Public holidays: ${results.map { it.name }}")
        assert(results.all { it.isPublicHoliday })
    }

    @Test
    fun testQuerySpecificationWithPersons() {
        val spec = QuerySpecification.Companion
            .withStatement("""
                MATCH (p:Person) WHERE p.createdBy = 'test' RETURN properties(p)
                """.trimIndent())
            .transform(Person::class.java)
            .filter { it.age != null && it.age > 25 }
            .map { "${it.firstName} ${it.lastName} (${it.age})" }

        val results = manager.query(spec)
        println("Adults: $results")
        assert(results.isNotEmpty())
    }

    @Test
    fun testComplexQueryChaining() {
        val spec = QuerySpecification.Companion
            .withStatement("""
                MATCH (p:Person) WHERE p.createdBy = 'test' AND p.profession = 'Engineer'
                RETURN properties(p)
                """.trimIndent())
            .transform(Person::class.java)
            .filter { it.isActive && it.city == "New York" }
            .map { it.email ?: "no-email" }
            .filter { it.contains("@") }

        val results = manager.query(spec)
        println("NY Engineer emails: $results")
        assert(results.all { it.contains("@") })
    }
}
