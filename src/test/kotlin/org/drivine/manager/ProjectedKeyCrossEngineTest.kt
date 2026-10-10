package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.ComparisonOperator
import org.drivine.query.dsl.any
import org.drivine.query.dsl.predicate
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.sort.CallSubqueryEmitter
import org.drivine.query.transform
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
import sample.projected.Ledger
import sample.projected.LedgerView
import sample.projected.LedgerViewQueryDsl
import sample.projected.Marker
import sample.projected.Oddity
import sample.projected.OddityQueryDsl
import sample.projected.Passage
import sample.projected.PassageView
import sample.projected.PassageViewQueryDsl
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * A view projects its root, and each relationship target, into a map keyed by field name. A
 * `@GraphProperty` field and the `@NodeStamp` field are stored under another name, so whatever reads
 * them after the projection — an `ORDER BY`, the filter of a scored search, a sort of the projected
 * collection — must read the field name, where a predicate on the node reads the stored name.
 * Verified on Neo4j, FalkorDB and Memgraph.
 *
 * Five passages, `p1`..`p5`, whose `sequence_number` runs against their ids (50 down to 10), each
 * marked by three markers named out of order. Nothing here deletes a node: Memgraph does not purge a
 * deleted node from a vector index. The same containers serve two neighbours of the load path: a key
 * known only at runtime, and a batch of statements.
 */
private val QUERY = listOf(1.0f, 0.0f, 0.0f, 0.0f)
private val DSL = PassageViewQueryDsl.INSTANCE

private fun seed(gom: StatelessGraphObjectManager) {
    (1..5).forEach { i ->
        // Each marker is saved by a statement of its own, and so has a stamp of its own.
        val markers = listOf("charlie", "alpha", "bravo").map { gom.save(Marker("m$i-$it", displayName = it)) }
        gom.save(
            PassageView(
                passage = Passage("p$i", "passage number $i", sequenceNumber = 60L - 10 * i, embedding = QUERY),
                markers = markers,
            )
        )
    }
    (1..3).forEach { i -> gom.save(LedgerView(Ledger("l$i", rank = 40L - 10 * i, meta = mapOf("origin" to "seed")))) }
}

private fun ids(views: List<PassageView>) = views.map { it.passage.id }

private fun verifyRootOrder(gom: StatelessGraphObjectManager) {
    val stamps = gom.loadAll(PassageView::class.java).map { assertNotNull(it.passage.stamp) }.sorted()
    assertEquals(5, stamps.distinct().size)

    // Ordered by the @NodeStamp field, both ways.
    val byStamp = gom.loadAll(PassageView::class.java, DSL) { orderBy { query.passage.stamp.asc() } }
    assertEquals(stamps, byStamp.map { it.passage.stamp })
    val byStampDescending = gom.loadAll(PassageView::class.java, DSL) { orderBy { query.passage.stamp.desc() } }
    assertEquals(stamps.reversed(), byStampDescending.map { it.passage.stamp })

    // Ordered by the @GraphProperty field, both ways.
    val bySequence = gom.loadAll(PassageView::class.java, DSL) { orderBy { query.passage.sequenceNumber.asc() } }
    assertEquals(listOf("p5", "p4", "p3", "p2", "p1"), ids(bySequence))
    val bySequenceDescending = gom.loadAll(PassageView::class.java, DSL) { orderBy { query.passage.sequenceNumber.desc() } }
    assertEquals(listOf("p1", "p2", "p3", "p4", "p5"), ids(bySequenceDescending))

    // A where on each is a predicate on the node, and composes with the order.
    val filtered = gom.loadAll(PassageView::class.java, DSL) {
        where { query.passage.sequenceNumber gte 30L }
        orderBy { query.passage.sequenceNumber.asc() }
    }
    assertEquals(listOf("p3", "p2", "p1"), ids(filtered))
    val byItsStamp = gom.loadAll(PassageView::class.java, DSL) { where { query.passage.stamp eq stamps[2] } }
    assertEquals(listOf(stamps[2]), byItsStamp.map { it.passage.stamp })
}

private fun verifyRootKeyset(gom: StatelessGraphObjectManager) {
    val stamps = gom.loadAll(PassageView::class.java).map { assertNotNull(it.passage.stamp) }.sorted()

    // A keyset on the stamp pages through every root once.
    val pagedStamps = mutableListOf<String>()
    var page = gom.loadAll(PassageView::class.java, DSL) { orderBy { query.passage.stamp.asc() }; limit(2) }
    while (page.isNotEmpty()) {
        pagedStamps += page.map { assertNotNull(it.passage.stamp) }
        val last = pagedStamps.last()
        page = gom.loadAll(PassageView::class.java, DSL) {
            orderBy { query.passage.stamp.asc() }
            seek { query.passage.stamp after last }
            limit(2)
        }
    }
    assertEquals(stamps, pagedStamps)

    // And so does a keyset on the renamed field, under a where.
    val pagedIds = mutableListOf<String>()
    var cursor: Long? = null
    while (true) {
        val previous = cursor
        val next = gom.loadAll(PassageView::class.java, DSL) {
            where { query.passage.sequenceNumber lte 40L }
            orderBy { query.passage.sequenceNumber.desc() }
            if (previous != null) seek { query.passage.sequenceNumber after previous }
            limit(3)
        }
        if (next.isEmpty()) break
        pagedIds += ids(next)
        cursor = next.last().passage.sequenceNumber
    }
    assertEquals(listOf("p2", "p3", "p4", "p5"), pagedIds)
}

/** A root with a property bag is projected with `.*`, so its map keeps the stored names. */
private fun verifyBaggedRootOrder(gom: StatelessGraphObjectManager) {
    val ledgers = gom.loadAll(LedgerView::class.java, LedgerViewQueryDsl.INSTANCE) {
        where { query.ledger.rank gte 20L }
        orderBy { query.ledger.rank.asc() }
    }
    assertEquals(listOf("l2", "l1"), ledgers.map { it.ledger.id })
    assertEquals(listOf(20L, 30L), ledgers.map { it.ledger.rank })
    assertEquals(mapOf<String, Any?>("origin" to "seed"), ledgers.first().ledger.meta)
}

private fun verifyCollectionSort(gom: StatelessGraphObjectManager) {
    fun markersOf(spec: org.drivine.query.dsl.GraphQuerySpec<PassageViewQueryDsl>.() -> Unit): List<Marker> =
        gom.loadAll(PassageView::class.java, DSL) {
            where { query.passage.id eq "p1" }
            spec()
        }.single().markers

    // Sorted by the @GraphProperty field of the target.
    assertEquals(listOf("alpha", "bravo", "charlie"), markersOf { orderBy { query.markers.displayName.asc() } }.map { it.displayName })
    assertEquals(listOf("charlie", "bravo", "alpha"), markersOf { orderBy { query.markers.displayName.desc() } }.map { it.displayName })

    // Sorted by its @NodeStamp field.
    val stamps = markersOf { }.map { assertNotNull(it.stamp) }.sorted()
    assertEquals(3, stamps.distinct().size)
    assertEquals(stamps, markersOf { orderBy { query.markers.stamp.asc() } }.map { it.stamp })
    assertEquals(stamps.reversed(), markersOf { orderBy { query.markers.stamp.desc() } }.map { it.stamp })
}

/** The filter of a scored search over a view runs after the projection. */
private fun verifyScoredSearchFilter(gom: StatelessGraphObjectManager) {
    val all = gom.loadAll(PassageView::class.java)
    val p2 = all.single { it.passage.id == "p2" }
    val rootStamp = assertNotNull(p2.passage.stamp)
    val markerStamp = assertNotNull(p2.markers.first().stamp)

    fun nearest(spec: org.drivine.query.dsl.GraphQuerySpec<PassageViewQueryDsl>.() -> Unit): Set<String> =
        gom.loadNearest(PassageView::class.java, DSL, QUERY, topK = 10, spec = spec).map { it.value.passage.id }.toSet()

    assertEquals(setOf("p1", "p2", "p3"), nearest { where { query.passage.sequenceNumber gte 30L } })
    assertEquals(setOf("p2"), nearest { where { query.passage.stamp eq rootStamp } })
    assertEquals(setOf("p1", "p2", "p3", "p4", "p5"), nearest { where { query.markers.any { displayName eq "alpha" } } })
    assertEquals(emptySet(), nearest { where { query.markers.any { displayName eq "delta" } } })
    assertEquals(setOf("p2"), nearest { where { query.markers.any { stamp eq markerStamp } } })

    fun matching(spec: org.drivine.query.dsl.GraphQuerySpec<PassageViewQueryDsl>.() -> Unit): Set<String> =
        gom.loadMatching(PassageView::class.java, DSL, "passage", topK = 10, spec = spec).map { it.value.passage.id }.toSet()

    assertEquals(setOf("p4", "p5"), matching { where { query.passage.sequenceNumber lt 30L } })
    assertEquals(setOf("p2"), matching { where { query.passage.stamp eq rootStamp } })
    assertEquals(setOf("p2"), matching { where { query.markers.any { stamp eq markerStamp } } })
}

/**
 * A key known only at runtime is one property whatever it holds, and names a parameter of its own.
 * [backtick] is false for an engine that reads no name with a backtick in it: there the doubled
 * backtick ends one name and begins another, which is no statement, so the key is refused.
 */
private fun verifyDynamicKeys(gom: StatelessGraphObjectManager, pm: PersistenceManager, backtick: Boolean = true) {
    val keys = listOf("source-id", "source id", "metadata.source-id", "metadata.source id") +
        if (backtick) listOf("tick`mark", "metadata.tick`mark") else emptyList()
    fun properties(value: String) = keys.joinToString(", ") { "`${it.replace("`", "``")}`: '$value'" }
    pm.execute(
        QuerySpecification.withStatement(
            """
            MERGE (a:Oddity {id: 'a'}) SET a += {title: 'A', ${properties("x")}}
            MERGE (b:Oddity {id: 'b'}) SET b += {title: 'B', ${properties("y")}}
            """.trimIndent()
        )
    )
    val dsl = OddityQueryDsl.INSTANCE
    fun found(spec: org.drivine.query.dsl.GraphQuerySpec<OddityQueryDsl>.() -> Unit) =
        gom.loadAll(Oddity::class.java, dsl, spec).map { it.id }.toSet()

    keys.forEach { key ->
        assertEquals(setOf("a"), found { where { query.predicate(key, ComparisonOperator.EQUALS, "x") } }, "key '$key'")
    }
    // A key that tries to close the quotes: a property no node has, or a statement the engine refuses.
    val closing: org.drivine.query.dsl.GraphQuerySpec<OddityQueryDsl>.() -> Unit =
        { where { query.predicate("tick` IS NULL OR n.`id", ComparisonOperator.IS_NOT_NULL) } }
    if (backtick) assertEquals(emptySet(), found(closing)) else assertFailsWith<DrivineException> { found(closing) }

    // Two keys that differ only in a character no parameter name can hold are two parameters.
    assertEquals(
        setOf("a"),
        found {
            where {
                query.predicate("source-id", ComparisonOperator.EQUALS, "x")
                query.predicate("source id", ComparisonOperator.EQUALS, "x")
            }
        },
    )
    assertEquals(
        emptySet(),
        found {
            where {
                query.predicate("source-id", ComparisonOperator.EQUALS, "x")
                query.predicate("source id", ComparisonOperator.EQUALS, "y")
            }
        },
    )

    // A key that reads as Cypher is a property no node has, not a predicate and a comment.
    assertEquals(
        emptySet(),
        found {
            where {
                query.predicate("id IS NOT NULL //", ComparisonOperator.EQUALS, "x")
                query.id eq "no such node"
            }
        },
    )
}

private fun long(statement: String) = QuerySpecification.withStatement(statement).transform(Long::class.java)

private fun verifyBatchResults(pm: PersistenceManager) {
    // Each statement's rows, in the order of the statements.
    val rows = pm.queryBatch(listOf(long("RETURN 1"), long("UNWIND [2, 3] AS x RETURN x"), long("RETURN 4")))
    assertEquals(listOf(listOf(1L), listOf(2L, 3L), listOf(4L)), rows)

    assertEquals(emptyList(), pm.queryBatch(emptyList()))
    pm.executeBatch(emptyList())
}

/** A batch that fails midway, and the number of `BatchProbe` nodes it leaves. */
private fun probesLeftByAFailedBatch(pm: PersistenceManager, id: String): Long {
    assertFailsWith<Exception> {
        pm.queryBatch(
            listOf(
                long("CREATE (n:BatchProbe {id: '$id'}) RETURN 1"),
                long("THIS IS NOT CYPHER"),
                long("CREATE (n:BatchProbe {id: '$id'}) RETURN 2"),
            )
        )
    }
    return pm.getOne(long("MATCH (n:BatchProbe {id: '$id'}) RETURN count(n)"))
}

private fun buildGom(pm: PersistenceManager, registry: SubtypeRegistry): StatelessGraphObjectManager =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

private fun ensureIndexes(pm: PersistenceManager) {
    pm.indexes.ensure(VectorIndexSpec("Passage", "embedding", 4))
    pm.indexes.ensure(FullTextIndexSpec("Passage", "text"))
}

/** [delegate] with its collections sorted by a subquery, where its own grammar sorts them with APOC. */
private class SubquerySortingPersistenceManager(
    private val delegate: PersistenceManager,
) : PersistenceManager by delegate {
    override val grammar: CypherGrammar = CypherDialect.NEO4J_5.grammar(CallSubqueryEmitter())
}

@Testcontainers
class ProjectedKeyNeo4jTest {
    companion object {
        private const val PASSWORD = "projectedkeytest"

        // APOC, for apoc.coll.sortMaps: the collection sort of the NEO4J_5 dialect.
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
                name = "neo-pk", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            gom = buildGom(pm, registry)
            ensureIndexes(pm)
            seed(gom)
            pm.execute(QuerySpecification.withStatement("CALL db.awaitIndexes(300)"))
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `a view is ordered by a stamp or renamed root field on Neo4j`() = verifyRootOrder(gom)

    @Test fun `a keyset on a stamp or renamed root field pages through every root once on Neo4j`() = verifyRootKeyset(gom)

    @Test fun `a view whose root has a property bag is ordered by a renamed field on Neo4j`() = verifyBaggedRootOrder(gom)

    @Test fun `a collection is sorted by a stamp or renamed target field with APOC on Neo4j`() = verifyCollectionSort(gom)

    @Test
    fun `a collection is sorted by a stamp or renamed target field in a subquery on Neo4j`() =
        verifyCollectionSort(buildGom(SubquerySortingPersistenceManager(pm), registry))

    @Test fun `a scored search over a view filters on a stamp or renamed field on Neo4j`() = verifyScoredSearchFilter(gom)

    @Test fun `a runtime key that is not a plain identifier filters on Neo4j`() = verifyDynamicKeys(gom, pm)

    @Test fun `a batch returns each statement's rows in order on Neo4j`() = verifyBatchResults(pm)

    @Test
    fun `a batch that fails midway is rolled back on Neo4j`() =
        assertEquals(0L, probesLeftByAFailedBatch(pm, "neo"))
}

@Testcontainers
class ProjectedKeyFalkorDbTest {
    companion object {
        private const val GRAPH = "projectedkeytest"

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
                name = "falkor-pk", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            gom = buildGom(pm, registry)
            ensureIndexes(pm)
            seed(gom)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `a view is ordered by a stamp or renamed root field on FalkorDB`() = verifyRootOrder(gom)

    @Test fun `a keyset on a stamp or renamed root field pages through every root once on FalkorDB`() = verifyRootKeyset(gom)

    @Test fun `a view whose root has a property bag is ordered by a renamed field on FalkorDB`() = verifyBaggedRootOrder(gom)

    @Test fun `a collection is sorted by a stamp or renamed target field on FalkorDB`() = verifyCollectionSort(gom)

    @Test fun `a scored search over a view filters on a stamp or renamed field on FalkorDB`() = verifyScoredSearchFilter(gom)

    @Test fun `a runtime key that is not a plain identifier filters on FalkorDB`() = verifyDynamicKeys(gom, pm, backtick = false)

    @Test fun `a batch returns each statement's rows in order on FalkorDB`() = verifyBatchResults(pm)

    /** FalkorDB has no transaction to roll back: each statement is committed as it runs. */
    @Test
    fun `a batch that fails midway keeps the statements before the failure on FalkorDB`() =
        assertEquals(1L, probesLeftByAFailedBatch(pm, "falkor"))
}

@Testcontainers
class ProjectedKeyMemgraphTest {
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
                name = "memgraph-pk", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            gom = buildGom(pm, registry)
            ensureIndexes(pm)
            seed(gom)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test fun `a view is ordered by a stamp or renamed root field on Memgraph`() = verifyRootOrder(gom)

    @Test fun `a keyset on a stamp or renamed root field pages through every root once on Memgraph`() = verifyRootKeyset(gom)

    @Test fun `a view whose root has a property bag is ordered by a renamed field on Memgraph`() = verifyBaggedRootOrder(gom)

    @Test fun `a collection is sorted by a stamp or renamed target field on Memgraph`() = verifyCollectionSort(gom)

    @Test fun `a scored search over a view filters on a stamp or renamed field on Memgraph`() = verifyScoredSearchFilter(gom)

    @Test fun `a runtime key that is not a plain identifier filters on Memgraph`() = verifyDynamicKeys(gom, pm)

    @Test fun `a batch returns each statement's rows in order on Memgraph`() = verifyBatchResults(pm)

    @Test
    fun `a batch that fails midway is rolled back on Memgraph`() =
        assertEquals(0L, probesLeftByAFailedBatch(pm, "memgraph"))
}
