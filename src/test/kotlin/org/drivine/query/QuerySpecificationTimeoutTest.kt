package org.drivine.query

import org.drivine.mapper.RowMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The timeout a library sets on a statement from the shared module's plan (drivine4j#868) stays with every copy of its
 * specification, as a connection runs the copy it makes: FalkorDB's then runs under it.
 */
class QuerySpecificationTimeoutTest {

    @Test
    fun `every copy of a specification keeps its timeout`() {
        val spec = QuerySpecification.withStatement("RETURN 1").also { it.timeoutMillis = 300_000 }

        assertEquals(300_000L, spec.transform(Map::class.java).timeoutMillis)
        assertEquals(300_000L, spec.map { it }.timeoutMillis)
        assertEquals(300_000L, spec.mapWith(object : RowMapper<Any?> { override fun map(row: Map<String, *>): Any? = row }).timeoutMillis)
        assertEquals(300_000L, spec.filterIsInstance(Map::class.java).timeoutMillis)
        assertEquals(300_000L, spec.finalizedCopy(QueryLanguage.CYPHER).timeoutMillis)
        assertEquals(300_000L, spec.withSql("SELECT 1", emptyMap()).timeoutMillis)
        assertEquals(300_000L, spec.transform(Map::class.java).finalizedCopy(QueryLanguage.CYPHER).timeoutMillis)
        assertEquals(null, QuerySpecification.withStatement("RETURN 1").finalizedCopy(QueryLanguage.CYPHER).timeoutMillis)
    }

    /** The timeout is the library's, not a caller's: no getter or setter of that name is public to Java, only Kotlin's mangled internal ones. */
    @Test
    fun `no caller can read or set the timeout`() {
        val named = QuerySpecification::class.java.methods.map { it.name }.filter { it.startsWith("getTimeoutMillis") || it.startsWith("setTimeoutMillis") }
        assertTrue(named.all { '$' in it }, "$named")
    }
}
