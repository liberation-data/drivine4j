package org.drivine.sqlite

import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.schema.EnsureResult
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaVersionStore
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.store.StoreIdentityResolver
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import sample.stateless.Claim
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Indexes, constraints, the schema marker and the store's stamp over a graph kept by SQLite. */
class SqliteSchemaManagementTest {

    private val manager = pm
    private val seed = SqliteSeed(pm)

    /** The plan SQLite makes for [sql]: it names an index where one answers. */
    private fun plan(sql: String): String = manager.query(QuerySpecification.withStatement("EXPLAIN QUERY PLAN $sql")).joinToString("\n")

    private fun property(alias: String, key: String) = org.drivine.backend.sqlite.SqliteSql.property(alias, key)

    private fun label(alias: String, label: String) = org.drivine.backend.sqlite.SqliteSql.hasLabel(alias, label)

    @Test
    fun `range index - ensure creates, ensure again matches, and a lookup as a statement writes it uses it`() {
        val spec = RangeIndexSpec("Proposition", "contextId")

        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)
        assertEquals("Proposition_contextId_range", manager.indexes.find(spec)?.name)
        val plan = plan("SELECT nid FROM schema_node n WHERE ${property("n", "contextId")} = 'x' AND ${label("n", "Proposition")}")
        assertTrue("USING INDEX schema_ix_PropositioncontextId" in plan, plan)
    }

    @Test
    fun `range index - composite, and one for each label over a property two labels share`() {
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Message", listOf("sessionId", "createdAt"))) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Article", "slug")) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Page", "slug")) is EnsureResult.Created)

        assertTrue(manager.indexes.drop(RangeIndexSpec("Article", "slug")))
        assertNull(manager.indexes.find(RangeIndexSpec("Article", "slug")))
        assertNotNull(manager.indexes.find(RangeIndexSpec("Page", "slug")), "the other label's index is its own")
        val page = plan("SELECT nid FROM schema_node n WHERE ${property("n", "slug")} = 'x' AND ${label("n", "Page")}")
        assertTrue("schema_ix_Pageslug" in page, page)
    }

    @Test
    fun `range index - a dotted property name is one property, and a node of several labels is found through either's index`() {
        assertTrue(manager.indexes.ensure(RangeIndexSpec("BagNode", "metadata.context")) is EnsureResult.Created)
        val bagged = plan("SELECT nid FROM schema_node n WHERE ${property("n", "metadata.context")} = 'x' AND ${label("n", "BagNode")}")
        assertTrue("schema_ix_BagNodemetadatacontext" in bagged, bagged)

        manager.indexes.ensure(RangeIndexSpec("Author", "handle"))
        // What the write function asks to find a node of the shape [Author, Person] by its handle.
        val found = plan("SELECT n.nid FROM schema_node n WHERE ${property("n", "handle")} = 'x' AND ${label("n", "Author")} AND ${label("n", "Person")} ORDER BY n.nid LIMIT 1")
        assertTrue("schema_ix_Authorhandle" in found, found)
    }

    @Test
    fun `uniqueness constraint - ensure creates, and the database enforces it for the label alone, on a save too`() {
        val spec = UniquenessConstraintSpec("ChatSession", "sessionId")
        assertTrue(manager.constraints.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.constraints.ensure(spec) is EnsureResult.AlreadyMatching)

        seed.node("ChatSession", properties = mapOf("sessionId" to "session-1"))
        val refused = assertFails { seed.node("ChatSession", properties = mapOf("sessionId" to "session-1")) }
        assertTrue(manager.constraints.grammar.isConstraintViolation(refused), refused.message)
        // Another label may hold the same value, and a node without the property is not constrained.
        seed.node("Transcript", properties = mapOf("sessionId" to "session-1"))
        seed.node("ChatSession", properties = mapOf("title" to "a"))
        seed.node("ChatSession", properties = mapOf("title" to "b"))

        // A save the constraint refuses is refused whole, as the write function's error.
        manager.constraints.ensure(UniquenessConstraintSpec("Claim", "text"))
        val stateless = StatelessGraphObjectManager(manager, Neo4jObjectMapper.instance, SubtypeRegistry())
        stateless.save(Claim("unique-1", "the same"))
        val saved = assertFails { stateless.save(Claim("unique-2", "the same")) }
        assertTrue(manager.constraints.grammar.isConstraintViolation(saved), saved.message)
        assertNull(stateless.load("unique-2", Claim::class.java))
    }

    @Test
    fun `constraint on violating data returns Violation with a sample of what repeats`() {
        seed.node("Person", properties = mapOf("email" to "dup@example.com", "name" to "A"))
        seed.node("Person", properties = mapOf("email" to "dup@example.com", "name" to "B"))

        val result = manager.constraints.ensure(UniquenessConstraintSpec("Person", "email", nodeIdProperty = "name"))

        assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
        val sample = (result as EnsureResult.Violation).conflictingSample.single()
        assertEquals(mapOf("email" to "dup@example.com"), sample["properties"])
        assertEquals(2L, (sample["count"] as Number).toLong())
        val nodes = (sample["nodes"] as List<*>).map { it as Map<*, *> }
        assertEquals(setOf("A", "B"), nodes.map { it["name"] }.toSet())
        assertEquals(2, nodes.mapNotNull { it["elementId"] }.toSet().size)
        assertNull(manager.constraints.find(UniquenessConstraintSpec("Person", "email")))
    }

    @Test
    fun `drop a constraint, and what it refused is allowed again`() {
        val spec = UniquenessConstraintSpec("Ticket", "number")
        manager.constraints.ensure(spec)
        seed.node("Ticket", properties = mapOf("number" to 1))

        assertTrue(manager.constraints.drop(spec))
        assertNull(manager.constraints.find(spec))
        seed.node("Ticket", properties = mapOf("number" to 1))
    }

    @Test
    fun `a vector and a full-text index are recorded and found, and a change of dimension is drift`() {
        val vector = VectorIndexSpec("Passage", "embedding", 3)
        assertTrue(manager.indexes.ensure(vector) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(vector) is EnsureResult.AlreadyMatching)
        assertEquals(3, manager.indexes.find(vector)?.dimensions)
        assertTrue(manager.indexes.ensure(VectorIndexSpec("Passage", "embedding", 5)) is EnsureResult.Drift)

        val text = FullTextIndexSpec("Essay", listOf("title", "body"))
        assertTrue(manager.indexes.ensure(text) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(text) is EnsureResult.AlreadyMatching)
        assertTrue(manager.indexes.drop(text))
        assertNull(manager.indexes.find(text))

        // What a record keeps beside its properties is read back, so the index matches again and not as drift.
        val euclidean = VectorIndexSpec("Passage", "summary", 2, SimilarityFunction.EUCLIDEAN)
        assertTrue(manager.indexes.ensure(euclidean) is EnsureResult.Created)
        assertEquals(SimilarityFunction.EUCLIDEAN, manager.indexes.find(euclidean)?.similarity)
        assertTrue(manager.indexes.ensure(euclidean) is EnsureResult.AlreadyMatching)
        val analyzed = FullTextIndexSpec("Essay", listOf("title"), analyzer = "an \"english\" one")
        assertTrue(manager.indexes.ensure(analyzed) is EnsureResult.Created)
        assertEquals("an \"english\" one", manager.indexes.find(analyzed)?.analyzer)
        assertTrue(manager.indexes.ensure(analyzed) is EnsureResult.AlreadyMatching)
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
        seed.node(SchemaVersionStore.LABEL, properties = mapOf("scope" to "schema", "version" to "older", "appliedAt" to 1))
        seed.node(SchemaVersionStore.LABEL, properties = mapOf("scope" to "schema", "version" to "newer", "appliedAt" to 2))

        store.ensureSingleton()

        val markers = "SELECT COUNT(*) FROM schema_node n WHERE ${label("n", SchemaVersionStore.LABEL)}"
        assertEquals(1L, manager.getOne(QuerySpecification.withStatement(markers).transform(Long::class.java)))
        assertEquals("newer", store.storedVersion())
        assertNotNull(manager.constraints.find(UniquenessConstraintSpec(SchemaVersionStore.LABEL, SchemaVersionStore.SCOPE_PROPERTY)))
    }

    @Test
    fun `the store is stamped once, and read back the same`() {
        val first = StoreIdentityResolver(manager).resolve()
        val again = StoreIdentityResolver(manager).resolve()

        assertEquals(first.id, again.id)
    }

    companion object {
        private val pm by lazy { SqliteTestDatabase.graphManager("schema") }

        @JvmStatic
        @BeforeAll
        fun clean() {
            SqliteTestDatabase.drop("schema")
            org.drivine.backend.sqlite.SqliteGraph("schema").ensure(pm)
        }
    }
}
