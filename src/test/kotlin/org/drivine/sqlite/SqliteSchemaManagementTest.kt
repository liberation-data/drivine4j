package org.drivine.sqlite

import org.drivine.DrivineException
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.cypher.store.SqliteSql as StoreSql
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
import kotlin.test.assertFailsWith
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

    /**
     * The test that the node `n`'s property [key] is the text [value], as a statement compares a value a range index holds:
     * the property's class and then its key, as the module reads them ([StoreSql.indexClass], [StoreSql.indexKey]).
     */
    private fun lookup(key: String, value: String) =
        "${StoreSql.indexClass("n.props", StoreSql.path(key))} = 'text' AND ${StoreSql.indexKey("n.props", StoreSql.path(key))} = '$value'"

    /** The name of the range index on [label]'s [properties] over the graph `schema`. */
    private fun rangeIndex(label: String, vararg properties: String) = SqliteGraph("schema").objectName("ix", listOf(label) + properties)

    /** Whether [plan] finds rows by a search of the index [name], not a scan. */
    private fun searches(plan: String, name: String) = Regex("""SEARCH \w+ USING INDEX $name\b""").containsMatchIn(plan)

    @Test
    fun `range index - ensure creates, ensure again matches, and a lookup as a statement writes it uses it`() {
        val spec = RangeIndexSpec("Proposition", "contextId")

        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)
        assertEquals("Proposition_contextId_range", manager.indexes.find(spec)?.name)
        val plan = plan("SELECT nid FROM schema_node n WHERE ${lookup("contextId", "x")} AND ${label("n", "Proposition")}")
        assertTrue(searches(plan, rangeIndex("Proposition", "contextId")), plan)
    }

    @Test
    fun `range index - a compiled Cypher lookup by a text or a truth value, written or a parameter, uses it`() {
        manager.indexes.ensure(RangeIndexSpec("Verdict", "contextId"))
        manager.indexes.ensure(RangeIndexSpec("Verdict", "settled"))
        listOf(
            Triple("MATCH (p:Verdict {contextId: 'x'}) RETURN p", emptyMap(), rangeIndex("Verdict", "contextId")),
            Triple("MATCH (p:Verdict) WHERE p.contextId = \$c RETURN p", mapOf("c" to "x"), rangeIndex("Verdict", "contextId")),
            Triple("MATCH (p:Verdict) WHERE p.settled = true RETURN p", emptyMap<String, Any?>(), rangeIndex("Verdict", "settled")),
        ).forEach { (cypher, parameters, index) ->
            val compiled = SqliteCypherCompiler("schema").compile(cypher, parameters)
            val plan = manager.query(QuerySpecification.withStatement("EXPLAIN QUERY PLAN ${compiled.sql}").bind(compiled.parameters)).joinToString("\n")
            assertTrue(searches(plan, index), "$cypher: $plan")
        }
    }

    @Test
    fun `range index - composite, and one for each label over a property two labels share`() {
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Message", listOf("sessionId", "createdAt"))) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Article", "slug")) is EnsureResult.Created)
        assertTrue(manager.indexes.ensure(RangeIndexSpec("Page", "slug")) is EnsureResult.Created)

        assertTrue(manager.indexes.drop(RangeIndexSpec("Article", "slug")))
        assertNull(manager.indexes.find(RangeIndexSpec("Article", "slug")))
        assertNotNull(manager.indexes.find(RangeIndexSpec("Page", "slug")), "the other label's index is its own")
        val page = plan("SELECT nid FROM schema_node n WHERE ${lookup("slug", "x")} AND ${label("n", "Page")}")
        assertTrue(searches(page, rangeIndex("Page", "slug")), page)
    }

    @Test
    fun `range index - a dotted property name is one property, and a node of several labels is found through either's index`() {
        assertTrue(manager.indexes.ensure(RangeIndexSpec("BagNode", "metadata.context")) is EnsureResult.Created)
        val bagged = plan("SELECT nid FROM schema_node n WHERE ${lookup("metadata.context", "x")} AND ${label("n", "BagNode")}")
        assertTrue(searches(bagged, rangeIndex("BagNode", "metadata.context")), bagged)

        manager.indexes.ensure(RangeIndexSpec("Author", "handle"))
        // What the write function asks to find a node of the shape [Author, Person] by its handle.
        val found = plan("SELECT n.nid FROM schema_node n WHERE ${lookup("handle", "x")} AND ${label("n", "Author")} AND ${label("n", "Person")} ORDER BY n.nid LIMIT 1")
        assertTrue(searches(found, rangeIndex("Author", "handle")), found)
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
        manager.indexes.run("DROP INDEX ${rangeIndex("Note", "topic")}")

        assertNull(manager.indexes.find(spec))
        assertTrue(manager.indexes.ensure(spec) is EnsureResult.Created)
    }

    @Test
    fun `an index over something else under Drivine's name is drift, not made, and a recreate replaces it`() {
        // CREATE INDEX IF NOT EXISTS passes over the name, so only the listing's object rows tell it apart (drivine4j#770).
        val spec = RangeIndexSpec("Coupon", "serial")
        val name = org.drivine.backend.sqlite.SqliteGraph("schema").objectName("ix", listOf("Coupon", "serial"))
        manager.indexes.run("CREATE INDEX $name ON schema_node (${property("", "couponRef")})")
        try {
            val drift = manager.indexes.ensure(spec)
            assertTrue(drift is EnsureResult.Drift, "got $drift")
            assertEquals(name, drift.existing.name)

            assertTrue(manager.indexes.recreate(spec) is EnsureResult.Recreated)
            assertTrue(manager.indexes.ensure(spec) is EnsureResult.AlreadyMatching)
            val plan = plan("SELECT nid FROM schema_node n WHERE ${property("n", "serial")} = 'x' AND ${label("n", "Coupon")}")
            assertTrue("USING INDEX $name" in plan, plan)
        } finally {
            manager.indexes.run("DROP INDEX IF EXISTS $name")
            runCatching { manager.indexes.drop(spec) }
        }
    }

    @Test
    fun `a table under Drivine's name for an index is drift, which a recreate leaves, and says so`() {
        // SQLite names tables and indexes alike, so a table under the name is in the way, and no recreate drops it (drivine4j#770).
        val index = RangeIndexSpec("Ledger", "serial")
        val constraint = UniquenessConstraintSpec("Ledger", "code")
        val names = listOf("ix" to index, "uq" to constraint).map { (kind, spec) -> org.drivine.backend.sqlite.SqliteGraph("schema").objectName(kind, listOf(spec.label) + spec.properties) }
        names.forEach { manager.indexes.run("CREATE TABLE $it (x INTEGER)") }
        try {
            assertTrue(manager.indexes.ensure(index) is EnsureResult.Drift)
            val refused = assertFailsWith<DrivineException> { manager.indexes.recreate(index) }
            assertTrue(names[0] in refused.message.orEmpty() && "Drop or rename it" in refused.message.orEmpty(), refused.message)
            assertTrue(manager.indexes.ensure(index) is EnsureResult.Drift, "the table is left as it was")

            assertTrue(manager.constraints.ensure(constraint) is EnsureResult.Drift)
            val recreated = manager.constraints.recreate(constraint)
            assertTrue(recreated is EnsureResult.Drift, "got $recreated")
            assertEquals(names[1], recreated.existing.name)
        } finally {
            names.forEach { manager.indexes.run("DROP TABLE IF EXISTS $it") }
            runCatching { manager.indexes.drop(index) }
            runCatching { manager.constraints.drop(constraint) }
        }
    }

    /** Each index whose name starts with [name], as SQLite keeps it, and whether it is unique. */
    private fun indexesNamed(name: String): Map<String, Boolean> =
        manager.query(QuerySpecification.withStatement(
            "SELECT name || ' ' || (sql LIKE 'CREATE UNIQUE INDEX%') FROM sqlite_master WHERE type = 'index' AND name LIKE '$name%'"
        ).transform(String::class.java)).associate { it.substringBefore(' ') to (it.substringAfter(' ') == "1") }

    /**
     * A recreate that the data allows ends with the constraint's one unique index under its name: SQLite renames no index,
     * so the old is dropped, the index made again under its name, and the one built first dropped (plans.yaml case 31).
     */
    @Test
    fun `a recreate of a uniqueness constraint leaves one unique index under its name, which enforces`() {
        val spec = UniquenessConstraintSpec("Pass", "serial")
        val name = org.drivine.backend.sqlite.SqliteGraph("schema").objectName("uq", listOf("Pass", "serial"))
        try {
            assertTrue(manager.constraints.ensure(spec) is EnsureResult.Created)
            seed.node("Pass", properties = mapOf("serial" to "p-1"))

            assertTrue(manager.constraints.recreate(spec) is EnsureResult.Recreated)

            assertEquals(mapOf(name to true), indexesNamed(name), "the index is made again under its name, and the one built first dropped")
            assertTrue(manager.constraints.ensure(spec) is EnsureResult.AlreadyMatching)
            val refused = assertFails { seed.node("Pass", properties = mapOf("serial" to "p-1")) }
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
        val name = org.drivine.backend.sqlite.SqliteGraph("schema").objectName("uq", listOf("Badge", "serial"))
        try {
            assertTrue(manager.constraints.ensure(spec) is EnsureResult.Created)
            // Its index made again by hand, not unique, so that data that repeats a key gets in.
            manager.indexes.run("DROP INDEX $name")
            manager.indexes.run("CREATE INDEX $name ON schema_node (${property("", "serial")}) WHERE ${label("", "Badge")}")
            // And what a recreate cut short left under the name it builds under first.
            manager.indexes.run("CREATE INDEX ${name}_r ON schema_node (${property("", "serial")})")
            seed.node("Badge", properties = mapOf("serial" to "b-1"))
            seed.node("Badge", properties = mapOf("serial" to "b-1"))

            val result = manager.constraints.recreate(spec)

            assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
            assertEquals(mapOf("serial" to "b-1"), (result as EnsureResult.Violation).conflictingSample.single()["properties"])
            assertEquals(mapOf(name to false), indexesNamed(name), "the old index is kept, and the leftover dropped")
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
        val copy = org.drivine.model.LowerCopies.of("handle")
        val name = org.drivine.backend.sqlite.SqliteGraph("schema").objectName("uq", listOf("Member", copy))
        try {
            assertTrue(manager.constraints.ensure(spec) is EnsureResult.Created)
            // Written past Drivine: the second node's copy is left out.
            seed.node("Member", properties = mapOf("handle" to "Ann", copy to "ann"))
            seed.node("Member", properties = mapOf("handle" to "ANN"))

            val result = manager.constraints.recreate(spec)

            assertTrue(result is EnsureResult.Violation, "expected Violation, got $result")
            assertEquals(mapOf(copy to "ann"), (result as EnsureResult.Violation).conflictingSample.single()["properties"])
            assertEquals(mapOf(name to true), indexesNamed(name), "the old index is kept, and nothing built beside it")
            val refused = assertFails { seed.node("Member", properties = mapOf("handle" to "aNN", copy to "ann")) }
            assertTrue(manager.constraints.grammar.isConstraintViolation(refused), refused.message)
        } finally {
            runCatching { manager.constraints.drop(spec) }
        }
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
