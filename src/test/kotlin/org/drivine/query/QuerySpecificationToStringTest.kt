package org.drivine.query

import org.drivine.connection.Person
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class QuerySpecificationToStringTest {

    @Test
    fun `toString with no parameters or post-processors`() {
        val spec = QuerySpecification
            .withStatement("MATCH (p:Person) RETURN p")

        println("=== Simple Query ===")
        println(spec.toString())
        println()

        assert(spec.toString().contains("MATCH (p:Person) RETURN p"))
        assert(spec.toString().contains("parameters: <empty>"))
        assert(spec.toString().contains("postProcessors: <none>"))
    }

    @Test
    fun `toString with parameters`() {
        val spec = QuerySpecification
            .withStatement("MATCH (p:Person {uuid: \$uuid}) RETURN p")
            .bind(mapOf(
                "uuid" to UUID.randomUUID().toString(),
                "name" to "John Doe",
                "age" to 30,
                "tags" to listOf("developer", "kotlin")
            ))

        println("=== Query with Parameters ===")
        println(spec.toString())
        println()

        assert(spec.toString().contains("uuid ="))
        assert(spec.toString().contains("name = \"John Doe\""))
        assert(spec.toString().contains("age = 30"))
        assert(spec.toString().contains("tags = [developer, kotlin]"))
    }

    @Test
    fun `toString with transform post-processor`() {
        val spec = QuerySpecification
            .withStatement("MATCH (p:Person) RETURN properties(p)")
            .bind(mapOf("limit" to 10))
            .transform(Person::class.java)

        println("=== Query with Transform ===")
        println(spec.toString())
        println()

        assert(spec.toString().contains("transform(Person)"))
        assert(spec.toString().contains("limit = 10"))
    }

    @Test
    fun `toString with multiple post-processors`() {
        val spec = QuerySpecification
            .withStatement("MATCH (p:Person) RETURN properties(p)")
            .transform(Person::class.java)
            .filter { it.age != null && it.age > 25 }
            .map { "${it.firstName} ${it.lastName}" }

        println("=== Query with Multiple Post-processors ===")
        println(spec.toString())
        println()

        assert(spec.toString().contains("[0] transform(Person)"))
        assert(spec.toString().contains("[1] filter(predicate)"))
        assert(spec.toString().contains("[2] map(mapper)"))
    }

    @Test
    fun `toString with skip and limit`() {
        val spec = QuerySpecification
            .withStatement("MATCH (p:Person) RETURN p")
            .skip(10)
            .limit(20)

        println("=== Query with Skip and Limit ===")
        println(spec.toString())
        println()

        assert(spec.toString().contains("skip: 10"))
        assert(spec.toString().contains("limit: 20"))
    }

    @Test
    fun `toString with complex parameters including Instant`() {
        val now = Instant.now()
        val spec = QuerySpecification
            .withStatement("CREATE (e:Event) SET e = \$event")
            .bind(mapOf(
                "event" to mapOf(
                    "uuid" to UUID.randomUUID().toString(),
                    "name" to "Test Event",
                    "timestamp" to now,
                    "metadata" to mapOf("key" to "value")
                )
            ))

        println("=== Query with Complex Parameters ===")
        println(spec.toString())
        println()

        assert(spec.toString().contains("event ="))
    }

    @Test
    fun `toString with null parameter`() {
        val spec = QuerySpecification
            .withStatement("MATCH (p:Person) WHERE p.email = \$email RETURN p")
            .bind(mapOf(
                "email" to null,
                "name" to "Test"
            ))

        println("=== Query with Null Parameter ===")
        println(spec.toString())
        println()

        assert(spec.toString().contains("email = null"))
        assert(spec.toString().contains("name = \"Test\""))
    }
}