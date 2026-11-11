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
    fun `the store names the columns, RETURN star's too, and a result with no rows keeps them`() {
        assertEquals("{\"columns\":[\"a\",\"b\"],\"rows\":[[1,2]]}\n", runCli(memory + listOf("--format", "json", "WITH 1 AS a, 2 AS b RETURN *")).stdout)
        assertEquals("{\"columns\":[\"x\",\"n\"],\"rows\":[]}\n", runCli(memory + listOf("--format", "json", "MATCH (n:None) RETURN n.x AS x, n")).stdout)
    }

    @Test
    fun `nodes and relationships in a list keep their labels and type, and a node with no labels prints its properties alone`() {
        val cypher = "CREATE (n:P {l: [1, 2]})-[r:R]->(m {k: 1}) RETURN [n] AS ns, [r] AS rs, m"
        assertEquals(CliRun(0, "ns, rs, m\n[(:P {l: [1, 2]})], [[:R]], ({k: 1})\n", ""), runCli(memory + listOf("--format", "plain", cypher)))
        assertEquals(
            "{\"columns\":[\"ns\",\"rs\",\"m\"],\"rows\":[[[{\"\$t\":\"node\",\"labels\":[\"P\"],\"properties\":{\"l\":[1,2]}}],[{\"\$t\":\"relationship\",\"type\":\"R\",\"properties\":{}}]," +
                "{\"\$t\":\"node\",\"labels\":[],\"properties\":{\"k\":1}}]]}\n",
            runCli(memory + listOf("--format", "json", cypher)).stdout,
        )
    }

    @Test
    fun `a path, which a SQL store refuses, fails as a statement does, with 1`() {
        val run = runCli(memory + listOf("--format", "json", "MATCH p = (a)-[:R]->(b) RETURN p"))
        assertEquals(1, run.exit, run.stderr)
        assertEquals("", run.stdout)
        assertTrue("path" in run.stderr, run.stderr)
    }

    /**
     * The compiler gives a node with no labels, an empty list and an empty map as Kotlin's own empty collections, which
     * Jackson's Kotlin module asks kotlin-reflect about, and the native image's kotlin-reflect knows nothing of them: so
     * they're written as plain ones (JacksonEncoder.plain). Run against the native binary, this is that.
     */
    @Test
    fun `a node with no labels, an empty list and an empty map are written and read, in the native binary too`() {
        val cypher = "CREATE (n {l: []}) RETURN n, [] AS l, {} AS m, labels(n) AS ls"
        assertEquals(
            CliRun(0, "{\"columns\":[\"n\",\"l\",\"m\",\"ls\"],\"rows\":[[{\"\$t\":\"node\",\"labels\":[],\"properties\":{\"l\":[]}},[],{},[]]]}\n", ""),
            runCli(memory + listOf("--format", "json", cypher)),
        )
        assertEquals(CliRun(0, "n, l, m\n({l: []}), [], {}\n", ""), runCli(memory + listOf("--format", "plain", "CREATE (n {l: []}) RETURN n, [] AS l, {} AS m")))
    }

    @Test
    fun `statements run in order, each printed as it comes, and the first that fails stops the run, unless --continue`() {
        val text = "RETURN 1 AS a; RETURN 1/0 AS b; RETURN 3 AS c"
        val stopped = runCli(memory + listOf("--format", "json", text))
        assertEquals(1, stopped.exit)
        assertEquals("{\"columns\":[\"a\"],\"rows\":[[1]]}\n", stopped.stdout)
        val went = runCli(memory + listOf("--format", "json", "--continue", text))
        assertEquals(1, went.exit)
        assertEquals("{\"columns\":[\"a\"],\"rows\":[[1]]}\n{\"columns\":[\"c\"],\"rows\":[[3]]}\n", went.stdout)
        assertTrue(went.stderr.isNotBlank())
        // Each statement runs in a transaction of its own, so a later failure leaves an earlier write.
        val file = kotlin.io.path.createTempDirectory("drivine-cli-").toFile()
        try {
            val db = listOf("-a", "sqlite:${java.io.File(file, "g.db")}")
            assertEquals(1, runCli(db + listOf("CREATE (:Kept); RETURN 1/0")).exit)
            assertEquals("{\"columns\":[\"n\"],\"rows\":[[1]]}\n", runCli(db + listOf("--format", "json", "MATCH (k:Kept) RETURN count(k) AS n")).stdout)
        } finally {
            file.deleteRecursively()
        }
    }

    @Test
    fun `statements come from a -f file or stdin, and the exit command stops reading`() {
        val dir = kotlin.io.path.createTempDirectory("drivine-cli-").toFile()
        try {
            java.io.File(dir, "a.cypher").writeText("RETURN 1 AS n;\n:exit\nRETURN 2 AS n;\n")
            assertEquals(CliRun(0, "{\"columns\":[\"n\"],\"rows\":[[1]]}\n", ""), runCli(memory + listOf("--format", "json", "-f", "a.cypher"), dir = dir))
        } finally {
            dir.deleteRecursively()
        }
        assertEquals(CliRun(0, "n\n1\nm\n2\n", ""), runCli(memory + listOf("--format", "plain"), stdin = "RETURN 1 AS n;\nRETURN 2 AS m"))
        // A command this build doesn't run yet fails as a statement does.
        val later = runCli(memory + listOf("--format", "json"), stdin = ":begin\nRETURN 1 AS n;")
        assertEquals(1, later.exit)
        assertTrue(":begin comes later" in later.stderr, later.stderr)
    }

    @Test
    fun `given a text, stdin isn't read, and an empty -f file runs nothing`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val out = java.io.StringWriter()
        val err = java.io.StringWriter()
        val cli = Cli(emptyMap(), out, err, stdin = { throw AssertionError("stdin read") }, stdinTerminal = false)
        assertEquals(0, cli.run(memory + listOf("--format", "plain", "--non-interactive", "RETURN 1 AS one")), err.toString())
        assertEquals("one\n1\n", out.toString())
        java.nio.file.Files.writeString(dir.resolve("empty.cypher"), "")
        assertEquals(CliRun(0, "", ""), runCli(memory + listOf("-f", "empty.cypher"), dir = dir.toFile()))
    }

    @Test
    fun `an unknown command fails as cypher-shell says, and one of the contract's that comes later says so`() {
        val nope = runCli(memory + listOf("--format", "plain"), stdin = ":nope\nRETURN 1 AS a;\n")
        assertEquals(CliRun(1, "", "Could not find command :nope, use :help to see available commands\n"), nope)
        assertTrue(":source comes later" in runCli(memory, stdin = ":source x.cypher\n").stderr)
    }

    @Test
    fun `under --continue each failure reaches stderr as it comes, before the next result, and the quit command stops reading`() {
        // Each stream as a process sees it: what was written reaches the shared log only when it's flushed.
        val log = StringBuilder()
        fun stream(tag: String) = object : java.io.Writer() {
            val pending = StringBuilder()
            override fun write(cbuf: CharArray, off: Int, len: Int) {
                pending.append(cbuf, off, len)
            }
            override fun flush() {
                if (pending.isNotEmpty()) log.append("$tag:$pending")
                pending.setLength(0)
            }
            override fun close() {}
        }
        val stdin = "RETURN 1 AS a;\nRETURN 1/0;\n:nope\nRETURN 2 AS c;\n:quit\nRETURN 3 AS d;\n"
        val run = Cli(emptyMap(), stream("out"), stream("err"), stdin = { java.io.StringReader(stdin) })
            .run(memory + listOf("--format", "json", "--continue"))
        assertEquals(1, run)
        val order = Regex("(out|err):").findAll(log).map { it.value }.toList()
        assertEquals(listOf("out:", "err:", "err:", "out:"), order, log.toString())
        assertTrue("Could not find command :nope" in log, log.toString())
        assertTrue("\"d\"" !in log, log.toString())
    }

    @Test
    fun `a -f file is read where its name is attached to -f`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        java.nio.file.Files.writeString(dir.resolve("a.cypher"), "RETURN 1 AS n;")
        assertEquals(CliRun(0, "n\n1\n", ""), runCli(memory + listOf("--format", "plain", "-fa.cypher"), dir = dir.toFile()))
    }

    @Test
    fun `a reader that closes stdout after a failure under --continue still leaves the run failed`() {
        val out = object : java.io.Writer() {
            val taken = StringBuilder()
            override fun write(cbuf: CharArray, off: Int, len: Int) {
                if ('\n' in taken) throw java.io.IOException("Broken pipe")
                taken.append(cbuf, off, len)
            }
            override fun flush() {}
            override fun close() {}
        }
        val err = java.io.StringWriter()
        val run = Cli(emptyMap(), out, err).run(memory + listOf("--format", "json", "--continue", "RETURN 1/0; RETURN 1 AS a; RETURN 2 AS b"))
        assertEquals(1, run, err.toString())
    }

    @Test
    fun `a result that can't be written stops the run with 1, past those before it`() {
        val out = object : java.io.Writer() {
            val taken = StringBuilder()
            override fun write(cbuf: CharArray, off: Int, len: Int) {
                if ('\n' in taken) throw java.io.IOException("No space left on device")
                taken.append(cbuf, off, len)
            }
            override fun flush() {}
            override fun close() {}
        }
        val err = java.io.StringWriter()
        assertEquals(1, Cli(emptyMap(), out, err).run(memory + listOf("--format", "json", "RETURN 1 AS a; RETURN 2 AS b; RETURN 3 AS c")))
        assertEquals("{\"columns\":[\"a\"],\"rows\":[[1]]}\n", out.taken.toString())
        assertEquals("drivine: can't write the result: No space left on device\n", err.toString())
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
    fun `a reader that closes stdout, as head does, ends the run with 0 and no stack trace`() {
        // A stdout that takes the first line and then is closed, as `drivine … | head -1` leaves it.
        val out = object : java.io.Writer() {
            val taken = StringBuilder()
            override fun write(cbuf: CharArray, off: Int, len: Int) {
                if ('\n' in taken) throw java.io.IOException("Broken pipe")
                taken.append(cbuf, off, len)
            }
            override fun flush() {
                if ('\n' in taken) throw java.io.IOException("Broken pipe")
            }
            override fun close() {}
        }
        val err = java.io.StringWriter()
        assertEquals(0, Cli(emptyMap(), out, err).run(memory + listOf("--format", "plain", "UNWIND range(1, 3) AS x RETURN x")))
        assertTrue(out.taken.startsWith("x\n"), out.taken.toString())
        assertEquals("", err.toString())
    }

    @Test
    fun `a stdout that can't be written, as a full disk under a redirect, fails the run with 1 and says so`() {
        val full = object : java.io.Writer() {
            override fun write(cbuf: CharArray, off: Int, len: Int) = throw java.io.IOException("No space left on device")
            override fun flush() = throw java.io.IOException("No space left on device")
            override fun close() {}
        }
        for (args in listOf(memory + listOf("--format", "plain", "RETURN 1 AS one"), listOf("--version"))) {
            val err = java.io.StringWriter()
            assertEquals(1, Cli(emptyMap(), full, err).run(args), args.toString())
            assertEquals("drivine: can't write the result: No space left on device\n", err.toString(), args.toString())
        }
        // What the JDK's FileOutputStream gives for a pipe its reader closed, on Linux and macOS, and for a closed stream.
        for (closed in listOf("Broken pipe", "Stream closed", "Stream Closed")) assertTrue(Cli.closedPipe(java.io.IOException(closed)), closed)
        assertTrue(!Cli.closedPipe(java.io.IOException("Bad file descriptor")))
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
