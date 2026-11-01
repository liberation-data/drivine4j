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
import sample.targetlabel.TlCat
import sample.targetlabel.TlDog
import sample.targetlabel.TlShelfView
import sample.targetlabel.TlShelfViewQueryDsl
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A condition on a related node tests only the nodes the field would load: those the relationship
 * leads to that carry the field's target labels (liberation-data/drivine4j#435). So a view is found by
 * a filter exactly when its loaded fields meet it.
 *
 * Four shelves. `s1` is kept by kim, a keeper; holds `e1` (alpha), tagged with the marker red; and is
 * guarded by rex, a dog. Each of the others reaches nodes that would meet the conditions but carry
 * other labels:
 * - `s2` is kept by a kim with only one of the keeper's two labels, holds a stranger titled alpha and
 *   `e2` (beta), which is tagged with a stranger named red, and is guarded by a node with the dog's own
 *   label but not the pet's, named rex, and by tom, a cat;
 * - `s3` is kept by, holds and is guarded by one stranger, named kim and rex and titled alpha, which is
 *   tagged with the marker red;
 * - `s4` has nothing.
 * A stranger is kept by kim too: it has the relationship but is no shelf. The path `tagged` reaches
 * only `s1`'s red: `s2`'s entry is tagged with a stranger, and `s3` holds a stranger, not an entry.
 */
private const val SEED = """
    CREATE (s1:TlShelf {id: 's1'})
    CREATE (s2:TlShelf {id: 's2'})
    CREATE (s3:TlShelf {id: 's3'})
    CREATE (s4:TlShelf {id: 's4'})
    CREATE (k1:TlPerson:TlKeeper {id: 'k1', display_name: 'kim'})
    CREATE (p2:TlPerson {id: 'p2', display_name: 'kim'})
    CREATE (x3:TlStranger {id: 'x3', display_name: 'kim', title: 'alpha', name: 'rex'})
    CREATE (x4:TlStranger {id: 'x4', title: 'alpha'})
    CREATE (e1:TlEntry {id: 'e1', title: 'alpha'})
    CREATE (e2:TlEntry {id: 'e2', title: 'beta'})
    CREATE (m1:TlMarker {id: 'm1', name: 'red'})
    CREATE (y2:TlStranger {id: 'y2', name: 'red'})
    CREATE (d1:TlPet:TlDog {id: 'd1', name: 'rex'})
    CREATE (c2:TlPet:TlCat {id: 'c2', name: 'tom'})
    CREATE (z2:TlDog {id: 'z2', name: 'rex'})
    CREATE (r:TlStranger {id: 'r'})
    CREATE (s1)-[:KEPT_BY]->(k1)
    CREATE (s1)-[:HOLDS]->(e1)
    CREATE (e1)-[:TAGGED]->(m1)
    CREATE (s1)-[:GUARDED_BY]->(d1)
    CREATE (s2)-[:KEPT_BY]->(p2)
    CREATE (s2)-[:HOLDS]->(x4)
    CREATE (s2)-[:HOLDS]->(e2)
    CREATE (e2)-[:TAGGED]->(y2)
    CREATE (s2)-[:GUARDED_BY]->(z2)
    CREATE (s2)-[:GUARDED_BY]->(c2)
    CREATE (s3)-[:KEPT_BY]->(x3)
    CREATE (s3)-[:HOLDS]->(x3)
    CREATE (s3)-[:GUARDED_BY]->(x3)
    CREATE (x3)-[:TAGGED]->(m1)
    CREATE (r)-[:KEPT_BY]->(k1)
"""

private val DSL = TlShelfViewQueryDsl.INSTANCE

private fun verify(gom: StatelessGraphObjectManager) {
    val loaded = gom.loadAll(TlShelfView::class.java, DSL) { }.associateBy { it.shelf.id }
    assertEquals(setOf("s1", "s2", "s3", "s4"), loaded.keys)

    // What the load reads of each field: the decoys are left out.
    assertEquals("kim", loaded.getValue("s1").keeper?.displayName)
    assertNull(loaded.getValue("s2").keeper)
    assertNull(loaded.getValue("s3").keeper)
    assertEquals(listOf("e2"), loaded.getValue("s2").entries.map { it.entry.id })
    assertEquals(emptyList(), loaded.getValue("s2").entries.single().markers)
    assertEquals(emptyList(), loaded.getValue("s3").entries)
    assertTrue(loaded.getValue("s1").pets.single() is TlDog)
    assertTrue(loaded.getValue("s2").pets.single() is TlCat)
    assertEquals(emptyList(), loaded.getValue("s3").pets)
    assertEquals(listOf("m1"), loaded.getValue("s1").tagged.map { it.id })
    assertEquals(emptyList(), loaded.getValue("s2").tagged)
    assertEquals(emptyList(), loaded.getValue("s3").tagged)

    /** The shelves [spec] finds are [expected], and those whose loaded fields meet [holds]. */
    fun agrees(expected: Set<String>, holds: (TlShelfView) -> Boolean, spec: GraphQuerySpec<TlShelfViewQueryDsl>.() -> Unit) {
        val found = gom.loadAll(TlShelfView::class.java, DSL, spec).map { it.shelf.id }.toSet()
        assertEquals(loaded.values.filter(holds).map { it.shelf.id }.toSet(), found, "the filter and the load disagree")
        assertEquals(expected, found)
    }
    fun keptByKim(view: TlShelfView) = view.keeper?.displayName == "kim"
    fun holdsAlpha(view: TlShelfView) = view.entries.any { it.entry.title == "alpha" }
    fun holdsRed(view: TlShelfView) = view.entries.any { entry -> entry.markers.any { it.name == "red" } }
    fun guardedByRex(view: TlShelfView) = view.pets.any { it.name == "rex" }

    // A relationship that holds one node: a node of two labels, and one with only one of them.
    agrees(setOf("s1"), ::keptByKim) { where { query.keeper.any { displayName eq "kim" } } }
    agrees(setOf("s1"), ::keptByKim) { where { query.keeper.displayName eq "kim" } }
    agrees(setOf("s2", "s3", "s4"), { !keptByKim(it) }) { where { query.keeper.none { displayName eq "kim" } } }
    agrees(setOf("s2", "s3", "s4"), { !keptByKim(it) }) { where { not { query.keeper.displayName eq "kim" } } }
    agrees(setOf("s1"), { it.keeper != null }) { where { query.keeper.any { } } }
    agrees(setOf("s2", "s3", "s4"), { it.keeper == null }) { where { query.keeper.none { } } }

    // A collection of nested views.
    agrees(setOf("s1"), ::holdsAlpha) { where { query.entries.any { title eq "alpha" } } }
    agrees(setOf("s1"), ::holdsAlpha) { where { query.entries.title eq "alpha" } }
    agrees(setOf("s2", "s3", "s4"), { !holdsAlpha(it) }) { where { query.entries.none { title eq "alpha" } } }
    agrees(setOf("s1", "s2"), { it.entries.isNotEmpty() }) { where { query.entries.any { } } }
    agrees(setOf("s3", "s4"), { it.entries.isEmpty() }) { where { query.entries.none { } } }

    // A relationship of the nested view: neither a marker reached from a node that is no entry, nor a
    // node that is no marker reached from an entry.
    agrees(setOf("s1"), ::holdsRed) { where { query.entries.markers.name eq "red" } }
    agrees(setOf("s1"), ::holdsRed) { where { query.entries.any { markers.name eq "red" } } }
    agrees(setOf("s2", "s3", "s4"), { !holdsRed(it) }) { where { query.entries.none { markers.name eq "red" } } }
    agrees(setOf("s1"), { holdsAlpha(it) && holdsRed(it) }) { where { query.entries.any { title eq "alpha"; markers.name eq "red" } } }

    // A target that is one of several classes: the label they share, not the label of one of them.
    agrees(setOf("s1"), ::guardedByRex) { where { query.pets.any { name eq "rex" } } }
    agrees(setOf("s2", "s3", "s4"), { !guardedByRex(it) }) { where { query.pets.none { name eq "rex" } } }
    agrees(setOf("s1", "s2"), { it.pets.isNotEmpty() }) { where { query.pets.any { } } }

    // A path: every hop, through a node with the hop's label, to a node with the target's labels.
    agrees(setOf("s1"), { view -> view.tagged.any { it.name == "red" } }) { where { query.tagged.any { name eq "red" } } }
    agrees(setOf("s2", "s3", "s4"), { view -> view.tagged.none { it.name == "red" } }) { where { query.tagged.none { name eq "red" } } }
    agrees(setOf("s1"), { it.tagged.isNotEmpty() }) { where { query.tagged.any { } } }

    // Within an `or`.
    agrees(setOf("s1", "s2"), { keptByKim(it) || it.pets.any { pet -> pet.name == "tom" } }) {
        where {
            anyOf {
                query.keeper.displayName eq "kim"
                query.pets.name eq "tom"
            }
        }
    }
}

private fun buildGom(pm: PersistenceManager, registry: SubtypeRegistry) =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

/** Indexes on the properties the conditions test, so a store that reads a filter through one is tested with it. */
private fun ensureIndexes(pm: PersistenceManager) {
    listOf("TlKeeper" to "display_name", "TlEntry" to "title", "TlMarker" to "name", "TlPet" to "name")
        .forEach { (label, property) -> pm.indexes.ensure(RangeIndexSpec(label, property)) }
}

class RelatedTargetLabelNeo4jTest {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-target-label", type = DatabaseType.NEO4J,
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
    fun `a condition on a related node tests only the nodes the field loads on Neo4j`() = verify(gom)
}

class RelatedTargetLabelFalkorDbTest {
    companion object {
        private const val GRAPH = "targetlabel"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-target-label", host = container.host, port = container.getMappedPort(6379),
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
    fun `a condition on a related node tests only the nodes the field loads on FalkorDB`() = verify(gom)
}

class RelatedTargetLabelMemgraphTest {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-target-label", type = DatabaseType.MEMGRAPH,
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
    fun `a condition on a related node tests only the nodes the field loads on Memgraph`() = verify(gom)
}

@OracleTests
class RelatedTargetLabelOracleTest {
    companion object {
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            val store = OracleTestDatabase.graphManager("targetlabel", registry)
            OracleTestDatabase.clear(store)
            val pm = OracleTestDatabase.fixtures(store)
            ensureIndexes(pm)
            OracleTestDatabase.seed(pm, SEED)
            gom = buildGom(pm, registry)
        }
    }

    @Test
    fun `a condition on a related node tests only the nodes the field loads on Oracle`() = verify(gom)
}

@PostgresTests
class RelatedTargetLabelPostgresTest {
    companion object {
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            val store = PostgresTestDatabase.graphManager("targetlabel", registry)
            PostgresTestDatabase.clear(store)
            val pm = PostgresTestDatabase.fixtures(store)
            ensureIndexes(pm)
            PostgresTestDatabase.seed(pm, SEED)
            gom = buildGom(pm, registry)
        }
    }

    @Test
    fun `a condition on a related node tests only the nodes the field loads on Postgres`() = verify(gom)
}

class RelatedTargetLabelSqliteTest {
    companion object {
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            val store = SqliteTestDatabase.graphManager("targetlabel", registry)
            SqliteTestDatabase.clear(store)
            val pm = SqliteTestDatabase.fixtures(store)
            ensureIndexes(pm)
            SqliteTestDatabase.seed(pm, SEED)
            gom = buildGom(pm, registry)
        }
    }

    @Test
    fun `a condition on a related node tests only the nodes the field loads on SQLite`() = verify(gom)
}
