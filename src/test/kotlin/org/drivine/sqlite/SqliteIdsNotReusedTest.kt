package org.drivine.sqlite

import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteBackend
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.connection.ReusedIdsException
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.PersistenceManagerType
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaManager
import org.drivine.transaction.TransactionContextHolder
import org.drivine.transaction.Transactions
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import sample.stateless.Claim
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A SQLite graph gives a deleted node's or relationship's id to nothing after it, as Oracle and Postgres give none
 * again (#713): its keys are `AUTOINCREMENT`. A file whose tables an earlier Drivine made, without it, is refused.
 */
class SqliteIdsNotReusedTest {

    private val pm: NonTransactionalPersistenceManager = SqliteTestDatabase.graphManager("reuse")

    private fun cypher(statement: String): List<Any?> = pm.query(QuerySpecification.withStatement(CypherStatement(statement)))

    private fun one(statement: String): Any? = cypher(statement).single()

    @BeforeEach
    fun clear() = SqliteTestDatabase.clear(pm)

    @Test
    fun `the tables are made with AUTOINCREMENT keys`() {
        val made = pm.query(
            QuerySpecification.withStatement("SELECT sql FROM sqlite_master WHERE name IN ('reuse_node', 'reuse_edge') ORDER BY name").transform(String::class.java)
        )
        assertEquals(2, made.size)
        assertContains(made[0], "eid   INTEGER PRIMARY KEY AUTOINCREMENT")
        assertContains(made[1], "nid    INTEGER PRIMARY KEY AUTOINCREMENT")
    }

    @Test
    fun `a node deleted, the one with the greatest id, gives its id to no node made after it, in another statement`() {
        cypher("CREATE (:Kept)")
        val deleted = one("CREATE (n:Gone) RETURN elementId(n)")
        cypher("MATCH (n:Gone) DELETE n")
        val made = one("CREATE (n:New) RETURN elementId(n)")
        assertNotEquals(deleted, made)
        assertEquals(0L, one("MATCH (n) WHERE elementId(n) = '$deleted' RETURN count(n)"))
    }

    @Test
    fun `a relationship deleted gives its id to no relationship made after it, in another statement`() {
        cypher("CREATE (:A)-[:Kept]->(:B)")
        val deleted = one("MATCH (a:A), (b:B) CREATE (a)-[r:Gone]->(b) RETURN elementId(r)")
        cypher("MATCH ()-[r:Gone]->() DELETE r")
        val made = one("MATCH (a:A), (b:B) CREATE (a)-[r:New]->(b) RETURN elementId(r)")
        assertNotEquals(deleted, made)
    }

    @Test
    fun `within one statement, a node deleted gives its id to none the statement makes after`() {
        cypher("CREATE (:Kept)")
        val row = one("CREATE (n:Gone) WITH elementId(n) AS deleted, n DELETE n CREATE (m:New) RETURN [deleted, elementId(m)]") as List<*>
        assertNotEquals(row[0], row[1])
        // TCK Merge1 #14: a MERGE after a DELETE in the same statement makes a node of its own.
        cypher("MATCH (n) DETACH DELETE n")
        cypher("CREATE (:Kept), (:Gone)")
        val merged = one("MATCH (n:Gone) WITH n, elementId(n) AS deleted DELETE n MERGE (m:Gone) RETURN [deleted, elementId(m)]") as List<*>
        assertNotEquals(merged[0], merged[1])
    }

    @Test
    fun `inside one transaction, a node deleted gives its id to none made after it`() {
        cypher("CREATE (:Kept)")
        val connection = SqliteTestDatabase.connections(pm).connect()
        fun query(statement: String) = connection.query(QuerySpecification.withStatement(CypherStatement(statement))).single()
        connection.startTransaction()
        try {
            val deleted = query("CREATE (n:Gone) RETURN elementId(n)")
            connection.query(QuerySpecification.withStatement(CypherStatement("MATCH (n:Gone) DELETE n")))
            val made = query("CREATE (n:New) RETURN elementId(n)")
            assertNotEquals(deleted, made)
        } finally {
            connection.rollbackTransaction()
            connection.release()
        }
    }

    @Test
    fun `clearing the graph keeps its ids counting from where they were, as on Postgres`() {
        val before = (one("CREATE (n:Gone) RETURN elementId(n)").toString()).toLong()
        SqliteTestDatabase.clear(pm)
        val after = (one("CREATE (n:New) RETURN elementId(n)").toString()).toLong()
        assertTrue(after > before, "$after after $before")
    }

    // ----- A file an earlier Drivine made -----

    /** A file with graph [graph]'s tables made as an earlier Drivine made them: keys without AUTOINCREMENT. */
    private fun earlierFile(directory: Path, graph: String, keepEdges: Boolean = false): String {
        val path = directory.resolve("earlier.db").toString()
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE ${graph}_node (nid INTEGER PRIMARY KEY, labels TEXT NOT NULL, props TEXT NOT NULL, stamp TEXT)")
                val key = if (keepEdges) "INTEGER PRIMARY KEY AUTOINCREMENT" else "INTEGER PRIMARY KEY"
                s.execute("CREATE TABLE ${graph}_edge (eid $key, src INTEGER NOT NULL, dst INTEGER NOT NULL, type TEXT NOT NULL, props TEXT NOT NULL DEFAULT '{}')")
                s.execute("INSERT INTO ${graph}_node (labels, props) VALUES ('[\"Person\"]', '{\"name\": \"Ada\"}')")
            }
        }
        return path
    }

    /** The file SQLite says the database at [path] is in, as the refusal names it. */
    private fun fileOf(path: String): String = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
        c.createStatement().use { s -> s.executeQuery(SqliteGraph.FILE).use { it.next(); it.getString(1) } }
    }

    private fun refusal(graph: String, file: String) =
        "The SQLite graph '$graph' in $file was made by an earlier Drivine, whose node and relationship ids could be " +
            "reused after a delete. Make a new file and save the graph into it again."

    @Test
    fun `a file whose graph an earlier Drivine made is refused on every connection, and nothing is made in it`(@TempDir directory: Path) {
        val path = earlierFile(directory, "drivine")
        // A pool of one: a refusal that kept the connection it looked on would leave the next attempt none to have.
        val provider = JdbcConnectionProvider.sqlite("earlier", path, poolMax = 1)
        try {
            repeat(3) {
                val refused = assertFailsWith<ReusedIdsException> { provider.connect() }
                assertEquals("drivine", refused.graph)
                assertEquals(fileOf(path), refused.path)
                assertContains(fileOf(path), "earlier.db")
                assertEquals(refusal("drivine", fileOf(path)), refused.message)
            }
        } finally {
            provider.end()
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('drivine_schema', 'drivine_edge_src')").use { it.next(); assertEquals(0, it.getInt(1)) }
            }
        }
    }

    @Test
    fun `a file whose table of nodes alone was made without AUTOINCREMENT is refused, and one of another graph is not`(@TempDir directory: Path) {
        // Tables made under another case of the graph's name are its tables, and looked at as such.
        val path = earlierFile(directory, "Drivine", keepEdges = true)
        val refused = JdbcConnectionProvider.sqlite("earlier", path)
        try {
            assertFailsWith<ReusedIdsException> { refused.connect() }
        } finally {
            refused.end()
        }
        // Another graph in the same file is made, and works.
        val other = JdbcConnectionProvider.sqlite("earlier", path, graphName = "fresh")
        try {
            other.connect().release()
        } finally {
            other.end()
        }
    }

    @Test
    fun `the graph's look refuses a store an earlier Drivine made before anything is run`(@TempDir directory: Path) {
        val path = earlierFile(directory, "g")
        val graph = SqliteGraph("g")
        val sent = mutableListOf<String>()
        val refused = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            assertFailsWith<ReusedIdsException> { graph.ensure(recording(c, sent)) }
        }
        // The look, then the file it names: nothing made.
        assertEquals(listOf(graph.made, SqliteGraph.FILE), sent)
        assertEquals(refusal("g", fileOf(path)), refused.message)
        assertEquals(fileOf(path), refused.path)
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT group_concat(name) FROM (SELECT name FROM sqlite_master ORDER BY name)").use { it.next(); assertEquals("g_edge,g_node", it.getString(1)) }
            }
        }
    }

    @Test
    fun `a graph whose name holds AUTOINCREMENT, made by an earlier Drivine, is refused`(@TempDir directory: Path) {
        // The name is in the statement SQLite keeps for each table; the look is for the key's AUTOINCREMENT alone.
        val path = earlierFile(directory, "autoincrement")
        val graph = SqliteGraph("autoincrement")
        val refused = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            assertFailsWith<ReusedIdsException> { graph.ensure(recording(c, mutableListOf())) }
        }
        assertEquals("autoincrement", refused.graph)
    }

    @Test
    fun `an in-memory database an earlier Drivine made is refused as one`() {
        val graph = SqliteGraph("g")
        val refused = DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE g_node (nid INTEGER PRIMARY KEY, labels TEXT NOT NULL, props TEXT NOT NULL, stamp TEXT)") }
            assertFailsWith<ReusedIdsException> { graph.ensure(recording(c, mutableListOf())) }
        }
        assertEquals(refusal("g", "an in-memory database"), refused.message)
        assertNull(refused.path)
    }

    @Test
    fun `the object manager over a file an earlier Drivine made gives the refusal as it is`(@TempDir directory: Path) {
        val path = earlierFile(directory, "drivine")
        val provider = JdbcConnectionProvider.sqlite("earlier", path)
        try {
            val pm = NonTransactionalPersistenceManager(provider, "earlier", DatabaseType.SQLITE, SubtypeRegistry())
            // The manager makes its backend, which looks for the graph, as it is made.
            val refused = assertFailsWith<ReusedIdsException> {
                StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry()).loadAll(Claim::class.java)
            }
            assertEquals(refusal("drivine", fileOf(path)), refused.message)
            assertFailsWith<ReusedIdsException> { SqliteBackend(pm, Neo4jObjectMapper.instance) }
        } finally {
            provider.end()
        }
    }

    @Test
    fun `a first connection taken inside a transaction gives the refusal as it is`(@TempDir directory: Path) {
        val path = earlierFile(directory, "drivine")
        val provider = JdbcConnectionProvider.sqlite("earlier", path)
        try {
            val registry = DatabaseRegistry(DataSourceMap()).also { it.register(provider) }
            val holder = TransactionContextHolder(registry)
            val tpm = PersistenceManagerFactory(registry, holder).get("earlier", PersistenceManagerType.TRANSACTIONAL)
            val refused = assertFailsWith<ReusedIdsException> {
                Transactions(holder).transaction { tpm.query(QuerySpecification.withStatement(CypherStatement("MATCH (n) RETURN n"))) }
            }
            assertEquals(refusal("drivine", fileOf(path)), refused.message)
        } finally {
            provider.end()
        }
    }

    @Test
    fun `the schema manager over a file an earlier Drivine made gives the refusal as it is, not among owners' failures`(@TempDir directory: Path) {
        val path = earlierFile(directory, "drivine")
        val provider = JdbcConnectionProvider.sqlite("default", path)
        try {
            val registry = DatabaseRegistry(DataSourceMap()).also { it.register(provider) }
            val factory = PersistenceManagerFactory(registry, TransactionContextHolder(registry))
            val schema = SchemaManager(factory, registry, listOf(SchemaCatalog.of(listOf(RangeIndexSpec("Person", "name"))).named("app")))
            assertEquals(refusal("drivine", fileOf(path)), assertFailsWith<ReusedIdsException> { schema.enforce() }.message)
            assertFailsWith<ReusedIdsException> { schema.ensure("app", RangeIndexSpec("Person", "age")) }
        } finally {
            provider.end()
        }
    }

    @Test
    fun `a file an earlier Drivine made, opened to be read only, is refused, not taken for one the user may not make tables in`(@TempDir directory: Path) {
        val path = earlierFile(directory, "drivine")
        // The application's own data source, read only: making the tables would be refused, and only logged.
        val readOnly = org.sqlite.SQLiteDataSource(org.sqlite.SQLiteConfig().apply { setReadOnly(true) }).apply { url = "jdbc:sqlite:$path" }
        val provider = JdbcConnectionProvider("earlier", DatabaseType.SQLITE, readOnly)
        repeat(2) {
            val refused = assertFailsWith<ReusedIdsException> { provider.connect() }
            assertEquals(refusal("drivine", fileOf(path)), refused.message)
        }
    }

    /** Runs each statement on [c], noting it in [sent], and gives the first column of its first row. */
    private fun recording(c: java.sql.Connection, sent: MutableList<String>) = { statement: String ->
        sent += statement
        c.createStatement().use { s -> s.executeQuery(statement).use { rows -> rows.next(); listOf<Any?>(rows.getObject(1)) } }
    }

    @Test
    fun `a fresh file is made with AUTOINCREMENT keys, and works`(@TempDir directory: Path) {
        val path = directory.resolve("fresh.db").toString()
        val provider = JdbcConnectionProvider.sqlite("fresh", path)
        try {
            val connection = provider.connect()
            try {
                connection.query(QuerySpecification.withStatement(CypherStatement("CREATE (:Person {name: 'Ada'})-[:KNOWS]->(:Person {name: 'Bob'})")))
                assertEquals(listOf<Any?>(2L), connection.query(QuerySpecification.withStatement(CypherStatement("MATCH (n:Person) RETURN count(n)"))))
            } finally {
                connection.release()
            }
            // Connected again: looked for, found, not refused.
            provider.connect().release()
        } finally {
            provider.end()
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('drivine_node', 'drivine_edge') AND sql LIKE '%AUTOINCREMENT%'").use { it.next(); assertEquals(2, it.getInt(1)) }
                s.executeQuery("SELECT name FROM sqlite_sequence ORDER BY name").use { rows ->
                    assertEquals(listOf("drivine_edge", "drivine_node"), generateSequence { if (rows.next()) rows.getString(1) else null }.toList())
                }
            }
        }
    }
}
