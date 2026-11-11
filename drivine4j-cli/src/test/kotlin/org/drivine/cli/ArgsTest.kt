package org.drivine.cli

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArgsTest {

    private fun run(vararg args: String): Invocation = (Args.parse(args.toList()) as Parsed.Run).invocation

    private fun refused(vararg args: String): String = assertFailsWith<BadInvocation> { Args.parse(args.toList()) }.message!!

    @Test
    fun `reads the address, the statement, the user, the type and the format`() {
        assertEquals(
            Invocation(address = "bolt://h:1", inputs = listOf("RETURN 1"), user = "neo", type = StoreType.MEMGRAPH, format = Format.JSON),
            run("-a", "bolt://h:1", "--user", "neo", "--type", "memgraph", "--format", "json", "RETURN 1"),
        )
        assertEquals(Invocation(address = "x", inputs = listOf("RETURN 2"), user = "u"), run("--address", "x", "-c", "RETURN 2", "--username", "u"))
        assertEquals(Invocation(user = "u", format = Format.PLAIN), run("-u", "u", "--format=plain"))
    }

    @Test
    fun `a later -a or -u replaces an earlier one, and each statement is an input`() {
        assertEquals(Invocation(address = "b", user = "y", inputs = listOf("RETURN 1", "RETURN 2")), run("-a", "a", "-u", "x", "-a", "b", "-u", "y", "RETURN 1", "-c", "RETURN 2"))
    }

    @Test
    fun `help and version end the run, before anything after them is read`() {
        assertEquals(Parsed.Help, Args.parse(listOf("-h", "--nope")))
        assertEquals(Parsed.Help, Args.parse(listOf("RETURN 1", "--help")))
        assertEquals(Parsed.Version, Args.parse(listOf("-v")))
        assertEquals(Parsed.Version, Args.parse(listOf("--version", "-p")))
    }

    @Test
    fun `the first flag that ends the run ends it`() {
        assertTrue("--nope" in refused("--nope", "-p", "x"))
        assertEquals(Args.PASSWORD_REFUSED, refused("-p", "x", "--nope"))
        assertTrue("--nope" in refused("--format", "json", "--nope", "--help"))
    }

    @Test
    fun `a password on the command line is refused with the contract's line`() {
        assertEquals(Args.PASSWORD_REFUSED, refused("-u", "x", "-p", "y", "RETURN 1"))
        assertEquals(Args.PASSWORD_REFUSED, refused("--password", "y"))
        assertEquals(Args.PASSWORD_REFUSED, refused("--password=y"))
        // Attached, as getopt and cypher-shell read -psecret, it is refused as -p, and the password isn't printed back.
        assertEquals(Args.PASSWORD_REFUSED, refused("-psecret", "RETURN 1"))
        assertEquals(Args.PASSWORD_REFUSED, refused("--passwordsecret", "RETURN 1"))
        // An unknown flag is named, never what follows it, and a value that isn't a word isn't printed back.
        assertTrue("secret" !in refused("-xpostgresql://u:secret@h/db", "RETURN 1"))
        assertTrue("secret" !in refused("--addresses=postgresql://u:secret@h/db", "RETURN 1"))
        assertTrue("secret" !in refused("--type", "postgresql://u:secret@h/db", "RETURN 1"))
        assertTrue("secret" !in refused("--format", "postgresql://u:secret@h/db", "RETURN 1"))
        assertEquals("drivine: unknown flag -x; see --help", refused("-xpostgresql://u:secret@h/db", "RETURN 1"))
        // -a with its value attached isn't read, and its address, password and all, isn't printed back.
        for (attached in listOf("-apostgresql://u:secret@h/db", "-a=postgresql://u:secret@h/db")) {
            assertEquals("drivine: give -a's value as the next argument, as in -a VALUE; see --help", refused(attached, "RETURN 1"))
        }
        assertEquals(
            "drivine: a password on the command line shows in ps and in your shell history; use DRIVINE_PASSWORD, a profile or the prompt.",
            Args.PASSWORD_REFUSED,
        )
    }

    @Test
    fun `an unknown flag, or one of the contract's that comes later, is named`() {
        assertTrue("--change-password" in refused("--change-password"))
        assertTrue("--explain" in refused("--explain"))
        assertTrue("comes later" in refused("--profile", "p"))
    }

    @Test
    fun `a flag's missing or bad value is refused`() {
        assertTrue("-a needs a value" in refused("-a"))
        assertTrue("-c needs a value" in refused("RETURN 1", "-c"))
        assertTrue("xml" in refused("--format", "xml"))
        assertTrue("verbose" in refused("--format", "verbose"))
        assertTrue("give auto, table, plain, json or csv" in refused("--format", "xml"))
        // table's own flags come later: refused, and said so.
        assertTrue("--sample-rows comes later" in refused("--format", "table", "--sample-rows", "10", "RETURN 1"))
        assertTrue("--wrap comes later" in refused("--format", "table", "--wrap", "false", "RETURN 1"))
        assertTrue("isn't a store" in refused("--type", "neptune"))
        assertTrue("takes no value" in refused("--help=yes"))
        // A short flag with something attached is named as itself, not as an unknown flag.
        assertEquals("drivine: -v takes no value; see --help", refused("-vx"))
        assertEquals("drivine: -h takes no value; see --help", refused("-h=x"))
        assertEquals("drivine: --continue takes no value; see --help", refused("--continue=yes"))
        assertEquals("drivine: -f needs a value; see --help", refused("-f"))
    }

    @Test
    fun `after the flags, two inputs, a file that can't be read, no input on a terminal and no address are refused in that order`() {
        fun refusedAfter(vararg args: String, terminal: Boolean = false) =
            assertFailsWith<BadInvocation> { run(*args).check(emptyMap(), stdinTerminal = terminal) }.message!!
        assertTrue("not two" in refusedAfter("RETURN 1", "RETURN 2"))
        assertTrue("not two" in refusedAfter("-c", "RETURN 1", "-f", "x.cypher"))
        assertTrue("not two" in refusedAfter("-f", "x.cypher", "-f", "y.cypher"))
        // A file that can't be read is checked before the address: nothing could run.
        assertEquals("drivine: can't read -f nope.cypher: no such file, or not one this user can read", refusedAfter("-f", "nope.cypher"))
        assertTrue("the REPL comes later" in refusedAfter(terminal = true))
        assertTrue("DRIVINE_ADDRESS" in refusedAfter("RETURN 1"))
    }

    @Test
    fun `the statements are the text's, a file's, or stdin's where none is given, unless it's a terminal`() {
        val env = mapOf("DRIVINE_ADDRESS" to "sqlite::memory:")
        assertEquals(Source.Text("RETURN 1"), run("RETURN 1").check(env).source)
        assertEquals(Source.Text("RETURN 2"), run("-c", "RETURN 2").check(env).source)
        val dir = kotlin.io.path.createTempDirectory("drivine-args-").toFile()
        try {
            java.io.File(dir, "a.cypher").writeText("RETURN 1;")
            assertEquals(Source.File(java.io.File(dir, "a.cypher")), run("-f", "a.cypher").check(env, dir).source)
            assertEquals(Source.File(java.io.File(dir, "a.cypher")), run("--file=a.cypher").check(env, dir).source)
            // A directory is no file to read.
            assertFailsWith<BadInvocation> { run("-f", ".").check(env, dir) }
        } finally {
            dir.deleteRecursively()
        }
        assertEquals(Source.Stdin, run().check(env).source)
        assertEquals(Source.Stdin, run("--non-interactive").check(env, stdinTerminal = true).source)
        assertEquals(true, run("--continue", "RETURN 1").check(env).continueOnFailure)
    }

    @Test
    fun `the address is -a's, then DRIVINE_ADDRESS's`() {
        val env = mapOf("DRIVINE_ADDRESS" to "sqlite:env.db")
        assertEquals("env.db", run("RETURN 1").check(env).address.database)
        assertEquals("flag.db", run("-a", "sqlite:flag.db", "RETURN 1").check(env).address.database)
    }

    @Test
    fun `the user is -u's, then the address's, then DRIVINE_USER's, and the password DRIVINE_PASSWORD's, then the address's`() {
        val env = mapOf("DRIVINE_USER" to "env", "DRIVINE_PASSWORD" to "secret")
        assertEquals("flag", run("-u", "flag", "-a", "postgresql://addr@h/db", "RETURN 1").check(env).user)
        assertEquals("addr", run("-a", "postgresql://addr@h/db", "RETURN 1").check(env).user)
        assertEquals("env", run("-a", "postgresql://h/db", "RETURN 1").check(env).user)
        assertEquals("secret", run("-a", "postgresql://h/db", "RETURN 1").check(env).password)

        val kept = mapOf("DRIVINE_ADDRESS" to "postgresql://addr:kept@h/db")
        assertEquals("kept", run("RETURN 1").check(kept).password)
        assertEquals("secret", run("RETURN 1").check(kept + ("DRIVINE_PASSWORD" to "secret")).password)
    }

    @Test
    fun `each -P is kept in order, attached or after a long flag's equals sign, and neither a bad one's refusal nor the plan prints its expression`() {
        val parameters = run("-P", "a => 1", "-Pb=2", "--param={c: 3}", "RETURN 1").parameters
        assertEquals(listOf(Assignment.Named("a", "1"), Assignment.Named("b", "2"), Assignment.Map("{c: 3}")), parameters)
        assertEquals("drivine: -P takes NAME => EXPRESSION, NAME=EXPRESSION or a map, {NAME: EXPRESSION}; see --help", refused("-P", "secret"))
        val plan = run("-a", "sqlite::memory:", "-P", "pw => 'secret'", "-P", "{key: 'secret'}", "RETURN 1").check(emptyMap())
        assertTrue("secret" !in plan.toString(), plan.toString())
    }

    @Test
    fun `an address on the command line that holds a password is refused as -p is`() {
        assertEquals(Args.PASSWORD_REFUSED, assertFailsWith<BadInvocation> { run("-a", "postgresql://u:secret@localhost:1/db", "RETURN 1").check(emptyMap()) }.message)
    }

    @Test
    fun `every format of the contract is taken`() {
        val env = mapOf("DRIVINE_ADDRESS" to "sqlite::memory:")
        Format.entries.forEach { format -> assertEquals(format, run("--format", format.flag, "RETURN 1").check(env).format, format.flag) }
    }
}
