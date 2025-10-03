package sample

import drivine.connection.Person
import drivine.query.QuerySpecification
import drivine.manager.PersistenceManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

@SpringBootTest(classes = [TestAppContext::class])
class PersonRepositoryTests @Autowired constructor(
    private val manager: PersistenceManager,
    private val personRepository: PersonRepository
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        manager.execute(QuerySpecification
            .withStatement("MATCH (p:Person) WHERE p.createdBy = 'test' DELETE p"))

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
                QuerySpecification
                    .withStatement<Unit>(query)
                    .bind(mapOf("person" to person)))
        }
    }

    @Test
    fun testPersonQueryWithMapping() {
        val spec = QuerySpecification
            .withStatement<Any>("""
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
    fun testPersonRepositoryMethods() {
        val nyPersons = personRepository.findPersonsByCity("New York")
        println("New York persons: ${nyPersons.map { "${it.firstName} ${it.lastName}" }}")

        val engineerNames = personRepository.findPersonsByProfession("Engineer")
        println("Engineer names: $engineerNames")

        val youngPeople = personRepository.findYoungPeople()
        println("Young people: ${youngPeople.map { "${it.firstName} (${it.age})" }}")
    }

    @Test
    fun testComplexChaining() {
        val spec = QuerySpecification
            .withStatement<Any>("""
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
