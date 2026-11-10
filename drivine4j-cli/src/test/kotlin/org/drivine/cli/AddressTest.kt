package org.drivine.cli

import org.drivine.connection.DatabaseType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressTest {

    private fun parse(text: String, type: StoreType? = null, fromCommandLine: Boolean = true) = Address.parse(text, type, fromCommandLine)

    private fun refused(text: String, type: StoreType? = null): String = assertFailsWith<BadInvocation> { parse(text, type) }.message!!

    @Test
    fun `each scheme names its store`() {
        assertEquals(Address(StoreType.NEO4J, "neo4j", "h", 7687), parse("neo4j://h:7687"))
        assertEquals(Address(StoreType.NEO4J, "neo4j+s", "h", null), parse("neo4j+s://h"))
        assertEquals(Address(StoreType.NEO4J, "bolt+ssc", "h", 1), parse("bolt+ssc://h:1"))
        assertEquals(Address(StoreType.MEMGRAPH, "bolt", "h", 7687), parse("memgraph://h:7687"))
        assertEquals(Address(StoreType.FALKORDB, null, "h", 6379), parse("falkordb://h:6379"))
        assertEquals(Address(StoreType.POSTGRES, null, "h", 5432, "graphs"), parse("postgresql://h:5432/graphs"))
        assertEquals(Address(StoreType.POSTGRES, null, "h", null, null), parse("postgres://h"))
        assertEquals(Address(StoreType.ORACLE, null, "h", 1521, "FREEPDB1"), parse("oracle://h:1521/FREEPDB1"))
        assertEquals(Address(StoreType.SQLITE, database = "graph.db"), parse("sqlite:graph.db"))
        assertEquals(Address(StoreType.SQLITE, database = "/tmp/a b.db"), parse("sqlite:/tmp/a b.db"))
    }

    @Test
    fun `sqlite colon memory is a database of the run's own`() {
        val memory = parse("sqlite::memory:")
        assertEquals(":memory:", memory.database)
        assertTrue(memory.inMemory)
        assertTrue(!parse("sqlite:x.db").inMemory)
    }

    @Test
    fun `only a bolt address leaves the store to --type, and a type the scheme contradicts is refused`() {
        assertEquals(StoreType.NEO4J, parse("bolt://h:1").store)
        assertEquals(StoreType.NEO4J, parse("bolt://h:1", StoreType.NEO4J).store)
        assertEquals(StoreType.MEMGRAPH, parse("bolt://h:1", StoreType.MEMGRAPH).store)
        assertEquals("bolt+s", parse("bolt+s://h:1", StoreType.MEMGRAPH).protocol)
        assertEquals(StoreType.POSTGRES, parse("postgresql://h/db", StoreType.POSTGRES).store)
        assertTrue("contradicts" in refused("postgresql://h/db", StoreType.NEO4J))
        assertTrue("contradicts" in refused("neo4j://h", StoreType.MEMGRAPH))
        assertTrue("contradicts" in refused("sqlite::memory:", StoreType.POSTGRES))
        assertTrue("Bolt" in refused("bolt://h", StoreType.POSTGRES))
    }

    @Test
    fun `the address's user is taken, and its password only from off the command line`() {
        assertEquals("u", parse("postgresql://u@h/db").user)
        assertNull(parse("postgresql://u@h/db").password)
        assertEquals(Args.PASSWORD_REFUSED, refused("postgresql://u:secret@h/db"))
        val kept = parse("postgresql://u:s%40cret@h/db", fromCommandLine = false)
        assertEquals("u", kept.user)
        assertEquals("s@cret", kept.password)
        assertTrue("secret" !in kept.toString())
    }

    @Test
    fun `a refusal of DRIVINE_ADDRESS's address doesn't print its password`() {
        fun refusedFromEnv(text: String, type: StoreType? = null) = assertFailsWith<BadInvocation> { parse(text, type, fromCommandLine = false) }.message!!
        assertEquals("drivine: the address postgresql://u:***@h:x/db has no port number after its host", refusedFromEnv("postgresql://u:secret@h:x/db"))
        assertEquals("drivine: a neo4j address names no database: neo4j://u:***@h/db", refusedFromEnv("neo4j://u:s@cret@h/db"))
        assertTrue("secret" !in refusedFromEnv("postgresql://u:secret@h/db", StoreType.NEO4J))
        assertTrue("secret" !in refusedFromEnv("postgresql://u:sec ret@h/db"))
        assertTrue("u@h" in refusedFromEnv("neo4j://u@h/db"))
        // A password that holds a `/`, unescaped, is masked to its last `@` too.
        assertTrue("cret" !in refusedFromEnv("postgresql://u:se/cret@h/db"), refusedFromEnv("postgresql://u:se/cret@h/db"))
    }

    @Test
    fun `a host with a _ in its name, as a Docker Compose service's, is read with its port and user`() {
        assertEquals(Address(StoreType.POSTGRES, null, "pg_main", 5432, "graphs", "u"), parse("postgresql://u@pg_main:5432/graphs"))
        assertEquals(Address(StoreType.NEO4J, "neo4j", "neo4j_db", null), parse("neo4j://neo4j_db"))
        assertEquals(Args.PASSWORD_REFUSED, refused("postgresql://u:secret@pg_main/graphs"))
        assertEquals("secret", parse("postgresql://u:secret@pg_main/graphs", fromCommandLine = false).password)
        assertTrue("no port number" in refused("neo4j://neo4j_db:x"))
        assertTrue("names no host" in refused("neo4j://:7687"))
    }

    @Test
    fun `an address that names no store, no host or a database where none is named is refused`() {
        assertTrue("names no store" in refused("http://h"))
        assertTrue("no scheme" in refused("h/db"))
        assertTrue("names no store" in refused("localhost:7687"))
        assertTrue("names no host" in refused("neo4j:///x"))
        assertTrue("SQLite's file" in refused("sqlite:"))
        assertTrue("names no database" in refused("neo4j://h/db"))
        assertTrue("names no database" in refused("memgraph://h/db"))
        assertTrue("names no database" in refused("falkordb://h/graph"))
        assertTrue("Oracle's service" in refused("oracle://h:1521"))
        // An option such as sslmode=require isn't read, so it isn't dropped without a word either.
        assertTrue("?query" in refused("postgresql://h/db?sslmode=require"))
        assertTrue("?query" in refused("neo4j://h:7687?policy=eu"))
        assertTrue("?query" in refused("postgresql://h/db#x"))
    }

    @Test
    fun `each address gives the connection properties Drivine is built from`() {
        fun properties(address: String, user: String? = null, password: String? = null) =
            Store.properties(Plan(parse(address, fromCommandLine = false), "RETURN 1", Format.JSON, user, password))

        properties("neo4j+s://h:1", "neo", "pw").let {
            assertEquals(DatabaseType.NEO4J, it.type)
            assertEquals("h", it.host)
            assertEquals(1, it.port)
            assertEquals("neo4j+s", it.protocol)
            assertEquals("neo", it.userName)
            assertEquals("pw", it.password)
            assertNull(it.databaseName)
        }
        // No user is an empty one, which the store refuses, not the CLI.
        assertEquals("", properties("bolt://h").userName)
        assertEquals(DatabaseType.MEMGRAPH, properties("memgraph://h").type)
        properties("falkordb://h:6379", "u", "pw").let {
            assertEquals(DatabaseType.FALKORDB, it.type)
            assertNull(it.userName)
            assertEquals("pw", it.password)
        }
        assertEquals("graphs", properties("postgresql://h/graphs", "u").databaseName)
        assertEquals("u", properties("postgresql://h", "u").databaseName)
        assertEquals("FREEPDB1", properties("oracle://h/FREEPDB1", "u").databaseName)
        properties("sqlite:graph.db", "u", "pw").let {
            assertEquals(DatabaseType.SQLITE, it.type)
            assertEquals("graph.db", it.databaseName)
            assertNull(it.userName)
            assertNull(it.password)
        }
    }
}
