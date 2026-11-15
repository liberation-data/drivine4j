package org.drivine.backend.oracle

import java.time.Instant
import org.drivine.annotation.Hop
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.query
import sample.groups.Feedback
import sample.groups.FeedbackQueryDsl
import sample.groups.Message
import sample.groups.MessageQueryDsl
import sample.groups.Owner
import sample.groups.Role
import sample.groups.User
import org.drivine.annotation.Direction
import org.drivine.annotation.NodeFragment
import com.fasterxml.jackson.databind.JsonNode
import org.drivine.backend.GraphBackend
import org.drivine.connection.CypherCompile
import org.drivine.cypher.sql.FragmentVariable
import org.drivine.cypher.sql.Temporal
import org.drivine.cypher.store.ValueLayout
import org.drivine.cypher.store.OracleSql as StoreSql
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
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.OrderDirection.ASC
import org.drivine.query.dsl.OrderDirection.DESC
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.PropertyReference
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.WhereCondition.PropertyCondition
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import sample.fulltext.ArticleNode
import sample.fulltext.EntityNode
import sample.stateless.Claim
import sample.stateless.ClaimStats
import sample.stateless.ClaimView
import sample.stateless.Human
import sample.stateless.Pals
import sample.mapped.view.DeletableSessionTree
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

    private fun backend(store: RecordingStore = store()) = OracleBackend(store, Neo4jObjectMapper.instance, catalogReads = Runnable::run)

    private fun query(build: GraphQuerySpec<Any>.() -> Unit = {}) = GraphQuerySpec(Any()).apply(build)

    @Suppress("UNCHECKED_CAST")
    private fun steps(bindings: Map<String, Any?>): List<Map<String, Any?>> = bindings[OracleWriteBlock.PLAN] as List<Map<String, Any?>>

    private fun String.squashed(): String = replace(Regex("\\s+"), " ")

    /** The module's compiler of the fragments of a statement over the graph `g`, which has no labelled index. */
    private val fragments = CypherCompile.oracleFragments("g", emptySet())

    /** The terms the module orders the node [row] by its property [key], as Cypher orders it, descending where [descending]. */
    private fun terms(row: String, key: String, descending: Boolean = false, cypher: String = "n.`$key`"): String =
        fragments.order(cypher, descending, mapOf("n" to FragmentVariable.Node(row))).terms.joinToString(", ") { it.squashed() }

    /** The value of the property [key] of the node [row], as the module reads it to group or return by. */
    private fun value(row: String, key: String): String = fragments.value("n.`$key`", mapOf("n" to FragmentVariable.Node(row))).terms.single().squashed()

    /** The condition the module compiles [cypher] to over the node [row] of [labels], given [parameters]. */
    private fun condition(row: String, labels: List<String>, cypher: String, parameters: Map<String, Any?>): String =
        fragments.condition(cypher, mapOf("n" to FragmentVariable.Node(row, labels)), parameters).terms.single().squashed()

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
        val backend = OracleBackend(store, Neo4jObjectMapper.instance, catalogReads = Runnable::run)

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

        // Compared by its class and key, as the index over the property of every node is made over them.
        assertContains(statement.statement, OracleSql.hasId("n_", "id", "JSON(\$${GraphBackend.ID_PARAM})"))
        assertEquals(setOf(GraphBackend.ID_PARAM), SqlParameters.positional(statement.statement).names.toSet())
        assertTrue(statement.bindings.isEmpty(), "the id is the caller's to bind")
        // Bound as the JSON the node holds it as.
        assertEquals(mapOf<String, Any?>(GraphBackend.ID_PARAM to "\"c1\""), statement.spec(mapOf(GraphBackend.ID_PARAM to "c1")).parameters)
    }

    @Test
    fun `a raw where is read as Cypher writes a property, for a load and for a count`() {
        val backend = backend()

        assertContains(backend.loadAll(Claim::class.java, "n.text = 'x'").statement, "(COALESCE(JSON_VALUE(n_.props, '$.text.v'), JSON_VALUE(n_.props, '$.text')) = 'x')")
        val count = backend.count(Claim::class.java, "n.text = 'x'").statement
        assertTrue(count.startsWith("SELECT COUNT(*)"), count)
        assertContains(count, "(COALESCE(JSON_VALUE(n_.props, '$.text.v'), JSON_VALUE(n_.props, '$.text')) = 'x')")
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

        // Its nulls are placed by a term of their own, of whether the value is null, before the value's own terms, as Cypher
        // orders them; a property no field declares alike.
        for (key in listOf("text", "undeclared")) {
            fun isNull(descending: Boolean) = terms("n_", key, descending, "n.`$key` IS NULL")
            val unplaced = terms("n_", key, descending = true)
            assertTrue(ordered(OrderSpec("n.$key", DESC)).squashed().startsWith(unplaced), key)
            assertTrue(ordered(OrderSpec("n.$key", DESC).nullsLast()).squashed().startsWith("${isNull(false)}, $unplaced"), key)
            assertTrue(ordered(OrderSpec("n.$key", DESC).nullsFirst()).squashed().startsWith("${isNull(true)}, $unplaced"), key)
            assertTrue(ordered(OrderSpec("n.$key", ASC).nullsFirst()).squashed().startsWith("${isNull(true)}, ${terms("n_", key)}"), key)
        }

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
        // The cursor's value bound as JSON text, by the name the module gives the seek's fragment, and compared as Cypher does.
        val (cursor, value) = seeking.statement.bindings.entries.single { it.key.startsWith("_cy_") }
        assertEquals("\"m\"", value)
        val seek = condition("n_", listOf("Claim"), "n.`text` < \$${cursor.removePrefix("_cy_")}", mapOf(cursor.removePrefix("_cy_") to "m"))
        assertContains(seeking.statement.statement.squashed(), seek)
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
        assertTrue(rootOrder.startsWith(terms("claim_", "text")), rootOrder)
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
            "SELECT object_name FROM g_schema WHERE kind = \$kind AND label = \$label AND properties = \$properties AND object_name IS NOT NULL" +
                " AND JSON_VALUE(details, '$.dimensions' RETURNING NUMBER) = \$dimensions",
            asked.statement,
        )
        // Only an index of the vector's size is searched: Oracle refuses to measure two sizes against each other.
        assertEquals(mapOf<String, Any?>("kind" to "VECTOR_INDEX", "label" to "VecDoc", "properties" to "[\"embedding\"]", "dimensions" to 2), asked.parameters)
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
        // Only the embeddings of the vector's size are measured: Oracle refuses to measure two sizes against each other.
        assertContains(plan.cypher, "VECTOR_DIMENSION_COUNT(TO_VECTOR(JSON_QUERY(c.props, '$.embedding' RETURNING CLOB))) = 1")
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

        // The shared module's lookup of the catalog, its parameters under the compiler's reserved prefix.
        val asked = store.queried.single()
        assertEquals("SELECT object_name FROM g_schema WHERE kind = \$_cy_ft1 AND label = \$_cy_ft2 AND properties = \$_cy_ft3 AND object_name IS NOT NULL", asked.statement)
        assertEquals(
            mapOf<String, Any?>("_cy_ft1" to "FULLTEXT_INDEX", "_cy_ft2" to "Entity", "_cy_ft3" to "[\"name\",\"description\"]"),
            asked.parameters,
        )
        assertContains(plan.cypher, "FROM g_t_made s")
        assertContains(plan.cypher, "JSON_TEXTCONTAINS(s.doc, '$.name'")
        assertContains(plan.cypher, "JSON_TEXTCONTAINS(s.doc, '$.description'")
    }

    @Test
    fun `with no full-text index made, a text search reads the property of every node of the label`() {
        val plan = backend(store(made(null))).matching(ArticleNode::class.java, null, null, "graph", topK = 3, threshold = 0.0)

        assertFalse("JSON_TEXTCONTAINS" in plan.cypher)
        // The text a search reads of the property: a temporal value's tag by its text, as the module reads it.
        assertContains(plan.cypher, "LOWER(${StoreSql.searchedText("c", "body")}) AS text")
        assertTrue(3 in plan.bindings.values, "the number asked for is bound")
    }

    // ----- Deleting -----

    @Test
    fun `a delete by id is one counted DELETE of the node found by its id, run on its own and given the id, which a where narrows further`() {
        val backend = backend()
        val plain = backend.delete(Claim::class.java, null, CascadeType.NONE).statement.squashed()
        val narrowed = backend.delete(Claim::class.java, "n.text = 'x'", CascadeType.NONE).statement.squashed()

        assertContains(plain, "EXECUTE IMMEDIATE 'DELETE FROM g_node n_ WHERE INSTR('','' || LTRIM(RTRIM(JSON_SERIALIZE(n_.labels), '']''), ''['') || '',\"'', '',\"Claim\",\"'') > 0 AND ")
        // The id compared by its class and key, as the index over the property of every node is made over them.
        // Each place the comparison reads the id is a bind of its own, each given the id.
        val binds = Regex("""JSON\(:b(\d+)\)""").findAll(plain).map { it.groupValues[1].toInt() }.toList()
        assertEquals((0 until binds.size).toList(), binds)
        assertContains(plain, OracleSql.hasId("n_", "id", "JSON(:bN)").replace("'", "''").replace(Regex(""":bN"""), ":b0").substringBefore(" AND "))
        assertContains(plain, "' USING ${List(binds.size) { "\$id" }.joinToString(", ")}; v_count := SQL%ROWCOUNT;")
        assertContains(plain, "OPEN v_rows FOR SELECT v_count FROM dual;")
        assertContains(narrowed, " AND (COALESCE(JSON_VALUE(n_.props, ''$.text.v''), JSON_VALUE(n_.props, ''$.text'')) = ''x'')' USING \$id")
    }

    @Test
    fun `a delete of all scans the label, and with no where takes every node of it`() {
        val backend = backend()
        val all = backend.deleteAll(Claim::class.java, null as String?).statement.squashed()
        val some = backend.deleteAll(Claim::class.java, "n.text = 'x'").statement.squashed()

        assertContains(all, "EXECUTE IMMEDIATE 'DELETE FROM g_node n_ WHERE JSON_EXISTS(n_.labels, ''$[*]?(@ == \"Claim\")'')'; v_count := SQL%ROWCOUNT;")
        assertContains(some, "DELETE FROM g_node n_ WHERE JSON_EXISTS(n_.labels, ''$[*]?(@ == \"Claim\")'') AND (COALESCE(JSON_VALUE(n_.props, ''$.text.v''), JSON_VALUE(n_.props, ''$.text'')) = ''x'')';")
    }

    @Test
    fun `a delete of all by a predicate on a relationship hands the block's statement its value, which a GRAPH_TABLE of the block's own would refuse`() {
        val statement = backend().deleteAll(ClaimView::class.java, query { conditions += PropertyCondition("people.name", ComparisonOperator.EQUALS, "Ada") })
        val sql = statement.statement.squashed()

        // The fragment's hints in the DELETE, and the related node's condition the module's EXISTS, its value the block's bind.
        assertContains(sql, "EXECUTE IMMEDIATE 'DELETE /*+ NO_JSON_TABLE_TRANSFORM */ FROM g_node claim_ WHERE")
        assertContains(sql, "AND EXISTS (SELECT /*+ NO_COALESCE_SQ */ 1 FROM g_node n1 CROSS JOIN g_edge e2 WHERE")
        assertContains(sql, "e2.src = claim_.nid")
        assertContains(sql, "e2.type = ''MENTIONS''")
        assertContains(sql, "= JSON(:b0)")
        assertContains(sql, "' USING \$_cy_param_people_name_0; v_count := SQL%ROWCOUNT;")
        assertEquals(mapOf("_cy_param_people_name_0" to "\"Ada\""), statement.bindings)
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
    fun `a cascade that deletes orphans reads what it keeps before it deletes the roots, and keeps what a kept node holds`() {
        val sql = backend().delete(ClaimView::class.java, null, CascadeType.DELETE_ORPHAN).statement.squashed()

        val collected = sql.indexOf("BULK COLLECT INTO v_ids")
        val rootsDeleted = sql.indexOf("DELETE FROM g_node claim_ WHERE")
        val orphansDeleted = sql.indexOf("FORALL i IN 1 .. v_ids.COUNT DELETE FROM g_node WHERE nid = v_ids(i);")
        assertTrue(collected in 0 until rootsDeleted && rootsDeleted < orphansDeleted, sql)
        // Kept: a node something outside the cascade relates to, and what a kept node holds by a step the cascade follows.
        assertContains(sql, "WHERE (e.src = c.nid AND e.dst NOT IN (SELECT nid FROM cascade_all)) OR (e.dst = c.nid AND e.src NOT IN (SELECT nid FROM cascade_all)))")
        assertContains(sql, "FROM cascade_kept k JOIN g_edge e ON (e.type = 'MENTIONS' AND e.src = k.nid)")
        assertContains(sql, ") CYCLE nid SET looped TO 'Y' DEFAULT 'N'")
        assertContains(sql, "SELECT nid BULK COLLECT INTO v_ids FROM cascade_targets WHERE nid NOT IN (SELECT nid FROM cascade_kept);")
        assertContains(sql, "v_count := v_count + v_ids.COUNT;")
    }

    @Test
    fun `a kept node holds the far end of each step the cascade follows, in the step's direction`() {
        // Each type and direction once: the root's messages, and the parts PART_OF it, at every level.
        val tree = backend().delete(DeletableSessionTree::class.java, null, CascadeType.DELETE_ORPHAN).statement.squashed()
        assertContains(tree, "ON (e.type = 'HAS_MESSAGE' AND e.src = k.nid) OR (e.type = 'PART_OF' AND e.dst = k.nid)")
        assertEquals(1, Regex("e.type = 'PART_OF' AND").findAll(tree).count(), tree)

        val pals = backend().delete(Pals::class.java, null, CascadeType.DELETE_ORPHAN).statement.squashed()
        assertContains(pals, "ON (e.type = 'KNOWS' AND (e.src = k.nid OR e.dst = k.nid))")
        assertContains(pals, "SELECT CASE WHEN e.src = k.nid THEN e.dst ELSE e.src END")
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
    fun `of a batch with fields named each step sets those of its own class`() {
        val fragments = listOf(Human("h1", "Ada"), Claim("c1", "one", "first")).withIndex().toList()
        val fields = mapOf<Class<*>, Set<String>?>(Human::class.java to emptySet(), Claim::class.java to setOf("note"))

        val steps = steps(backend().saveTogether(fragments, NullPolicy.IGNORE) { fields[it] }.stamped.single().parameters)

        assertEquals(listOf<Any?>(emptyMap<String, Any?>(), mapOf("note" to "first")), steps.map { it["set"] ?: emptyMap<String, Any?>() })
    }

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
    fun `a patch is a step for each row, in the order given, five hundred to a block of one text`() {
        val rows = (0 until 501).map { PatchRow(it, "h${500 - it}", mapOf("name" to "Human $it", "note" to null)) }
        val specs = backend().patchAll(Human::class.java, listOf("name", "note"), rows, clears = false)

        val (first, second) = specs.map { steps(it.parameters) }
        assertEquals(500, first.size)
        assertEquals((0..500).map { "h${500 - it}" }, (first + second).map { it["k"] })
        // The node `h0` was given last, and is returned under the index it was given at.
        assertEquals(mapOf("op" to "patch", "s" to 0, "k" to "h0", "i" to "500", "fresh" to second.single()["fresh"], "set" to mapOf("name" to "Human 500"), "id" to "h0"), second.single())
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
    fun `a patch of one is one step, and its predicate is in the block's text, run as a statement of its own`() {
        val conditions = listOf(
            WhereCondition.PropertyCondition("claim.text", ComparisonOperator.EQUALS, "one"),
            WhereCondition.PropertyCondition("people.name", ComparisonOperator.EQUALS, "Ada"),
        )
        val patch = backend().patch(ClaimView::class.java, 7.0, linkedMapOf("text" to "two", "note" to null), conditions)

        val step = steps(patch.bindings).single()
        assertEquals(
            mapOf("op" to "patch", "s" to 0, "k" to "7", "i" to "0", "fresh" to step["fresh"], "set" to mapOf("text" to "two"), "unset" to listOf("note"), "id" to 7.0),
            step,
        )
        // A String id is the value given, 7.0, which no node of a String id holds, as on the graph engines.
        // The predicate's values are the block's own parameters, handed to the query in the order it names them.
        val text = "_cy_param_claim_text_0"
        val name = "_cy_param_people_name_1"
        assertEquals(setOf(OracleWriteBlock.PLAN, OracleWriteBlock.MARK, text, name), patch.bindings.keys)
        assertEquals(listOf<Any?>("\"one\"", "\"Ada\""), listOf(patch.bindings[text], patch.bindings[name]))
        val asked = patch.statement.substringAfter("EXECUTE IMMEDIATE '").substringBefore("'\n        INTO v_met\n        USING ")
        // The fragments' hints in the query's SELECT, and a condition on a related node the module's EXISTS.
        assertContains(asked.squashed(), "SELECT /*+ NO_JSON_TABLE_TRANSFORM */ COUNT(*) FROM g_node claim_ WHERE claim_.nid = :b0 AND ")
        assertContains(asked, "EXISTS (SELECT /*+ NO_COALESCE_SQ */ 1 FROM g_node n1 CROSS JOIN g_edge e2 WHERE")
        // Inside the text a quote is doubled, and no parameter of the block is named.
        assertContains(asked, "e2.type = ''MENTIONS''")
        assertFalse("\$_cy" in asked)
        assertEquals(listOf(":b0", ":b1", ":b2"), Regex(":b\\d+").findAll(asked).map { it.value }.toList())
        assertEquals(listOf("v_nid", "\$$text", "\$$name"), patch.statement.substringAfter("INTO v_met\n        USING ").substringBefore(";").split(", ").let { used -> listOf(used[0]) + used.drop(1).sorted() })
        assertContains(patch.statement, "IF v_met > 0 THEN\n            write_props(op, v_o);")
        assertContains(patch.statement, "CASE WHEN v_met = 0 THEN '-' WHEN v_same = 0 THEN '1' ELSE '0' END")
    }

    @Test
    fun `a property of a patch's predicate whose name looks like a parameter is left as the name it is`() {
        val conditions = listOf(
            WhereCondition.PropertyCondition("n.cost\$_p0", ComparisonOperator.IS_NULL, null),
            WhereCondition.PropertyCondition("n.name", ComparisonOperator.EQUALS, "Ada"),
        )
        val patch = backend().patch(Human::class.java, "h1", mapOf("name" to "Eve"), conditions)

        val asked = patch.statement.substringAfter("EXECUTE IMMEDIATE '").substringBefore("'\n        INTO v_met\n        USING ")
        assertContains(asked, "cost\$_p0")
        assertEquals("v_nid, \$_cy_param_n_name_0", patch.statement.substringAfter("INTO v_met\n        USING ").substringBefore(";"))
        assertEquals(listOf(":b0", ":b1"), Regex(":b\\d+").findAll(asked).map { it.value }.toList())
    }

    @Test
    fun `a patch of one with no predicate asks nothing before it writes, and its block is a patch of many's`() {
        val one = backend().patch(Human::class.java, "h1", mapOf("name" to "Ada"), emptyList())
        val many = backend().patchAll(Human::class.java, listOf("name"), listOf(PatchRow(0, "h1", mapOf("name" to "Ada"))), clears = false).single()

        assertFalse("EXECUTE IMMEDIATE" in one.statement)
        assertEquals(many.statement!!.text, one.statement)
        assertEquals(setOf(OracleWriteBlock.PLAN, OracleWriteBlock.MARK), one.bindings.keys)
    }

    @Test
    fun `no fragments is no block`() {
        assertTrue(backend().saveTogether(emptyList(), NullPolicy.IGNORE).stamped.isEmpty())
    }

    @Test
    fun `the stamp a node carries is read by its id's JSON and every label of the root`() {
        val found = backend().foundStamp(StampWrite(Gauge::class.java, 3.0, "Gauge:Hot", "id", null))

        assertEquals(
            "SELECT n.stamp FROM g_node n WHERE ${OracleSql.hasId("n", "id", "JSON(\$id)")}" +
                " AND INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n.labels), ']'), '[') || ',\"', ',\"Gauge\",\"') > 0 AND INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n.labels), ']'), '[') || ',\"', ',\"Hot\",\"') > 0",
            found.statement,
        )
        assertEquals(mapOf<String, Any?>("id" to "3.0"), found.bindings)
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
        assertEquals(mapOf("op" to "ref", "r" to 0, "s" to 0, "k" to "c1", "id" to "c1"), from)
        assertEquals(mapOf("op" to "ref", "r" to 1, "s" to 1, "k" to "h1", "id" to "h1"), to)
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

        // The id as the JSON the node holds it as: a double, which Oracle compares as the number it is.
        assertEquals(mapOf<String, Any?>("_fromId" to "3.0"), statement.bindings)
        assertContains(sql, "<-[e IS edge WHERE e.type = 'READ_BY']-")
        assertContains(sql, "\"Gauge\"")
        assertContains(sql, "\"Hot\"")
        assertContains(sql, "JSON(\$_fromId)")
        // The target's label is tested on the node found, not used to find it.
        assertContains(sql, "INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n_.labels), ']'), '[') || ',\"', ',\"Human\",\"') > 0")
    }

    @Test
    fun `a relationship type with a quote in it is written as a literal with the quote doubled`() {
        val statement = backend().related(NodeRef(Claim::class.java, "c1"), "IT'S", Direction.OUTGOING, Human::class.java)

        assertContains(statement.statement, "e.type = 'IT''S'")
    }

    // ----- Grouped reads -----

    /** Guide's page ratings: the votes of each page, with two filtered counts on one property. */
    private fun pageVotes() = GroupQuerySpec(FeedbackQueryDsl.INSTANCE).apply {
        key(query.page)
        val votes = count()
        count { query.helpful eq true }
        count { query.helpful eq false }
        having { votes gte 3 }
    }

    /** Guide's most active users: the messages of each owner, two hops away, most first. */
    private fun topUsers() = GroupQuerySpec(MessageQueryDsl.INSTANCE).apply {
        where { query.role eq Role.USER; query.createdAt gte Instant.parse("2026-03-01T00:00:00Z") }
        key(path<User>(Hop("HAS_MESSAGE", Direction.INCOMING, label = "GroupSession"), Hop("OWNED_BY")))
        val written = count()
        sum(query.tokens) { query.role eq Role.USER }
        orderBy { written.desc() }
        skip(10)
        limit(5)
    }

    @Test
    fun `a grouped read by a property groups the roots, and the statement filters, orders and returns its rows`() {
        val groups = backend().groups(Feedback::class.java, pageVotes())
        val sql = groups.statement.squashed()

        // The fragments' hints in the outermost SELECT, and there alone.
        assertTrue(sql.startsWith("SELECT /*+ NO_JSON_TABLE_TRANSFORM */ JSON_OBJECT( 'k0' VALUE g_.k0, 'a0' VALUE g_.a0, 'a1' VALUE g_.a1, 'a2' VALUE g_.a2 RETURNING JSON) AS result"), sql)
        assertEquals(1, Regex(Regex.escape("/*+")).findAll(sql).count(), sql)
        // Grouped by the key's value as the module reads it, whatever kind it is.
        val page = value("n_", "page")
        assertContains(sql, "SELECT $page AS k0, COUNT(*) AS a0, ")
        assertContains(sql, "GROUP BY $page ) g_")
        // Each filtered count's condition the DSL's Cypher compiled by the module, its value bound by the name it gives it.
        fun helpful(i: Int, value: Boolean) =
            condition("n_", listOf("GroupFeedback"), "n.helpful = \$param_n_helpful_$i", mapOf("param_n_helpful_$i" to value))
        assertContains(sql, "COALESCE(SUM(CASE WHEN ${helpful(0, true)} THEN 1 ELSE 0 END), 0) AS a1")
        assertContains(sql, "COALESCE(SUM(CASE WHEN ${helpful(1, false)} THEN 1 ELSE 0 END), 0) AS a2")
        // Ordered by the key as Cypher orders it, read from a node of the group that holds it.
        assertTrue(sql.endsWith("WHERE g_.a0 >= \$_p2 ORDER BY ${terms("k0_holder_", "page")}"), sql)
        assertEquals(mapOf<String, Any?>("_cy_param_n_helpful_0" to "true", "_cy_param_n_helpful_1" to "false", "_p2" to 3), groups.bindings)
    }

    @Test
    fun `a grouped read by a node reads the distinct pairs of a root and its key node, and joins each back for what is read of it`() {
        val sql = backend().groups(Message::class.java, topUsers()).statement.squashed()

        assertContains(sql, "MATCH (n_ IS node WHERE JSON_EXISTS(n_.labels, '$[*]?(@ == \"GroupMessage\")')) <-")
        assertContains(sql, "<-[e1_1 IS edge WHERE e1_1.type = 'HAS_MESSAGE']- (via_ IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(via_.labels), ']'), '[') || ',\"', ',\"GroupSession\",\"') > 0)")
        assertContains(sql, "-[e1_2 IS edge WHERE e1_2.type = 'OWNED_BY']-> (k0_ IS node WHERE INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(k0_.labels), ']'), '[') || ',\"', ',\"GroupUser\",\"') > 0 AND INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(k0_.labels), ']'), '[') || ',\"', ',\"GroupPerson\",\"') > 0)")
        assertContains(sql, "SELECT DISTINCT r.nid, r.k0 FROM GRAPH_TABLE (g")
        // Each pair's root joined back by the keys Oracle keeps, and the where the module's condition on it, the path's start.
        val joined = "COLUMNS (n_.nid, k0_.nid AS k0)) r ) pair_ JOIN g_node n_ ON (n_.nid = pair_.nid + 0 AND pair_.nid = n_.nid + 0) WHERE "
        assertContains(sql, joined)
        val where = sql.substringAfter(joined).substringBefore(") n_ GROUP BY").substringBefore("GROUP BY pair_.k0")
        assertContains(where, "JSON(\$_cy_param_n_role_0)")
        assertContains(where, "\$_cy_param_n_createdAt_1")
        assertContains(sql, "GROUP BY pair_.k0 ) g_")
        assertContains(sql, "THEN JSON_VALUE(n_.props, '$.tokens' RETURNING NUMBER) END), 0) AS a1")
        // The key's node is joined back for its document, and gives the order its last term, its id as Cypher orders it.
        assertContains(sql, "'k0' VALUE JSON_TRANSFORM(k0_.props, SET '$.labels' = k0_.labels, SET '$.\"__drivine.stamp\"' = k0_.stamp)")
        assertContains(sql, "JOIN g_node k0_ ON k0_.nid = g_.k0")
        assertTrue(sql.endsWith("ORDER BY g_.a0 DESC, ${terms("k0_", "id")} OFFSET ${'$'}_p3 ROWS FETCH NEXT ${'$'}_p4 ROWS ONLY"), sql)
    }

    @Test
    fun `a grouped read that counts by a node alone reads no row of the roots`() {
        val spec = GroupQuerySpec(MessageQueryDsl.INSTANCE).apply {
            key(path<User>(Hop("OWNED_BY")))
            count()
        }

        val sql = backend().groups(Message::class.java, spec).statement.squashed()

        assertContains(sql, "SELECT pair_.k0 AS k0, COUNT(*) AS a0 FROM ( SELECT DISTINCT r.nid, r.k0 FROM GRAPH_TABLE (g")
        assertFalse("JOIN g_node n_" in sql)
    }

    @Test
    fun `two hops of one type in a path are two relationships, and a test against a list is asked of the root's own row`() {
        val spec = GroupQuerySpec(MessageQueryDsl.INSTANCE).apply {
            where { query.role eq Role.USER; query.tokens inList listOf(1L, 2L) }
            key(path<Message>(Hop("HAS_MESSAGE", Direction.INCOMING), Hop("HAS_MESSAGE")))
            val least = min(query.createdAt)
            having { least eq Instant.parse("2026-03-01T00:00:00Z") }
        }

        val groups = backend().groups(Message::class.java, spec)
        val sql = groups.statement.squashed()

        val joined = "WHERE e1_1.eid <> e1_2.eid COLUMNS (n_.nid, k0_.nid AS k0)) r ) pair_ JOIN g_node n_ ON (n_.nid = pair_.nid + 0 AND pair_.nid = n_.nid + 0) WHERE "
        assertContains(sql, joined)
        // The where is the module's, on the root's own row: the test against the list reads the bound list there, and not in the pattern.
        assertContains(sql.substringAfter(joined), "JSON_TABLE(JSON(\$_cy_param_n_tokens_1)")
        assertFalse("JSON_TABLE" in sql.substringAfter("MATCH").substringBefore("COLUMNS"))
        assertEquals("[1,2]", groups.bindings["_cy_param_n_tokens_1"])
        // The least value's node joined back, and the having compares its value with the moment, bound as its tag.
        assertContains(sql, ") g_ JOIN g_node k0_ ON k0_.nid = g_.k0 LEFT JOIN g_node a0_holder_ ON (a0_holder_.nid = g_.a0r + 0 AND g_.a0r = a0_holder_.nid + 0) WHERE ")
        val having = groups.bindings.entries.single { it.key.startsWith("_cy_f") }
        assertEquals(Neo4jObjectMapper.instance.valueToTree<JsonNode>(ValueLayout.tagMap(Temporal.DATE_TIME, "2026-03-01T00:00:00Z")), Neo4jObjectMapper.instance.readTree(having.value as String))
    }

    @Test
    fun `a key no field declares is grouped by its value as the module reads it, whatever kind it is`() {
        val spec = GroupQuerySpec(MessageQueryDsl.INSTANCE).apply {
            val priority = keyOrNull(PropertyReference<Any?>("n", "meta.priority"))
            orderBy { priority.desc() }
        }

        val sql = backend().groups(Message::class.java, spec).statement.squashed()

        // One key, its name quoted as a JSON path step, read and ordered as Cypher reads and orders a value.
        assertContains(sql, "GROUP BY ${value("n_", "meta.priority")} ) g_")
        assertContains(sql, "'k0' VALUE g_.k0 RETURNING JSON")
        assertTrue(sql.endsWith("ORDER BY ${terms("k0_holder_", "meta.priority", descending = true)}"), sql)
    }

    @Test
    fun `a key is read as the module reads a value whatever its field declares, and a least value is its node's own`() {
        val spec = GroupQuerySpec(FeedbackQueryDsl.INSTANCE).apply {
            keyOrNull(query.stars)
            key(query.createdAt)
            min(query.createdAt)
            avg(query.stars)
        }

        val sql = backend().groups(Feedback::class.java, spec).statement.squashed()

        assertContains(sql, "GROUP BY ${value("n_", "stars")}, ${value("n_", "createdAt")} ) g_")
        // The least moment is the value of the first node of its group as Cypher orders it, joined back.
        assertContains(sql, "'a0' VALUE ${value("a0_holder_", "createdAt")}")
        assertContains(sql, "AVG(JSON_VALUE(n_.props, '$.stars' RETURNING NUMBER)) AS a1")
        assertTrue(sql.endsWith("ORDER BY ${terms("k0_holder_", "stars")}, ${terms("k1_holder_", "createdAt")}"), sql)
    }
}
