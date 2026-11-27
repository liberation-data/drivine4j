package org.drivine.schema

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphProperty
import org.drivine.annotation.GraphRelationship
import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.annotation.NodeId
import org.drivine.annotation.RelationshipFragment
import org.drivine.annotation.Root
import org.drivine.connection.Connection
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseType
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.NodeRef
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.model.FragmentModel
import org.drivine.session.SessionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals

@NodeFragment(labels = ["Order", "Purchase"])
data class OrderFixture(
    @NodeId
    val id: String,
)

@NodeFragment(labels = ["Invoice"])
data class InvoiceFixture(
    @NodeId
    @GraphProperty("invoice_no")
    val number: String,
)

@NodeFragment(labels = ["Note"])
data class NoteWithoutIdFixture(
    val text: String,
)

@GraphView
data class OrderInvoicesFixture(
    @Root val order: OrderFixture,
    @GraphRelationship(type = "BILLED_AS", direction = Direction.OUTGOING)
    val invoices: List<InvoiceFixture> = emptyList(),
)

@NodeFragment(labels = ["Customer"])
data class CustomerFixture(
    @NodeId
    val id: String,
)

/** A view nested in itself. */
@GraphView
data class OrderChainFixture(
    @Root val order: OrderFixture,
    @GraphRelationship(type = "FOLLOWS", direction = Direction.OUTGOING)
    val previous: OrderChainFixture? = null,
)

@RelationshipFragment
data class BilledFixture(
    val on: String,
    val invoice: InvoiceFixture,
)

/** A view whose relationships reach a nested view, nested in itself, and a relationship fragment's target. */
@GraphView
data class CustomerOrdersFixture(
    @Root val customer: CustomerFixture,
    @GraphRelationship(type = "PLACED", direction = Direction.OUTGOING)
    val orders: List<OrderChainFixture> = emptyList(),
    @GraphRelationship(type = "BILLED", direction = Direction.OUTGOING)
    val billed: List<BilledFixture> = emptyList(),
)

/** The warning of a fragment whose `@NodeId` no index serves (#828): once per fragment and database, and never in a save. */
class NodeIdIndexCheckTest {

    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(NodeIdIndexCheck::class.java) as ch.qos.logback.classic.Logger

    @BeforeEach
    fun start() {
        NodeIdIndexCheck.reset()
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun stop() {
        logger.detachAppender(appender)
        NodeIdIndexCheck.reset()
    }

    private fun warnings() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    private fun range(label: String, vararg properties: String) = SchemaItemInfo(SchemaItemKind.RANGE_INDEX, label, properties.toList())

    private fun unique(label: String, vararg properties: String) = SchemaItemInfo(SchemaItemKind.UNIQUENESS_CONSTRAINT, label, properties.toList())

    private val orderWarning =
        "@NodeId 'id' of OrderFixture has no index under :Order, so each save scans every :Order node. Its default index is off " +
            "(nodeIdIndex = false / drivine.schema.index-node-ids=false, or OrderFixture isn't in a SchemaCatalog): declare @RangeIndex or @Unique on it, " +
            "or indexes.ensure(RangeIndexSpec(\"Order\", listOf(\"id\")))."

    /** The words a warning ends with where there is more than one database: the one it is said of (#848). */
    private fun on(database: String) = " On database '$database'."

    @Test
    fun `a fragment some catalog names is checked only on the databases whose catalogs name it, and the database is named`() {
        NodeIdIndexCheck.managed()
        FragmentModel.from(OrderFixture::class.java)
        FragmentModel.from(InvoiceFixture::class.java)
        // Order's catalog is for a alone, Invoice's for b alone; neither has its id indexed.
        NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Order"), "b" to setOf("Invoice")))
        NodeIdIndexCheck.listed("a", emptyList())
        NodeIdIndexCheck.listed("b", emptyList())
        assertEquals(listOf(orderWarning + on("a")), warnings().filter { it.contains("OrderFixture") })
        assertEquals(1, warnings().count { it.contains("InvoiceFixture") && it.endsWith(on("b")) })
        assertEquals(2, warnings().size, "neither is warned of on the other's database")
    }

    @Test
    fun `a fragment first used after the enforce is checked only where it belongs, and where a later ensure names it`() {
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Purchase"), "b" to setOf("Invoice")))
        NodeIdIndexCheck.listed("a", emptyList())
        NodeIdIndexCheck.listed("b", emptyList())
        FragmentModel.from(OrderFixture::class.java)
        assertEquals(listOf(orderWarning + on("a")), warnings(), "named on a under its other label, Purchase")
        // A catalog ensured on b at runtime names Order there, and b is listed again.
        NodeIdIndexCheck.catalogued("b", listOf("Order"))
        NodeIdIndexCheck.listed("b", emptyList())
        assertEquals(listOf(orderWarning + on("a"), orderWarning + on("b")), warnings())
    }

    @Test
    fun `a fragment no catalog names is checked on every database listed`() {
        NodeIdIndexCheck.managed()
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Customer"), "b" to emptySet()))
        NodeIdIndexCheck.listed("a", emptyList())
        NodeIdIndexCheck.listed("b", emptyList())
        assertEquals(listOf(orderWarning + on("a"), orderWarning + on("b")), warnings())
    }

    @Test
    fun `a fragment no index serves is warned of once per database, in the issue's words`() {
        NodeIdIndexCheck.managed()
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.listed("default", emptyList())
        NodeIdIndexCheck.listed("default", emptyList())
        FragmentModel.from(OrderFixture::class.java)
        assertEquals(listOf(orderWarning), warnings())
        NodeIdIndexCheck.listed("users", listOf(range("Customer", "id")))
        assertEquals(listOf(orderWarning, orderWarning + on("users")), warnings(), "another database, whose index is under a label the fragment hasn't")
        // An index under any of the fragment's labels serves it: a save matches all of them.
        NodeIdIndexCheck.listed("archive", listOf(range("Purchase", "id")))
        assertEquals(2, warnings().size)
    }

    @Test
    fun `a range index or a uniqueness constraint over the id alone, under one of its labels, serves it`() {
        NodeIdIndexCheck.managed()
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.listed("a", listOf(range("Order", "id")))
        NodeIdIndexCheck.listed("b", listOf(unique("Order", "id")))
        assertEquals(emptyList(), warnings())
        NodeIdIndexCheck.listed("c", listOf(range("Order", "id", "placedAt"), unique("Order", "id", "line")))
        assertEquals(listOf(orderWarning + on("c")), warnings(), "an item over more properties serves no lookup by the id alone")
    }

    @Test
    fun `an item serves the id where the store takes it for one over the id alone, as FalkorDB lists a label's range properties`() {
        NodeIdIndexCheck.managed()
        FragmentModel.from(OrderFixture::class.java)
        // FalkorDB: one item of every range property of the label, each its own index.
        val falkor = { item: SchemaItemInfo, spec: SchemaItemSpec -> item.label == spec.label && item.properties.containsAll(spec.properties) }
        NodeIdIndexCheck.listed("falkor", listOf(range("Order", "placedAt", "id")), falkor)
        assertEquals(emptyList(), warnings())
        NodeIdIndexCheck.listed("neo", listOf(range("Order", "placedAt", "id")))
        assertEquals(listOf(orderWarning + on("neo")), warnings(), "a composite index elsewhere")
    }

    @Test
    fun `a fragment first used after an enforce listed is checked against it, by the property its id is stored under`() {
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.listed("default", listOf(range("Invoice", "number")))
        FragmentModel.from(InvoiceFixture::class.java)
        assertEquals(1, warnings().size)
        assertEquals(true, warnings().single().startsWith("@NodeId 'invoice_no' of InvoiceFixture has no index under :Invoice"))
        NodeIdIndexCheck.reset()
        appender.list.clear()
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.listed("default", listOf(range("Invoice", "invoice_no")))
        FragmentModel.from(InvoiceFixture::class.java)
        assertEquals(emptyList(), warnings())
    }

    @Test
    fun `an index or a constraint ensured after an enforce listed serves a fragment first used later`() {
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.listed("default", emptyList())
        NodeIdIndexCheck.ensured("default", RangeIndexSpec("Order", listOf("id")), EnsureResult.Created(range("Order", "id")))
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.ensured("default", UniquenessConstraintSpec("Invoice", listOf("invoice_no")), EnsureResult.AlreadyMatching(unique("Invoice", "invoice_no")))
        FragmentModel.from(InvoiceFixture::class.java)
        assertEquals(emptyList(), warnings())

        // A recreate that ends a drift, as IndexManager.recreate and ConstraintManager.recreate report it.
        NodeIdIndexCheck.reset()
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.listed("default", listOf(unique("Order", "id", "line")))
        NodeIdIndexCheck.ensured("default", UniquenessConstraintSpec("Order", listOf("id")), EnsureResult.Recreated(unique("Order", "id", "line"), unique("Order", "id")))
        FragmentModel.from(OrderFixture::class.java)
        assertEquals(emptyList(), warnings(), "a recreate that ends a drift serves it")

        NodeIdIndexCheck.reset()
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.listed("default", emptyList())
        NodeIdIndexCheck.ensured("default", RangeIndexSpec("Order", listOf("id"), ignoreCase = true), EnsureResult.Created(range("Order", "id")))
        NodeIdIndexCheck.ensured("default", RangeIndexSpec("Order", listOf("id", "placedAt")), EnsureResult.Created(range("Order", "id", "placedAt")))
        NodeIdIndexCheck.ensured("default", UniquenessConstraintSpec("Order", listOf("id")), EnsureResult.Drift(unique("Order", "id", "line"), UniquenessConstraintSpec("Order", listOf("id"))))
        NodeIdIndexCheck.ensured("other", RangeIndexSpec("Order", listOf("id")), EnsureResult.Created(range("Order", "id")))
        FragmentModel.from(OrderFixture::class.java)
        assertEquals(listOf(orderWarning), warnings(), "one that ignores case, over more properties, not made, or on a database no enforce listed")
    }

    /**
     * A manager asked for `default` is of the first registered database, which [SchemaManager] lists by its registered
     * name: an index ensured through it serves a fragment there (#886).
     */
    @Test
    fun `an index ensured through the default manager serves a fragment on the database it is registered as`() {
        val provider = object : ConnectionProvider {
            override val name = "neo"
            override val type = DatabaseType.NEO4J
            override val subtypeRegistry = null
            override fun connect(): Connection = throw UnsupportedOperationException()
            override fun end() = Unit
        }
        val manager = NonTransactionalPersistenceManager(provider, "default", DatabaseType.NEO4J, SubtypeRegistry())
        assertEquals("neo", manager.indexes.database)
        assertEquals(null, IndexManager(SchemaExecutor(provider), provider.schemaGrammar).database, "a manager with no owner is of no database")

        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.listed("neo", emptyList())
        NodeIdIndexCheck.ensured(manager.indexes.database, RangeIndexSpec("Order", listOf("id")), EnsureResult.Created(range("Order", "id")))
        FragmentModel.from(OrderFixture::class.java)
        assertEquals(emptyList(), warnings())
    }

    /**
     * With no [SchemaManager], the index or constraint the warning names, ensured by hand, serves the fragment, on whichever
     * database: the warning 30 s after its first use isn't given (#886). One that doesn't serve it leaves the warning.
     */
    @Test
    fun `with no schema manager an index or a constraint ensured by hand that serves the id silences the later warning`() {
        NodeIdIndexCheck.ensured("neo", RangeIndexSpec("Order", listOf("id")), EnsureResult.Created(range("Order", "id")))
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.unmanaged(OrderFixture::class.java)
        // Ensured after the fragment's first use, before the warning is due: the order an application's startup gives.
        FragmentModel.from(InvoiceFixture::class.java)
        NodeIdIndexCheck.ensured("neo", UniquenessConstraintSpec("Invoice", listOf("invoice_no")), EnsureResult.AlreadyMatching(unique("Invoice", "invoice_no")))
        NodeIdIndexCheck.unmanaged(InvoiceFixture::class.java)
        assertEquals(emptyList(), warnings())

        // Under another of the fragment's labels serves it too: a save matches every label.
        NodeIdIndexCheck.reset()
        NodeIdIndexCheck.ensured("neo", RangeIndexSpec("Purchase", listOf("id")), EnsureResult.Created(range("Purchase", "id")))
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.unmanaged(OrderFixture::class.java)
        assertEquals(emptyList(), warnings(), "an index under another of its labels")

        NodeIdIndexCheck.reset()
        NodeIdIndexCheck.ensured("neo", RangeIndexSpec("Order", listOf("id"), ignoreCase = true), EnsureResult.Created(range("Order", "id")))
        NodeIdIndexCheck.ensured("neo", RangeIndexSpec("Order", listOf("id", "placedAt")), EnsureResult.Created(range("Order", "id", "placedAt")))
        NodeIdIndexCheck.ensured("neo", UniquenessConstraintSpec("Order", listOf("id")), EnsureResult.Drift(unique("Order", "id", "line"), UniquenessConstraintSpec("Order", listOf("id"))))
        NodeIdIndexCheck.ensured(null, RangeIndexSpec("Order", listOf("id")), EnsureResult.Created(range("Order", "id")))
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.unmanaged(OrderFixture::class.java)
        assertEquals(listOf(orderWarning), warnings(), "one that ignores case, over more properties, not made, or of no database")

        // Forgotten by a reset, as everything else is.
        NodeIdIndexCheck.reset()
        appender.list.clear()
        NodeIdIndexCheck.ensured("neo", RangeIndexSpec("Order", listOf("id")), EnsureResult.Created(range("Order", "id")))
        NodeIdIndexCheck.reset()
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.unmanaged(OrderFixture::class.java)
        assertEquals(listOf(orderWarning), warnings(), "after a reset")
    }

    /**
     * What was ensured by hand before any enforce is not taken into what an enforce lists of the database later: that
     * listing, read of the store, is what serves an id there from then on (#886).
     */
    @Test
    fun `an index ensured by hand before an enforce gives way to what the enforce lists`() {
        NodeIdIndexCheck.ensured("neo", RangeIndexSpec("Order", listOf("id")), EnsureResult.Created(range("Order", "id")))
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.listed("neo", emptyList())
        assertEquals(listOf(orderWarning), warnings(), "the store, as listed, has no index serving it")

        NodeIdIndexCheck.reset()
        appender.list.clear()
        NodeIdIndexCheck.ensured("neo", RangeIndexSpec("Order", listOf("id")), EnsureResult.Created(range("Order", "id")))
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.listed("neo", listOf(range("Order", "id")))
        assertEquals(emptyList(), warnings(), "listed by the enforce")
    }

    @Test
    fun `with no schema manager a fragment is warned of later, and not where one was built by then`() {
        FragmentModel.from(OrderFixture::class.java)
        assertEquals(emptyList(), warnings(), "not at its first use: a manager may yet be built")
        NodeIdIndexCheck.unmanaged(OrderFixture::class.java)
        assertEquals(listOf(orderWarning), warnings())

        NodeIdIndexCheck.reset()
        appender.list.clear()
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.unmanaged(OrderFixture::class.java)
        assertEquals(emptyList(), warnings())
    }

    @Test
    fun `a fragment with no @NodeId is never warned of`() {
        NodeIdIndexCheck.managed()
        FragmentModel.from(NoteWithoutIdFixture::class.java)
        NodeIdIndexCheck.listed("default", emptyList())
        assertEquals(emptyList(), warnings())
    }

    /** A fragment used on a database no catalog names it on is checked there all the same: its saves scan the label there (#858). */
    @Test
    fun `a fragment used on a database is checked there, whether it was used before the listing or after it`() {
        NodeIdIndexCheck.managed()
        // Order is catalogued for a alone, but is used on b, and later on c.
        NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Order"), "b" to emptySet(), "c" to emptySet()))
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.used("b", OrderFixture::class.java)
        NodeIdIndexCheck.listed("a", emptyList())
        NodeIdIndexCheck.listed("b", emptyList())
        NodeIdIndexCheck.listed("c", emptyList())
        assertEquals(listOf(orderWarning + on("a"), orderWarning + on("b")), warnings(), "not on c, where it isn't used yet")
        NodeIdIndexCheck.used("c", OrderFixture::class.java)
        NodeIdIndexCheck.used("c", OrderFixture::class.java)
        NodeIdIndexCheck.used("b", OrderFixture::class.java)
        assertEquals(listOf(orderWarning + on("a"), orderWarning + on("b"), orderWarning + on("c")), warnings(), "once per database")
    }

    @Test
    fun `a fragment used on a database where an index serves its id is not warned of there`() {
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Order"), "b" to emptySet()))
        NodeIdIndexCheck.listed("b", listOf(range("Purchase", "id")))
        NodeIdIndexCheck.used("b", OrderFixture::class.java)
        NodeIdIndexCheck.used("unlisted", OrderFixture::class.java)
        assertEquals(emptyList(), warnings(), "served under its other label on b; nothing is known of a database no enforce listed")
    }

    @Test
    fun `a view used on a database has its root and the fragments of its relationships checked there`() {
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Order", "Invoice"), "b" to emptySet()))
        NodeIdIndexCheck.listed("b", emptyList())
        NodeIdIndexCheck.used("b", OrderInvoicesFixture::class.java)
        assertEquals(listOf(orderWarning + on("b")), warnings().filter { it.contains("OrderFixture") })
        assertEquals(1, warnings().count { it.contains("InvoiceFixture") && it.endsWith(on("b")) })
    }

    @Test
    fun `a view's nested views, one nested in itself, and a relationship fragment's target are checked where the view is used`() {
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Order", "Invoice", "Customer"), "b" to emptySet()))
        NodeIdIndexCheck.listed("b", emptyList())
        NodeIdIndexCheck.used("b", CustomerOrdersFixture::class.java)
        assertEquals(
            listOf(1, 1, 1),
            listOf("CustomerFixture", "OrderFixture", "InvoiceFixture").map { fragment -> warnings().count { it.contains("of $fragment ") && it.endsWith(on("b")) } },
            "${warnings()}",
        )
    }

    @Test
    fun `a reset forgets where a fragment was used`() {
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Order"), "b" to emptySet()))
        NodeIdIndexCheck.used("b", OrderFixture::class.java)
        NodeIdIndexCheck.reset()
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Order"), "b" to emptySet()))
        FragmentModel.from(OrderFixture::class.java)
        NodeIdIndexCheck.listed("b", emptyList())
        assertEquals(emptyList(), warnings())
        NodeIdIndexCheck.used("b", OrderFixture::class.java)
        assertEquals(listOf(orderWarning + on("b")), warnings(), "used again after the reset, it is checked again")
    }

    /** A manager's operation tells the check its database, by the name it is registered under (#858, #886). */
    @Test
    fun `a load through the default manager is a use on the database it is registered as`() {
        val provider = object : ConnectionProvider {
            override val name = "neo"
            override val type = DatabaseType.NEO4J
            override val subtypeRegistry = null
            override fun connect(): Connection = throw UnsupportedOperationException("no store in this test")
            override fun end() = Unit
        }
        val objects = StatelessGraphObjectManager(
            NonTransactionalPersistenceManager(provider, "default", DatabaseType.NEO4J, SubtypeRegistry()),
            Neo4jObjectMapper.instance,
            SubtypeRegistry(),
        )
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Order"), "neo" to emptySet()))
        NodeIdIndexCheck.listed("neo", emptyList())
        runCatching { objects.load("o1", OrderFixture::class.java) }
        assertEquals(listOf(orderWarning + on("neo")), warnings())
    }

    /** Each operation of a manager, and of a stateless one beside it, tells the check of the class it uses (#858). */
    @Test
    fun `each operation of a manager is a use on its database`() {
        val provider = object : ConnectionProvider {
            override val name = "neo"
            override val type = DatabaseType.NEO4J
            override val subtypeRegistry = null
            override fun connect(): Connection = throw UnsupportedOperationException("no store in this test")
            override fun end() = Unit
        }
        val persistence = NonTransactionalPersistenceManager(provider, "default", DatabaseType.NEO4J, SubtypeRegistry())
        val managed = GraphObjectManager(persistence, SessionManager(Neo4jObjectMapper.instance), Neo4jObjectMapper.instance, SubtypeRegistry())
        val stateless = StatelessGraphObjectManager(persistence, Neo4jObjectMapper.instance, SubtypeRegistry())
        val order = OrderFixture("o1")
        val operations: List<Pair<String, () -> Any?>> = listOf(
            "loadAll" to { managed.loadAll(OrderFixture::class.java) },
            "loadAll where" to { managed.loadAll(OrderFixture::class.java, "true") },
            "count" to { managed.count(OrderFixture::class.java) },
            "count where" to { managed.count(OrderFixture::class.java, "true") },
            "load" to { managed.load("o1", OrderFixture::class.java) },
            "delete" to { managed.delete("o1", OrderFixture::class.java) },
            "deleteAll" to { managed.deleteAll(OrderFixture::class.java) },
            "save" to { managed.save(order) },
            "saveAll" to { managed.saveAll(listOf(order)) },
            "stateless save" to { stateless.save(order) },
            "stateless create" to { stateless.create(order) },
            "stateless saveAll" to { stateless.saveAll(listOf(order)) },
            "stateless patchAll" to { stateless.patchAllFields(listOf(order), only = setOf("id")) },
            "stateless patch" to { stateless.patch("o1", OrderFixture::class.java, Unit) {} },
        )
        val missed = operations.filter { (_, operation) ->
            NodeIdIndexCheck.reset()
            appender.list.clear()
            NodeIdIndexCheck.managed()
            NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Order"), "neo" to emptySet()))
            NodeIdIndexCheck.listed("neo", emptyList())
            runCatching { operation() }
            warnings() != listOf(orderWarning + on("neo"))
        }.map { it.first }
        assertEquals(emptyList(), missed, "operations that told the check nothing")
    }

    /** A relationship between nodes matched by id, and a grouped read, use the fragments they name (#858). */
    @Test
    fun `an edge's nodes and a grouped read are uses on the database they run on`() {
        val provider = object : ConnectionProvider {
            override val name = "neo"
            override val type = DatabaseType.NEO4J
            override val subtypeRegistry = null
            override fun connect(): Connection = throw UnsupportedOperationException("no store in this test")
            override fun end() = Unit
        }
        val objects = StatelessGraphObjectManager(
            NonTransactionalPersistenceManager(provider, "default", DatabaseType.NEO4J, SubtypeRegistry()),
            Neo4jObjectMapper.instance,
            SubtypeRegistry(),
        )
        NodeIdIndexCheck.managed()
        NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Order", "Invoice", "Customer"), "neo" to emptySet()))
        NodeIdIndexCheck.listed("neo", emptyList())
        runCatching { objects.edges.relate(NodeRef(OrderFixture::class.java, "o1"), NodeRef(InvoiceFixture::class.java, "i1"), "BILLED_AS") }
        runCatching { objects.groups.countBy(CustomerFixture::class.java, "id") }
        assertEquals(
            listOf(1, 1, 1),
            listOf("OrderFixture", "InvoiceFixture", "CustomerFixture").map { fragment -> warnings().count { it.contains("of $fragment ") && it.endsWith(on("neo")) } },
            "${warnings()}",
        )
    }

    /** Each edge operation tells the check of the nodes it names, and a related read of its target too (#858). */
    @Test
    fun `each edge operation is a use of the fragments it names`() {
        val provider = object : ConnectionProvider {
            override val name = "neo"
            override val type = DatabaseType.NEO4J
            override val subtypeRegistry = null
            override fun connect(): Connection = throw UnsupportedOperationException("no store in this test")
            override fun end() = Unit
        }
        val objects = StatelessGraphObjectManager(
            NonTransactionalPersistenceManager(provider, "default", DatabaseType.NEO4J, SubtypeRegistry()),
            Neo4jObjectMapper.instance,
            SubtypeRegistry(),
        )
        val order = NodeRef(OrderFixture::class.java, "o1")
        val invoice = NodeRef(InvoiceFixture::class.java, "i1")
        val operations: List<Triple<String, List<String>, () -> Any?>> = listOf(
            Triple("unrelate", listOf("OrderFixture", "InvoiceFixture")) { objects.edges.unrelate(order, invoice, "BILLED_AS") },
            Triple("unrelateAll", listOf("OrderFixture")) { objects.edges.unrelateAll(order, "BILLED_AS") },
            Triple("loadRelated", listOf("OrderFixture", "InvoiceFixture")) {
                objects.edges.loadRelated(order, "BILLED_AS", Direction.OUTGOING, InvoiceFixture::class.java)
            },
        )
        val missed = operations.filter { (_, fragments, operation) ->
            NodeIdIndexCheck.reset()
            appender.list.clear()
            NodeIdIndexCheck.managed()
            NodeIdIndexCheck.catalogued(mapOf("a" to setOf("Order", "Invoice"), "neo" to emptySet()))
            NodeIdIndexCheck.listed("neo", emptyList())
            runCatching { operation() }
            warnings().map { warning -> fragments.firstOrNull { warning.contains("of $it ") && warning.endsWith(on("neo")) } } != fragments
        }.map { it.first }
        assertEquals(emptyList(), missed, "edge operations that told the check otherwise")
    }

}
