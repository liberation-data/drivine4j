package org.drivine.query.dsl

import org.drivine.connection.DatabaseType
import org.drivine.schema.FalkorDbSchemaGrammar
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * A uniqueness constraint pins a query to one root only when it is in force: FalkorDB keeps listing one
 * whose build failed, though it enforces nothing. The store is a [RecordingStore] on FalkorDB's grammar.
 */
class QueryIndexAdvisorFailedConstraintTest {

    /** An advisor over a store listing a uniqueness constraint on Person(email) with [status], and no index. */
    private fun advisor(status: String): QueryIndexAdvisor {
        val constraint = mapOf("type" to "unique", "label" to "Person", "properties" to listOf("email"), "entitytype" to "NODE", "status" to status)
        val store = RecordingStore(DatabaseType.FALKORDB, FalkorDbSchemaGrammar()) { ran ->
            if ("db.constraints()" in ran.statement) listOf(constraint) else emptyList<Any?>()
        }
        return QueryIndexAdvisor(store.indexes, store.constraints)
    }

    private fun QueryIndexAdvisor.orderedByNamePinnedByEmail() =
        check(IndexAdvicePolicy.FAIL, "Person", listOf("name"), "orderBy", pinnedBy = setOf("email"))

    @Test
    fun `a built uniqueness constraint pins the query`() {
        assertDoesNotThrow { advisor("OPERATIONAL").orderedByNamePinnedByEmail() }
    }

    @Test
    fun `a uniqueness constraint whose build failed does not pin the query`() {
        assertThrows<IllegalStateException> { advisor("FAILED").orderedByNamePinnedByEmail() }
    }
}
