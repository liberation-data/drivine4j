package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.annotation.NodeFragment
import org.drivine.backend.GraphBackend
import org.drivine.connection.DatabaseType
import org.drivine.manager.CascadeType
import org.drivine.manager.NodeRef
import org.drivine.manager.NullPolicy
import org.drivine.manager.RelateMode
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.query.PatchRow
import org.drivine.query.SqlParameters
import org.drivine.query.SaveRequest
import org.drivine.query.StampWrite
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.OrderDirection.ASC
import org.drivine.query.dsl.OrderDirection.DESC
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import sample.fulltext.ArticleNode
import sample.fulltext.EntityNode
import sample.stateless.Claim
import sample.stateless.ClaimStats
import sample.stateless.ClaimView
import sample.stateless.Human
import sample.vectorfilter.VecDocNode
import java.sql.SQLException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A node with no id, which no relationship written on its own can name. */
@NodeFragment(labels = ["Drift"])
data class Drift(val name: String)

/** A node whose label has a colon in it, as a label that is data may. */
@NodeFragment(labels = ["ns:Thing"])
data class Spaced(@org.drivine.annotation.NodeId val id: String)

/**
 * What [OracleBackend] makes of each operation an object manager asks of it: which statement, what
 * it binds, and what it refuses. The store is a [RecordingStore], so no database is needed.
 */
class OracleBackendTest {

    private val graph = OracleGraph("g")

    private fun store(answer: (RecordingStore.Ran) -> Any? = { emptyList<Any?>() }) =
        RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(graph), graphName = "g", answer = answer)

    private fun backend(store: RecordingStore = store()) = OracleBackend(store, Neo4jObjectMapper.instance)

    private fun query(build: GraphQuerySpec<Any>.() -> Unit = {}) = GraphQuerySpec(Any()).apply(build)

    @Suppress("UNCHECKED_CAST")
    private fun steps(bindings: Map<String, Any?>): List<Map<String, Any?>> = bindings[OracleWriteBlock.PLAN] as List<Map<String, Any?>>

    private fun String.squashed(): String = replace(Regex("\\s+"), " ")

    // ----- Making the store -----

    @Test
    fun `the store is made when the backend is, once, on a schema connection and not the caller's`() {
        val store = store()
        backend(store)

        assertEquals(listOf(graph.ensuring), store.schema)
        assertTrue(store.queried.isEmpty() && store.executed.isEmpty())
        assertEquals(1, store.connected)
        assertEquals(1, store.released)
    }

    @Test
    fun `a manager that names no graph speaks to the default one`() {
        val store = RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(OracleGraph("drivine")))
        val backend = OracleBackend(store, Neo4jObjectMapper.instance)

        assertEquals(listOf(OracleGraph(OracleGraph.DEFAULT_NAME).ensuring), store.schema)
        assertContains(backend.load(Claim::class.java).statement, "GRAPH_TABLE (drivine")
    }

    @Test
    fun `a graph name Oracle cannot take is refused before anything is run`() {
        val store = RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(graph), graphName = "my-graph")

        assertFailsWith<IllegalArgumentException> { OracleBackend(store, Neo4jObjectMapper.instance) }
        assertTrue(store.schema.isEmpty())
    }

    @Test
    fun `the backend speaks no Cypher, deletes orphans, and reads no stored keys ahead of a save`() {
        val backend = backend()

        assertEquals(null, backend.cypherGrammar)
        assertTrue(backend.supportsOrphanDelete)
        assertEquals(null, backend.orphanDeleteLimit)
        assertFalse(backend.supportsIndexBackedOrdering)
        assertEquals(emptySet<String>(), backend.storedKeys.of("Claim", "id", "c1"))
    }

    // ----- Reading -----

    @Test
    fun `a load by id finds the root by the id bound under the name every backend binds it by`() {
        val statement = backend().load(Claim::class.java)

        assertContains(statement.statement, "JSON_VALUE(n_.props, '$.id') = \$${GraphBackend.ID_PARAM}")
        assertEquals(listOf(GraphBackend.ID_PARAM), SqlParameters.positional(statement.statement).names)
        assertTrue(statement.bindings.isEmpty(), "the id is the caller's to bind")
    }

    @Test
    fun `a raw where is read as Cypher writes a property, for a load and for a count`() {
        val backend = backend()

        assertContains(backend.loadAll(Claim::class.java, "n.text = 'x'").statement, "(JSON_VALUE(n_.props, '$.text') = 'x')")
        val count = backend.count(Claim::class.java, "n.text = 'x'").statement
        assertTrue(count.startsWith("SELECT COUNT(*)"), count)
        assertContains(count, "(JSON_VALUE(n_.props, '$.text') = 'x')")
        assertFalse("WHERE (" in backend.loadAll(Claim::class.java, null).statement.substringAfterLast("COLUMNS"))
    }

    @Test
    fun `a seek and a skip are refused together`() {
        val seekAndSkip = query {
            orders += OrderSpec("n.text", ASC)
            seekValues += SeekValueSpec("n.text", "m")
            skip = 10
        }

        val refused = assertFailsWith<IllegalArgumentException> { backend().loadAll(Claim::class.java, seekAndSkip) }
        assertEquals("seek and skip cannot be used together", refused.message)
    }

    @Test
    fun `an order that places its nulls says so in SQL, and is refused under a seek`() {
        fun ordered(order: OrderSpec) = backend().loadAll(Claim::class.java, query { orders += order }).statement.statement.substringAfterLast("ORDER BY ")

        assertTrue(ordered(OrderSpec("n.text", DESC).nullsLast()).startsWith("JSON_VALUE(n_.props, '$.text') DESC NULLS LAST"))
        assertTrue(ordered(OrderSpec("n.text", ASC).nullsFirst()).startsWith("JSON_VALUE(n_.props, '$.text') ASC NULLS FIRST"))
        assertFalse("NULLS" in ordered(OrderSpec("n.text", DESC)))
        // A property no field declares is two terms, as a number and as text, and a text is null as a
        // number: its nulls are placed by a term of their own, and the two are as they are without one.
        val unplaced = "JSON_VALUE(n_.props, '$.undeclared.numberOnly()' RETURNING NUMBER) DESC, JSON_VALUE(n_.props, '$.undeclared') DESC"
        assertTrue(ordered(OrderSpec("n.undeclared", DESC)).startsWith(unplaced))
        assertTrue(ordered(OrderSpec("n.undeclared", DESC).nullsLast()).startsWith("CASE WHEN JSON_VALUE(n_.props, '$.undeclared') IS NULL THEN 1 ELSE 0 END, $unplaced"))
        assertTrue(ordered(OrderSpec("n.undeclared", DESC).nullsFirst()).startsWith("CASE WHEN JSON_VALUE(n_.props, '$.undeclared') IS NULL THEN 0 ELSE 1 END, $unplaced"))

        val underSeek = query {
            orders += OrderSpec("n.text", DESC).nullsFirst()
            seekValues += SeekValueSpec("n.text", "m")
        }
        assertEquals(
            "nullsFirst() on n.text: nulls cannot be placed under seek: a cursor does not say which side of the nulls it is on",
            assertFailsWith<IllegalArgumentException> { backend().loadAll(Claim::class.java, underSeek) }.message,
        )
    }

    @Test
    fun `an order of a relationship collection that places its nulls is refused`() {
        val sorted = query { orders += OrderSpec("people.name", ASC).nullsLast() }

        assertEquals(
            "nullsLast() on people.name: nulls cannot be placed in the order of a relationship collection",
            assertFailsWith<IllegalArgumentException> { backend().loadAll(ClaimView::class.java, sorted) }.message,
        )
    }

    @Test
    fun `a load that seeks is a keyset load, and one that pages by skip is not`() {
        val backend = backend()
        val seeking = backend.loadAll(Claim::class.java, query {
            orders += OrderSpec("n.text", DESC)
            seekValues += SeekValueSpec("n.text", "m")
            limit = 5
        })
        val paging = backend.loadAll(Claim::class.java, query {
            orders += OrderSpec("n.text", DESC)
            skip = 10
            limit = 5
        })

        assertTrue(seeking.keyset)
        assertContains(seeking.statement.statement, "JSON_VALUE(n_.props, '$.text') < \$_p0")
        assertEquals("m", seeking.statement.bindings["_p0"])
        assertFalse(seeking.statement.statement.contains("OFFSET"))

        assertFalse(paging.keyset)
        assertContains(paging.statement.statement, "OFFSET")
        assertEquals(listOf(10, 5), paging.statement.bindings.values.toList())
        assertEquals(listOf(OrderSpec("n.text", DESC)), paging.rootOrders)
    }

    @Test
    fun `an order on a relationship field sorts that field's list, and any other orders the roots`() {
        val plan = backend().loadAll(ClaimView::class.java, query {
            orders += OrderSpec("people.name", DESC)
            orders += OrderSpec("claim.text", ASC)
        })

        assertEquals(listOf(OrderSpec("claim.text", ASC)), plan.rootOrders)
        val sql = plan.statement.statement.squashed()
        // The roots are ordered by their own property alone; the list is ordered inside its own array.
        val rootOrder = sql.substringAfterLast("ORDER BY ")
        assertTrue(rootOrder.startsWith("JSON_VALUE(claim_.props, '$.text')"), rootOrder)
        assertFalse("'$.name'" in rootOrder, rootOrder)
        assertContains(sql.substringBeforeLast("ORDER BY "), "'$.name'")
    }

    @Test
    fun `an alias with an underscore names a nested collection, so on a fragment it orders nothing`() {
        val plan = backend().loadAll(Claim::class.java, query { orders += OrderSpec("people_employer.name", ASC) })

        assertTrue(plan.rootOrders.isEmpty())
        assertFalse("'$.name'" in plan.statement.statement)
    }

    @Test
    fun `a count and a delete refuse a seek, which they would ignore`() {
        val seeking = query {
            orders += OrderSpec("n.text", ASC)
            seekValues += SeekValueSpec("n.text", "m")
        }
        val backend = backend()

        val message = "seek is only supported by loadAll; this operation would ignore the cursor"
        assertEquals(message, assertFailsWith<IllegalArgumentException> { backend.count(Claim::class.java, seeking) }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { backend.deleteAll(Claim::class.java, seeking) }.message)
    }

    @Test
    fun `a count through the query DSL takes no order and no page from it`() {
        val counted = backend().count(Claim::class.java, query {
            orders += OrderSpec("n.text", ASC)
            skip = 3
            limit = 4
        })

        assertFalse("ORDER BY" in counted.statement)
        assertFalse("OFFSET" in counted.statement || "FETCH" in counted.statement)
        assertTrue(counted.bindings.isEmpty())
    }

    // ----- Searching -----

    private fun made(table: String?): (RecordingStore.Ran) -> Any? = { ran ->
        if (ran.statement.startsWith("SELECT object_name FROM g_schema")) listOfNotNull(table) else emptyList<Any?>()
    }

    @Test
    fun `the nearest are found through the table of a vector index that has been made`() {
        val store = store(made("g_v_made"))
        val plan = backend(store).nearest(VecDocNode::class.java, null, null, listOf(0.1f, 0.2f), topK = 5, threshold = null, searchK = null, partitionLabel = null)

        val asked = store.queried.single()
        assertEquals(
            "SELECT object_name FROM g_schema WHERE kind = \$kind AND label = \$label AND properties = \$properties AND object_name IS NOT NULL",
            asked.statement,
        )
        assertEquals(mapOf<String, Any?>("kind" to "VECTOR_INDEX", "label" to "VecDoc", "properties" to "[\"embedding\"]"), asked.parameters)
        assertContains(plan.cypher, "FROM g_v_made s")
        assertContains(plan.cypher, "FETCH APPROX FIRST")
        assertEquals(5, plan.requestedRows)
        assertEquals(5, plan.indexK)
    }

    @Test
    fun `with no vector index made, the nearest are measured exactly over the nodes`() {
        val plan = backend(store(made(null))).nearest(VecDocNode::class.java, null, null, listOf(0.1f), topK = 5, threshold = 0.5, searchK = null, partitionLabel = null)

        assertFalse("FETCH APPROX" in plan.cypher)
        assertContains(plan.cypher, "VECTOR_DISTANCE(TO_VECTOR(JSON_QUERY(c.props, '$.embedding' RETURNING CLOB))")
        assertTrue(0.5 in plan.bindings.values, "the threshold is bound")
    }

    @Test
    fun `a search that asks the index for more takes that many candidates and returns the top of them`() {
        val wide = backend(store(made("g_v_made"))).nearest(VecDocNode::class.java, null, null, listOf(0.1f), topK = 5, threshold = null, searchK = 50, partitionLabel = null)
        val plain = backend(store(made("g_v_made"))).nearest(VecDocNode::class.java, null, null, listOf(0.1f), topK = 5, threshold = null, searchK = null, partitionLabel = null)

        assertEquals(5, wide.requestedRows)
        assertEquals(50, wide.indexK)
        // The candidates are the 50 nearest, and 5 of them come back.
        assertEquals(listOf<Any?>(50, 5), wide.bindings.values.filterIsInstance<Int>())
        // Without it the candidates are the 5 nearest, and nothing further limits them.
        assertEquals(listOf<Any?>(5), plain.bindings.values.filterIsInstance<Int>())
    }

    @Test
    fun `a text search goes through the table of a full-text index that has been made, asked for by all its properties`() {
        val store = store(made("g_t_made"))
        val plan = backend(store).matching(EntityNode::class.java, null, null, "graph", topK = 3, threshold = 0.0)

        assertEquals(
            mapOf<String, Any?>("kind" to "FULLTEXT_INDEX", "label" to "Entity", "properties" to "[\"name\",\"description\"]"),
            store.queried.single().parameters,
        )
        assertContains(plan.cypher, "FROM g_t_made s")
        assertContains(plan.cypher, "JSON_TEXTCONTAINS(s.doc, '$.name'")
        assertContains(plan.cypher, "JSON_TEXTCONTAINS(s.doc, '$.description'")
    }

    @Test
    fun `with no full-text index made, a text search reads the property of every node of the label`() {
        val plan = backend(store(made(null))).matching(ArticleNode::class.java, null, null, "graph", topK = 3, threshold = 0.0)

        assertFalse("JSON_TEXTCONTAINS" in plan.cypher)
        assertContains(plan.cypher, "LOWER(JSON_VALUE(c.props, '$.body' RETURNING CLOB)) AS text")
        assertTrue(3 in plan.bindings.values, "the number asked for is bound")
    }

    // ----- Deleting -----

    @Test
    fun `a delete by id is one counted DELETE of the node found by its id, which a where narrows further`() {
        val backend = backend()
        val plain = backend.delete(Claim::class.java, null, CascadeType.NONE).statement.squashed()
        val narrowed = backend.delete(Claim::class.java, "n.text = 'x'", CascadeType.NONE).statement.squashed()

        assertContains(plain, "DELETE FROM g_node n_ WHERE INSTR(JSON_SERIALIZE(n_.labels), '\"Claim\"') > 0 AND JSON_VALUE(n_.props, '$.id') = \$id; v_count := SQL%ROWCOUNT;")
        assertContains(plain, "OPEN v_rows FOR SELECT v_count FROM dual;")
        assertContains(narrowed, "AND JSON_VALUE(n_.props, '$.id') = \$id AND (JSON_VALUE(n_.props, '$.text') = 'x');")
    }

    @Test
    fun `a delete of all scans the label, and with no where takes every node of it`() {
        val backend = backend()
        val all = backend.deleteAll(Claim::class.java, null as String?).statement.squashed()
        val some = backend.deleteAll(Claim::class.java, "n.text = 'x'").statement.squashed()

        assertContains(all, "DELETE FROM g_node n_ WHERE JSON_EXISTS(n_.labels, '$[*]?(@ == \"Claim\")'); v_count := SQL%ROWCOUNT;")
        assertContains(some, "DELETE FROM g_node n_ WHERE JSON_EXISTS(n_.labels, '$[*]?(@ == \"Claim\")') AND (JSON_VALUE(n_.props, '$.text') = 'x');")
    }

    @Test
    fun `a cascade that deletes all takes the roots and what they reach in one set, so a node reached twice is deleted once`() {
        val sql = backend().delete(ClaimView::class.java, null, CascadeType.DELETE_ALL).statement.squashed()

        assertContains(sql, "TYPE t_ids IS TABLE OF NUMBER; v_ids t_ids;")
        assertContains(sql, "SELECT nid BULK COLLECT INTO v_ids FROM ( SELECT claim_.nid FROM g_node claim_ WHERE")
        assertContains(sql, " UNION ")
        assertFalse(" UNION ALL " in sql)
        assertContains(sql, "FORALL i IN 1 .. v_ids.COUNT DELETE FROM g_node WHERE nid = v_ids(i); v_count := v_ids.COUNT;")
    }

    @Test
    fun `a cascade that deletes orphans reads what the roots reach before it deletes them, and keeps a node something else still relates to`() {
        val sql = backend().delete(ClaimView::class.java, null, CascadeType.DELETE_ORPHAN).statement.squashed()

        val collected = sql.indexOf("BULK COLLECT INTO v_ids")
        val rootsDeleted = sql.indexOf("DELETE FROM g_node claim_ WHERE")
        val orphansDeleted = sql.indexOf("DELETE FROM g_node n WHERE n.nid = v_ids(i) AND NOT EXISTS (SELECT 1 FROM g_edge e WHERE e.src = n.nid OR e.dst = n.nid);")
        assertTrue(collected in 0 until rootsDeleted && rootsDeleted < orphansDeleted, sql)
        assertContains(sql, "v_count := v_count + SQL%ROWCOUNT;")
        assertFalse("FORALL" in sql)
    }

    @Test
    fun `a cascade with nothing to reach is the plain delete, for a fragment and for a view whose fields are not its to delete`() {
        val backend = backend()
        for (type in listOf(Claim::class.java, ClaimStats::class.java)) {
            for (cascade in listOf(CascadeType.DELETE_ALL, CascadeType.DELETE_ORPHAN)) {
                val sql = backend.delete(type, null, cascade).statement
                assertFalse("BULK COLLECT" in sql, "${type.simpleName} $cascade")
                assertEquals(backend.delete(type, null, CascadeType.NONE).statement, sql)
            }
        }
    }

    // ----- Saving -----

    @Test
    fun `a batch of fragments is one block for each five hundred, each fragment a step under its own index`() {
        val fragments = (0 until 501).map { IndexedValue(it, Human("h$it", "Human $it")) }
        val batch = backend().saveTogether(fragments, NullPolicy.IGNORE)
        val specs = batch.stamped

        // Stamped or not, every fragment is a step, and none is left to a statement of its own.
        assertTrue(batch.unstamped.isEmpty() && batch.alone.isEmpty())
        assertEquals(2, specs.size)
        val (first, second) = specs.map { steps(it.parameters) }
        assertEquals(500, first.size)
        assertEquals(1, second.size)
        assertEquals("0", first.first()["i"])
        assertEquals("499", first.last()["i"])
        assertEquals("500", second.single()["i"])
        assertEquals("h500", second.single()["k"])
        // One text for every block: the plan is data, so the driver keeps one statement prepared.
        assertEquals(specs[0].statement!!.text, specs[1].statement!!.text)
    }

    @Test
    fun `a patch is a step for each row, in the order of their ids, five hundred to a block of one text`() {
        val rows = (0 until 501).map { PatchRow(it, "h${500 - it}", mapOf("name" to "Human $it", "note" to null)) }
        val specs = backend().patchAll(Human::class.java, listOf("name", "note"), rows, clears = false)

        val (first, second) = specs.map { steps(it.parameters) }
        assertEquals(500, first.size)
        assertEquals((0..500).map { "h$it" }.sorted(), (first + second).map { it["k"] })
        // The node `h0` was given last, and is returned under the index it was given at.
        assertEquals(mapOf("op" to "patch", "s" to 0, "k" to "h0", "i" to "500", "fresh" to first.first()["fresh"], "set" to mapOf("name" to "Human 500")), first.first())
        assertEquals(specs[0].statement!!.text, specs[1].statement!!.text)
        assertContains(specs[0].statement!!.text, "JSON_TABLE(v_result")
    }

    @Test
    fun `a null in a row of a patch is left out of what its step sets, and removed only when the patch clears`() {
        val rows = listOf(PatchRow(0, "h1", mapOf("name" to "Ada", "note" to null)))

        val kept = steps(backend().patchAll(Human::class.java, listOf("name", "note"), rows, clears = false).single().parameters).single()
        val cleared = steps(backend().patchAll(Human::class.java, listOf("name", "note"), rows, clears = true).single().parameters).single()

        assertEquals(mapOf("name" to "Ada") to null, kept["set"] to kept["unset"])
        assertEquals(mapOf("name" to "Ada") to listOf("note"), cleared["set"] to cleared["unset"])
    }

    @Test
    fun `no fragments is no block`() {
        assertTrue(backend().saveTogether(emptyList(), NullPolicy.IGNORE).stamped.isEmpty())
    }

    @Test
    fun `the stamp a node carries is read by its id as stored text and every label of the root`() {
        val found = backend().foundStamp(StampWrite(Gauge::class.java, 3.0, "Gauge:Hot", "id", null))

        assertEquals(
            "SELECT n.stamp FROM g_node n WHERE JSON_VALUE(n.props, '$.id') = \$id" +
                " AND INSTR(JSON_SERIALIZE(n.labels), '\"Gauge\"') > 0 AND INSTR(JSON_SERIALIZE(n.labels), '\"Hot\"') > 0",
            found.statement,
        )
        assertEquals(mapOf<String, Any?>("id" to "3"), found.bindings)
    }

    @Test
    fun `the stamp of a node whose label has a colon in it is looked for under that label whole`() {
        val saved = backend().save(SaveRequest(Spaced("s1"), checked = true, nullPolicy = NullPolicy.IGNORE))
        val found = backend().foundStamp(saved.root)

        assertContains(found.statement, OracleSql.carriesLabel("n", "ns:Thing"))
        assertFalse(OracleSql.carriesLabel("n", "ns") in found.statement)
    }

    @Test
    fun `a deadlock and a serialization failure are contention, and no other failure is`() {
        val backend = backend()

        assertTrue(backend.contended(SQLException("deadlock detected while waiting for resource", "61000", 60)))
        assertTrue(backend.contended(SQLException("can't serialize access for this transaction", "72000", 8177)))
        // A wait that ran out, a unique key, a lost connection: none is another writer at that moment.
        assertFalse(backend.contended(SQLException("resource busy and acquire with NOWAIT specified", "61000", 54)))
        assertFalse(backend.contended(SQLException("unique constraint violated", "23000", 1)))
        assertFalse(backend.contended(SQLException("no more data to read from socket", "08000", 17410)))
        assertFalse(backend.contended(IllegalStateException("ORA-00060")))
        // The failure itself is asked, not what caused it: the executor walks the causes.
        assertFalse(backend.contended(RuntimeException(SQLException("deadlock", "61000", 60))))
    }

    // ----- Relationships on their own -----

    @Test
    fun `relate finds both nodes, which must be there, and joins them once, leaving out a null property`() {
        val statement = backend().relate(
            NodeRef(Claim::class.java, "c1"), NodeRef(Human::class.java, "h1", setOf("Vip")), "MENTIONS",
            mapOf("page" to 3, "note" to null), RelateMode.MERGE,
        )

        val (from, to, link) = steps(statement.bindings)
        assertEquals(mapOf("op" to "ref", "r" to 0, "s" to 0, "k" to "c1"), from)
        assertEquals(mapOf("op" to "ref", "r" to 1, "s" to 1, "k" to "h1"), to)
        assertEquals(mapOf("op" to "link", "from" to 0, "to" to 1, "type" to "MENTIONS", "dir" to "OUT", "props" to mapOf("page" to 3)), link)
        // The labels the ref adds are part of how the node is found.
        assertContains(statement.statement, "\"Vip\"")
        assertContains(statement.statement, "\"Human\"")
        assertEquals(listOf(OracleWriteBlock.PLAN, OracleWriteBlock.MARK), SqlParameters.positional(statement.statement).names)
    }

    @Test
    fun `relate under CREATE makes one more relationship each time, and with no properties names none`() {
        val created = backend().relate(NodeRef(Claim::class.java, "c1"), NodeRef(Claim::class.java, "c2"), "SUPPORTS", mapOf("note" to null), RelateMode.CREATE)

        val (from, to, link) = steps(created.bindings)
        // Two nodes of one shape are found by the one arm of the block.
        assertEquals(from["s"], to["s"])
        assertEquals(mapOf("op" to "link", "from" to 0, "to" to 1, "type" to "SUPPORTS", "dir" to "OUT", "always" to true), link)
    }

    @Test
    fun `a node is referred to by its id as the store reads it back`() {
        val statement = backend().unrelate(NodeRef(Gauge::class.java, 3.0), NodeRef(Gauge::class.java, 2.5f), "NEAR")

        assertEquals(listOf("3", "2.5"), steps(statement.bindings).filter { it["op"] == "ref" }.map { it["k"] })
    }

    @Test
    fun `unrelate cuts the relationships between two nodes, and unrelateAll those of one node in a direction`() {
        val backend = backend()
        val between = steps(backend.unrelate(NodeRef(Claim::class.java, "c1"), NodeRef(Human::class.java, "h1"), "MENTIONS").bindings)
        val incoming = steps(backend.unrelateAll(NodeRef(Human::class.java, "h1"), "MENTIONS", Direction.INCOMING).bindings)
        val either = steps(backend.unrelateAll(NodeRef(Human::class.java, "h1"), "MENTIONS", Direction.UNDIRECTED).bindings)

        assertEquals(mapOf("op" to "cut", "from" to 0, "to" to 1, "type" to "MENTIONS", "dir" to "OUT"), between.last())
        assertEquals(listOf("ref", "cut"), incoming.map { it["op"] })
        assertEquals(mapOf("op" to "cut", "from" to 0, "type" to "MENTIONS", "dir" to "IN"), incoming.last())
        assertEquals("BOTH", either.last()["dir"])
    }

    @Test
    fun `a relationship with no type is refused, whichever way it is written`() {
        val backend = backend()
        val claim = NodeRef(Claim::class.java, "c1")
        val human = NodeRef(Human::class.java, "h1")

        val refusals = listOf(
            assertFailsWith<IllegalArgumentException> { backend.relate(claim, human, " ", emptyMap(), RelateMode.MERGE) },
            assertFailsWith<IllegalArgumentException> { backend.unrelate(claim, human, "") },
            assertFailsWith<IllegalArgumentException> { backend.unrelateAll(claim, "\t", Direction.OUTGOING) },
            assertFailsWith<IllegalArgumentException> { backend.related(claim, "", Direction.OUTGOING, Human::class.java) },
        )
        refusals.forEach { assertEquals("A relationship needs a type.", it.message) }
    }

    @Test
    fun `a node of a class with no id cannot be referred to`() {
        val backend = backend()
        val drift = NodeRef(Drift::class.java, "d1")
        val message = "Drift has no @NodeId field, so a node of it cannot be referred to by id."

        assertEquals(message, assertFailsWith<IllegalArgumentException> { backend.relate(drift, NodeRef(Human::class.java, "h1"), "SEES", emptyMap(), RelateMode.MERGE) }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { backend.relate(NodeRef(Human::class.java, "h1"), drift, "SEES", emptyMap(), RelateMode.MERGE) }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { backend.unrelateAll(drift, "SEES", Direction.OUTGOING) }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { backend.related(drift, "SEES", Direction.OUTGOING, Human::class.java) }.message)
    }

    @Test
    fun `the nodes related to a node are loaded as the target, found from the node by its id and every label its ref names`() {
        val statement = backend().related(NodeRef(Gauge::class.java, 3.0, setOf("Hot")), "READ_BY", Direction.INCOMING, Human::class.java)
        val sql = statement.statement.squashed()

        assertEquals(mapOf<String, Any?>("_fromId" to "3"), statement.bindings)
        assertContains(sql, "<-[e IS edge WHERE e.type = 'READ_BY']-")
        assertContains(sql, "\"Gauge\"")
        assertContains(sql, "\"Hot\"")
        assertContains(sql, "= \$_fromId")
        // The target's label is tested on the node found, not used to find it.
        assertContains(sql, "INSTR(JSON_SERIALIZE(n_.labels), '\"Human\"') > 0")
    }

    @Test
    fun `a relationship type with a quote in it is written as a literal with the quote doubled`() {
        val statement = backend().related(NodeRef(Claim::class.java, "c1"), "IT'S", Direction.OUTGOING, Human::class.java)

        assertContains(statement.statement, "e.type = 'IT''S'")
    }
}
