package org.drivine.cypher

import org.drivine.connection.DatabaseType
import org.drivine.cypher.spec.CypherSpec
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.SharedNeo4j
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.testcontainers.containers.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A Cypher statement written for a graph engine runs on a SQL store, compiled to its SQL, and gives the
 * same rows: the statements of the spec's `cypher/queries.yaml` are run on Neo4j, Postgres and Oracle over
 * its graph, and give the rows it says.
 */
abstract class CypherQueryContract {

    abstract val pm: PersistenceManager

    /** Writes the graph a Cypher `CREATE` describes. */
    abstract fun seed(cypher: String)

    /** Removes the graph the tests wrote. */
    abstract fun clear()

    /** Whether the statement under test is compiled to SQL: what only compiling refuses is asked of it alone. */
    open val compiles: Boolean = false

    private fun query(cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(cypher.trimIndent())).bind(parameters))

    @TestFactory
    fun spec() = CypherSpec.load("queries.yaml").let { spec ->
        spec.tests(CypherSpec.storeOf(pm.type), before = { clear(); seed(requireNotNull(spec.graph)) }) { step -> query(step.cypher, step.parameters) }
    }

    @Test
    fun `collect(DISTINCT) beside grouping keys, other aggregates, an order, a page and a variable outside its aggregates`() {
        // Not in the spec yet: distinctFirst's grouping WITH, and the projection over it.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", 4L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p.name, count(*) + size(collect(DISTINCT m.title)) ORDER BY count(*) DESC, p.name SKIP 1 LIMIT 1"),
        )
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Keanu", 3L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) WITH p, collect(DISTINCT m) AS ms WHERE size(ms) > 1 RETURN p.name, size(ms) ORDER BY p.name"),
        )
        assertEquals(
            listOf(listOf("Carrie", 1969L), listOf("Keanu", 1967L), listOf("Laurence", 1962L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) WITH p, size(collect(DISTINCT m.title)) + p.born AS x RETURN p.name, x ORDER BY p.name"),
        )
    }

    @Test
    fun `collect(DISTINCT) in a DISTINCT projection ordered by a key's property or an aggregate no item is`() {
        // Not in the spec yet: the projection over distinctFirst's grouping WITH orders by what that WITH holds.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Keanu", 3L), listOf("Laurence", 1L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) WITH DISTINCT p, size(collect(DISTINCT m.title)) AS n ORDER BY p.born DESC RETURN p.name, n"),
        )
        assertEquals(
            listOf(listOf("Laurence", 1L), listOf("Carrie", 2L), listOf("Keanu", 3L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN DISTINCT p.name, size(collect(DISTINCT m.title)) ORDER BY count(*)"),
        )
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Laurence", 1L), listOf("Keanu", 3L)),
            query(
                "MATCH (p:Person)-[:ACTED_IN]->(m) WITH p.name AS name, m.title AS t " +
                    "RETURN name AS nm, size(collect(DISTINCT t)) ORDER BY any(x IN collect(DISTINCT t) WHERE x STARTS WITH 'J' AND name = 'Keanu'), nm"
            ),
        )
    }

    @Test
    fun `collect(DISTINCT) beside an item named after a variable another item is, ordered by that name`() {
        // Not in the spec yet: in an order term, an item's name is that item, not the variable it shadows.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", 2L), listOf("Keanu", 3L), listOf("Laurence", 1L)),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p AS person, p.name AS p, size(collect(DISTINCT m.title)) ORDER BY toLower(p)")
                .map { (it as List<*>).drop(1) },
        )
    }

    @Test
    fun `an item of the relationships collect gathers is read in the projection that gathers them`() {
        // Not in the spec yet: Oracle reads no aggregate from a subquery, so the item is read from what collect gathered.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", "Trinity"), listOf("Laurence", "Morpheus")),
            query("MATCH (p:Person)-[r:ACTED_IN]->() WHERE p.name <> 'Keanu' RETURN p.name, collect(r)[0].role ORDER BY p.name"),
        )
        assertEquals(
            listOf(listOf(4L, "Morpheus", "Trinity")),
            query(
                "MATCH (:Person {name: 'Laurence'})-[r:ACTED_IN]->() MATCH (:Person {name: 'Carrie'})-[s:ACTED_IN]->() " +
                    "RETURN size(collect(r) + collect(s)), (collect(r) + collect(s))[0].role, (collect(r) + collect(s))[-1].role"
            ),
        )
        assertEquals(
            listOf(listOf(true, true)),
            query("MATCH (:Person {name: 'Laurence'})-[r:ACTED_IN]->() WITH collect(r) AS rs RETURN rs[9] IS NULL, rs[-9] IS NULL"),
        )
    }

    @Test
    fun `collect(DISTINCT) under a comprehension, a quantifier or a reduce that names a grouping key in its body`() {
        // Not in the spec yet: distinctFirst's grouping WITH keeps each variable such a body names, outside the aggregate.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", 2L, false, 12L), listOf("Keanu", 3L, false, 15L), listOf("Laurence", 1L, false, 8L)),
            query(
                "MATCH (p:Person)-[:ACTED_IN]->(m) WITH p.name AS name, m.title AS t " +
                    "RETURN name, size([x IN collect(DISTINCT t) WHERE x <> name]), any(x IN collect(DISTINCT t) WHERE x = name), " +
                    "reduce(n = 0, x IN collect(DISTINCT t) | n + size(name)) ORDER BY name"
            ),
        )
    }

    @Test
    fun `collect(DISTINCT) beside a pattern or a subquery that names a grouping key`() {
        // Not in the spec yet: distinctFirst's grouping WITH keeps each variable a pattern or a subquery names, outside the aggregate.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Carrie", 2L, 2L, false), listOf("Keanu", 4L, 4L, true), listOf("Laurence", 2L, 2L, true)),
            query(
                "MATCH (p:Person)-[:ACTED_IN]->(m) WITH p, size(collect(DISTINCT m.title)) + COUNT { (p)-[:KNOWS]->() } AS x, " +
                    "size([(p)-[:KNOWS]->(f) | f.name] + collect(DISTINCT m.title)) AS y, " +
                    "size(collect(DISTINCT m.title)) > 2 OR EXISTS { (p)-[:KNOWS]->(:Person {name: 'Keanu'}) } AS z " +
                    "RETURN p.name, x, y, z ORDER BY p.name"
            ),
        )
    }

    @Test
    fun `collect(DISTINCT) of dates keeps each once, as dates`() {
        // Not in the spec yet: what distinctFirst keeps of a collection is still of the kind collect gathered.
        val dates = "UNWIND [date('2020-01-01'), date('2020-01-01'), date('2021-05-01')] AS d"
        assertEquals(listOf(listOf(2020L, 2021L)), query("$dates WITH collect(DISTINCT d) AS ds RETURN [x IN ds | x.year]"))
        assertEquals(listOf(5L), query("$dates RETURN collect(DISTINCT d)[1].month"))
    }

    @Test
    fun `an item of a pattern comprehension's relationships is compared by its id`() {
        // Not in the spec yet: a pattern comprehension gathers their properties beside their ids, as collect does, but in a subquery.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf(false, true, true)),
            query(
                "MATCH (a:Person {name: 'Keanu'}) WITH [(a)-[r:ACTED_IN {role: 'Neo'}]->() | r] AS rs, a " +
                    "RETURN [[(a)-[r:ACTED_IN {role: 'Neo'}]->() | r][0] = [(a)-[r:ACTED_IN {role: 'Neo'}]->() | r][1], " +
                    "[(a)-[r:ACTED_IN {role: 'Neo'}]->() | r][1] IN rs, [(a)-[r:ACTED_IN {role: 'Neo'}]->() | r][0].role = 'Neo']"
            ),
        )
    }

    @Test
    fun `a list comprehension over nodes or relationships that keeps them gives a list of them, compared by id`() {
        // Not in the spec yet: what entityComprehension keeps, with no projection or one of the item, is still a list of nodes or relationships.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf(true, false, 1L, false, listOf("Neo", "Neo"))),
            query(
                "MATCH (:Person {name: 'Keanu'})-[r:ACTED_IN {role: 'Neo'}]->() WITH collect(r) AS rs " +
                    "RETURN [[x IN rs WHERE x <> rs[0]][0] = rs[1], [x IN rs | x][0] = rs[1], size([x IN rs WHERE x = rs[0] | x]), " +
                    "rs[0] IN [x IN rs WHERE x <> rs[0]], [x IN rs | x.role]]"
            ),
        )
        assertEquals(
            listOf(listOf(true, true, listOf("Carrie"))),
            query(
                "MATCH p = (:Person {name: 'Laurence'})-[:KNOWS*2]->(b) " +
                    "RETURN [[n IN nodes(p) WHERE n.name <> 'Laurence'][0] = nodes(p)[1], b IN [n IN nodes(p) | n], [n IN nodes(p) WHERE n = b | n.name]]"
            ),
        )
    }

    @Test
    fun `a path's nodes or relationships added together are a list of them, and of no path, null`() {
        // Not in the spec yet: lists of nodes no collect gathers, added, indexed and iterated by id; over a null list, null.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf(6L, true, false, listOf("Laurence", "Keanu", "Carrie", "Laurence", "Keanu", "Carrie"), true, 4L)),
            query(
                "MATCH p = (:Person {name: 'Laurence'})-[:KNOWS*2]->() " +
                    "RETURN [size(nodes(p) + nodes(p)), (nodes(p) + nodes(p))[3] = nodes(p)[0], (nodes(p) + nodes(p))[3] = nodes(p)[1], " +
                    "[n IN nodes(p) + nodes(p) | n.name], relationships(p)[1] IN relationships(p) + relationships(p), size(relationships(p) + relationships(p))]"
            ),
        )
        assertEquals(
            listOf(listOf(null, null, null)),
            query("OPTIONAL MATCH p = (:Nope)-->() RETURN [[n IN nodes(p) | n.name], [n IN nodes(p) WHERE true], nodes(p)[0]]"),
        )
    }

    @Test
    fun `randomUUID gives a new UUID each time`() {
        val (a, b) = query("UNWIND [1, 2] AS x RETURN randomUUID()").map { it as String }
        assertTrue(a != b && Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(a), "$a $b")
    }

    @Test
    fun `toString of a float too small for Oracle's NUMBER writes it`() {
        // Oracle's NUMBER reads it as 0, a whole number. Its text differs by store, so the spec has no case for it.
        assertEquals(1e-200, (query("RETURN toString(1.0E-200)").single() as String).toDouble())
    }

    @Test
    fun `a day and a moment are their ISO text`() {
        // A graph engine's driver gives a day and a moment as values of its own.
        if (!compiles) return
        val (day, moment) = query("RETURN [date(), localdatetime()]").single() as List<*>
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(day as String), day)
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3}|\\.\\d{6})?").matches(moment as String), moment)
    }
}

class CypherQueryNeo4jTest : CypherQueryContract() {
    companion object {
        @JvmField
        val container: Neo4jContainer<*> = SharedNeo4j.forClass()

        private lateinit var provider: Neo4jConnectionProvider
        lateinit var manager: NonTransactionalPersistenceManager

        @JvmStatic @BeforeAll
        fun setup() {
            val registry = SubtypeRegistry()
            provider = Neo4jConnectionProvider(
                name = "neo-cypher", type = DatabaseType.NEO4J,
                host = container.host, port = container.getMappedPort(7687),
                user = "neo4j", password = SharedNeo4j.PASSWORD, database = "neo4j",
                config = emptyMap(), subtypeRegistry = registry, cypherDialect = CypherDialect.NEO4J_5,
            )
            manager = NonTransactionalPersistenceManager(provider, "neo4j", DatabaseType.NEO4J, registry)
        }

        @JvmStatic @AfterAll
        fun teardown() = provider.end()
    }

    override val pm: PersistenceManager get() = manager

    override fun seed(cypher: String) = manager.execute(QuerySpecification.withStatement(cypher.trimIndent()))

    override fun clear() = manager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
}

@PostgresTests
class CypherQueryPostgresTest : CypherQueryContract() {
    private val manager = PostgresTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun seed(cypher: String) = PostgresTestDatabase.seed(manager, cypher)

    override fun clear() = PostgresTestDatabase.clear(manager)
}

@OracleTests
class CypherQueryOracleTest : CypherQueryContract() {
    private val manager = OracleTestDatabase.graphManager("cypher")

    override val pm: PersistenceManager get() = manager

    override val compiles = true

    override fun seed(cypher: String) = OracleTestDatabase.seed(manager, cypher)

    override fun clear() = OracleTestDatabase.clear(manager)
}
