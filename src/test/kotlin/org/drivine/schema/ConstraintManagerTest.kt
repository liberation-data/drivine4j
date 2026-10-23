package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import org.neo4j.driver.exceptions.Neo4jException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What [ConstraintManager.ensure] makes of a constraint the engine refuses, on Neo4j's grammar over a
 * [RecordingStore]: a violation only when a key repeats in the data, and otherwise the refusal itself.
 */
class ConstraintManagerTest {

    private val spec = UniquenessConstraintSpec("Person", "email")

    /** The refusal Neo4j gives for data that repeats a key, as an older server gives it: by code alone. */
    private val violated = Neo4jException("Neo.DatabaseError.Schema.ConstraintCreationFailed", "Unable to create Constraint( name='Person_email_unique' )")

    private val repeated = mapOf("properties" to mapOf("email" to "dup@example.com"), "count" to 2L)

    /** A store that refuses the constraint with [refusal] and answers the query for repeated keys with [conflicts]. */
    private fun store(refusal: Throwable, conflicts: () -> Any?) = RecordingStore(DatabaseType.NEO4J) { ran ->
        when {
            ran.statement.startsWith("CREATE CONSTRAINT") -> refusal
            "_count > 1" in ran.statement -> conflicts()
            else -> emptyList<Any?>()
        }
    }

    @Test
    fun `a refusal the data bears out is a violation, with the keys that repeat`() {
        val result = store(violated) { listOf(repeated) }.constraints.ensure(spec)

        assertEquals(EnsureResult.Violation(spec, listOf(repeated)), result)
    }

    @Test
    fun `a refusal of the kind a violation gives is thrown when no key repeats`() {
        val thrown = assertFailsWith<Neo4jException> { store(violated) { emptyList<Any?>() }.constraints.ensure(spec) }

        assertSame(violated, thrown)
    }

    @Test
    fun `a refusal is thrown, with why the data could not be read, when the query for repeated keys fails`() {
        val unread = DrivineException("connection lost")
        val refusal = Neo4jException("Neo.DatabaseError.Schema.ConstraintCreationFailed", "Unable to create Constraint")

        val thrown = assertFailsWith<Neo4jException> { store(refusal) { unread }.constraints.ensure(spec) }

        assertSame(refusal, thrown)
        assertSame(unread, thrown.suppressed.single())
    }

    @Test
    fun `with no sample asked for, the data is still read to confirm the violation, one key at most`() {
        val store = store(violated) { listOf(repeated) }

        val result = store.constraints.ensure(spec, violationSampleSize = 0)

        assertEquals(EnsureResult.Violation(spec, emptyList()), result)
        assertTrue(store.schema.single { "_count > 1" in it }.endsWith("LIMIT 1"))
    }

    @Test
    fun `a negative sample size leaves the sample empty, as 0 does`() {
        val result = store(violated) { listOf(repeated) }.constraints.ensure(spec, violationSampleSize = -1)

        assertEquals(EnsureResult.Violation(spec, emptyList()), result)
    }

    @Test
    fun `a refusal the grammar does not suspect is thrown without reading the data`() {
        val refusal = Neo4jException("Neo.ClientError.Statement.SyntaxError", "Invalid input")
        val store = store(refusal) { listOf(repeated) }

        assertSame(refusal, assertFailsWith<Neo4jException> { store.constraints.ensure(spec) })
        assertTrue(store.schema.none { "_count > 1" in it })
    }

    /**
     * Neo4j's grammar as an engine that builds a constraint after CREATE returns (FalkorDB's way)
     * would use it, whose build of every constraint fails.
     */
    private val failingBuild = object : SchemaGrammar by Neo4jSchemaGrammar() {
        override val constraintCreationIsAsync = true
        override fun parseConstraintRows(rows: List<Any?>) =
            Neo4jSchemaGrammar().parseConstraintRows(rows).map { it.copy(status = "FAILED") }
    }

    /** A store whose constraint is missing before CREATE and failed after it, answering the query for repeated keys with [conflicts]. */
    private fun failingBuildStore(conflicts: Any?): RecordingStore {
        var created = false
        val constraint = mapOf("name" to "Person_email", "type" to "UNIQUENESS", "entityType" to "NODE", "labelsOrTypes" to listOf("Person"), "properties" to listOf("email"))
        return RecordingStore(DatabaseType.NEO4J, failingBuild) { ran ->
            when {
                ran.statement.startsWith("CREATE CONSTRAINT") -> emptyList<Any?>().also { created = true }
                ran.statement.startsWith("SHOW CONSTRAINTS") -> if (created) listOf(constraint) else emptyList()
                "_count > 1" in ran.statement -> conflicts
                else -> emptyList<Any?>()
            }
        }
    }

    @Test
    fun `a constraint whose build fails is dropped, and is a violation when a key repeats`() {
        val store = failingBuildStore(listOf(repeated))

        assertEquals(EnsureResult.Violation(spec, listOf(repeated)), store.constraints.ensure(spec))
        assertTrue(store.schema.any { it.startsWith("DROP CONSTRAINT") })
    }

    @Test
    fun `a constraint whose build fails is dropped, and thrown when no key repeats`() {
        val store = failingBuildStore(emptyList<Any?>())

        val thrown = assertFailsWith<DrivineException> { store.constraints.ensure(spec) }

        assertContains(thrown.message!!, "no key it covers repeats")
        assertTrue(store.schema.any { it.startsWith("DROP CONSTRAINT") })
    }

    @Test
    fun `a constraint whose build fails is dropped, and thrown with why the data could not be read`() {
        val unread = DrivineException("connection lost")
        val store = failingBuildStore(unread)

        val thrown = assertFailsWith<DrivineException> { store.constraints.ensure(spec) }

        assertSame(unread, thrown.suppressed.single())
        assertTrue(store.schema.any { it.startsWith("DROP CONSTRAINT") })
    }
}
