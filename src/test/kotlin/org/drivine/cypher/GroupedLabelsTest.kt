package org.drivine.cypher

import org.drivine.backend.oracle.OracleSql
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, a node a `MATCH` finds after a `WITH` that groups its rows is tested for its labels by their text, which the
 * index over labels can't answer (`OracleCypherCompiler.groupedLabels`): answered by that index, Oracle sometimes crashed
 * the session building the cursor of the Cypher spec's "Maps and lists holding relationships compare by identity"
 * (ORA-00600 `[QJSNMV_GET_MVI_MD:2]`, #601, #320, liberation-data/drivine#620). Every other test of a node's labels is as
 * it was, so the index still finds the nodes of a label. The spec case pins the rows.
 */
class GroupedLabelsTest {

    private fun oracle(cypher: String) = OracleCypherCompiler("g").compile(cypher).sql

    private fun indexed(alias: String, label: String) = "JSON_EXISTS($alias.labels, '\$[*]?(@ == \"$label\")')"

    /** The aliases of the nodes [sql] tests for [label] in the index's form, and those it tests by the labels' text. */
    private fun tests(sql: String, label: String): Pair<List<String>, List<String>> =
        Regex("""JSON_EXISTS\((n\d+)\.labels, '\$\[\*]\?\(@ == "${Regex.escape(label)}"\)'\)""").findAll(sql).map { it.groupValues[1] }.toList() to
            Regex("""JSON_SERIALIZE\((n\d+)\.labels\)""").findAll(sql).map { it.groupValues[1] }.distinct().filter { OracleSql.holdsLabel("$it.labels", label) in sql }.toList()

    @Test
    fun `tests the labels of the later node by their text, and the grouped node through the index, in the spec case that crashed`() {
        val sql = oracle(
            "MATCH (p:Person)-[r:ACTED_IN]->() WITH p, collect(DISTINCT [r]) AS l, count(DISTINCT [r]) AS n " +
                "MATCH (p)-[r2:ACTED_IN]->(:Movie {title: 'The Matrix'}) RETURN p.name, size(l), n, [r2] IN l ORDER BY p.name"
        )
        val (movieIndexed, movieText) = tests(sql, "Movie")
        assertEquals(emptyList(), movieIndexed, sql)
        assertEquals(1, movieText.size, sql)
        val (personIndexed, personText) = tests(sql, "Person")
        assertEquals(1, personIndexed.size, sql)
        assertEquals(emptyList(), personText, sql)
        assertTrue(indexed(personIndexed.single(), "Person") in sql, sql)
    }

    @Test
    fun `tests by their text after a WITH that aggregates, is DISTINCT, or reads one that does, each label of the node`() {
        for (cypher in listOf(
            "MATCH (p:Person) WITH count(p) AS c MATCH (m:Movie) RETURN c, m.title",
            "MATCH (p:Person) WITH DISTINCT p.name AS name MATCH (m:Movie) RETURN name, m.title",
            "MATCH (p:Person) WITH p, count(*) AS c WITH p, c MATCH (p)-[:ACTED_IN]->(m:Movie) RETURN m.title",
            "MATCH (p:Person) WITH p, count(*) AS c WHERE c > 0 MATCH (p)-[:ACTED_IN]->(m:Movie) RETURN m.title",
            "MATCH (p:Person) WITH p, count(*) AS c MATCH (p)-[:ACTED_IN]->(m:Movie:Film) RETURN m.title",
        )) {
            val sql = oracle(cypher)
            val (index, text) = tests(sql, "Movie")
            assertEquals(emptyList(), index, "$cypher\n$sql")
            assertEquals(1, text.size, "$cypher\n$sql")
            if (":Film" in cypher) assertEquals(1, tests(sql, "Film").second.size, "$cypher\n$sql")
        }
    }

    @Test
    fun `tests by their text a label the WHERE of such a node tests, as one its pattern names`() {
        for (cypher in listOf(
            "MATCH (p:Person) WITH p, count(*) AS c MATCH (m) WHERE m:Movie RETURN c, m.title",
            "MATCH (p:Person) WITH p, count(*) AS c MATCH (p)-[:ACTED_IN]->(m) WHERE m:Movie RETURN c, m.title",
        )) {
            val sql = oracle(cypher)
            val (index, text) = tests(sql, "Movie")
            assertEquals(emptyList(), index, "$cypher\n$sql")
            assertEquals(1, text.size, "$cypher\n$sql")
        }
        val before = "MATCH (m) WHERE m:Movie WITH m, count(*) AS c RETURN m.title, c"
        val sql = oracle(before)
        val (index, text) = tests(sql, "Movie")
        assertEquals(1, index.size, "$before\n$sql")
        assertEquals(emptyList(), text, "$before\n$sql")
    }

    @Test
    fun `tests through the index where no WITH groups, before the WITH that does, and in another UNION branch`() {
        for (cypher in listOf(
            "MATCH (n:Movie) RETURN count(n)",
            "MATCH (p:Person) WITH p MATCH (p)-[:ACTED_IN]->(m:Movie) RETURN m.title",
            "MATCH (p:Person) WITH p ORDER BY p.name LIMIT 3 MATCH (p)-[:ACTED_IN]->(m:Movie) RETURN m.title",
            "MATCH (p:Person)-[:ACTED_IN]->(m:Movie) WITH m, count(p) AS c RETURN m.title, c",
        )) {
            val sql = oracle(cypher)
            val (index, text) = tests(sql, "Movie")
            assertEquals(1, index.size, "$cypher\n$sql")
            assertEquals(emptyList(), text, "$cypher\n$sql")
        }
        val union = "MATCH (p:Person) WITH count(p) AS c MATCH (m:Movie) RETURN m.title AS t UNION MATCH (m:Movie) RETURN m.title AS t"
        val sql = oracle(union)
        val (index, text) = tests(sql, "Movie")
        assertEquals(1, index.size, "$union\n$sql")
        assertEquals(1, text.size, "$union\n$sql")
    }

    @Test
    fun `leaves the test as it was on Postgres`() {
        val sql = PostgresCypherCompiler("g").compile("MATCH (p:Person) WITH p, count(*) AS c MATCH (p)-[:ACTED_IN]->(m:Movie) RETURN m.title").sql
        assertTrue(Regex("""n\d+\.labels @> '\["Movie"]'""").containsMatchIn(sql), sql)
    }
}
