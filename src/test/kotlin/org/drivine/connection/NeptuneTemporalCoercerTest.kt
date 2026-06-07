package org.drivine.connection

import org.drivine.query.TemporalCoercer
import org.drivine.query.grammar.CypherDialect
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Neptune lacks native datetime, so its connections must coerce temporals to ISO strings (like
 * FalkorDB) — keeping the write and param paths consistent. Neo4j/Memgraph keep native temporals.
 * Verifies the coercer wiring without a live Neptune (which isn't available in CI).
 */
class NeptuneTemporalCoercerTest {

    private fun provider(type: DatabaseType, dialect: CypherDialect) = Neo4jConnectionProvider(
        name = "coercer-test", type = type, host = "localhost", port = 9999,
        user = "x", password = "x", database = null, config = emptyMap(), cypherDialect = dialect,
    )

    @Test
    fun `Neptune connections attach the temporal-to-string coercer`() {
        val conn = provider(DatabaseType.NEPTUNE, CypherDialect.NEPTUNE).connect()
        try {
            assertTrue(conn.parameterCoercers().any { it === TemporalCoercer })
        } finally {
            conn.release()
        }
    }

    @Test
    fun `Neo4j connections keep native temporals (no coercer)`() {
        val conn = provider(DatabaseType.NEO4J, CypherDialect.NEO4J_5).connect()
        try {
            assertFalse(conn.parameterCoercers().any { it === TemporalCoercer })
        } finally {
            conn.release()
        }
    }
}