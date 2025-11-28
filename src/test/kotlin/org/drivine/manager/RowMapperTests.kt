package org.drivine.manager

import org.drivine.connection.Person
import org.drivine.mapper.RowMapper
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import sample.simple.TestAppContext
import java.util.UUID

@SpringBootTest(classes = [TestAppContext::class])
class RowMapperTests @Autowired constructor(
    private val manager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        manager.execute(
            QuerySpecification.Companion
            .withStatement("MATCH (p:Person) WHERE p.createdBy = 'rowmapper-test' DELETE p"))
    }

    @Test
    fun `mapWith using RowMapper class instance`() {
        // Create test data
        val uuid = UUID.randomUUID().toString()
        manager.execute(
            QuerySpecification.Companion
                .withStatement("""
                    CREATE (p:Person {
                        uuid: ${'$'}uuid,
                        firstName: 'Alice',
                        lastName: 'Smith',
                        createdBy: 'rowmapper-test'
                    })
                """.trimIndent())
                .bind(mapOf("uuid" to uuid))
        )

        // Query using RowMapper
        class PersonRowMapper : RowMapper<Person> {
            override fun map(row: Map<String, Any?>): Person {
                return Person(
                    uuid = UUID.fromString(row["uuid"] as String),
                    firstName = row["firstName"] as String?,
                    lastName = row["lastName"] as String?,
                    email = null,
                    age = null,
                    city = null,
                    country = null,
                    profession = null,
                    createdTimestamp = null
                )
            }
        }

        val people = manager.query(
            QuerySpecification.Companion
                .withStatement("MATCH (p:Person {uuid: ${'$'}uuid}) RETURN p")
                .bind(mapOf("uuid" to uuid))
                .mapWith(PersonRowMapper())
        )

        println("Result using RowMapper: $people")
        assert(people.size == 1)
        assert(people[0].firstName == "Alice")
        assert(people[0].lastName == "Smith")
        assert(people[0].uuid.toString() == uuid)
    }

    @Test
    fun `mapWith with inline RowMapper`() {
        // Create test data
        val uuid = UUID.randomUUID().toString()
        manager.execute(
            QuerySpecification.Companion
                .withStatement("""
                    CREATE (p:Person {
                        uuid: ${'$'}uuid,
                        firstName: 'Bob',
                        lastName: 'Jones',
                        createdBy: 'rowmapper-test'
                    })
                """.trimIndent())
                .bind(mapOf("uuid" to uuid))
        )

        // Inline RowMapper using object expression
        val people = manager.query(
            QuerySpecification.Companion
                .withStatement("MATCH (p:Person {uuid: ${'$'}uuid}) RETURN p")
                .bind(mapOf("uuid" to uuid))
                .mapWith(object : RowMapper<Person> {
                    override fun map(row: Map<String, Any?>): Person {
                        return Person(
                            uuid = UUID.fromString(row["uuid"] as String),
                            firstName = row["firstName"] as String?,
                            lastName = row["lastName"] as String?,
                            email = null,
                            age = null,
                            city = null,
                            country = null,
                            profession = null,
                            createdTimestamp = null
                        )
                    }
                })
        )

        println("Result using inline RowMapper: $people")
        assert(people.size == 1)
        assert(people[0].firstName == "Bob")
        assert(people[0].lastName == "Jones")
        assert(people[0].uuid.toString() == uuid)
    }

    @Test
    fun `mapWith to custom type not using transform`() {
        // Create test data
        val uuid = UUID.randomUUID().toString()
        manager.execute(
            QuerySpecification.Companion
                .withStatement("""
                    CREATE (p:Person {
                        uuid: ${'$'}uuid,
                        firstName: 'Charlie',
                        lastName: 'Brown',
                        createdBy: 'rowmapper-test'
                    })
                """.trimIndent())
                .bind(mapOf("uuid" to uuid))
        )

        // Map to a custom DTO
        data class PersonSummary(val fullName: String, val id: String)

        val summaries = manager.query(
            QuerySpecification.Companion
                .withStatement("MATCH (p:Person {uuid: ${'$'}uuid}) RETURN p")
                .bind(mapOf("uuid" to uuid))
                .mapWith(object : RowMapper<PersonSummary> {
                    override fun map(row: Map<String, Any?>): PersonSummary {
                        val firstName = row["firstName"] as String
                        val lastName = row["lastName"] as String
                        return PersonSummary(
                            fullName = "$firstName $lastName",
                            id = row["uuid"] as String
                        )
                    }
                })
        )

        println("Result as custom DTO: $summaries")
        assert(summaries.size == 1)
        assert(summaries[0].fullName == "Charlie Brown")
        assert(summaries[0].id == uuid)
    }

    @Test
    fun `mapWith handling properties() result`() {
        // Create test data
        val uuid = UUID.randomUUID().toString()
        manager.execute(
            QuerySpecification.Companion
                .withStatement("""
                    CREATE (p:Person {
                        uuid: ${'$'}uuid,
                        firstName: 'Diana',
                        lastName: 'Prince',
                        createdBy: 'rowmapper-test'
                    })
                """.trimIndent())
                .bind(mapOf("uuid" to uuid))
        )

        // Query that returns properties(p) explicitly
        val people = manager.query(
            QuerySpecification.Companion
                .withStatement("MATCH (p:Person {uuid: ${'$'}uuid}) RETURN properties(p) as person")
                .bind(mapOf("uuid" to uuid))
                .mapWith(object : RowMapper<Person> {
                    override fun map(row: Map<String, Any?>): Person {
                        // The row will have a "person" key containing the properties map
                        @Suppress("UNCHECKED_CAST")
                        val personProps = row["person"] as? Map<String, Any?> ?: row
                        return Person(
                            uuid = UUID.fromString(personProps["uuid"] as String),
                            firstName = personProps["firstName"] as String?,
                            lastName = personProps["lastName"] as String?,
                            email = null,
                            age = null,
                            city = null,
                            country = null,
                            profession = null,
                            createdTimestamp = null
                        )
                    }
                })
        )

        println("Result from properties(): $people")
        assert(people.size == 1)
        assert(people[0].firstName == "Diana")
        assert(people[0].lastName == "Prince")
    }
}
