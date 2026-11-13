package org.drivine.postgres

import org.drivine.DrivineException
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.model.LowerCopies
import org.drivine.query.QuerySpecification
import org.drivine.schema.EnsureResult
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaStatement
import org.drivine.schema.SchemaVersionStore
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Indexes, constraints and the schema marker over a graph kept by Postgres. */
@PostgresTests
class PostgresSchemaManagementIntegrationTest {

    private val manager = pm
    private val fixtures = PostgresTestDatabase.fixtures(pm)

    private fun execute(cypher: String) = fixtures.execute(QuerySpecification.withStatement(cypher))

    /**
     * The plan Postgres makes for [sql], with a scan of the whole table ruled out: so it names an index
     * only where one can answer. An index's name is in it as Postgres keeps it, in lower case. [without]
     * are indexes the planner would rather use, dropped for the plan in a transaction that is rolled
     * back: what is left to it shows whether another index can answer.
     */
    /**
     * A lookup of the node `n` by its property [key] equal to the text `x`, as layout 2 indexes it
     * (drivine-cypher-spec#161): the property's JSON, `props -> 'key'`, which a range index and a
     * uniqueness constraint's index are made over.
     */
    private fun lookup(key: String) = "(n.props -> '${key.replace("'", "''")}') = '\"x\"'::jsonb"

    private fun plan(sql: String, without: List<String> = emptyList()): String {
        val connection = PostgresTestDatabase.provider.connect()
        try {
            connection.startTransaction()
            without.forEach { connection.query(QuerySpecification.withStatement("DROP INDEX $it")) }
            connection.query(QuerySpecification.withStatement("SET LOCAL enable_seqscan = off"))
            return connection.query(QuerySpecification.withStatement("EXPLAIN $sql")).joinToString("\n")
        } finally {
            connection.release()
        }
    }

    @Test
    fun `range index - ensure creates, and ensure again matches`() {
        val spec = RangeIndexSpec("Proposition", "contextId")

        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)
        assertEquals("Proposition_contextId_range", manager.indexes.find(spec)?.name)
    }

    @Test
    fun `range index - composite, and one for each label over a property two labels share`() {
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Message", listOf("sessionId", "createdAt"))) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Article", "slug")) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Page", "slug")) is EnsureResult.Created)

        assertTrue(manager.indexes.drop(RangeIndexSpec("Article", "slug")))
        assertNull(manager.indexes.find(RangeIndexSpec("Article", "slug")))
        assertNotNull(manager.indexes.find(RangeIndexSpec("Page", "slug")), "the other label's index is its own")
        val page = plan("SELECT nid FROM schema_node n WHERE ${lookup("slug")} AND n.labels @> '[\"Page\"]'")
        assertTrue("schema_ix2_pageslug" in page, page)
    }

    @Test
    fun `range index - a dotted property name is one property, and a lookup by it uses the index`() {
        // A property bag flattens to keys like `metadata.context`: one property whose name has a dot in it.
        val spec = RangeIndexSpec("BagNode", "metadata.context")
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)

        val plan = plan("SELECT nid FROM schema_node n WHERE ${lookup("metadata.context")} AND n.labels @> '[\"BagNode\"]'")
        assertTrue("schema_ix2_bagnodemetadatacontext" in plan, "expected an index scan, got:\n$plan")
    }

    @Test
    fun `range index - a node of several labels is found through the index of any one of them, as the write function looks for it`() {
        manager.indexes.ensure(RangeIndexSpec("Author", "handle"))

        // What the write function asks to find a node of the shape [Author, Person] by its handle.
        val plan = plan("SELECT min(n.nid) FROM schema_node n WHERE ${lookup("handle")} AND n.labels @> '[\"Author\"]' AND n.labels @> '[\"Person\"]'")
        assertTrue("schema_ix2_authorhandle" in plan, plan)
    }

    @Test
    fun `uniqueness constraint - ensure creates, and the database enforces it for the label alone`() {
        val spec = UniquenessConstraintSpec("ChatSession", "sessionId")
        assertTrue(manager.constraints.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.constraints.ensure(spec) is EnsureResult.AlreadyMatching)

        execute("CREATE (:ChatSession {sessionId: 'session-1'})")
        val refused = assertFailsWith<DrivineException> { execute("CREATE (:ChatSession {sessionId: 'session-1'})") }
        assertTrue(manager.constraints.grammar.isConstraintViolation(refused), refused.message)
        // Another label may hold the same value, and a node without the property is not constrained.
        execute("CREATE (:Transcript {sessionId: 'session-1'})")
        execute("CREATE (:ChatSession {title: 'a'}), (:ChatSession {title: 'b'})")
        // The constraint's index finds a node, as a range index does.
        val plan = plan("SELECT nid FROM schema_node n WHERE ${lookup("sessionId")} AND n.labels @> '[\"ChatSession\"]'")
        assertTrue("schema_uq2_chatsessionsessionid" in plan, plan)
    }

    @Test
    fun `a range index made first is not in the way of a uniqueness constraint, and is kept when replacing is asked`() {
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Account", "accountId")) is EnsureResult.Created)
        val spec = UniquenessConstraintSpec("Account", "accountId")

        val created = manager.constraints.ensure(spec, replaceBlockingIndex = true)

        assertTrue(created is EnsureResult.Created, "expected Created, got $created")
        assertNotNull(manager.indexes.find(RangeIndexSpec("Account", "accountId")))
    }

    @Test
    fun `composite uniqueness constraint`() {
        val spec = UniquenessConstraintSpec("Membership", listOf("tenantId", "userId"))
        assertTrue(manager.constraints.ensure(spec) is EnsureResult.Created)

        execute("CREATE (:Membership {tenantId: 't1', userId: 'u1'}), (:Membership {tenantId: 't1', userId: 'u2'})")
        assertFailsWith<DrivineException> { execute("CREATE (:Membership {tenantId: 't1', userId: 'u1'})") }
    }

    @Test
    fun `constraint on violating data returns Violation with a sample of what repeats`() {
        execute("CREATE (:Person {email: 'dup@example.com', name: 'A'}), (:Person {email: 'dup@example.com', name: 'B'})")

        val result = manager.constraints.ensure(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "name"))

        assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
        val sample = (result as EnsureResult.Violation).conflictingSample.single()
        assertEquals(mapOf("email" to "dup@example.com"), sample["properties"])
        assertEquals(2L, (sample["count"] as Number).toLong())

        // Each conflicting node is named, by the engine's id and by the property it is identified by.
        val nodes = (result.conflictingSample.single()["nodes"] as List<*>).map { it as Map<*, *> }
        assertEquals(setOf("A", "B"), nodes.map { it["name"] }.toSet())
        assertEquals(2, nodes.mapNotNull { it["elementId"] }.toSet().size)
        assertNull(manager.constraints.find(UniquenessConstraintSpec("Person", "email")))
    }

    @Test
    fun `drop a constraint, and what it refused is allowed again`() {
        val spec = UniquenessConstraintSpec("Ticket", "number")
        manager.constraints.ensure(spec)
        execute("CREATE (:Ticket {number: 1})")

        assertTrue(manager.constraints.drop(spec))
        assertNull(manager.constraints.find(spec))
        execute("CREATE (:Ticket {number: 1})")
    }

    /** Each index of the graph's node table whose name starts with [name], as Postgres keeps it, and whether it is unique. */
    private fun indexesNamed(name: String): Map<String, Boolean> =
        manager.query(QuerySpecification.withStatement(
            "SELECT c.relname || ' ' || i.indisunique::text FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid " +
                "WHERE i.indrelid = 'schema_node'::regclass AND c.relname LIKE '${name.lowercase()}%'"
        ).transform(String::class.java)).associate { it.substringBefore(' ') to it.substringAfter(' ').toBoolean() }

    /** A recreate that the data allows swaps the index built beside the old into its name, and leaves nothing beside it (plans.yaml case 31). */
    @Test
    fun `a recreate of a uniqueness constraint leaves one unique index under its name, which enforces`() {
        val spec = UniquenessConstraintSpec("Pass", "serial")
        val name = PostgresGraph("schema").objectName("uq", listOf("Pass", "serial"))
        try {
            assertTrue(manager.constraints.ensure(spec) is EnsureResult.Created)
            execute("CREATE (:Pass {serial: 'p-1'})")

            assertTrue(manager.constraints.recreate(spec) is EnsureResult.Recreated)

            assertEquals(mapOf(name.lowercase() to true), indexesNamed(name), "the index built is swapped into the old one's name")
            assertTrue(manager.constraints.ensure(spec) is EnsureResult.AlreadyMatching)
            val refused = assertFailsWith<DrivineException> { execute("CREATE (:Pass {serial: 'p-1'})") }
            assertTrue(manager.constraints.grammar.isConstraintViolation(refused), refused.message)
        } finally {
            runCatching { manager.constraints.drop(spec) }
        }
    }

    /**
     * A uniqueness constraint is made again beside its old index, which is swapped out only once the new one is built
     * (drivine-cypher-spec#135 §4, plans.yaml case 31b): the data refusing the build leaves the old index and its
     * record as they were, and nothing beside them, as the index a recreate cut short had left under the name it builds
     * under first is dropped before the build.
     */
    @Test
    fun `a recreate of a uniqueness constraint the data refuses at its build keeps the index it had, and leaves none beside it`() {
        val spec = UniquenessConstraintSpec("Badge", "serial")
        val name = PostgresGraph("schema").objectName("uq", listOf("Badge", "serial"))
        try {
            assertTrue(manager.constraints.ensure(spec) is EnsureResult.Created)
            // Its index made again by hand, not unique, so that data that repeats a key gets in.
            manager.indexes.run("DROP INDEX $name")
            manager.indexes.run("CREATE INDEX $name ON schema_node ((props ->> 'serial')) WHERE labels @> '[\"Badge\"]'")
            // And what a recreate cut short left under the name it builds under first.
            manager.indexes.run("CREATE INDEX ${name}_r ON schema_node ((props ->> 'serial'))")
            execute("CREATE (:Badge {serial: 'b-1'}), (:Badge {serial: 'b-1'})")

            val result = manager.constraints.recreate(spec)

            assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
            assertEquals(mapOf("serial" to "b-1"), (result as EnsureResult.Violation).conflictingSample.single()["properties"])
            assertEquals(mapOf(name.lowercase() to false), indexesNamed(name), "the old index is kept, and the leftover dropped")
            assertEquals(1L, manager.getOne(QuerySpecification.withStatement(
                "SELECT count(*) FROM schema_schema WHERE kind = 'UNIQUENESS_CONSTRAINT' AND label = 'Badge'"
            ).transform(Long::class.java)), "the record is kept")
        } finally {
            runCatching { manager.constraints.drop(spec) }
            manager.indexes.run("DROP INDEX IF EXISTS ${name}_r")
        }
    }

    /**
     * A constraint that ignores case fills its lowercased copies in again before the build, with the old index standing
     * (plans.yaml case 31m): a copy left out of step by a write past Drivine, whose property lowercased repeats another's
     * copy, is refused by the old index. That is reported as a violation, and the old index still enforces.
     */
    @Test
    fun `a recreate whose refill of the lowercased copies the old index refuses keeps that index, which still enforces`() {
        val spec = UniquenessConstraintSpec("Member", "handle", ignoreCase = true)
        val copy = LowerCopies.of("handle")
        val name = PostgresGraph("schema").objectName("uq", listOf("Member", copy))
        try {
            assertTrue(manager.constraints.ensure(spec) is EnsureResult.Created)
            // Written past Drivine: the second node's copy is left out.
            execute("CREATE (:Member {handle: 'Ann', `$copy`: 'ann'}), (:Member {handle: 'ANN'})")

            val result = manager.constraints.recreate(spec)

            assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
            assertEquals(mapOf(copy to "ann"), (result as EnsureResult.Violation).conflictingSample.single()["properties"])
            assertEquals(mapOf(name.lowercase() to true), indexesNamed(name), "the old index is kept, and nothing built beside it")
            val refused = assertFailsWith<DrivineException> { execute("CREATE (:Member {handle: 'aNN', `$copy`: 'ann'})") }
            assertTrue(manager.constraints.grammar.isConstraintViolation(refused), refused.message)
        } finally {
            runCatching { manager.constraints.drop(spec) }
        }
    }

    @Test
    fun `vector index - recorded, found, a change of dimension is drift, and a search through it uses it`() {
        val spec = VectorIndexSpec("Passage", "embedding", 3)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)
        assertEquals(3, manager.indexes.find(spec)?.dimensions)
        assertTrue(manager.indexes.ensure(VectorIndexSpec("Passage", "embedding", 5)) is EnsureResult.Drift)

        // A node with an embedding of another size is left out of the index, and not refused.
        execute("CREATE (:Passage {id: 'p1', embedding: [1.0, 0.0, 0.0]}), (:Passage {id: 'p2', embedding: [1.0, 0.0]})")
        val search = "SELECT s.nid FROM schema_node s WHERE s.labels @> '[\"Passage\"]' AND jsonb_typeof((s.props -> 'embedding')) = 'array' " +
            "AND jsonb_array_length((s.props -> 'embedding')) = 3 ORDER BY CAST(CAST((s.props -> 'embedding') AS text) AS vector(3)) <=> CAST('[1,0,0]' AS vector(3)) LIMIT 5"
        val plan = plan(search)
        assertTrue("schema_v2_passageembedding" in plan, plan)
    }

    @Test
    fun `full-text index - its trigrams answer whether the text holds a term's words`() {
        val spec = FullTextIndexSpec("Essay", listOf("title", "body"))
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)

        // Essays enough that the label alone is no way to find one, and the planner told so.
        manager.execute(QuerySpecification.withStatement(
            "INSERT INTO schema_node (labels, props) SELECT '[\"Essay\"]', jsonb_build_object('title', 'essay ' || i, 'body', 'soup ' || i) FROM generate_series(1, 500) i"
        ))
        manager.execute(QuerySpecification.withStatement("ANALYZE schema_node"))
        val text = "lower(COALESCE((c.props ->> 'title'), '') || ' ' || COALESCE((c.props ->> 'body'), ''))"
        // Over a few hundred nodes the planner reads the label's through the index over labels; without it, the trigrams answer.
        val plan = plan("SELECT c.nid FROM schema_node c WHERE c.labels @> '[\"Essay\"]' AND ($text LIKE '%graph%')", without = listOf("schema_node_labels"))
        assertTrue("schema_t2_essaytitlebody" in plan, plan)
        assertTrue(manager.indexes.drop(spec))
        assertNull(manager.indexes.find(spec))
    }

    @Test
    fun `an index dropped by hand is not listed, and is made again when asked for`() {
        val spec = RangeIndexSpec("Note", "topic")
        manager.indexes.ensure(spec)
        manager.indexes.run("DROP INDEX schema_ix2_Notetopic_${Integer.toHexString("Note\u0000topic".hashCode())}")

        assertNull(manager.indexes.find(spec))
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
    }

    @Test
    fun `schema version marker round-trips, with its inventory`() {
        val store = SchemaVersionStore(manager)
        store.clear()
        assertNull(store.storedVersion())
        assertNull(store.storedInventory())

        store.record("model-v1", listOf("a", "b"))
        assertEquals("model-v1", store.storedVersion())
        assertEquals(listOf("a", "b"), store.storedInventory())

        store.record("model-v1", listOf("a", "b", "c"), runtime = setOf("b", "c"), stale = setOf("c"))
        val record = store.storedRecord()!!
        assertEquals(listOf("a", "b", "c"), record.keys, "an item ensured at runtime is kept in the inventory")
        assertEquals(setOf("b", "c"), record.runtime)
        assertEquals(setOf("c"), record.stale)

        store.record("model-v2")
        assertEquals("model-v2", store.storedVersion())
        assertEquals(emptyList(), store.storedInventory(), "an empty inventory is one, and not an absent one")

        store.record(null, listOf("c"))
        assertNull(store.storedVersion(), "a version that is null is none")

        store.clear()
        assertNull(store.storedVersion())
    }

    @Test
    fun `ensureSingleton repairs duplicate markers and constrains the marker key`() {
        val store = SchemaVersionStore(manager)
        store.clear()
        // The pre-constraint race: two processes each made a marker.
        manager.constraints.drop(UniquenessConstraintSpec(SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY))
        execute("CREATE (:`${SchemaVersionStore.LABEL}` {scope: 'schema', version: 'older', appliedAt: 1}), (:`${SchemaVersionStore.LABEL}` {scope: 'schema', version: 'newer', appliedAt: 2})")

        store.ensureSingleton()

        assertEquals(1L, fixtures.getOne(QuerySpecification.withStatement("MATCH (m:`${SchemaVersionStore.LABEL}`) RETURN count(m)").transform(Long::class.java)))
        assertEquals("newer", store.storedVersion())
        assertNotNull(manager.constraints.find(UniquenessConstraintSpec(SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY)))
    }

    companion object {
        private val pm by lazy { PostgresTestDatabase.graphManager("schema") }

        @JvmStatic
        @BeforeAll
        fun clean() {
            PostgresTestDatabase.clear(pm)
            // What an earlier run against the same database left.
            pm.constraints.list().forEach { runCatching { pm.constraints.drop(UniquenessConstraintSpec(it.label, it.properties)) } }
            pm.indexes.list().forEach { runCatching { pm.execute(QuerySpecification.withStatement(pm.indexes.grammar.dropIndex(it).single().let { s -> (s as SchemaStatement.Cypher).statement })) } }
        }
    }
}
