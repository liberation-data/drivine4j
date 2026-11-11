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
        val later = runCli(memory + listOf("--format", "json"), stdin = ":use other\nRETURN 1 AS n;")
        assertEquals(1, later.exit)
        assertTrue(":use comes later" in later.stderr, later.stderr)
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
        assertTrue(":use comes later" in runCli(memory, stdin = ":use x\n").stderr)
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
    fun `stdin that breaks mid-run fails the run with 1, past the results before it, and a -f file that can't be read is 2`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        // A pipe that gives one statement and then fails, as a broken pipe on stdin does.
        val broken = object : java.io.Reader() {
            val text = "RETURN 1 AS a;\n"
            var at = 0
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (at >= text.length) throw java.io.IOException("Input/output error")
                cbuf[off] = text[at++]
                return 1
            }
            override fun close() {}
        }
        val out = java.io.StringWriter()
        val err = java.io.StringWriter()
        assertEquals(1, Cli(emptyMap(), out, err, stdin = { broken }).run(memory + listOf("--format", "json")))
        assertEquals("{\"columns\":[\"a\"],\"rows\":[[1]]}\n", out.toString())
        assertEquals("drivine: can't read the statements: Input/output error\n", err.toString())
        // Inside a transaction too, under --continue: the input can't be read on, so the run ends, rolled back, as it would outside one.
        val inTransaction = object : java.io.Reader() {
            val text = ":begin\nRETURN 1 AS a;\n"
            var at = 0
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (at >= text.length) throw java.io.IOException("Input/output error")
                cbuf[off] = text[at++]
                return 1
            }
            override fun close() {}
        }
        val err2 = java.io.StringWriter()
        assertEquals(1, Cli(emptyMap(), java.io.StringWriter(), err2, stdin = { inTransaction }).run(memory + listOf("--format", "json", "--continue")))
        assertEquals("drivine: can't read the statements: Input/output error\n", err2.toString())
        val missing = runCli(memory + listOf("-f", "nope.cypher"), dir = dir.toFile())
        assertEquals(CliRun(2, "", "drivine: can't read -f nope.cypher: no such file, or not one this user can read\n"), missing)
    }

    @Test
    fun `under --continue a failure inside a transaction rolls it back, the statements after it run on their own, and commit then fails`() {
        val dir = kotlin.io.path.createTempDirectory("drivine-cli-").toFile()
        try {
            val db = listOf("-a", "sqlite:${java.io.File(dir, "g.db")}", "--format", "plain")
            val run = runCli(db + listOf("--continue"), stdin = ":begin\nCREATE (:Gone);\nRETURN 1/0;\nCREATE (:Kept);\n:commit\nMATCH (n) RETURN labels(n)[0] AS l;\n")
            assertEquals(1, run.exit, run.stderr)
            assertEquals("l\n\"Kept\"\n", run.stdout)
            assertTrue("no transaction is open" in run.stderr, run.stderr)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `under --continue a command that fails inside a transaction leaves it open, so commit keeps what it wrote`() {
        val dir = kotlin.io.path.createTempDirectory("drivine-cli-").toFile()
        try {
            val db = listOf("-a", "sqlite:${java.io.File(dir, "g.db")}", "--format", "plain")
            val run = runCli(db + listOf("--continue"), stdin = ":begin\nCREATE (:Kept);\n:nope\n:commit\nMATCH (n:Kept) RETURN count(n) AS c;\n")
            assertEquals(1, run.exit, run.stderr)
            assertEquals("c\n1\n", run.stdout)
            assertTrue("Could not find command :nope" in run.stderr, run.stderr)
            assertTrue("no transaction is open" !in run.stderr, run.stderr)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `under --continue a param of none of the forms inside a transaction leaves it open, as it reaches no store`() {
        val dir = kotlin.io.path.createTempDirectory("drivine-cli-").toFile()
        try {
            val db = listOf("-a", "sqlite:${java.io.File(dir, "g.db")}", "--format", "plain")
            val run = runCli(db + listOf("--continue"), stdin = ":begin\nCREATE (:Kept);\n:param nope\n:commit\nMATCH (n:Kept) RETURN count(n) AS c;\n")
            assertEquals(1, run.exit, run.stderr)
            assertEquals("c\n1\n", run.stdout)
            assertTrue(":param takes" in run.stderr, run.stderr)
            assertTrue("no transaction is open" !in run.stderr, run.stderr)
            // Nor does a map form whose value the store gives, but isn't a map: the store evaluated it, and is as it was.
            val notMap = runCli(db + listOf("--continue"), stdin = ":begin\nCREATE (:Kept);\n:param {a: 1}['a']\n:commit\nMATCH (n:Kept) RETURN count(n) AS c;\n")
            assertEquals("c\n2\n", notMap.stdout, notMap.stderr)
            assertTrue("a parameter map is a map" in notMap.stderr, notMap.stderr)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a store that can't be reached as a transaction begins, or as a -P is evaluated, exits with 3`() {
        // Port 1 on this machine refuses the connection, so no store is needed, and none is started.
        val begin = runCli(listOf("-a", "bolt://localhost:1", "--continue"), mapOf("DRIVINE_USER" to "u", "DRIVINE_PASSWORD" to "p"), stdin = ":begin\nRETURN 1 AS a;\n")
        assertEquals(3, begin.exit, begin.stderr)
        assertEquals("", begin.stdout)
        assertTrue("can't connect" in begin.stderr, begin.stderr)
        val parameter = runCli(listOf("-a", "bolt://localhost:1", "-P", "a => 1", "RETURN 1"), mapOf("DRIVINE_USER" to "u", "DRIVINE_PASSWORD" to "p"))
        assertEquals(3, parameter.exit, parameter.stderr)
        assertTrue("can't connect" in parameter.stderr, parameter.stderr)
    }

    @Test
    fun `a second begin fails, exit inside a transaction rolls it back, and a source file runs in the transaction open`() {
        val dir = kotlin.io.path.createTempDirectory("drivine-cli-").toFile()
        try {
            val db = listOf("-a", "sqlite:${java.io.File(dir, "g.db")}", "--format", "plain")
            val twice = runCli(db, stdin = ":begin\n:begin\n")
            assertEquals(1, twice.exit)
            assertTrue("open already" in twice.stderr, twice.stderr)
            assertEquals(0, runCli(db, stdin = ":begin\nCREATE (:Gone);\n:exit\n:commit\n").exit)
            java.io.File(dir, "s.cypher").writeText("CREATE (:Sourced);\n:rollback\n")
            assertEquals(CliRun(0, "c\n0\n", ""), runCli(db, stdin = ":begin\n:source s.cypher\nMATCH (n) RETURN count(n) AS c;\n", dir = dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a source file's inputs run in place, so its begin and commit are the run's, and one that sources itself fails`() {
        val dir = kotlin.io.path.createTempDirectory("drivine-cli-").toFile()
        try {
            val db = listOf("-a", "sqlite:${java.io.File(dir, "g.db")}", "--format", "plain")
            // The statement after the file's :commit runs too, outside the transaction.
            java.io.File(dir, "commit.cypher").writeText("CREATE (:A);\n:commit\nCREATE (:B);\n")
            assertEquals(CliRun(0, "c\n2\n", ""), runCli(db, stdin = ":begin\n:source commit.cypher\nMATCH (n) RETURN count(n) AS c;\n", dir = dir))
            // A transaction the file opens stays open after it, for the input that named it to commit.
            java.io.File(dir, "begin.cypher").writeText(":begin\nCREATE (:C);\n")
            assertEquals(CliRun(0, "c\n1\n", ""), runCli(db, stdin = ":source begin.cypher\n:commit\nMATCH (n:C) RETURN count(n) AS c;\n", dir = dir))
            // One byte-order mark at the start of a :source file isn't part of its text, as the contract's "Files" says.
            java.io.File(dir, "mark.cypher").writeText("\uFEFF:param x => 1\nRETURN \$x AS x;\n")
            assertEquals(CliRun(0, "x\n1\n", ""), runCli(db, stdin = ":source mark.cypher\n", dir = dir))
            java.io.File(dir, "self.cypher").writeText("RETURN 1 AS one;\n:source ./self.cypher\n")
            val self = runCli(db + listOf("--continue"), stdin = ":source self.cypher\nRETURN 2 AS two;\n", dir = dir)
            assertEquals(CliRun(1, "one\n1\ntwo\n2\n", "drivine: :source ./self.cypher is being read already, so it would never end\n"), self)
            // The -f file is being read too, so one that sources itself fails as well.
            val selfFile = runCli(db + listOf("-f", "self.cypher"), dir = dir)
            assertEquals(CliRun(1, "one\n1\n", "drivine: :source ./self.cypher is being read already, so it would never end\n"), selfFile)
            assertEquals(CliRun(1, "", "drivine: :source takes a file\n"), runCli(db, stdin = ":source\n", dir = dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `every statement is given the parameters, one that doesn't use them too, and a -P that fails runs nothing, under --continue too`() {
        assertEquals(CliRun(0, "b\n2\n", ""), runCli(memory + listOf("--format", "plain", "-P", "a => 1", "RETURN 2 AS b")))
        val failed = runCli(memory + listOf("--format", "plain", "--continue", "-P", "a => 1/0", "-P", "b => 2", "RETURN 3 AS c"))
        assertEquals(1, failed.exit)
        assertEquals("", failed.stdout)
        val notMap = runCli(memory, stdin = ":param {a: 1}\n:param [1]\n")
        assertEquals(CliRun(1, "", "drivine: :param takes NAME => EXPRESSION, NAME=EXPRESSION or a map, {NAME: EXPRESSION}\n"), notMap)
        assertEquals(CliRun(1, "", "drivine: a parameter map is a map, as in {limit: 10}\n"), runCli(memory, stdin = ":param {a: 1}\n:param {a: 1}['a']\n"))
        assertEquals(CliRun(1, "", "drivine: :params takes nothing, or clear\n"), runCli(memory, stdin = ":params all\n"))
        assertEquals(CliRun(1, "", "drivine: no transaction is open: :begin opens one\n"), runCli(memory, stdin = ":rollback\n"))
    }

    @Test
    fun `params prints none as an empty map and a text in single quotes, and a parameter set later replaces one, in its place`() {
        assertEquals(CliRun(0, "{}\n", ""), runCli(memory, stdin = ":params\n"))
        assertEquals(
            CliRun(0, "{\n  a: 'it\\'s',\n  b: [1, 'x'],\n  c: TRUE\n}\n", ""),
            runCli(memory, stdin = ":param a => 1\n:param b => [1, 'x']\n:param c => true\n:param a => \"it's\"\n:params\n"),
        )
        val bad = runCli(memory, stdin = ":param nope\n")
        assertEquals(1, bad.exit)
        assertTrue(":param takes" in bad.stderr, bad.stderr)
        // A parameter's expression may use one set before it.
        assertEquals(CliRun(0, "b\n3\n", ""), runCli(memory + listOf("--format", "plain", "-P", "a => 1", "-P", "b => \$a + 2", "RETURN \$b AS b")))
        assertTrue(":help" in runCli(memory, stdin = ":help\n").stdout)
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
