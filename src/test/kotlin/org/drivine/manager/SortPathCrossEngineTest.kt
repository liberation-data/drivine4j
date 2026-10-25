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
import org.drivine.query.dsl.NodeReference
import org.drivine.query.dsl.StringPropertyReference
import org.drivine.query.dsl.query
import org.drivine.query.grammar.CypherDialect
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.sort.CallSubqueryEmitter
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
import sample.sortpath.SpOrgView
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Hand-written query DSL for [SpOrgView], as `drivine4j-codegen` emits it. */
class SpNameProperties(override val nodeAlias: String) : NodeReference {
    val name = StringPropertyReference(nodeAlias, "name")
}

class SpTeamLevel(alias: String) {
    val team = SpNameProperties(alias)
    val members = SpNameProperties("${alias}_members")
}

class SpGroupLevel(alias: String) {
    val group = SpNameProperties(alias)
    val sub_teams = SpTeamLevel("${alias}_sub_teams")
}

class SpOrgQueryDsl {
    val org = SpNameProperties("org")
    val sub_units = SpNameProperties("sub_units")
    val groups = SpGroupLevel("groups")
}

/**
 * A collection is sorted by its whole path on every store: a relationship named with an underscore, at
 * the top and beneath, and a collection three levels down. Each was silently left unsorted. A graph
 * engine that sorts by `CALL` subqueries sorts no nested collection, and now refuses those paths as it
 * refuses any nested sort, where before they matched nothing and were dropped.
 */
private fun verify(gom: StatelessGraphObjectManager, sortsNested: Boolean) {
    fun org(spec: GraphQuerySpec<SpOrgQueryDsl>.() -> Unit): SpOrgView =
        gom.loadAll(SpOrgView::class.java, SpOrgQueryDsl(), spec).single()

    assertEquals(listOf("u3", "u2", "u1"), org { orderBy { query.sub_units.name.desc() } }.sub_units.map { it.name })
    assertEquals(listOf("u1", "u2", "u3"), org { orderBy { query.sub_units.name.asc() } }.sub_units.map { it.name })
    if (sortsNested) {
        assertEquals(
            listOf("t3", "t2", "t1"),
            org { orderBy { query.groups.sub_teams.team.name.desc() } }.groups.single().sub_teams.map { it.team.name },
        )
        assertEquals(
            listOf("m3", "m2", "m1"),
            org { orderBy { query.groups.sub_teams.members.name.desc() } }.groups.single().sub_teams.single { it.team.name == "t2" }.members.map { it.name },
        )
    } else {
        assertFailsWith<UnsupportedOperationException> { org { orderBy { query.groups.sub_teams.team.name.desc() } } }
        assertFailsWith<UnsupportedOperationException> { org { orderBy { query.groups.sub_teams.members.name.desc() } } }
    }
}

private const val SEED = """
    CREATE (o:SpOrg {id: 'o', name: 'org'})
    CREATE (g:SpGroup {id: 'g', name: 'group'})
    CREATE (o)-[:HAS_GROUP]->(g)
    CREATE (u2:SpItem {id: 'u2', name: 'u2'})
    CREATE (u1:SpItem {id: 'u1', name: 'u1'})
    CREATE (u3:SpItem {id: 'u3', name: 'u3'})
    CREATE (o)-[:HAS_UNIT]->(u2)
    CREATE (o)-[:HAS_UNIT]->(u1)
    CREATE (o)-[:HAS_UNIT]->(u3)
    CREATE (t2:SpTeam {id: 't2', name: 't2'})
    CREATE (t1:SpTeam {id: 't1', name: 't1'})
    CREATE (t3:SpTeam {id: 't3', name: 't3'})
    CREATE (g)-[:HAS_SUB_TEAM]->(t2)
    CREATE (g)-[:HAS_SUB_TEAM]->(t1)
    CREATE (g)-[:HAS_SUB_TEAM]->(t3)
    CREATE (m2:SpItem {id: 'm2', name: 'm2'})
    CREATE (m1:SpItem {id: 'm1', name: 'm1'})
    CREATE (m3:SpItem {id: 'm3', name: 'm3'})
    CREATE (t2)-[:HAS_MEMBER]->(m2)
    CREATE (t2)-[:HAS_MEMBER]->(m1)
    CREATE (t2)-[:HAS_MEMBER]->(m3)
"""

private fun buildGom(pm: PersistenceManager, registry: SubtypeRegistry) =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

/** [delegate] with its collections sorted by a subquery, where its own grammar sorts them with APOC, which this Neo4j lacks. */
private class SortedBySubquery(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    override val grammar: CypherGrammar = CypherDialect.NEO4J_5.grammar(CallSubqueryEmitter())
}

class SortPathNeo4jTest {
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
                name = "neo-sort-path", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            pm = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
            gom = buildGom(SortedBySubquery(pm), registry)
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
    fun `a collection sorts by a path a split on an underscore misreads on Neo4j`() = verify(gom, sortsNested = false)
}

class SortPathFalkorDbTest {
    companion object {
        private const val GRAPH = "sortpath"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-sort-path", host = container.host, port = container.getMappedPort(6379),
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
    fun `a collection sorts by a path a split on an underscore misreads on FalkorDB`() = verify(gom, sortsNested = false)
}

class SortPathMemgraphTest {
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
                name = "memgraph-sort-path", type = DatabaseType.MEMGRAPH,
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
    fun `a collection sorts by a path a split on an underscore misreads on Memgraph`() = verify(gom, sortsNested = false)
}

@OracleTests
class SortPathOracleTest {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun seed() {
        OracleTestDatabase.clear(pm)
        OracleTestDatabase.seed(pm, SEED)
    }

    @Test
    fun `a collection sorts by a path a split on an underscore misreads on Oracle`() = verify(buildGom(pm, registry), sortsNested = true)
}

@PostgresTests
class SortPathPostgresTest {
    private val registry = SubtypeRegistry()
    private val pm = PostgresTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun seed() {
        PostgresTestDatabase.clear(pm)
        PostgresTestDatabase.seed(pm, SEED)
    }

    @Test
    fun `a collection sorts by a path a split on an underscore misreads on Postgres`() = verify(buildGom(pm, registry), sortsNested = true)
}

class SortPathSqliteTest {
    private val registry = SubtypeRegistry()
    private val pm = SqliteTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun seed() {
        SqliteTestDatabase.clear(pm)
        SqliteTestDatabase.seed(pm, SEED)
    }

    @Test
    fun `a collection sorts by a path a split on an underscore misreads on SQLite`() = verify(buildGom(pm, registry), sortsNested = true)
}
