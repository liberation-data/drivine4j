package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, a property that is an item of a `RETURN` or a `WITH` is read through its text, as extended JSON: Oracle
 * crashed the session where a statement took a text past 32 KB from the properties as JSON for each of two rows (#255).
 * Elsewhere a property is read as it was.
 */
class OracleItemPropertyTest {

    private val long = "JSON(JSON_QUERY(JSON_SERIALIZE(JSON_TRANSFORM(n1.props, KEEP '\$.a') RETURNING CLOB EXTENDED), '\$.a' RETURNING CLOB) EXTENDED)"

    @Test
    fun `a property an item returns or passes on is read through its text, and one sorted, tested or nested in an item as it was`() {
        val returned = OracleCypherCompiler("g").compile("MATCH (n:Doc) WHERE n.b = 1 RETURN n.a ORDER BY n.id").sql
        assertTrue(returned.contains("$long AS c1"), returned)
        assertTrue(returned.contains("JSON_QUERY(n1.props, '\$.id' RETURNING JSON) AS s1") && returned.contains("JSON_QUERY(n1.props, '\$.b' RETURNING JSON)"), returned)
        val passed = OracleCypherCompiler("g").compile("MATCH (n:Doc) WITH n.a AS a RETURN [a]").sql
        assertEquals(1, Regex(Regex.escape(long)).findAll(passed).count(), passed)
        val nested = OracleCypherCompiler("g").compile("MATCH (n:Doc) RETURN [n.a, n {.a}]").sql
        assertTrue(!nested.contains("RETURNING CLOB"), nested)
        val sorted = OracleCypherCompiler("g").compile("MATCH (n:Doc) RETURN n.a ORDER BY n.a").sql
        assertTrue(sorted.contains("$long AS c1") && sorted.contains("$long AS s1"), sorted)
    }

    @Test
    fun `a property a text function or a sum reads is read from the properties by its own path, and not taken out as JSON first`() {
        // Taken out with JSON_QUERY and read as a CLOB or summed, a text past 32 KB closed the session, one row a statement,
        // and a later statement lost its rows (#298).
        val sql = OracleCypherCompiler("g").compile("MATCH (n:Doc) RETURN [toUpper(n.a), size(n.a), substring(n.a, 1), n.a STARTS WITH 'x', n.a + 'x', toString(n.a)]").sql
        assertTrue(!Regex("JSON_VALUE\\(JSON_QUERY\\(n1.props, '\\$.a' RETURNING JSON\\)").containsMatchIn(sql), sql)
        assertTrue(sql.contains("JSON_VALUE(n1.props, '\$.a' RETURNING CLOB)") && sql.contains("JSON_VALUE(n1.props, '\$.a.type()')"), sql)
        assertTrue(sql.contains("JSON_VALUE(n1.props, '\$.a.size()' RETURNING NUMBER)"), sql)
    }

    @Test
    fun `a property returned beside a variable-length walk is read as elsewhere`() {
        val sql = OracleCypherCompiler("g").compile("MATCH (:Doc {id: 1})-[:R*]->(n) RETURN n.a").sql
        assertTrue(!sql.contains("RETURNING CLOB") && sql.contains(".a' RETURNING JSON)"), sql)
    }

    @Test
    fun `a relationship's property returned is read through its text`() {
        val sql = OracleCypherCompiler("g").compile("MATCH ()-[r:R]->() RETURN r.a").sql
        assertTrue(sql.contains("KEEP '\$.a') RETURNING CLOB EXTENDED), '\$.a' RETURNING CLOB) EXTENDED)"), sql)
    }

    @Test
    fun `a map's value returned is read as elsewhere`() {
        val sql = OracleCypherCompiler("g").compile("WITH {a: 1} AS m RETURN m.a").sql
        assertTrue(!sql.contains("KEEP") && sql.contains("'\$.a' RETURNING JSON)"), sql)
    }

    @Test
    fun `a node's stamp returned is read from its column`() {
        val sql = OracleCypherCompiler("g").compile("MATCH (n) RETURN n.`__drivine.stamp`").sql
        assertTrue(sql.contains("JSON_SCALAR(n1.stamp) AS c1") && !sql.contains("KEEP"), sql)
    }

    @Test
    fun `a property an item groups by or makes distinct is read through its text`() {
        val grouped = OracleCypherCompiler("g").compile("MATCH (n:Doc) RETURN n.a, count(*)").sql
        assertTrue(grouped.contains(long), grouped)
        val distinct = OracleCypherCompiler("g").compile("MATCH (n:Doc) RETURN DISTINCT n.a").sql
        assertTrue(distinct.contains(long), distinct)
    }

    @Test
    fun `a property a write returns is read through its text`() {
        val sql = OracleCypherCompiler("g").compile("MATCH (n:Doc) SET n.b = 1 RETURN n.a").sql
        assertTrue(sql.contains("KEEP '\$.a') RETURNING CLOB EXTENDED), '\$.a' RETURNING CLOB) EXTENDED) AS c1"), sql)
    }

    @Test
    fun `on Postgres a property returned is read as elsewhere`() {
        val sql = PostgresCypherCompiler("g").compile("MATCH (n:Doc) RETURN n.a").sql
        assertTrue(sql.contains("(n1.props -> 'a')"), sql)
    }
}
