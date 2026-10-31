package org.drivine.manager

import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.any
import org.drivine.query.dsl.anyOf
import org.drivine.query.dsl.none
import org.drivine.query.dsl.not
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.schema.RangeIndexSpec
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import sample.conditiondepth.CdHolderView
import sample.conditiondepth.CdHolderViewQueryDsl
import sample.conditiondepth.CdPeerView
import sample.conditiondepth.CdPeerViewQueryDsl
import sample.conditiondepth.CdWindowView
import sample.conditiondepth.CdWindowViewQueryDsl
import kotlin.test.assertEquals

/**
 * A condition on a list of fragments read over several hops (`maxDepth` above 1) tests every node the
 * load reads, to the same depth, a depth given for the query included (liberation-data/drivine4j#461).
 * So a view is found by a filter exactly when its loaded field meets it.
 *
 * `following` is `NEXT` to two hops. The chain a → b → c → d; the cycle p → q → p, where each reaches
 * itself in two hops, and no walk takes a relationship twice; s1 → x1, a stranger whose text is "c",
 * which no field loads; and s2 → x2 → m, where m, two hops away, is reached through a stranger. Holders
 * hold the windows: h1 holds a, h2 c, h3 p and h5 s2; h4 holds nothing.
 *
 * `peers` is `LINK` either way to two hops. u — v, one relationship, so neither reaches itself; f and g
 * linked twice, two relationships, so each reaches itself over both, and g — k.
 */
private const val SEED = """
    CREATE (a:CdNode {id: 'a', text: 'a'})
    CREATE (b:CdNode {id: 'b', text: 'b'})
    CREATE (c:CdNode {id: 'c', text: 'c'})
    CREATE (d:CdNode {id: 'd', text: 'd'})
    CREATE (p:CdNode {id: 'p', text: 'p'})
    CREATE (q:CdNode {id: 'q', text: 'q'})
    CREATE (s1:CdNode {id: 's1', text: 's1'})
    CREATE (s2:CdNode {id: 's2', text: 's2'})
    CREATE (m:CdNode {id: 'm', text: 'm'})
    CREATE (x1:CdStranger {id: 'x1', text: 'c'})
    CREATE (x2:CdStranger {id: 'x2', text: 'n'})
    CREATE (h1:CdHolder {id: 'h1'})
    CREATE (h2:CdHolder {id: 'h2'})
    CREATE (h3:CdHolder {id: 'h3'})
    CREATE (h4:CdHolder {id: 'h4'})
    CREATE (h5:CdHolder {id: 'h5'})
    CREATE (a)-[:NEXT]->(b)
    CREATE (b)-[:NEXT]->(c)
    CREATE (c)-[:NEXT]->(d)
    CREATE (p)-[:NEXT]->(q)
    CREATE (q)-[:NEXT]->(p)
    CREATE (s1)-[:NEXT]->(x1)
    CREATE (s2)-[:NEXT]->(x2)
    CREATE (x2)-[:NEXT]->(m)
    CREATE (h1)-[:HAS]->(a)
    CREATE (h2)-[:HAS]->(c)
    CREATE (h3)-[:HAS]->(p)
    CREATE (h5)-[:HAS]->(s2)
    CREATE (u:CdPeer {id: 'u'})
    CREATE (v:CdPeer {id: 'v'})
    CREATE (f:CdPeer {id: 'f'})
    CREATE (g:CdPeer {id: 'g'})
    CREATE (k:CdPeer {id: 'k'})
    CREATE (u)-[:LINK]->(v)
    CREATE (f)-[:LINK]->(g)
    CREATE (f)-[:LINK]->(g)
    CREATE (g)-[:LINK]->(k)
"""

private val WINDOW = CdWindowViewQueryDsl.INSTANCE
private val HOLDER = CdHolderViewQueryDsl.INSTANCE
private val PEER = CdPeerViewQueryDsl.INSTANCE

private fun verify(gom: StatelessGraphObjectManager) {
    windows(gom)
    holders(gom)
    peers(gom)
}

/** The texts of the nodes each window's `following` loads, by the window's id, with [depth] for `following` when given. */
private fun loadedWindows(gom: StatelessGraphObjectManager, depth: Int?) =
    gom.loadAll(CdWindowView::class.java, WINDOW) { depth?.let { depth("following", it) } }
        .associate { it.node.id to it.following.map { node -> node.text!! }.sorted() }

private fun windows(gom: StatelessGraphObjectManager) {
    // What the load reads: everything within two hops, through any node, of the target's label, once.
    assertEquals(
        mapOf(
            "a" to listOf("b", "c"), "b" to listOf("c", "d"), "c" to listOf("d"), "d" to emptyList(),
            "p" to listOf("p", "q"), "q" to listOf("p", "q"), "s1" to emptyList(), "s2" to listOf("m"), "m" to emptyList(),
        ),
        loadedWindows(gom, null),
    )
    assertEquals(listOf("b", "c", "d"), loadedWindows(gom, 3).getValue("a"))
    assertEquals(listOf("b"), loadedWindows(gom, 1).getValue("a"))

    /**
     * The windows [spec] finds are [expected], and those whose id and loaded `following`, read to [depth]
     * where one is given, meet [holds]. Each is found once, and counted as found.
     */
    fun agrees(expected: Set<String>, depth: Int? = null, holds: (String, List<String>) -> Boolean, spec: GraphQuerySpec<CdWindowViewQueryDsl>.() -> Unit) {
        val loaded = loadedWindows(gom, depth)
        val withDepth: GraphQuerySpec<CdWindowViewQueryDsl>.() -> Unit = { spec(); depth?.let { depth("following", it) } }
        val found = gom.loadAll(CdWindowView::class.java, WINDOW, withDepth).map { it.node.id }
        assertEquals(found.toSet().size, found.size, "a window is found once: $found")
        assertEquals(loaded.filter { (id, following) -> holds(id, following) }.keys, found.toSet(), "the filter and the load disagree")
        assertEquals(expected, found.toSet())
        assertEquals(expected.size.toLong(), gom.count(CdWindowView::class.java, WINDOW, withDepth), "the count and the filter disagree")
    }

    agrees(setOf("a", "b"), holds = { _, it -> "c" in it }) { where { query.following.any { text eq "c" } } }
    agrees(setOf("a", "b"), holds = { _, it -> "c" in it }) { where { query.following.text eq "c" } }
    agrees(setOf("c", "d", "p", "q", "s1", "s2", "m"), holds = { _, it -> "c" !in it }) { where { query.following.none { text eq "c" } } }
    agrees(setOf("c", "d", "p", "q", "s1", "s2", "m"), holds = { _, it -> "c" !in it }) { where { not { query.following.text eq "c" } } }
    // Two conditions on one node.
    agrees(setOf("b", "c"), holds = { _, it -> "d" in it }) { where { query.following.any { text eq "d"; id eq "d" } } }
    // An empty block: whether the field loads a node at all, m two hops away through a stranger among them.
    agrees(setOf("a", "b", "c", "p", "q", "s2"), holds = { _, it -> it.isNotEmpty() }) { where { query.following.any { } } }
    agrees(setOf("d", "s1", "m"), holds = { _, it -> it.isEmpty() }) { where { query.following.none { } } }
    agrees(setOf("s2"), holds = { _, it -> "m" in it }) { where { query.following.any { text eq "m" } } }
    // A cycle: each of p and q reaches itself, and is found once.
    agrees(setOf("p", "q"), holds = { _, it -> "p" in it }) { where { query.following.any { text eq "p" } } }
    // Within an `or`.
    agrees(setOf("b", "c", "p"), holds = { id, it -> "d" in it || id == "p" }) {
        where {
            anyOf {
                query.following.text eq "d"
                query.node.text eq "p"
            }
        }
    }
    // A depth given for the query: deeper, and down to one hop.
    agrees(setOf("a", "b", "c"), depth = 3, holds = { _, it -> "d" in it }) { where { query.following.any { text eq "d" } } }
    agrees(setOf("b"), depth = 1, holds = { _, it -> "c" in it }) { where { query.following.any { text eq "c" } } }
    // A depth below one reads one hop, as the load does.
    agrees(setOf("b"), depth = 0, holds = { _, it -> "c" in it }) { where { query.following.any { text eq "c" } } }
    agrees(setOf("a", "b", "c", "p", "q"), depth = 1, holds = { _, it -> it.isNotEmpty() }) { where { query.following.any { } } }
}

/**
 * A walk that takes a relationship twice reaches nothing a path does, for the load or for a condition:
 * u reaches itself only back over the one relationship to v, so it neither loads u nor is found by it,
 * while f and g, linked twice, each reach themselves over the two.
 */
private fun peers(gom: StatelessGraphObjectManager) {
    val loaded = gom.loadAll(CdPeerView::class.java, PEER) { }.associate { it.peer.id to it.peers.map { peer -> peer.id }.sorted() }
    assertEquals(
        mapOf("u" to listOf("v"), "v" to listOf("u"), "f" to listOf("f", "g", "k"), "g" to listOf("f", "g", "k"), "k" to listOf("f", "g")),
        loaded,
    )
    fun found(id: String) = gom.loadAll(CdPeerView::class.java, PEER) { where { query.peers.any { this.id eq id } } }.map { it.peer.id }
    for (id in loaded.keys) {
        val expected = loaded.filterValues { id in it }.keys
        val found = found(id)
        assertEquals(expected, found.toSet(), "the filter and the load disagree on $id")
        assertEquals(expected.size, found.size, "a peer is found once: $found")
        assertEquals(expected.size.toLong(), gom.count(CdPeerView::class.java, PEER) { where { query.peers.any { this.id eq id } } })
    }
}

private fun holders(gom: StatelessGraphObjectManager) {
    /** The texts each window of each holder loads, by the holder's id, with [depth] for `following` when given. */
    fun loaded(depth: Int?) = gom.loadAll(CdHolderView::class.java, HOLDER) { depth?.let { depth("following", it) } }
        .associate { holder -> holder.holder.id to holder.windows.map { window -> window.following.map { it.text!! }.sorted() } }

    assertEquals(
        mapOf(
            "h1" to listOf(listOf("b", "c")), "h2" to listOf(listOf("d")), "h3" to listOf(listOf("p", "q")),
            "h4" to emptyList(), "h5" to listOf(listOf("m")),
        ),
        loaded(null),
    )

    /** The holders [spec] finds are [expected], and those with a window whose loaded `following` meets [holds]. */
    fun agrees(expected: Set<String>, depth: Int? = null, holds: (List<List<String>>) -> Boolean, spec: GraphQuerySpec<CdHolderViewQueryDsl>.() -> Unit) {
        val withDepth: GraphQuerySpec<CdHolderViewQueryDsl>.() -> Unit = { spec(); depth?.let { depth("following", it) } }
        val found = gom.loadAll(CdHolderView::class.java, HOLDER, withDepth).map { it.holder.id }
        assertEquals(found.toSet().size, found.size, "a holder is found once: $found")
        assertEquals(loaded(depth).filterValues(holds).keys, found.toSet(), "the filter and the load disagree")
        assertEquals(expected, found.toSet())
    }

    agrees(setOf("h1"), holds = { windows -> windows.any { "c" in it } }) { where { query.windows.following.text eq "c" } }
    agrees(setOf("h1"), holds = { windows -> windows.any { "c" in it } }) { where { query.windows.any { following.text eq "c" } } }
    agrees(setOf("h2", "h3", "h4", "h5"), holds = { windows -> windows.none { "c" in it } }) { where { query.windows.none { following.text eq "c" } } }
    agrees(setOf("h5"), holds = { windows -> windows.any { "m" in it } }) { where { query.windows.following.text eq "m" } }
    agrees(setOf("h3"), holds = { windows -> windows.any { "p" in it } }) { where { query.windows.following.text eq "p" } }
    agrees(setOf("h1", "h2"), depth = 3, holds = { windows -> windows.any { "d" in it } }) { where { query.windows.following.text eq "d" } }
    agrees(emptySet(), depth = 1, holds = { windows -> windows.any { "c" in it } }) { where { query.windows.following.text eq "c" } }
}

private fun buildGom(pm: PersistenceManager, registry: SubtypeRegistry) =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

/** An index on the property the conditions test, so a store that reads a filter through one is tested with it. */
private fun ensureIndexes(pm: PersistenceManager) = pm.indexes.ensure(RangeIndexSpec("CdNode", "text"))

class ConditionDepthNeo4jTest {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-condition-depth", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            val pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            gom = buildGom(pm, registry)
            pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
            pm.execute(QuerySpecification.withStatement(SEED.trimIndent()))
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `a condition on a list read over several hops tests the nodes the load reads on Neo4j`() = verify(gom)
}

class ConditionDepthFalkorDbTest {
    companion object {
        private const val GRAPH = "conditiondepth"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-condition-depth", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            val pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            gom = buildGom(pm, registry)
            pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
            pm.execute(QuerySpecification.withStatement(SEED.trimIndent()))
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `a condition on a list read over several hops tests the nodes the load reads on FalkorDB`() = verify(gom)
}

class ConditionDepthMemgraphTest {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-condition-depth", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            val pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            gom = buildGom(pm, registry)
            pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
            pm.execute(QuerySpecification.withStatement(SEED.trimIndent()))
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @Test
    fun `a condition on a list read over several hops tests the nodes the load reads on Memgraph`() = verify(gom)
}

@OracleTests
class ConditionDepthOracleTest {
    companion object {
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            val store = OracleTestDatabase.graphManager("conditiondepth", registry)
            OracleTestDatabase.clear(store)
            val pm = OracleTestDatabase.fixtures(store)
            ensureIndexes(pm)
            OracleTestDatabase.seed(pm, SEED)
            gom = buildGom(pm, registry)
        }
    }

    @Test
    fun `a condition on a list read over several hops tests the nodes the load reads on Oracle`() = verify(gom)
}

@PostgresTests
class ConditionDepthPostgresTest {
    companion object {
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            val store = PostgresTestDatabase.graphManager("conditiondepth", registry)
            PostgresTestDatabase.clear(store)
            val pm = PostgresTestDatabase.fixtures(store)
            ensureIndexes(pm)
            PostgresTestDatabase.seed(pm, SEED)
            gom = buildGom(pm, registry)
        }
    }

    @Test
    fun `a condition on a list read over several hops tests the nodes the load reads on Postgres`() = verify(gom)
}

class ConditionDepthSqliteTest {
    companion object {
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            val store = SqliteTestDatabase.graphManager("conditiondepth", registry)
            SqliteTestDatabase.clear(store)
            val pm = SqliteTestDatabase.fixtures(store)
            ensureIndexes(pm)
            SqliteTestDatabase.seed(pm, SEED)
            gom = buildGom(pm, registry)
        }
    }

    @Test
    fun `a condition on a list read over several hops tests the nodes the load reads on SQLite`() = verify(gom)
}
