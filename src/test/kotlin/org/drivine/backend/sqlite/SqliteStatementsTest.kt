package org.drivine.backend.sqlite

import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SqliteStatementsTest {

    @Test
    fun `a statement run again is the one prepared, and of many statements of their own only the latest are kept`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val statements = SqliteStatements(connection)
            val first = statements.prepared("SELECT 0")
            assertSame(first, statements.prepared("SELECT 0"))

            // A patch's query has its values written in: each is a statement of its own.
            (1..1000).forEach { assertEquals(listOf(it.toLong()), statements.rows("SELECT $it") { r -> r.getLong(1) }) }

            assertTrue(first.isClosed, "a statement not run for a long while is closed, not kept")
            val latest = statements.prepared("SELECT 1000")
            assertFalse(latest.isClosed)
            assertEquals(listOf(0L), statements.rows("SELECT 0") { it.getLong(1) }, "one that was closed is prepared again")
        }
    }
}
