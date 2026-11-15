package org.drivine.backend.oracle

import org.drivine.connection.DatabaseType
import org.drivine.cypher.store.OracleSql as StoreSql
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.dsl.WhereCondition.LabelCondition
import org.drivine.query.dsl.WhereCondition.OrCondition
import org.drivine.query.dsl.WhereCondition.PropertyCondition
import org.drivine.schema.RangeIndexSpec
import org.drivine.test.RecordingStore
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.junit.jupiter.api.Test
import sample.groups.Feedback
import sample.groups.FeedbackQueryDsl
import sample.stateless.Claim
import sample.stateless.ClaimView
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A node of two labels, each of which may have indexes of its own. */
@NodeFragment(labels = ["VecContent", "VecChunk"])
data class TwoLabelled(@NodeId val id: String, val chunkText: String, val x: String)

/**
 * A filter on the nodes of a label, read through a labelled index where one answers it: the
 * statements [OracleSelect] and [OracleBackend] write then, and when they do not. No database is needed.
 */
class OracleLabelledIndexTest {

    private val graph = OracleGraph("g")

    private val claimText = LabelledIndexes(listOf(LabelledIndex("Claim", listOf("text"))))
    private val claimTextNote = LabelledIndexes(listOf(LabelledIndex("Claim", listOf("text", "note"))))

    private fun select(type: Class<*> = Claim::class.java, labelled: LabelledIndexes = claimText) = OracleSelect(graph, type, labelled = labelled)

    private fun condition(key: String, operator: ComparisonOperator, value: Any?, alias: String = "n") = PropertyCondition("$alias.$key", operator, value)

    private val scansClaim = "JSON_EXISTS(n_.labels, '$[*]?(@ == \"Claim\")')"

    /**
     * Whether [sql] compares the property [key] of the node [row] through the index of [label]: by the expressions of the
     * labelled index a range index makes, and of the unique index a uniqueness constraint makes, each null on a node without
     * the label ([StoreSql.labelledIndex], [StoreSql.uniqueIndex]), so either index answers the label and the property.
     */
    private fun throughIndex(sql: String, label: String, key: String, row: String = "n_"): Boolean =
        (StoreSql.labelledIndex(row, label, key) + StoreSql.uniqueIndex(row, label, listOf(key), key)).all { it in sql }

    /** Whether [sql] compares anything through an index of [label]: the test of the label that begins each such expression. */
    private fun anyThroughIndex(sql: String, label: String, row: String = "n_") = "CASE WHEN ${StoreSql.hasLabel(row, label)}" in sql

    @Test
    fun `a comparison a labelled index answers reads the property as the index does, and the label is then not tested on its own`() {
        val select = select()
        val statement = select.load(listOfNotNull(select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x")), indexed = true)))
        // The fragment's hints in the outermost SELECT, and no label tested in the pattern.
        assertTrue(statement.statement.startsWith("SELECT /*+ NO_JSON_TABLE_TRANSFORM */ n_.doc AS result\nFROM GRAPH_TABLE (g\n    MATCH (n_ IS node)\n"), statement.statement)
        assertTrue(throughIndex(statement.statement.substringAfter("\nWHERE "), "Claim", "text"), statement.statement)
        assertEquals(mapOf<String, Any?>("_cy_param_n_text_0" to "\"x\""), statement.bindings)
    }

    @Test
    fun `a count is read through the index alone`() {
        val select = select()
        val statement = select.count(listOfNotNull(select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x")), indexed = true)))
        assertTrue(statement.statement.startsWith("SELECT /*+ NO_JSON_TABLE_TRANSFORM */ COUNT(*)\nFROM GRAPH_TABLE (g\n    MATCH (n_ IS node)\n"), statement.statement)
        assertTrue(throughIndex(statement.statement, "Claim", "text"), statement.statement)
    }

    @Test
    fun `an equality with a number is answered as one with a text is, as the index holds each value's class and key`() {
        val select = select()
        assertTrue(throughIndex(select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, 3)), indexed = true)!!, "Claim", "text"))
        assertEquals("", select.selecting(emptyList()), "no label is left to test")
    }

    @Test
    fun `the other conditions on the root are as they were, a property an index holds further in among them`() {
        for (labelled in listOf(claimText, claimTextNote)) {
            val select = select(labelled = labelled)
            val sql = select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x"), condition("note", ComparisonOperator.EQUALS, "y")), indexed = true)!!
            assertTrue(throughIndex(sql, "Claim", "text"), sql)
            assertFalse(StoreSql.labelledIndex("n_", "Claim", "note").any { it in sql }, sql)
            assertEquals(listOf("_cy_param_n_text_0", "_cy_param_n_note_1"), select.bindings().keys.toList())
        }
    }

    @Test
    fun `a property the index holds but does not begin with does not answer for the label`() {
        val select = select(labelled = claimTextNote)
        val sql = select.predicate(listOf(condition("note", ComparisonOperator.EQUALS, "y")), indexed = true)!!
        assertFalse(anyThroughIndex(sql, "Claim"), sql)
        assertEquals(scansClaim, select.selecting(emptyList()))
    }

    @Test
    fun `what no index answers leaves the label tested, read through the index over labels`() {
        val unanswered = listOf(
            listOf(condition("text", ComparisonOperator.NOT_EQUALS, "x")),
            listOf(condition("text", ComparisonOperator.IN, listOf("x", "y"))),
            listOf(condition("text", ComparisonOperator.CONTAINS, "x")),
            listOf(condition("text", ComparisonOperator.STARTS_WITH, "x")),
            listOf(condition("text", ComparisonOperator.IS_NULL, null)),
            // Oracle stores an empty text as it stores null.
            listOf(condition("text", ComparisonOperator.EQUALS, "")),
            listOf(condition("note", ComparisonOperator.EQUALS, "x")),
            // An ordering is compared as Cypher compares it, by kind and then by value, which the index's key does not order.
            listOf(condition("text", ComparisonOperator.GREATER_THAN, "m")),
            listOf(condition("text", ComparisonOperator.LESS_THAN_OR_EQUAL, "m")),
            // In an OR, or under a NOT, the condition is not met by every root.
            listOf(OrCondition(listOf(condition("text", ComparisonOperator.EQUALS, "x"), condition("note", ComparisonOperator.EQUALS, "y")))),
            listOf(WhereCondition.NotCondition(listOf(condition("text", ComparisonOperator.EQUALS, "x")))),
        )
        unanswered.forEach { conditions ->
            val select = select()
            val sql = select.predicate(conditions, indexed = true)!!
            assertFalse(anyThroughIndex(sql, "Claim"), sql)
            assertEquals(scansClaim, select.selecting(emptyList()), "for $conditions")
        }
    }

    @Test
    fun `nothing is taken as tested by an index unless the caller says every root meets the predicate`() {
        val select = select()
        select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x")))
        assertEquals(scansClaim, select.selecting(emptyList()))
    }

    @Test
    fun `a root found by its id is found as it was, by the index over the id, its label tested`() {
        val select = select()
        val id = select.hasId("id")
        select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x")), indexed = true)
        assertEquals("INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n_.labels), ']'), '[') || ',\"', ',\"Claim\",\"') > 0\n  AND $id", select.selecting(listOf(id)))
    }

    @Test
    fun `another label of the root is tested in the form the index over labels cannot answer`() {
        val select = select()
        val sql = select.predicate(
            listOf(LabelCondition("n", listOf("Claim", "Reviewed")), condition("text", ComparisonOperator.EQUALS, "x")),
            indexed = true,
        )!!
        assertTrue("INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n_.labels), ']'), '[') || ',\"', ',\"Reviewed\",\"') > 0" in sql, sql)
        assertTrue(throughIndex(sql, "Claim", "text"), sql)
        assertEquals("", select.selecting(emptyList()))
    }

    @Test
    fun `a uniqueness constraint's index over one property answers an equality as a labelled index does`() {
        val equal = listOf(condition("text", ComparisonOperator.EQUALS, "x"))
        val labelled = select().predicate(equal, indexed = true)
        for (indexes in listOf(
            LabelledIndexes(listOf(LabelledIndex("Claim", listOf("text"), unique = true))),
            LabelledIndexes(listOf(LabelledIndex("Claim", listOf("text")), LabelledIndex("Claim", listOf("text"), unique = true))),
        )) {
            val select = select(labelled = indexes)
            assertEquals(labelled, select.predicate(equal, indexed = true))
            assertEquals("", select.selecting(emptyList()))
        }
    }

    // ----- A root of two labels -----

    private fun has(label: String) = "JSON_EXISTS(n_.labels, '$[*]?(@ == \"$label\")')"
    private fun carries(label: String) = "INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n_.labels), ']'), '[') || ',\"', ',\"$label\",\"') > 0"

    /**
     * The statement [conditions] make on a root of two labels, with what is asserted of every such
     * statement: each label is tested, by a test of its own or by a comparison through its own index.
     */
    private fun twoLabelled(indexes: LabelledIndexes, vararg conditions: WhereCondition): String {
        val select = select(TwoLabelled::class.java, indexes)
        val predicate = select.predicate(conditions.toList(), indexed = true)
        val statement = select.load(listOfNotNull(predicate)).statement
        listOf("VecContent", "VecChunk").forEach { label ->
            assertTrue(carries(label) in statement || "CASE WHEN ${has(label)}" in statement || "MATCH (n_ IS node WHERE ${has(label)}" in statement, "$label is not tested in:\n$statement")
        }
        return statement
    }

    @Test
    fun `each label of a root that needs no test of its own is tested by a comparison through its own index, whatever the order`() {
        val indexes = LabelledIndexes(listOf(LabelledIndex("VecContent", listOf("id")), LabelledIndex("VecChunk", listOf("chunkText", "id"))))
        val chunk = condition("chunkText", ComparisonOperator.EQUALS, "t")
        val id = condition("id", ComparisonOperator.EQUALS, "x")
        listOf(listOf(chunk, id), listOf(id, chunk)).forEach { conditions ->
            val statement = twoLabelled(indexes, *conditions.toTypedArray())
            assertTrue(throughIndex(statement, "VecContent", "id"), statement)
            assertTrue(throughIndex(statement, "VecChunk", "chunkText"), statement)
            assertTrue("MATCH (n_ IS node)\n" in statement, statement)
        }
    }

    @Test
    fun `two labels with indexes on one property, and another index on one label, are each tested`() {
        val indexes = LabelledIndexes(listOf(
            LabelledIndex("VecContent", listOf("id"), unique = true),
            LabelledIndex("VecChunk", listOf("id"), unique = true),
            LabelledIndex("VecChunk", listOf("x")),
        ))
        val x = condition("x", ComparisonOperator.EQUALS, "a")
        val id = condition("id", ComparisonOperator.EQUALS, "b")
        listOf(listOf(x, id), listOf(id, x), listOf(id)).forEach { conditions ->
            val statement = twoLabelled(indexes, *conditions.toTypedArray())
            // The id is compared through the index of each label that leads with it, so each label is tested by it.
            assertTrue(throughIndex(statement, "VecContent", "id"), statement)
            assertTrue(throughIndex(statement, "VecChunk", "id"), statement)
            assertTrue("MATCH (n_ IS node)\n" in statement, statement)
        }
        assertTrue(throughIndex(twoLabelled(indexes, x), "VecChunk", "x"))
    }

    @Test
    fun `a property one label holds an index on is read through that label's, and the other label is tested on its own`() {
        val indexes = LabelledIndexes(listOf(LabelledIndex("VecContent", listOf("id"), unique = true)))
        val statement = twoLabelled(indexes, condition("id", ComparisonOperator.EQUALS, "b"), condition("id", ComparisonOperator.LESS_THAN, "z"))
        assertTrue(throughIndex(statement, "VecContent", "id"), statement)
        assertFalse(anyThroughIndex(statement, "VecChunk"), statement)
        assertTrue("MATCH (n_ IS node WHERE ${carries("VecChunk")})" in statement, statement)
    }

    @Test
    fun `a view's root is read through the index under the root's own name`() {
        val select = select(ClaimView::class.java)
        val sql = select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x", alias = "claim")), indexed = true)!!
        assertTrue(throughIndex(sql, "Claim", "text", row = "claim_"), sql)
        assertTrue(select.load(listOf(sql)).statement.contains("MATCH (claim_ IS node)\n"))
    }

    // ----- Through the backend -----

    /** The catalog read on the caller's thread, so what a test sees of it is settled when a call returns. */
    private fun backend(store: RecordingStore) = OracleBackend(store, Neo4jObjectMapper.instance, catalogReads = Runnable::run)

    private fun query(vararg conditions: WhereCondition) = GraphQuerySpec(Any()).apply { this.conditions += conditions }

    /** A store whose catalog lists [rows], each a range index whose labelled index is there. */
    private fun store(vararg rows: Map<String, Any?>) = RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(graph), graphName = "g") { ran ->
        if (ran.statement == LabelledIndexCatalog.query(graph)) rows.toList() else emptyList<Any?>()
    }

    private fun listed(label: String, vararg properties: String, kind: String = "RANGE_INDEX") =
        mapOf("kind" to kind, "label" to label, "properties" to properties.toList())

    @Test
    fun `the backend reads the labelled indexes from the catalog once, on a connection of its own, and a load, a count and a delete use them`() {
        val store = store(listed("Claim", "text"))
        val backend = backend(store)
        val equal = query(condition("text", ComparisonOperator.EQUALS, "x"))

        val load = backend.loadAll(Claim::class.java, equal).statement.statement
        val count = backend.count(Claim::class.java, equal).statement
        val delete = backend.deleteAll(Claim::class.java, equal).statement

        listOf(load, count).forEach {
            assertTrue("MATCH (n_ IS node)\n" in it, it)
            assertTrue(throughIndex(it.substringAfter("\nWHERE "), "Claim", "text"), it)
        }
        // A delete is run from a block, its quotes doubled, the label tested by the comparison alone.
        assertTrue("DELETE /*+ NO_JSON_TABLE_TRANSFORM */ FROM g_node n_\n    WHERE (" in delete, delete)
        assertTrue((StoreSql.labelledIndex("n_", "Claim", "text")).all { it.replace("'", "''") in delete }, delete)
        assertFalse("WHERE ${has("Claim").replace("'", "''")}" in delete, delete)
        assertEquals(1, store.schema.count { it == LabelledIndexCatalog.query(graph) }, "the catalog is read once")
        assertTrue(store.queried.none { it.statement == LabelledIndexCatalog.query(graph) }, "and not among the statements of a read")
    }

    @Test
    fun `a grouped read filters its roots through the index, and a filter of one of its numbers does not decide how the roots are read`() {
        val backend = backend(store(listed("GroupFeedback", "page"), listed("GroupFeedback", "id")))

        val filtered = backend.groups(Feedback::class.java, GroupQuerySpec(FeedbackQueryDsl.INSTANCE).apply {
            where { query.page eq "/home" }
            keyOrNull(query.helpful)
            count()
        }).statement
        assertTrue("MATCH (n_ IS node)\n" in filtered, filtered)
        assertTrue(throughIndex(filtered.substringAfter("\n    WHERE "), "GroupFeedback", "page"), filtered)

        // A count of the roots with an id is no filter of the roots, which are then read by their label, whether or not the
        // count's own condition is compared through the index.
        val counted = backend.groups(Feedback::class.java, GroupQuerySpec(FeedbackQueryDsl.INSTANCE).apply {
            keyOrNull(query.helpful)
            count { query.id eq "f1" }
        }).statement
        assertTrue("MATCH (n_ IS node WHERE JSON_EXISTS(n_.labels, '$[*]?(@ == \"GroupFeedback\")'))" in counted, counted)
    }

    @Test
    fun `a read never waits for the catalog, which is read elsewhere, once at a time, and meanwhile what was read last is used`() {
        val pending = ArrayDeque<Runnable>()
        val store = store(listed("Claim", "text"))
        var now = 0L
        val catalog = LabelledIndexCatalog(store, graph, maxAge = 1000, clock = { now }, refresh = { pending.add(it) })
        fun reads() = store.schema.count { it == LabelledIndexCatalog.query(graph) }

        // Before the first read is done, none is known; and asking again begins no second read.
        assertEquals(emptySet(), catalog.current().leading)
        assertEquals(emptySet(), catalog.current().leading)
        assertEquals(1, pending.size)
        assertEquals(0, reads())

        pending.removeFirst().run()
        assertEquals(1, reads())
        assertEquals(setOf("Claim" to "text"), catalog.current().leading)
        assertTrue(pending.isEmpty())

        // Once old, what was read is still used while it is read again.
        now = 1000
        assertEquals(setOf("Claim" to "text"), catalog.current().leading)
        assertEquals(1, pending.size)
    }

    @Test
    fun `a read that could not be begun is begun by the next caller`() {
        var failing = true
        val pending = ArrayDeque<Runnable>()
        val catalog = LabelledIndexCatalog(store(listed("Claim", "text")), graph, refresh = { if (failing) throw OutOfMemoryError("unable to create native thread") else pending.add(it) })

        assertFailsWith<OutOfMemoryError> { catalog.current() }
        failing = false
        catalog.current()
        assertEquals(1, pending.size, "the failed start left no read marked as under way")
    }

    @Test
    fun `a catalog that cannot be read again leaves what was read before`() {
        var failing = false
        val store = RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(graph), graphName = "g") { ran ->
            when {
                ran.statement != LabelledIndexCatalog.query(graph) -> emptyList<Any?>()
                failing -> IllegalStateException("pool exhausted")
                else -> listOf(listed("Claim", "text"))
            }
        }
        var now = 0L
        val catalog = LabelledIndexCatalog(store, graph, maxAge = 1000, clock = { now }, refresh = Runnable::run)
        assertEquals(setOf("Claim" to "text"), catalog.current().leading)
        failing = true
        now = 1000
        assertEquals(setOf("Claim" to "text"), catalog.current().leading)
    }

    @Test
    fun `a catalog with no labelled index, or one that cannot be read, leaves the statements as they were`() {
        val none = backend(store()).loadAll(Claim::class.java, query(condition("text", ComparisonOperator.EQUALS, "x"))).statement.statement
        assertTrue("MATCH (n_ IS node WHERE $scansClaim)" in none, none)

        val failing = RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(graph), graphName = "g") { ran ->
            if (ran.statement == LabelledIndexCatalog.query(graph)) IllegalStateException("no catalog") else emptyList<Any?>()
        }
        val unread = backend(failing).loadAll(Claim::class.java, query(condition("text", ComparisonOperator.EQUALS, "x"))).statement.statement
        assertEquals(none, unread)
    }

    @Test
    fun `the catalog is read again once this process has made or dropped an index since, or when what was read is old`() {
        var now = 0L
        // A graph of its own: what is counted is what this process did to the graph.
        val own = OracleGraph("labelled_catalog")
        val store = RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(own), graphName = own.name)
        val catalog = LabelledIndexCatalog(store, own, maxAge = 1000, clock = { now }, refresh = Runnable::run)
        fun reads() = store.schema.count { it == LabelledIndexCatalog.query(own) }

        catalog.current()
        catalog.current()
        assertEquals(1, reads())

        // Writing the statements is not running them: what is read before they have run would be kept.
        OracleSchemaGrammar(own).createIndex(RangeIndexSpec("Claim", "text"), null)
        catalog.current()
        assertEquals(1, reads())

        // The index manager says they have run.
        OracleSchemaGrammar(own).changed()
        catalog.current()
        assertEquals(2, reads())

        now = 1000
        catalog.current()
        assertEquals(3, reads())
    }
}
