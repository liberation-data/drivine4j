package org.drivine.oracle

import org.drivine.DrivineException
import org.drivine.query.QuerySpecification
import org.drivine.schema.EnsureResult
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
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

/** Indexes, constraints and the schema marker over a graph kept by Oracle. */
@OracleTests
class OracleSchemaManagementIntegrationTest {

    private val manager = pm
    private val fixtures = OracleTestDatabase.fixtures(pm)

    private fun execute(cypher: String) = fixtures.execute(QuerySpecification.withStatement(cypher))

    /** The plan Oracle makes for [sql], as text. */
    private fun plan(sql: String): String {
        manager.execute(QuerySpecification.withStatement("EXPLAIN PLAN FOR $sql"))
        return manager.query(QuerySpecification.withStatement("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY())").transform(String::class.java))
            .joinToString("\n")
    }

    @Test
    fun `range index - ensure creates, and ensure again matches`() {
        val spec = RangeIndexSpec("Proposition", "contextId")

        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)
        assertEquals("Proposition_contextId_range", manager.indexes.find(spec)?.name)
    }

    @Test
    fun `range index - composite, and one over a property two labels share`() {
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Message", listOf("sessionId", "createdAt"))) is EnsureResult.Created)
        // The index is over the property whatever the label, so the second label uses the first one's.
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Article", "slug")) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Page", "slug")) is EnsureResult.Created)

        assertTrue(manager.indexes.drop(RangeIndexSpec("Article", "slug")))
        assertNull(manager.indexes.find(RangeIndexSpec("Article", "slug")))
        assertNotNull(manager.indexes.find(RangeIndexSpec("Page", "slug")), "the other label's index is its own record")
        assertTrue("SCHEMA_IX_SLUG" in plan("SELECT nid FROM schema_node n WHERE JSON_VALUE(n.props, '$.\"slug\"') = 'x'"), "the shared index is still there")
    }

    @Test
    fun `range index - a dotted property name is one property, and a lookup by it uses the index`() {
        // A property bag flattens to keys like `metadata.context`: one property whose name has a dot in it.
        val spec = RangeIndexSpec("BagNode", "metadata.context")
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)

        val plan = plan("SELECT nid FROM schema_node n WHERE JSON_VALUE(n.props, '$.\"metadata.context\"') = 'x'")
        assertTrue("INDEX" in plan && "SCHEMA_IX_METADATACONTEXT" in plan, "expected an index scan, got:\n$plan")
    }

    @Test
    fun `uniqueness constraint - ensure creates, and the database enforces it for the label alone`() {
        val spec = UniquenessConstraintSpec("ChatSession", "sessionId")
        assertTrue(manager.constraints.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.constraints.ensure(spec) is EnsureResult.AlreadyMatching)
        assertNotNull(manager.indexes.find(RangeIndexSpec("ChatSession", "sessionId")), "a node is found by the index beneath the constraint")

        execute("CREATE (:ChatSession {sessionId: 'session-1'})")
        val refused = assertFailsWith<DrivineException> { execute("CREATE (:ChatSession {sessionId: 'session-1'})") }
        assertTrue(manager.constraints.grammar.isConstraintViolation(refused), refused.message)
        // Another label may hold the same value: the constraint is the label's.
        execute("CREATE (:Transcript {sessionId: 'session-1'})")
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
    fun `a key whose nodes pass 4000 bytes comes back with every node asked for`() {
        // JSON_ARRAYAGG with no RETURNING is VARCHAR2(4000), which these nodes overflow (ORA-40478).
        execute("CREATE " + (1..150).joinToString(", ") { "(:Member {email: 'dup@example.com', handle: 'member-${"%05d".format(it)}'})" })

        val result = manager.constraints.ensure(UniquenessConstraintSpec("Member", "email", nodeIdProperty = "handle"), violationSampleSize = 200)

        assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
        val sample = result.conflictingSample.single()
        assertEquals(150L, (sample["count"] as Number).toLong())
        assertEquals(150, (sample["nodes"] as List<*>).size)
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
    fun `vector index - recorded, found, and a change of dimension is drift`() {
        val spec = VectorIndexSpec("Chunk", "embedding", 4)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)
        assertEquals(4, manager.indexes.find(spec)?.dimensions)

        assertTrue(manager.indexes.ensure(VectorIndexSpec("Chunk", "embedding", 8)) is EnsureResult.Drift)
        assertEquals(8, manager.indexes.recreate(VectorIndexSpec("Chunk", "embedding", 8)).current.dimensions)
    }

    private fun count(sql: String): Long = manager.getOne(QuerySpecification.withStatement(sql).transform(Long::class.java))

    @Test
    fun `vector index - a table of embeddings is kept in step with the nodes, whatever writes them`() {
        val spec = VectorIndexSpec("Passage", "embedding", 3)
        // A node that is there before the index is made is taken in with it.
        execute("CREATE (:Passage {id: 'p0', embedding: [1.0, 0.0, 0.0]})")
        manager.indexes.ensure(spec)
        val embeddings = "schema_v_Passageembedding_" + Integer.toHexString("Passage\u0000embedding".hashCode())
        assertEquals(1, count("SELECT COUNT(*) FROM user_indexes WHERE table_name = '${embeddings.uppercase()}' AND index_type = 'VECTOR'"))

        execute("CREATE (:Passage {id: 'p1', embedding: [0.0, 1.0, 0.0]}), (:Passage {id: 'p2'}), (:Other {id: 'o1', embedding: [0.0, 0.0, 1.0]})")
        assertEquals(2, count("SELECT COUNT(*) FROM $embeddings"), "the passages that have an embedding, and no other node")
        execute("MATCH (p:Passage {id: 'p2'}) SET p.embedding = [0.0, 0.0, 1.0]")
        assertEquals(3, count("SELECT COUNT(*) FROM $embeddings"))
        execute("MATCH (p:Passage {id: 'p0'}) DETACH DELETE p")
        assertEquals(2, count("SELECT COUNT(*) FROM $embeddings"))

        assertTrue(manager.indexes.drop(spec))
        assertEquals(0, count("SELECT COUNT(*) FROM user_tables WHERE table_name = '${embeddings.uppercase()}'"))
        execute("CREATE (:Passage {id: 'p3', embedding: [1.0, 1.0, 0.0]})")
    }

    @Test
    fun `full-text index - a table of text is kept beside the nodes, and no index is put on the nodes themselves`() {
        val spec = FullTextIndexSpec("Essay", listOf("title", "body"))
        execute("CREATE (:Essay {id: 'e0', title: 'Graphs', body: 'stored as rows'})")
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)
        val texts = "schema_t_Essaytitlebody_" + Integer.toHexString("Essay\u0000title\u0000body".hashCode())

        execute("CREATE (:Essay {id: 'e1', title: 'Soup'}), (:Other {id: 'o2', title: 'Graphs'})")
        assertEquals(2, count("SELECT COUNT(*) FROM $texts"))
        assertEquals(1, count("SELECT COUNT(*) FROM $texts t WHERE JSON_TEXTCONTAINS(t.doc, '$.\"title\"', '{graphs}')"))
        // An Oracle Text index on the node table would answer a save's own lookups from what was last committed.
        assertEquals(0, count("SELECT COUNT(*) FROM user_indexes WHERE table_name = 'SCHEMA_NODE' AND index_type = 'DOMAIN'"))

        assertTrue(manager.indexes.drop(spec))
        assertEquals(0, count("SELECT COUNT(*) FROM user_tables WHERE table_name = '${texts.uppercase()}'"))
        execute("CREATE (:Essay {id: 'e2', title: 'After'})")
    }

    @Test
    fun `an index is dropped as the object its record names, whatever it would be named now`() {
        val spec = VectorIndexSpec("Legacy", "embedding", 3)
        manager.indexes.ensure(spec)
        val named = "schema_v_Legacyembedding_" + Integer.toHexString("Legacy\u0000embedding".hashCode())
        // The table as an earlier version might have named it, with the trigger such a table has, and the record of it.
        manager.indexes.run("ALTER TABLE $named RENAME TO schema_legacy_side")
        manager.indexes.run("DROP TRIGGER ${named}_t")
        manager.indexes.run("BEGIN EXECUTE IMMEDIATE 'CREATE TRIGGER schema_legacy_side_t AFTER INSERT ON schema_node FOR EACH ROW BEGIN NULL; END;'; END;")
        manager.indexes.run("UPDATE schema_schema SET object_name = 'schema_legacy_side' WHERE kind = 'VECTOR_INDEX' AND label = 'Legacy'")
        assertNotNull(manager.indexes.find(spec), "the index is listed, for the table its record names is there")

        assertTrue(manager.indexes.drop(spec))

        assertEquals(0, count("SELECT COUNT(*) FROM user_tables WHERE table_name = 'SCHEMA_LEGACY_SIDE'"))
        assertEquals(0, count("SELECT COUNT(*) FROM user_triggers WHERE trigger_name = 'SCHEMA_LEGACY_SIDE_T'"))
        assertNull(manager.indexes.find(spec))
        execute("CREATE (:Legacy {id: 'l1', embedding: [1.0, 0.0, 0.0]})")
    }

    @Test
    fun `a full-text index recorded with nothing made for it is dropped by forgetting it`() {
        val spec = FullTextIndexSpec("Memo", listOf("subject"))
        manager.indexes.run(
            "INSERT INTO schema_schema (kind, label, properties, name, object_name, details) " +
                "VALUES ('FULLTEXT_INDEX', 'Memo', '[\"subject\"]', '${spec.effectiveName}', NULL, '{}')"
        )
        assertNotNull(manager.indexes.find(spec), "what Oracle Text would have indexed is recorded all the same")

        assertTrue(manager.indexes.drop(spec))
        assertNull(manager.indexes.find(spec))
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
        private val pm by lazy { OracleTestDatabase.graphManager("schema") }

        @JvmStatic
        @BeforeAll
        fun clean() {
            OracleTestDatabase.clear(pm)
            // What an earlier run against the same database left: constraints first, for each comes with an index.
            pm.constraints.list().forEach { runCatching { pm.constraints.drop(UniquenessConstraintSpec(it.label, it.properties)) } }
            pm.indexes.list().forEach { runCatching { pm.execute(QuerySpecification.withStatement(pm.indexes.grammar.dropIndex(it).single().let { s -> (s as org.drivine.schema.SchemaStatement.Cypher).statement })) } }
        }
    }
}
