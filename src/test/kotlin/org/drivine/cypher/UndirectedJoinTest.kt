package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * A relationship of either direction is compiled to read the relationship table both ways round and join on equalities
 * (#570) on Postgres and Oracle, a relationship from a node to itself kept once by a condition of the join, not a `WHERE`
 * of either half. SQLite keeps the OR, which it probes by index. It joined with an OR across the two directions, which Postgres can't hash-join on, so it compared every pair
 * of nodes, and which Oracle's recursive walk didn't expand, so it read every relationship at each step. The rows it
 * gives are pinned by the Cypher spec's undirected cases and `CypherQueryContract`; this pins the shape of the join.
 */
class UndirectedJoinTest {

    private val compilers = listOf(PostgresCypherCompiler(GRAPH), OracleCypherCompiler(GRAPH), SqliteCypherCompiler(GRAPH))

    /** The stores that read a relationship of either direction both ways round; SQLite keeps the OR, which it probes by index. */
    private val bothWays = compilers.take(2)

    private val undirected = listOf(
        "MATCH (n)-[r]-(m) RETURN n, r, m",
        "MATCH (n {k: 1})-[r:KNOWS]-(m) RETURN r.since, m",
        "MATCH (a {k: 1}), (b {k: 2}) MATCH (a)-[r]-(b) RETURN r",
        "MATCH (n {k: 1})-[*1..3]-(m) RETURN m",
        "MATCH p = (n)-[:KNOWS*1..2]-(m) RETURN nodes(p), relationships(p)",
        "MATCH (m)-[*1..2]-(n {k: 1}) RETURN m",
    )

    /**
     * An end of a relationship row compared as stored: how a step either way round was joined, with an OR across the two
     * directions, and how its walk found the node it reached, `CASE WHEN e.src = at THEN e.dst ELSE e.src END`. A
     * relationship row's alias is `e` and a number; Oracle's walk has a `src` column of its own, the node it set out from.
     */
    private val endCompared = Regex("""\be\d+\.(src|dst) = """)

    @Test
    fun `a relationship of either direction joins on the ends read both ways round, with no OR across them`() {
        bothWays.forEach { compiler ->
            undirected.forEach { cypher ->
                val sql = compiler.compile(cypher, emptyMap()).sql
                val where = "${compiler::class.simpleName}, $cypher:\n$sql"
                assertTrue("UNION ALL SELECT eid, src, dst, type, props, dst AS a, src AS b, 1 AS back FROM ${GRAPH}_edge)" in sql, where)
                assertTrue(Regex("""\(e\d+\.back = 0 OR e\d+\.src <> e\d+\.dst\)""").containsMatchIn(sql), where)
                assertTrue(endCompared.find(sql) == null, where)
            }
        }
    }

    @Test
    fun `on SQLite a relationship of either direction still joins with an OR, read from the relationship table`() {
        val compiler = SqliteCypherCompiler(GRAPH)
        undirected.forEach { cypher ->
            val sql = compiler.compile(cypher, emptyMap()).sql
            assertTrue("AS back" !in sql && Regex("""\be\d+\.src = \S+ OR \be\d+\.dst = |\) OR \(e\d+\.dst = """).containsMatchIn(sql), "$cypher:\n$sql")
        }
    }

    @Test
    fun `a directed relationship still reads the relationship table once`() {
        compilers.forEach { compiler ->
            listOf("MATCH (n)-[r]->(m) RETURN r", "MATCH (n)<-[*1..2]-(m) RETURN m").forEach { cypher ->
                val sql = compiler.compile(cypher, emptyMap()).sql
                assertTrue("dst AS a" !in sql && endCompared.find(sql) != null, "${compiler::class.simpleName}, $cypher:\n$sql")
            }
        }
    }

    private companion object {
        const val GRAPH = "undirected"
    }
}
