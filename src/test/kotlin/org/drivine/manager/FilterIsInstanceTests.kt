package org.drivine.manager

import com.fasterxml.jackson.annotation.JsonSubTypes
import org.drivine.mapper.RowMapper
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.Rollback
import org.springframework.transaction.annotation.Transactional
import sample.simple.TestAppContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestAppContext::class])
@Transactional
@Rollback(true)
class FilterIsInstanceTests @Autowired constructor(
    private val manager: PersistenceManager
) {

    @JsonSubTypes(
        JsonSubTypes.Type(value = Dog::class, name = "Dog"),
        JsonSubTypes.Type(value = Cat::class, name = "Cat"),
        JsonSubTypes.Type(value = Bird::class, name = "Bird")
    )
    sealed class Animal {
        abstract val name: String
    }

    data class Dog(override val name: String, val breed: String, val tags: Set<String> = emptySet()) : Animal()
    data class Cat(override val name: String, val livesRemaining: Int) : Animal()
    data class Bird(override val name: String, val canFly: Boolean) : Animal()

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        manager.execute(
            QuerySpecification.Companion
                .withStatement("MATCH (a) WHERE a.createdBy = 'filterIsInstance-test' DETACH DELETE a")
        )

        // Create test animals in the database with specific labels
        manager.execute(
            QuerySpecification.Companion
                .withStatement(
                    """
                    CREATE (d1:Dog:Animal {name: 'Buddy', breed: 'Golden Retriever', tags: ['friendly', 'playful', 'trained'], createdBy: 'filterIsInstance-test'})
                    CREATE (c1:Cat:Animal {name: 'Whiskers', livesRemaining: 9, createdBy: 'filterIsInstance-test'})
                    CREATE (d2:Dog:Animal {name: 'Max', breed: 'German Shepherd', tags: ['guard', 'loyal'], createdBy: 'filterIsInstance-test'})
                    CREATE (b1:Bird:Animal {name: 'Tweety', canFly: true, createdBy: 'filterIsInstance-test'})
                    CREATE (c2:Cat:Animal {name: 'Mittens', livesRemaining: 7, createdBy: 'filterIsInstance-test'})
                """.trimIndent()
                )
        )
    }

    // RowMapper that converts Neo4j nodes to appropriate Animal subtype based on labels
    class AnimalRowMapper : RowMapper<Animal> {
        override fun map(row: Map<String, Any?>): Animal {
            val name = row["name"] as String

            @Suppress("UNCHECKED_CAST")
            val labels = row["labels"] as? List<String> ?: emptyList()

            return when {
                "Dog" in labels -> {
                    @Suppress("UNCHECKED_CAST")
                    val tagsList = row["tags"] as? List<String> ?: emptyList()
                    Dog(
                        name = name,
                        breed = row["breed"] as String,
                        tags = tagsList.toSet()
                    )
                }

                "Cat" in labels -> Cat(
                    name = name,
                    livesRemaining = (row["livesRemaining"] as Number).toInt()
                )

                "Bird" in labels -> Bird(
                    name = name,
                    canFly = row["canFly"] as Boolean
                )

                else -> throw IllegalArgumentException("Unknown animal type with labels: $labels")
            }
        }
    }

    @Test
    fun `filterIsInstance with reified type parameter filters Dogs from mixed animals`() {
        // Query all animals and map them to Animal instances using labels as discriminator
        val spec = QuerySpecification.Companion
            .withStatement(
                """
                MATCH (a:Animal)
                WHERE a.createdBy = 'filterIsInstance-test'
                WITH properties(a) AS props, labels(a) AS lbls
                RETURN props {.*, labels: lbls} AS a
                ORDER BY a.name
            """.trimIndent()
            )
            .mapWith(AnimalRowMapper())
            .filterIsInstance<Dog>()

        val dogs = manager.query(spec)

        println("Filtered dogs: $dogs")
        assertEquals(2, dogs.size)
        assertTrue(dogs.all { it is Dog })
        assertEquals("Buddy", dogs[0].name)
        assertEquals("Golden Retriever", dogs[0].breed)
        assertEquals(setOf("friendly", "playful", "trained"), dogs[0].tags)
        assertEquals("Max", dogs[1].name)
        assertEquals("German Shepherd", dogs[1].breed)
        assertEquals(setOf("guard", "loyal"), dogs[1].tags)
    }

    @Test
    fun `filterIsInstance with Class parameter (Java compatible) filters Cats`() {
        val spec = QuerySpecification.Companion
            .withStatement(
                """
                MATCH (a:Animal)
                WHERE a.createdBy = 'filterIsInstance-test'
                WITH properties(a) AS props, labels(a) AS lbls
                RETURN props {.*, labels: lbls} AS a
                ORDER BY a.name
            """.trimIndent()
            )
            .mapWith(AnimalRowMapper())
            .filterIsInstance(Cat::class.java)

        val cats = manager.query(spec)

        println("Filtered cats: $cats")
        assertEquals(2, cats.size)
        assertTrue(cats.all { it is Cat })
        assertEquals("Mittens", cats[0].name)
        assertEquals(7, cats[0].livesRemaining)
        assertEquals("Whiskers", cats[1].name)
        assertEquals(9, cats[1].livesRemaining)
    }

    @Test
    fun `filterIsInstance filters Birds correctly`() {
        val spec = QuerySpecification.Companion
            .withStatement(
                """
                MATCH (a:Animal)
                WHERE a.createdBy = 'filterIsInstance-test'
                WITH properties(a) AS props, labels(a) AS lbls
                RETURN props {.*, labels: lbls} AS a
                ORDER BY a.name
            """.trimIndent()
            )
            .mapWith(AnimalRowMapper())
            .filterIsInstance<Bird>()

        val birds = manager.query(spec)

        println("Filtered birds: $birds")
        assertEquals(1, birds.size)
        assertEquals("Tweety", birds[0].name)
        assertTrue(birds[0].canFly)
    }

    @Test
    fun `filterIsInstance with no matches returns empty list`() {
        // Filter for a type that doesn't exist in the database
        // We only have Dogs, Cats, and Birds, so filtering for just Birds and expecting Dogs will return empty
        val spec = QuerySpecification.Companion
            .withStatement(
                """
                MATCH (a:Dog)
                WHERE a.createdBy = 'filterIsInstance-test'
                WITH properties(a) AS props, labels(a) AS lbls
                RETURN props {.*, labels: lbls} AS a
            """.trimIndent()
            )
            .mapWith(AnimalRowMapper())
            .filterIsInstance<Cat>()  // Filter for Cats from Dog results

        val cats = manager.query(spec)

        println("No cats found from dog query: $cats")
        assertTrue(cats.isEmpty())
    }

    @Test
    fun `filterIsInstance can be chained with filter predicates`() {
        val spec = QuerySpecification.Companion
            .withStatement(
                """
                MATCH (a:Animal)
                WHERE a.createdBy = 'filterIsInstance-test'
                WITH properties(a) AS props, labels(a) AS lbls
                RETURN props {.*, labels: lbls} AS a
                ORDER BY a.name
            """.trimIndent()
            )
            .mapWith(AnimalRowMapper())
            .filterIsInstance<Dog>()
            .filter { it.breed.contains("Golden") }

        val goldenDogs = manager.query(spec)

        println("Golden dogs: $goldenDogs")
        assertEquals(1, goldenDogs.size)
        assertEquals("Buddy", goldenDogs[0].name)
        assertEquals("Golden Retriever", goldenDogs[0].breed)
        assertEquals(setOf("friendly", "playful", "trained"), goldenDogs[0].tags)
    }

    @Test
    fun `filterIsInstance following transform`() {
        val spec = QuerySpecification.Companion
            .withStatement(
                """
                MATCH (a:Animal)
                WHERE a.createdBy = 'filterIsInstance-test'
                WITH properties(a) AS props, labels(a) AS lbls
                RETURN props {.*, labels: lbls} AS a
                ORDER BY a.name
            """.trimIndent()
            )
            .transform(Animal::class.java)
            .filterIsInstance<Dog>()
            .filter { it.breed.contains("Golden") }

        val goldenDogs = manager.query(spec)

        println("Golden dogs: $goldenDogs")
        assertEquals(1, goldenDogs.size)
        assertEquals("Buddy", goldenDogs[0].name)
        assertEquals("Golden Retriever", goldenDogs[0].breed)

        // Verify Set<String> property was deserialized correctly
        assertEquals(setOf("friendly", "playful", "trained"), goldenDogs[0].tags)

        // Now let's save it back and verify round-trip serialization
        val buddyWithNewTags = goldenDogs[0].copy(tags = setOf("friendly", "playful", "trained", "golden"))
        manager.execute(
            QuerySpecification
                .withStatement(
                    """
                    MATCH (d:Dog:Animal {name: 'Buddy'})
                    SET d = ${'$'}dog
                    SET d.createdBy = 'filterIsInstance-test'
                """.trimIndent()
                )
                .bindObject("dog", buddyWithNewTags)
        )

        // Query it back and verify the tags were saved and loaded correctly
        val reloadedDogs = manager.query(spec)
        println("Reloaded dogs: $reloadedDogs")
        assertEquals(1, reloadedDogs.size)
        assertEquals(setOf("friendly", "playful", "trained", "golden"), reloadedDogs[0].tags)
    }

    @Test
    fun `filterIsInstance works after map transformation`() {
        // First get all animals, then wrap them, then filter
        data class TaggedAnimal(val animal: Animal, val tag: String)

        val spec = QuerySpecification.Companion
            .withStatement(
                """
                MATCH (a:Animal)
                WHERE a.createdBy = 'filterIsInstance-test'
                WITH properties(a) AS props, labels(a) AS lbls
                RETURN props {.*, labels: lbls} AS a
                ORDER BY a.name
            """.trimIndent()
            )
            .mapWith(AnimalRowMapper())
            .map { animal -> TaggedAnimal(animal, "tagged") }
            .filter { it.animal is Cat }

        val taggedCats = manager.query(spec)

        println("Tagged cats: $taggedCats")
        assertEquals(2, taggedCats.size)
        assertTrue(taggedCats.all { it.animal is Cat })
        assertEquals("tagged", taggedCats[0].tag)
    }

    @Test
    fun `all animals can be retrieved without filtering`() {
        val spec = QuerySpecification.Companion
            .withStatement(
                """
                MATCH (a:Animal)
                WHERE a.createdBy = 'filterIsInstance-test'
                WITH properties(a) AS props, labels(a) AS lbls
                RETURN props {.*, labels: lbls} AS a
                ORDER BY a.name
            """.trimIndent()
            )
            .mapWith(AnimalRowMapper())

        val allAnimals = manager.query(spec)

        println("All animals: $allAnimals")
        assertEquals(5, allAnimals.size)

        // Using Kotlin's standard library filterIsInstance on the result
        val dogs = allAnimals.filterIsInstance<Dog>()
        val cats = allAnimals.filterIsInstance<Cat>()
        val birds = allAnimals.filterIsInstance<Bird>()

        assertEquals(2, dogs.size)
        assertEquals(2, cats.size)
        assertEquals(1, birds.size)
    }

    @Test
    fun `filterIsInstance can be chained multiple times`() {
        // This tests that filtering from Animal -> Dog works correctly
        val spec = QuerySpecification.Companion
            .withStatement(
                """
                MATCH (a:Animal)
                WHERE a.createdBy = 'filterIsInstance-test'
                WITH properties(a) AS props, labels(a) AS lbls
                RETURN props {.*, labels: lbls} AS a
                ORDER BY a.name
            """.trimIndent()
            )
            .mapWith(AnimalRowMapper())
            .filterIsInstance<Animal>()  // First keep all Animals
            .filterIsInstance<Dog>()     // Then narrow to Dogs

        val dogs = manager.query(spec)

        println("Chained filter result: $dogs")
        assertEquals(2, dogs.size)
        assertTrue(dogs.all { it is Dog })
    }
}
