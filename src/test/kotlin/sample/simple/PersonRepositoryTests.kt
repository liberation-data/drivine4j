package sample.simple

import org.drivine.connection.Person
import org.drivine.query.QuerySpecification
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.toMap
import org.drivine.utils.partial
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class PersonRepositoryTests @Autowired constructor(
    private val manager: PersistenceManager,
    @Autowired private val personRepository: PersonRepository,
    repository: PersonRepository
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        manager.execute(QuerySpecification
            .withStatement("MATCH (p:Person) WHERE p.createdBy = 'test' DETACH DELETE p"))

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
                    .withStatement(query)
                    .bind(mapOf("person" to person)))
        }
    }

    @Test
    fun testPersonQueryWithMapping() {
        val spec = QuerySpecification
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

    @Test
    fun testUpdate() {
        // Get an existing person
        val person = personRepository.findPersonsByCity("New York").first()
        println("Before update: ${person.firstName} ${person.lastName}, age: ${person.age}")

        // Update the person
        val updated = personRepository.update(person.copy(age = 35, city = "Los Angeles"))
        println("After update: ${updated.firstName} ${updated.lastName}, age: ${updated.age}, city: ${updated.city}")

        // Verify the update
        assert(updated.uuid == person.uuid)
        assert(updated.age == 35)
        assert(updated.city == "Los Angeles")
    }

    @Test
    fun `update using partial should be supported`() {
        // Get an existing person
        val person = personRepository.findPersonsByCity("New York").first()
        println("Before update: ${person.firstName} ${person.lastName}, age: ${person.age}")


        val patch = partial<Person> {
            set(Person::profession, "Stock Broker")
            set(Person::email, "jane@wallstreet.com")
        }

        val updated = personRepository.update(person.uuid, patch)

        println("After update: ${updated.firstName} ${updated.lastName}, age: ${updated.age}, city: ${updated.city}")

        // Verify the update
        assert(updated.uuid == person.uuid)
        assert(updated.profession == "Stock Broker")
        assert(updated.email == "jane@wallstreet.com")
        assert(person.age == updated.age)
    }

    @Test
    fun `update with null value and includeNulls=false should preserve existing value`() {
        // Get an existing person with an email
        val person = personRepository.findPersonsByCity("New York").first()
        val originalEmail = person.email
        println("Before update: ${person.firstName} ${person.lastName}, email: ${person.email}")
        assert(originalEmail != null) { "Test requires person with non-null email" }

        // Update using the standard update method which uses includeNulls=false
        // This should preserve the existing email since null values are excluded from the map
        val updated = personRepository.update(person.copy(email = null, age = 99))
        println("After update: ${updated.firstName} ${updated.lastName}, email: ${updated.email}, age: ${updated.age}")

        // Verify: age should be updated, but email should remain unchanged (preserved)
        assert(updated.uuid == person.uuid)
        assert(updated.age == 99) { "Age should be updated to 99" }
        assert(updated.email == originalEmail) {
            "Email should be preserved when updating with null and includeNulls=false. " +
            "Expected: $originalEmail, Got: ${updated.email}"
        }
    }

    @Test
    fun `update with null value and includeNulls=true should remove property from node`() {
        // Get an existing person with an email
        val person = personRepository.findPersonsByCity("Toronto").first()
        println("Before update: ${person.firstName} ${person.lastName}, email: ${person.email}")
        assert(person.email != null) { "Test requires person with non-null email" }

        // Create a custom update method that includes nulls
        // Using SET p = $props (not +=) to replace all properties
        // Neo4jObjectMapper includes nulls by default (JsonInclude.Include.ALWAYS)
        val propsWithNulls = Neo4jObjectMapper.instance.toMap(
            person.copy(email = null, age = 88)
        )

        val statement = """
            MERGE (p:Person {uuid: ${'$'}props.uuid})
            SET p = ${'$'}props
            RETURN properties(p)
        """

        val updated = manager.getOne(
            QuerySpecification
                .withStatement(statement)
                .bind(mapOf("props" to propsWithNulls))
                .transform(Person::class.java)
        )

        println("After update: ${updated.firstName} ${updated.lastName}, email: ${updated.email}, age: ${updated.age}")

        // Verify: both age and email should be updated, email should now be null
        assert(updated.uuid == person.uuid)
        assert(updated.age == 88) { "Age should be updated to 88" }
        assert(updated.email == null) {
            "Email should be null when updating with includeNulls=true and SET p = \$props. " +
            "Got: ${updated.email}"
        }
    }
}
