package org.drivine.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** What a run of the CLI gave. */
data class CliRun(val exit: Int, val stdout: String, val stderr: String)

/**
 * Runs the CLI with [env] for its environment and nothing of the test's own: in process, as the binary runs, or, where
 * `DRIVINE_CLI_BINARY` names a built `drivine`, that binary, as a process given [stdin] and run in [dir]. In process
 * the CLI reads no stdin yet, and no case that runs here needs a working directory of its own.
 */
fun runCli(args: List<String>, env: Map<String, String> = emptyMap(), stdin: String? = null, dir: File? = null): CliRun {
    CLI_BINARY?.let { return runBinary(it, args, env, stdin, dir) }
    val out = StringWriter()
    val err = StringWriter()
    val exit = Cli(env, out, err).run(args)
    return CliRun(exit, out.toString(), err.toString())
}

/** The binary the tests run, where `DRIVINE_CLI_BINARY` names one: see README.md. */
private val CLI_BINARY: String? = System.getenv("DRIVINE_CLI_BINARY")?.ifBlank { null }

private fun runBinary(binary: String, args: List<String>, env: Map<String, String>, stdin: String?, dir: File?): CliRun {
    val out = File.createTempFile("drivine-out-", ".txt")
    val err = File.createTempFile("drivine-err-", ".txt")
    try {
        val builder = ProcessBuilder(listOf(binary) + args).directory(dir).redirectOutput(out).redirectError(err)
        builder.environment().apply { clear(); putAll(env) }
        val process = builder.start()
        // Closed, so a run given no stdin reads an empty one, as from a pipe with nothing in it.
        process.outputStream.use { it.write(stdin.orEmpty().toByteArray(Charsets.UTF_8)) }
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            fail("$binary ${args.joinToString(" ")} ran past five minutes")
        }
        return CliRun(process.exitValue(), out.readText(Charsets.UTF_8), err.readText(Charsets.UTF_8))
    } finally {
        out.delete()
        err.delete()
    }
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
            case["name"] in PATHS && GROUPS.getValue("sql").contains(store) ->
                "a SQL store refuses a path: liberation-data/drivine-cypher-spec#163"
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
        // Each case in an empty working directory of its own, where its files are to be written when files come.
        val dir = Files.createTempDirectory("drivine-case-").toFile()
        val run = try {
            runCli(args, env, case["stdin"] as String?, dir)
        } finally {
            dir.deleteRecursively()
        }
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

        /** The cases that print a path, which a SQL store refuses (liberation-data/drivine-cypher-spec#163). */
        private val PATHS = setOf("plain prints a path along and against its relationships' directions")

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
