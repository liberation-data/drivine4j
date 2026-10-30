package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, two texts are compared as `VARCHAR2`s, or as CLOBs by `DBMS_LOB.COMPARE` where either is past 4,000 bytes, and
 * not as JSON: `=` of JSON refused a text past about 32 KB (ORA-40180), and `<`, `>`, `<=` and `>=` of one taken out of the
 * store as JSON on two rows or more closed the session (ORA-03113, #389). A property is read by its own path.
 */
class OracleCompareTextTest {

    private fun sql(cypher: String, parameters: Map<String, Any?> = mapOf("x" to "abc")) = OracleCypherCompiler("g").compile(cypher, parameters).sql

    /** The comparison [operator] of two texts as CLOBs, [cx] and [cy], each made a CLOB by `TO_CLOB`. */
    private fun clobs(cx: String, cy: String, operator: String) =
        "NVL(DBMS_LOB.COMPARE(TO_CLOB($cx), TO_CLOB($cy)), SIGN(NVL(LENGTH(TO_CLOB($cx)), 0) - NVL(LENGTH(TO_CLOB($cy)), 0))) $operator 0"

    /** The comparison [operator] of two texts as their `VARCHAR2`s [sx] and [sy], where both are read so, else as their CLOBs [cx] and [cy]. */
    private fun texts(sx: String, sy: String, cx: String, cy: String, operator: String) =
        "CASE WHEN $sx IS NOT NULL AND $sy IS NOT NULL THEN $sx $operator $sy ELSE ${clobs(cx, cy, operator)} END"

    private fun property(node: String, clob: Boolean = false) = "JSON_VALUE($node.props, '\$.t'${if (clob) " RETURNING CLOB" else ""})"

    @Test
    fun `two stored texts are ordered and compared as VARCHAR2s or CLOBs, each read by its own path, and other values as JSON`() {
        val n = listOf(property("n1"), property("n2"), property("n1", clob = true), property("n2", clob = true))
        for (operator in listOf("<", ">", "<=", ">=")) {
            val ordered = sql("MATCH (n:Doc), (m:Doc) WHERE n.t $operator m.t RETURN count(*)")
            val text = texts(n[0], n[1], n[2], n[3], operator)
            assertTrue(ordered.contains("THEN CASE WHEN JSON_VALUE(n1.props, '\$.t.type()') = 'string' THEN $text ELSE JSON_QUERY(n1.props, '\$.t' RETURNING JSON) $operator JSON_QUERY(n2.props, '\$.t' RETURNING JSON) END END"), ordered)
            assertTrue(!ordered.contains("JSON_VALUE(JSON_QUERY("), ordered)
        }
        // Equal as JSON where both are read as VARCHAR2s, as is quicker than reading their kinds; else as CLOBs where both are
        // texts, null where either isn't there, and not equal where one is a text.
        val equal = sql("MATCH (n:Doc), (m:Doc) RETURN n.t = m.t, n.t <> m.t")
        val json = "JSON_QUERY(n1.props, '\$.t' RETURNING JSON) = JSON_QUERY(n2.props, '\$.t' RETURNING JSON)"
        val kinds = "JSON_VALUE(n1.props, '\$.t.type()') = 'string' %s JSON_VALUE(n2.props, '\$.t.type()') = 'string'"
        val compared = "CASE WHEN ${n[0]} IS NOT NULL AND ${n[1]} IS NOT NULL THEN $json WHEN ${kinds.format("AND")} THEN ${clobs(n[2], n[3], "=")} " +
            "WHEN JSON_VALUE(n1.props, '\$.t.type()') IS NULL OR JSON_VALUE(n2.props, '\$.t.type()') IS NULL THEN NULL WHEN ${kinds.format("OR")} THEN FALSE ELSE $json END"
        assertEquals(2, Regex(Regex.escape(compared)).findAll(equal).count(), equal)
        assertTrue(!equal.contains("JSON_VALUE(JSON_QUERY("), equal)
        // A query of one table that compares a property is not rewritten by Oracle's JSON table transformation, which lost the
        // reads of a node's properties beside a quantifier's subquery; one that compares none is, as before.
        for (cypher in listOf("MATCH (n:Doc) WHERE n.t > \$x RETURN n", "MATCH (n:Doc) RETURN all(x IN [1] WHERE n.b > 1962) = true")) {
            assertTrue(sql(cypher).startsWith("SELECT /*+ NO_JSON_TABLE_TRANSFORM */"), cypher)
        }
        assertTrue(!sql("MATCH (n:Doc) RETURN n.t").contains("NO_JSON_TABLE_TRANSFORM"))
    }

    @Test
    fun `a stored text is compared with a given one, a list's items and a long value as texts, the property bound by its own path`() {
        // A given text of at most 4,000 bytes is read as a VARCHAR2, and so is longer than a stored text that isn't.
        val given = sql("MATCH (n:Doc) WHERE n.t = \$x RETURN n")
        assertTrue(given.contains("CASE WHEN ${property("n1")} IS NOT NULL OR NVL(JSON_VALUE(n1.props, '\$.t.type()'), 'null') <> 'string' THEN JSON_QUERY(n1.props, '\$.t' RETURNING JSON) = JSON(\$_cy_x) ELSE FALSE END"), given)
        val longer = sql("MATCH (n:Doc) WHERE n.t = \$x RETURN n", mapOf("x" to "€".repeat(1_334)))
        assertTrue(longer.contains("THEN ${clobs(property("n1", clob = true), "JSON_VALUE(JSON(\$_cy_x), '\$' RETURNING CLOB)", "=")}"), longer)
        val number = sql("MATCH (n:Doc) WHERE n.t = 5 RETURN n")
        assertTrue(number.contains("<> 'string' THEN JSON_QUERY(n1.props, '\$.t' RETURNING JSON) = JSON('5') ELSE FALSE END"), number)
        val empty = sql("MATCH (n:Doc) WHERE n.t = '' RETURN n")
        assertTrue(empty.contains("THEN ${clobs(property("n1", clob = true), "JSON_VALUE(JSON('\"\"'), '\$' RETURNING CLOB)", "=")}") && !empty.contains("ELSE FALSE END"), empty)
        // Items each read as a VARCHAR2, written or given, are compared as JSON with the property, null where it is a longer text.
        val item = Regex("i\\d+\\.item = CASE WHEN ${Regex.escape(property("n1"))} IS NOT NULL OR NVL\\(JSON_VALUE\\(n1\\.props, '\\\$\\.t\\.type\\(\\)'\\), 'null'\\) <> 'string' THEN JSON_QUERY\\(n1\\.props, '\\\$\\.t' RETURNING JSON\\) END")
        for (cypher in listOf("MATCH (n:Doc) WHERE n.t IN \$xs RETURN n", "MATCH (n:Doc) RETURN n.t IN \$xs", "MATCH (n:Doc) RETURN n.t IN ['a', 5, null, \$x]")) {
            val listed = sql(cypher, mapOf("xs" to listOf("a", "b"), "x" to "abc"))
            assertTrue(item.containsMatchIn(listed) && !listed.contains("DBMS_LOB"), listed)
        }
        // An empty item, or one past 4,000 bytes, isn't, and both sides are compared as texts.
        val clobItem = Regex("DBMS_LOB\\.COMPARE\\(TO_CLOB\\(JSON_VALUE\\(i\\d+\\.item, '\\$' RETURNING CLOB\\)\\), TO_CLOB\\(JSON_VALUE\\(n1\\.props, '\\$\\.t' RETURNING CLOB\\)\\)\\)")
        for (xs in listOf(listOf("a", ""), listOf("a", "€".repeat(1_334)))) {
            val listed = sql("MATCH (n:Doc) WHERE n.t IN \$xs RETURN n", mapOf("xs" to xs))
            assertTrue(clobItem.containsMatchIn(listed) && !item.containsMatchIn(listed), listed)
        }
        // The issue's: beside a value too long to write twice, which is bound, the property is bound by its own path, its JSON
        // only where it is no text past 32,767 characters, and its kind and CLOB from columns of their own.
        val beside = sql("MATCH (n:Doc), (m:Doc) WHERE n.t > toLower(trim(coalesce(\$p.q, m.name))) RETURN count(*)", mapOf("p" to mapOf("q" to null)))
        assertTrue(beside.contains("CASE WHEN JSON_VALUE(n1.props, '\$.t.length()' RETURNING NUMBER) > 32767 THEN NULL ELSE JSON_QUERY(n1.props, '\$.t' RETURNING JSON) END AS v1, JSON_VALUE(n1.props, '\$.t.type()') AS v2, JSON_VALUE(n1.props, '\$.t' RETURNING CLOB) AS v3"), beside)
        val (alias) = Regex("(sh\\d+)\\.v3").find(beside)!!.destructured
        assertTrue(beside.contains("THEN CASE WHEN $alias.v2 = 'string' THEN ${texts("JSON_VALUE($alias.v1, '\$')", "JSON_VALUE($alias.v4, '\$')", "$alias.v3", "JSON_VALUE($alias.v4, '\$' RETURNING CLOB)", ">")} ELSE $alias.v1 > $alias.v4 END"), beside)
        val inBeside = sql("MATCH (n:Doc), (m:Doc) RETURN n.t IN [toLower(trim(coalesce(\$p.q, m.name)))]", mapOf("p" to mapOf("q" to null)))
        assertTrue(inBeside.contains("JSON_VALUE(n1.props, '\$.t' RETURNING CLOB) AS v3") && inBeside.contains("DBMS_LOB.COMPARE("), inBeside)
    }

    @Test
    fun `a stored text is compared as a text with each item of a list a subquery works out for each row`() {
        // The list worked out in a derived table inside a lateral view (#433), and the property read by its own path.
        val clobItem = Regex("DBMS_LOB\\.COMPARE\\(TO_CLOB\\(JSON_VALUE\\(i\\d+\\.item, '\\$' RETURNING CLOB\\)\\), TO_CLOB\\(JSON_VALUE\\(n1\\.props, '\\$\\.t' RETURNING CLOB\\)\\)\\)")
        val filtered = sql("MATCH (n:Doc) WHERE n.t IN [(n)-->(q) | q.t] RETURN n")
        assertTrue(Regex("LATERAL \\(SELECT i\\d+_v\\.list FROM \\(SELECT \\(SELECT").containsMatchIn(filtered) && clobItem.containsMatchIn(filtered), filtered)
        assertTrue(!filtered.contains("JSON_VALUE(JSON_QUERY("), filtered)
        // Beside such a list, too long to write twice and so bound, the property is bound by its own path, as beside a sum.
        val returned = sql("MATCH (n:Doc), (m:Doc) RETURN n.t IN keys(m)")
        val (alias) = Regex("JSON_VALUE\\(n1\\.props, '\\$\\.t' RETURNING CLOB\\) AS v4\\) (sh\\d+)").find(returned)?.destructured ?: error(returned)
        assertTrue(Regex("DBMS_LOB\\.COMPARE\\(TO_CLOB\\(JSON_VALUE\\(i\\d+\\.item, '\\$' RETURNING CLOB\\)\\), TO_CLOB\\($alias\\.v4\\)\\)").containsMatchIn(returned), returned)
    }

    @Test
    fun `a list that holds a list or a map, written or given, has its items compared with a stored text as texts`() {
        val item = Regex("i\\d+\\.item = CASE WHEN")
        val lists = listOf(
            "MATCH (n:Doc) WHERE n.t IN ['a', \$m] RETURN n" to mapOf("m" to mapOf("k" to 1L)),
            "MATCH (n:Doc) WHERE n.t IN ['a', ['b']] RETURN n" to emptyMap(),
            "MATCH (n:Doc) WHERE n.t IN \$xs RETURN n" to mapOf("xs" to listOf("a", listOf("b"))),
        )
        for ((cypher, parameters) in lists) {
            val listed = sql(cypher, parameters)
            assertTrue(!item.containsMatchIn(listed) && listed.contains("DBMS_LOB.COMPARE("), listed)
        }
    }

    @Test
    fun `two texts the statement makes or is given are compared as texts, and on Postgres and SQLite a comparison is written as it was`() {
        val made = sql("WITH \$t AS t, \$x AS x RETURN substring(t, 0, 32768) = x", mapOf("t" to "abc", "x" to "a"))
        assertTrue(made.contains("DBMS_LOB.COMPARE(") && !Regex("THEN sh\\d+\\.v1 = sh\\d+\\.v2 END").containsMatchIn(made), made)
        val cypher = "MATCH (n:Doc), (m:Doc) WHERE n.t > m.t AND n.t = \$x RETURN n.t IN [m.t, \$x]"
        for (compiled in listOf(PostgresCypherCompiler("g").compile(cypher, mapOf("x" to "abc")).sql, SqliteCypherCompiler("g").compile(cypher, mapOf("x" to "abc")).sql)) {
            assertTrue(!compiled.contains("DBMS_LOB") && !compiled.contains("RETURNING CLOB"), compiled)
        }
    }

    @Test
    fun `in a statement that writes and in the step of a reduce, which run as PL SQL, two values are compared as JSON, as before`() {
        // There a CLOB read from an unwound row's JSON closed the session, where the JSON is refused (ORA-40180).
        val written = sql("UNWIND \$items AS t MERGE (n:Doc {a: t.a}) WITH n WHERE n.t > \$x AND n.t IN \$xs SET n.y = 1", mapOf("items" to listOf(mapOf("a" to "x")), "x" to "abc", "xs" to listOf("a")))
        assertTrue(!written.contains("DBMS_LOB.COMPARE"), written)
        assertTrue(written.contains("THEN JSON_QUERY(w7.c1_props, '\$.t' RETURNING JSON) > CASE WHEN"), written)
        val folded = sql("WITH ['a', 'b'] AS l RETURN reduce(acc = [], s IN l | acc + [s = \$x, s < \$x])")
        assertTrue(!folded.contains("DBMS_LOB.COMPARE"), folded)
    }
}
