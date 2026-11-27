package org.drivine.cli

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.drivine.cypher.spec.CypherSpec
import org.drivine.cypher.spec.CypherSpec.Case
import org.drivine.cypher.spec.CypherSpec.Step
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The Cypher spec's cases (`queries.yaml`, `writes.yaml`, `temporal.yaml` and `results.yaml`) run through the CLI on one
 * [store] at [address], with `--format json`: what a user runs, so a statement that works through the library and not
 * through the binary, as where native-image left out a class or a resource the statement reaches, fails here and not in
 * a user's hands (liberation-data/drivine4j#707). Run by the root module's runner, CypherSpec.kt, with its holds, so each
 * step gives the rows, or the failure, the spec says, as through `PersistenceManager.query`.
 *
 * A case is one run of the CLI, under `--continue`: its graph written, then each step its file says runs on [store], its
 * parameters set by `:param`, so the steps of a case that write share the store as they do through the library, and a
 * store is connected to once a case, not once a step. A marker after each step, a command the CLI doesn't know (which
 * fails, on stderr) and a `RETURN` of a text (on stdout), cuts what the run printed into each step's part.
 *
 * Where [clears], the store's nodes are removed before a case, as on a store that outlasts a run; SQLite in memory is a
 * store of the run's own.
 */
class CliSpec(
    private val store: String,
    private val address: String,
    private val user: String? = null,
    private val password: String? = null,
    private val clears: Boolean = true,
) {

    /** A container of the cases of each file. */
    fun tests(): List<DynamicNode> = FILES.map { file -> DynamicContainer.dynamicContainer(file, tests(CypherSpec.load(file))) }

    private fun tests(spec: CypherSpec): List<DynamicTest> {
        val run = Runner(spec)
        // The runner's own tests, a case's in the order of its cases: each told the case it runs, as runner reads a step's
        // part of the case's run by its place among the steps run.
        return spec.tests(store, before = run::reset, held = held(spec), run = run::step).zip(spec.cases) { test, case ->
            DynamicTest.dynamicTest(test.displayName) {
                run.case = case
                test.executable.execute()
            }
        }
    }

    private inner class Runner(private val spec: CypherSpec) {
        var case: Case? = null
        private var parts: List<Part>? = null
        private var next = 0

        fun reset() {
            parts = null
            next = 0
        }

        fun step(step: Step): List<Any?> {
            val parts = parts ?: run(requireNotNull(case)).also { parts = it }
            val part = parts.getOrNull(next++) ?: fail("${case?.name}: the runner asked for more steps than the run ran")
            assertEquals(part.step.cypher, step.cypher, "the step the run ran in this place")
            part.failure?.let { throw CliFailure(it) }
            return part.rows(spec)
        }

        /** The case's steps on [store], each with its part of the one run that ran them all. */
        private fun run(case: Case): List<Part> {
            val steps = runnable(case, held(spec)[case.name].orEmpty(), store)
            val script = buildString {
                if (clears) append(statement("MATCH (n) DETACH DELETE n"))
                spec.graph?.let { append(statement(it)) }
                marker(0)
                steps.forEachIndexed { i, step ->
                    appendLine(":params clear")
                    CypherSpec.expanded(step.parameters).forEach { (name, value) -> appendLine(":param ${escaped(name)} => ${literal(value)}") }
                    append(statement(step.cypher))
                    marker(i + 1)
                }
            }
            val dir = Files.createTempDirectory("drivine-spec-").toFile()
            try {
                File(dir, "case.cypher").writeText(script)
                val env = buildMap {
                    user?.let { put(Invocation.ENV_USER, it) }
                    password?.let { put(Invocation.ENV_PASSWORD, it) }
                }
                val run = runCli(listOf("-a", address, "--format", "json", "--continue", "-f", "case.cypher"), env, dir = dir)
                if (run.exit !in setOf(ExitCode.OK, ExitCode.STATEMENT_FAILED)) fail("${case.name}: the run exited with ${run.exit}: ${run.stderr}")
                val out = cut(run.stdout, steps.size) { it == "{\"columns\":[\"$MARKER\"],\"rows\":[[\"$MARKER\"]]}" }
                val err = cut(run.stderr, steps.size) { it.startsWith("Could not find command :$MARKER") }
                val seeded = err[0].isEmpty() && out[0].all { it.startsWith("{\"columns\":") }
                if (!seeded) fail("${case.name}: the case's graph wasn't written: ${err[0].joinToString("\n")}")
                return steps.mapIndexed { i, step -> Part(step, out[i + 1], err[i + 1]) }
            } finally {
                dir.deleteRecursively()
            }
        }

        private fun StringBuilder.marker(n: Int) {
            appendLine(":$MARKER$n")
            appendLine("RETURN '$MARKER' AS $MARKER;")
        }
    }

    /** A step's part of the run: the lines it printed on stdout, its result, and on stderr, its failure. */
    private class Part(val step: Step, val out: List<String>, val err: List<String>) {
        val failure: String? = err.joinToString("\n").takeIf { it.isNotEmpty() }

        /** Its rows, as the runner reads `PersistenceManager.query`'s: a row of one column its value, of several a list. */
        fun rows(spec: CypherSpec): List<Any?> {
            val result = out.singleOrNull() ?: fail("${step.cypher}: printed ${out.size} results, not one: $out")
            val read = json.readValue<Map<String, Any?>>(result)
            val columns = read["columns"] as List<*>
            step.columns?.let { assertEquals(it, columns, "the columns of ${step.cypher}") }
            val typed = spec.file == TYPED
            return (read["rows"] as List<*>).map { row ->
                val values = (row as List<*>).map { value(it, typed) }
                if (columns.size == 1) values.single() else values
            }
        }
    }

    /** A failure the CLI printed for a step, as the runner reads a failure of the store's: by its message. */
    class CliFailure(message: String) : RuntimeException(message)

    companion object {
        val FILES = listOf("queries.yaml", "writes.yaml", "temporal.yaml", "results.yaml")

        /**
         * The steps that fail through the CLI alone, by file and case, as the runner's own holds are: each is run, and
         * must fail, so its hold is taken out when its fix lands. A parameter the CLI sets is a Cypher literal the store
         * works out (`:param`), where the library binds the value as it is.
         */
        private val HELD_HERE: Map<String, Map<String, List<CypherSpec.Hold>>> = mapOf(
            "queries.yaml" to mapOf(
                // A map literal with an empty key, `{``: 9}`, fails on Oracle (ORA-40595), where a parameter of that map works.
                "A key worked out at run time" to listOf(CypherSpec.Hold(EMPTY_KEY, on = listOf("oracle"), steps = listOf(13))),
            ),
        )

        private const val EMPTY_KEY = "liberation-data/drivine-cypher-spec#438"

        /** The holds of [spec]'s steps: the runner's own, and those of the CLI's ([HELD_HERE]). */
        internal fun held(spec: CypherSpec): Map<String, List<CypherSpec.Hold>> {
            val here = HELD_HERE[spec.file].orEmpty()
            return (spec.held().keys + here.keys).associateWith { spec.held()[it].orEmpty() + here[it].orEmpty() }
        }

        /**
         * The steps of [case] the runner runs on [store], in order, as CypherSpec.tests runs them: those that run there, up
         * to one that [holds] skip there, which ends the case on it. A skip of a step that doesn't run on [store] ends
         * nothing, as the runner passes that step over before it reads its holds.
         */
        internal fun runnable(case: Case, holds: List<CypherSpec.Hold>, store: String): List<Step> {
            val skips = holds.filter { it.skip && Step(cypher = "", only = it.on).runsOn(store) }
            return case.steps.withIndex()
                .filter { (_, step) -> step.runsOn(store) }
                .takeWhile { (i, _) -> skips.none { it.steps == null || i + 1 in it.steps } }
                .map { it.value }
        }

        /**
         * [cypher] as a statement of the run's script, its indent trimmed as the root module's runners trim it, and a `;`
         * it ends with dropped, ended by a `;` on a line of its own, so that a statement whose last line is a `//`
         * comment doesn't take the `;`, and the marker after it, into the comment.
         */
        fun statement(cypher: String): String = cypher.trimIndent().trimEnd().removeSuffix(";") + "\n;\n"

        /** The marker's name, a command the CLI doesn't know and the column of its `RETURN`. */
        private const val MARKER = "drivinespecstep"

        private val json = ObjectMapper().enable(DeserializationFeature.USE_LONG_FOR_INTS)

        /**
         * [text]'s lines cut by the lines [marker] says end a part, the `steps + 1` parts before the last marker: the
         * graph's, and a step's each. What comes after the last marker is passed over.
         */
        fun cut(text: String, steps: Int, marker: (String) -> Boolean): List<List<String>> {
            val parts = mutableListOf<List<String>>()
            var part = mutableListOf<String>()
            for (line in text.lines()) {
                if (marker(line)) {
                    parts += part
                    part = mutableListOf()
                } else if (line.isNotEmpty()) {
                    part += line
                }
            }
            if (parts.size != steps + 1) fail("The run printed ${parts.size} of its ${steps + 1} markers:\n$text")
            return parts
        }

        /** The file whose rows keep each value's kind, as the CLI's json does: the rest read as `PersistenceManager.query`'s. */
        const val TYPED = "results.yaml"

        /**
         * A value the CLI wrote in json, as the spec's files write it. `{"$float": …}` is the double it names. Where
         * [typed], as `results.yaml` writes its rows, a node, a relationship and a path keep their `$t` (a path without
         * the CLI's `forward`), and a map of the user's own that has a `$t` key, which the CLI writes inside
         * `{"$t":"map","v":…}`, is `{"$map": …}`. Otherwise, as `PersistenceManager.query` gives them, a node and a
         * relationship are their properties, and that map is itself.
         */
        fun value(value: Any?, typed: Boolean): Any? = when (value) {
            is Map<*, *> -> {
                val read by lazy { value.mapValues { (_, it) -> value(it, typed) } }
                when {
                    // The user's own map, its entries read and not itself: its `$t` names no kind.
                    value.keys == setOf("\$t", "v") && value["\$t"] == "map" ->
                        (value["v"] as Map<*, *>).mapValues { (_, it) -> value(it, typed) }.let { if (typed) mapOf("\$map" to it) else it }
                    value["\$t"] == "path" && typed -> read - "forward"
                    (value["\$t"] == "node" || value["\$t"] == "relationship") && !typed -> read["properties"]
                    else -> CypherSpec.expanded<Any?>(read)
                }
            }
            is List<*> -> value.map { value(it, typed) }
            else -> value
        }

        /** [value], a spec parameter, as a Cypher literal for `:param`: NaN and the infinities as a division that gives them. */
        fun literal(value: Any?): String = when (value) {
            null -> "null"
            // A line break escaped too, as `:param` is a command of one line.
            is String -> "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"
            is Boolean, is Long, is Int -> value.toString()
            is Double -> when {
                value.isNaN() -> "0.0 / 0.0"
                value == Double.POSITIVE_INFINITY -> "1.0 / 0.0"
                value == Double.NEGATIVE_INFINITY -> "-1.0 / 0.0"
                else -> value.toString()
            }
            is List<*> -> value.joinToString(", ", "[", "]", transform = ::literal)
            is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (key, item) -> "${escaped(key.toString())}: ${literal(item)}" }
            else -> error("A parameter of a kind no Cypher literal writes: ${value.javaClass.name}")
        }

        private fun escaped(name: String) = "`" + name.replace("`", "``") + "`"
    }
}
