package org.drivine.backend.oracle

import org.drivine.connection.DatabaseType
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
    private val text = "CASE WHEN $scansClaim THEN JSON_VALUE(n_.props, '$.text') END"
    private val note = "CASE WHEN $scansClaim THEN JSON_VALUE(n_.props, '$.note') END"

    @Test
    fun `a comparison a labelled index answers reads the property as the index does, and the label is then not tested on its own`() {
        val select = select()
        val statement = select.load(listOfNotNull(select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x")), indexed = true)))
        assertEquals(
            """
            SELECT n_.doc AS result
            FROM GRAPH_TABLE (g
                MATCH (n_ IS node)
                COLUMNS (n_.labels, n_.props, n_.doc)) n_
            WHERE $text = ${'$'}_p0
            """.trimIndent(),
            statement.statement,
        )
        assertEquals(mapOf<String, Any?>("_p0" to "x"), statement.bindings)
    }

    @Test
    fun `a count is read through the index alone`() {
        val select = select()
        val statement = select.count(listOfNotNull(select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x")), indexed = true)))
        assertEquals(
            """
            SELECT COUNT(*)
            FROM GRAPH_TABLE (g
                MATCH (n_ IS node)
                COLUMNS (n_.labels, n_.props)) n_
            WHERE $text = ${'$'}_p0
            """.trimIndent(),
            statement.statement,
        )
    }

    @Test
    fun `each ordering is answered as equality is`() {
        listOf(
            ComparisonOperator.GREATER_THAN to ">", ComparisonOperator.GREATER_THAN_OR_EQUAL to ">=",
            ComparisonOperator.LESS_THAN to "<", ComparisonOperator.LESS_THAN_OR_EQUAL to "<=",
        ).forEach { (operator, written) ->
            val select = select()
            assertEquals("$text $written \$_p0", select.predicate(listOf(condition("text", operator, "m")), indexed = true))
            assertEquals(null, select.selecting(emptyList()).takeIf { it.isNotEmpty() }, "no label is left to test")
        }
    }

    @Test
    fun `the other conditions on the root are as they were, and one on a property the index also holds reads it as the index does`() {
        val one = select()
        val sql = one.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x"), condition("note", ComparisonOperator.EQUALS, "y")), indexed = true)
        assertEquals("$text = \$_p0 AND JSON_VALUE(n_.props, '$.note') = \$_p1", sql)

        val composite = select(labelled = claimTextNote)
        val both = composite.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x"), condition("note", ComparisonOperator.EQUALS, "y")), indexed = true)
        assertEquals("$text = \$_p0 AND $note = \$_p1", both)
    }

    @Test
    fun `a property the index holds but does not begin with does not answer for the label`() {
        val select = select(labelled = claimTextNote)
        val sql = select.predicate(listOf(condition("note", ComparisonOperator.EQUALS, "y")), indexed = true)
        assertEquals("JSON_VALUE(n_.props, '$.note') = \$_p0", sql)
        assertEquals(scansClaim, select.selecting(emptyList()))
    }

    @Test
    fun `what no index answers leaves the statement as it was, the label read through the index over labels`() {
        val unanswered = listOf(
            listOf(condition("text", ComparisonOperator.NOT_EQUALS, "x")),
            listOf(condition("text", ComparisonOperator.IN, listOf("x", "y"))),
            listOf(condition("text", ComparisonOperator.CONTAINS, "x")),
            listOf(condition("text", ComparisonOperator.STARTS_WITH, "x")),
            listOf(condition("text", ComparisonOperator.IS_NULL, null)),
            listOf(condition("text", ComparisonOperator.EQUALS, "")),
            listOf(condition("text", ComparisonOperator.EQUALS, 3)),
            listOf(condition("note", ComparisonOperator.EQUALS, "x")),
            // In an OR, the condition is not met by every root.
            listOf(OrCondition(listOf(condition("text", ComparisonOperator.EQUALS, "x"), condition("note", ComparisonOperator.EQUALS, "y")))),
        )
        unanswered.forEach { conditions ->
            val select = select()
            val sql = select.predicate(conditions, indexed = true)!!
            assertFalse("CASE WHEN" in sql, sql)
            assertEquals(scansClaim, select.selecting(emptyList()), "for $conditions")
        }
    }

    @Test
    fun `nothing is read through an index unless the caller says every root meets the predicate`() {
        val select = select()
        val sql = select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x")))
        assertEquals("JSON_VALUE(n_.props, '$.text') = \$_p0", sql)
        assertEquals(scansClaim, select.selecting(emptyList()))
    }

    @Test
    fun `a root found by its id is found as it was, by the index over the id`() {
        val select = select()
        val id = select.hasId("id")
        val sql = select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x")), indexed = true)
        assertEquals("JSON_VALUE(n_.props, '$.text') = \$_p0", sql)
        assertEquals("INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n_.labels), ']'), '[') || ',\"', ',\"Claim\",\"') > 0\n  AND $id", select.selecting(listOf(id)))
    }

    @Test
    fun `another label of the root is tested in the form the index over labels cannot answer`() {
        val select = select()
        val sql = select.predicate(
            listOf(LabelCondition("n", listOf("Claim", "Reviewed")), condition("text", ComparisonOperator.EQUALS, "x")),
            indexed = true,
        )
        assertEquals("INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n_.labels), ']'), '[') || ',\"', ',\"Reviewed\",\"') > 0 AND $text = \$_p0", sql)
    }

    @Test
    fun `a uniqueness constraint's index over one property is read through as it reads the property, before a labelled index`() {
        val unique = LabelledIndexes(listOf(LabelledIndex("Claim", listOf("text")), LabelledIndex("Claim", listOf("text"), unique = true)))
        val select = select(labelled = unique)
        val sql = select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x")), indexed = true)
        assertEquals("CASE WHEN $scansClaim AND JSON_EXISTS(n_.props, '$.text') THEN JSON_VALUE(n_.props, '$.text') END = \$_p0", sql)
        assertEquals("", select.selecting(emptyList()))
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
            assertTrue("CASE WHEN ${has("VecContent")} THEN JSON_VALUE(n_.props, '$.id') END = " in statement, statement)
            assertTrue("CASE WHEN ${has("VecChunk")} THEN JSON_VALUE(n_.props, '$.chunkText') END = " in statement, statement)
            assertTrue("MATCH (n_ IS node)\n" in statement, statement)
        }
    }

    @Test
    fun `two labels with unique indexes on one property, and another index on one label, are each tested`() {
        val indexes = LabelledIndexes(listOf(
            LabelledIndex("VecContent", listOf("id"), unique = true),
            LabelledIndex("VecChunk", listOf("id"), unique = true),
            LabelledIndex("VecChunk", listOf("x")),
        ))
        val x = condition("x", ComparisonOperator.EQUALS, "a")
        val id = condition("id", ComparisonOperator.EQUALS, "b")
        listOf(listOf(x, id), listOf(id, x)).forEach { conditions ->
            val statement = twoLabelled(indexes, *conditions.toTypedArray())
            // The id is answered for the first label whose index leads with it, and read through that label's index alone.
            assertTrue("CASE WHEN ${has("VecContent")} AND JSON_EXISTS(n_.props, '$.id') THEN JSON_VALUE(n_.props, '$.id') END = " in statement, statement)
            assertFalse("CASE WHEN ${has("VecChunk")} AND JSON_EXISTS(n_.props, '$.id')" in statement, statement)
            assertTrue("CASE WHEN ${has("VecChunk")} THEN JSON_VALUE(n_.props, '$.x') END = " in statement, statement)
        }
        // With the id alone, the other label is tested on its own, in the form no index answers.
        val alone = twoLabelled(indexes, id)
        assertTrue("MATCH (n_ IS node WHERE ${carries("VecChunk")})" in alone, alone)
    }

    @Test
    fun `a property one label holds a unique index on and the other a labelled index on is read through the label that answers it`() {
        val indexes = LabelledIndexes(listOf(LabelledIndex("VecContent", listOf("id"), unique = true), LabelledIndex("VecChunk", listOf("id"))))
        val statement = twoLabelled(indexes, condition("id", ComparisonOperator.EQUALS, "b"), condition("id", ComparisonOperator.LESS_THAN, "z"))
        assertEquals(2, Regex(Regex.escape("CASE WHEN ${has("VecContent")} AND JSON_EXISTS(n_.props, '$.id')")).findAll(statement).count(), statement)
        assertFalse("CASE WHEN ${has("VecChunk")}" in statement, statement)
        assertTrue("MATCH (n_ IS node WHERE ${carries("VecChunk")})" in statement, statement)
    }

    @Test
    fun `a view's root is read through the index under the root's own name`() {
        val select = select(ClaimView::class.java)
        val sql = select.predicate(listOf(condition("text", ComparisonOperator.EQUALS, "x", alias = "claim")), indexed = true)
        assertEquals("CASE WHEN JSON_EXISTS(claim_.labels, '$[*]?(@ == \"Claim\")') THEN JSON_VALUE(claim_.props, '$.text') END = \$_p0", sql)
        assertTrue(select.load(listOfNotNull(sql)).statement.contains("MATCH (claim_ IS node)\n"))
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
            assertTrue(it.endsWith("\nWHERE $text = \$_p0"), it)
        }
        // A delete is run from a block, its quotes doubled.
        assertTrue("DELETE FROM g_node n_\n    WHERE ${text.replace("'", "''")} = :b0'" in delete, delete)
        assertEquals(1, store.schema.count { it == LabelledIndexCatalog.query(graph) }, "the catalog is read once")
        assertTrue(store.queried.none { it.statement == LabelledIndexCatalog.query(graph) }, "and not among the statements of a read")
    }

    @Test
    fun `a grouped read filters its roots through the index, and a filter of one of its numbers does not decide how the roots are read`() {
        val backend = backend(store(listed("GroupFeedback", "page"), listed("GroupFeedback", "id")))
        val page = "CASE WHEN JSON_EXISTS(n_.labels, '$[*]?(@ == \"GroupFeedback\")') THEN JSON_VALUE(n_.props, '$.page') END"

        val filtered = backend.groups(Feedback::class.java, GroupQuerySpec(FeedbackQueryDsl.INSTANCE).apply {
            where { query.page eq "/home" }
            keyOrNull(query.helpful)
            count()
        }).statement
        assertTrue("MATCH (n_ IS node)\n" in filtered, filtered)
        assertTrue("\n    WHERE $page = \$_p0\n" in filtered, filtered)

        // A count of the roots with a page is no filter of the roots, which are then read by their label.
        val counted = backend.groups(Feedback::class.java, GroupQuerySpec(FeedbackQueryDsl.INSTANCE).apply {
            keyOrNull(query.helpful)
            count { query.id eq "f1" }
        }).statement
        assertTrue("MATCH (n_ IS node WHERE JSON_EXISTS(n_.labels, '$[*]?(@ == \"GroupFeedback\")'))" in counted, counted)
        assertFalse("CASE WHEN JSON_EXISTS(n_.labels" in counted, counted)
    }

    @Test
    fun `a read never waits for the catalog, which is read elsewhere, once at a time, and meanwhile what was read last is used`() {
        val pending = ArrayDeque<Runnable>()
        val store = store(listed("Claim", "text"))
        var now = 0L
        val catalog = LabelledIndexCatalog(store, graph, maxAge = 1000, clock = { now }, refresh = { pending.add(it) })
        fun reads() = store.schema.count { it == LabelledIndexCatalog.query(graph) }

        // Before the first read is done, none is known; and asking again begins no second read.
        assertEquals(null, catalog.current().expression("n", "Claim", "text"))
        assertEquals(null, catalog.current().expression("n", "Claim", "text"))
        assertEquals(1, pending.size)
        assertEquals(0, reads())

        pending.removeFirst().run()
        assertEquals(1, reads())
        assertEquals(OracleSql.labelled("n", "Claim", "text"), catalog.current().expression("n", "Claim", "text"))
        assertTrue(pending.isEmpty())

        // Once old, what was read is still used while it is read again.
        now = 1000
        assertEquals(OracleSql.labelled("n", "Claim", "text"), catalog.current().expression("n", "Claim", "text"))
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
        assertEquals(OracleSql.labelled("n", "Claim", "text"), catalog.current().expression("n", "Claim", "text"))
        failing = true
        now = 1000
        assertEquals(OracleSql.labelled("n", "Claim", "text"), catalog.current().expression("n", "Claim", "text"))
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
