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
        assert(spec.toString().contains("tags = [\"developer\", \"kotlin\"]"))
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

    @Test
    fun `long collections are abbreviated, not dumped`() {
        val embedding = List(1536) { it * 0.001 }
        val spec = QuerySpecification
            .withStatement("CALL db.index.vector.queryNodes(\$index, \$topK, \$queryVector)")
            .bind(mapOf("index" to "entity_index", "topK" to 10, "queryVector" to embedding))

        val rendered = spec.toString()

        // Both renderings — the readable block and the :params line — are bounded.
        assert(rendered.contains("… +1526 more"))
        assert(!rendered.contains(embedding.last().toString()))
        // A log line for a 1536-dim vector should stay in the hundreds of chars.
        assert(rendered.length < 1000) { "toString was ${rendered.length} chars" }
        // Short parameters are untouched.
        assert(rendered.contains("topK = 10"))
    }

    @Test
    fun `long strings are abbreviated with their real length`() {
        val spec = QuerySpecification
            .withStatement("CREATE (d:Document {text: \$text})")
            .bind(mapOf("text" to "x".repeat(5000)))

        val rendered = spec.toString()

        assert(rendered.contains("(5000 chars)"))
        assert(rendered.length < 2000) { "toString was ${rendered.length} chars" }
    }

    @Test
    fun `an abbreviated string is not a valid Cypher literal`() {
        val spec = QuerySpecification
            .withStatement("CREATE (d:Document {text: \$text})")
            .bind(mapOf("text" to "x".repeat(5000)))

        val params = spec.toString().substringAfter("  :params ")

        // The marker sits outside the closing quote, so pasting this cannot silently write a
        // 500-char stub — it is a parse error.
        assert(params.contains("\"… (5000 chars)")) { params }
    }

    @Test
    fun `embeddings bound via a map parameter are abbreviated`() {
        val embedding = List(1536) { it * 0.001 }
        val spec = QuerySpecification
            .withStatement("CREATE (e:Entity) SET e = \$props")
            .bind(mapOf("props" to mapOf("name" to "Widget", "embedding" to embedding)))

        val rendered = spec.toString()

        assert(rendered.contains("… +1526 more"))
        assert(!rendered.contains(embedding.last().toString()))
        assert(rendered.length < 1000) { "toString was ${rendered.length} chars" }
    }

    @Test
    fun `embeddings nested in a batch of rows are abbreviated`() {
        val embedding = List(1536) { it * 0.001 }
        val rows = List(50) { mapOf("id" to "row-$it", "props" to mapOf("embedding" to embedding)) }
        val spec = QuerySpecification
            .withStatement("UNWIND \$rows AS row CREATE (n:Node) SET n = row.props")
            .bind(mapOf("rows" to rows))

        val rendered = spec.toString()

        // Both the outer list and each nested embedding are bounded.
        assert(rendered.contains("… +40 more"))
        assert(rendered.contains("… +1526 more"))
        assert(!rendered.contains(embedding.last().toString()))
        assert(rendered.length < 4000) { "toString was ${rendered.length} chars" }
    }

    @Test
    fun `long strings nested inside a collection are abbreviated`() {
        val spec = QuerySpecification
            .withStatement("UNWIND \$docs AS doc CREATE (d:Document {text: doc})")
            .bind(mapOf("docs" to List(3) { "x".repeat(5000) }))

        val rendered = spec.toString()

        assert(rendered.contains("(5000 chars)"))
        assert(rendered.length < 4000) { "toString was ${rendered.length} chars" }
    }

    @Test
    fun `maps with many keys are abbreviated`() {
        val spec = QuerySpecification
            .withStatement("CREATE (n:Node) SET n = \$props")
            .bind(mapOf("props" to (1..40).associate { "key$it" to it }))

        val rendered = spec.toString()

        assert(rendered.contains("… +30 more"))
        assert(!rendered.contains("key40"))
    }
}
