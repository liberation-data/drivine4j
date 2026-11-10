package org.drivine.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import java.io.StringWriter
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** What a run of the CLI gave. */
data class CliRun(val exit: Int, val stdout: String, val stderr: String)

/** Runs the CLI in process, as the binary runs, with [env] for its environment and nothing of the test's own. */
fun runCli(args: List<String>, env: Map<String, String> = emptyMap()): CliRun {
    val out = StringWriter()
    val err = StringWriter()
    val exit = Cli(env, out, err).run(args)
    return CliRun(exit, out.toString(), err.toString())
}

/**
 * The contract's cases, `cli.yaml` (copied from drivine-cypher-spec: see `cli/VERSION` in the test resources), run
 * against one [store] at [address], in process, as the contract's README says a runner runs them ("Cases"). The store's
 * credentials go in `DRIVINE_USER` and `DRIVINE_PASSWORD` unless a case gives those itself.
 *
 * A case that needs what this build doesn't have yet is skipped, with why: [skipped] says which.
 */
class CliCases(
    private val store: String,
    private val address: String,
    private val user: String? = null,
    private val password: String? = null,
    /** Whether the graph is emptied before each case. `sqlite::memory:` starts empty on each run. */
    private val clears: Boolean = true,
) {

    fun tests(): List<DynamicTest> = cases().map { case ->
        DynamicTest.dynamicTest(case["name"] as String) {
            skipped(case)?.let { assumeTrue(false, it) }
            check(case)
        }
    }

    /**
     * Why [case] isn't run here, or null where it is. A case is run where it needs only what this build has: one
     * statement, given as the argument or with `-c`, the address, the user, `--type` and the formats plain, json and auto.
     */
    fun skipped(case: Map<String, Any?>): String? {
        val args = args(case)
        val env = (case["env"] as Map<*, *>?).orEmpty()
        return when {
            case["mode"] == "cypher-shell" -> "the cypher-shell mode is liberation-data/drivine4j#720's"
            case["store"] != false && !runsOn(case) -> "not a case for $store"
            (case["os"] as List<*>?)?.contains(os()) == false -> "not a case for ${os()}"
            case["files"] != null -> "files, :commands and profiles come later"
            args.any { it in Args.LATER } -> "${args.first { it in Args.LATER }} comes later"
            args.zipWithNext().any { (flag, value) -> flag == "--format" && value in Format.LATER } -> "--format table and csv come later"
            case["stdin"] != null && !statementGiven(args) -> "reading statements from stdin comes later"
            env.keys.any { it == "DRIVINE_DATABASE" || it == "DRIVINE_GRAPH" } -> "--database and --graph come later"
            case["name"] in NEEDS_LABELS -> "a node, a relationship and a path print with their labels and types, " +
                "which PersistenceManager.query doesn't give yet (the contract's \"On a SQL store, the CLI reads a node's labels\")"
            else -> null
        }
    }

    private fun check(case: Map<String, Any?>) {
        if (clears && case["store"] != false) {
            val cleared = runCli(listOf("-a", address, "MATCH (n) DETACH DELETE n"), credentials())
            assertEquals(0, cleared.exit, "emptying the graph: ${cleared.stderr}")
        }
        val args = (if (case["store"] == false) emptyList() else listOf("-a", address)) + args(case)
        val env = if (case["store"] == false) mutableMapOf() else credentials()
        (case["env"] as Map<*, *>?)?.forEach { (name, value) ->
            if (value == null) env.remove(name as String) else env[name as String] = substituted(value as String)
        }
        val run = runCli(args, env)
        val name = case["name"]
        (case["stdout"] as String?)?.let { assertEquals(it, run.stdout, "$name: stdout (stderr: ${run.stderr})") }
        (case["stdoutLines"] as List<*>?)?.let { patterns -> matchLines(patterns.map { Regex(it as String) }, run.stdout, name) }
        (case["stderr"] as String?)?.let { assertTrue(it in run.stderr, "$name: stderr is ${run.stderr}, not holding $it") }
        assertEquals(case["exit"], run.exit, "$name: the exit code (stdout: ${run.stdout}, stderr: ${run.stderr})")
    }

    /** Each pattern found in a line after the one the pattern before it was found in. */
    private fun matchLines(patterns: List<Regex>, stdout: String, name: Any?) {
        val lines = stdout.split('\n')
        var at = 0
        patterns.forEach { pattern ->
            while (at < lines.size && !pattern.containsMatchIn(lines[at])) at++
            if (at == lines.size) fail("$name: no line of stdout after the last matched matches $pattern: $stdout")
            at++
        }
    }

    private fun credentials(): MutableMap<String, String> = buildMap {
        user?.let { put(Invocation.ENV_USER, it) }
        password?.let { put(Invocation.ENV_PASSWORD, it) }
    }.toMutableMap()

    private fun args(case: Map<String, Any?>): List<String> = (case["args"] as List<*>).map { substituted(it as String) }

    private fun substituted(text: String) = text.replace("\$user", user.orEmpty()).replace("\$password", password.orEmpty())

    private fun runsOn(case: Map<String, Any?>): Boolean {
        fun stores(key: String) = (case[key] as List<*>?)?.flatMap { GROUPS[it] ?: listOf(it) }
        return stores("only")?.contains(store) != false && stores("except")?.contains(store) != true
    }

    private fun statementGiven(args: List<String>): Boolean = try {
        (Args.parse(args) as? Parsed.Run)?.invocation?.inputs?.isNotEmpty() ?: true
    } catch (e: BadInvocation) {
        true
    }

    companion object {
        private val GROUPS = mapOf("graph" to listOf("neo4j", "memgraph", "falkordb"), "sql" to listOf("postgres", "oracle", "sqlite"))

        /** The cases that print a node, a relationship or a path. */
        private val NEEDS_LABELS = setOf(
            "plain prints a path along and against its relationships' directions",
            "plain prints a node and a relationship as cypher-shell does",
            "plain prints a text's characters as they are",
        )

        @Suppress("UNCHECKED_CAST")
        fun cases(): List<Map<String, Any?>> {
            val text = CliCases::class.java.getResource("/cli/cli.yaml")!!.readText()
            return (ObjectMapper(YAMLFactory()).readValue(text, Map::class.java)["cases"] as List<Map<String, Any?>>)
        }

        private fun os(): String = System.getProperty("os.name").lowercase().let {
            when {
                "mac" in it -> "macos"
                "win" in it -> "windows"
                else -> "linux"
            }
        }
    }
}
