package org.drivine.cli

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract's "Connecting", short of a store: where the address, the database, the graph, the user and the password
 * come from, in what order, the profiles of `.drivine.yml`, and the password prompt. The cases of `cli.yaml` drive the
 * same against each store.
 */
class ConnectingTest {

    private val home: File = Files.createTempDirectory("drivine-home-").toFile()
    private val dir: File = Files.createTempDirectory("drivine-dir-").toFile()

    @AfterEach
    fun cleanUp() {
        home.deleteRecursively()
        dir.deleteRecursively()
    }

    /** Writes [text] as `.drivine.yml` in [where], with [mode], in octal. */
    private fun profiles(where: File, text: String, mode: String = "600") {
        val file = File(where, Profiles.FILE).also { it.writeText(text) }
        val digits = mode.map { it.digitToInt() }
        val permissions = digits.joinToString("") { d -> "${if (d and 4 != 0) 'r' else '-'}${if (d and 2 != 0) 'w' else '-'}${if (d and 1 != 0) 'x' else '-'}" }
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions))
    }

    private fun plan(vararg args: String, env: Map<String, String> = emptyMap()): Plan =
        (Args.parse(args.toList() + "RETURN 1") as Parsed.Run).invocation.check(env, dir, home = home)

    private fun refused(vararg args: String, env: Map<String, String> = emptyMap()): String =
        assertFailsWith<BadInvocation> { plan(*args, env = env) }.message!!

    @Test
    fun `the address comes from -a, then DRIVINE_ADDRESS, then the profile`() {
        profiles(dir, "default: p\nprofiles:\n  p:\n    address: \"postgresql://profile/db\"\n")
        assertEquals("flag", plan("-a", "postgresql://flag/db", env = mapOf("DRIVINE_ADDRESS" to "postgresql://env/db")).address.host)
        assertEquals("env", plan(env = mapOf("DRIVINE_ADDRESS" to "postgresql://env/db")).address.host)
        assertEquals("profile", plan().address.host)
        assertEquals("p", plan().profile)
    }

    @Test
    fun `with no address from any of them, the invocation is refused and says where to give one`() {
        assertTrue("-a ADDRESS, DRIVINE_ADDRESS or a profile" in refused())
    }

    @Test
    fun `the database is the address's where it names one, then -d's, then DRIVINE_DATABASE's, then the profile's`() {
        profiles(dir, "default: p\nprofiles:\n  p:\n    database: from_profile\n")
        assertEquals("in_address", plan("-a", "postgresql://h/in_address", "-d", "flag").database)
        assertEquals("flag", plan("-a", "postgresql://h", "-d", "flag", env = mapOf("DRIVINE_DATABASE" to "env")).database)
        assertEquals("env", plan("-a", "postgresql://h", env = mapOf("DRIVINE_DATABASE" to "env")).database)
        assertEquals("from_profile", plan("-a", "postgresql://h").database)
        assertEquals("from_profile", plan("-a", "neo4j://h").database)
    }

    @Test
    fun `Oracle's service comes from the address or -d, and with neither the invocation is refused`() {
        assertEquals("FREEPDB1", plan("-a", "oracle://h:1521", "-d", "FREEPDB1").database)
        assertEquals("FREEPDB1", Store.properties(plan("-a", "oracle://h:1521", "-d", "FREEPDB1")).databaseName)
        assertTrue("Oracle's service" in refused("-a", "oracle://h:1521"))
    }

    @Test
    fun `the graph comes from -g, then DRIVINE_GRAPH, then the profile, and is the SQL stores' graph and FalkorDB's`() {
        // None given: the store's default graph.
        assertNull(Store.properties(plan("-a", "postgresql://h/db")).defaultGraphPath)
        profiles(dir, "default: p\nprofiles:\n  p:\n    graph: from_profile\n")
        assertEquals("flag", plan("-a", "sqlite::memory:", "-g", "flag", env = mapOf("DRIVINE_GRAPH" to "env")).graph)
        assertEquals("env", plan("-a", "sqlite::memory:", env = mapOf("DRIVINE_GRAPH" to "env")).graph)
        assertEquals("from_profile", plan("-a", "sqlite::memory:").graph)
        assertEquals("g", Store.properties(plan("-a", "postgresql://h/db", "-g", "g")).defaultGraphPath)
        val falkor = Store.properties(plan("-a", "falkordb://h", "-g", "g"))
        assertEquals("g" to null, falkor.databaseName to falkor.defaultGraphPath)
    }

    @Test
    fun `a database or a graph the store has none of is refused, however it's given`() {
        assertTrue("-a sqlite:path names the file" in refused("-a", "sqlite::memory:", "-d", "x"))
        assertTrue("-a sqlite:path names the file" in refused("-a", "sqlite::memory:", env = mapOf("DRIVINE_DATABASE" to "x")))
        assertTrue("--graph names the graph" in refused("-a", "falkordb://h", "--database", "x"))
        assertTrue("--database names the database" in refused("-a", "neo4j://h", "-g", "x"))
        assertTrue("--database names the database" in refused("-a", "memgraph://h", env = mapOf("DRIVINE_GRAPH" to "x")))
        profiles(dir, "default: p\nprofiles:\n  p:\n    graph: x\n")
        assertTrue("a profile's graph" in refused("-a", "bolt://h"))
    }

    @Test
    fun `the user comes from -u, then the address, then DRIVINE_USER, then the profile`() {
        profiles(dir, "default: p\nprofiles:\n  p:\n    user: from_profile\n")
        val env = mapOf("DRIVINE_USER" to "env")
        assertEquals("flag", plan("-a", "postgresql://addr@h/db", "-u", "flag", env = env).user)
        assertEquals("addr", plan("-a", "postgresql://addr@h/db", env = env).user)
        assertEquals("env", plan("-a", "postgresql://h/db", env = env).user)
        assertEquals("from_profile", plan("-a", "postgresql://h/db").user)
    }

    @Test
    fun `the password comes from DRIVINE_PASSWORD, then an address kept off the command line, then the profile`() {
        profiles(dir, "default: p\nprofiles:\n  p:\n    password: from_profile\n")
        val env = mapOf("DRIVINE_ADDRESS" to "postgresql://u:in_address@h/db")
        assertEquals("env", plan(env = env + ("DRIVINE_PASSWORD" to "env")).password)
        assertEquals("in_address", plan(env = env).password)
        assertEquals("from_profile", plan("-a", "postgresql://u@h/db").password)
    }

    @Test
    fun `a profile's address may hold a password, which isn't the command line's`() {
        profiles(dir, "default: p\nprofiles:\n  p:\n    address: \"postgresql://u:secret@h/db\"\n")
        assertEquals("secret", plan().password)
    }

    @Test
    fun `the working directory's profile replaces the home's of the same name as a whole, and its default the home's`() {
        profiles(home, "default: a\nprofiles:\n  a:\n    address: \"postgresql://home_a/db\"\n    user: home_user\n  b:\n    address: \"postgresql://home_b/db\"\n")
        assertEquals("home_a" to "home_user", plan().let { it.address.host to it.user })
        profiles(dir, "default: b\nprofiles:\n  a:\n    address: \"postgresql://dir_a/db\"\n")
        assertEquals("home_b", plan().address.host)
        val a = plan("--profile", "a")
        assertEquals("dir_a" to null, a.address.host to a.user)
    }

    @Test
    fun `a profile's type names the store of a bolt address, and one the address contradicts is refused`() {
        profiles(dir, "profiles:\n  m:\n    address: \"bolt://h:7687\"\n    type: memgraph\n  bad:\n    address: \"bolt://h\"\n    type: mongo\n")
        assertEquals(StoreType.MEMGRAPH, plan("--profile", "m").address.store)
        assertEquals(StoreType.NEO4J, plan("--profile", "m", "--type", "neo4j").address.store)
        assertTrue("isn't a store" in refused("--profile", "bad"))
    }

    @Test
    fun `a profile file that is bad is refused, and names no secret it holds`() {
        profiles(dir, "profiles:\n  p:\n    address: \"sqlite::memory:\"\n    password: \"hunter2\n")
        val bad = refused("--profile", "p")
        assertTrue("isn't YAML" in bad && "hunter2" !in bad, bad)
        profiles(dir, "profiles:\n  p: [1, 2]\n")
        assertTrue("isn't a map of keys" in refused("--profile", "p"))
        profiles(dir, "profiles:\n  p:\n    address: [a]\n")
        assertTrue("isn't a value" in refused("--profile", "p"))
    }

    @Test
    fun `a default that names no profile is refused, and a file with no default and no --profile picks none`() {
        profiles(dir, "default: nope\nprofiles:\n  p:\n    address: \"sqlite::memory:\"\n")
        assertTrue("names no profile" in refused())
        profiles(dir, "profiles:\n  p:\n    address: \"sqlite::memory:\"\n")
        assertTrue("-a ADDRESS, DRIVINE_ADDRESS or a profile" in refused())
    }

    @Test
    fun `a file holding a password that others can read is refused, whichever profile holds it, and one of mode 600 isn't`() {
        val text = "profiles:\n  p:\n    address: \"sqlite::memory:\"\n  q:\n    password: x\n"
        profiles(dir, text, mode = "640")
        val refusal = refused("--profile", "p")
        assertTrue("chmod 600 ${File(dir, Profiles.FILE)}" in refusal, refusal)
        profiles(dir, text, mode = "600")
        assertEquals("p", plan("--profile", "p").profile)
        // One with no password may be read by others.
        profiles(dir, "profiles:\n  p:\n    address: \"sqlite::memory:\"\n", mode = "644")
        assertEquals("p", plan("--profile", "p").profile)
    }

    @Test
    fun `nothing prints a password a profile gives`() {
        profiles(dir, "default: p\nprofiles:\n  p:\n    address: \"postgresql://u:in_address@h/db\"\n    password: in_profile\n")
        val plan = plan()
        assertTrue("in_address" !in plan.toString() && "in_profile" !in plan.toString(), plan.toString())
        val profile = Profiles.pick(null, home, dir)!!
        assertTrue("in_address" !in profile.toString() && "in_profile" !in profile.toString(), profile.toString())
    }

    /** A run of the CLI on a terminal's stdin, with [ask] as its prompt for a password: what was asked for, by user, and the exit code. */
    private fun prompted(vararg args: String, env: Map<String, String> = emptyMap(), answer: String? = "typed"): Pair<List<String?>, Int> {
        val asked = mutableListOf<String?>()
        val err = StringWriter()
        val exit = Cli(env, StringWriter(), err, stdinTerminal = true, dir = dir, askPassword = { user -> asked += user; answer }).run(args.toList())
        return asked to exit
    }

    @Test
    fun `where stdin is a terminal and nothing gives a password, it's asked for, once the invocation is checked`() {
        // An address no store listens on: the prompt comes before the CLI connects, which then fails.
        val (asked, exit) = prompted("-a", "postgresql://localhost:1/db", "-u", "who", "RETURN 1")
        assertEquals(listOf<String?>("who"), asked)
        assertEquals(ExitCode.UNREACHABLE, exit)
        // Given, it isn't asked for; SQLite takes none; a bad invocation asks nothing.
        assertEquals(emptyList(), prompted("-a", "postgresql://localhost:1/db", "RETURN 1", env = mapOf("DRIVINE_PASSWORD" to "x")).first)
        assertEquals(emptyList<String?>() to ExitCode.OK, prompted("-a", "sqlite::memory:", "RETURN 1"))
        assertEquals(emptyList<String?>() to ExitCode.BAD_INVOCATION, prompted("-a", "sqlite::memory:", "-d", "x", "RETURN 1"))
    }

    @Test
    fun `where stdin isn't a terminal, nothing is asked`() {
        val asked = mutableListOf<String?>()
        Cli(emptyMap(), StringWriter(), StringWriter(), stdinTerminal = false, dir = dir, askPassword = { asked += it; "x" })
            .run(listOf("-a", "postgresql://localhost:1/db", "RETURN 1"))
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `HOME names the home whose profiles are read`() {
        profiles(home, "default: p\nprofiles:\n  p:\n    address: \"sqlite::memory:\"\n")
        val out = StringWriter()
        val exit = Cli(mapOf("HOME" to home.path), out, StringWriter(), dir = dir).run(listOf("--format", "plain", "RETURN 1 AS one"))
        assertEquals(ExitCode.OK to "one\n1\n", exit to out.toString())
        assertEquals(ExitCode.BAD_INVOCATION, Cli(emptyMap(), StringWriter(), StringWriter(), dir = dir).run(listOf("RETURN 1")))
    }
}
