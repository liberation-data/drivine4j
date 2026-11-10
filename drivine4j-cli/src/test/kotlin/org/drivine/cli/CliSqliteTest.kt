package org.drivine.cli

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The CLI end to end, in process, on SQLite in memory: no container, so a plain `test` runs it. */
class CliSqliteTest {

    private val memory = listOf("-a", "sqlite::memory:")

    @Test
    fun `a statement's rows print as one json line, and the run exits with 0`() {
        assertEquals(CliRun(0, "{\"columns\":[\"n\"],\"rows\":[[1]]}\n", ""), runCli(memory + listOf("--format", "json", "RETURN 1 AS n")))
    }

    @Test
    fun `a statement that fails exits with 1, and prints nothing to stdout`() {
        val run = runCli(memory + listOf("--format", "json", "RETURN 1/0"))
        assertEquals(1, run.exit)
        assertEquals("", run.stdout)
        assertTrue(run.stderr.isNotBlank())
    }

    @Test
    fun `a statement compiles to SQL and writes the graph, which lasts as long as the run`() {
        assertEquals(
            "{\"columns\":[\"name\",\"c\"],\"rows\":[[\"x\",1]]}\n",
            runCli(memory + listOf("--format", "json", "CREATE (p:P {name: 'x'}) WITH p MATCH (q:P) RETURN q.name AS name, count(*) AS c")).stdout,
        )
        assertEquals("{\"columns\":[\"c\"],\"rows\":[[0]]}\n", runCli(memory + listOf("--format", "json", "MATCH (q:P) RETURN count(q) AS c")).stdout)
    }

    @Test
    fun `a write with no RETURN gives no columns, and a column of an unaliased expression is named by its text`() {
        assertEquals("{\"columns\":[],\"rows\":[]}\n", runCli(memory + listOf("--format", "json", "CREATE (:P)")).stdout)
        assertEquals("{\"columns\":[\"1 + 1\"],\"rows\":[[2]]}\n", runCli(memory + listOf("--format", "json", "RETURN 1 + 1")).stdout)
    }

    @Test
    fun `a column that holds a list is one column`() {
        assertEquals("{\"columns\":[\"l\"],\"rows\":[[[1,2]]]}\n", runCli(memory + listOf("--format", "json", "RETURN [1, 2] AS l")).stdout)
    }

    @Test
    fun `a file of SQLite's that the address names is kept`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val file = listOf("-a", "sqlite:${dir.resolve("graph.db")}")
        assertEquals(0, runCli(file + "CREATE (:Kept {k: 1})").exit)
        assertEquals("k\n1\n", runCli(file + listOf("--format", "plain", "MATCH (n:Kept) RETURN n.k AS k")).stdout)
    }

    @Test
    fun `the address may come from DRIVINE_ADDRESS`() {
        assertEquals("one\n1\n", runCli(listOf("RETURN 1 AS one"), mapOf("DRIVINE_ADDRESS" to "sqlite::memory:")).stdout)
    }

    @Test
    fun `--version prints drivine's version, and --help the usage`() {
        val version = runCli(listOf("--version"))
        assertEquals(0, version.exit)
        assertTrue(Regex("^drivine \\S+\n$").matches(version.stdout), version.stdout)
        assertTrue("unknown" !in version.stdout, "the build writes the version: ${version.stdout}")
        val help = runCli(listOf("--help"))
        assertEquals(0, help.exit)
        assertTrue(help.stdout.startsWith("Usage: drivine"))
    }

    @Test
    fun `the invocation is checked before the CLI connects`() {
        // bolt://localhost:1 can't be reached, but a bad invocation exits with 2 first.
        assertEquals(2, runCli(listOf("-a", "bolt://localhost:1", "--nope", "RETURN 1")).exit)
        assertEquals(2, runCli(listOf("-a", "bolt://localhost:1", "RETURN 1", "RETURN 2")).exit)
        assertEquals(2, runCli(listOf("-a", "bolt://localhost:1", "--type", "postgres", "RETURN 1")).exit)
    }

    @Test
    fun `a store of each kind that can't be reached exits with 3, and prints nothing to stdout`() {
        // Port 1 on this machine refuses the connection, so no store is needed, and none is started.
        listOf("bolt://localhost:1", "memgraph://localhost:1", "falkordb://localhost:1", "postgresql://localhost:1/db", "oracle://localhost:1/FREEPDB1")
            .forEach { address ->
                val run = runCli(listOf("-a", address, "RETURN 1"), mapOf("DRIVINE_USER" to "u", "DRIVINE_PASSWORD" to "p"))
                assertEquals(3, run.exit, "$address: ${run.stderr}")
                assertEquals("", run.stdout, address)
                assertTrue("can't connect" in run.stderr, "$address: ${run.stderr}")
            }
    }

    // On Windows, Process.destroy kills the process outright, as kill -9 does, which no hook outlives.
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    fun `sqlite colon memory's file is deleted when the run is killed`(@org.junit.jupiter.api.io.TempDir tmp: java.nio.file.Path) {
        // On a JVM of its own, as the binary runs, with a temp dir of the test's: a statement that runs for minutes,
        // stopped by SIGTERM once the file is there.
        val jvm = ProcessHandle.current().info().command().orElseThrow()
        val process = ProcessBuilder(
            jvm, "-Djava.io.tmpdir=$tmp", "-cp", System.getProperty("java.class.path"), "org.drivine.cli.MainKt",
            "-a", "sqlite::memory:", "UNWIND range(1, 1000000000) AS x RETURN count(x) AS c",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
            while (!tmp.toFile().walk().any { it.name == "memory.db" }) {
                assertTrue(process.isAlive && System.nanoTime() < deadline, "the run made no memory.db in $tmp")
                Thread.sleep(50)
            }
            process.destroy()
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "the run didn't stop on SIGTERM")
            assertEquals(emptyList(), tmp.toFile().listFiles()!!.map { it.name }, "left behind in $tmp")
        } finally {
            process.destroyForcibly()
        }
    }

    @TestFactory
    fun `the contract's cases`(): List<DynamicTest> = CliCases("sqlite", "sqlite::memory:", clears = false).tests()
}
