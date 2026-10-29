package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * On Oracle, a property that is an item of a `RETURN` or a `WITH` is read through its text, as extended JSON: Oracle
 * crashed the session where a statement took a text past 32 KB from the properties as JSON for each of two rows (#255).
 * So is a text a write takes from the row it is writing, to work out what it writes (#309), and an unwound item's value a
 * `RETURN` or a `WITH` gives (#322). Elsewhere a property is read as it was.
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
    fun `a property a text function reads beside a long value has its texts bound beside it, by its own path`() {
        // A value past SHARED_ABOVE's length is bound in a subquery. The property was bound beside it as JSON, and read as a
        // CLOB from that; left where it is read, its CLOB was read beside the subquery. On two rows or more, a text past
        // 32 KB so closed the session either way (#370).
        val long = "toLower(trim(coalesce(\$p.q, m.name)))"
        val shapes = listOf(
            "n.a CONTAINS #", "# CONTAINS n.a", "n.a STARTS WITH #", "n.a ENDS WITH #", "split(n.a, #)", "replace(n.a, #, 'x')", "replace(n.a, 'x', #)",
            "replace(n.a, n.b, #)",
        )
        for (shape in shapes) {
            for (cypher in listOf("MATCH (n:Doc), (m:Doc) RETURN ${shape.replace("#", long)}", "MATCH (n:Doc), (m:Doc) WITH n, m WHERE ${shape.replace("#", long)} IS NOT NULL RETURN 1")) {
                val sql = compiledBeside(cypher)
                assertTrue(Regex("JSON_VALUE\\([\\w.]+props, '\\$.a'\\) END AS v\\d+").containsMatchIn(sql), "$cypher: $sql")
                assertTrue(Regex("JSON_VALUE\\([\\w.]+props, '\\$.a' RETURNING CLOB\\) END AS v\\d+").containsMatchIn(sql), "$cypher: $sql")
                assertTrue(!Regex("THEN JSON_VALUE\\([\\w.]+props, '\\$.a' RETURNING CLOB\\) END(?! AS v)").containsMatchIn(sql), "$cypher: $sql")
            }
        }
    }

    @Test
    fun `a property a sum reads beside a long value has its kind and its CLOB bound beside it, by its own path`() {
        // Bound beside the long value as JSON and read as a CLOB from that, a text past 32 KB closed the session (#370); left
        // where it is read beside the subquery, a sum of short texts closed it on one row, `a.id + '->' + b.id`.
        val long = "toLower(trim(coalesce(\$p.q, m.name)))"
        for (shape in listOf("n.a + #", "# + n.a", "size(n.a + #)", "n.a + duration(#)", "duration(#) + n.a", "n.a - duration(#)")) {
            val sql = compiledBeside("MATCH (n:Doc), (m:Doc) RETURN ${shape.replace("#", long)}")
            assertTrue(sql.contains("JSON_VALUE(n1.props, '\$.a.type()') AS v"), "$shape: $sql")
            assertTrue(sql.contains("JSON_VALUE(n1.props, '\$.a' RETURNING CLOB) AS v"), "$shape: $sql")
            assertTrue(sql.contains("CASE WHEN JSON_VALUE(n1.props, '\$.a.length()' RETURNING NUMBER) > 32767 THEN NULL ELSE JSON_QUERY(n1.props, '\$.a' RETURNING JSON) END AS v"), "$shape: $sql")
            // Read nowhere else: its length, its JSON, its kind and its CLOB.
            assertEquals(4, Regex(Regex.escape("n1.props, '\$.a")).findAll(sql).count(), "$shape: $sql")
            // Its kind and its CLOB are read from their own columns, not from its JSON, which is null past 32,767 characters.
            val (json, kind) = Regex("END AS (v\\d+), JSON_VALUE\\(n1.props, '\\$.a.type\\(\\)'\\) AS (v\\d+)").find(sql)!!.destructured
            val view = Regex("(sh\\d+)\\.$kind\\b").find(sql)!!.groupValues[1]
            assertTrue(!Regex("JSON_VALUE\\($view\\.$json, '\\$(\\.type\\(\\)'\\)|' RETURNING CLOB\\))").containsMatchIn(sql), "$shape: $sql")
        }
        val sum = OracleCypherCompiler("g").compile("MATCH (a)-[:R]->(b) RETURN a.id + '->' + b.id").sql
        assertTrue(sum.contains("JSON_VALUE(n2.props, '\$.id.type()') AS v"), sum)
        assertEquals(4, Regex(Regex.escape("n2.props, '\$.id")).findAll(sum).count(), sum)
    }

    @Test
    fun `an aggregate of a text function a property is read by beside a long value works its argument out first, its texts bound there`() {
        // An aggregate's argument binds no value; one that would is worked out first, for each row (#333), which the texts'
        // binding says as shared's does, so the property's texts are bound beside the long value there too. A long literal
        // binds nothing of its own, so only the texts' binding says the argument binds a value.
        val long = "'${"y".repeat(250)}'"
        for (aggregate in listOf("count(CASE WHEN n.a CONTAINS $long THEN 1 END)", "collect(split(n.a, $long))")) {
            val cypher = "MATCH (n:Doc), (m:Doc) RETURN $aggregate"
            val sql = compiledBeside(cypher)
            assertTrue(Regex("JSON_VALUE\\([\\w.]+props, '\\$.a' RETURNING CLOB\\) END AS v\\d+").containsMatchIn(sql), "$cypher: $sql")
        }
    }

    /** [cypher], whose long value is bound, compiled; its property `a` is not bound as JSON, and the nodes' labels are tested. */
    private fun compiledBeside(cypher: String): String {
        val sql = OracleCypherCompiler("g").compile(cypher, mapOf("p" to mapOf("q" to null))).sql
        assertTrue(Regex(" AS v1\\b").containsMatchIn(sql), "$cypher binds the long value: $sql")
        assertTrue(!Regex("JSON_QUERY\\([\\w.]+props, '\\$.[ab]' RETURNING JSON\\) AS v").containsMatchIn(sql), "$cypher: $sql")
        assertTrue(!Regex("JSON_VALUE\\(JSON_QUERY\\([\\w.]+props, '\\$.[ab]' RETURNING JSON\\)").containsMatchIn(sql), "$cypher: $sql")
        assertEquals(2, Regex(Regex.escape("JSON_EXISTS(")).findAll(sql).count(), "$cypher: $sql")
        return sql
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
    fun `an unwound item's value an item returns or passes on is read through the item's text where it is a text`() {
        // Oracle crashed the session where such a value, a text past 32 KB, was read as JSON for each of two rows (#322).
        fun read(json: String) =
            "CASE WHEN JSON_VALUE($json, '\$.a.type()') = 'string' THEN JSON(JSON_QUERY(JSON_SERIALIZE($json RETURNING CLOB), '\$.a' RETURNING CLOB)) " +
                "ELSE JSON_QUERY($json, '\$.a' RETURNING JSON) END"
        val ts = mapOf("ts" to emptyList<Any>())
        fun compiled(cypher: String) = OracleCypherCompiler("g").compile(cypher, ts).sql
        val returned = compiled("UNWIND \$ts AS t RETURN t.a")
        assertTrue(returned.contains("${read("u1.item")} AS c1"), returned)
        val passed = compiled("UNWIND \$ts AS t WITH t.a AS a CREATE (:Doc {a: a})")
        assertTrue(passed.contains("${read("u1.item")} AS c1"), passed)
        val carried = compiled("UNWIND \$ts AS t WITH t RETURN t.a")
        assertTrue(carried.contains("${read("w3.c1")} AS c1"), carried)
        val written = compiled("UNWIND \$ts AS t MATCH (n:Doc {id: t.id}) SET n.b = 1 RETURN t.a")
        assertTrue(written.contains("${read("JSON_QUERY(r4.item, '\$.v1' RETURNING JSON)")} AS c1"), written)
        // Tested, sorted, nested in an item, or matched by a pattern, as it was.
        val other = compiled("UNWIND \$ts AS t WITH t WHERE t.b = 1 RETURN [t.a] ORDER BY t.c")
        assertTrue(!other.contains("SERIALIZE") && other.contains("JSON_QUERY(w3.c1, '\$.a' RETURNING JSON)"), other)
        val merged = compiled("UNWIND \$ts AS t MERGE (n:Doc {a: t.a})")
        assertTrue(merged.contains("= JSON_QUERY(JSON_QUERY(cy_row, '\$.v1' RETURNING JSON), '\$.a' RETURNING JSON)"), merged)
        // A UNION whose every query returns an unwound item gives one, read so too.
        val unioned = compiled("CALL { UNWIND \$ts AS m RETURN m UNION ALL UNWIND \$ts AS m RETURN m } RETURN m.a")
        assertTrue(unioned.contains("JSON_SERIALIZE(") && unioned.contains("'\$.a.type()'"), unioned)
        // A map made of a node's properties and carried past a write is read from the write's rows, so read so too.
        val past = compiled("MATCH (n:Doc) WITH n, n {.a} AS m SET n.b = 1 RETURN m.a")
        assertTrue(past.contains("JSON_SERIALIZE(") && past.contains("'\$.a.type()'"), past)
        // So does a CALL that returns one, an OPTIONAL one among them.
        listOf("CALL { UNWIND \$ts AS m RETURN m } RETURN m.a", "OPTIONAL CALL { UNWIND \$ts AS m RETURN m } RETURN m.a").forEach { cypher ->
            val sql = compiled(cypher)
            assertTrue(sql.contains("JSON_SERIALIZE(") && sql.contains("'\$.a.type()'"), sql)
        }
        // A temporal value's field, or a key that may name one, is read as a temporal field is.
        listOf("UNWIND \$ts AS t RETURN t.year", "UNWIND [date('2020-01-02')] AS t RETURN t.year").forEach { cypher ->
            val sql = compiled(cypher)
            assertTrue(!sql.contains("'\$.year.type()'"), sql)
        }
    }

    @Test
    fun `a value of a map made of a node's properties, or written, is read as elsewhere`() {
        // Only a value read from an unwound item is read through its text: a CASE on a node's properties made Oracle drop a label test.
        listOf(
            "MATCH (n:Doc) RETURN properties(n).a",
            "MATCH (n:Doc) WITH n {.a} AS m RETURN m.a",
            "MATCH (n:Doc) WITH {a: n.a} AS m RETURN m.a",
            "MATCH (n:Doc) CALL { WITH n RETURN n {.a} AS m UNION ALL UNWIND [{a: 1}] AS m RETURN m } RETURN m.a",
        ).forEach { cypher ->
            val sql = OracleCypherCompiler("g").compile(cypher).sql
            assertTrue(!sql.contains("SERIALIZE"), sql)
        }
    }

    @Test
    fun `on Postgres an unwound item's value returned is read as elsewhere`() {
        val ts = mapOf("ts" to emptyList<Any>())
        assertTrue(PostgresCypherCompiler("g").compile("UNWIND \$ts AS t RETURN t.a", ts).sql.contains("SELECT (u1.item -> 'a') AS c1"))
        assertTrue(PostgresCypherCompiler("g").compile("UNWIND \$ts AS t WITH t RETURN t.a", ts).sql.contains("SELECT (w3.c1 -> 'a') AS c1"))
        val written = PostgresCypherCompiler("g").compile("UNWIND \$ts AS t MATCH (n:Doc {id: t.id}) SET n.b = 1 RETURN t.a", ts).sql
        assertTrue(written.contains("(((r4.item -> 'v1')) -> 'a') AS c1") || written.contains("((r4.item -> 'v1') -> 'a') AS c1"), written)
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
    fun `a grouping key an order term over the items reads is read through its text once, for the item and the GROUP BY`() {
        // #413: worked out in the query that groups, the term read the key's text again at each level of the sum, 52 times.
        val sql = OracleCypherCompiler("g").compile("MATCH (me:P)--(you:P) RETURN me.age AS age, count(you.age) AS cnt ORDER BY me.age + count(you.age)").sql
        assertEquals(2, Regex("JSON_SERIALIZE").findAll(sql).count(), sql)
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

    @Test
    fun `an item of a list read by its place is read through the list's text where it is a text`() {
        // Taken out of the list as JSON, a text past 32 KB closed the session from the second run on, given two rows (#388).
        // So the item's kind is read first, and only an item of another kind is taken out as JSON, in the CASE's ELSE.
        val shapes = listOf(
            "RETURN size(split(n.a, 'x')[0])", "WITH split(n.a, 'x') AS parts RETURN size(parts[0])", "RETURN split(n.a, 'x')[0]",
            "WITH split(n.a, 'x') AS parts RETURN parts[-1]", "RETURN size(split(n.a, 'x')[n.i])", "RETURN size(split(n.a, 'x')[0..1][0])",
            "RETURN n.tags[0]", "RETURN [x IN range(0, 1) | toUpper(n.tags[x])]",
        )
        for (shape in shapes) {
            val sql = OracleCypherCompiler("g").compile("MATCH (n:Doc) $shape").sql
            // Each read of the item is its kind's, its text's, and in the ELSE, its JSON's.
            val (kinds, texts, all) = listOf("'\$[\$i].type()' PASSING ", "RETURNING CLOB), '\$[\$i]' PASSING ", "'\$[\$i]' PASSING ").map { Regex.escape(it).toRegex().findAll(sql).count() }
            assertTrue(kinds > 0 && texts == kinds && all == 2 * kinds, "$shape: $sql")
            assertTrue(Regex(Regex.escape("ELSE JSON_QUERY(")).findAll(sql).count() >= kinds, "$shape: $sql")
            // The node's label test stays.
            assertEquals(1, Regex(Regex.escape("JSON_EXISTS(")).findAll(sql).count(), "$shape: $sql")
        }
        // By head and last; of a stored list, its kind read by the property's own path (see direct).
        val ends = OracleCypherCompiler("g").compile("MATCH (n:Doc) RETURN [size(head(split(n.a, 'x'))), last(split(n.a, 'x')), head(n.tags)]").sql
        assertTrue(ends.contains("'\$[0].type()') = 'string'") && ends.contains("'\$[last].type()') = 'string'"), ends)
        assertTrue(ends.contains("RETURNING CLOB), '\$[0]' RETURNING CLOB)") && ends.contains("RETURNING CLOB), '\$[last]' RETURNING CLOB)"), ends)
        assertTrue(ends.contains("JSON_VALUE(n1.props, '\$.tags[0].type()') = 'string'"), ends)
        for (end in listOf("0", "last")) {
            val (texts, all) = listOf("RETURNING CLOB), '\$[$end]' RETURNING CLOB)", "'\$[$end]' RETURNING").map { Regex.escape(it).toRegex().findAll(ends).count() }
            assertTrue(texts > 0 && all == 2 * texts, ends)
        }
        // An unwound item is read as it was: JSON_TABLE's item did not close the session.
        val unwound = OracleCypherCompiler("g").compile("MATCH (n:Doc) UNWIND split(n.a, 'x') AS p RETURN size(p)").sql
        assertTrue(!unwound.contains("RETURNING CLOB), '\$["), unwound)
        // Postgres reads an item as before.
        val postgres = PostgresCypherCompiler("g").compile("MATCH (n:Doc) RETURN [split(n.a, 'x')[0], head(n.tags), last(n.tags)]").sql
        assertTrue(postgres.contains("-> 0)") && postgres.contains("-> -1)") && !postgres.contains("CLOB"), postgres)
    }
}
