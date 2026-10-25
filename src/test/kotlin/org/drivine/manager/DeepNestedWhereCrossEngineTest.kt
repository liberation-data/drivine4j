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
import org.drivine.query.dsl.anyOf
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.StringPropertyReference
import org.drivine.query.dsl.UnderscoredRegion
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.sqlite.SqliteTestDatabase
import org.drivine.test.SharedFalkorDb
import org.drivine.test.SharedMemgraph
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Neo4jContainer
import sample.mapped.view.LocationHierarchy
import kotlin.test.assertEquals

/** Hand-written query DSL for [LocationHierarchy] and [UnderscoredRegion], as `drivine4j-codegen` emits it: each level built when read. */
class DeepLocationProperties(override val nodeAlias: String) : NodeReference {
    val name = StringPropertyReference(nodeAlias, "name")
}

class DeepLocationLevel(alias: String) {
    val location = DeepLocationProperties(alias)
    val subLocations: DeepLocationLevel get() = DeepLocationLevel("${location.nodeAlias}_subLocations")
}

class DeepLocationQueryDsl {
    val location = DeepLocationProperties("location")
    val subLocations: DeepLocationLevel get() = DeepLocationLevel("subLocations")
}

class DeepRegionLevel(alias: String) {
    val location = DeepLocationProperties(alias)
    val sub_regions: DeepRegionLevel get() = DeepRegionLevel("${location.nodeAlias}_sub_regions")
}

class DeepRegionQueryDsl {
    val location = DeepLocationProperties("location")
    val sub_regions: DeepRegionLevel get() = DeepRegionLevel("sub_regions")
}

/**
 * A `where` three levels down on a view that holds itself returns the roots it holds of on every
 * store: a check of each level in turn, met by one chain of related nodes, through a relationship whose
 * name holds an underscore as well.
 */
private fun verify(gom: StatelessGraphObjectManager) {
    fun names(spec: GraphQuerySpec<DeepLocationQueryDsl>.() -> Unit): Set<String> =
        gom.loadAll(LocationHierarchy::class.java, DeepLocationQueryDsl(), spec).map { it.location.name }.toSet()

    assertEquals(setOf("Europe"), names { where { query.subLocations.subLocations.subLocations.location.name eq "Paris" } })
    assertEquals(setOf("Europe", "Asia"), names {
        where { anyOf { query.subLocations.subLocations.subLocations.location.name eq "Paris"; query.subLocations.subLocations.subLocations.location.name eq "Tokyo" } }
    })
    // Held of one chain: Western Europe leads to no Tokyo.
    assertEquals(emptySet(), names {
        where {
            query.subLocations.location.name eq "Western Europe"
            query.subLocations.subLocations.subLocations.location.name eq "Tokyo"
        }
    })
    assertEquals(setOf("Europe"), names {
        where {
            query.subLocations.location.name eq "Western Europe"
            query.subLocations.subLocations.location.name eq "France"
            query.subLocations.subLocations.subLocations.location.name eq "Paris"
        }
    })

    val regions = gom.loadAll(UnderscoredRegion::class.java, DeepRegionQueryDsl()) {
        where { query.sub_regions.sub_regions.sub_regions.location.name eq "Tokyo" }
    }
    assertEquals(setOf("Asia"), regions.map { it.location.name }.toSet())
}

private const val SEED = """
    CREATE (europe:Location {uuid: '00000000-0000-0000-0000-000000000001', name: 'Europe', type: 'continent'})
    CREATE (west:Location {uuid: '00000000-0000-0000-0000-000000000002', name: 'Western Europe', type: 'region'})
    CREATE (france:Location {uuid: '00000000-0000-0000-0000-000000000003', name: 'France', type: 'country'})
    CREATE (paris:Location {uuid: '00000000-0000-0000-0000-000000000004', name: 'Paris', type: 'city'})
    CREATE (asia:Location {uuid: '00000000-0000-0000-0000-000000000005', name: 'Asia', type: 'continent'})
    CREATE (east:Location {uuid: '00000000-0000-0000-0000-000000000006', name: 'Eastern Asia', type: 'region'})
    CREATE (japan:Location {uuid: '00000000-0000-0000-0000-000000000007', name: 'Japan', type: 'country'})
    CREATE (tokyo:Location {uuid: '00000000-0000-0000-0000-000000000008', name: 'Tokyo', type: 'city'})
    CREATE (europe)-[:HAS_LOCATION]->(west)
    CREATE (west)-[:HAS_LOCATION]->(france)
    CREATE (france)-[:HAS_LOCATION]->(paris)
    CREATE (asia)-[:HAS_LOCATION]->(east)
    CREATE (east)-[:HAS_LOCATION]->(japan)
    CREATE (japan)-[:HAS_LOCATION]->(tokyo)
"""

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry) =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

class DeepNestedWhereNeo4jTest {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-deep-where", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            gom = buildGom(pm, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun seed() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
        pm.execute(QuerySpecification.withStatement(SEED.trimIndent()))
    }

    @Test
    fun `a where three levels down on Neo4j`() = verify(gom)
}

class DeepNestedWhereFalkorDbTest {
    companion object {
        private const val GRAPH = "deepwhere"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-deep-where", host = container.host, port = container.getMappedPort(6379),
                password = null, graphName = GRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, GRAPH, DatabaseType.FALKORDB, registry)
            gom = buildGom(pm, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun seed() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
        pm.execute(QuerySpecification.withStatement(SEED.trimIndent()))
    }

    @Test
    fun `a where three levels down on FalkorDB`() = verify(gom)
}

class DeepNestedWhereMemgraphTest {
    companion object {
        @JvmField
        val container: GenericContainer<*> = SharedMemgraph.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "memgraph-deep-where", type = DatabaseType.MEMGRAPH,
                host = container.host, port = container.getMappedPort(7687),
                user = "", password = "", database = null, config = emptyMap(),
                cypherDialect = CypherDialect.MEMGRAPH, subtypeRegistry = registry,
            )
            pm = NonTransactionalPersistenceManager(provider, "memgraph", DatabaseType.MEMGRAPH, registry)
            gom = buildGom(pm, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    @BeforeEach
    fun seed() {
        pm.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
        pm.execute(QuerySpecification.withStatement(SEED.trimIndent()))
    }

    @Test
    fun `a where three levels down on Memgraph`() = verify(gom)
}

@OracleTests
class DeepNestedWhereOracleTest {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun seed() {
        OracleTestDatabase.clear(pm)
        OracleTestDatabase.seed(pm, SEED)
    }

    @Test
    fun `a where three levels down on Oracle`() = verify(buildGom(pm, registry))
}

@PostgresTests
class DeepNestedWherePostgresTest {
    private val registry = SubtypeRegistry()
    private val pm = PostgresTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun seed() {
        PostgresTestDatabase.clear(pm)
        PostgresTestDatabase.seed(pm, SEED)
    }

    @Test
    fun `a where three levels down on Postgres`() = verify(buildGom(pm, registry))
}

class DeepNestedWhereSqliteTest {
    private val registry = SubtypeRegistry()
    private val pm = SqliteTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun seed() {
        SqliteTestDatabase.clear(pm)
        SqliteTestDatabase.seed(pm, SEED)
    }

    @Test
    fun `a where three levels down on SQLite`() = verify(buildGom(pm, registry))
}
