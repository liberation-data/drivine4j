package org.drivine.sqlite

import org.drivine.Drivine
import org.drivine.DrivineException
import org.drivine.StaleObjectException
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnection
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.mapper.JdbcResultMapper
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.schema.SchemaGrammar
import org.junit.jupiter.api.Test
import org.drivine.schema.UniquenessConstraintSpec
import sample.stateless.Claim
import sample.stateless.ClaimView
import sample.stateless.Human
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A connection to SQLite: how it is opened, what it binds, what it reads back, and what it refuses. */
class SqliteConnectionTest {

    private val pm = SqliteTestDatabase.pm

    private fun row(sql: String, parameters: Map<String, Any?> = emptyMap()): Any? =
        pm.query(QuerySpecification.withStatement(sql).bind(parameters)).single()

    @Test
    fun `each connection writes ahead to a log, enforces foreign keys and waits for a locked file, as long as it is told`() {
        assertEquals("wal", row("PRAGMA journal_mode"))
        assertEquals(1L, row("PRAGMA foreign_keys"))
        assertEquals(30_000L, row("PRAGMA busy_timeout"))
        assertEquals(1L, row("PRAGMA synchronous"), "NORMAL")

        val path = Files.createTempDirectory("drivine-sqlite-busy").resolve("busy.db").toString()
        val provider = JdbcConnectionProvider.sqlite("busy", path, busyTimeoutMillis = 1_234)
        try {
            provider.nativeDriver.connection.use { connection ->
                connection.createStatement().use { it.executeQuery("PRAGMA busy_timeout").use { rows -> assertTrue(rows.next()); assertEquals(1_234, rows.getInt(1)) } }
            }
        } finally {
            provider.end()
        }
    }

    @Test
    fun `every connection of the pool is opened so, with the functions registered, not only the first`() {
        val held = (1..3).map { SqliteTestDatabase.provider.nativeDriver.connection }
        try {
            assertEquals(3, held.map { System.identityHashCode(it.unwrap(java.sql.Connection::class.java)) }.toSet().size, "three connections of their own")
            held.forEach { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA foreign_keys").use { assertTrue(it.next()); assertEquals(1, it.getInt(1)) }
                    statement.executeQuery("PRAGMA busy_timeout").use { assertTrue(it.next()); assertEquals(30_000, it.getInt(1)) }
                    statement.executeQuery("PRAGMA journal_mode").use { assertTrue(it.next()); assertEquals("wal", it.getString(1)) }
                    statement.executeQuery("SELECT drivine_lower('A')").use { assertTrue(it.next()); assertEquals("a", it.getString(1)) }
                }
            }
        } finally {
            held.forEach { it.close() }
        }
    }

    @Test
    fun `a value is read as what it is, and text a statement of its own returns stays text`() {
        assertEquals(listOf(1L, "x", 2.5, null), row("SELECT 1, 'x', 2.5, NULL"))
        assertEquals("{\"a\":1}", row("SELECT json_object('a', 1)"), "JSON in a statement of the user's own is text, as in a character column elsewhere")
        assertEquals(mapOf("a" to 1L), row("/* drivine:json */ SELECT json_object('a', 1)"), "a statement that says its text is JSON is read as JSON")
        assertEquals(listOf(null, 3L, 2.5, listOf("x")), row("/* drivine:json */ SELECT NULL, COUNT(*) + 2, 2.5, json_array('x') FROM (SELECT 1)"), "only its text is read as JSON: a null, a count and a real are what they are")
    }

    @Test
    fun `a moment, a date and a decimal are bound as SQLite keeps them`() {
        val moment = Instant.parse("2026-03-01T12:00:00.5Z")
        assertEquals(listOf("text", 1772366400.5), row("SELECT typeof(\$m), unixepoch(\$m, 'subsec')", mapOf("m" to moment)))
        assertEquals("2026-03-01", row("SELECT date(\$d)", mapOf("d" to LocalDate.of(2026, 3, 1))))
        assertEquals(listOf("text", 1L), row("SELECT typeof(\$d), drivine_epoch(\$d) = drivine_epoch(\$m)", mapOf("d" to java.util.Date.from(moment), "m" to moment)), "a Date is the instant it names")
        assertEquals(listOf("integer", "real"), row("SELECT typeof(\$a), typeof(\$b)", mapOf("a" to BigDecimal("7"), "b" to BigDecimal("1.5"))))
    }

    @Test
    fun `the functions Drivine's statements call are there on every connection`() {
        assertEquals(listOf(1L, 0L, null), row("SELECT 'graph' REGEXP '^gr', 'graph' REGEXP '^x', NULL REGEXP 'x'"))
        assertEquals("école straße", row("SELECT drivine_lower('ÉCOLE STRASSE')").toString().replace("strasse", "straße"))
        assertEquals(2L, row("SELECT drivine_regexp_count('a graph of graphs', 'graph')"))
        assertEquals(1.0, row("SELECT drivine_similarity('[1, 0]', '[2, 0]', 'COSINE')"))
        assertEquals(0.5, row("SELECT drivine_similarity('[1, 0]', '[0, 0]', 'EUCLIDEAN')"))
        assertEquals(null, row("SELECT drivine_similarity('[1, 0]', '[1, 0, 0]', 'COSINE')"), "vectors of two sizes are not measured")
        assertEquals(
            listOf(1_772_366_400_000_001_000L, 1_772_366_400_000_001_000L, 1_772_366_400_000_000_000L),
            row("SELECT drivine_epoch('2026-03-01T12:00:00.000001Z'), drivine_epoch('2026-03-01T13:00:00.000001+01:00'), drivine_epoch('2026-03-01T12:00')"),
            "a moment is the instant it is whatever its offset, and a date-time with none is read as at UTC",
        )
        assertEquals(listOf(null, null, null), row("SELECT drivine_epoch('2026-03-01'), drivine_epoch('soon'), drivine_epoch('3000-01-01T00:00:00Z')"), "no moment, or none an integer of nanoseconds holds")
    }

    @Test
    fun `a Cypher statement is refused until SQLite compiles one`() {
        val refused = assertFailsWith<DrivineException> { pm.query(QuerySpecification.withStatement(CypherStatement("MATCH (n) RETURN n"))) }
        assertTrue("does not run on SQLITE yet" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a transaction that is rolled back writes nothing, and one that commits writes it all`() {
        val path = Files.createTempDirectory("drivine-sqlite-tx").resolve("tx.db").toString()
        Drivine(DataSourceMap(mapOf("lite" to ConnectionProperties(type = DatabaseType.SQLITE, databaseName = path)))).use { drivine ->
            val stateless = drivine.graphObjectManager("lite")
            stateless.count(Claim::class.java)

            runCatching {
                drivine.transaction {
                    stateless.save(Claim("c1", "one"))
                    stateless.save(Claim("c2", "two"))
                    error("rolled back")
                }
            }
            assertEquals(0, stateless.count(Claim::class.java))

            drivine.transaction {
                stateless.save(Claim("c1", "one"))
                val saved = stateless.save(Claim("c2", "two"))
                stateless.save(saved.copy(text = "three"))
                // A stale save in a transaction rolls back its own steps, and leaves the transaction's.
                assertFailsWith<StaleObjectException> { stateless.save(saved.copy(text = "four")) }
            }
            assertEquals(listOf("one", "three"), stateless.loadAll(Claim::class.java).map { it.text }.sorted())
        }
    }

    @Test
    fun `a transaction ends without opening another, so a commit or a rollback never waits for the file's lock`() {
        SqliteTestDatabase.run("CREATE TABLE IF NOT EXISTS tx_end (v TEXT)", "DELETE FROM tx_end")
        // The driver's commit and rollback open the next transaction as they end one, which waits for the write lock.
        val called = mutableListOf<String>()
        fun connection(): JdbcConnection {
            val pooled = SqliteTestDatabase.provider.nativeDriver.connection
            val recording = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                called += method.name
                try {
                    method.invoke(pooled, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as java.sql.Connection
            return JdbcConnection(recording, JdbcResultMapper(), DatabaseType.SQLITE)
        }
        fun insert(connection: JdbcConnection, v: String) = connection.query(QuerySpecification.withStatement("INSERT INTO tx_end (v) VALUES (\$v)").bind(mapOf("v" to v)))

        connection().let { connection ->
            connection.startTransaction()
            insert(connection, "committed")
            connection.commitTransaction()
            connection.startTransaction()
            insert(connection, "rolled back")
            connection.rollbackTransaction()
            connection.release(null)
        }
        assertTrue("commit" !in called && "rollback" !in called, "neither the driver's commit nor its rollback: $called")
        assertEquals(listOf("committed"), pm.query(QuerySpecification.withStatement("SELECT v FROM tx_end").transform(String::class.java)))

        // Once ended, the connection holds no lock: another takes the file's write lock without waiting.
        SqliteTestDatabase.provider.nativeDriver.connection.use { other ->
            other.createStatement().use { it.execute("PRAGMA busy_timeout = 0"); it.execute("BEGIN IMMEDIATE"); it.execute("ROLLBACK"); it.execute("PRAGMA busy_timeout = 30000") }
        }

        // A transaction left open when the connection goes back to its pool is rolled back the same way.
        called.clear()
        connection().let { connection ->
            connection.startTransaction()
            insert(connection, "left open")
            connection.release(null)
        }
        assertTrue("rollback" !in called, "not the driver's rollback: $called")
        assertEquals(listOf("committed"), pm.query(QuerySpecification.withStatement("SELECT v FROM tx_end").transform(String::class.java)))
    }

    @Test
    fun `a commit SQLite refuses leaves the transaction to roll back, and the connection goes back to its pool with none open`() {
        val path = Files.createTempDirectory("drivine-sqlite-refused").resolve("refused.db").toString()
        val provider = JdbcConnectionProvider.sqlite("refused", path, poolMax = 1)
        try {
            fun run(connection: org.drivine.connection.Connection, sql: String) = connection.query(QuerySpecification.withStatement(sql))
            fun count(): Any? = provider.connect().let { connection ->
                try { run(connection, "SELECT count(*) AS n FROM child").single() } finally { connection.release() }
            }
            provider.connect().let { connection ->
                run(connection, "CREATE TABLE parent (id INTEGER PRIMARY KEY)")
                // A deferred key is checked as the transaction commits: SQLite refuses the commit and keeps the transaction open.
                run(connection, "CREATE TABLE child (parent INTEGER REFERENCES parent (id) DEFERRABLE INITIALLY DEFERRED)")
                connection.release()
            }
            provider.connect().let { connection ->
                connection.startTransaction()
                run(connection, "INSERT INTO child (parent) VALUES (1)")
                assertFails { connection.commitTransaction() }
                connection.rollbackTransaction()
                connection.release()
            }
            assertEquals(0L, count())

            // The pool's one connection commits each statement as it runs, and opens a transaction as it is asked to.
            provider.nativeDriver.connection.use { pooled ->
                assertTrue(pooled.autoCommit)
                pooled.createStatement().use { it.execute("INSERT INTO parent (id) VALUES (1)") }
            }
            provider.connect().let { connection ->
                connection.startTransaction()
                run(connection, "INSERT INTO child (parent) VALUES (1)")
                connection.commitTransaction()
                connection.release()
            }
            assertEquals(1L, count())
        } finally {
            provider.end()
        }
    }

    @Test
    fun `a transaction SQLite has already rolled back is rolled back without failing, and leaves no transaction open`() {
        val path = Files.createTempDirectory("drivine-sqlite-aborted").resolve("aborted.db").toString()
        val provider = JdbcConnectionProvider.sqlite("aborted", path, poolMax = 1)
        try {
            fun run(connection: org.drivine.connection.Connection, sql: String) = connection.query(QuerySpecification.withStatement(sql))
            provider.connect().let { connection ->
                run(connection, "CREATE TABLE t (v TEXT)")
                // A statement that fails so, as one that fills the disk or is interrupted does, ends the whole transaction.
                run(connection, "CREATE TRIGGER t_abort BEFORE INSERT ON t WHEN NEW.v = 'abort' BEGIN SELECT RAISE(ROLLBACK, 'aborted'); END")
                connection.release()
            }
            provider.connect().let { connection ->
                connection.startTransaction()
                run(connection, "INSERT INTO t (v) VALUES ('rolled back')")
                assertFails { run(connection, "INSERT INTO t (v) VALUES ('abort')") }
                connection.rollbackTransaction()
                connection.release()
            }
            provider.connect().let { connection ->
                connection.startTransaction()
                run(connection, "INSERT INTO t (v) VALUES ('committed')")
                connection.commitTransaction()
                connection.release()
            }
            provider.nativeDriver.connection.use { pooled ->
                assertTrue(pooled.autoCommit)
                pooled.createStatement().use { statement ->
                    statement.executeQuery("SELECT group_concat(v) FROM t").use { it.next(); assertEquals("committed", it.getString(1)) }
                }
            }
        } finally {
            provider.end()
        }
    }

    @Test
    fun `a save that fails after its first steps wrote rolls back its own steps, in a transaction or not`() {
        val path = Files.createTempDirectory("drivine-sqlite-failed").resolve("failed.db").toString()
        Drivine(DataSourceMap(mapOf("lite" to ConnectionProperties(type = DatabaseType.SQLITE, databaseName = path)))).use { drivine ->
            val stateless = drivine.graphObjectManager("lite")
            drivine.persistenceManager("lite").constraints.ensure(UniquenessConstraintSpec("Human", "name"))
            stateless.save(Human("ada", "Ada"))
            val claim = stateless.save(Claim("c1", "one"))
            // The root is written first; the person it mentions is then refused by the constraint.
            val refused = ClaimView(claim.copy(text = "two"), people = listOf(Human("other-ada", "Ada")))

            val grammar = drivine.persistenceManager("lite").constraints.grammar
            val failed = assertFails { stateless.save(refused) }
            assertTrue(grammar.isConstraintViolation(failed), failed.message)
            assertEquals("one", stateless.load("c1", Claim::class.java)?.text)

            drivine.transaction {
                stateless.save(Claim("c2", "kept"))
                assertTrue(grammar.isConstraintViolation(assertFails { stateless.save(refused) }))
            }
            assertEquals("one", stateless.load("c1", Claim::class.java)?.text, "the failed save's root is not written")
            assertEquals("kept", stateless.load("c2", Claim::class.java)?.text, "the transaction's own write is")
            assertNull(stateless.load("other-ada", Human::class.java))
        }
    }

    @Test
    fun `a provider is built from properties with the file's path as the database name, and no host`() {
        val path = Files.createTempDirectory("drivine-sqlite-props").resolve("props.db").toString()
        Drivine(DataSourceMap(mapOf("lite" to ConnectionProperties(type = DatabaseType.SQLITE, databaseName = path, defaultGraphPath = "props", poolMax = 2)))).use { drivine ->
            val provider = assertNotNull(drivine.databaseRegistry.connectionProvider("lite")) as JdbcConnectionProvider
            assertEquals("props", provider.graphName)
            assertEquals("SQLite", provider.schemaGrammar.engine)
            val stateless = drivine.graphObjectManager("lite")
            stateless.save(Claim("c1", "one"))
            assertEquals("one", stateless.load("c1", Claim::class.java)?.text)
        }

        val refused = assertFailsWith<IllegalArgumentException> {
            DatabaseRegistry(DataSourceMap(mapOf("lite" to ConnectionProperties(type = DatabaseType.SQLITE)))).connectionProvider("lite")
        }
        assertTrue("databaseName" in refused.message.orEmpty(), refused.message)

        // Each pooled connection to an in-memory database would open one of its own.
        listOf("", ":memory:", "file::memory:?cache=shared", "file:graph?mode=memory").forEach { path ->
            val memory = assertFailsWith<IllegalArgumentException>(path) { JdbcConnectionProvider.sqlite("memory", path) }
            assertTrue("names no file" in memory.message.orEmpty(), memory.message)
        }
    }

    @Test
    fun `the schema grammar of a SQLite provider is SQLite's`() {
        val grammar: SchemaGrammar = SqliteTestDatabase.provider.schemaGrammar
        assertEquals("SQLite", grammar.engine)
    }
}
