package sample

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestAppContext::class])
class JacksonPolymorphicTests @Autowired constructor(
    private val manager: PersistenceManager
) {

    @BeforeEach
    fun setupTestData() {
        // Clear existing test data
        manager.execute(QuerySpecification
            .withStatement("MATCH (a) WHERE a.createdBy = 'jackson-polymorphic-test' DETACH DELETE a"))

        // Create test animals in the database
        manager.execute(
            QuerySpecification
                .withStatement("""
                    CREATE (d1:Dog:Animal {name: 'Buddy', breed: 'Golden Retriever', type: 'Dog', createdBy: 'jackson-polymorphic-test'})
                    CREATE (c1:Cat:Animal {name: 'Whiskers', livesRemaining: 9, type: 'Cat', createdBy: 'jackson-polymorphic-test'})
                    CREATE (d2:Dog:Animal {name: 'Max', breed: 'German Shepherd', type: 'Dog', createdBy: 'jackson-polymorphic-test'})
                    CREATE (b1:Bird:Animal {name: 'Tweety', canFly: true, type: 'Bird', createdBy: 'jackson-polymorphic-test'})
                    CREATE (c2:Cat:Animal {name: 'Mittens', livesRemaining: 7, type: 'Cat', createdBy: 'jackson-polymorphic-test'})
                """.trimIndent())
        )
    }

    // ========================================
    // APPROACH 1: Using Jackson Annotations
    // ========================================

    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
    )
    @JsonSubTypes(
        JsonSubTypes.Type(value = AnnotatedDog::class, name = "Dog"),
        JsonSubTypes.Type(value = AnnotatedCat::class, name = "Cat"),
        JsonSubTypes.Type(value = AnnotatedBird::class, name = "Bird")
    )
    sealed class AnnotatedAnimal {
        abstract val name: String
    }

    data class AnnotatedDog(
        override val name: String,
        val breed: String
    ) : AnnotatedAnimal()

    data class AnnotatedCat(
        override val name: String,
        val livesRemaining: Int
    ) : AnnotatedAnimal()

    data class AnnotatedBird(
        override val name: String,
        val canFly: Boolean
    ) : AnnotatedAnimal()

    @Test
    fun `Jackson polymorphic deserialization using type property with annotations`() {
        val animals = manager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (a:Animal)
                    WHERE a.createdBy = 'jackson-polymorphic-test'
                    RETURN properties(a) AS a
                    ORDER BY a.name
                """.trimIndent())
                .transform(AnnotatedAnimal::class.java)
        )

        println("Animals deserialized with annotations: $animals")
        assertEquals(5, animals.size)

        // Verify types were correctly deserialized
        val dogs = animals.filterIsInstance<AnnotatedDog>()
        val cats = animals.filterIsInstance<AnnotatedCat>()
        val birds = animals.filterIsInstance<AnnotatedBird>()

        assertEquals(2, dogs.size)
        assertEquals(2, cats.size)
        assertEquals(1, birds.size)

        // Check specific values
        assertTrue(dogs.any { it.name == "Buddy" && it.breed == "Golden Retriever" })
        assertTrue(cats.any { it.name == "Whiskers" && it.livesRemaining == 9 })
        assertTrue(birds.any { it.name == "Tweety" && it.canFly })
    }

    @Test
    fun `Filter annotated animals after Jackson deserialization`() {
        val dogs = manager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (a:Animal)
                    WHERE a.createdBy = 'jackson-polymorphic-test'
                    RETURN properties(a) AS a
                    ORDER BY a.name
                """.trimIndent())
                .transform(AnnotatedAnimal::class.java)
                .filterIsInstance<AnnotatedDog>()
        )

        println("Dogs only: $dogs")
        assertEquals(2, dogs.size)
        assertTrue(dogs.all { it is AnnotatedDog })
        assertEquals("Buddy", dogs[0].name)
        assertEquals("Max", dogs[1].name)
    }

    // ========================================
    // APPROACH 2: Using Neo4j labels (no type property needed!)
    // ========================================

    /**
     * Drivine's TransformPostProcessor automatically detects @JsonSubTypes and uses Neo4j labels
     * to determine the correct subtype. No custom deserializer needed!
     */
    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
    )
    @JsonSubTypes(
        JsonSubTypes.Type(value = LabeledDog::class, name = "Dog"),
        JsonSubTypes.Type(value = LabeledCat::class, name = "Cat"),
        JsonSubTypes.Type(value = LabeledBird::class, name = "Bird")
    )
    sealed class LabeledAnimal {
        abstract val name: String
    }

    data class LabeledDog(
        override val name: String,
        val breed: String
    ) : LabeledAnimal()

    data class LabeledCat(
        override val name: String,
        val livesRemaining: Int
    ) : LabeledAnimal()

    data class LabeledBird(
        override val name: String,
        val canFly: Boolean
    ) : LabeledAnimal()

    @Test
    fun `Jackson polymorphic deserialization using Neo4j labels automatically`() {
        // Include labels in the result - TransformPostProcessor will use them for type discrimination
        val animals = manager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (a:Animal)
                    WHERE a.createdBy = 'jackson-polymorphic-test'
                    WITH properties(a) AS props, labels(a) AS lbls
                    RETURN props {.*, labels: lbls} AS a
                    ORDER BY a.name
                """.trimIndent())
                .transform(LabeledAnimal::class.java)
        )

        println("Animals deserialized using labels: $animals")
        assertEquals(5, animals.size)

        // Verify types were correctly deserialized
        val dogs = animals.filterIsInstance<LabeledDog>()
        val cats = animals.filterIsInstance<LabeledCat>()
        val birds = animals.filterIsInstance<LabeledBird>()

        assertEquals(2, dogs.size)
        assertEquals(2, cats.size)
        assertEquals(1, birds.size)

        // Check specific values
        assertTrue(dogs.any { it.name == "Buddy" && it.breed == "Golden Retriever" })
        assertTrue(cats.any { it.name == "Whiskers" && it.livesRemaining == 9 })
        assertTrue(birds.any { it.name == "Tweety" && it.canFly })
    }

    @Test
    fun `Filter labeled animals after Jackson deserialization`() {
        val cats = manager.query(
            QuerySpecification
                .withStatement("""
                    MATCH (a:Animal)
                    WHERE a.createdBy = 'jackson-polymorphic-test'
                    WITH properties(a) AS props, labels(a) AS lbls
                    RETURN props {.*, labels: lbls} AS a
                    ORDER BY a.name
                """.trimIndent())
                .transform(LabeledAnimal::class.java)
                .filterIsInstance<LabeledCat>()
        )

        println("Cats only: $cats")
        assertEquals(2, cats.size)
        assertTrue(cats.all { it is LabeledCat })
        assertEquals("Mittens", cats[0].name)
        assertEquals(7, cats[0].livesRemaining)
        assertEquals("Whiskers", cats[1].name)
        assertEquals(9, cats[1].livesRemaining)
    }
}