package org.drivine.cypher

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A node equal to a node is their keys compared, through the store's own comparison of two keys. On Oracle that is `+ 0`
 * on one side, both ways round, so either is read by its key: compared column to column, Oracle 23 dropped the later
 * node's join and tested its label in a form that misses a node with more than one label, so `MATCH (p)-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q` gave no rows (#576).
 */
class NodeEqualityTest {

    private val later = "MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q RETURN c.name"
    private val earlier = "MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE q = c RETURN c.name"

    @Test
    fun `on Oracle, a node equal to a node compares the keys with + 0 on one side, both ways round`() {
        val sql = OracleCypherCompiler("g").compile(later).sql
        assertTrue("(n4.nid = n2.nid + 0 AND n2.nid = n4.nid + 0)" in sql, sql)
        val other = OracleCypherCompiler("g").compile(earlier).sql
        assertTrue("(n2.nid = n4.nid + 0 AND n4.nid = n2.nid + 0)" in other, other)
    }

    @Test
    fun `on Oracle, a node compared to a node by NOT =, IN, a list, CASE or in a subquery compares the keys with + 0 too`() {
        val q = "MATCH (p {name: 'Keanu'})-[:KNOWS]->(q)"
        for (statement in listOf(
            "$q MATCH (c:Person) WHERE c <> q RETURN c.name",
            "$q MATCH (c:Person) WHERE c IN [q] RETURN c.name",
            "$q MATCH (c:Person) WHERE [c] = [q] RETURN c.name",
            "$q MATCH (c:Person) WHERE CASE c WHEN q THEN true ELSE false END RETURN c.name",
            "$q OPTIONAL MATCH (c:Person) WHERE c = q RETURN c.name",
            "$q WHERE EXISTS { MATCH (c:Person) WHERE c = q } RETURN q.name",
        )) {
            val sql = OracleCypherCompiler("g").compile(statement).sql
            assertTrue(Regex("""n\d+\.nid = n\d+\.nid \+ 0""").containsMatchIn(sql), "$statement\n$sql")
            assertFalse(Regex("""n\d+\.nid = n\d+\.nid(?! \+ 0)""").containsMatchIn(sql), "$statement\n$sql")
        }
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
    private fun plan(compiled: CompiledCypher) = OracleTestDatabase.plan(pm, compiled.sql, compiled.parameters, "BASIC +OUTLINE")

    /** That [statement] gives [expected], and that Oracle keeps the join of `c`, the later of the two nodes compared. */
    private fun assertJoined(statement: String, expected: List<Any?>) {
        val compiled = OracleCypherCompiler(GRAPH).compile(statement)
        val compared = assertNotNull(Regex("""\b(n(\d+))\.nid = (n(\d+))\.nid \+ 0""").find(compiled.sql), "No node equal to a node in:\n${compiled.sql}")
        val c = listOf(compared.groupValues[1] to compared.groupValues[2], compared.groupValues[3] to compared.groupValues[4])
            .maxBy { it.second.toInt() }.first.uppercase()
        val plan = plan(compiled)
        println("$statement\n$plan\n")
        assertTrue("Outline Data" in plan, plan)
        assertFalse(Regex("""ELIMINATE_JOIN\([^)]*"$c"""").containsMatchIn(plan), "$statement eliminates c's join:\n$plan")
        assertEquals(expected, query(statement), statement)
    }

    @Test
    fun `a node with two labels, equal to a node matched before it, is still joined and found`() {
        assertJoined("MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q RETURN c.name", listOf("Carrie"))
        assertJoined("MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE q = c RETURN c.name", listOf("Carrie"))
        assertJoined("MATCH (p {name: 'Keanu'})<-[:KNOWS]-(q) MATCH (c:Person) WHERE c = q RETURN c.name", listOf("Laurence"))
        assertJoined("MATCH (p:Person {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE c = q RETURN c.name", listOf("Carrie"))
        assertJoined("MATCH (p {name: 'Keanu'})-[:KNOWS]->(q), (c:Person) WHERE c = q RETURN c.name", listOf("Carrie"))
        assertJoined("MATCH (p {name: 'Keanu'})-[:KNOWS]->(q) MATCH (c:Person) WHERE id(c) = id(q) RETURN c.name", listOf("Carrie"))
    }

    companion object {
        const val GRAPH = "nodeeq"
    }
}
