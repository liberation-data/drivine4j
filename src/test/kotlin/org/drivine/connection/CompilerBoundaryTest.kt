package org.drivine.connection

import org.drivine.DrivineException
import org.drivine.cypher.CompilerException
import org.drivine.cypher.spec.CypherSpec
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.schema.ddl.SchemaDdl
import org.drivine.schema.ddl.SchemaDdlResult
import org.drivine.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * drivine4j calls the compiler, a module of its own (org.drivine:drivine-cypher-compiler), at one place, [CypherCompile],
 * which turns the compiler's refusal, a [CompilerException], into the [DrivineException] drivine4j has always thrown. A
 * caller that catches DrivineException would miss a refusal that came out anywhere else: these hold the boundary.
 */
class CompilerBoundaryTest {

    private val refusals = CypherSpec.load("refusals.yaml").cases.flatMap { case -> case.steps.map { case.name to it } }
        .filter { (_, step) -> step.error != null }

    @Test
    fun `every refusal in the spec reaches a caller of the public API as a DrivineException, its message, causes and trace intact`() {
        val pm = SqliteTestDatabase.graphManager("boundary")
        var checked = 0
        var ddl = 0
        for ((case, step) in refusals) {
            if (!step.runsOn("sqlite")) continue
            val where = "$case: ${step.cypher.trim()}"
            // Schema DDL is run by drivine4j's SchemaCommands, which makes the item or refuses it itself: it is compiled
            // only where it is run as a query, and the spec's refusal is the compiler's of that.
            if (SchemaDdl.translate(step.cypher) !is SchemaDdlResult.NotDdl) {
                ddl++
                continue
            }
            val parameters = expanded(step.parameters)
            // The compiler's own refusal, as it throws it.
            val refusal = assertThrows<CompilerException>(where) { SqliteCypherCompiler("boundary", JacksonEncoder).compile(step.cypher, parameters) }
            val thrown = assertThrows<Throwable>(where) {
                pm.query(QuerySpecification.withStatement(CypherStatement(step.cypher)).bind(parameters))
            }
            // The manager wraps what the connection throws with the query run, as it always has: the outer exception names
            // the spec, and its root cause is the boundary's.
            val drivine = assertIs<DrivineException>(thrown, where)
            when (val error = step.error) {
                is String -> assertEquals(error, drivine.message, where)
                is List<*> -> assertTrue(drivine.message in error, "Expected one of $error, but was <${drivine.message}>: $where")
            }
            assertEquals(refusal.message, drivine.message, where)
            val boundary = assertIs<DrivineException>(drivine.rootCause, where)
            assertSame(boundary, drivine.cause, where)
            assertEquals(refusal.message, boundary.message, where)
            assertEquals(refusal.rootCause?.javaClass, boundary.rootCause?.javaClass, where)
            assertEquals(refusal.rootCause?.message, boundary.rootCause?.message, where)
            assertSame(boundary.rootCause, boundary.cause, where)
            // Thrown where the compiler threw it, not at the boundary.
            assertEquals(refusal.stackTrace.first().toString(), boundary.stackTrace.first().toString(), where)
            checked++
        }
        assertTrue(checked > 100, "Only $checked of the spec's refusals ran on SQLite through the compiler ($ddl as schema DDL)")
    }

    @Test
    fun `each store's compile at the boundary throws the refusal as a DrivineException`() {
        val (case, step) = refusals.first { (_, step) -> step.error is String && listOf("postgres", "oracle", "sqlite").all(step::runsOn) }
        val parameters = expanded(step.parameters)
        val compiles = mapOf(
            "postgres" to { CypherCompile.postgres("g", step.cypher, parameters) },
            "sqlite" to { CypherCompile.sqlite("g", step.cypher, parameters) },
            "oracle" to { CypherCompile.oracle("g", step.cypher, parameters, { true }, { true }) },
            "oracle, its functions not stored" to { CypherCompile.oracle("g", step.cypher, parameters, { false }, { false }) },
        )
        for ((store, compile) in compiles) {
            val thrown = assertThrows<Throwable>("$case on $store") { compile() }
            assertIs<DrivineException>(thrown, "$case on $store")
            assertEquals(step.error, thrown.message, "$case on $store")
        }
    }

    @Test
    fun `nothing in drivine4j but CypherCompile calls the compiler to compile`() {
        // What can throw a CompilerException: making a compiler and compiling with it, or the compiler's reader, procedures
        // and JSON entry, which compiling calls.
        val calls = Regex("""\b(?:(?:Postgres|Oracle|Sqlite|Sql)CypherCompiler\s*\(|CypherReader\b|org\.drivine\.cypher\.Procedure\b|CypherCompiler\.compile\b|CompileJson\b)""")
        val sources = File("src/main/kotlin")
        assertTrue(sources.isDirectory, "No ${sources.absolutePath}")
        val offenders = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.invariantSeparatorsPath != "src/main/kotlin/org/drivine/connection/CypherCompile.kt" }
            .flatMap { file -> file.readLines().withIndex().filter { (_, line) -> calls.containsMatchIn(code(line)) }.map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" } }
            .toList()
        assertEquals(emptyList(), offenders, "Calls into the compiler that would let a CompilerException past the boundary")
    }

    @Test
    fun `no class is both drivine4j's and the compiler's`() {
        // Two jars that hold one class name each give whichever the class path finds first (the compiler's LowerCopies
        // would shadow drivine4j's): the compiler holds its part of a shared file under org.drivine.cypher.store instead.
        val compiler = File(CompilerException::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(compiler.isFile, "The compiler is not a jar: $compiler")
        val theirs = ZipFile(compiler).use { jar -> jar.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toSet() }
        val outputs = File(DrivineException::class.java.protectionDomain.codeSource.location.toURI())
        val ours = listOf(outputs, File(outputs.parentFile.parentFile, "java/main")).filter { it.isDirectory }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "class" }.map { it.relativeTo(dir).invariantSeparatorsPath }.toList()
        }.toSet()
        assertTrue(ours.size > 100, "Found only ${ours.size} of drivine4j's classes in $outputs")
        assertTrue("org/drivine/model/LowerCopies.class" in ours, "drivine4j's classes in $outputs don't hold LowerCopies")
        assertEquals(emptySet(), ours intersect theirs, "Classes in both drivine4j ($outputs) and the compiler ($compiler)")
    }

    /** [line] without a line comment or the text of a KDoc line, where a name is mentioned, not called. */
    private fun code(line: String): String = line.trim().let { if (it.startsWith("*") || it.startsWith("/*")) "" else it.substringBefore("//") }

    /** The spec's parameters as values: `{$repeat, times}` written out, and `{$float}` the double it names. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> expanded(value: T): T = when (value) {
        is Map<*, *> -> when (value.keys) {
            setOf("\$repeat", "times") -> (value["\$repeat"] as String).repeat((value["times"] as Number).toInt())
            setOf("\$float") -> (value["\$float"] as String).toDouble()
            else -> value.mapValues { (_, item) -> expanded(item) }
        } as T
        is List<*> -> value.map { expanded(it) } as T
        else -> value
    }
}
