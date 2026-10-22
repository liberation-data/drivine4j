package org.drivine.backend.postgres

import org.drivine.connection.DatabaseType
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import java.sql.SQLException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What [PostgresBackend] makes of what an object manager asks of it. The store is a [RecordingStore], so no database is needed. */
class PostgresBackendTest {

    private fun backend() = PostgresBackend(
        RecordingStore(DatabaseType.POSTGRES, ddl = PostgresSchemaGrammar(PostgresGraph("g")), graphName = "g"),
        Neo4jObjectMapper.instance,
    )

    @Test
    fun `a deadlock and a serialization failure are contention, and no other failure is`() {
        val backend = backend()

        assertTrue(backend.contended(SQLException("could not serialize access due to concurrent update", "40001")))
        assertTrue(backend.contended(SQLException("deadlock detected", "40P01")))
        // A unique key, a lock that ran out of time, a lost connection: none is another writer at that moment.
        assertFalse(backend.contended(SQLException("duplicate key value violates unique constraint", "23505")))
        assertFalse(backend.contended(SQLException("canceling statement due to lock timeout", "55P03")))
        assertFalse(backend.contended(SQLException("An I/O error occurred while sending to the backend.", "08006")))
        assertFalse(backend.contended(IllegalStateException("40001")))
        // The failure itself is asked, not what caused it: the executor walks the causes.
        assertFalse(backend.contended(RuntimeException(SQLException("deadlock detected", "40P01"))))
    }
}
