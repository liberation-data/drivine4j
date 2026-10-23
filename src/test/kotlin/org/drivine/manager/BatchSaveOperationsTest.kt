package org.drivine.manager

import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps
import org.drivine.query.QuerySpecification
import org.drivine.query.ROW_CHANGES_NODE
import org.drivine.query.Stamping
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.grammar.CypherGrammar
import org.drivine.session.SessionManager
import org.junit.jupiter.api.Test
import sample.graphproperty.ChunkNode
import sample.graphproperty.WidgetNode
import sample.nullpolicy.VecSaveNode
import sample.propertybag.BaggedNode
import sample.stateless.Claim
import sample.stateless.Human
import sample.stateless.Memo
import sample.stateless.MemoView
import sample.stateless.Tagged
import sample.stateless.Thread
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The statements a `saveAll` is made of: which roots go as the rows of an `UNWIND`, what a row holds, and what follows it. */
class BatchSaveOperationsTest {

    private val mapper = Neo4jObjectMapper.instance

    private fun operations(chunkSize: Int = 1000, grammar: CypherGrammar? = CypherDialect.NEO4J_5.grammar(), stamping: Stamping = Stamping(checked = false)) =
        BatchSaveOperations(mapper, SessionManager(mapper), chunkSize, grammar, StoredPropertyKeys { _, _, _ -> emptySet() }, stamping)

    private val QuerySpecification<*>.text: String get() = statement!!.text

    @Suppress("UNCHECKED_CAST")
    private val QuerySpecification<*>.rows: List<Map<String, Any?>> get() = parameters["rows"] as List<Map<String, Any?>>

    /** The rows without the stamp each is offered, which is new for every row. */
    private val QuerySpecification<*>.written: List<Map<String, Any?>> get() = rows.map { it - "stamp" }

    private fun QuerySpecification<*>.isUnwind(): Boolean = text.startsWith("UNWIND \$rows AS row")

    // ----- Batches that hand no stamp back -----

    @Test
    fun `fragments of one class are the rows of one UNWIND, each its id and its other properties`() {
        val specs = operations().buildBatchSpecs(listOf(Human("h1", "Ada"), Human("h2", "Grace")), CascadeType.NONE)

        val unwind = specs.single()
        assertTrue(unwind.text.startsWith("UNWIND \$rows AS row\nMERGE (n:Human {id: row.id})\n"), unwind.text)
        assertContains(unwind.text, "SET n += row.props, ")
        assertEquals(2, unwind.rows.map { it["stamp"] }.distinct().size, "a stamp of its own for each row")
        assertEquals(
            listOf(mapOf("id" to "h1", "props" to mapOf("name" to "Ada")), mapOf("id" to "h2", "props" to mapOf("name" to "Grace"))),
            unwind.written,
        )
    }

    @Test
    fun `the rows go in chunks, the last one short, and in the order of their ids whatever order the objects were given in`() {
        val humans = (1..5).map { Human("h$it", "n$it") }
        val specs = operations(chunkSize = 2).buildBatchSpecs(humans.reversed(), CascadeType.NONE)

        assertEquals(listOf(2, 2, 1), specs.map { it.rows.size })
        assertEquals(humans.map { it.id }, specs.flatMap { it.rows }.map { it["id"] })
        assertEquals(1, specs.map { it.text }.distinct().size, "one text for every chunk")
    }

    @Test
    fun `each class is a group of its own, merged on its own labels and the stored name of its id`() {
        val specs = operations().buildBatchSpecs(listOf(Human("h1", "Ada"), WidgetNode("w1", "Gear"), Human("h2", "Grace")), CascadeType.NONE)

        assertEquals(2, specs.size)
        assertContains(specs[0].text, "MERGE (n:Human {id: row.id})")
        assertEquals(listOf("h1", "h2"), specs[0].rows.map { it["id"] })
        assertContains(specs[1].text, "MERGE (n:Widget {widget_key: row.id})")
        assertEquals(listOf(mapOf("id" to "w1", "props" to mapOf("name" to "Gear"))), specs[1].written)
    }

    @Test
    fun `a null is left out of a row when nulls are ignored and kept when they clear, and what is not stored is never in it`() {
        val items = listOf(Claim("c1", "text", note = null), Thread("t1", "hello"))

        val ignoring = operations().buildBatchSpecs(items, CascadeType.NONE, NullPolicy.IGNORE).map { it.rows.single()["props"] }
        val clearing = operations().buildBatchSpecs(items, CascadeType.NONE, NullPolicy.CLEAR).map { it.rows.single()["props"] }

        // The stamp is never written from the object, whatever the policy; a computed property is not a field.
        assertEquals(listOf<Any?>(mapOf("text" to "text"), mapOf("title" to "hello")), ignoring)
        assertEquals(listOf<Any?>(mapOf("text" to "text", "note" to null), mapOf("title" to "hello")), clearing)
    }

    @Test
    fun `with fields named a row holds those alone, each named as declared and keyed as stored, for the class it is asked of`() {
        val items = listOf(ChunkNode("k1", "text", sequenceNumber = 1), Human("h1", "Ada"))
        val fields = mapOf<Class<*>, Set<String>?>(ChunkNode::class.java to setOf("sequenceNumber", "rootDocumentId"))

        val ignoring = operations().buildBatchSpecs(items, CascadeType.NONE, NullPolicy.IGNORE) { fields[it] }.map { it.written.single() }
        val clearing = operations().buildBatchSpecs(items, CascadeType.NONE, NullPolicy.CLEAR) { fields[it] }.map { it.written.single() }

        assertEquals(listOf(mapOf("id" to "k1", "props" to mapOf("sequence_number" to 1L)), mapOf("id" to "h1", "props" to mapOf("name" to "Ada"))), ignoring)
        // A null clears only where it is named: the section id, which is not, is no part of the row.
        assertEquals(mapOf<String, Any?>("sequence_number" to 1L, "root_document_id" to null), clearing.first()["props"])
    }

    @Test
    fun `with fields named a root saved by itself sets those alone`() {
        val items = listOf(BaggedNode("b1", "one", mapOf("k" to "v")))

        val whole = operations().buildBatchSpecs(items, CascadeType.NONE).single()
        val titled = operations().buildBatchSpecs(items, CascadeType.NONE) { setOf("title") }.single()
        val bagged = operations().buildBatchSpecs(items, CascadeType.NONE) { setOf("metadata") }.single()

        assertContains(whole.text, "metadata.k")
        assertContains(whole.text, "title")
        assertContains(titled.text, "title")
        assertFalse("metadata" in titled.text, "the bag is not written: ${titled.text}")
        assertContains(bagged.text, "metadata.k")
        assertFalse("title" in bagged.text, "the title is not written: ${bagged.text}")
    }

    @Test
    fun `a root an UNWIND cannot write is saved a statement to an object, among roots that go as rows`() {
        val specs = operations().buildBatchSpecs(
            listOf(BaggedNode("b1", "one", mapOf("k" to "v")), BaggedNode("b2", "two"), Human("h1", "Ada")),
            CascadeType.NONE,
        )

        val (bagged, plain) = specs.partition { !it.isUnwind() }
        assertEquals(2, bagged.size, "a statement for each bagged node")
        bagged.forEach { assertContains(it.text, "Bagged") }
        assertEquals(listOf("h1"), plain.single().rows.map { it["id"] })
    }

    @Test
    fun `a root with a vector goes as a row where the engine takes a vector as it is, and by itself where the engine wraps one`() {
        val items = listOf(VecSaveNode("v1", "t", listOf(0.1f, 0.2f)))

        assertTrue(operations(grammar = CypherDialect.NEO4J_5.grammar()).buildBatchSpecs(items, CascadeType.NONE).single().isUnwind())
        assertFalse(operations(grammar = CypherDialect.FALKORDB.grammar()).buildBatchSpecs(items, CascadeType.NONE).any { it.isUnwind() })
    }

    @Test
    fun `the roots of views are upserted by one UNWIND, ahead of the statements that relate them`() {
        val views = listOf(
            MemoView(Memo("m1", "first"), listOf(Human("h1", "Ada"))),
            MemoView(Memo("m2", "second"), listOf(Human("h2", "Grace"), Human("h3", "Edsger"))),
        )
        val specs = operations().buildBatchSpecs(views, CascadeType.NONE)

        assertTrue(specs.first().text.startsWith("UNWIND \$rows AS row\nMERGE (n:Memo {id: row.id})\n"), specs.first().text)
        assertEquals(
            listOf(mapOf("id" to "m1", "props" to mapOf("text" to "first")), mapOf("id" to "m2", "props" to mapOf("text" to "second"))),
            specs.first().written,
        )
        val rest = specs.drop(1)
        assertTrue(rest.isNotEmpty() && rest.none { it.isUnwind() })
        assertTrue(rest.all { "MENTIONS" in it.text || "Human" in it.text }, "what follows writes the people and the relationships to them")
    }

    @Test
    fun `an object whose id is null is refused by its class, and nothing is built for the batch`() {
        // A Kotlin id cannot be null; a row read from elsewhere can be given one.
        val nameless = mapper.convertValue(mapOf("id" to null, "title" to "t"), NullableKey::class.java)

        val refused = assertFailsWith<IllegalArgumentException> { operations().buildBatchSpecs(listOf(NullableKey("k1", "ok"), nameless), CascadeType.NONE) }
        assertEquals("Cannot saveAll NullableKey with a null @GraphNodeId", refused.message)
    }

    // ----- Stamped batches -----

    @Test
    fun `a stamped row holds the named fields alone`() {
        val items = listOf(Claim("c1", "one", "first"), Claim("c2", "two")).withIndex().toList()

        val spec = operations(stamping = Stamping(checked = false, handsBack = true)).buildStampedSpecs(items, NullPolicy.CLEAR) { setOf("note") }.single()

        assertEquals(listOf<Any?>(mapOf("note" to "first"), mapOf("note" to null)), spec.rows.map { it["props"] })
    }

    @Test
    fun `stamped rows of more than one class each hold the fields named for their own class`() {
        val items = listOf(Claim("c1", "one", "first"), Tagged("t1", "kept"), Claim("c2", "two", "second")).withIndex().toList()
        val fields = mapOf<Class<*>, Set<String>?>(Claim::class.java to setOf("note"), Tagged::class.java to setOf("text"))

        val specs = operations(stamping = Stamping(checked = false, handsBack = true)).buildStampedSpecs(items, NullPolicy.IGNORE) { fields[it] }

        assertEquals(
            listOf(listOf<Any?>(mapOf("note" to "first"), mapOf("note" to "second")), listOf<Any?>(mapOf("text" to "kept"))),
            specs.map { spec -> spec.rows.map { it["props"] } },
        )
    }

    @Test
    fun `a stamped batch offers each row a stamp of its own, taken only when the row changes the node`() {
        val spec = operations(stamping = Stamping(checked = false)).buildBatchSpecs(listOf(Claim("c1", "a"), Claim("c2", "b")), CascadeType.NONE).single()

        assertEquals(
            listOf(
                "UNWIND \$rows AS row",
                "MERGE (n:Claim {id: row.id})",
                Stamps.lock("n"),
                "WITH n, row, coalesce(n.${Stamps.QUOTED}, '') AS ${Stamps.FOUND}, $ROW_CHANGES_NODE AS changed",
                "SET n += row.props, ${Stamps.restamp("n", "changed", "row.stamp")}",
            ).joinToString("\n"),
            spec.text,
        )
        val stamps = spec.rows.map { it["stamp"] as String }
        assertTrue(stamps.all(Stamps::isTwoTokens))
        assertEquals(2, stamps.distinct().size)
        assertEquals(setOf("id", "props", "stamp"), spec.rows.first().keys)
    }

    @Test
    fun `the statements that hand stamps back tell a node they made from one they found, and return a row for each item under its index`() {
        val items = listOf(IndexedValue(4, Claim("c1", "a")), IndexedValue(9, Claim("c2", "b")))
        val spec = operations(stamping = Stamping(checked = false, handsBack = true)).buildStampedSpecs(items, NullPolicy.IGNORE).single()
        val lines = spec.text.lines()

        assertEquals("MERGE (n:Claim {id: row.id})", lines[1])
        assertEquals(Stamps.onCreate("n"), lines[2])
        assertEquals(Stamps.lock("n"), lines[3])
        assertContains(spec.text, "${Stamps.foundOf("n")} AS ${Stamps.FOUND}")
        assertContains(spec.text, "\nREMOVE ${Stamps.unmark("n")}\n")
        assertEquals("RETURN row.i + '/' + ${Stamps.FOUND} + '=' + n.${Stamps.QUOTED}", lines.last())
        // The index is text, as the row it comes back in is.
        assertEquals(listOf("4", "9"), spec.rows.map { it["i"] })
        assertEquals(listOf("c1", "c2"), spec.rows.map { it["id"] })
    }

    @Test
    fun `stamped fragments of several classes are a statement to a class, each chunked, every item still under the index it was given`() {
        val items = listOf(Claim("c1", "a"), Human("h1", "Ada"), Claim("c2", "b"), Claim("c3", "c")).mapIndexed { i, it -> IndexedValue(i, it) }
        val specs = operations(chunkSize = 2, stamping = Stamping(checked = false, handsBack = true)).buildStampedSpecs(items, NullPolicy.IGNORE)

        assertEquals(3, specs.size)
        assertEquals(listOf(listOf("0", "2"), listOf("3"), listOf("1")), specs.map { spec -> spec.rows.map { it["i"] } })
        assertContains(specs[2].text, "MERGE (n:Human {id: row.id})")
        assertEquals(listOf("0", "1", "2", "3"), specs.flatMap { spec -> spec.rows.map { it["i"] as String } }.sorted())
    }

    @Test
    fun `stamped fragments go class by class in the order of their names, and each class's rows in the order of their ids, chunk after chunk`() {
        val items = listOf(Human("h2", "Grace"), Claim("c3", "c"), Human("h1", "Ada"), Claim("c1", "a"), Claim("c2", "b")).withIndex().toList()

        val specs = operations(chunkSize = 2, stamping = Stamping(checked = false, handsBack = true)).buildStampedSpecs(items, NullPolicy.IGNORE)

        assertEquals(listOf(listOf("c1", "c2"), listOf("c3"), listOf("h1", "h2")), specs.map { spec -> spec.rows.map { it["id"] } })
        assertEquals(listOf(listOf("3", "4"), listOf("1"), listOf("2", "0")), specs.map { spec -> spec.rows.map { it["i"] } })
    }

    @Test
    fun `no stamped fragments is no statement`() {
        assertTrue(operations(stamping = Stamping(checked = false, handsBack = true)).buildStampedSpecs(emptyList(), NullPolicy.IGNORE).isEmpty())
    }
}

/** A node whose id a mapper can leave null. */
@org.drivine.annotation.NodeFragment(labels = ["NullableKey"])
data class NullableKey(@org.drivine.annotation.NodeId val id: String?, val title: String)
