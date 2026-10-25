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
import sample.siblings.SibOfficeView
import kotlin.test.assertEquals

/** Hand-written query DSL for [SibOfficeView], as `drivine4j-codegen` emits it. */
class SibNameProperties(override val nodeAlias: String) : NodeReference {
    val name = StringPropertyReference(nodeAlias, "name")
}

class SibDeskLevel(alias: String) {
    val staffer = SibNameProperties(alias)
    val employer = SibNameProperties("${alias}_employer")
    val mentor = SibNameProperties("${alias}_mentor")
}

class SibOfficeQueryDsl {
    val office = SibNameProperties("office")
    val desks = SibDeskLevel("desks")
}

/**
 * A `where` on two relationships of the same nested view holds of one node of it on every store: a
 * desk that works for Acme and is mentored by Ann. Each relationship leads from the desk, not one from
 * the other's target.
 */
private fun verify(gom: StatelessGraphObjectManager) {
    fun offices(spec: GraphQuerySpec<SibOfficeQueryDsl>.() -> Unit): Set<String> =
        gom.loadAll(SibOfficeView::class.java, SibOfficeQueryDsl(), spec).map { it.office.name }.toSet()

    assertEquals(setOf("O1"), offices {
        where {
            query.desks.employer.name eq "Acme"
            query.desks.mentor.name eq "Ann"
        }
    })
    // With the desk's own name too: three conditions, one desk.
    assertEquals(setOf("O1"), offices {
        where {
            query.desks.staffer.name eq "Sam"
            query.desks.employer.name eq "Acme"
            query.desks.mentor.name eq "Ann"
        }
    })
    // O3 has a desk at Acme and another mentored by Ann, but none that is both.
    assertEquals(setOf("O1", "O2", "O3"), offices { where { query.desks.employer.name eq "Acme" } })
    assertEquals(setOf("O1", "O3"), offices { where { query.desks.mentor.name eq "Ann" } })
}

private const val SEED = """
    CREATE (o1:SibOffice {id: 'o1', name: 'O1'})
    CREATE (o2:SibOffice {id: 'o2', name: 'O2'})
    CREATE (o3:SibOffice {id: 'o3', name: 'O3'})
    CREATE (acme:SibFirm {id: 'acme', name: 'Acme'})
    CREATE (ann:SibStaffer {id: 'ann', name: 'Ann'})
    CREATE (sam:SibStaffer {id: 'sam', name: 'Sam'})
    CREATE (lee:SibStaffer {id: 'lee', name: 'Lee'})
    CREATE (kim:SibStaffer {id: 'kim', name: 'Kim'})
    CREATE (joe:SibStaffer {id: 'joe', name: 'Joe'})
    CREATE (o1)-[:SEATS]->(sam)
    CREATE (sam)-[:WORKS_FOR]->(acme)
    CREATE (sam)-[:MENTORED_BY]->(ann)
    CREATE (o2)-[:SEATS]->(lee)
    CREATE (lee)-[:WORKS_FOR]->(acme)
    CREATE (o3)-[:SEATS]->(kim)
    CREATE (kim)-[:WORKS_FOR]->(acme)
    CREATE (o3)-[:SEATS]->(joe)
    CREATE (joe)-[:MENTORED_BY]->(ann)
"""

private fun buildGom(pm: NonTransactionalPersistenceManager, registry: SubtypeRegistry) =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

class SiblingNestedWhereNeo4jTest {
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
                name = "neo-sibling-where", type = DatabaseType.NEO4J,
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
    fun `a where on two sibling relationships holds of one desk on Neo4j`() = verify(gom)
}

class SiblingNestedWhereFalkorDbTest {
    companion object {
        private const val GRAPH = "siblingwhere"

        @JvmField
        val container: GenericContainer<*> = SharedFalkorDb.forClass()

        private lateinit var provider: FalkorDbConnectionProvider
        private lateinit var pm: NonTransactionalPersistenceManager
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = FalkorDbConnectionProvider(
                name = "falkor-sibling-where", host = container.host, port = container.getMappedPort(6379),
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
    fun `a where on two sibling relationships holds of one desk on FalkorDB`() = verify(gom)
}

class SiblingNestedWhereMemgraphTest {
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
                name = "memgraph-sibling-where", type = DatabaseType.MEMGRAPH,
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
    fun `a where on two sibling relationships holds of one desk on Memgraph`() = verify(gom)
}

@OracleTests
class SiblingNestedWhereOracleTest {
    private val registry = SubtypeRegistry()
    private val pm = OracleTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun seed() {
        OracleTestDatabase.clear(pm)
        OracleTestDatabase.seed(pm, SEED)
    }

    @Test
    fun `a where on two sibling relationships holds of one desk on Oracle`() = verify(buildGom(pm, registry))
}

@PostgresTests
class SiblingNestedWherePostgresTest {
    private val registry = SubtypeRegistry()
    private val pm = PostgresTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun seed() {
        PostgresTestDatabase.clear(pm)
        PostgresTestDatabase.seed(pm, SEED)
    }

    @Test
    fun `a where on two sibling relationships holds of one desk on Postgres`() = verify(buildGom(pm, registry))
}

class SiblingNestedWhereSqliteTest {
    private val registry = SubtypeRegistry()
    private val pm = SqliteTestDatabase.graphManager("contracts", registry)

    @BeforeEach
    fun seed() {
        SqliteTestDatabase.clear(pm)
        SqliteTestDatabase.seed(pm, SEED)
    }

    @Test
    fun `a where on two sibling relationships holds of one desk on SQLite`() = verify(buildGom(pm, registry))
}
