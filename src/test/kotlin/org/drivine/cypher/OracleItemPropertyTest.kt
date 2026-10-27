package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, a property that is an item of a `RETURN` or a `WITH` is read through its text, as extended JSON: Oracle
 * crashed the session where a statement took a text past 32 KB from the properties as JSON for each of two rows (#255).
 * So is a text a write takes from the row it is writing, to work out what it writes (#309). Elsewhere a property is read
 * as it was.
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
    fun `a property whose key is quoted in its path, or is in a map, is read by its own path too`() {
        fun read(cypher: String) = OracleCypherCompiler("g").compile(cypher).sql.also { assertTrue(!it.contains("JSON_VALUE(JSON_QUERY(n1.props, '\$.\""), it) }
        val quoted = read("MATCH (n:Doc) RETURN [size(n.`it's`), toUpper(n.`a b`), size(n.`x' RETURNING JSON) y`)]")
        assertTrue(quoted.contains("JSON_VALUE(n1.props, '\$.\"it''s\".size()' RETURNING NUMBER)"), quoted)
        assertTrue(quoted.contains("JSON_VALUE(n1.props, '\$.\"a b\"' RETURNING CLOB)"), quoted)
        assertTrue(quoted.contains("JSON_VALUE(n1.props, '\$.\"x'' RETURNING JSON) y\".type()')"), quoted)
        // A key in a map is read by its path in the map, the map taken out of the properties as it was.
        val nested = read("MATCH (n:Doc) RETURN size(n.m.a)")
        assertTrue(nested.contains("JSON_VALUE(JSON_QUERY(n1.props, '\$.m' RETURNING JSON), '\$.a.size()' RETURNING NUMBER)"), nested)
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
    fun `a text a write takes from its row is read through the row's text, and a pattern it matches tests it as it was`() {
        fun read(path: String) =
            "CASE WHEN JSON_VALUE(cy_row, '$path.type()') = 'string' THEN JSON(JSON_QUERY(JSON_SERIALIZE(cy_row RETURNING CLOB), '$path' RETURNING CLOB)) " +
                "ELSE JSON_QUERY(cy_row, '$path' RETURNING JSON) END"
        val set = OracleCypherCompiler("g").compile("UNWIND \$ts AS t MATCH (n:Doc {id: t.id}) SET n.a = t.a, n.b = t, n.c = t.m.`k.j`", mapOf("ts" to emptyList<Any>())).sql
        assertTrue(set.contains("SELECT ${read("\$.v1.a")} INTO cy_value"), set)
        assertTrue(set.contains("SELECT ${read("\$.v1")} INTO cy_value"), set)
        assertTrue(set.contains("SELECT ${read("\$.v1.m.\"k.j\"")} INTO cy_value"), set)
        assertTrue(!set.contains("JSON_QUERY(JSON_QUERY(cy_row"), set)
        val merged = OracleCypherCompiler("g").compile("UNWIND \$ts AS t MERGE (n:Doc {id: t.id}) ON CREATE SET n.a = t.a", mapOf("ts" to emptyList<Any>())).sql
        assertTrue(merged.contains("= JSON_QUERY(JSON_QUERY(cy_row, '\$.v1' RETURNING JSON), '\$.id' RETURNING JSON)"), merged)
        assertTrue(merged.contains("JSON_OBJECT('id' VALUE ${read("\$.v1.id")} NULL ON NULL RETURNING JSON) INTO cy_value"), merged)
        assertTrue(merged.contains("SELECT ${read("\$.v1.a")} INTO cy_value"), merged)
    }

    @Test
    fun `a value a UNION in a write's subquery takes from its row in each query is read from what the UNION gives`() {
        // x is t.m in one query and t.l in the other (#336): read from the row at the first's keys, the other's rows read t.m.a.
        val ts = mapOf("ts" to emptyList<Any>())
        fun union(second: String) =
            "UNWIND \$ts AS t MATCH (n {id: t.id}) SET n.a = COUNT { CALL { WITH t RETURN t.m AS x UNION ALL WITH t RETURN $second AS x } MATCH (y) WHERE y.k = x.a RETURN y }"
        val differ = OracleCypherCompiler("g").compile(union("t.l"), ts).sql
        assertTrue(!differ.contains("'\$.v1.m.a'") && !differ.contains("'\$.v1.l.a'"), differ)
        // Where each query takes it from the same keys, it is read from the row there, as #309 reads it.
        val same = OracleCypherCompiler("g").compile(union("t.m"), ts).sql
        assertTrue(same.contains("'\$.v1.m.a'"), same)
        val postgres = PostgresCypherCompiler("g").compile(union("t.l"), ts).sql
        assertTrue(!postgres.contains("(((cy_row -> 'v1') -> 'm') -> 'a')"), postgres)
    }

    @Test
    fun `a value an OPTIONAL CALL in a write's subquery returns from its row is read from what the call gives`() {
        // u is null where the call finds nothing (#336): read from the row, it would be t, and y.k compared with t.a.
        val cypher = "UNWIND \$ts AS t MATCH (n {id: t.id}) SET n.a = COUNT { OPTIONAL CALL { WITH t MATCH (y:Nope) RETURN t AS u } MATCH (y) WHERE y.k = u.a RETURN y }"
        val ts = mapOf("ts" to emptyList<Any>())
        val oracle = OracleCypherCompiler("g").compile(cypher, ts).sql
        assertTrue(!oracle.contains("'\$.v1.a'"), oracle)
        val postgres = PostgresCypherCompiler("g").compile(cypher, ts).sql
        assertTrue(!postgres.contains("((cy_row -> 'v1') -> 'a')"), postgres)
    }

    @Test
    fun `on Postgres a value a write takes from its row is read as elsewhere`() {
        val sql = PostgresCypherCompiler("g").compile("UNWIND \$ts AS t MATCH (n:Doc {id: t.id}) SET n.a = t.a", mapOf("ts" to emptyList<Any>())).sql
        assertTrue(!sql.contains("SERIALIZE") && sql.contains("'v1'") , sql)
    }

    @Test
    fun `on Postgres a property returned is read as elsewhere`() {
        val sql = PostgresCypherCompiler("g").compile("MATCH (n:Doc) RETURN n.a").sql
        assertTrue(sql.contains("(n1.props -> 'a')"), sql)
    }
}
