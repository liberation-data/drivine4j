package org.drivine.cypher

import org.drivine.backend.oracle.OracleSql
import org.drivine.cypher.sql.CompiledCypher
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.drivine.oracle.OracleTestDatabase
import org.drivine.oracle.OracleTests
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A node equal to a node is their keys compared, through the store's own comparison of two keys. On Oracle that is `+ 0`
 * on one side: compared column to column, Oracle 23 dropped the later node's join and tested its label in a form that
 * misses a node with more than one label, so `MATCH (p)-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q` gave no rows (#576).
 */
class NodeEqualityTest {

    private val later = "MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q RETURN c.name"
    private val earlier = "MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE q = c RETURN c.name"

    @Test
    fun `on Oracle, a node equal to a node compares the keys with + 0 on one side`() {
        val sql = OracleCypherCompiler("g").compile(later).sql
        assertTrue("n4.nid = n2.nid + 0" in sql, sql)
        val other = OracleCypherCompiler("g").compile(earlier).sql
        assertTrue("n2.nid = n4.nid + 0" in other, other)
    }

    @Test
    fun `on Postgres and SQLite, a node equal to a node compares the keys as they are`() {
        for (sql in listOf(PostgresCypherCompiler("g").compile(later).sql, SqliteCypherCompiler("g").compile(later).sql)) {
            assertTrue("n4.nid = n2.nid" in sql && "+ 0" !in sql, sql)
        }
    }

    @Test
    fun `a relationship equal to a relationship still compares the keys as they are, on Oracle too`() {
        val sql = OracleCypherCompiler("g").compile("MATCH (a)-[r]->(b) MATCH (a)-[s]->(b) WHERE s = r RETURN count(*)").sql
        assertTrue(Regex("""e\d+\.eid = e\d+\.eid\b(?! \+ 0)""").containsMatchIn(sql), sql)
    }
}

/**
 * On Oracle, a node with a label equal to a node matched before it is still joined, and so found when it has more than one
 * label (#576). The plan's outline names each join Oracle eliminates.
 */
@OracleTests
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleNodeEqualityPlanTest {

    private val pm by lazy { OracleTestDatabase.graphManager(GRAPH) }
    private val cypher by lazy { OracleTestDatabase.fixtures(pm) }

    @BeforeAll
    fun seed() {
        OracleTestDatabase.clear(pm)
        // Two labels on each node: Oracle's filter of the labels, in the multivalue index's form, missed such a node.
        query("CREATE (k:Person:CyEq {name: 'Keanu'}), (c:Person:CyEq {name: 'Carrie'}), (l:Person:CyEq {name: 'Laurence'}), (k)-[:KNOWS]->(c), (l)-[:KNOWS]->(k)")
    }

    private fun query(statement: String): List<Any?> = cypher.query(QuerySpecification.withStatement(CypherStatement(statement)))

    /** The plan Oracle makes for [compiled] with its outline, its values written in as literals, explained and read in one block. */
    private fun plan(compiled: CompiledCypher): String {
        val sql = compiled.parameters.entries.sortedByDescending { it.key.length }
            .fold(compiled.sql) { text, (name, value) -> text.replace("\$$name", if (value == null) "NULL" else OracleSql.literal(value.toString())) }
        val block = """
            DECLARE
                v_plan SYS_REFCURSOR;
            BEGIN
                EXECUTE IMMEDIATE ${OracleSql.text("EXPLAIN PLAN FOR $sql")};
                OPEN v_plan FOR SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY(format => 'BASIC +OUTLINE'));
                DBMS_SQL.RETURN_RESULT(v_plan);
            END;
        """.trimIndent()
        return pm.query(QuerySpecification.withStatement(block).transform(String::class.java)).joinToString("\n")
    }

    /** That [statement] gives [expected], and that Oracle keeps the join of `c`, compiled as `n4`. */
    private fun assertJoined(statement: String, expected: List<Any?>) {
        val plan = plan(OracleCypherCompiler(GRAPH).compile(statement))
        println("$statement\n$plan\n")
        assertTrue("Outline Data" in plan, plan)
        assertFalse(Regex("""ELIMINATE_JOIN\([^)]*"N4"""").containsMatchIn(plan), "$statement eliminates c's join:\n$plan")
        assertEquals(expected, query(statement), statement)
    }

    @Test
    fun `a node with two labels, equal to a node matched before it, is still joined and found`() {
        assertJoined("MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q RETURN c.name", listOf("Carrie"))
        assertJoined("MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE q = c RETURN c.name", listOf("Carrie"))
        assertJoined("MATCH (p {name: 'Keanu'})<-[:KNOWS]-(q) MATCH (c:Person) WHERE c = q RETURN c.name", listOf("Laurence"))
    }

    companion object {
        const val GRAPH = "nodeeq"
    }
}
