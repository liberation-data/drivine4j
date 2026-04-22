package org.drivine.query

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuerySpecificationRenderTest {

    @Test
    fun `render with string value replaces placeholder in compiled statement`() {
        val spec = QuerySpecification
            .withStatement("MATCH ()-[r:\$(\$type)]->() RETURN r")
            .renderParam("type", "HAS_ENTITY")

        val compiled = Neo4jSpecCompiler(spec.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals("MATCH ()-[r:HAS_ENTITY]->() RETURN r", compiled.statement)
    }

    @Test
    fun `render with list of strings joins with colon`() {
        val spec = QuerySpecification
            .withStatement("MERGE (e:ContentElement {id: \$id}) SET e:\$(\$labels)")
            .render(mapOf("labels" to listOf("Chunk", "Document")))
            .bind(mapOf("id" to "abc"))

        val compiled = Neo4jSpecCompiler(spec.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals(
            "MERGE (e:ContentElement {id: \$id}) SET e:Chunk:Document",
            compiled.statement
        )
    }

    @Test
    fun `unmatched dollar-paren expressions pass through untouched`() {
        val spec = QuerySpecification
            .withStatement("MATCH (n:\$(\$known)) WHERE n.foo = \$(\$unknown) RETURN n")
            .render(mapOf("known" to "Person"))

        val compiled = Neo4jSpecCompiler(spec.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals(
            "MATCH (n:Person) WHERE n.foo = \$(\$unknown) RETURN n",
            compiled.statement
        )
    }

    @Test
    fun `no render call leaves dollar-paren expressions intact for Neo4j 5 native handling`() {
        val spec = QuerySpecification
            .withStatement("MATCH (n:\$(\$label)) RETURN n")
            .bind(mapOf("label" to "Person"))

        val compiled = Neo4jSpecCompiler(spec.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals("MATCH (n:\$(\$label)) RETURN n", compiled.statement)
    }

    @Test
    fun `render params are not sent as database parameters`() {
        val spec = QuerySpecification
            .withStatement("MERGE (e:ContentElement {id: \$id}) SET e:\$(\$labels)")
            .render(mapOf("labels" to listOf("Chunk", "Document")))
            .bind(mapOf("id" to "abc"))

        val compiled = Neo4jSpecCompiler(spec.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertTrue(compiled.parameters.containsKey("id"))
        assertFalse(
            compiled.parameters.containsKey("labels"),
            "render params must not leak into DB-level parameters"
        )
        assertEquals(1, compiled.parameters.size)
    }

    @Test
    fun `multiple render params in the same query`() {
        val spec = QuerySpecification
            .withStatement("MATCH (a:\$(\$from))-[r:\$(\$rel)]->(b:\$(\$to)) RETURN r")
            .render(mapOf(
                "from" to "Person",
                "rel" to "KNOWS",
                "to" to "Person"
            ))

        val compiled = Neo4jSpecCompiler(spec.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals(
            "MATCH (a:Person)-[r:KNOWS]->(b:Person) RETURN r",
            compiled.statement
        )
    }

    @Test
    fun `mixed render and bind params compile correctly`() {
        val spec = QuerySpecification
            .withStatement(
                "MERGE (e:ContentElement {id: \$id}) " +
                "SET e:\$(\$labels) SET e += \$props"
            )
            .render(mapOf("labels" to listOf("Chunk", "Document")))
            .bind(mapOf(
                "id" to "abc",
                "props" to mapOf("title" to "hello")
            ))

        val compiled = Neo4jSpecCompiler(spec.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals(
            "MERGE (e:ContentElement {id: \$id}) SET e:Chunk:Document SET e += \$props",
            compiled.statement
        )
        assertEquals(setOf("id", "props"), compiled.parameters.keys)
    }

    @Test
    fun `renderParam convenience is equivalent to render with single entry`() {
        val viaMap = QuerySpecification
            .withStatement("MATCH (n:\$(\$label)) RETURN n")
            .render(mapOf("label" to "Person"))
        val viaSingle = QuerySpecification
            .withStatement("MATCH (n:\$(\$label)) RETURN n")
            .renderParam("label", "Person")

        val compiledMap = Neo4jSpecCompiler(viaMap.finalizedCopy(QueryLanguage.CYPHER)).compile()
        val compiledSingle = Neo4jSpecCompiler(viaSingle.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals(compiledMap.statement, compiledSingle.statement)
    }

    @Test
    fun `bind produces identical params whether or not render is called`() {
        val id = UUID.randomUUID().toString()
        val withoutRender = QuerySpecification
            .withStatement("MATCH (n:Person {id: \$id}) RETURN n")
            .bind(mapOf("id" to id, "name" to "Ada", "age" to 36))
        val withRender = QuerySpecification
            .withStatement("MATCH (n:\$(\$label) {id: \$id}) RETURN n")
            .render(mapOf("label" to "Person"))
            .bind(mapOf("id" to id, "name" to "Ada", "age" to 36))

        val compiledNo = Neo4jSpecCompiler(withoutRender.finalizedCopy(QueryLanguage.CYPHER)).compile()
        val compiledYes = Neo4jSpecCompiler(withRender.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals(compiledNo.parameters, compiledYes.parameters)
    }

    @Test
    fun `bind type conversion still runs when render is also used`() {
        val uuid = UUID.randomUUID()
        val now = Instant.now()
        val spec = QuerySpecification
            .withStatement("MERGE (n:\$(\$label) {id: \$id}) SET n.createdAt = \$createdAt")
            .render(mapOf("label" to "Person"))
            .bind(mapOf(
                "id" to uuid,
                "createdAt" to now
            ))

        val compiled = Neo4jSpecCompiler(spec.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals(uuid.toString(), compiled.parameters["id"])
        assertTrue(
            compiled.parameters["createdAt"] is ZonedDateTime,
            "Instant bind value should still be converted to ZonedDateTime when render is used"
        )
    }

    @Test
    fun `render does not modify bind value contents even if they look like placeholders`() {
        val trickyValue = "\$(\$label) not a placeholder, just a string value"
        val spec = QuerySpecification
            .withStatement("MATCH (n:\$(\$label)) WHERE n.note = \$note RETURN n")
            .render(mapOf("label" to "Person"))
            .bind(mapOf("note" to trickyValue))

        val compiled = Neo4jSpecCompiler(spec.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals("MATCH (n:Person) WHERE n.note = \$note RETURN n", compiled.statement)
        assertEquals(
            trickyValue,
            compiled.parameters["note"],
            "bind values must be passed through verbatim — template rendering only touches query text"
        )
    }

    @Test
    fun `render and bind can be called in either order without affecting result`() {
        val renderFirst = QuerySpecification
            .withStatement("MATCH (n:\$(\$label) {id: \$id}) RETURN n")
            .render(mapOf("label" to "Person"))
            .bind(mapOf("id" to "abc"))
        val bindFirst = QuerySpecification
            .withStatement("MATCH (n:\$(\$label) {id: \$id}) RETURN n")
            .bind(mapOf("id" to "abc"))
            .render(mapOf("label" to "Person"))

        val a = Neo4jSpecCompiler(renderFirst.finalizedCopy(QueryLanguage.CYPHER)).compile()
        val b = Neo4jSpecCompiler(bindFirst.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals(a.statement, b.statement)
        assertEquals(a.parameters, b.parameters)
    }

    @Test
    fun `render falls back to toString for non-string non-list values`() {
        val spec = QuerySpecification
            .withStatement("MATCH (n) WHERE n.count = \$(\$n) RETURN n")
            .renderParam("n", 42)

        val compiled = Neo4jSpecCompiler(spec.finalizedCopy(QueryLanguage.CYPHER)).compile()

        assertEquals("MATCH (n) WHERE n.count = 42 RETURN n", compiled.statement)
    }
}
