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
