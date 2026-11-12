package org.drivine.backend.sqlite

import org.drivine.cypher.host.HostFunction
import org.drivine.cypher.store.SqliteFunctions as Shared
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [SqliteHostFunction] hands the shared module's host functions xerial's arguments and results, registers each with the
 * flags it declares, and [SqliteHostFunction.check] fails a connection whose functions aren't the compiler's version.
 */
class SqliteHostFunctionTest {

    private fun <T> connection(block: (Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite::memory:").use(block)

    private fun Connection.one(sql: String): Any? = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> rows.next(); rows.getObject(1) }
    }

    @Test
    fun `an argument's type is SQLite's`() = connection { connection ->
        SqliteHostFunction.register(connection, HostFunction("t_type", 1, deterministic = true) { args, _, result -> result.text(args.type(0).name) })
        assertEquals(
            listOf("INTEGER", "FLOAT", "TEXT", "BLOB", "NULL"),
            listOf("1", "1.5", "'a'", "x'00'", "NULL").map { connection.one("SELECT t_type($it)") },
        )
    }

    @Test
    fun `an argument reads as each of xerial's reads, converted as SQLite converts it`() = connection { connection ->
        SqliteHostFunction.register(connection, HostFunction("t_reads", 5, deterministic = true) { args, count, result ->
            result.text("$count ${args.long(0)} ${args.int(1)} ${args.double(2)} ${args.blob(3)?.toList()} ${args.text16(4)}")
        })
        assertEquals("5 7 3 2.5 [1, 2] é", connection.one("SELECT t_reads('7', 3.9, '2.5', x'0102', 'é')"))
        SqliteHostFunction.register(connection, HostFunction("t_nulls", 2, deterministic = true) { args, _, result ->
            result.text("${args.text(0)} ${args.text16(0)} ${args.blob(1)}")
        })
        assertEquals("null null null", connection.one("SELECT t_nulls(NULL, NULL)"))
    }

    @Test
    fun `a function that throws, or writes an error, fails the statement with its message`() = connection { connection ->
        SqliteHostFunction.register(connection, HostFunction("t_throw", 0, deterministic = true) { _, _, _ -> throw IllegalArgumentException("thrown here") })
        SqliteHostFunction.register(connection, HostFunction("t_error", 0, deterministic = true) { _, _, result -> result.error("written here") })
        assertContains(assertFailsWith<SQLException> { connection.one("SELECT t_throw()") }.message.orEmpty(), "thrown here")
        assertContains(assertFailsWith<SQLException> { connection.one("SELECT t_error()") }.message.orEmpty(), "written here")
    }

    @Test
    fun `a direct-only function can't be called from a view, an innocuous one can from an untrusted schema`() = connection { connection ->
        SqliteHostFunction.register(connection, HostFunction("t_direct", 0, deterministic = false, directOnly = true) { _, _, result -> result.long(1) })
        SqliteHostFunction.register(connection, HostFunction("t_innocuous", 0, deterministic = true) { _, _, result -> result.long(2) })
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA trusted_schema = OFF")
            statement.execute("CREATE VIEW direct AS SELECT t_direct()")
            statement.execute("CREATE VIEW innocuous AS SELECT t_innocuous()")
        }
        assertEquals(1, connection.one("SELECT t_direct()"))
        assertContains(assertFailsWith<SQLException> { connection.one("SELECT * FROM direct") }.message.orEmpty(), "unsafe use of t_direct()")
        assertEquals(2, connection.one("SELECT * FROM innocuous"))
    }

    @Test
    fun `check passes the module's functions, and fails none or another version`() {
        connection { connection ->
            SqliteFunctions.register(connection)
            SqliteHostFunction.check(connection)
        }
        val none = connection { connection -> assertFailsWith<IllegalStateException> { SqliteHostFunction.check(connection) } }
        assertContains(none.message.orEmpty(), "SQLite has no ${Shared.HOST_VERSION_FUNCTION}()")
        val other = connection { connection ->
            SqliteHostFunction.register(connection, HostFunction(Shared.HOST_VERSION_FUNCTION, 0, deterministic = true) { _, _, result -> result.long(0) })
            assertFailsWith<IllegalStateException> { SqliteHostFunction.check(connection) }
        }
        assertContains(other.message.orEmpty(), "version 0, the compiler needs ${Shared.HOST_VERSION}")
    }
}
