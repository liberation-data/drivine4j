package org.drivine.cypher

import org.drivine.cypher.sql.CompiledCypher
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.manager.NonTransactionalPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.postgres.PostgresTestDatabase
import org.drivine.postgres.PostgresTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.schema.UniquenessConstraintSpec
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A node matched by a property its index is over, the value an unwound row's (drivine4j#617): compared as JSON inside a `CASE`
 * alone, which no index answers, so a batch of relationships read every node of the label for each row. Beside it now stands a
 * test the index answers, for a value of any kind, that leaves out no row the comparison keeps: a text, a number, a float equal
 * to a whole one, a truth value, a list, null and a missing key each give the rows they gave. On Oracle, a parameter's value
 * had no such test either.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PropertyKeyIndexContract {

    abstract val pm: NonTransactionalPersistenceManager
    abstract val cypher: PersistenceManager

    /** The statement as the store compiles it. */
    abstract fun compiled(cypher: String, parameters: Map<String, Any?>): CompiledCypher

    /** The plan the store makes for [compiled], as it writes it. */
    abstract fun plan(compiled: CompiledCypher): String

    /** Whether [plan] finds the nodes by the index over their `id`, not by reading every node of the label. */
    abstract fun probesIndex(plan: String): Boolean

    open fun analyze() {}

    /** Whether a `WHERE` comparing the property with a row's value probes the index too, as a pattern's does. */
    open val whereProbesIndex = true

    /** Whether the store keeps an infinity: Postgres's JSON holds none. */
    open val storesInfinity = true

    @BeforeAll
    fun seed() {
        org.drivine.test.SqlCypherFixtures.clear(pm)
        pm.constraints.ensure(UniquenessConstraintSpec("Person", "id"))
        pm.constraints.ensure(UniquenessConstraintSpec(IMPORT_LABEL, IMPORT_ID))
        query("UNWIND range(0, ${IMPORTED - 1}) AS k CREATE (:`$IMPORT_LABEL` {`$IMPORT_ID`: k})")
        query("UNWIND range(0, ${NODES - 1}) AS k CREATE (:Person {id: 'p' + toString(k), k: k})")
        query("CREATE (:Person {id: 7, k: -1}), (:Person {id: 8.0, k: -2}), (:Person {id: true, k: -3}), (:Person {id: [1, 2], k: -4})")
        query("CREATE (:Person {id: ['a', 'b'], k: -5}), (:Person {id: [], k: -6}), (:Person {id: [1], k: -7}), (:Person {id: [[1, 2]], k: -8})")
        query("CREATE (:Person {id: 0.1, k: -9}), (:Person {id: 1.0e20, k: -10}), (:Person {id: -7, k: -11}), (:Person {id: 0, k: -12})")
        query("CREATE (:Person {id: '', k: -13}), (:Person {id: \$big, k: -14}), (:Person {id: \$long, k: -15})", mapOf("big" to BIG, "long" to LONG))
        analyze()
    }

    private fun query(statement: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        cypher.query(QuerySpecification.withStatement(CypherStatement(statement)).bind(parameters))

    @Test
    fun `a row's value of each kind finds the nodes it is equal to, as JSON compares them`() {
        // 7.0 is 7, and 8 is 8.0, as in Cypher; a list, empty, of one item or of lists, is equal to the same list only, not to its item or
        // to its items in another order; null and a missing key are equal to nothing.
        val rows = listOf(
            mapOf("s" to "p5"), mapOf("s" to 7), mapOf("s" to 7.0), mapOf("s" to 8), mapOf("s" to true), mapOf("s" to listOf(1, 2)),
            mapOf("s" to null), emptyMap<String, Any?>(), mapOf("s" to "nobody"), mapOf("s" to "7"),
            mapOf("s" to listOf("a", "b")), mapOf("s" to emptyList<Any>()), mapOf("s" to listOf(1)), mapOf("s" to listOf(listOf(1, 2))),
            mapOf("s" to listOf(2, 1)), mapOf("s" to 1),
        )
        val expected = listOf(-8L, -7L, -6L, -5L, -4L, -3L, -2L, -1L, -1L, 5L)
        assertEquals(expected, query("UNWIND \$rows AS row MATCH (a:Person {id: row.s}) RETURN a.k ORDER BY a.k", mapOf("rows" to rows)))
        // The same, compared in a WHERE.
        assertEquals(expected, query("UNWIND \$rows AS row MATCH (a:Person) WHERE a.id = row.s RETURN a.k ORDER BY a.k", mapOf("rows" to rows)))
        // A list on its own: beside a null row, Oracle has been seen to plan the read otherwise and find lists it finds no other way.
        val lists = listOf(mapOf("s" to listOf(1, 2)), mapOf("s" to emptyList<Any>()))
        assertEquals(listOf(-6L, -4L), query("UNWIND \$rows AS row MATCH (a:Person {id: row.s}) RETURN a.k ORDER BY a.k", mapOf("rows" to lists)))
    }

    @Test
    fun `a row's number, empty text or long text finds the node it is equal to`() {
        // -0.0 is 0, as in Cypher. Oracle's index holds no text for an empty or a long one, which JSON_VALUE reads as null.
        val rows = listOf(0.1, 1.0e20, -7, -0.0, BIG, "", LONG).map { mapOf("s" to it) }
        val expected = listOf(-15L, -14L, -13L, -12L, -11L, -10L, -9L)
        assertEquals(expected, query("UNWIND \$rows AS row MATCH (a:Person {id: row.s}) RETURN a.k ORDER BY a.k", mapOf("rows" to rows)))
        // Each on its own, as rows that hold no list.
        for ((row, k) in rows.zip(listOf(-9L, -10L, -11L, -12L, -14L, -13L, -15L))) {
            assertEquals(listOf(k), query("UNWIND \$rows AS row MATCH (a:Person {id: row.s}) RETURN a.k", mapOf("rows" to listOf(row))), "$row")
        }
        // A parameter's or a written empty or long text.
        assertEquals(listOf(-13L), query("MATCH (a:Person {id: \$s}) RETURN a.k", mapOf("s" to "")))
        assertEquals(listOf(-13L), query("MATCH (a:Person {id: ''}) RETURN a.k"))
        assertEquals(listOf(-15L), query("MATCH (a:Person {id: \$s}) RETURN a.k", mapOf("s" to LONG)))
    }

    @Test
    fun `a text the statement works out, or an item of a list it writes, finds the node it is equal to though empty or long`() {
        // Not an empty text worked out: on Oracle, a text function gives null for one, as SQL holds no empty text.
        val rows = mapOf("rows" to listOf(LONG, "p5").map { mapOf("s" to it) })
        assertEquals(listOf(-15L, 5L), query("UNWIND \$rows AS row MATCH (a:Person {id: toString(row.s)}) RETURN a.k ORDER BY a.k", rows))
        assertEquals(listOf(-13L, 5L), query("UNWIND ['', 'p5'] AS s MATCH (a:Person {id: s}) RETURN a.k ORDER BY a.k"))
    }

    @Test
    fun `a list the statement writes finds what each of its rows is equal to, a list, a parameter's list or a function's among them`() {
        val statement = "UNWIND [{s: 'p5'}, {s: [1, 2]}, {s: \$l}, {s: range(7, 8)}] AS row MATCH (a:Person {id: row.s}) RETURN a.k ORDER BY a.k"
        assertEquals(listOf(-6L, -4L, 5L), query(statement, mapOf("l" to emptyList<Any>())))
        assertEquals(listOf(-4L, 5L), query("UNWIND [{s: 'p5'}, {s: \$l}] AS row MATCH (a:Person {id: row.s}) RETURN a.k ORDER BY a.k", mapOf("l" to listOf(1, 2))))
        assertEquals(listOf(-4L, 5L), query("UNWIND [[1, 2], 'p5'] AS s MATCH (a:Person {id: s}) RETURN a.k ORDER BY a.k"))
        assertEquals(listOf(-4L), query("UNWIND [{s: datetime('2024-03-15T10:00:00Z')}, {s: range(1, 2)}] AS row MATCH (a:Person {id: row.s}) RETURN a.k"))
        assertEquals(listOf(5L), query("UNWIND [{s: 'p5'}, {s: null}, {t: [1, 2]}, {s: 7.5}] AS row MATCH (a:Person {id: row.s}) RETURN a.k"))
        // The items themselves, of a parameter's list or of one written, and parameters that hold no list among literals.
        val scalars = mapOf("ids" to listOf("p5", 7.0, 8, true, BIG, null, 0.1), "a" to 7.0, "b" to 8, "c" to true, "d" to BIG)
        val found = listOf(-14L, -9L, -3L, -2L, -1L, 5L)
        assertEquals(found, query("UNWIND \$ids AS id MATCH (a:Person {id: id}) RETURN a.k ORDER BY a.k", scalars))
        assertEquals(found - -9L, query("UNWIND [\$a, \$b, \$c, \$d, 'p5'] AS s MATCH (a:Person {id: s}) RETURN a.k ORDER BY a.k", scalars))
        assertEquals(found - -9L, query("UNWIND [{s: \$a}, {s: \$b}, {s: \$c}, {s: \$d}, {s: 'p5'}] AS row MATCH (a:Person {id: row.s}) RETURN a.k ORDER BY a.k", scalars))
        assertEquals(listOf(-6L, -4L, 5L), query("UNWIND \$ids AS id MATCH (a:Person {id: id}) RETURN a.k ORDER BY a.k", mapOf("ids" to listOf("p5", listOf(1, 2), emptyList<Any>()))))
    }

    @Test
    fun `a key of a map a row holds finds what it is equal to, where the map is missing, null or holds a list`() {
        val statement = "UNWIND \$rows AS row MATCH (a:Person {id: row.a.b}) RETURN a.k ORDER BY a.k"
        val lists = listOf(mapOf("a" to mapOf("b" to "p5")), mapOf("a" to mapOf("b" to listOf(1, 2))), mapOf("a" to null), emptyMap(), mapOf("a" to emptyMap<String, Any>()))
        assertEquals(listOf(-4L, 5L), query(statement, mapOf("rows" to lists)))
        val scalars = listOf(mapOf("a" to mapOf("b" to "p5")), mapOf("a" to null), emptyMap(), mapOf("a" to mapOf("c" to listOf(1))))
        assertEquals(listOf(5L), query(statement, mapOf("rows" to scalars)))
        // A key read of a list or a text, which Neo4j refuses: what the comparison alone gives, as a WHERE compares it.
        val where = "UNWIND \$rows AS row MATCH (a:Person) WHERE a.id = row.a.b RETURN a.k ORDER BY a.k"
        val odd = mapOf("rows" to listOf(mapOf("a" to listOf(mapOf("b" to listOf(1, 2)))), mapOf("a" to "text"), mapOf("a" to mapOf("b" to "p5"))))
        assertEquals(query(where, odd), query(statement, odd))
    }

    @Test
    fun `a truth value or a number finds no text that looks like it, nor a text the truth value or number, by = or by its reverse or by not-equal`() {
        query("CREATE (:Odd {id: 'true', k: 1}), (:Odd {id: true, k: 2}), (:Odd {id: '7', k: 3}), (:Odd {id: 7, k: 4})")
        val rows = mapOf("rows" to listOf(true, "true", 7, "7", null, 7.0).mapIndexed { i, s -> mapOf("i" to i, "s" to s) })
        val equal = listOf(2L, 11L, 24L, 33L, 54L)
        assertEquals(equal, query("UNWIND \$rows AS row MATCH (a:Odd {id: row.s}) RETURN row.i * 10 + a.k AS p ORDER BY p", rows))
        assertEquals(equal, query("UNWIND \$rows AS row MATCH (a:Odd) WHERE a.id = row.s RETURN row.i * 10 + a.k AS p ORDER BY p", rows))
        assertEquals(equal, query("UNWIND \$rows AS row MATCH (a:Odd) WHERE row.s = a.id RETURN row.i * 10 + a.k AS p ORDER BY p", rows))
        // Not equal: a null row is equal to nothing and unequal to nothing.
        val unequal = listOf(1L, 3L, 4L, 12L, 13L, 14L, 21L, 22L, 23L, 31L, 32L, 34L, 51L, 52L, 53L)
        assertEquals(unequal, query("UNWIND \$rows AS row MATCH (a:Odd) WHERE a.id <> row.s RETURN row.i * 10 + a.k AS p ORDER BY p", rows))
        assertEquals(unequal, query("UNWIND \$rows AS row MATCH (a:Odd) WHERE NOT a.id = row.s RETURN row.i * 10 + a.k AS p ORDER BY p", rows))
        if (!storesInfinity) return
        // An infinity finds the one stored, and NaN is equal to nothing, itself included.
        val nonFinite = mapOf("i" to Double.POSITIVE_INFINITY, "n" to Double.NEGATIVE_INFINITY, "nan" to Double.NaN)
        query("CREATE (:Odd {id: \$i, k: 5}), (:Odd {id: \$n, k: 6}), (:Odd {id: \$nan, k: 7})", nonFinite)
        val infinities = mapOf("rows" to listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN).map { mapOf("s" to it) })
        assertEquals(listOf(5L, 6L), query("UNWIND \$rows AS row MATCH (a:Odd {id: row.s}) RETURN a.k ORDER BY a.k", infinities))
        assertEquals(listOf(5L, 6L), query("UNWIND \$rows AS row MATCH (a:Odd) WHERE a.id = row.s RETURN a.k ORDER BY a.k", infinities))
    }

    @Test
    fun `a MERGE of each row finds the node it is equal to, and makes none`() {
        val rows = listOf("p3", 7.0, 8, listOf(1, 2), true, 0.1, "").map { mapOf("s" to it) }
        val statement = "UNWIND \$rows AS row MERGE (a:Person {id: row.s}) RETURN a.k ORDER BY a.k"
        assertEquals(listOf(-13L, -9L, -4L, -3L, -2L, -1L, 3L), query(statement, mapOf("rows" to rows)))
        assertEquals(listOf((NODES + 15).toLong()), query("MATCH (a:Person) RETURN count(a)"))
    }

    @Test
    fun `a node at a relationship's end is found by a row's or a parameter's value`() {
        query("MATCH (a:Person {id: 'p10'}), (b:Person {id: 'p11'}) CREATE (a)-[:LINKS]->(b)")
        assertEquals(listOf(10L), query("MATCH (a:Person)-[:LINKS]->(b:Person {id: \$s}) RETURN a.k", mapOf("s" to "p11")))
        assertEquals(listOf(10L), query("MATCH (a:Person)-[:LINKS]->(b:Person {id: 'p11'}) RETURN a.k"))
        assertEquals(listOf(10L), query("UNWIND \$rows AS row MATCH (a:Person)-[:LINKS]->(b:Person {id: row.s}) RETURN a.k", mapOf("rows" to listOf(mapOf("s" to "p11"), mapOf("s" to "p12")))))
        assertEquals(listOf(0L), query("MATCH (a:Person)-[:LINKS]->(b:Person {id: \$s}) RETURN count(a)", mapOf("s" to "p12")))
    }

    @Test
    fun `each row of an UNWIND finds its node through the index, not by reading every node of the label`() {
        val rows = mapOf("rows" to (0 until 100).map { mapOf("s" to "p${it * 7}", "d" to "p${it * 7 + 1}") }, "ids" to (0 until 100).map { "p${it * 7}" })
        for (statement in listOf(
            "UNWIND \$rows AS row MATCH (a:Person {id: row.s}) RETURN a.k",
            "UNWIND \$ids AS id MATCH (a:Person {id: id}) RETURN a.k",
            "UNWIND \$rows AS row MATCH (a:Person {id: row.s}) MATCH (b:Person {id: row.d}) RETURN a.k, b.k",
        ) + listOfNotNull("UNWIND \$rows AS row MATCH (a:Person) WHERE a.id = row.s RETURN a.k".takeIf { whereProbesIndex })) {
            val plan = plan(compiled(statement, rows))
            println("$statement\n$plan\n")
            assertTrue(probesIndex(plan), "$statement reads every node of the label for each row:\n$plan")
        }
        assertEquals(listOf(100L), query("UNWIND \$rows AS row MATCH (a:Person {id: row.s}) RETURN count(a)", rows))
    }

    @Test
    fun `a row's number finds a node whose number the store keeps with more zeros after its point`() {
        // Postgres's numeric keeps the scale a product makes: 0.25 * 10 is 2.50, 2.0 * 2.0 is 4.00, and 1.0E-5 is 0.000010.
        query("CREATE (:Scaled {id: 0.25 * 10, k: 1}), (:Scaled {id: 0.25 * 4, k: 2}), (:Scaled {id: 2.0 * 2.0, k: 3}), (:Scaled {id: 1.0E-5, k: 4}), (:Scaled {id: [1.0, 2.50], k: 5})")
        val statement = "UNWIND \$rows AS row MATCH (a:Scaled {id: row.s}) RETURN a.k ORDER BY a.k"
        for ((rows, expected) in listOf(
            listOf(2.5, 1L, 4L, 0.00001) to listOf(1L, 2L, 3L, 4L),
            listOf(1.0, 4.0) to listOf(2L, 3L),
            listOf(listOf(1L, 2.5)) to listOf(5L),
        )) {
            val bound = mapOf("rows" to rows.map { mapOf("s" to it) })
            assertEquals(expected, query(statement, bound), "$rows")
            assertEquals(expected, query("UNWIND \$rows AS row MATCH (a:Scaled) WHERE a.id = row.s RETURN a.k ORDER BY a.k", bound), "$rows in a WHERE")
        }
    }

    @Test
    fun `the same statement given rows with no list, then rows with one, finds the list's node the second time`() {
        // A statement is compiled for the rows it is given: one whose rows hold no list at the key leaves out the test that lets
        // a list through, and is no statement compiled for rows that do.
        val statement = "UNWIND \$rows AS row MATCH (a:Person {id: row.s}) RETURN a.k ORDER BY a.k"
        assertEquals(listOf(5L), query(statement, mapOf("rows" to listOf(mapOf("s" to "p5")))))
        assertEquals(listOf(-4L, 5L), query(statement, mapOf("rows" to listOf(mapOf("s" to "p5"), mapOf("s" to listOf(1, 2))))))
        assertEquals(listOf(5L), query(statement, mapOf("rows" to listOf(mapOf("s" to "p5")))))
    }

    @Test
    fun `a relationship for each row is made between the nodes the row names`() {
        val rows = mapOf("rows" to (0 until 50).map { mapOf("s" to "p$it", "d" to "p${it + 1}") })
        query("UNWIND \$rows AS row MATCH (a:Person {id: row.s}) MATCH (b:Person {id: row.d}) CREATE (a)-[:FOLLOWS]->(b)", rows)
        assertEquals(listOf(50L), query("MATCH (:Person)-[r:FOLLOWS]->(:Person) RETURN count(r)"))
        assertEquals(listOf(listOf(0L, 1L)), query("MATCH (a:Person {id: 'p0'})-[:FOLLOWS]->(b) RETURN [a.k, b.k]"))
    }

    /** A batch of relationships as `apoc.export.cypher` writes it: its rows written out, its key a name of spaces. */
    private fun apocBatch(from: Int, to: Int, write: String = "CREATE (start)-[r:NEXT_CHUNK]->(finish) SET r += row.properties") =
        "UNWIND [${(from until to).joinToString(", ") { "{start: {_id: $it}, end: {_id: ${it + 1}}, properties: {}}" }}] AS row " +
            "MATCH (start:`$IMPORT_LABEL` {`$IMPORT_ID`: row.start._id}) MATCH (finish:`$IMPORT_LABEL` {`$IMPORT_ID`: row.end._id}) $write"

    @Test
    fun `a batch of relationships as APOC exports it finds each row's nodes through the index on a key of spaces`() {
        // Its reads, planned as a query: a write compiles to a procedure, which no EXPLAIN takes.
        val plan = plan(compiled(apocBatch(0, 20, "RETURN start, finish"), emptyMap()))
        println("apoc\n$plan\n")
        assertTrue(probesIndex(plan), "an APOC batch reads every node of the label for each row:\n$plan")
        query(apocBatch(0, 20))
        query(apocBatch(20, 40))
        assertEquals(listOf(40L), query("MATCH (:`$IMPORT_LABEL`)-[r:NEXT_CHUNK]->(:`$IMPORT_LABEL`) RETURN count(r)"))
        assertEquals(listOf(listOf(39L, 40L)), query("MATCH (a)-[:NEXT_CHUNK]->(b {`$IMPORT_ID`: 40}) RETURN [a.`$IMPORT_ID`, b.`$IMPORT_ID`]"))
    }

    @Test
    fun `a parameter's text finds its node through the index`() {
        val plan = plan(compiled("MATCH (a:Person {id: \$s}) RETURN a.k", mapOf("s" to "p42")))
        println("parameter\n$plan\n")
        assertTrue(probesIndex(plan), "a parameter reads every node of the label:\n$plan")
        assertEquals(listOf(42L), query("MATCH (a:Person {id: \$s}) RETURN a.k", mapOf("s" to "p42")))
    }

    companion object {
        const val GRAPH = "propkey"
        const val NODES = 5000
        const val IMPORT_LABEL = "UNIQUE IMPORT LABEL"
        const val IMPORT_ID = "UNIQUE IMPORT ID"
        const val IMPORTED = 2000
        const val BIG = 9007199254740993L
        val LONG = "x".repeat(5000)
    }
}

@PostgresTests
class PostgresPropertyKeyIndexTest : PropertyKeyIndexContract() {
    override val pm by lazy { PostgresTestDatabase.graphManager(GRAPH) }
    override val cypher by lazy { PostgresTestDatabase.fixtures(pm) }

    override fun compiled(cypher: String, parameters: Map<String, Any?>) = PostgresCypherCompiler(GRAPH).compile(cypher, parameters)

    /** The plan with no hash or merge join, as `PostgresIdKeyPlanTest` takes it: over a graph this small Postgres might hash every node to the rows. */
    override fun plan(compiled: CompiledCypher): String {
        val connection = PostgresTestDatabase.provider.connect()
        return try {
            connection.startTransaction()
            connection.query(QuerySpecification.withStatement("SET LOCAL enable_hashjoin = off"))
            connection.query(QuerySpecification.withStatement("SET LOCAL enable_mergejoin = off"))
            connection.query(QuerySpecification.withStatement("EXPLAIN ${compiled.sql}").bind(compiled.parameters)).joinToString("\n")
        } finally {
            connection.release()
        }
    }

    override fun probesIndex(plan: String) =
        Regex("""Index Cond: \(\(props ->> '(id|$IMPORT_ID)'::text\) = """).containsMatchIn(plan) && "Seq Scan on ${GRAPH}_node" !in plan

    override fun analyze() = PostgresTestDatabase.run("ANALYZE ${GRAPH}_node")

    override val storesInfinity = false
}

@OracleTests
class OraclePropertyKeyIndexTest : PropertyKeyIndexContract() {
    override val pm by lazy { OracleTestDatabase.graphManager(GRAPH) }
    override val cypher by lazy { OracleTestDatabase.fixtures(pm) }

    /**
     * Compiled knowing the indexes the graph has, which a statement the connection runs is not yet: its rows are found as
     * before, without the index, which the tests of rows here check stays so.
     */
    override fun compiled(cypher: String, parameters: Map<String, Any?>) =
        OracleCypherCompiler(GRAPH, indexedProperties = setOf("Person" to "id", IMPORT_LABEL to IMPORT_ID)).compile(cypher, parameters)

    override fun plan(compiled: CompiledCypher) = OracleTestDatabase.plan(pm, compiled.sql, compiled.parameters, "BASIC +PREDICATE")

    /**
     * The uniqueness constraint's index read by its key, and neither the index over labels, which reads every node of the
     * label, nor a full scan of the nodes.
     */
    override fun probesIndex(plan: String) =
        Regex("""INDEX (UNIQUE|RANGE) SCAN\s*\|\s*${GRAPH.uppercase()}_UQ_""").containsMatchIn(plan) &&
            "${GRAPH.uppercase()}_NODE_LABELS" !in plan &&
            !Regex("""TABLE ACCESS (STORAGE )?FULL\s*\|\s*${GRAPH.uppercase()}_NODE\b""").containsMatchIn(plan)

    /** A `WHERE` names no label to write the labelled index's expression for. */
    override val whereProbesIndex = false

    override fun analyze() = OracleTestDatabase.run("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '${GRAPH.uppercase()}_NODE'); END;")

    @Test
    fun `a statement compiled without the graph's indexes compares the property as JSON alone, and tests the labels through their index`() {
        // A property with no index of its own: its labels tested by their text, the node table would be read whole (#638).
        val sql = OracleCypherCompiler(GRAPH).compile("UNWIND \$rows AS row MATCH (a:Person {id: row.s}) RETURN a.k", mapOf("rows" to listOf(mapOf("s" to "p1")))).sql
        assertTrue(org.drivine.backend.oracle.OracleSql.unique("n2", "Person", listOf("id"), "id") !in sql, sql)
        assertTrue("CARDINALITY" !in sql, sql)
        assertTrue("JSON_EXISTS(n2.labels, '\$[*]?(@ == \"Person\")')" in sql, sql)
    }
}
