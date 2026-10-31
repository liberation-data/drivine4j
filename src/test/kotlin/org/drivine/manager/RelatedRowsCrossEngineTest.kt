package org.drivine.manager

import org.drivine.connection.DatabaseType
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
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.Neo4jContainer
import sample.relatedrows.RrShelfView
import sample.relatedrows.RrShelfViewQueryDsl
import kotlin.test.assertEquals

/**
 * A condition on a related node finds the roots Neo4j finds, where the related node's property is
 * missing and where there is no related node at all: for `any` and `none`, nested, and over a
 * relationship out, in, either way and along a path (liberation-data/drivine4j#460, which writes the
 * related rows of a condition on Postgres in the `EXISTS` itself).
 *
 * Five shelves:
 * - `a1` holds `b1` (alpha, 10 pages, tagged red) and `b2` (no title, no pages); is next to `a2`; ann likes it;
 * - `a2` holds `b3` (no title, 5 pages, tagged with a tag that has no name);
 * - `a3` holds `b4` (beta), and a stranger titled alpha; a reader with no name likes it;
 * - `a4` has nothing;
 * - `a5` holds `b5` (alpha, tagged blue and with a tag that has no name); is next to itself.
 */
private const val SEED = """
    CREATE (a1:RrShelf {id: 'a1'})
    CREATE (a2:RrShelf {id: 'a2'})
    CREATE (a3:RrShelf {id: 'a3'})
    CREATE (a4:RrShelf {id: 'a4'})
    CREATE (a5:RrShelf {id: 'a5'})
    CREATE (b1:RrBook {id: 'b1', title: 'alpha', pages: 10})
    CREATE (b2:RrBook {id: 'b2'})
    CREATE (b3:RrBook {id: 'b3', pages: 5})
    CREATE (b4:RrBook {id: 'b4', title: 'beta'})
    CREATE (b5:RrBook {id: 'b5', title: 'alpha'})
    CREATE (x:RrStranger {id: 'x', title: 'alpha'})
    CREATE (t1:RrTag {id: 't1', name: 'red'})
    CREATE (t2:RrTag {id: 't2'})
    CREATE (t3:RrTag {id: 't3', name: 'blue'})
    CREATE (t4:RrTag {id: 't4'})
    CREATE (r1:RrReader {id: 'r1', name: 'ann'})
    CREATE (r2:RrReader {id: 'r2'})
    CREATE (a1)-[:HOLDS]->(b1)
    CREATE (a1)-[:HOLDS]->(b2)
    CREATE (a2)-[:HOLDS]->(b3)
    CREATE (a3)-[:HOLDS]->(b4)
    CREATE (a3)-[:HOLDS]->(x)
    CREATE (a5)-[:HOLDS]->(b5)
    CREATE (b1)-[:TAGGED]->(t1)
    CREATE (b3)-[:TAGGED]->(t2)
    CREATE (b5)-[:TAGGED]->(t3)
    CREATE (b5)-[:TAGGED]->(t4)
    CREATE (a1)-[:NEXT_TO]->(a2)
    CREATE (a5)-[:NEXT_TO]->(a5)
    CREATE (r1)-[:LIKES]->(a1)
    CREATE (r2)-[:LIKES]->(a3)
"""

private val DSL = RrShelfViewQueryDsl.INSTANCE

private fun verify(gom: StatelessGraphObjectManager) {
    fun found(spec: GraphQuerySpec<RrShelfViewQueryDsl>.() -> Unit): Set<String> =
        gom.loadAll(RrShelfView::class.java, DSL, spec).map { it.shelf.id }.toSet()
    fun expect(vararg ids: String, spec: GraphQuerySpec<RrShelfViewQueryDsl>.() -> Unit) = assertEquals(ids.toSet(), found(spec))

    assertEquals(setOf("a1", "a2", "a3", "a4", "a5"), found { })

    // A property that some related nodes don't have.
    expect("a1", "a5") { where { query.books.any { title eq "alpha" } } }
    expect("a1", "a5") { where { query.books.title eq "alpha" } }
    expect("a2", "a3", "a4") { where { query.books.none { title eq "alpha" } } }
    expect("a2", "a3", "a4") { where { not { query.books.any { title eq "alpha" } } } }
    expect("a1", "a2") { where { query.books.any { title.isNull() } } }
    expect("a3", "a4", "a5") { where { query.books.none { title.isNull() } } }
    expect("a1", "a3", "a5") { where { query.books.any { title.isNotNull() } } }
    // A missing title is not unequal to alpha, so no book of a1 or a2 is, and none of a4's is either.
    expect("a3") { where { query.books.any { title neq "alpha" } } }
    expect("a1", "a2", "a4", "a5") { where { query.books.none { title neq "alpha" } } }
    expect("a1") { where { query.books.any { pages gt 7L } } }
    expect("a2", "a3", "a4", "a5") { where { query.books.none { pages gt 7L } } }
    expect("a5") { where { query.books.any { title eq "alpha"; pages.isNull() } } }

    // No related node at all.
    expect("a1", "a2", "a3", "a5") { where { query.books.any { } } }
    expect("a4") { where { query.books.none { } } }

    // A relationship of the related node.
    expect("a1") { where { query.books.any { tags.name eq "red" } } }
    expect("a2", "a3", "a4", "a5") { where { query.books.none { tags.name eq "red" } } }
    expect("a2", "a5") { where { query.books.any { tags.name.isNull() } } }
    expect("a5") { where { query.books.any { title eq "alpha"; tags.name eq "blue" } } }
    expect("a1", "a3", "a4") { where { query.books.none { tags.name.isNull() } } }

    // Either way, and from a node to itself.
    expect("a1") { where { query.neighbours.any { id eq "a2" } } }
    expect("a2") { where { query.neighbours.any { id eq "a1" } } }
    expect("a5") { where { query.neighbours.any { id eq "a5" } } }
    expect("a1", "a2", "a5") { where { query.neighbours.any { } } }
    expect("a3", "a4") { where { query.neighbours.none { } } }

    // Against the relationship's direction.
    expect("a1") { where { query.fans.any { name eq "ann" } } }
    expect("a3") { where { query.fans.any { name.isNull() } } }
    expect("a2", "a4", "a5") { where { query.fans.none { } } }

    // Along a path of two hops.
    expect("a5") { where { query.marks.any { name eq "blue" } } }
    expect("a2", "a5") { where { query.marks.any { name.isNull() } } }
    expect("a1", "a2", "a5") { where { query.marks.any { } } }
    expect("a3", "a4") { where { query.marks.none { } } }

    // Within an `or`.
    expect("a1", "a4") {
        where {
            anyOf {
                query.fans.name eq "ann"
                query.books.none { }
            }
        }
    }
}

private fun buildGom(pm: PersistenceManager, registry: SubtypeRegistry) =
    StatelessGraphObjectManager(pm, Neo4jObjectMapper.instance, registry)

/** Indexes on the properties the conditions test, so a store that reads a filter through one is tested with it. */
private fun ensureIndexes(pm: PersistenceManager) {
    listOf("RrBook" to "title", "RrTag" to "name", "RrReader" to "name").forEach { (label, property) -> pm.indexes.ensure(RangeIndexSpec(label, property)) }
}

class RelatedRowsNeo4jTest {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-related-rows", type = DatabaseType.NEO4J,
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
    fun `a condition on a related node finds what it finds on Neo4j`() = verify(gom)
}

@PostgresTests
class RelatedRowsPostgresTest {
    companion object {
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            val store = PostgresTestDatabase.graphManager("relatedrows", registry)
            PostgresTestDatabase.clear(store)
            val pm = PostgresTestDatabase.fixtures(store)
            ensureIndexes(pm)
            PostgresTestDatabase.seed(pm, SEED)
            gom = buildGom(pm, registry)
        }
    }

    @Test
    fun `a condition on a related node finds what Neo4j finds on Postgres`() = verify(gom)
}

@OracleTests
class RelatedRowsOracleTest {
    companion object {
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            val store = OracleTestDatabase.graphManager("relatedrows", registry)
            OracleTestDatabase.clear(store)
            val pm = OracleTestDatabase.fixtures(store)
            ensureIndexes(pm)
            OracleTestDatabase.seed(pm, SEED)
            gom = buildGom(pm, registry)
        }
    }

    @Test
    fun `a condition on a related node finds what Neo4j finds on Oracle`() = verify(gom)
}

class RelatedRowsSqliteTest {
    companion object {
        private lateinit var gom: StatelessGraphObjectManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            val store = SqliteTestDatabase.graphManager("relatedrows", registry)
            SqliteTestDatabase.clear(store)
            val pm = SqliteTestDatabase.fixtures(store)
            ensureIndexes(pm)
            SqliteTestDatabase.seed(pm, SEED)
            gom = buildGom(pm, registry)
        }
    }

    @Test
    fun `a condition on a related node finds what Neo4j finds on SQLite`() = verify(gom)
}
