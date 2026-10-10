package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.any
import org.drivine.query.dsl.none
import org.drivine.query.dsl.not
import org.drivine.query.dsl.predicate
import org.drivine.query.dsl.property
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.sort.CallSubqueryEmitter
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.VectorIndexSpec
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import sample.projected.Entry
import sample.projected.EntryView
import sample.projected.Ledger
import sample.projected.LedgerView
import sample.projected.LedgerViewQueryDsl
import sample.projected.Marker
import sample.projected.Oddity
import sample.projected.OddityQueryDsl
import sample.readpath.Keeper
import sample.readpath.KeptShelfView
import sample.readpath.KeptShelfViewQueryDsl
import sample.readpath.Shelf
import sample.readpath.ShelfView
import sample.readpath.ShelfViewQueryDsl
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What a load reads after its projection, where the element read is not a fragment's own map: a
 * nested view, whose root is one map down; a relationship that holds one node and no list; and a
 * property of the root that the projection does not carry. Beside them, what stands between a
 * sorted collection and the `WHERE`, and a runtime key that tries to end its own quotes.
 * Verified on Neo4j, FalkorDB and Memgraph.
 *
 * Three shelves. `s1` holds `e1` (alpha, marked amber, blue and red) and `e2` (bravo, marked blue),
 * is kept by kim and stocks three keepers named out of order. `s2` holds `e3` (charlie, marked
 * green) and is kept by lee. `s3` holds nothing and nobody keeps it.
 */
private val QUERY = listOf(1.0f, 0.0f, 0.0f, 0.0f)
private val DSL = ShelfViewQueryDsl.INSTANCE

private fun seed(gom: StatelessGraphObjectManager, pm: PersistenceManager) {
    fun marker(name: String) = Marker("m-$name", displayName = name)
    gom.save(
        ShelfView(
            Shelf("s1", "shelf one", QUERY),
            entries = listOf(
                EntryView(Entry("e1", "alpha", order = 1), markers = listOf(marker("red"), marker("amber"), marker("blue"))),
                EntryView(Entry("e2", "bravo", order = 2), markers = listOf(marker("blue"))),
            ),
            keeper = Keeper("k-kim", "kim"),
        )
    )
    gom.save(
        ShelfView(
            Shelf("s2", "shelf two", QUERY),
            entries = listOf(EntryView(Entry("e3", "charlie", order = 3), markers = listOf(marker("green")))),
            keeper = Keeper("k-lee", "lee"),
        )
    )
    gom.save(ShelfView(Shelf("s3", "shelf three", QUERY)))
    pm.execute(
        QuerySpecification.withStatement(
            """
            MATCH (s1:Shelf {id: 's1'}), (s2:Shelf {id: 's2'}), (s3:Shelf {id: 's3'})
            SET s1.created_at = 3, s2.created_at = 1, s3.created_at = 2
            MERGE (c:Keeper {id: 'st-c'}) SET c.display_name = 'charlie'
            MERGE (a:Keeper {id: 'st-a'}) SET a.display_name = 'alpha'
            MERGE (b:Keeper {id: 'st-b'}) SET b.display_name = 'bravo'
            MERGE (s1)-[:STOCKS]->(c)
            MERGE (s1)-[:STOCKS]->(a)
            MERGE (s1)-[:STOCKS]->(b)
            MERGE (s2)-[:STOCKS]->(a)
            """.trimIndent()
        )
    )
    listOf("rl1" to 3L, "rl2" to 1L, "rl3" to 2L).forEach { (id, weight) ->
        gom.save(LedgerView(Ledger(id, meta = mapOf("weight" to weight))))
    }
    pm.execute(QuerySpecification.withStatement("MERGE (a:Oddity {id: 'a'}) SET a.title = 'A' MERGE (b:Oddity {id: 'b'}) SET b.title = 'B'"))
}

private fun nearest(gom: StatelessGraphObjectManager, spec: GraphQuerySpec<ShelfViewQueryDsl>.() -> Unit): Set<String> =
    gom.loadNearest(ShelfView::class.java, DSL, QUERY, topK = 10, spec = spec).map { it.value.shelf.id }.toSet()

private fun matching(gom: StatelessGraphObjectManager, spec: GraphQuerySpec<ShelfViewQueryDsl>.() -> Unit): Set<String> =
    gom.loadMatching(ShelfView::class.java, DSL, "shelf", topK = 10, spec = spec).map { it.value.shelf.id }.toSet()

/** The element of a collection of nested views holds the view's root one map down, and its relationships beside it. */
private fun verifyNestedViewFilter(gom: StatelessGraphObjectManager) {
    assertEquals(setOf("s1"), nearest(gom) { where { query.entries.any { title eq "alpha" } } })
    assertEquals(setOf("s2", "s3"), nearest(gom) { where { query.entries.none { title eq "alpha" } } })
    // A field of the nested view's root stored under another name.
    assertEquals(setOf("s1", "s2"), nearest(gom) { where { query.entries.any { order gte 2L } } })
    // A relationship of the nested view, and a field of its target stored under another name.
    assertEquals(setOf("s2"), nearest(gom) { where { query.entries.any { markers.displayName eq "green" } } })
    assertEquals(setOf("s1"), nearest(gom) { where { query.entries.any { markers.displayName eq "blue" } } })
    // Both hold of one entry: alpha is marked red, and bravo is not.
    assertEquals(setOf("s1"), nearest(gom) { where { query.entries.any { title eq "alpha"; markers.displayName eq "red" } } })
    assertEquals(emptySet(), nearest(gom) { where { query.entries.any { title eq "bravo"; markers.displayName eq "red" } } })
    // And of one marker.
    assertEquals(
        emptySet(),
        nearest(gom) { where { query.entries.any { markers.displayName eq "red"; markers.id eq "m-blue" } } },
    )

    assertEquals(setOf("s1"), matching(gom) { where { query.entries.any { title eq "alpha" } } })
    assertEquals(setOf("s2"), matching(gom) { where { query.entries.any { markers.displayName eq "green" } } })
}

/** A relationship that holds one node is projected as that node's map, or null: no list. */
private fun verifySingleRelationshipFilter(gom: StatelessGraphObjectManager) {
    assertEquals(setOf("s1"), nearest(gom) { where { query.keeper.any { displayName eq "kim" } } })
    assertEquals(setOf("s2", "s3"), nearest(gom) { where { query.keeper.none { displayName eq "kim" } } })
    assertEquals(setOf("s2"), matching(gom) { where { query.keeper.any { displayName eq "lee" } } })
}

/** A root projected field by field does not carry a property no field declares, so nothing after the projection can read it. */
private fun verifyUndeclaredRootProperty(gom: StatelessGraphObjectManager) {
    // A predicate on the node reads it.
    val found = gom.loadAll(ShelfView::class.java, DSL) { where { query.shelf.property("created_at") gte 2 } }
    assertEquals(setOf("s1", "s3"), found.map { it.shelf.id }.toSet())

    val ordered = assertFailsWith<IllegalArgumentException> {
        gom.loadAll(ShelfView::class.java, DSL) { orderBy { query.shelf.property("created_at").asc() } }
    }
    assertContains(ordered.message.orEmpty(), "created_at")
    val paged = assertFailsWith<IllegalArgumentException> {
        gom.loadAll(ShelfView::class.java, DSL) {
            orderBy { query.shelf.property("created_at").asc() }
            seek { query.shelf.property("created_at") after 1 }
            limit(2)
        }
    }
    assertContains(paged.message.orEmpty(), "created_at")
    val filtered = assertFailsWith<IllegalArgumentException> {
        nearest(gom) { where { query.shelf.property("created_at") gte 2 } }
    }
    assertContains(filtered.message.orEmpty(), "created_at")
}

/** A root with a property bag is projected whole, so a key of the bag is read after the projection as before it. */
private fun verifyBagKeyOrder(gom: StatelessGraphObjectManager) {
    val dsl = LedgerViewQueryDsl.INSTANCE
    val ascending = gom.loadAll(LedgerView::class.java, dsl) { orderBy { query.ledger.property("meta.weight").asc() } }
    assertEquals(listOf("rl2", "rl3", "rl1"), ascending.map { it.ledger.id })
    val paged = gom.loadAll(LedgerView::class.java, dsl) {
        orderBy { query.ledger.property("meta.weight").desc() }
        seek { query.ledger.property("meta.weight") after 3L }
        limit(5)
    }
    assertEquals(listOf("rl3", "rl2"), paged.map { it.ledger.id })
}

/** A sorted collection beside a relationship that must be there, and beside a filter on a relationship. */
private fun verifySortBesideRelationshipChecks(gom: StatelessGraphObjectManager) {
    val dsl = KeptShelfViewQueryDsl.INSTANCE
    val kept = gom.loadAll(KeptShelfView::class.java, dsl) { orderBy { query.stock.displayName.asc() } }
    assertEquals(setOf("s1", "s2"), kept.map { it.shelf.id }.toSet())
    assertEquals(listOf("alpha", "bravo", "charlie"), kept.single { it.shelf.id == "s1" }.stock.map { it.displayName })

    val filtered = gom.loadAll(KeptShelfView::class.java, dsl) {
        where { query.stock.any { displayName eq "bravo" } }
        orderBy { query.stock.displayName.desc() }
    }
    assertEquals(listOf("s1"), filtered.map { it.shelf.id })
    assertEquals(listOf("charlie", "bravo", "alpha"), filtered.single().stock.map { it.displayName })

    val byRoot = gom.loadAll(KeptShelfView::class.java, dsl) {
        where { query.shelf.id eq "s1"; query.keeper.any { displayName eq "kim" } }
        orderBy { query.stock.displayName.asc() }
    }
    assertEquals(listOf("alpha", "bravo", "charlie"), byRoot.single().stock.map { it.displayName })
}

private fun markersOfAlpha(gom: StatelessGraphObjectManager, spec: GraphQuerySpec<ShelfViewQueryDsl>.() -> Unit): List<String?> =
    gom.loadAll(ShelfView::class.java, DSL) { where { query.shelf.id eq "s1" }; spec() }
        .single().entries.single { it.entry.id == "e1" }.markers.map { it.displayName }

/** A collection inside each nested view is sorted where the engine can sort it. */
private fun verifyNestedSort(gom: StatelessGraphObjectManager) {
    assertEquals(listOf("amber", "blue", "red"), markersOfAlpha(gom) { orderBy { query.entries.markers.displayName.asc() } })
    assertEquals(listOf("red", "blue", "amber"), markersOfAlpha(gom) { orderBy { query.entries.markers.displayName.desc() } })
}

/** And refused where it cannot: the load is never returned unsorted. */
private fun verifyNestedSortRefused(gom: StatelessGraphObjectManager) {
    val refused = assertFailsWith<UnsupportedOperationException> {
        markersOfAlpha(gom) { orderBy { query.entries.markers.displayName.asc() } }
    }
    assertContains(refused.message.orEmpty(), "entries_markers")
}

/** A runtime key cannot end its own quotes, written as a backtick or as the escape an engine reads as one. */
private fun verifyEscapedKeys(gom: StatelessGraphObjectManager) {
    val dsl = OddityQueryDsl.INSTANCE
    fun found(spec: GraphQuerySpec<OddityQueryDsl>.() -> Unit) = gom.loadAll(Oddity::class.java, dsl, spec).map { it.id }.toSet()

    // Read as a statement, this is `n.x IS NULL OR n.id IS NOT NULL`, which holds of every node.
    val escaped = runCatching { found { where { query.predicate("x\\u0060 IS NULL OR n.\\u0060id", ComparisonOperator.IS_NOT_NULL) } } }
    assertEquals(emptySet(), escaped.getOrDefault(emptySet()), "an escaped backtick ended the quotes")

    val empty = assertFailsWith<IllegalArgumentException> { found { where { query.predicate("", ComparisonOperator.IS_NOT_NULL) } } }
    assertContains(empty.message.orEmpty(), "empty")
}

/** A `not { }` over a property of a relationship's target is a predicate on the relationship, as it is outside the `not`. */
private fun verifyNotOverRelationship(gom: StatelessGraphObjectManager) {
    val notKim = gom.loadAll(ShelfView::class.java, DSL) { where { not { query.keeper.displayName eq "kim" } } }
    assertEquals(setOf("s2", "s3"), notKim.map { it.shelf.id }.toSet())
    val both = gom.loadAll(ShelfView::class.java, DSL) {
        where { query.shelf.id eq "s2"; not { query.entries.title eq "alpha" }; query.keeper.displayName eq "lee" }
    }
    assertEquals(listOf("s2"), both.map { it.shelf.id })
}

private fun buildGom(pm: PersistenceManager, registry: SubtypeRegistry): StatelessGraphObjectManager =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

private fun ensureIndexes(pm: PersistenceManager) {
    pm.indexes.ensure(VectorIndexSpec("Shelf", "embedding", 4))
    pm.indexes.ensure(FullTextIndexSpec("Shelf", "title"))
}

/** [delegate] with its collections sorted by a subquery, where its own grammar sorts them with APOC. */
private class SubquerySorting(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    override val grammar: CypherGrammar = CypherDialect.NEO4J_5.grammar(CallSubqueryEmitter())
}

@Testcontainers
class ReadPathNeo4jTest {
    companion object {
        private const val PASSWORD = "readpathtest"

        @Container @JvmField
        val container: Neo4jContainer<*> = Neo4jContainer(DockerImageName.parse("neo4j:latest"))
            .apply { withAdminPassword(PASSWORD) }
            .withEnv("NEO4J_PLUGINS", "[\"apoc\"]")

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: StatelessGraphObjectManager
        private lateinit var registry: SubtypeRegistry

        @JvmStatic @BeforeAll
        fun setup() {
            registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-rp", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            gom = buildGom(pm, registry)
            ensureIndexes(pm)
            seed(gom, pm)
            pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes(300)"))
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `a scored search filters on a collection of nested views on Neo4j`() = verifyNestedViewFilter(gom)

    @Test fun `a scored search filters on a relationship that holds one node on Neo4j`() = verifySingleRelationshipFilter(gom)

    @Test fun `a view is not ordered or filtered after its projection by a property it does not project on Neo4j`() = verifyUndeclaredRootProperty(gom)

    @Test fun `a view whose root has a property bag is ordered and paged by a key of the bag on Neo4j`() = verifyBagKeyOrder(gom)

    @Test fun `a collection is sorted with APOC beside a required relationship and a relationship filter on Neo4j`() = verifySortBesideRelationshipChecks(gom)

    @Test
    fun `a collection is sorted in a subquery beside a required relationship and a relationship filter on Neo4j`() =
        verifySortBesideRelationshipChecks(buildGom(SubquerySorting(pm), registry))

    @Test fun `a collection inside a nested view is sorted with APOC on Neo4j`() = verifyNestedSort(gom)

    @Test
    fun `a collection inside a nested view is not sorted in a subquery on Neo4j`() =
        verifyNestedSortRefused(buildGom(SubquerySorting(pm), registry))

    @Test fun `a runtime key cannot end its quotes on Neo4j`() = verifyEscapedKeys(gom)

    @Test fun `a not over a relationship target's property is a predicate on the relationship on Neo4j`() = verifyNotOverRelationship(gom)
}

@Testcontainers
class ReadPathFalkorDbTest {
    companion object {
        private const val GRAPH = "readpathtest"

        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("falkordb/falkordb:latest"))
            .withExposedPorts(6379)

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-rp", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            gom = buildGom(pm, registry)
            ensureIndexes(pm)
            seed(gom, pm)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `a scored search filters on a collection of nested views on FalkorDB`() = verifyNestedViewFilter(gom)

    @Test fun `a scored search filters on a relationship that holds one node on FalkorDB`() = verifySingleRelationshipFilter(gom)

    @Test fun `a view is not ordered or filtered after its projection by a property it does not project on FalkorDB`() = verifyUndeclaredRootProperty(gom)

    @Test fun `a view whose root has a property bag is ordered and paged by a key of the bag on FalkorDB`() = verifyBagKeyOrder(gom)

    @Test fun `a collection is sorted beside a required relationship and a relationship filter on FalkorDB`() = verifySortBesideRelationshipChecks(gom)

    @Test fun `a collection inside a nested view is sorted on FalkorDB`() = verifyNestedSort(gom)

    @Test fun `a runtime key cannot end its quotes on FalkorDB`() = verifyEscapedKeys(gom)

    @Test fun `a not over a relationship target's property is a predicate on the relationship on FalkorDB`() = verifyNotOverRelationship(gom)
}

@Testcontainers
class ReadPathMemgraphTest {
    companion object {
        @Container @JvmField
        val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("memgraph/memgraph:latest"))
            .withExposedPorts(7687).waitingFor(Wait.forListeningPort())

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-rp", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            gom = buildGom(pm, registry)
            ensureIndexes(pm)
            seed(gom, pm)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `a scored search filters on a collection of nested views on Memgraph`() = verifyNestedViewFilter(gom)

    @Test fun `a scored search filters on a relationship that holds one node on Memgraph`() = verifySingleRelationshipFilter(gom)

    @Test fun `a view is not ordered or filtered after its projection by a property it does not project on Memgraph`() = verifyUndeclaredRootProperty(gom)

    @Test fun `a view whose root has a property bag is ordered and paged by a key of the bag on Memgraph`() = verifyBagKeyOrder(gom)

    @Test fun `a collection is sorted beside a required relationship and a relationship filter on Memgraph`() = verifySortBesideRelationshipChecks(gom)

    @Test fun `a collection inside a nested view is not sorted on Memgraph`() = verifyNestedSortRefused(gom)

    @Test fun `a runtime key cannot end its quotes on Memgraph`() = verifyEscapedKeys(gom)

    @Test fun `a not over a relationship target's property is a predicate on the relationship on Memgraph`() = verifyNotOverRelationship(gom)
}
