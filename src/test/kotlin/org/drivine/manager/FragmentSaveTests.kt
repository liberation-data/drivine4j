package org.drivine.manager

import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.mapped.fragment.Organization
import sample.mapped.fragment.Person
import sample.simple.TestAppContext
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class FragmentSaveTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'save-test' DETACH DELETE n")
        )
    }

    @Test
    fun `should create new fragment`() {
        val uuid = UUID.randomUUID()
        val person = Person(
            uuid = uuid,
            name = "Kent Beck",
            bio = "Creator of TDD and XP"
        )

        // Save the new person
        graphObjectManager.save(person)

        // Verify it was saved
        val loaded = graphObjectManager.load(uuid.toString(), Person::class.java)
        assertNotNull(loaded)
        assertEquals("Kent Beck", loaded.name)
        assertEquals("Creator of TDD and XP", loaded.bio)
    }

    @Test
    fun `should update existing fragment with dirty fields only`() {
        // Create a person in the database
        val uuid = UUID.randomUUID()
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:Person:Mapped {
                        uuid: ${'$'}uuid,
                        name: 'Erich Gamma',
                        bio: 'Gang of Four author',
                        createdBy: 'save-test'
                    })
                """.trimIndent())
                .bind(mapOf("uuid" to uuid.toString()))
        )

        // Load the person (adds to session)
        val person = graphObjectManager.load(uuid.toString(), Person::class.java)
        assertNotNull(person)

        // Modify only the name
        val modifiedPerson = person.copy(name = "Erich Gamma (Updated)")

        // Save (should only update name field, not bio)
        graphObjectManager.save(modifiedPerson)

        // Verify the update
        val loaded = graphObjectManager.load(uuid.toString(), Person::class.java)
        assertNotNull(loaded)
        assertEquals("Erich Gamma (Updated)", loaded.name)
        assertEquals("Gang of Four author", loaded.bio) // Bio unchanged
    }

    @Test
    fun `should update fragment not in session with all fields`() {
        // Create a person in the database
        val uuid = UUID.randomUUID()
        persistenceManager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (p:Person:Mapped {
                        uuid: ${'$'}uuid,
                        name: 'Robert Martin',
                        bio: 'Uncle Bob',
                        createdBy: 'save-test'
                    })
                """.trimIndent())
                .bind(mapOf("uuid" to uuid.toString()))
        )

        // Create a modified person WITHOUT loading (not in session)
        val person = Person(
            uuid = uuid,
            name = "Robert C. Martin",
            bio = "Clean Code author"
        )

        // Save (should update all fields since not in session)
        graphObjectManager.save(person)

        // Verify the update
        val loaded = graphObjectManager.load(uuid.toString(), Person::class.java)
        assertNotNull(loaded)
        assertEquals("Robert C. Martin", loaded.name)
        assertEquals("Clean Code author", loaded.bio)
    }

    @Test
    fun `should handle null bio field`() {
        val uuid = UUID.randomUUID()
        val org = Organization(
            uuid = uuid,
            name = "Anthropic"
        )

        // Save the organization
        graphObjectManager.save(org)

        // Verify it was saved
        val loaded = graphObjectManager.load(uuid.toString(), Organization::class.java)
        assertNotNull(loaded)
        assertEquals("Anthropic", loaded.name)
    }

    @Test
    fun `should be idempotent - multiple saves of same object`() {
        val uuid = UUID.randomUUID()
        val person = Person(
            uuid = uuid,
            name = "Ward Cunningham",
            bio = "Inventor of the Wiki"
        )

        // Save multiple times
        graphObjectManager.save(person)
        graphObjectManager.save(person)
        graphObjectManager.save(person)

        // Verify only one node exists
        val count = persistenceManager.query(
            QuerySpecification
                .withStatement("MATCH (p:Person {uuid: \$uuid}) RETURN count(p)")
                .bind(mapOf("uuid" to uuid.toString()))
                .transform<Int>()  // Now we can use reified types!
        )

        assertEquals(1, count.first())
    }
}