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
        assertEquals(
            "drivine: a password on the command line shows in ps and in your shell history; use DRIVINE_PASSWORD, a profile or the prompt.",
            Args.PASSWORD_REFUSED,
        )
    }

    @Test
    fun `an unknown flag, or one of the contract's that comes later, is named`() {
        assertTrue("--change-password" in refused("--change-password"))
        assertTrue("-f" in refused("-f", "x.cypher"))
        assertTrue("comes later" in refused("--profile", "p"))
    }

    @Test
    fun `a flag's missing or bad value is refused`() {
        assertTrue("-a needs a value" in refused("-a"))
        assertTrue("-c needs a value" in refused("RETURN 1", "-c"))
        assertTrue("xml" in refused("--format", "xml"))
        assertTrue("verbose" in refused("--format", "verbose"))
        assertTrue("comes later" in refused("--format", "table"))
        assertTrue("comes later" in refused("--format", "csv"))
        assertTrue("isn't a store" in refused("--type", "neptune"))
        assertTrue("takes no value" in refused("--help=yes"))
    }

    @Test
    fun `after the flags, two inputs, no input and no address are refused in that order`() {
        assertTrue("one statement" in assertFailsWith<BadInvocation> { run("RETURN 1", "RETURN 2").check(emptyMap()) }.message!!)
        assertTrue("give a statement" in assertFailsWith<BadInvocation> { run().check(emptyMap()) }.message!!)
        assertTrue("DRIVINE_ADDRESS" in assertFailsWith<BadInvocation> { run("RETURN 1").check(emptyMap()) }.message!!)
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
    fun `an address on the command line that holds a password is refused as -p is`() {
        assertEquals(Args.PASSWORD_REFUSED, assertFailsWith<BadInvocation> { run("-a", "postgresql://u:secret@localhost:1/db", "RETURN 1").check(emptyMap()) }.message)
    }
}
