package org.drivine.sample

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.cypher.api.CompileJson
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Cypher compiler on the class path of a Spring Boot application, as this module's is: Boot's BOM manages
 * kotlinx-serialization-json, so an application resolves the compiler's JSON library to Boot's version, beneath the one
 * the compiler is built with. A compiler that came to call that library's newer API would fail here, as it would in the
 * application, at the first statement it compiled (a NoSuchMethodError, not a compile error).
 */
class BootManagedCompilerTest {

    private val mapper = ObjectMapper()

    @Test
    fun `the JSON library on the class path is the one Spring Boot's BOM manages`() {
        // Blank where the BOM names no kotlin-serialization.version: Gradle passes a null property as -D with no value.
        val managed = requireNotNull(System.getProperty("drivine.boot.kotlinSerialization")?.takeIf { it.isNotBlank() }) { "The build passes no drivine.boot.kotlinSerialization" }
        // The compiler's runtime dependency, as it is an application's: not on the compile class path.
        val json = Class.forName("kotlinx.serialization.json.Json")
        val jar = File(json.protectionDomain.codeSource.location.toURI()).name
        assertTrue(jar.startsWith("kotlinx-serialization-json") && jar.endsWith("-$managed.jar"), "kotlinx-serialization-json $managed is not on the class path: $jar")
    }

    @Test
    fun `the compiler parses and compiles on Boot's JSON library, each store, typed parameters and all`() {
        // CompileJson parses each request with the library; the Oracle compiler reads a literal it writes back with it.
        val requests = listOf("postgres", "oracle", "sqlite").flatMap { store ->
            listOf(
                """{"store": "$store", "graph": {"name": "g"}, "cypher": "MATCH (p:Person {name: ${'$'}name}) WHERE p.age > ${'$'}age RETURN p.name", "parameters": {"name": "Ada", "age": 36}}""",
                """{"store": "$store", "graph": {"name": "g"}, "cypher": "RETURN ${'$'}x AS x, ${'$'}d AS d, ${'$'}b AS b, ${'$'}m AS m", "parameters": {"x": {"${'$'}float": "NaN"}, "d": {"${'$'}temporal": "DATE", "text": "2026-11-08"}, "b": {"${'$'}bytes": "AAEC"}, "m": {"${'$'}map": {"${'$'}a": [1, 2.5, null, true]}}}}""",
                """{"store": "$store", "graph": {"name": "g"}, "cypher": "MATCH (n:N) WHERE n.a = 'x' AND n.b = 2.5 RETURN n"}""",
            )
        }
        for (request in requests) {
            val answer = mapper.readTree(CompileJson.compile(request))
            assertTrue(answer.has("sql") && answer["sql"].textValue().isNotBlank(), "No SQL for $request: $answer")
        }
        assertEquals(
            mapper.readTree("""{"refusal": "The statement has a parameter ${'$'}x, and no value is bound to it."}"""),
            mapper.readTree(CompileJson.compile("""{"store": "postgres", "graph": {"name": "g"}, "cypher": "RETURN ${'$'}x"}""")),
        )
        // The compilers drivine4j makes, as a Boot application's connection makes them.
        for (compiler in listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g"))) {
            val compiled = compiler.compile("MATCH (n:N) WHERE n.a = 'x' AND n.tags = ${'$'}tags RETURN n", mapOf("tags" to listOf("a", 1L, 2.5)))
            assertTrue(compiled.sql.isNotBlank())
        }
    }
}
