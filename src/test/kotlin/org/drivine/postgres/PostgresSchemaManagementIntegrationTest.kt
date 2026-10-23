package org.drivine.postgres

import org.drivine.DrivineException
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
        val page = plan("SELECT nid FROM schema_node n WHERE (n.props ->> 'slug') = 'x' AND n.labels @> '[\"Page\"]'")
        assertTrue("schema_ix_pageslug" in page, page)
    }

    @Test
    fun `range index - a dotted property name is one property, and a lookup by it uses the index`() {
        // A property bag flattens to keys like `metadata.context`: one property whose name has a dot in it.
        val spec = RangeIndexSpec("BagNode", "metadata.context")
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)

        val plan = plan("SELECT nid FROM schema_node n WHERE (n.props ->> 'metadata.context') = 'x' AND n.labels @> '[\"BagNode\"]'")
        assertTrue("schema_ix_bagnodemetadatacontext" in plan, "expected an index scan, got:\n$plan")
    }

    @Test
    fun `range index - a node of several labels is found through the index of any one of them, as the write function looks for it`() {
        manager.indexes.ensure(RangeIndexSpec("Author", "handle"))

        // What the write function asks to find a node of the shape [Author, Person] by its handle.
        val plan = plan("SELECT min(n.nid) FROM schema_node n WHERE (n.props ->> 'handle') = 'x' AND n.labels @> '[\"Author\"]' AND n.labels @> '[\"Person\"]'")
        assertTrue("schema_ix_authorhandle" in plan, plan)
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
        val plan = plan("SELECT nid FROM schema_node n WHERE (n.props ->> 'sessionId') = 'x' AND n.labels @> '[\"ChatSession\"]'")
        assertTrue("schema_uq_chatsessionsessionid" in plan, plan)
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

        val result = manager.constraints.ensure(UniquenessConstraintSpec("Person", "email"))

        assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
        val sample = (result as EnsureResult.Violation).conflictingSample.single()
        assertEquals(mapOf("email" to "dup@example.com"), sample["properties"])
        assertEquals(2L, (sample["count"] as Number).toLong())
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
        assertTrue("schema_v_passageembedding" in plan, plan)
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
        assertTrue("schema_t_essaytitlebody" in plan, plan)
        assertTrue(manager.indexes.drop(spec))
        assertNull(manager.indexes.find(spec))
    }

    @Test
    fun `an index dropped by hand is not listed, and is made again when asked for`() {
        val spec = RangeIndexSpec("Note", "topic")
        manager.indexes.ensure(spec)
        manager.indexes.run("DROP INDEX schema_ix_Notetopic_${Integer.toHexString("Note\u0000topic".hashCode())}")

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
