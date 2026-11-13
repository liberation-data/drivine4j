package org.drivine.manager

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.Root
import org.drivine.annotation.VectorIndex
import org.drivine.backend.GraphBackend
import org.drivine.backend.GraphStatement
import org.drivine.backend.oracle.OracleBackend
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSelect
import org.drivine.backend.oracle.OracleSql
import org.drivine.backend.oracle.OracleWriteBlocks
import org.drivine.backend.postgres.PostgresBackend
import org.drivine.backend.postgres.PostgresSql
import org.drivine.backend.sql.NodeIds
import org.drivine.backend.sql.NodeShape
import org.drivine.backend.sql.WriteReturns
import org.drivine.backend.sqlite.SqliteBackend
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.FragmentModel
import org.drivine.model.Stamps
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.QuerySpecification
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SqlCypherFixtures
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@NodeFragment(labels = ["LookupLong"])
data class LookupLong(@NodeId val id: Long, val name: String)

@NodeFragment(labels = ["LookupDate"])
data class LookupDate(@NodeId val id: LocalDate, val name: String)

@NodeFragment(labels = ["LookupText"])
data class LookupText(@NodeId val id: String, val name: String)

/** A root with an embedding, which `loadNearestAmong` finds among others by their ids' text. */
@NodeFragment(labels = ["LookupNear"])
data class LookupNear(@NodeId val id: LocalDate, val name: String, @VectorIndex val embedding: List<Float>? = null)

/** A root with an embedding and a `LocalDateTime` id, whose text `toString` writes without its seconds where they are 0. */
@NodeFragment(labels = ["LookupNearMoment"])
data class LookupNearMoment(@NodeId val id: LocalDateTime, val name: String, @VectorIndex val embedding: List<Float>? = null)

@NodeFragment(labels = ["LookupTarget"])
data class LookupTarget(@NodeId val id: String, val name: String)

/** The target joined to days, which a save that replaces the list unlinks by their ids. */
@GraphView
data class LookupTargetDays(
    @Root val target: LookupTarget,
    @GraphRelationship(type = "ON_DAY", direction = Direction.OUTGOING)
    val days: List<LookupDate> = emptyList(),
)

/**
 * A node found by its id on a SQL store, under the store layout 2 (liberation-data/drivine-cypher-spec#245): a save, a load,
 * a delete by id and `loadRelated` compare the id as the store's index is made over it, the property's JSON on Postgres and
 * its class and key on Oracle and SQLite. So the index answers each lookup, and a temporal id, held as its tag, is found: a
 * `LocalDate` id saved twice is one node. The id a load or a delete gives as text is read as the `@NodeId` field's type
 * first; text that is no value of it finds nothing, and a `String` id is the text given.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class NodeIdLookupContract {

    /** The store's own manager, which runs SQL. */
    abstract val store: org.drivine.manager.NonTransactionalPersistenceManager

    /** The backend that writes the statements a manager runs on the store. */
    internal abstract val backend: GraphBackend

    /** The plan the store makes for [sql] with [bindings], as text. */
    abstract fun plan(sql: String, bindings: Map<String, Any?>): String

    /** Whether [plan] finds the node of [label] by the index over its `id`, and does not read the label's nodes whole. */
    abstract fun readsIndex(plan: String, label: String): Boolean

    /** The plan of the delete by id of a [type] whose id is given as [id]. */
    open fun deletePlan(type: Class<*>, id: String): String = planOf(backend.delete(type, null, CascadeType.NONE), id)

    /** Gathers what the store's planner knows of the node table. */
    open fun analyze() {}

    private val pm by lazy { SqlCypherFixtures.over(store) }
    private val manager by lazy { StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, SubtypeRegistry()) }

    private fun count(cypher: String): Long = pm.query(QuerySpecification.withStatement(cypher).transform(Long::class.java)).single()

    /** The plan of [statement] with the id given as [id], bound as the manager binds it. */
    internal fun planOf(statement: GraphStatement, id: String): String {
        val spec = statement.spec(mapOf(GraphBackend.ID_PARAM to id))
        return plan(spec.statement!!.text, spec.parameters)
    }

    @BeforeAll
    fun seed() {
        SqlCypherFixtures.clear(store)
        listOf("LookupLong", "LookupDate", "LookupText", "LookupTarget", "LookupNear").forEach { store.constraints.ensure(UniquenessConstraintSpec(it, "id")) }
        // Enough nodes of each label that reading them whole costs more than the index does.
        listOf("LookupLong", "LookupDate", "LookupText").forEach { label ->
            pm.execute(QuerySpecification.withStatement("UNWIND range(1000, ${1000 + FILLERS - 1}) AS k CREATE (:$label {id: k, name: 'filler'})"))
        }
        pm.execute(QuerySpecification.withStatement("UNWIND range(1000, ${1000 + FILLERS - 1}) AS k CREATE (:LookupNear {id: k, name: 'filler', embedding: [1.0, 0.0]})"))
        manager.save(LookupTarget("x", "the target"))
        // Every filler is joined to the target, so a relationship of the type is no rarer than the node it leaves.
        listOf("LookupLong", "LookupDate", "LookupText").forEach { label ->
            pm.execute(QuerySpecification.withStatement("MATCH (n:$label), (t:LookupTarget {id: 'x'}) CREATE (n)-[:POINTS_TO]->(t)"))
        }
        analyze()
    }

    // ----- A temporal id -----

    @Test
    fun `a LocalDate id saved twice is one node, and loads by its id's text`() {
        val day = LocalDate.of(2024, 1, 2)
        manager.save(LookupDate(day, "first"))
        manager.save(LookupDate(day, "second"))

        assertEquals(1L, count("MATCH (n:LookupDate) WHERE n.name IN ['first', 'second'] RETURN count(n)"))
        assertEquals(LookupDate(day, "second"), manager.load("2024-01-02", LookupDate::class.java))
    }

    @Test
    fun `one save of a LocalDate id twice makes one node, as the plan keys the nodes it makes by the id as stored`() {
        val day = LocalDate.of(2024, 1, 3)
        manager.saveAll(listOf(LookupDate(day, "batch first"), LookupDate(day, "batch second")))

        assertEquals(1L, count("MATCH (n:LookupDate) WHERE n.name IN ['batch first', 'batch second'] RETURN count(n)"))
        assertEquals(day, manager.load("2024-01-03", LookupDate::class.java)?.id)
    }

    // ----- Load, delete by id and relatedTo, for a Long, a LocalDate and a String id -----

    /** Saves [node], joins it to the target, and finds it by [text] through a load, `loadRelated` and a delete. */
    private fun <T : Any> lookups(node: T, text: String) {
        val type = node.javaClass
        manager.save(node)
        assertTrue(manager.edges.relate(NodeRef(type, text), NodeRef(LookupTarget::class.java, "x"), "POINTS_TO"), "a ref by the id's text finds the node")

        assertEquals(node, manager.load(text, type))
        assertEquals(listOf("x"), manager.edges.loadRelated(NodeRef(type, text), "POINTS_TO", Direction.OUTGOING, LookupTarget::class.java).map { it.id })
        assertEquals(1, manager.delete(text, type))
        assertNull(manager.load(text, type))
    }

    @Test
    fun `a Long id is found by its text`() = lookups(LookupLong(5, "five"), "5")

    @Test
    fun `a LocalDate id is found by its text`() = lookups(LookupDate(LocalDate.of(2024, 3, 4), "march"), "2024-03-04")

    @Test
    fun `a String id is found by its text`() = lookups(LookupText("t5", "text"), "t5")

    // ----- Relate (a `ref` step) and patch (a `patch` step), each finding its node by the id's JSON -----

    /**
     * Joins [node], saved, to the target by refs given the id as text and as its value; patches its name; and refers to a
     * node of the class that isn't there, [absent], which is not made, and joins nothing, as before.
     */
    private fun <T : Any> refsAndPatches(node: T, text: String, renamed: T, absent: String) {
        val type = node.javaClass
        val target = NodeRef(LookupTarget::class.java, "x")
        manager.save(node)

        assertTrue(manager.edges.relate(NodeRef(type, text), target, "REFERS_TO"), "a ref by the id's text")
        assertTrue(manager.edges.relate(NodeRef(type, assertNotNull(manager.load(text, type)).let { idOf(it) }), target, "REFERS_TO", mode = RelateMode.CREATE), "a ref by the id's value")
        val label = FragmentModel.from(type).labels.single()
        val joined = "MATCH (:$label)-[r:REFERS_TO]->(:LookupTarget) RETURN count(r)"
        assertEquals(2L, count(joined))

        assertEquals(PatchResult(found = true, applied = true, changed = true), manager.patchAllFields(listOf(renamed), only = setOf("name")).single())
        assertEquals(renamed, manager.load(text, type))

        assertTrue(!manager.edges.relate(NodeRef(type, absent), target, "REFERS_TO"), "a ref to a node that isn't there joins nothing")
        assertTrue(!manager.edges.relate(target, NodeRef(type, absent), "REFERS_TO"), "nor as the relationship's target")
        assertEquals(0L, count("MATCH (t:LookupTarget)-[r:REFERS_TO]->() RETURN count(r)"))
        assertEquals(2L, count(joined))
    }

    /** The id of a loaded [node], as its `@NodeId` field holds it. */
    private fun idOf(node: Any): Any = FragmentModel.from(node.javaClass).nodeIdField!!.let { field ->
        node.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(node)
    }

    @Test
    fun `a relate and a patch find a node of a Long id`() = refsAndPatches(LookupLong(21, "a"), "21", LookupLong(21, "b"), "22")

    @Test
    fun `a relate and a patch find a node of a LocalDate id`() =
        refsAndPatches(LookupDate(LocalDate.of(2024, 5, 6), "a"), "2024-05-06", LookupDate(LocalDate.of(2024, 5, 6), "b"), "2024-05-07")

    @Test
    fun `a relate and a patch find a node of a String id`() = refsAndPatches(LookupText("t21", "a"), "t21", LookupText("t21", "b"), "t22")

    /**
     * A save that replaces a list keeps the targets the object still holds, told by their ids as the plan keys them, and
     * unlinks the rest: a target of a temporal id is read back by its tag's text `v`, which the plan's `ids` hold. Read as
     * anything else, the dropped day would be kept, or the held one unlinked and joined again, which gives it a new
     * relationship token, its stamp's second.
     */
    @Test
    fun `a save that replaces a list unlinks a target of a temporal id by its tag's text, and keeps the one still held`() {
        val target = LookupTarget("days", "a target of days")
        val first = LookupDate(LocalDate.of(2025, 1, 1), "first day")
        val second = LookupDate(LocalDate.of(2025, 1, 2), "second day")
        manager.save(LookupTargetDays(target, listOf(first, second)), relationships = Replace(LookupTargetDays::days))
        val joined = "MATCH (:LookupTarget {id: 'days'})-[:ON_DAY]->(d:LookupDate) RETURN d.name ORDER BY d.name"
        assertEquals(listOf("first day", "second day"), pm.query(QuerySpecification.withStatement(joined).transform(String::class.java)))
        val stamp = "MATCH (d:LookupDate {name: 'second day'}) RETURN d.${Stamps.QUOTED}"
        val held = pm.query(QuerySpecification.withStatement(stamp).transform(String::class.java)).single()

        manager.save(LookupTargetDays(target, listOf(second)), relationships = Replace(LookupTargetDays::days))

        assertEquals(listOf("second day"), pm.query(QuerySpecification.withStatement(joined).transform(String::class.java)))
        assertEquals(held, pm.query(QuerySpecification.withStatement(stamp).transform(String::class.java)).single(), "the held day's relationship is untouched")
        assertEquals(2L, count("MATCH (d:LookupDate) WHERE d.name IN ['first day', 'second day'] RETURN count(d)"), "unlinked, not deleted")
    }

    @Test
    fun `a patch of a node that isn't there finds none`() {
        assertEquals(PatchResult.ABSENT, manager.patchAllFields(listOf(LookupDate(LocalDate.of(1999, 1, 1), "none")), only = setOf("name")).single())
        assertEquals(0L, count("MATCH (n:LookupDate) WHERE n.name = 'none' RETURN count(n)"))
    }

    @Test
    fun `text on a String id finds only the string, not a number of the same text`() {
        pm.execute(QuerySpecification.withStatement("CREATE (:LookupText {id: 6, name: 'number'})"))
        manager.save(LookupText("6", "string"))

        assertEquals(LookupText("6", "string"), manager.load("6", LookupText::class.java))
        assertEquals(1, manager.delete("6", LookupText::class.java))
        assertEquals(1L, count("MATCH (n:LookupText) WHERE n.id = 6 RETURN count(n)"))
        assertNull(manager.load("6", LookupText::class.java))
    }

    @Test
    fun `text that is no number finds nothing on a Long id, and nothing is thrown`() {
        manager.save(LookupLong(7, "seven"))
        val nowhere = NodeRef(LookupLong::class.java, "abc")

        assertNull(manager.load("abc", LookupLong::class.java))
        assertEquals(0, manager.delete("abc", LookupLong::class.java))
        assertEquals(emptyList(), manager.edges.loadRelated(nowhere, "POINTS_TO", Direction.OUTGOING, LookupTarget::class.java))
        assertTrue(!manager.edges.relate(nowhere, NodeRef(LookupTarget::class.java, "x"), "POINTS_TO"))
        assertEquals(LookupLong(7, "seven"), manager.load("7", LookupLong::class.java))
    }

    // ----- Each lookup reads the index -----

    private fun <T : Any> readThroughIndex(type: Class<T>, label: String, text: String) {
        val load = planOf(backend.load(type), text)
        assertTrue(readsIndex(load, label), "a load by id:\n$load")
        val delete = deletePlan(type, text)
        assertTrue(readsIndex(delete, label), "a delete by id:\n$delete")
        val related = backend.related(NodeRef(type, text), "POINTS_TO", Direction.OUTGOING, LookupTarget::class.java)
        val relatedPlan = plan(related.statement, related.bindings)
        assertTrue(readsIndex(relatedPlan, label), "loadRelated:\n$relatedPlan")
    }

    /**
     * `loadNearestAmong` finds the roots of its ids as a load finds one, each id's text read as the `@NodeId` field's type,
     * and gives each root and neighbour by the text of its id, a temporal one by its tag's text.
     */
    @Test
    fun `loadNearestAmong finds roots of a LocalDate id by their text, and gives each by its text`() {
        manager.save(LookupNear(LocalDate.of(2030, 1, 1), "a", listOf(1.0f, 0.0f)))
        manager.save(LookupNear(LocalDate.of(2030, 1, 2), "b", listOf(0.8f, 0.6f)))
        manager.save(LookupNear(LocalDate.of(2030, 1, 3), "c", listOf(0.0f, 1.0f)))

        val found = manager.loadNearestAmong(LookupNear::class.java, listOf("2030-01-01", "2030-01-02", "2030-01-03", "2030-01-09", "abc"), 1)

        assertEquals(
            mapOf("2030-01-01" to listOf("2030-01-02"), "2030-01-02" to listOf("2030-01-01"), "2030-01-03" to listOf("2030-01-02")),
            found.mapValues { (_, near) -> near.map { it.value } },
        )
    }

    /**
     * `loadNearestAmong` gives each root and neighbour by the text it was given, though the store holds a `LocalDateTime`
     * id's text with its seconds, `2030-01-01T10:00:00`, and `toString` writes `2030-01-01T10:00`.
     */
    @Test
    fun `loadNearestAmong gives each id by the text it was given`() {
        val first = LocalDateTime.of(2030, 1, 1, 10, 0)
        val second = LocalDateTime.of(2030, 1, 1, 11, 0)
        manager.save(LookupNearMoment(first, "a", listOf(1.0f, 0.0f)))
        manager.save(LookupNearMoment(second, "b", listOf(0.8f, 0.6f)))

        val found = manager.loadNearestAmong(LookupNearMoment::class.java, listOf(first.toString(), second.toString()), 1)

        assertEquals(
            mapOf("2030-01-01T10:00" to listOf("2030-01-01T11:00"), "2030-01-01T11:00" to listOf("2030-01-01T10:00")),
            found.mapValues { (_, near) -> near.map { it.value } },
        )
    }

    /**
     * An id given as two texts of one value is one root: each text is a key, and the root comes back as a neighbour by the
     * first text given, never as its own.
     */
    @Test
    fun `loadNearestAmong gives an id given as two texts under each, and finds its root once`() {
        val first = LocalDateTime.of(2030, 1, 1, 10, 0)
        val second = LocalDateTime.of(2030, 1, 1, 11, 0)
        manager.save(LookupNearMoment(first, "a", listOf(1.0f, 0.0f)))
        manager.save(LookupNearMoment(second, "b", listOf(0.8f, 0.6f)))

        val found = manager.loadNearestAmong(LookupNearMoment::class.java, listOf("2030-01-01T10:00", "2030-01-01T10:00:00", "2030-01-01T11:00"), 2)

        assertEquals(
            mapOf(
                "2030-01-01T10:00" to listOf("2030-01-01T11:00"),
                "2030-01-01T10:00:00" to listOf("2030-01-01T11:00"),
                "2030-01-01T11:00" to listOf("2030-01-01T10:00"),
            ),
            found.mapValues { (_, near) -> near.map { it.value } },
        )
    }

    /** `loadNearestAmong` finds its roots through the index over their ids, as a load does, and does not read the label whole. */
    @Test
    fun `loadNearestAmong reads the index`() {
        val statement = backend.nearestAmong(LookupNear::class.java, null, listOf("2030-01-01", "2030-01-02"), 1, null, 2)
        val plan = plan(statement.statement, statement.bindings)
        assertTrue(readsIndex(plan, "LookupNear"), "loadNearestAmong:\n$plan")
    }

    @Test
    fun `a lookup by a Long id reads the index`() = readThroughIndex(LookupLong::class.java, "LookupLong", "1500")

    @Test
    fun `a lookup by a LocalDate id reads the index`() = readThroughIndex(LookupDate::class.java, "LookupDate", "2024-01-02")

    @Test
    fun `a lookup by a String id reads the index`() = readThroughIndex(LookupText::class.java, "LookupText", "t1")

    companion object {
        const val FILLERS = 3000

        /** The JSON a node of [type] holds the id [text] as. */
        fun idJson(type: Class<*>, text: String): String = assertNotNull(NodeIds(Neo4jObjectMapper.instance).json(FragmentModel.from(type), text))
    }
}

@PostgresTests
class NodeIdLookupPostgresTest : NodeIdLookupContract() {
    override val store by lazy { PostgresTestDatabase.graphManager(GRAPH) }
    internal override val backend: GraphBackend by lazy { PostgresBackend(store, Neo4jObjectMapper.instance) }

    override fun analyze() = PostgresTestDatabase.run("ANALYZE ${GRAPH}_node", "ANALYZE ${GRAPH}_edge")

    override fun plan(sql: String, bindings: Map<String, Any?>): String =
        store.query(QuerySpecification.withStatement("EXPLAIN $sql").bind(bindings).transform(String::class.java)).joinToString("\n")

    override fun readsIndex(plan: String, label: String) =
        Regex("""Index (Only )?Scan using ${GRAPH}_uq2_${label.lowercase()}id_\w+ on ${GRAPH}_node[\s\S]*?Index Cond: \(\(props -> 'id'::text\) = """).containsMatchIn(plan) &&
            !Regex("""Seq Scan on ${GRAPH}_node\b""").containsMatchIn(plan)

    /**
     * A save finds each node through the lookup the module's write function prepares, `<graph>_find`: called on one
     * connection, the statement it prepared is explained there, for the id's JSON.
     */
    @Test
    fun `a save's lookup reads the index`() {
        listOf(LookupLong::class.java to "1500", LookupDate::class.java to "2024-01-02", LookupText::class.java to "t1").forEach { (type, text) ->
            val label = FragmentModel.from(type).labels.single()
            val connection = PostgresTestDatabase.provider.connect()
            val plan = try {
                connection.startTransaction()
                val id = idJson(type, text)
                connection.query(QuerySpecification.withStatement("SELECT (${GRAPH}_find('[\"$label\"]'::jsonb, 'id', ${PostgresSql.literal(id)}::jsonb)).nid"))
                val name = connection.query(
                    QuerySpecification.withStatement("SELECT name FROM pg_prepared_statements WHERE name LIKE 'drivine_find_%' AND statement LIKE \$label")
                        .bind(mapOf("label" to "%\"$label\"%")).transform(String::class.java),
                ).single()
                connection.query(QuerySpecification.withStatement("EXPLAIN EXECUTE $name(${PostgresSql.literal(id)}::jsonb)").transform(String::class.java)).joinToString("\n")
            } finally {
                connection.rollbackTransaction()
                connection.release()
            }
            assertTrue(readsIndex(plan, label), "the save's lookup of a $label:\n$plan")
        }
    }

    private companion object {
        const val GRAPH = "idlookups"
    }

    /**
     * A plan of a library that sends a `ref` or a `patch` step only the id's text, as plans before 0.48.1 did, is refused
     * by name by the store's write function, rather than finding no node and reading as absent (drivine-cypher-spec#245).
     */
    @Test
    fun `a ref or a patch step without the id's JSON is refused by name`() {
        val shapes = """[{"labels": ["LookupText"], "key": "id"}]"""
        for (step in listOf("""{"op": "ref", "r": 0, "s": 0, "k": "t1"}""", """{"op": "patch", "s": 0, "k": "t1", "i": "0", "fresh": "f"}""")) {
            val refused = kotlin.runCatching {
                store.query(QuerySpecification.withStatement(
                    "SELECT ${GRAPH}_write(CAST('[$step]' AS jsonb), CAST('$shapes' AS jsonb), 'mark', 'COUNT', NULL)"
                ))
            }.exceptionOrNull()
            val message = generateSequence(refused) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("A plan step that finds a node by id carries no id" in message, "$step: $message")
        }
    }
}

@OracleTests
class NodeIdLookupOracleTest : NodeIdLookupContract() {
    override val store by lazy { OracleTestDatabase.graphManager(GRAPH) }
    internal override val backend: GraphBackend by lazy { OracleBackend(store, Neo4jObjectMapper.instance, catalogReads = Runnable::run) }

    override fun analyze() = listOf("NODE", "EDGE").forEach { OracleTestDatabase.run("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '${GRAPH.uppercase()}_$it'); END;") }

    override fun plan(sql: String, bindings: Map<String, Any?>): String = OracleTestDatabase.plan(store, sql, bindings, "BASIC +PREDICATE")

    /** The index over the property of every node, read by its class and key, and neither the index over labels nor a full scan of the nodes. */
    override fun readsIndex(plan: String, label: String) =
        Regex("""INDEX (UNIQUE|RANGE) SCAN\s*\|\s*${GRAPH.uppercase()}_IX2_ID_""").containsMatchIn(plan) &&
            "${GRAPH.uppercase()}_NODE_LABELS" !in plan &&
            !Regex("""TABLE ACCESS (STORAGE )?FULL\s*\|\s*${GRAPH.uppercase()}_NODE\b""").containsMatchIn(plan)

    /**
     * The delete by id runs from a block, which no plan is made of: its statement is the select's, `DELETE FROM` the node
     * table where the root is the one of the id, as [OracleBackend] writes it, and is explained so.
     */
    override fun deletePlan(type: Class<*>, id: String): String {
        val select = OracleSelect(OracleGraph(GRAPH), type)
        val delete = select.hinted("DELETE FROM ${OracleGraph(GRAPH).nodes} ${select.root}\nWHERE ${select.selecting(listOf(select.hasId(GraphBackend.ID_PARAM)))}")
        return plan(delete, select.bindings() + (GraphBackend.ID_PARAM to idJson(type, id)))
    }

    /** A save finds each node by the block's `find`, whose query is explained for the id's JSON. */
    @Test
    fun `a save's lookup reads the index`() {
        listOf(LookupLong::class.java to "1500", LookupDate::class.java to "2024-01-02", LookupText::class.java to "t1").forEach { (type, text) ->
            val label = FragmentModel.from(type).labels.single()
            val block = OracleWriteBlocks(OracleGraph(GRAPH)).statement(listOf(NodeShape(listOf(label), "id")), WriteReturns.COUNT, null)
            val find = assertNotNull(Regex("""SELECT MIN\(n\.nid\) INTO v_nid FROM (.*);""").find(block), block).groupValues[1]
            val plan = plan("SELECT MIN(n.nid) FROM ${find.replace("JSON(p_id)", "JSON(${OracleSql.literal(idJson(type, text))})")}", emptyMap())
            assertTrue(readsIndex(plan, label), "the save's lookup of a $label:\n$plan")
        }
    }

    private companion object {
        const val GRAPH = "idlookups"
    }
}

class NodeIdLookupSqliteTest : NodeIdLookupContract() {
    override val store by lazy { SqliteTestDatabase.graphManager(GRAPH) }
    internal override val backend: GraphBackend by lazy { SqliteBackend(store, Neo4jObjectMapper.instance) }

    override fun analyze() = SqliteTestDatabase.run("ANALYZE")

    override fun plan(sql: String, bindings: Map<String, Any?>): String =
        store.query(QuerySpecification.withStatement("EXPLAIN QUERY PLAN $sql").bind(bindings)).joinToString("\n") { row -> (row as List<*>).last().toString() }

    /**
     * The node searched for through the uniqueness constraint's index over the label's `id`, by both its columns, the id's
     * class and key: `EXPLAIN QUERY PLAN` says it uses the index.
     */
    override fun readsIndex(plan: String, label: String) =
        Regex("""SEARCH \w+ USING (COVERING )?INDEX ${GRAPH}_uq2_${label}id_\w+ \(<expr>=\? AND <expr>=\?\)""").containsMatchIn(plan)

    private companion object {
        const val GRAPH = "idlookups"
    }
}
