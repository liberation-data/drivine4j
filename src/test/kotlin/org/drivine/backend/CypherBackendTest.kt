package org.drivine.backend

import org.drivine.annotation.Direction
import org.drivine.backend.oracle.OracleBackend
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSchemaGrammar
import org.drivine.connection.DatabaseType
import org.drivine.manager.CascadeType
import org.drivine.manager.NodeRef
import org.drivine.manager.NullPolicy
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps
import org.drivine.query.PatchRow
import org.drivine.query.QuerySpecification
import org.drivine.query.SaveRequest
import org.drivine.query.StampWrite
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.OrderDirection.ASC
import org.drivine.query.dsl.OrderDirection.DESC
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import org.neo4j.driver.exceptions.ClientException
import org.neo4j.driver.exceptions.ServiceUnavailableException
import org.neo4j.driver.exceptions.TransientException
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.dsl.ComparisonOperator
import sample.stateless.ClaimView
import sample.stateless.Claim
import sample.vector.DocNode
import sample.stateless.Human
import sample.stateless.Tagged
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the backend of the graph engines adds to the builders it calls: which backend a store gets,
 * what is refused, what is bound, and what it asks the store itself. The store is a [RecordingStore].
 */
class CypherBackendTest {

    private val neo4j = CypherDialect.NEO4J_5.grammar()

    private fun backend(store: RecordingStore = RecordingStore(DatabaseType.NEO4J)) = CypherBackend(store, neo4j, Neo4jObjectMapper.instance)

    private fun query(build: GraphQuerySpec<Any>.() -> Unit = {}) = GraphQuerySpec(Any()).apply(build)

    private val seeking = query {
        orders += OrderSpec("n.text", ASC)
        seekValues += SeekValueSpec("n.text", "m")
    }

    // ----- Which backend -----

    @Test
    fun `a store that speaks Cypher gets the Cypher backend with the manager's own grammar`() {
        for (type in listOf(DatabaseType.NEO4J, DatabaseType.MEMGRAPH, DatabaseType.FALKORDB, DatabaseType.NEPTUNE, DatabaseType.EMBABEL)) {
            val store = RecordingStore(type)
            val backend = GraphBackends.of(store, Neo4jObjectMapper.instance)

            assertIs<CypherBackend>(backend, type.name)
            assertSame(store.grammar.javaClass, backend.cypherGrammar!!.javaClass)
            assertTrue(store.schema.isEmpty() && store.queried.isEmpty(), "nothing is asked of the store to build it")
        }
    }

    @Test
    fun `a store that speaks neither Cypher nor a language Drivine has a backend for is refused, and sent nothing`() {
        val store = RecordingStore(DatabaseType.POSTGRES)

        val refused = assertFailsWith<UnsupportedOperationException> { GraphBackends.of(store, Neo4jObjectMapper.instance) }
        assertContains(refused.message!!, "POSTGRES")
        assertTrue(store.schema.isEmpty() && store.queried.isEmpty())
    }

    @Test
    fun `Oracle gets its own backend, which has no Cypher grammar and makes the graph the manager names`() {
        val store = RecordingStore(DatabaseType.ORACLE, ddl = OracleSchemaGrammar(OracleGraph("g")), graphName = "g")
        val backend = GraphBackends.of(store, Neo4jObjectMapper.instance)

        assertIs<OracleBackend>(backend)
        assertNull(backend.cypherGrammar)
        assertEquals(listOf(OracleGraph("g").ensuring), store.schema)
    }

    @Test
    fun `what an engine can do is what its grammar says`() {
        val falkor = CypherBackend(RecordingStore(DatabaseType.FALKORDB), CypherDialect.FALKORDB.grammar(), Neo4jObjectMapper.instance)
        val grammar = CypherDialect.FALKORDB.grammar()

        assertEquals(grammar.supportsOrphanDelete, falkor.supportsOrphanDelete)
        assertEquals(grammar.orphanDeleteLimit, falkor.orphanDeleteLimit)
        assertEquals(grammar.supportsIndexBackedOrdering, falkor.supportsIndexBackedOrdering)
        assertEquals(neo4j.supportsOrphanDelete, backend().supportsOrphanDelete)
    }

    // ----- Reading -----

    @Test
    fun `a page is bound and not written, the skip before the limit as Cypher wants them`() {
        val paged = backend().loadAll(Claim::class.java, query { skip = 20; limit = 10 }).statement
        val limited = backend().loadAll(Claim::class.java, query { limit = 10 }).statement
        val skipped = backend().loadAll(Claim::class.java, query { skip = 20 }).statement
        val whole = backend().loadAll(Claim::class.java, query()).statement

        assertTrue(paged.statement.endsWith("\nSKIP \$_skip\nLIMIT \$_limit"), paged.statement)
        assertEquals(mapOf<String, Any?>("_skip" to 20, "_limit" to 10), paged.bindings)
        assertTrue(limited.statement.endsWith("\nLIMIT \$_limit") && "SKIP" !in limited.statement, limited.statement)
        assertEquals(mapOf<String, Any?>("_limit" to 10), limited.bindings)
        assertTrue(skipped.statement.endsWith("\nSKIP \$_skip") && "LIMIT" !in skipped.statement, skipped.statement)
        assertFalse("SKIP" in whole.statement || "LIMIT" in whole.statement)
        assertTrue(whole.bindings.isEmpty())
        // The text is the same for every page.
        assertEquals(paged.statement, backend().loadAll(Claim::class.java, query { skip = 40; limit = 10 }).statement.statement)
    }

    @Test
    fun `an order that places its nulls orders by the test for null first, and is refused under a seek`() {
        fun ordered(order: OrderSpec) = backend().loadAll(Claim::class.java, query { orders += order }).statement.statement

        assertContains(ordered(OrderSpec("n.text", DESC).nullsLast()), "ORDER BY n.text IS NULL, n.text DESC")
        assertContains(ordered(OrderSpec("n.text", DESC).nullsFirst()), "ORDER BY n.text IS NOT NULL, n.text DESC")
        assertContains(ordered(OrderSpec("n.text", ASC).nullsLast()), "ORDER BY n.text IS NULL, n.text ASC")
        // Unset, the order is as it ever was.
        assertContains(ordered(OrderSpec("n.text", DESC)), "ORDER BY n.text DESC")

        val underSeek = query {
            orders += OrderSpec("n.text", ASC).nullsLast()
            seekValues += SeekValueSpec("n.text", "m")
        }
        assertEquals(
            "nullsLast() on n.text: nulls cannot be placed under seek: a cursor does not say which side of the nulls it is on",
            assertFailsWith<IllegalArgumentException> { backend().loadAll(Claim::class.java, underSeek) }.message,
        )
    }

    @Test
    fun `the conditions of a load stand in its WHERE with no parentheses around them`() {
        // FalkorDB 6 reads a condition in parentheses it does not need by scanning the label, where
        // it would have used an index (FalkorDB/FalkorDB#3331).
        val filtered = query {
            conditions += WhereCondition.PropertyCondition("n.text", ComparisonOperator.EQUALS, "a")
            conditions += WhereCondition.PropertyCondition("n.note", ComparisonOperator.EQUALS, "b")
        }
        val statement = backend().loadAll(Claim::class.java, filtered).statement.statement
        val where = statement.lineSequence().first { it.startsWith("WHERE") }
        assertEquals("WHERE n.text = \$param_n_text_0 AND n.note = \$param_n_note_1", where)

        // A cursor is joined to them as it stands, and over one property has no parentheses either.
        val sought = query {
            conditions += WhereCondition.PropertyCondition("n.note", ComparisonOperator.EQUALS, "b")
            orders += OrderSpec("n.text", ASC)
            seekValues += SeekValueSpec("n.text", "m")
        }
        val paged = backend().loadAll(Claim::class.java, sought).statement.statement.lineSequence().first { it.startsWith("WHERE") }
        assertEquals("WHERE n.note = \$param_n_note_0 AND n.text IS NOT NULL AND n.text > \$_seek_0", paged)
    }

    @Test
    fun `a seek and a skip are refused together, and a seek alone makes a keyset load`() {
        val both = query {
            orders += OrderSpec("n.text", ASC)
            seekValues += SeekValueSpec("n.text", "m")
            skip = 5
        }

        assertEquals("seek and skip cannot be used together", assertFailsWith<IllegalArgumentException> { backend().loadAll(Claim::class.java, both) }.message)
        val plan = backend().loadAll(Claim::class.java, seeking)
        assertTrue(plan.keyset)
        assertEquals(listOf(OrderSpec("n.text", ASC)), plan.rootOrders)
        assertTrue("m" in plan.statement.bindings.values, "the cursor is bound")
        assertFalse(backend().loadAll(Claim::class.java, query { orders += OrderSpec("n.text", ASC) }).keyset)
    }

    @Test
    fun `a count and a delete refuse a seek, which they would ignore`() {
        val message = "seek is only supported by loadAll; this operation would ignore the cursor"

        assertEquals(message, assertFailsWith<IllegalArgumentException> { backend().count(Claim::class.java, seeking) }.message)
        assertEquals(message, assertFailsWith<IllegalArgumentException> { backend().deleteAll(Claim::class.java, seeking) }.message)
    }

    @Test
    fun `a load by id matches the id bound under the name every backend binds it by`() {
        val load = backend().load(Claim::class.java)

        assertContains(load.statement, "\$${GraphBackend.ID_PARAM}")
        assertTrue(load.bindings.isEmpty(), "the id is the caller's to bind")
        assertEquals(mapOf<String, Any?>("id" to "c1", "extra" to 1), load.spec(mapOf("id" to "c1", "extra" to 1)).parameters)
    }

    // ----- Deleting -----

    @Test
    fun `a delete by id that is given a where must meet both`() {
        val plain = backend().delete(Claim::class.java, null, CascadeType.NONE).statement
        val narrowed = backend().delete(Claim::class.java, "n.text = 'x'", CascadeType.NONE).statement

        assertContains(plain, "\$id")
        assertFalse("n.text = 'x'" in plain)
        assertTrue(Regex("\\\$id\\s+AND n\\.text = 'x'").containsMatchIn(narrowed), narrowed)
    }

    // ----- Saving -----

    @Test
    fun `the stamp a node carries is read as empty text when it has none, so a node without one still gives a row`() {
        val found = backend().foundStamp(StampWrite(Claim::class.java, 7L, "Claim:Vip", "id", "a:b"))

        assertEquals("MATCH (n:Claim:Vip {id: \$id}) RETURN coalesce(n.${Stamps.QUOTED}, '')", found.statement)
        assertEquals(mapOf<String, Any?>("id" to 7L), found.bindings)
    }

    @Test
    fun `only what the driver calls transient is contention, and a lost connection is not`() {
        val backend = backend()

        assertTrue(backend.contended(TransientException("Neo.TransientError.Transaction.DeadlockDetected", "deadlock")))
        assertFalse(backend.contended(ServiceUnavailableException("connection reset")))
        assertFalse(backend.contended(ClientException("Neo.ClientError.Statement.SyntaxError", "syntax")))
        assertFalse(backend.contended(IllegalStateException("deadlock")))
        // The failure itself is asked, not what caused it: the executor walks the causes.
        assertFalse(backend.contended(RuntimeException(TransientException("Neo.TransientError.Transaction.DeadlockDetected", "deadlock"))))
    }

    @Test
    fun `a save is one statement, whose root is the node it stamps`() {
        val saved = backend().save(SaveRequest(Claim("c1", "text"), checked = true, nullPolicy = NullPolicy.IGNORE))

        assertEquals(StampWrite(Claim::class.java, "c1", "Claim", "id", null, null), saved.root)
    }

    @Test
    fun `of a batch the fragments an UNWIND can write go as rows, stamped apart from unstamped, and a stamped one it cannot is left to be saved alone`() {
        val fragments = listOf(Human("h1", "Ada"), Claim("c1", "one"), Tagged("t1", "kept", mapOf("k" to "v")), Claim("c2", "two"), Human("h2", "Grace"))
        val batch = backend().saveTogether(fragments.withIndex().toList(), NullPolicy.IGNORE)

        fun ids(spec: QuerySpecification<*>) = (spec.parameters["rows"] as List<*>).map { (it as Map<*, *>)["id"] }
        assertEquals(listOf("h1", "h2"), ids(batch.unstamped.single()))
        assertFalse("RETURN" in batch.unstamped.single().statement!!.text)
        // A stamped row is returned under the index its fragment had in the batch.
        assertEquals(listOf("c1", "c2"), ids(batch.stamped.single()))
        assertEquals(listOf("1", "3"), (batch.stamped.single().parameters["rows"] as List<*>).map { (it as Map<*, *>)["i"] })
        assertContains(batch.stamped.single().statement!!.text, "RETURN row.i + '/' + ")
        assertEquals(listOf(IndexedValue(2, fragments[2])), batch.alone)
    }

    @Test
    fun `a patch of one binds the id, each value by its index, the predicate's values and a stamp to offer`() {
        val conditions = listOf(WhereCondition.PropertyCondition("n.text", ComparisonOperator.EQUALS, "one"))
        val patch = backend().patch(Claim::class.java, java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), linkedMapOf("text" to "two", "note" to null), conditions)

        assertContains(patch.statement, "MATCH (n:Claim {id: \$id})")
        assertContains(patch.statement, "WITH n, coalesce((n.text = \$param_n_text_0), false) AS _met")
        assertEquals(setOf("id", "_v0", "param_n_text_0", "_stamp"), patch.bindings.keys)
        assertEquals(listOf("00000000-0000-0000-0000-000000000001", "two", "one"), listOf("id", "_v0", "param_n_text_0").map { patch.bindings[it] })
        assertTrue(Stamps.isTwoTokens(patch.bindings["_stamp"] as String))
    }

    @Test
    fun `a patch of a view names its root as the view does, and tests a relationship as a load does`() {
        val conditions = listOf(WhereCondition.PropertyCondition("people.name", ComparisonOperator.EQUALS, "Ada"))
        val patch = backend().patch(ClaimView::class.java, "c1", mapOf("note" to "narrated"), conditions)

        assertContains(patch.statement, "MATCH (claim:Claim {id: \$id})")
        assertContains(patch.statement, "WITH claim, coalesce((EXISTS { (claim)-[:MENTIONS]->(people) WHERE people.name = \$param_people_name_0 }), false) AS _met")
        assertContains(patch.statement, "SET claim.note = \$_v0)")
    }

    @Test
    fun `the rows of a patch are sent in the order given, each under its own index and offered a stamp of its own`() {
        val rows = listOf("c", "a", "b").mapIndexed { index, id -> PatchRow(index, id, mapOf("note" to "n$index")) }
        val spec = backend().patchAll(Claim::class.java, listOf("note"), rows, clears = false).single()

        @Suppress("UNCHECKED_CAST")
        val sent = spec.parameters["rows"] as List<Map<String, Any?>>
        assertEquals(listOf("c", "a", "b"), sent.map { it["id"] })
        assertEquals(listOf("0", "1", "2"), sent.map { it["i"] })
        assertEquals(listOf("n0", "n1", "n2"), sent.map { (it["v"] as Map<*, *>)["note"] })
        assertEquals(3, sent.map { it["stamp"] as String }.onEach { assertTrue(Stamps.isTwoTokens(it), it) }.distinct().size)
        assertContains(spec.statement!!.text, "MATCH (n:Claim {id: row.id})")
    }

    @Test
    fun `a patch of more rows than a statement takes is a statement for each chunk, of one text`() {
        val rows = (1..2001).map { PatchRow(it, "c$it", mapOf("note" to "n")) }
        val specs = backend().patchAll(Claim::class.java, listOf("note"), rows, clears = false)

        assertEquals(listOf(1000, 1000, 1), specs.map { (it.parameters["rows"] as List<*>).size })
        assertEquals(1, specs.map { it.statement!!.text }.distinct().size)
    }

    @Test
    fun `rows that hold a vector go fewer to a statement`() {
        val rows = (1..201).map { PatchRow(it, "d$it", mapOf("title" to "t", "embedding" to listOf(1.0f))) }

        fun sizes(properties: List<String>) = backend().patchAll(DocNode::class.java, properties, rows, clears = false).map { (it.parameters["rows"] as List<*>).size }

        assertEquals(listOf(100, 100, 1), sizes(listOf("title", "embedding")))
        assertEquals(listOf(201), sizes(listOf("title")))
    }

    @Test
    fun `a batch of nothing is no statement`() {
        val batch = backend().saveTogether(emptyList(), NullPolicy.IGNORE)

        assertTrue(batch.unstamped.isEmpty() && batch.stamped.isEmpty() && batch.alone.isEmpty())
    }

    // ----- What it asks the store -----

    @Test
    fun `the keys a stored node has are read from the store by its labels and id, each as text`() {
        val store = RecordingStore(DatabaseType.NEO4J) { listOf(listOf("id", "metadata.a", 7)) }
        val keys = backend(store).storedKeys.of("Bagged:Extra", "id", "b1")

        assertEquals(setOf("id", "metadata.a", "7"), keys)
        assertEquals("MATCH (n:Bagged:Extra {id: \$id}) RETURN keys(n)", store.queried.single().statement)
        assertEquals(mapOf<String, Any?>("id" to "b1"), store.queried.single().parameters)
    }

    @Test
    fun `a node that is not there has no keys and owns no labels`() {
        val backend = backend(RecordingStore(DatabaseType.NEO4J))

        assertEquals(emptySet(), backend.storedKeys.of("Bagged", "id", "missing"))
        assertEquals(emptyList(), backend.storedKeys.ownedLabels("Thing", "id", "missing", "__drivine.labels"))
    }

    @Test
    fun `the labels a node's field owns are read from the property they are recorded under, quoted, and none recorded is none owned`() {
        val store = RecordingStore(DatabaseType.NEO4J) { listOf(listOf("Vip", "Hot")) }

        assertEquals(listOf("Vip", "Hot"), backend(store).storedKeys.ownedLabels("Thing", "id", "t1", "__drivine.labels"))
        assertEquals("MATCH (n:Thing {id: \$id}) RETURN coalesce(n.`__drivine.labels`, [])", store.queried.single().statement)
    }

    // ----- Relationships on their own -----

    @Test
    fun `the nodes related to a node are loaded as the target's fragment, from the match that finds each once`() {
        val related = backend().related(NodeRef(Claim::class.java, "c1"), "MENTIONS", Direction.OUTGOING, Human::class.java)

        assertTrue(related.statement.startsWith("MATCH (a:`Claim` {`id`: \$_fromId})-[:`MENTIONS`]->(n:`Human`)\nWITH DISTINCT n"), related.statement)
        assertContains(related.statement, "RETURN")
        assertEquals(mapOf<String, Any?>("_fromId" to "c1"), related.bindings)
    }

    @Test
    fun `a statement's own bindings give way to those it is run with`() {
        val statement = GraphStatement("RETURN \$a + \$b", mapOf("a" to 1, "b" to 2))

        assertEquals(mapOf<String, Any?>("a" to 1, "b" to 2), statement.spec().parameters)
        assertEquals(mapOf<String, Any?>("a" to 1, "b" to 9, "c" to 3), statement.spec(mapOf("b" to 9, "c" to 3)).parameters)
        assertEquals("RETURN \$a + \$b", statement.spec().statement!!.text)
    }
}
