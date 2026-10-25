package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import org.neo4j.driver.exceptions.Neo4jException
import java.time.Duration
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

    // ----- A failed constraint already in the store -----

    /** Neo4j's grammar as an engine that builds a constraint after CREATE returns would use it, reading each constraint's status from its row. */
    private val asyncBuild = object : SchemaGrammar by Neo4jSchemaGrammar() {
        override val constraintCreationIsAsync = true
        override fun parseConstraintRows(rows: List<Any?>) =
            Neo4jSchemaGrammar().parseConstraintRows(rows).zip(rows) { info, row -> info.copy(status = (row as Map<*, *>)["status"] as String?) }
    }

    private fun listed(status: String) =
        mapOf("name" to "Person_email", "type" to "UNIQUENESS", "entityType" to "NODE", "labelsOrTypes" to listOf("Person"), "properties" to listOf("email"), "status" to status)

    /**
     * A store holding a constraint whose build failed, until DROP CONSTRAINT has run. Once CREATE has run, each
     * listing gives the next of [builds], the last of them from then on. The query for repeated keys gives [conflicts].
     */
    private fun failedInStore(vararg builds: String, conflicts: Any? = emptyList<Any?>()): RecordingStore {
        var status: String? = "FAILED"
        var listings = 0
        return RecordingStore(DatabaseType.NEO4J, asyncBuild) { ran ->
            when {
                ran.statement.startsWith("DROP CONSTRAINT") -> emptyList<Any?>().also { status = null }
                ran.statement.startsWith("CREATE CONSTRAINT") -> emptyList<Any?>().also { status = "CREATED" }
                ran.statement.startsWith("SHOW CONSTRAINTS") -> when (status) {
                    null -> emptyList()
                    "CREATED" -> listOf(listed(builds[minOf(listings++, builds.size - 1)]))
                    else -> listOf(listed(status!!))
                }
                "_count > 1" in ran.statement -> conflicts
                else -> emptyList<Any?>()
            }
        }
    }

    @Test
    fun `a failed constraint already in the store is not taken for the constraint, but dropped and built again`() {
        val store = failedInStore("OPERATIONAL")

        val result = store.constraints.ensure(spec)

        assertTrue(result is EnsureResult.Created, "expected Created, got $result")
        val dropped = store.schema.indexOfFirst { it.startsWith("DROP CONSTRAINT") }
        assertTrue(dropped >= 0, "expected the failed constraint dropped: ${store.schema}")
        assertTrue(store.schema.drop(dropped).any { it.startsWith("CREATE CONSTRAINT") }, "expected the constraint made after it: ${store.schema}")
    }

    @Test
    fun `a failed constraint already in the store is a violation when the data still repeats a key`() {
        val store = failedInStore("FAILED", conflicts = listOf(repeated))

        assertEquals(EnsureResult.Violation(spec, listOf(repeated)), store.constraints.ensure(spec))
        // The one in the store, then the one built again, which failed too.
        assertEquals(2, store.schema.count { it.startsWith("DROP CONSTRAINT") })
    }

    @Test
    fun `a failed constraint already in the store is a violation with an empty sample at sample size 0`() {
        val store = failedInStore("FAILED", conflicts = listOf(repeated))

        assertEquals(EnsureResult.Violation(spec, emptyList()), store.constraints.ensure(spec, violationSampleSize = 0))
        assertEquals(2, store.schema.count { it.startsWith("DROP CONSTRAINT") })
    }

    @Test
    fun `a built constraint already in the store is matching, and left, whether or not the engine reports a status`() {
        for (grammar in listOf(asyncBuild, Neo4jSchemaGrammar())) {
            val store = RecordingStore(DatabaseType.NEO4J, grammar) { ran ->
                if (ran.statement.startsWith("SHOW CONSTRAINTS")) listOf(listed("OPERATIONAL")) else emptyList<Any?>()
            }

            assertTrue(store.constraints.ensure(spec) is EnsureResult.AlreadyMatching, "on $grammar")
            assertTrue(store.schema.none { it.startsWith("DROP") || it.startsWith("CREATE") }, "on $grammar: ${store.schema}")
        }
    }

    @Test
    fun `drop removes a constraint whose build failed`() {
        val store = failedInStore()

        assertTrue(store.constraints.drop(spec))
        assertTrue(store.schema.any { it.startsWith("DROP CONSTRAINT") })
        assertEquals(null, store.constraints.find(spec))
    }

    @Test
    fun `a build is read from one listing a poll, so one that fails after being listed under construction is not built`() {
        val store = failedInStore("UNDER CONSTRUCTION", "UNDER CONSTRUCTION", "FAILED", conflicts = listOf(repeated))

        assertEquals(EnsureResult.Violation(spec, listOf(repeated)), store.constraints.ensure(spec))
        // One listing for each poll after CREATE: twice under construction, then failed.
        val made = store.schema.indexOfFirst { it.startsWith("CREATE CONSTRAINT") }
        assertEquals(3, store.schema.drop(made).takeWhile { !it.startsWith("DROP CONSTRAINT") }.count { it.startsWith("SHOW CONSTRAINTS") })
    }

    // ----- A constraint already in the store, still being built -----

    /**
     * A store holding a constraint another process is building. Until DROP CONSTRAINT has run, each listing
     * gives the next of [builds], the last of them from then on; a null in [builds] lists nothing, as when
     * that process has dropped its failed build. After DROP CONSTRAINT, nothing is listed until CREATE has
     * run, and then each listing gives the next of [rebuilds]. The query for repeated keys gives [conflicts].
     */
    private fun buildingInStore(vararg builds: String?, rebuilds: List<String> = listOf("OPERATIONAL"), conflicts: Any? = emptyList<Any?>()): RecordingStore {
        var phase = "building"
        var listings = 0
        return RecordingStore(DatabaseType.NEO4J, asyncBuild) { ran ->
            when {
                ran.statement.startsWith("DROP CONSTRAINT") -> emptyList<Any?>().also { phase = "dropped" }
                ran.statement.startsWith("CREATE CONSTRAINT") -> emptyList<Any?>().also { phase = "made"; listings = 0 }
                ran.statement.startsWith("SHOW CONSTRAINTS") -> when (phase) {
                    "building" -> listOfNotNull(builds[minOf(listings++, builds.size - 1)]?.let { listed(it) })
                    "made" -> listOf(listed(rebuilds[minOf(listings++, rebuilds.size - 1)]))
                    else -> emptyList()
                }
                "_count > 1" in ran.statement -> conflicts
                else -> emptyList<Any?>()
            }
        }
    }

    @Test
    fun `a constraint already in the store and still being built is waited on, and matching once built`() {
        val store = buildingInStore("UNDER CONSTRUCTION", "UNDER CONSTRUCTION", "OPERATIONAL")

        val result = store.constraints.ensure(spec)

        assertTrue(result is EnsureResult.AlreadyMatching, "expected AlreadyMatching, got $result")
        assertEquals("OPERATIONAL", result.info.status)
        assertEquals(3, store.schema.count { it.startsWith("SHOW CONSTRAINTS") })
        assertTrue(store.schema.none { it.startsWith("DROP") || it.startsWith("CREATE") }, "${store.schema}")
    }

    @Test
    fun `a constraint already in the store whose build then fails is dropped, and a violation when the data repeats a key`() {
        val store = buildingInStore("UNDER CONSTRUCTION", "FAILED", rebuilds = listOf("FAILED"), conflicts = listOf(repeated))

        assertEquals(EnsureResult.Violation(spec, listOf(repeated)), store.constraints.ensure(spec))
        // The one whose build failed, then the one built again, which failed too.
        assertEquals(2, store.schema.count { it.startsWith("DROP CONSTRAINT") })
    }

    @Test
    fun `a constraint already in the store whose build then fails is dropped, and built again once the data is clean`() {
        val store = buildingInStore("UNDER CONSTRUCTION", "FAILED")

        val result = store.constraints.ensure(spec)

        assertTrue(result is EnsureResult.Created, "expected Created, got $result")
        val dropped = store.schema.indexOfFirst { it.startsWith("DROP CONSTRAINT") }
        assertTrue(dropped >= 0, "expected the failed constraint dropped: ${store.schema}")
        assertTrue(store.schema.drop(dropped).any { it.startsWith("CREATE CONSTRAINT") }, "expected the constraint made after it: ${store.schema}")
    }

    @Test
    fun `a constraint already in the store that is dropped while it is built is made`() {
        val store = buildingInStore("UNDER CONSTRUCTION", null)

        val result = store.constraints.ensure(spec)

        assertTrue(result is EnsureResult.Created, "expected Created, got $result")
        assertTrue(store.schema.none { it.startsWith("DROP CONSTRAINT") }, "${store.schema}")
        assertEquals(1, store.schema.count { it.startsWith("CREATE CONSTRAINT") })
    }

    @Test
    fun `a constraint already in the store still being built when the wait ends is thrown, and left`() {
        val store = RecordingStore(DatabaseType.NEO4J, asyncBuild, asyncCreationTimeout = Duration.ofMillis(500)) { ran ->
            if (ran.statement.startsWith("SHOW CONSTRAINTS")) listOf(listed("UNDER CONSTRUCTION")) else emptyList<Any?>()
        }

        val thrown = assertFailsWith<DrivineException> { store.constraints.ensure(spec) }

        assertContains(thrown.message!!, "Timed out")
        assertTrue(store.schema.none { it.startsWith("DROP") || it.startsWith("CREATE") }, "${store.schema}")
    }

    /** What FalkorDB says to a drop of a constraint that is not there. */
    private val noSuchConstraint = DrivineException("Unable to drop constraint, no such constraint.")

    /**
     * A store whose constraint is listed with each of [builds] in turn, the last from then on, until another
     * process also waiting on it drops it first: the drop here is refused, and nothing is listed until CREATE
     * has run, after which it is listed OPERATIONAL. With [madeHere], nothing is listed until CREATE has run
     * here, and the listing of [builds] follows it.
     */
    private fun droppedFirstElsewhere(vararg builds: String, madeHere: Boolean = false, conflicts: Any? = emptyList<Any?>()): RecordingStore {
        var phase = if (madeHere) "none" else "building"
        var listings = 0
        return RecordingStore(DatabaseType.NEO4J, asyncBuild) { ran ->
            when {
                ran.statement.startsWith("DROP CONSTRAINT") -> noSuchConstraint.also { phase = "dropped" }
                ran.statement.startsWith("CREATE CONSTRAINT") -> emptyList<Any?>().also { phase = if (phase == "none") "building" else "made" }
                ran.statement.startsWith("SHOW CONSTRAINTS") -> when (phase) {
                    "building" -> listOf(listed(builds[minOf(listings++, builds.size - 1)]))
                    "made" -> listOf(listed("OPERATIONAL"))
                    else -> emptyList()
                }
                "_count > 1" in ran.statement -> conflicts
                else -> emptyList<Any?>()
            }
        }
    }

    @Test
    fun `a failed build another process dropped first is built again here`() {
        val store = droppedFirstElsewhere("UNDER CONSTRUCTION", "FAILED")

        val result = store.constraints.ensure(spec)

        assertTrue(result is EnsureResult.Created, "expected Created, got $result")
        assertEquals(1, store.schema.count { it.startsWith("CREATE CONSTRAINT") })
    }

    @Test
    fun `a failed build made here that another process dropped first is a violation when a key repeats`() {
        val store = droppedFirstElsewhere("UNDER CONSTRUCTION", "FAILED", madeHere = true, conflicts = listOf(repeated))

        assertEquals(EnsureResult.Violation(spec, listOf(repeated)), store.constraints.ensure(spec))
    }

    @Test
    fun `a refused drop of a failed build is thrown while it is still listed`() {
        val store = RecordingStore(DatabaseType.NEO4J, asyncBuild) { ran ->
            when {
                ran.statement.startsWith("DROP CONSTRAINT") -> noSuchConstraint
                ran.statement.startsWith("SHOW CONSTRAINTS") -> listOf(listed("FAILED"))
                else -> emptyList<Any?>()
            }
        }

        assertSame(noSuchConstraint, assertFailsWith<DrivineException> { store.constraints.ensure(spec) })
        assertTrue(store.schema.none { it.startsWith("CREATE CONSTRAINT") }, "${store.schema}")
    }

    // ----- An index in the way -----

    private val inTheWay = Neo4jException("Neo.ClientError.Schema.IndexAlreadyExists", "There already exists an index (:Person {email}). A constraint cannot be created until the index has been dropped.")

    private fun rangeIndex(name: String, vararg properties: String) =
        mapOf("name" to name, "type" to "RANGE", "entityType" to "NODE", "labelsOrTypes" to listOf("Person"), "properties" to properties.toList())

    /**
     * A store with [indexes], that refuses the constraint for an index in the way of it until DROP INDEX has run,
     * and answers the query for repeated keys with [conflicts].
     */
    private fun blockedStore(vararg indexes: Map<String, Any>, conflicts: Any? = emptyList<Any?>()): RecordingStore {
        var dropped = false
        return RecordingStore(DatabaseType.NEO4J) { ran ->
            when {
                ran.statement.startsWith("DROP INDEX") -> emptyList<Any?>().also { dropped = true }
                ran.statement.startsWith("CREATE CONSTRAINT") -> if (dropped) emptyList<Any?>() else inTheWay
                ran.statement.startsWith("SHOW INDEXES") -> if (dropped) emptyList() else indexes.toList()
                "_count > 1" in ran.statement -> conflicts
                else -> emptyList<Any?>()
            }
        }
    }

    @Test
    fun `an index in the way of the constraint is reported by name, and left`() {
        val store = blockedStore(rangeIndex("person_email", "email"))

        val result = store.constraints.ensure(spec)

        assertEquals(EnsureResult.BlockedByIndex(spec, SchemaItemInfo(SchemaItemKind.RANGE_INDEX, "Person", listOf("email"), "person_email")), result)
        assertTrue(store.schema.none { it.startsWith("DROP INDEX") })
    }

    @Test
    fun `an index in the way of the constraint is dropped and the constraint made, when asked`() {
        val store = blockedStore(rangeIndex("person_email", "email"))

        val result = store.constraints.ensure(spec, replaceBlockingIndex = true)

        assertTrue(result is EnsureResult.Created, "expected Created, got $result")
        val dropped = store.schema.indexOf("DROP INDEX `person_email` IF EXISTS")
        assertTrue(dropped >= 0, "expected the index dropped: ${store.schema}")
        assertTrue(store.schema.drop(dropped).any { it.startsWith("CREATE CONSTRAINT") }, "expected the constraint made after it: ${store.schema}")
    }

    @Test
    fun `an index in the way is kept, and the violation reported, when a key repeats`() {
        val store = blockedStore(rangeIndex("person_email", "email"), conflicts = listOf(repeated))

        val result = store.constraints.ensure(spec, replaceBlockingIndex = true)

        assertEquals(EnsureResult.Violation(spec, listOf(repeated)), result)
        assertTrue(store.schema.none { it.startsWith("DROP INDEX") }, "expected the index kept: ${store.schema}")
    }

    @Test
    fun `recreate replaces an index in the way of the constraint, when asked`() {
        val store = blockedStore(rangeIndex("person_email", "email"))

        val result = store.constraints.recreate(spec, replaceBlockingIndex = true)

        assertTrue(result is EnsureResult.Recreated, "expected Recreated, got $result")
        assertTrue("DROP INDEX `person_email` IF EXISTS" in store.schema, "expected the index dropped: ${store.schema}")
    }

    @Test
    fun `the index in the way is the one over the constraint's properties in their order`() {
        val composite = UniquenessConstraintSpec("Person", listOf("tenant", "email"))
        val store = blockedStore(rangeIndex("email_tenant", "email", "tenant"), rangeIndex("tenant_email", "tenant", "email"))

        val result = store.constraints.ensure(composite) as EnsureResult.BlockedByIndex

        assertEquals("tenant_email", result.index.name)
    }

    @Test
    fun `a refusal for an index in the way is thrown when there is no such index`() {
        val store = blockedStore(rangeIndex("person_name", "name"))

        assertSame(inTheWay, assertFailsWith<Neo4jException> { store.constraints.ensure(spec, replaceBlockingIndex = true) })
        assertTrue(store.schema.none { it.startsWith("DROP INDEX") })
    }

    @Test
    fun `an index in the way is kept, and the refusal thrown, when the data cannot be read`() {
        val unread = DrivineException("connection lost")
        val store = blockedStore(rangeIndex("person_email", "email"), conflicts = unread)

        val thrown = assertFailsWith<Neo4jException> { store.constraints.ensure(spec, replaceBlockingIndex = true) }

        assertSame(inTheWay, thrown)
        assertSame(unread, thrown.suppressed.single())
        assertTrue(store.schema.none { it.startsWith("DROP INDEX") })
    }
}
