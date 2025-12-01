package org.drivine.manager

import org.drivine.query.QuerySpecification
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class FragmentLoadingTests @Autowired constructor(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (n) WHERE n.createdBy = 'fragment-test' DETACH DELETE n")
        )

        // Create test data: Person and Organization nodes
        val personUuid = UUID.randomUUID()
        val orgUuid = UUID.randomUUID()

        val query = """
            CREATE (person:Person:Mapped {
                uuid: ${'$'}personUuid,
                name: 'Martin Fowler',
                bio: 'Author of Refactoring',
                createdBy: 'fragment-test'
            })
            CREATE (org:Organization {
                uuid: ${'$'}orgUuid,
                name: 'ThoughtWorks',
                createdBy: 'fragment-test'
            })
        """.trimIndent()

        persistenceManager.execute(
            QuerySpecification
                .withStatement(query)
                .bind(
                    mapOf(
                        "personUuid" to personUuid.toString(),
                        "orgUuid" to orgUuid.toString()
                    )
                )
        )
    }

    @Test
    fun `should load all Person fragments`() {
        val results = graphObjectManager.loadAll(Person::class.java)

        println("Loaded ${results.size} Person fragments")
        results.forEach { person ->
            println("Person: ${person.name} - ${person.bio}")
        }

        assertTrue(results.isNotEmpty())
        val martin = results.find { it.name == "Martin Fowler" }
        assertNotNull(martin)
        assertEquals("Martin Fowler", martin.name)
        assertEquals("Author of Refactoring", martin.bio)
    }

    @Test
    fun `should load Person fragment by id`() {
        // First get all to find the UUID
        val all = graphObjectManager.loadAll(Person::class.java)
        assertTrue(all.isNotEmpty())
        val expectedPerson = all.first()

        // Now load by ID
        val loaded = graphObjectManager.load(expectedPerson.uuid.toString(), Person::class.java)

        assertNotNull(loaded)
        assertEquals(expectedPerson.uuid, loaded.uuid)
        assertEquals(expectedPerson.name, loaded.name)
        assertEquals(expectedPerson.bio, loaded.bio)

        println("Successfully loaded person by ID: ${loaded.name}")
    }

    @Test
    fun `should return null when loading non-existent fragment id`() {
        val nonExistentId = UUID.randomUUID().toString()
        val result = graphObjectManager.load(nonExistentId, Person::class.java)

        assertNull(result)
        println("Correctly returned null for non-existent fragment ID")
    }

    @Test
    fun `should load all Organization fragments`() {
        val results = graphObjectManager.loadAll(Organization::class.java)

        println("Loaded ${results.size} Organization fragments")
        results.forEach { org ->
            println("Organization: ${org.name}")
        }

        assertTrue(results.isNotEmpty())
        val thoughtWorks = results.find { it.name == "ThoughtWorks" }
        assertNotNull(thoughtWorks)
        assertEquals("ThoughtWorks", thoughtWorks.name)
    }

    @Test
    fun `should load Organization fragment by id`() {
        // First get all to find the UUID
        val all = graphObjectManager.loadAll(Organization::class.java)
        assertTrue(all.isNotEmpty())
        val expectedOrg = all.first()

        // Now load by ID
        val loaded = graphObjectManager.load(expectedOrg.uuid.toString(), Organization::class.java)

        assertNotNull(loaded)
        assertEquals(expectedOrg.uuid, loaded.uuid)
        assertEquals(expectedOrg.name, loaded.name)

        println("Successfully loaded organization by ID: ${loaded.name}")
    }
}