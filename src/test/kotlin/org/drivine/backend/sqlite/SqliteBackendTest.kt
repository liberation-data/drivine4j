package org.drivine.backend.sqlite

import org.drivine.connection.DatabaseType
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.SQLException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What [SqliteBackend] makes of what an object manager asks of it. The store is a [RecordingStore], so no database is needed. */
class SqliteBackendTest {

    private fun backend() = SqliteBackend(
        RecordingStore(DatabaseType.SQLITE, ddl = SqliteSchemaGrammar(SqliteGraph("g")), graphName = "g"),
        Neo4jObjectMapper.instance,
    )

    @Test
    fun `a locked file or table is contention, by its code or by what is left of it in a function's message, and no other failure is`() {
        val backend = backend()

        assertTrue(backend.contended(SQLiteException("[SQLITE_BUSY] The database file is locked (database is locked)", SQLiteErrorCode.SQLITE_BUSY)))
        assertTrue(backend.contended(SQLiteException("[SQLITE_BUSY_SNAPSHOT] Another database connection has already written", SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT)))
        assertTrue(backend.contended(SQLiteException("[SQLITE_LOCKED] A table in the database is locked", SQLiteErrorCode.SQLITE_LOCKED)))
        // A write turned away inside the write function reaches the statement as its message alone.
        assertTrue(backend.contended(SQLiteException("[SQLITE_ERROR] SQL error or missing database ([SQLITE_BUSY] The database file is locked (database is locked))", SQLiteErrorCode.SQLITE_ERROR)))
        assertFalse(backend.contended(SQLiteException("[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)))
        assertFalse(backend.contended(SQLException("no such table: g_node")))
        assertFalse(backend.contended(IllegalStateException("database is locked")))
        // The failure itself is asked, not what caused it: the executor walks the causes.
        assertFalse(backend.contended(RuntimeException(SQLiteException("[SQLITE_BUSY]", SQLiteErrorCode.SQLITE_BUSY))))
    }
}
