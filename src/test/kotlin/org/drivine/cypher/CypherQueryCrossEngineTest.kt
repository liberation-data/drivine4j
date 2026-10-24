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
    fun `a float parameter that is a whole number stays a float nested in maps and lists, and past 1e15`() {
        val p = mapOf("a" to listOf(1.0, 2.5, mapOf("b" to -3.0)), "n" to 4L)
        assertEquals(listOf(p), query("RETURN \$p", mapOf("p" to p)))
        assertEquals(listOf(listOf(-3.0, "-2.0")), query("RETURN [\$p.a[2].b, toString(\$f)]", mapOf("p" to p, "f" to -2.0)))
        // Postgres's jsonb reads a float past a long's range back as a whole number, which drivine reads as a long.
        if (pm.type != DatabaseType.POSTGRES) assertEquals(listOf(listOf(1e20, 2e20)), query("RETURN [\$big, \$big * 2]", mapOf("big" to 1e20)))
    }

    @Test
    fun `a float parameter that is a whole number equals the whole number stored`() {
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        val matrix = listOf("The Matrix")
        assertEquals(matrix, query("MATCH (m:Movie {released: \$r}) RETURN m.title", mapOf("r" to 1999.0)))
        assertEquals(matrix, query("MATCH (m:Movie) WHERE m.released = \$r RETURN m.title", mapOf("r" to 1999.0)))
        assertEquals(matrix, query("MATCH (m:Movie) WHERE m.released IN \$rs RETURN m.title", mapOf("rs" to listOf(1999.0, 1.5))))
        assertEquals(listOf("Keanu"), query("MATCH (p:Person) WHERE p.born >= \$b AND p.born < \$b + 1 RETURN p.name", mapOf("b" to 1964.0)))
        assertEquals(listOf(true), query("RETURN \$f = 2", mapOf("f" to 2.0)))
    }

    @Test
    fun `a float parameter that is a whole number stays a float in a UNION, and in a parameter past 32 KB`() {
        assertEquals(listOf(2.0, 1.5), query("RETURN \$f AS x UNION ALL RETURN 1.5 AS x", mapOf("f" to 2.0)))
        assertEquals(listOf(2.0), query("RETURN \$doc.n", mapOf("doc" to mapOf("text" to "y".repeat(40_000), "n" to 2.0))))
    }

    @Test
    fun `a map parameter keyed as extended JSON writes a double comes back a map`() {
        val m = mapOf("\$numberDouble" to "1")
        assertEquals(listOf(m), query("RETURN \$m", mapOf("m" to m)))
        // Beside a float parameter whose text is the same as the map's.
        val same = mapOf("\$numberDouble" to "2.0")
        assertEquals(listOf(listOf(2.0, same)), query("RETURN [\$f, \$m]", mapOf("f" to 2.0, "m" to same)))
    }

    @Test
    fun `a float that is a whole number, collected once each, stays a float`() {
        // Not in the spec yet: what distinctFirst keeps of a collection is the items collect gathered, not their text read back.
        assertEquals(listOf(listOf(1.0, 2.5)), query("UNWIND [1.0, 2.5, 1.0] AS x RETURN collect(DISTINCT x)"))
        assertEquals(listOf(listOf(2L, listOf(2.0)), listOf(1L, listOf(2.0))), query("UNWIND [1, 2] AS x RETURN x, collect(DISTINCT \$f) ORDER BY x DESC", mapOf("f" to 2.0)))
    }

    @Test
    fun `an item past 4,000 bytes, and a map with its keys in another order, collected once each`() {
        // Not in the spec yet: Oracle keeps each item once as JSON compares items, not as their text does.
        val big = "y".repeat(5_000)
        assertEquals(listOf(listOf(listOf(big), "a")), query("UNWIND [[\$b], [\$b], 'a'] AS x RETURN collect(DISTINCT x)", mapOf("b" to big)))
        assertEquals(listOf(listOf(mapOf("a" to 1L, "b" to 2L))), query("UNWIND [{a: 1, b: 2}, {b: 2, a: 1}] AS x RETURN collect(DISTINCT x)"))
    }

    @Test
    fun `a map parameter keyed as extended JSON writes a value, beside a float that is a whole number in the same parameter, comes back a map`() {
        val p = mapOf("a" to 2.5, "m" to mapOf("\$numberDouble" to "1"), "l" to mapOf("\$numberLong" to "5"))
        assertEquals<List<Any?>>(listOf(p), query("RETURN \$p", mapOf("p" to p + ("b" to 2.0))).map { (it as Map<*, *>) - "b" })
    }

    @Test
    fun `a walk sorts by coalesce of an aggregate collected once each, and a DISTINCT projection by coalesce of an item`() {
        // Not in the spec yet: an order term that reads an aggregate distinctFirst worked out first is carried out, beside a walk, as #162's sort was.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        assertEquals(
            listOf(listOf("Johnny Mnemonic", 1L), listOf("The Matrix", 3L), listOf("The Matrix Reloaded", 2L), listOf("Keanu", 1L), listOf("Carrie", 1L)),
            query("MATCH (:Person {name: 'Laurence'})-[*]->(b) RETURN coalesce(b.name, b.title) AS n, count(*) ORDER BY coalesce(head(collect(DISTINCT b.born)), 0), n"),
        )
        assertEquals(
            listOf(listOf("Carrie", listOf("The Matrix", "The Matrix Reloaded")), listOf("Laurence", listOf("The Matrix"))),
            query("MATCH (p:Person)-[:ACTED_IN]->(m) RETURN DISTINCT p.name AS n, collect(DISTINCT m.title) AS ts ORDER BY coalesce(ts[2], 'zz'), n SKIP 1 LIMIT 2"),
        )
    }

    @Test
    fun `toString writes a float with its fraction, an integer and a text as they are`() {
        assertEquals(listOf(listOf("2.5", "3", "a", "4.0")), query("RETURN [toString(2.5), toString(3), toString('a'), toString(\$f * 2)]", mapOf("f" to 2.0)))
        // A float too small for Oracle's NUMBER, which reads it as 0, a whole number.
        assertEquals(1e-200, (query("RETURN toString(1.0E-200)").single() as String).toDouble())
    }

    @Test
    fun `a walk returns and sorts by toString of a long expression of where it ends`() {
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        // On Oracle toString reads its argument twice, so one this long is bound in a subquery: beside a walk, as #162's sort was.
        val walk = "MATCH (:Person {name: 'Laurence'})-[*]->(b) RETURN toString(coalesce(b.name, b.title)) AS n"
        val rows = listOf("Carrie", "Johnny Mnemonic", "Keanu", "The Matrix", "The Matrix", "The Matrix", "The Matrix Reloaded", "The Matrix Reloaded")
        assertEquals(rows, query(walk).map { it as String }.sorted())
        assertEquals(rows, query("$walk ORDER BY n"))
    }

    @Test
    fun `a walk sorted by coalesce in a WITH keeps that order, skipped and limited, in the query that reads it`() {
        // Not in the spec yet: a WITH carries its sort term out as #162's RETURN does, and the query after it reads that term.
        clear()
        seed(requireNotNull(CypherSpec.load("queries.yaml").graph))
        val walk = "MATCH (:Person {name: 'Laurence'})-[*]->(b) WITH b ORDER BY coalesce(b.name, b.title) DESC, b.released SKIP 1 LIMIT 4"
        assertEquals(listOf("The Matrix Reloaded", "The Matrix", "The Matrix", "The Matrix"), query("$walk RETURN coalesce(b.name, b.title)"))
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
