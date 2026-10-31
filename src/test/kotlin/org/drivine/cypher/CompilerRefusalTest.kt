package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.cypher.spec.CypherSpec
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.drivine.cypher.sql.SqliteCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A statement the compiler cannot make SQL of, or that is wrong as Cypher is, is refused when it is compiled,
 * with what is wrong, on each store alike: before the store is sent anything. The statements and messages are
 * the spec's, in `cypher/refusals.yaml`.
 */
class CompilerRefusalTest {

    /**
     * The spec's refusals, but for the steps of [COMPILED], which the spec at the tag drivine4j runs refuses and the
     * compiler now compiles: drivine-cypher-spec#33 and #40 drop them. Remove this when drivine4j moves to the tag that has them.
     */
    private val spec = CypherSpec.load("refusals.yaml").let { spec ->
        spec.copy(cases = spec.cases.map { case -> case.copy(steps = case.steps.filterNot { it.cypher in COMPILED }) })
    }

    private companion object {
        /** #422: a truncation to the decade; #495: a date of a week. */
        val COMPILED = setOf("RETURN date.truncate('decade', date('2024-03-15'))", "RETURN date({year: 2024, week: 10, dayOfWeek: 3})")
    }

    @TestFactory
    fun postgres() = spec.tests("postgres", before = {}) { step -> PostgresCypherCompiler("g").compile(step.cypher, step.parameters); emptyList() }

    @TestFactory
    fun oracle() = spec.tests("oracle", before = {}) { step -> OracleCypherCompiler("g").compile(step.cypher, step.parameters); emptyList() }

    @TestFactory
    fun sqlite() = spec.tests("sqlite", before = {}) { step -> SqliteCypherCompiler("g").compile(step.cypher, step.parameters); emptyList() }

    @Test
    fun `collect(DISTINCT) beside a variable no other item is, outside its aggregates, is refused as collect() is`() {
        // Not in the spec yet: aggregatesFirst groups by each such variable, so it is refused before.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val refused = assertThrows<DrivineException> { compiler.compile("MATCH (p:Person) RETURN p.born, collect(DISTINCT p.name) + p.name") }.message!!
            assertTrue(refused.contains("names p outside its aggregates"), refused)
        }
    }

    @Test
    fun `round() compiles a precision below 0 or past 2147483647, written, negated or given, to fail on the row that rounds a number by it`() {
        // Neo4j reads the precision only when it rounds (#305), so none is refused when compiling, in both forms.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            listOf(
                "RETURN round(1234.5, -2)" to emptyMap(),
                "RETURN round(1234.5, -1.2, 'UP')" to emptyMap(),
                "RETURN round(1234.5, -\$p)" to mapOf("p" to 2),
                "RETURN round(1234.5, \$p)" to mapOf("p" to -2.5),
                "RETURN round(1234.5, 2147483648)" to emptyMap(),
                "RETURN round(1234.5, \$p, 'HALF_UP')" to mapOf("p" to Double.POSITIVE_INFINITY),
            ).forEach { (cypher, parameters) ->
                val sql = compiler.compile(cypher, parameters).sql
                assertTrue(sql.contains("round() takes a precision from 0 to 2147483647, as in Neo4j."), "${compiler::class.simpleName}: $cypher: $sql")
            }
            // -0.5 and -0.9 cut toward zero are 0 places, and 2147483647.9 is 2147483647: none fails.
            listOf(
                "RETURN [round(1234.5, -0.5), round(1234.5, \$p, 'HALF_UP')]" to mapOf("p" to -0.9),
                "RETURN [round(1234.5, 2147483647), round(1234.5, \$p, 'UP')]" to mapOf("p" to 2147483647.9),
            ).forEach { (cypher, parameters) ->
                val sql = compiler.compile(cypher, parameters).sql
                assertTrue(!sql.contains("round() takes a precision"), "${compiler::class.simpleName}: $cypher: $sql")
            }
        }
    }

    @Test
    fun `toString of a list or a map the statement makes is refused, as Neo4j refuses it`() {
        // #349: Postgres and SQLite gave the list's JSON text, each spaced its own way, and Oracle null. The spec's refusals hold the rest.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            listOf(
                "WITH [1, 2] AS l RETURN toString(l)" to "list",
                "RETURN toString({a: 1})" to "map",
            ).forEach { (cypher, kind) ->
                val refused = assertThrows<DrivineException>("${compiler::class.simpleName}: $cypher") { compiler.compile(cypher) }.message!!
                assertEquals("toString() takes a number, a text, a truth value or a temporal value, not a $kind, as in Neo4j.", refused)
            }
            // A value whose kind the statement leaves open is compiled, as Neo4j plans it.
            compiler.compile("UNWIND [1, 'a'] AS x RETURN toString(x)")
            compiler.compile("MATCH (n) RETURN toString(n.x)")
        }
    }

    @Test
    fun `a value the statement writes as one of a kind an operator or a function does not take is refused, as Neo4j refuses it`() {
        // #420: each gave null, false or a value. In the spec's refusals from v0.6.20; until this copy moves to it, held here too.
        val refused = listOf(
            "RETURN 123 AND true" to "AND takes a truth value, not an integer, as in Neo4j.",
            "RETURN false OR 'foo'" to "OR takes a truth value, not a text, as in Neo4j.",
            "RETURN [true] XOR false" to "XOR takes a truth value, not a list, as in Neo4j.",
            "RETURN null AND {}" to "AND takes a truth value, not a map, as in Neo4j.",
            "RETURN NOT -1.5" to "NOT takes a truth value, not a float, as in Neo4j.",
            "WITH 123 AS x WITH x AS y RETURN NOT y" to "NOT takes a truth value, not an integer, as in Neo4j.",
            "MATCH (n) WHERE 1 RETURN n" to "An integer is not a condition, as in Neo4j.",
            "WITH 'a' AS x MATCH (n) WHERE x RETURN n" to "A text is not a condition, as in Neo4j.",
            "RETURN CASE WHEN [] THEN 'a' END" to "A list is not a condition, as in Neo4j.",
            "RETURN [x IN [1, 2] WHERE 'a']" to "A text is not a condition, as in Neo4j.",
            "RETURN any(x IN [1] WHERE 1.5)" to "A float is not a condition, as in Neo4j.",
            "WITH 123 AS x RETURN x.num" to "An integer has no properties.",
            "WITH 'abc' AS s MATCH (n) RETURN s.size" to "A text has no properties.",
            "WITH [1] AS l RETURN l.num" to "A list has no properties.",
            "WITH true AS b RETURN b.num" to "A truth value has no properties.",
            "WITH true AS list, 0 AS idx RETURN list[idx]" to "A truth value is not a list or a map, and is indexed as one, as in Neo4j.",
            "RETURN 'abc'[0]" to "A text is not a list or a map, and is indexed as one, as in Neo4j.",
            "WITH [1, 2, 3] AS list, 4.7 AS idx RETURN list[idx]" to "A list is indexed by an integer, not a float, as in Neo4j.",
            "RETURN [1, 2, 3]['1']" to "A list is indexed by an integer, not a text, as in Neo4j.",
            "RETURN {a: 1}[1]" to "A map is indexed by a text, not an integer, as in Neo4j.",
            "RETURN 1 IN 123" to "IN takes a list, not an integer, as in Neo4j.",
            "WITH {x: []} AS m RETURN 1 IN m" to "IN takes a list, not a map, as in Neo4j.",
            "RETURN range(0, 1, 1.1)" to "range() takes integers, not a float, as in Neo4j.",
            "RETURN range(true, 1)" to "range() takes integers, not a truth value, as in Neo4j.",
            "RETURN range(2, 8, 0)" to "range() takes a step other than 0, as in Neo4j.",
            "RETURN range(2, 8, -0)" to "range() takes a step other than 0, as in Neo4j.",
            "RETURN properties(1)" to "properties() takes a node, a relationship or a map, not an integer, as in Neo4j.",
            "WITH 'Cypher' AS t RETURN properties(t)" to "properties() takes a node, a relationship or a map, not a text, as in Neo4j.",
            "RETURN properties([true, false])" to "properties() takes a node, a relationship or a map, not a list, as in Neo4j.",
            // Read in the step of reduce(), which Oracle gives the row as JSON, a variable is of the kind the statement made it.
            "WITH 1 AS x RETURN reduce(a = 0, y IN [1] | a + x.num)" to "An integer has no properties.",
            "WITH 'a' AS x RETURN reduce(a = true, y IN [1] | a AND x)" to "AND takes a truth value, not a text, as in Neo4j.",
            // A kind each query of a UNION gives alike is kept.
            "CALL { RETURN 1 AS x UNION RETURN 2 AS x } RETURN x.num" to "An integer has no properties.",
            "CREATE (a) SET a.maplist = [{num: 1}]" to
                "A property is a number, a text, a truth value, a temporal value or a list of them, not a list of maps or lists, as in Neo4j.",
            "MATCH (a) SET a.m = {num: 1}" to "A property is a number, a text, a truth value, a temporal value or a list of them, not a map, as in Neo4j.",
        )
        // A compiler is made for each statement, as a connection makes one: one that has compiled a write compiles the next as one.
        listOf({ PostgresCypherCompiler("g") }, { OracleCypherCompiler("g") }, { SqliteCypherCompiler("g") }).forEach { compiler ->
            refused.forEach { (cypher, message) ->
                val store = compiler()::class.simpleName
                assertEquals(message, assertThrows<DrivineException>("$store: $cypher") { compiler().compile(cypher) }.message, "$store: $cypher")
            }
            // A parameter's value is known as the statement is compiled, once for each call: a step of 0 would never end, and
            // an index given of another kind is refused as one written is.
            listOf(
                Triple("RETURN range(2, 8, \$s)", mapOf("s" to 0), "range() takes a step other than 0, as in Neo4j."),
                Triple("WITH [1] AS l RETURN l[\$k]", mapOf("k" to "a"), "A list is indexed by an integer, not a text, as in Neo4j."),
                Triple("WITH {a: 1} AS m RETURN m[\$k]", mapOf("k" to 1), "A map is indexed by a text, not an integer, as in Neo4j."),
                // A value carried across a write is of the kind the statement made it.
                Triple("WITH 1 AS x CREATE (n) RETURN x.num", emptyMap(), "An integer has no properties."),
            ).forEach { (cypher, parameters, message) ->
                assertEquals(message, assertThrows<DrivineException>(cypher) { compiler().compile(cypher, parameters) }.message, cypher)
            }
            // A value whose kind is known only when the row is read, a property, a parameter or an unwound item, is compiled, as
            // Neo4j plans it; and so are null and the kinds each takes.
            listOf(
                "MATCH (n) WHERE n.flag AND NOT n.other RETURN n" to emptyMap(),
                "RETURN \$p AND true" to mapOf("p" to 1),
                "MATCH (n) RETURN n.x.y, n.x[0], 1 IN n.x, range(0, n.x), properties(n.x)" to emptyMap(),
                "UNWIND [{a: 1}] AS m RETURN m.a, m['a']" to emptyMap(),
                "RETURN NOT null, null XOR true, null.num, null[0], [1, 2, 3][null], 1 IN null, properties(null)" to emptyMap(),
                "WITH {a: 1} AS m, 'a' AS k RETURN m.a, m[k], properties(m), [1, 2][-1], range(0, 10, -2)" to emptyMap(),
                "MATCH (a) SET a.tags = ['x', 'y'], a.n = -1" to emptyMap(),
                // A variable each query of a UNION makes of another kind is of none known.
                "CALL { RETURN 1 AS x UNION RETURN {a: 1} AS x } RETURN x.a" to emptyMap(),
                // A variable a later WITH, a comprehension, a quantifier or a reduce() binds again is of the kind that one makes it.
                "WITH 1 AS x WITH {a: 1} AS x RETURN x.a" to emptyMap(),
                "WITH 1 AS x RETURN [x IN [{a: 1}] | x.a], [x IN [true] WHERE x], any(x IN [true] WHERE x), reduce(x = true, y IN [true] | x AND y)" to emptyMap(),
                // A CASE, a coalesce(), a list joined, an item of a list or a map written, or a parameter, is of no kind known.
                "WITH CASE WHEN true THEN {a: 1} ELSE 1 END AS x, coalesce(null, {a: 1}) AS y, [1] + [{a: 1}] AS z RETURN x.a, y.a, z[1]" to emptyMap(),
                "RETURN [{a: 1}][0].a, [true][0] AND true, {a: true}.a AND true, {a: [1]}['a'][0]" to emptyMap(),
                "WITH \$p AS x RETURN x.a, x AND true, x[0], 1 IN x, properties(x)" to mapOf("p" to mapOf("a" to 1)),
                "WITH [1] AS l, {a: 1} AS m RETURN l[\$i], m[\$k]" to mapOf("i" to 0, "k" to "a"),
                "WITH {a: 1} AS m CREATE (n) RETURN m.a" to emptyMap(),
                // A name a WITH or an UNWIND binds again after a write is of the kind that one makes it.
                "WITH 1 AS x CREATE (n {v: x}) WITH n AS x RETURN x.v" to emptyMap(),
                "WITH 1 AS x CREATE (n {v: x}) WITH n.v AS x RETURN x.a" to emptyMap(),
                "WITH 1 AS x CREATE (n) WITH {a: 1} AS x CREATE (m) RETURN x.a" to emptyMap(),
                "WITH 1 AS x CREATE (n) WITH 1 AS y UNWIND [{a: 1}] AS x RETURN x.a" to emptyMap(),
            ).forEach { (cypher, parameters) -> compiler().compile(cypher, parameters) }
        }
    }

    @Test
    fun `a variable used as two kinds, or made where it is bound already, is refused, as Neo4j refuses it`() {
        // Neo4j's VariableTypeConflict and VariableAlreadyBound: before, most were refused as not compiled yet ("bound before its
        // pattern", "a path is not a value"), and MATCH (a) CREATE (a) made nothing. Until the spec holds them, held here.
        val refused = listOf(
            "MATCH (a)-[a]->() RETURN a" to "a is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH ()-[r]-(r) RETURN r" to "r is a relationship, and is used as a node: a variable is of one kind, as in Neo4j.",
            "MATCH (r) MATCH ()-[r]-() RETURN r" to "r is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH (r)-[]-()-[r*]-() RETURN r" to "r is a node, and is used as a list of relationships: a variable is of one kind, as in Neo4j.",
            "MATCH ()-[r*]-() MATCH ()-[r]-() RETURN r" to "r is a list of relationships, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH p = ()-->() MATCH (p) RETURN p" to "p is a path, and is used as a node: a variable is of one kind, as in Neo4j.",
            "WITH true AS n MATCH (n) RETURN n" to "n is a truth value, and is used as a node: a variable is of one kind, as in Neo4j.",
            "WITH [] AS r MATCH ()-[r]-() RETURN r" to "r is a list, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "WITH {x: 1} AS r MATCH ()-[r*]-() RETURN r" to "r is a map, and is used as a list of relationships: a variable is of one kind, as in Neo4j.",
            "MATCH (a) WITH a AS r MATCH ()-[r]-() RETURN r" to "r is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH (a) WITH * MATCH ()-[a]->() RETURN a" to "a is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH (a) WITH 1 AS a MATCH ()-[a]->() RETURN a" to "a is an integer, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH (a) RETURN [(a)-[a]->() | 1] AS l" to "a is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH (a) WHERE (a)-[a]->() RETURN a" to "a is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH (a) WHERE EXISTS { MATCH ()-[a]->() } RETURN a" to "a is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH (a) RETURN COUNT { (a)-[a]->() } AS c" to "a is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH (a) CALL { WITH a MATCH ()-[a]->() RETURN 1 AS x } RETURN x" to "a is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "CALL { MATCH (a) RETURN a } MATCH ()-[a]->() RETURN a" to "a is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH ()-[r]->() CREATE (a)-[:R]->(r)" to "r is a relationship, and is used as a node: a variable is of one kind, as in Neo4j.",
            "MATCH p = (p)-[]-() RETURN p" to "p is bound already, so a pattern binds no path to it, as in Neo4j.",
            "MATCH ()-[p]->() MATCH p = ()-->() RETURN p" to "p is bound already, so a pattern binds no path to it, as in Neo4j.",
            "UNWIND [1] AS p MATCH p = ()-->() RETURN p" to "p is bound already, so a pattern binds no path to it, as in Neo4j.",
            "MATCH (a) CREATE (a)" to "a is bound already, so a CREATE makes no node of it, as in Neo4j.",
            "MATCH (a) MERGE (a)" to "a is bound already, so a MERGE makes no node of it, as in Neo4j.",
            "CREATE (n), (n)" to "n is bound already, so a CREATE makes no node of it, as in Neo4j.",
            "MATCH (a) CREATE (a)-[:R]->(b), (b)" to "b is bound already, so a CREATE makes no node of it, as in Neo4j.",
            "MATCH ()-[r]->() CREATE (r)" to "r is bound already, so a CREATE makes no node of it, as in Neo4j.",
            "CREATE (n:Foo)-[:T1]->(), (n:Bar)-[:T2]->()" to "n is bound already, so a CREATE or MERGE makes no node of it with labels or properties.",
            "CREATE (n)-[r:T]->(), ()-[r:T]->()" to "r is bound already, so a CREATE makes no relationship of it, as in Neo4j.",
            "MATCH (a) CREATE (b)-[a:R]->()" to "a is bound already, so a CREATE makes no relationship of it, as in Neo4j.",
            "WITH 1 AS r MERGE ()-[r:R]->()" to "r is bound already, so a MERGE makes no relationship of it, as in Neo4j.",
            "MATCH (a) CALL { WITH a CREATE (a) } RETURN 1" to "a is bound already, so a CREATE makes no node of it, as in Neo4j.",
            // A CALL imports by its brackets as by its first WITH.
            "MATCH (a) CALL (a) { CREATE (a) } RETURN 1" to "a is bound already, so a CREATE makes no node of it, as in Neo4j.",
            "MATCH (a) CALL (a) { MATCH ()-[a]->() RETURN 1 AS x } RETURN x" to "a is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH (a) CALL (*) { CREATE (a) } RETURN 1" to "a is bound already, so a CREATE makes no node of it, as in Neo4j.",
            // What brackets import is in scope after a WITH that does not name it, as Neo4j has it (#410).
            "MATCH (a) CALL (a) { WITH 1 AS one CREATE (a) RETURN one } RETURN one" to "a is bound already, so a CREATE makes no node of it, as in Neo4j.",
            "MATCH (a) CALL (*) { WITH 1 AS one MATCH ()-[a]->() RETURN one } RETURN one" to "a is a node, and is used as a relationship: a variable is of one kind, as in Neo4j.",
            "MATCH ()-[r]->() FOREACH (x IN [1] | CREATE (r)-[:T]->())" to "r is a relationship, and is used as a node: a variable is of one kind, as in Neo4j.",
            "MATCH p = (a)-[*]->(b) RETURN size(p)" to "size() takes a list or a text, not a path, as in Neo4j: length() takes a path.",
        )
        // A compiler is made for each statement, as a connection makes one.
        listOf({ PostgresCypherCompiler("g") }, { OracleCypherCompiler("g") }, { SqliteCypherCompiler("g") }).forEach { compiler ->
            refused.forEach { (cypher, message) ->
                val store = compiler()::class.simpleName
                assertEquals(message, assertThrows<DrivineException>("$store: $cypher") { compiler().compile(cypher) }.message, "$store: $cypher")
            }
            // Neo4j takes each of these: a node bound before an OPTIONAL MATCH or a MERGE that joins it up, a name a WITH, a
            // UNION, a pattern comprehension, an EXISTS or a CALL that imports nothing leaves to a new variable, a list a
            // variable-length relationship matches, a value whose kind the statement leaves open, and a variable bound
            // already that a CREATE or a MERGE in a FOREACH names.
            listOf(
                "MATCH (a) OPTIONAL MATCH (a)-->(b) RETURN b",
                "MATCH (a) OPTIONAL MATCH (a) RETURN a",
                "OPTIONAL MATCH (a) MATCH (a)-->() RETURN a",
                "MATCH (a) MERGE (a)-[:R]->(b)",
                "MATCH (a) MERGE (b)-[:R]->(a)",
                "MATCH (a) MERGE (a)-[:R]->(a)",
                "CREATE (a) MERGE (a)-[:R]->(b)",
                "MATCH (a)-[r]->(b) MERGE (a)-[s:T]->(b)",
                "MATCH (n) CREATE (n)-[:R]->(n)",
                "CREATE (n)-[:T1]->(), (n)-[:T2]->()",
                "MATCH (a) CREATE (b), (a)-[:R]->(b)",
                "MATCH (n) FOREACH (x IN [1] | CREATE (n)-[:R]->())",
                "MATCH (n) FOREACH (x IN [1] | CREATE (n))",
                "FOREACH (x IN [1] | CREATE (n), (n))",
                "MATCH (a) FOREACH (x IN [1] | MERGE (a)-[:R]->())",
                "MATCH (a) WITH a SKIP 0 MERGE (a)-[:R]->()",
                "MATCH (a) CALL { CREATE (a) RETURN 1 AS x } RETURN x",
                "MATCH (a)-[r*]->() WITH r MATCH ()-[r*]->() RETURN r",
                "MATCH (a) RETURN [(a)-->(a) | 1] AS l",
                "WITH 1 AS p RETURN [p = ()-->() | length(p)] AS l",
                "MATCH (a), (a)-->() RETURN a",
                "MATCH (n) WITH n MATCH (n:L) RETURN n",
                "MATCH (a) WITH a AS b, a AS c MATCH (b)-->(c) RETURN b",
                "MATCH ()-[a]->() WITH 1 AS x MATCH (a) RETURN a",
                "MATCH ()-[r]->() RETURN r UNION ALL MATCH (r) RETURN r",
                "MATCH (a) RETURN [a IN [1] | a] AS x",
                "MATCH (n) RETURN [p = (n)-->() | length(p)] AS x",
                "MATCH (a) WITH [(a)-[r]->() | r] AS rs MATCH ()-[r]->() RETURN r",
                "MATCH (a) WHERE EXISTS { MATCH (a)-[r]->() } MATCH ()-[r]->() RETURN r",
                "MATCH (a) CALL { WITH a MATCH (a)-->(b) RETURN b } MATCH (b)-->() RETURN b",
                "MATCH (a) CALL (a) { MATCH (a)-->(b) RETURN b } MATCH (b)-->() RETURN b",
                "MATCH (a) CALL (a) { CREATE (a)-[:R]->(b) RETURN b } RETURN b",
                // A name a CALL does not import is a new variable in it, of any kind (#410).
                "MATCH (a) CALL { MATCH ()-[a]->() RETURN 1 AS x } RETURN a, x",
                "MATCH (a) CALL () { MATCH ()-[a]->() RETURN 1 AS x } RETURN a, x",
                "MATCH (a), (b) CALL (a) { MATCH ()-[b]->() RETURN 1 AS x } RETURN x",
                "MATCH (a) CALL { WITH a WITH 1 AS one MATCH ()-[a]->() RETURN 1 AS x } RETURN x",
                "MATCH (a) CALL () { CREATE (a) RETURN 1 AS x } RETURN x",
                // A procedure's call imports each variable of the row, so its WHERE reads them.
                "MATCH (a) CALL db.labels() YIELD label WHERE label = a.kind RETURN label",
                "MATCH ()-[n]->(m) FOREACH (n IN [m] | CREATE (n)-[:R]->())",
                "MATCH (a) WITH a, 1 AS b ORDER BY size([(a)-->(x) | x]) RETURN b",
                "MATCH (a) MATCH p = (a)-->() RETURN nodes(p)",
                "MATCH ()-[r*]->() RETURN size(r)",
                "WITH [] AS r MATCH ()-[r*]-() RETURN r",
                "MATCH (a) WITH collect(a) AS ns UNWIND ns AS n MATCH (n)-->(m) RETURN m",
            ).forEach { compiler().compile(it) }
            // Neo4j takes these too, and the compiler refuses them for what it does not compile yet, as before. So it refuses a
            // relationship bound before a CREATE or a MERGE that makes it again, as the spec has it, which Neo4j refuses as bound already.
            listOf(
                "MATCH ()-[r]->() WITH r MATCH ()-[r]->() RETURN r" to "A relationship variable bound before its pattern is Cypher, and is not compiled to SQL yet.",
                "MATCH (a)-[r]->() RETURN EXISTS { (a)-[r]->() } AS e" to "A relationship variable bound before its pattern is Cypher, and is not compiled to SQL yet.",
                "MATCH (a)-[r]->(b) MERGE (a)-[r]->(b)" to "A relationship variable bound before its pattern is Cypher, and is not compiled to SQL yet.",
                "MATCH ()-[r]->() FOREACH (x IN [1] | CREATE ()-[r:T]->())" to "A relationship variable bound before its pattern is Cypher, and is not compiled to SQL yet.",
                "MATCH (a) CALL { WITH a RETURN a AS b UNION WITH a MATCH ()-[b]->() RETURN b } RETURN b" to
                    "A UNION in CALL { … } whose queries return values of different kinds is Cypher, and is not compiled to SQL yet.",
                "MATCH p = ()-->() RETURN [p = ()-->() | p] AS x" to "A path is not a value drivine gives the same on each store: use nodes(p), relationships(p) and length(p).",
                // A CALL's brackets import only variables of its row, as Neo4j has it (#410).
                "MATCH (a) CALL (x) { RETURN 1 AS n } RETURN n" to "x is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.",
                "MATCH (a) WITH 1 AS x CALL db.labels() YIELD label WHERE label = a.kind RETURN label" to
                    "a is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.",
                "MATCH (a), (b) CALL (a) { CALL (b) { MATCH (b)-->(d) RETURN d } RETURN d.x AS x } RETURN x" to
                    "b is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.",
                // A CALL holding a randomUUID() reads the UUID made for its row, and still imports only what it names.
                "UNWIND [1] AS i CALL { RETURN toString(i) + randomUUID() AS u } RETURN u" to
                    "i is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.",
                "UNWIND [1] AS i CALL (x) { RETURN randomUUID() AS u } RETURN u" to
                    "x is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.",
                // A variable quoted as the compiler names its own is imported as any other is.
                "UNWIND [1] AS `#anew9` CALL () { RETURN `#anew9` AS x } RETURN x" to
                    "#anew9 is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.",
            ).forEach { (cypher, message) ->
                val store = compiler()::class.simpleName
                assertEquals(message, assertThrows<DrivineException>("$store: $cypher") { compiler().compile(cypher) }.message, "$store: $cypher")
            }
        }
    }

    @Test
    fun `a CALL's leading WITH that imports names variables of the row alone, as Neo4j has it`() {
        // Not in the spec yet (#410): Neo4j refuses an importing WITH with anything more (its 42I28), which was compiled as any WITH.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val store = compiler::class.simpleName
            listOf(
                "MATCH (a), (b) CALL { WITH a, b AS c MATCH (a)-->(c) RETURN c.x AS x } RETURN x",
                "MATCH (a) CALL { WITH a AS c MATCH (c)-->(d) RETURN d.x AS x } RETURN x",
                "MATCH (a) CALL { WITH a WHERE a.x > 1 RETURN count(*) AS n } RETURN a, n",
                "MATCH (a) CALL { WITH a ORDER BY a.x LIMIT 1 MATCH (a)-->(d) RETURN d.x AS x } RETURN x",
                "MATCH (a) CALL { WITH a SKIP 0 RETURN 1 AS x } RETURN x",
                "MATCH (a) CALL { WITH DISTINCT a MATCH (a)-->(d) RETURN d.x AS x } RETURN x",
                "MATCH (a) CALL { WITH a, 1 AS one MATCH (a)-->(d) RETURN d.x + one AS x } RETURN x",
                "MATCH (a) CALL { WITH * WHERE true RETURN 1 AS x } RETURN x",
                "MATCH (a) CALL { UNWIND [1] AS x RETURN x UNION WITH a WHERE true RETURN 1 AS x } RETURN x",
                "MATCH (a) CALL { WITH a WHERE a.x > 1 SET a.y = 1 } RETURN a",
                // One holding a randomUUID() too, whose query reads a UUID made for the row before its WITH.
                "MATCH (a) CALL { WITH a AS c RETURN c.x + randomUUID() AS x } RETURN x",
            ).forEach { cypher ->
                val refused = assertThrows<DrivineException>("$store: $cypher") { compiler.compile(cypher) }.message!!
                assertTrue(refused.startsWith("The importing WITH of a CALL { … } names variables of the row alone, as in Cypher"), "$store: $cypher: $refused")
            }
            // A leading WITH that names no variable of the row as it is imports nothing, and is any WITH; so is one of a CALL with brackets.
            listOf(
                "MATCH (a) CALL { WITH 1 AS one WHERE one > 0 RETURN one } RETURN one",
                "MATCH (a) CALL { WITH DISTINCT 1 AS one RETURN one } RETURN one",
                "MATCH (a) CALL { WITH a AS a RETURN 1 AS one } RETURN one",
                "MATCH (a) CALL (a) { WITH a WHERE a.x > 1 RETURN a.x AS x } RETURN x",
                "MATCH (a) CALL (a) { WITH DISTINCT a, 1 AS one ORDER BY a.x LIMIT 1 RETURN one } RETURN one",
            ).forEach { compiler.compile(it) }
        }
    }

    @Test
    fun `two lists compared in a statement that writes are refused on Oracle, whose block calls no function the query declares`() {
        // #349: Oracle compares two lists by a function the query declares, which a procedure's queries do not call.
        val cypher = "CREATE (n:N {a: [1] < [2]})"
        val refused = assertThrows<DrivineException> { OracleCypherCompiler("g").compile(cypher) }.message!!
        assertTrue(refused.startsWith("Two lists compared with <, <=, > or >= do not run on Oracle in a statement that writes"), refused)
        PostgresCypherCompiler("g").compile(cypher)
        SqliteCypherCompiler("g").compile(cypher)
        // Nor does a reduce()'s step, which is a function the query declares.
        val folded = "RETURN reduce(acc = false, x IN [[1], [2]] | acc OR [1] < x)"
        val inStep = assertThrows<DrivineException> { OracleCypherCompiler("g").compile(folded) }.message!!
        assertTrue(inStep.startsWith("Two lists compared with <, <=, > or >= do not run on Oracle in the step of a reduce()"), inStep)
        PostgresCypherCompiler("g").compile(folded)
        SqliteCypherCompiler("g").compile(folded)
    }

    @Test
    fun `a date written as a text that is no date is refused, with what the JVM's parser says of it`() {
        // Not in the spec: the message ends with the JVM's own text.
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            val refused = assertThrows<DrivineException> { compiler.compile("RETURN date('2024-02-30')") }.message!!
            assertTrue(refused.startsWith("'2024-02-30' is no date"), refused)
        }
    }

    @Test
    fun `a temporal map of the calendar's fields Neo4j fails on, or of an ordinal day of no year, is refused`() {
        // #495: these fields were refused as not compiled before. Until the spec holds these (drivine-cypher-spec#40), held here.
        val refused = listOf(
            "RETURN date({year: 2023, month: 2, week: 3})" to "date() takes a month or a week, not both.",
            "RETURN date({year: 2023, quarter: 2, day: 5})" to "date() takes a month or a quarter, not both.",
            "RETURN datetime({year: 2023, week: 2, ordinalDay: 5})" to "datetime() takes a week or an ordinal day, not both.",
            "RETURN date({year: 2023, ordinalDay: 5, quarter: 1})" to "date() takes an ordinal day or a quarter, not both.",
            "RETURN date({year: 2023, dayOfWeek: 3})" to "date() takes a day of the week beside a week.",
            "RETURN localdatetime({year: 2023, dayOfQuarter: 3})" to "localdatetime() takes a day of the quarter beside a quarter.",
            "RETURN date({week: 2})" to "date() takes a year beside a week.",
            "RETURN date({quarter: 2})" to "date() takes a year beside a quarter.",
            "RETURN date({year: 2020, week: 54})" to "date() takes a week from 1 to 53, not 54.",
            "RETURN date({year: 2020, week: 0})" to "date() takes a week from 1 to 53, not 0.",
            "RETURN date({year: 2023, week: 2, dayOfWeek: 8})" to "date() takes a day of the week from 1 to 7, not 8.",
            "RETURN date({year: 2023, ordinalDay: 366})" to "date() takes an ordinal day from 1 to 365 in 2023, not 366.",
            "RETURN date({year: 2023, ordinalDay: -1})" to "date() takes an ordinal day from 1 to 365 in 2023, not -1.",
            "RETURN date({year: 2023, quarter: 5})" to "date() takes a quarter from 1 to 4, not 5.",
            "RETURN date({year: 2024, quarter: 4, dayOfQuarter: 93})" to "date() takes a day of the quarter from 1 to 92, not 93.",
            "RETURN date({year: 2023, week: 2.5})" to "date() takes a whole number for week, not 2.5.",
            // A year past an Int's, which would wrap round to 2023.
            "RETURN date({year: 4294969319, week: 1})" to "date() takes no year 4294969319: Invalid value for Year (valid values -999999999 - 999999999): 4294969319",
            "RETURN date({year: '2023', quarter: 2})" to "date() takes a whole number for year, not '2023'.",
            "RETURN localtime({hour: 12, week: 2})" to "localtime() takes no field week.",
            "RETURN time({hour: 12, ordinalDay: 2})" to "time() takes no field ordinalDay.",
            "RETURN date({ordinalDay: 2})" to "date() of an ordinal day without a year is Cypher, and is not compiled to SQL yet.",
            "RETURN datetime({year: 1984, week: 10, timezone: 'Europe/Stockholm'})" to "The time zone 'Europe/Stockholm' is Cypher, and is not compiled to SQL yet.",
        )
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            refused.forEach { (cypher, message) ->
                assertEquals(message, assertThrows<DrivineException>("${compiler::class.simpleName}: $cypher") { compiler.compile(cypher) }.message, cypher)
            }
            // Week 53 of a year of 52 weeks and a day of the quarter past its last run on, and what an expression gives is the store's to check.
            listOf(
                "RETURN date({year: 2019, week: 53}), date({year: 2023, quarter: 1, dayOfQuarter: 92}), date({year: 2024, ordinalDay: 366})",
                "WITH 54 AS w RETURN date({year: 2020, week: w}), datetime({year: \$y, ordinalDay: 366, timezone: '+01:00'})",
            ).forEach { compiler.compile(it, mapOf("y" to 2023)) }
        }
    }

    @Test
    fun `a temporal map from another temporal value that Neo4j fails on is refused`() {
        // #508: such maps were refused as not compiled before. Until the spec holds these (liberation-data/drivine-cypher-spec#44), held here.
        val refused = listOf(
            "RETURN date({date: date('2024-03-15'), hour: 3})" to "date() takes no field hour.",
            "RETURN date({date: date('2024-03-15'), timezone: '+01:00'})" to "date() takes no field timezone.",
            "RETURN date({datetime: datetime('2024-03-15T10:00Z')})" to "date() takes no field datetime.",
            "RETURN localtime({date: date('2024-03-15')})" to "localtime() takes no field date.",
            "RETURN time({datetime: datetime('2024-03-15T10:00Z')})" to "time() takes no field datetime.",
            "RETURN localtime({time: localtime('10:00'), timezone: '+01:00'})" to "localtime() takes no field timezone.",
            "RETURN time({time: time('10:00Z'), day: 2})" to "time() takes no field day.",
            "RETURN localdatetime({datetime: localdatetime('2024-03-15T10:00'), timezone: '+01:00'})" to "localdatetime() takes no field timezone.",
            "RETURN datetime({date: date('2024-03-15'), foo: 1})" to "datetime() takes no field foo.",
            "RETURN datetime({date: date('2024-03-15'), datetime: datetime('2020-01-01T10:00Z')})" to "datetime() takes a datetime, or a date and a time, not both.",
            "RETURN localdatetime({time: localtime('11:00'), datetime: localdatetime('2020-01-01T10:00')})" to "localdatetime() takes a datetime, or a date and a time, not both.",
            "RETURN date({date: localtime('10:00')})" to "date() takes a date, a localdatetime or a datetime for date, not a localtime.",
            "RETURN localtime({time: date('2024-03-15')})" to "localtime() takes a localtime, a time, a localdatetime or a datetime for time, not a date.",
            "RETURN datetime({datetime: date('2024-03-15')})" to "datetime() takes a localdatetime or a datetime for datetime, not a date.",
            "RETURN date({date: null})" to "date() takes a date, a localdatetime or a datetime for date, not null.",
            "RETURN date({date: '2024-03-15'})" to "date() takes a date, a localdatetime or a datetime for date, not '2024-03-15'.",
            "WITH 5 AS x RETURN localtime({time: x})" to "localtime() takes a localtime, a time, a localdatetime or a datetime for time, not an integer.",
            "RETURN date({date: date('2024-03-15'), month: 2, week: 3})" to "date() takes a month or a week, not both.",
            "RETURN date({date: date('2024-03-15'), dayOfWeek: 2, day: 3})" to "date() takes a month or a week, not both.",
            "RETURN localdatetime({year: 2024, month: 3, time: localtime('10:00')})" to "localdatetime() takes a date beside a time: a date, or a year with a month and a day, a week and a day of the week, an ordinal day, or a quarter and a day of the quarter.",
            "RETURN datetime({year: 2024, week: 3, time: localtime('10:00')})" to "datetime() takes a date beside a time: a date, or a year with a month and a day, a week and a day of the week, an ordinal day, or a quarter and a day of the quarter.",
            "RETURN datetime({time: localtime('10:00')})" to "datetime() takes a date beside a time: a date, or a year with a month and a day, a week and a day of the week, an ordinal day, or a quarter and a day of the quarter.",
            "RETURN localdatetime({date: date('2024-03-15'), second: 3})" to "localdatetime() takes a second beside a minute.",
            "RETURN localdatetime({date: date('2024-03-15'), minute: 3})" to "localdatetime() takes a minute beside an hour.",
            "RETURN datetime({date: date('2024-03-15'), hour: 5, minute: 3, microsecond: 1})" to "datetime() takes a microsecond beside a second.",
            "RETURN date({date: date('2024-03-15'), day: 32})" to "date() takes a day from 1 to 31, not 32.",
            "RETURN date({date: date('2024-03-15'), month: 13})" to "date() takes a month from 1 to 12, not 13.",
            "RETURN date({date: date('2024-03-15'), week: 54})" to "date() takes a week from 1 to 53, not 54.",
            "RETURN date({date: date('2024-03-15'), dayOfWeek: 8})" to "date() takes a day of the week from 1 to 7, not 8.",
            "RETURN date({date: date('2024-03-15'), quarter: 5})" to "date() takes a quarter from 1 to 4, not 5.",
            "RETURN date({date: date('2024-03-15'), dayOfQuarter: 93})" to "date() takes a day of the quarter from 1 to 92, not 93.",
            "RETURN localtime({time: localtime('10:00'), hour: 24})" to "localtime() takes an hour from 0 to 23, not 24.",
            "RETURN localtime({time: localtime('10:00'), second: 60})" to "localtime() takes a second from 0 to 59, not 60.",
            "RETURN localtime({time: localtime('10:00'), millisecond: 1000})" to "localtime() takes a millisecond from 0 to 999, not 1000.",
            "RETURN localtime({time: localtime('10:00'), millisecond: 5, microsecond: 1000})" to "localtime() takes a microsecond from 0 to 999 beside a millisecond, not 1000.",
            "RETURN localtime({time: localtime('10:00'), nanosecond: 1000000000})" to "localtime() takes a nanosecond from 0 to 999999999, not 1000000000.",
            "RETURN date({date: date('2024-03-15'), year: 2024.5})" to "date() takes a whole number for year, not 2024.5.",
            "RETURN date({date: date('2024-03-15'), day: '3'})" to "date() takes a whole number for day, not '3'.",
            "RETURN time({time: time('10:00+01:00'), timezone: null})" to "time() takes a time zone, not null.",
            "RETURN datetime({date: date('2024-03-15'), time: localtime('10:00'), timezone: 5})" to "datetime() takes a time zone, not 5.",
            "RETURN time({time: time('10:00+01:00'), timezone: '+0500'})" to "'+0500' is no time zone.",
            "RETURN datetime({datetime: localdatetime('2024-03-15T10:00'), timezone: 'Europe/Stockholm'})" to "The time zone 'Europe/Stockholm' is Cypher, and is not compiled to SQL yet.",
            // A zone by name in the constructor of the value given, which Postgres and Oracle gave as null and SQLite failed on.
            "WITH datetime({year: 1984, month: 10, day: 11, hour: 12, timezone: 'Europe/Stockholm'}) AS other RETURN time({time: other})" to "The time zone 'Europe/Stockholm' is Cypher, and is not compiled to SQL yet.",
            "RETURN datetime({year: 1984, month: 10, day: 11, timezone: 'Europe/Stockholm'})" to "The time zone 'Europe/Stockholm' is Cypher, and is not compiled to SQL yet.",
        )
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            refused.forEach { (cypher, message) ->
                assertEquals(message, assertThrows<DrivineException>("${compiler::class.simpleName}: $cypher") { compiler.compile(cypher) }.message, cypher)
            }
            // A day of the week or of the quarter alone, beside a value that gives the date, and a number an expression gives, are the store's to check.
            listOf(
                "RETURN date({date: date('2024-03-15'), dayOfWeek: 2}), date({date: date('2024-03-15'), dayOfQuarter: 92}), date({date: date('2024-03-15'), ordinalDay: 366})",
                "WITH 13 AS m RETURN date({date: date('2024-03-15'), month: m}), localtime({time: \$t, hour: 3}), datetime({datetime: \$t, timezone: \$z})",
            ).forEach { compiler.compile(it, mapOf("t" to java.time.LocalDateTime.of(2024, 3, 15, 10, 0), "z" to "+01:00")) }
        }
    }

    @Test
    fun `a truncation's map of fields Neo4j fails on, or that the stores have no SQL for yet, is refused`() {
        // #422: a truncation took no third argument before. Until the spec holds these (drivine-cypher-spec#33), held here.
        val refused = listOf(
            "RETURN date.truncate('month', date('2024-03-15'), {}, {})" to "date.truncate() takes a unit, a value and a map of fields.",
            "RETURN date.truncate('fortnight', date('2024-03-15'))" to
                "date.truncate() truncates to millennium, century, decade, year, weekYear, quarter, month, week, day, hour, minute, second, millisecond or microsecond, not fortnight.",
            "RETURN date.truncate('day', date('2024-03-15'), {hour: 5})" to "date.truncate() takes no field hour.",
            "RETURN localdatetime.truncate('day', localdatetime('2024-03-15T10:00'), {timezone: '+05:00'})" to "localdatetime.truncate() takes no field timezone.",
            "RETURN localtime.truncate('hour', localtime('10:30'), {day: 2})" to "localtime.truncate() takes no field day.",
            "RETURN date.truncate('day', date('2024-03-15'), {foo: 1})" to "date.truncate() takes no field foo.",
            "RETURN date.truncate('month', date('2024-03-15'), {day: 2, dayOfWeek: 3})" to "date.truncate() takes a day or a day of the week, not both.",
            "RETURN date.truncate('month', date('2024-03-15'), {day: null, dayOfWeek: 3})" to "date.truncate() takes a day or a day of the week, not both.",
            // Neo4j takes a day of the week with a year alone, not with a month, though it is null.
            "RETURN date.truncate('month', date('2024-03-15'), {month: 2, dayOfWeek: 3})" to "date.truncate() takes a month or a day of the week, not both.",
            "RETURN date.truncate('weekYear', date('2024-03-15'), {dayOfWeek: 2, year: 2000, month: null})" to "date.truncate() takes a month or a day of the week, not both.",
            "RETURN date.truncate('week', date('2024-03-15'), {dayOfWeek: 9})" to "date.truncate() takes a day of the week from 1 to 7, not 9.",
            // A number past its field's range, which a store would carry into the next field, as Neo4j fails it.
            "RETURN date.truncate('year', date('2017-02-11'), {month: -1})" to "date.truncate() takes a month from 1 to 12, not -1.",
            "RETURN localtime.truncate('hour', localtime('12:00'), {hour: 24})" to "localtime.truncate() takes an hour from 0 to 23, not 24.",
            "RETURN localtime.truncate('second', localtime('12:00'), {millisecond: 1000})" to "localtime.truncate() takes a millisecond from 0 to 999, not 1000.",
            "RETURN localtime.truncate('second', localtime('12:00'), {microsecond: 1000000})" to "localtime.truncate() takes a microsecond from 0 to 999999, not 1000000.",
            "RETURN localtime.truncate('second', localtime('12:00'), {millisecond: 5, microsecond: 1000})" to
                "localtime.truncate() takes a microsecond from 0 to 999 beside a millisecond, not 1000.",
            "RETURN localtime.truncate('millisecond', localtime('12:00'), {microsecond: 1000})" to
                "localtime.truncate() takes a microsecond from 0 to 999 beside a millisecond, not 1000.",
            "RETURN date.truncate('month', date('2024-03-15'), {day: 2.7})" to "date.truncate() takes a whole number for day, not 2.7.",
            "RETURN date.truncate('month', date('2024-03-15'), {year: '2000'})" to "date.truncate() takes a whole number for year, not '2000'.",
            "RETURN datetime.truncate('day', date('2024-03-15'), {timezone: '+0500'})" to "'+0500' is no time zone.",
            "RETURN datetime.truncate('day', date('2024-03-15'), {timezone: 'bogus'})" to "'bogus' is no time zone.",
            "RETURN datetime.truncate('day', date('2024-03-15'), {timezone: null})" to "datetime.truncate() takes a time zone, not null.",
            // Not compiled yet: sub-microsecond digits, the fields of a week or a quarter, a zone by name, and what an expression gives.
            "RETURN datetime.truncate('day', date('2024-03-15'), {nanosecond: 2})" to "datetime.truncate() of a map with nanosecond is Cypher, and is not compiled to SQL yet.",
            "RETURN date.truncate('month', date('2024-03-15'), {week: 2})" to "date.truncate() of a map with week is Cypher, and is not compiled to SQL yet.",
            "RETURN datetime.truncate('day', date('2024-03-15'), {timezone: 'Europe/Stockholm'})" to "The time zone 'Europe/Stockholm' is Cypher, and is not compiled to SQL yet.",
            "WITH {day: 2} AS fields RETURN date.truncate('month', date('2024-03-15'), fields)" to
                "A truncation's fields that an expression gives is Cypher, and is not compiled to SQL yet.",
            "WITH '+01:00' AS zone RETURN datetime.truncate('day', date('2024-03-15'), {timezone: zone})" to
                "A truncation to a time zone an expression gives is Cypher, and is not compiled to SQL yet.",
        )
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            refused.forEach { (cypher, message) ->
                assertEquals(message, assertThrows<DrivineException>("${compiler::class.simpleName}: $cypher") { compiler.compile(cypher) }.message, cypher)
            }
            // The units the spec's tag refuses, and fields of every kind the stores have SQL for, compile.
            listOf(
                "RETURN date.truncate('decade', date('2024-03-15'))",
                "RETURN date.truncate('weekYear', date('2024-03-15'), {dayOfWeek: 2, year: 2000})",
                "RETURN localtime.truncate('second', localtime('12:00'), {microsecond: 999999})",
                "RETURN datetime.truncate('microsecond', datetime('2024-03-15T10:00Z'), {hour: 1, minute: 2, second: 3, millisecond: 4, microsecond: 5, timezone: 'Z'})",
                "WITH 3 AS d RETURN localdatetime.truncate('millennium', \$at, {day: d})",
            ).forEach { compiler.compile(it, mapOf("at" to "2024-03-15T10:00")) }
        }
    }

    @Test
    fun `an aggregate in a WHERE is refused, as Neo4j refuses it`() {
        // #489: Neo4j's 42I24, "invalid use of aggregate function". Before, SQLite and Postgres failed with an error of their own, and
        // Oracle gave rows. Until the spec holds these (drivine-cypher-spec#38), held here.
        fun where(aggregate: String) = "$aggregate aggregates, and a WHERE holds no aggregate unless its WITH aggregates, as in Neo4j: aggregate in a WITH first, " +
            "and filter what it gives in that WITH's WHERE, as in WITH x, count(*) AS c WHERE c > 1."
        fun undefined(variable: String) = "$variable is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used."
        val refused = listOf(
            "UNWIND [1, 2] AS i WITH i WHERE sum(i) > 1 RETURN i" to where("sum()"),
            "UNWIND [1, 2] AS i WITH i WHERE i > 1 AND count(*) > 0 RETURN i" to where("count(*)"),
            "MATCH (n) WHERE count(*) > 1 RETURN n" to where("count(*)"),
            "MATCH (a) WHERE a.x IN collect(a.y) RETURN a" to where("collect()"),
            "MATCH (n) RETURN [(n)-->(m) WHERE count(m) > 1 | m] AS ms" to where("count()"),
            "UNWIND [[1, 2]] AS l RETURN all(x IN l WHERE sum(x) > 1) AS a" to where("sum()"),
            "UNWIND [[1, 2]] AS l RETURN [x IN l WHERE count(x) > 1 | x] AS a" to where("count()"),
            "MATCH (a) ((x)-[:R]->(y) WHERE count(x) > 1)+ (b) RETURN a" to where("count()"),
            "MATCH (n) WHERE EXISTS { (n)-->(m) WHERE count(m) > 1 } RETURN n" to where("count()"),
            "MATCH (n) WHERE COUNT { (n)-->(m) WHERE count(m) > 1 } > 0 RETURN n" to where("count()"),
            "MATCH (n) RETURN COUNT { MATCH (n)-->(m) WHERE count(m) > 1 RETURN m } AS c" to where("count()"),
            "MATCH (n) RETURN COUNT { MATCH (n)-->(m) WITH m WHERE count(m) > 1 RETURN m } AS c" to where("count()"),
            "UNWIND [1, 2] AS i CALL (i) { WITH i WHERE sum(i) > 1 RETURN i AS j } RETURN j" to where("sum()"),
            "UNWIND [1, 2] AS i CALL { WITH i MATCH (n) WHERE max(n.x) > i RETURN n } RETURN n" to where("max()"),
            "UNWIND [1, 2] AS i CALL (i) { CALL (i) { UNWIND [1, 2] AS x WITH x WHERE sum(x) > i RETURN x } RETURN x } RETURN x" to where("sum()"),
            "MATCH (p:Person) RETURN p.name AS n UNION MATCH (m:Movie) WHERE count(*) > 1 RETURN m.title AS n" to where("count(*)"),
            "UNWIND [x IN [1, 2] WHERE count(x) > 0 | x] AS y RETURN y" to where("count()"),
            "MATCH (n) SET n.v = [x IN [1, 2] WHERE sum(x) > 0 | x]" to where("sum()"),
            "MERGE (n:N) ON CREATE SET n.v = [x IN [1, 2] WHERE min(x) > 0 | x]" to where("min()"),
            "CREATE (n:N {v: [x IN [1, 2] WHERE avg(x) > 0 | x]})" to where("avg()"),
            "FOREACH (i IN [1] | CREATE (:N {v: [x IN [1, 2] WHERE max(x) > 0 | x]}))" to where("max()"),
            // #497: a WITH whose only aggregate is in its WHERE groups nothing; in the WHERE of one that groups, a comprehension's,
            // a quantifier's or a pattern's own WHERE or body holds no aggregate either. Until the spec holds these (drivine-cypher-spec#41),
            // held here.
            "UNWIND [1, 1, 2] AS i WITH i WHERE count(*) > 1 RETURN i" to where("count(*)"),
            "UNWIND [1, 1, 2] AS i WITH * WHERE count(*) > 1 RETURN i" to where("count(*)"),
            "UNWIND [1, 1, 2] AS i WITH i, count(*) AS c WHERE all(x IN [1] WHERE count(*) > x) RETURN i, c" to where("count(*)"),
            "UNWIND [1, 1, 2] AS i WITH i, count(*) AS c WHERE size([x IN [1] WHERE count(*) > x | x]) > 0 RETURN i, c" to where("count(*)"),
            "UNWIND [1, 1, 2] AS i WITH i, count(*) AS c WHERE [x IN [1] | x + count(*)][0] > 2 RETURN i, c" to where("count(*)"),
            "UNWIND [1, 1, 2] AS i WITH DISTINCT i WHERE reduce(s = 0, x IN [1] | s + count(*)) > 0 RETURN i" to where("count(*)"),
            "MATCH (p)-[:ACTED_IN]->(m) WITH p, count(*) AS c WHERE size([(p)-[:ACTED_IN]->(x) WHERE count(x) > 1 | x]) > 0 RETURN p.name" to where("count()"),
            "MATCH (p)-[:ACTED_IN]->(m) WITH p, count(*) AS c WHERE EXISTS { (p)-[:KNOWS]->(q) WHERE count(q) > 0 } RETURN p.name" to where("count()"),
            "UNWIND [1, 2] AS i CALL (i) { UNWIND [i, i] AS x WITH x WHERE count(*) > 1 RETURN x } RETURN x" to where("count(*)"),
            // #497: there a variable from before the WITH is read only inside an aggregate, or where it is a key, written as its item is.
            "UNWIND [1, 2, 3] AS i WITH i % 2 AS k, count(*) AS c WHERE i > 0 RETURN k, c" to undefined("i"),
            "UNWIND [1, 2, 3] AS i WITH i % 2 AS k, count(*) AS c WHERE sum(i) > 2 AND i > 0 RETURN k, c" to undefined("i"),
            "UNWIND [1, 2, 3] AS i WITH DISTINCT i % 2 AS k WHERE i > 0 RETURN k" to undefined("i"),
            "MATCH (p)-[:ACTED_IN]->(m) WITH p.name AS n, count(*) AS c WHERE p.born > 1965 RETURN n, c" to undefined("p"),
            "MATCH (p)-[:ACTED_IN]->(m) WITH p, count(*) AS c WHERE m.released > 1996 RETURN p.name" to undefined("m"),
            "UNWIND [1, 1, 2] AS i WITH i, count(*) AS c WHERE sum(c) > 1 RETURN i, c" to undefined("c"),
            "UNWIND [1, 1, 2] AS i WITH i AS j, count(*) AS c WHERE sum(j) > 1 RETURN j, c" to undefined("j"),
            "UNWIND [1, 1, 2] AS i WITH i AS j, count(*) AS c WHERE any(x IN [1] WHERE x = i) RETURN j, c" to undefined("i"),
        )
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            refused.forEach { (cypher, message) ->
                val store = compiler::class.simpleName
                assertEquals(message, assertThrows<DrivineException>("$store: $cypher") { compiler.compile(cypher) }.message, "$store: $cypher")
            }
            // A WHERE over what an aggregate gave, or in a subquery or a CALL that aggregates in its own query, is compiled, as Neo4j runs it.
            listOf(
                "UNWIND [1, 2] AS i WITH count(*) AS c WHERE c > 1 RETURN c",
                "UNWIND [1, 2] AS i WITH collect(i) AS l WHERE size(l) > 1 RETURN l",
                "MATCH (n) RETURN COUNT { MATCH (n)-->(m) WITH count(m) AS c WHERE c > 0 RETURN c } AS k",
                "MATCH (n) WHERE EXISTS { MATCH (n)-->(m) WITH count(m) AS c WHERE c > 0 RETURN c } RETURN n.x",
                "MATCH (n) CALL (n) { MATCH (n)-->(m) WITH count(m) AS c WHERE c > 0 RETURN c } RETURN n.x, c",
                "MATCH (n) WITH n, count { (n)-->() } AS c WHERE c > 0 RETURN n.x",
                // #497: the WHERE of a WITH that aggregates, or is DISTINCT, filters its groups, an aggregate in it among them.
                "UNWIND [1, 1, 2] AS i WITH i, count(*) AS c WHERE count(*) > 1 RETURN i",
                "UNWIND [1, 1, 2] AS i WITH i, count(*) AS c WHERE sum(i) > 1 RETURN i",
                "UNWIND [1, 2] AS i WITH i AS j, count(*) AS c WHERE j > count(*) RETURN j",
                "UNWIND [1, 1, 2] AS i WITH DISTINCT i WHERE count(*) > 1 RETURN i",
                "UNWIND [1, 1, 2] AS i WITH i AS j, count(*) AS c WHERE i > 1 RETURN j, c",
                "UNWIND [1, 1, 2] AS i WITH i, count(*) AS c WHERE all(x IN collect(i) WHERE x > 1) RETURN i, c",
                "MATCH (n) WITH n, count(*) AS c WHERE EXISTS { MATCH (n)-->(m) WITH m, count(*) AS k WHERE count(*) > 0 RETURN m } RETURN n.x",
                "UNWIND [1, 2] AS i CALL (i) { UNWIND [i, i] AS x WITH x, count(*) AS c WHERE count(*) > 1 RETURN c } RETURN i, c",
            ).forEach { compiler.compile(it) }
        }
    }

    @Test
    fun `an aggregating item or order term that names a variable no item groups by, in a comprehension's or a pattern's body too, is refused`() {
        // #482: Neo4j's 42I18, "reference to non-grouping sub-expression". The body of a comprehension, a quantifier, a reduce() or a
        // pattern was not read for the variables it names, nor an order term at all. Until the spec holds these (drivine-cypher-spec#38),
        // held here.
        fun item(name: String, variable: String) = "$name aggregates, and names $variable outside its aggregates, which no other item is: " +
            "Cypher would group by it without being told. Aggregate in a WITH first, as in WITH $variable, collect(…) AS items RETURN { … }."
        fun sort(variable: String) = "A term of an ORDER BY aggregates, and names $variable outside its aggregates and the items: " +
            "Cypher would group by it without being told. Order by an item's name, or by an expression of the items and aggregates."
        val refused = listOf(
            "UNWIND [1, 2] AS x UNWIND [3] AS z RETURN x, [y IN collect(z) | y + z] AS l" to item("l", "z"),
            "UNWIND [1, 2] AS x UNWIND [3] AS z WITH x, [y IN collect(z) | y + z] AS l RETURN x, l" to item("l", "z"),
            "UNWIND [1, 2] AS x UNWIND [3] AS z RETURN x, [y IN collect(x) WHERE y > z | y] AS l" to item("l", "z"),
            "UNWIND [1, 2] AS x UNWIND [3] AS z RETURN x, any(y IN collect(x) WHERE y > z) AS l" to item("l", "z"),
            "UNWIND [1, 2] AS x UNWIND [3] AS z RETURN x, reduce(s = 0, y IN collect(x) | s + z) AS l" to item("l", "z"),
            "UNWIND [{a: 1, b: 2}] AS x RETURN x.a, [y IN collect(x) | y.a + x.b] AS l" to item("l", "x"),
            "MATCH (n) RETURN size([(n)-->(m) | m]) + count(*) AS c" to item("c", "n"),
            "MATCH (n) RETURN n.x, size([(n)-->(m) | m.x]) + count(*) AS c" to item("c", "n"),
            "MATCH (n) RETURN EXISTS { (n)-->() } AND count(*) > 0 AS b" to item("b", "n"),
            "UNWIND [{a: 1}, {a: 2}] AS x RETURN x.a AS a, count(*) AS c ORDER BY x.b + count(*)" to sort("x"),
            "UNWIND [{a: 1, b: 2}] AS x WITH x.a AS a, count(*) AS c ORDER BY x.b + count(*) RETURN a" to sort("x"),
            "UNWIND [1, 2] AS x UNWIND [3] AS z RETURN x, count(*) AS c ORDER BY z + count(*)" to sort("z"),
            "UNWIND [1, 2] AS x UNWIND [3] AS z RETURN x, count(*) AS c ORDER BY [w IN collect(z) | w + z]" to sort("z"),
            "MATCH (me:Person)--(you:Person) RETURN count(you.age) AS agg ORDER BY me.age + count(you.age)" to sort("me"),
            "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p AS q, count(*) AS c ORDER BY size(keys(m {.title})) + count(*)" to sort("m"),
            // Neo4j runs these, but reads p in the pattern as a new variable that matches any node, not as the key q is.
            "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p AS q, count(*) AS c ORDER BY size([(p)-[:KNOWS]->(k) | k]) + count(*)" to sort("p"),
            "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p AS q, count(*) AS c ORDER BY EXISTS { (p)-[:KNOWS]->() } AND count(*) > 1" to sort("p"),
        )
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            refused.forEach { (cypher, message) ->
                val store = compiler::class.simpleName
                assertEquals(message, assertThrows<DrivineException>("$store: $cypher") { compiler.compile(cypher) }.message, "$store: $cypher")
            }
            // A body or an order term that names only what the projection groups by, an item's name, or a variable of its own is compiled,
            // as Neo4j runs it.
            listOf(
                "UNWIND [1, 2] AS x RETURN x, [y IN collect(x) | y + x] AS l",
                "UNWIND [1, 2] AS x RETURN x, collect(x) AS c",
                "UNWIND [1, 2] AS x RETURN [x IN collect(x) | x + 1] AS l",
                "UNWIND [1, 2] AS x RETURN x, reduce(s = 0, y IN collect(x) | s + y + x) AS l",
                "UNWIND [{a: 1}, {a: 2}] AS x RETURN x.a, count(*) AS c",
                "UNWIND [{a: 1}] AS x RETURN x.a, [y IN collect(x) | y.a + x.a] AS l",
                "MATCH (n) RETURN n, size([(n)-->(m) | m]) + count(*) AS c",
                "MATCH (n) RETURN n, EXISTS { (n)-->() } AND count(*) > 0 AS b",
                "UNWIND [{a: 1}, {a: 2}] AS x RETURN x.a AS a, count(*) AS c ORDER BY x.a + count(*)",
                "UNWIND [1, 2] AS x RETURN x AS y, count(*) AS c ORDER BY x + count(*)",
                "UNWIND [1, 2] AS x RETURN x AS y, count(*) AS c ORDER BY y + count(*)",
                "UNWIND [{a: 1, b: 2}] AS x RETURN x.a + x.b, count(*) AS cnt ORDER BY x.a + x.b + count(*)",
                "UNWIND [1, 2] AS x UNWIND [3] AS z RETURN x, count(*) AS c ORDER BY [w IN collect(z) | w + x]",
                // A key an item renames, named by an order term's map projection, or by a pattern where no item renames it.
                "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p AS q, count(*) AS c ORDER BY size(keys(p {.name})) + count(*)",
                "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p AS q, count(*) AS c ORDER BY p {.born}.born + count(*)",
                "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p, count(*) AS c ORDER BY size([(p)-[:KNOWS]->(k) | k]) + count(*)",
            ).forEach { compiler.compile(it) }
        }
    }

    @Test
    fun `an aggregating item reads a key that is a variable or a variable's property, and an order term a key written in any case`() {
        // #491: Neo4j reads `x.a` in `x.a + count(*)` as the key `x.a`, but no key of another shape there (its 42I18), and in a
        // pattern's parts only a key that is a variable. It names a function in any case. Until the spec holds these
        // (drivine-cypher-spec#39), held here.
        fun item(name: String, variable: String) = "$name aggregates, and names $variable outside its aggregates, which no other item is: " +
            "Cypher would group by it without being told. Aggregate in a WITH first, as in WITH $variable, collect(…) AS items RETURN { … }."
        val refused = listOf(
            "UNWIND [{a: 1, b: 2}] AS x RETURN x.a AS a, x.b + count(*) AS c" to item("c", "x"),
            "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN toUpper(p.name) AS u, toUpper(p.name) + count(*) AS c ORDER BY u" to item("c", "p"),
            "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN toUpper(p.name) AS u, [t IN collect(m.title) | t + toUpper(p.name)] AS c ORDER BY u" to item("c", "p"),
            "UNWIND [{a: {b: 1}}, {a: {b: 1}}, {a: {b: 2}}] AS x RETURN x.a.b AS k, x.a.b + count(*) AS c ORDER BY k" to item("c", "x"),
            "UNWIND [{a: {b: 1}}, {a: {b: 1}}, {a: {b: 2}}] AS x RETURN x.a.b AS k, [y IN collect(x) | y.a.b + x.a.b] AS c ORDER BY k" to item("c", "x"),
            "UNWIND [{a: 1}, {a: 1}, {a: 2}] AS x RETURN x.a + 1 AS k, x.a + 1 + count(*) AS c ORDER BY k" to item("c", "x"),
            "UNWIND [{a: 1}, {a: 1}, {a: 2}] AS x RETURN x['a'] AS a, x['a'] + count(*) AS b ORDER BY a" to item("b", "x"),
            "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p.name AS name, size([(q:Person)-[:KNOWS]->() WHERE q.name = p.name | q]) + count(*) AS c ORDER BY name" to
                item("c", "p"),
            "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p.name AS name, [x IN collect(m) | size([(x)<-[:ACTED_IN]-(q) WHERE q.name = p.name | q])] AS c ORDER BY name" to
                item("c", "p"),
            // Unnamed, the map projection is the item p, not the variable.
            "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p {.name}, count(*) + size(p.name) AS c ORDER BY p.name" to item("c", "p"),
            "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p {.name} AS q, p {.name}.name + count(*) AS c" to item("c", "p"),
        )
        listOf(PostgresCypherCompiler("g"), OracleCypherCompiler("g"), SqliteCypherCompiler("g")).forEach { compiler ->
            refused.forEach { (cypher, message) ->
                val store = compiler::class.simpleName
                assertEquals(message, assertThrows<DrivineException>("$store: $cypher") { compiler.compile(cypher) }.message, "$store: $cypher")
            }
            listOf(
                "MATCH (x) RETURN x.a AS a, x.a + count(*) AS b",
                "UNWIND [{a: 1}, {a: 1}, {a: 2}] AS x RETURN x.a, x.a + count(*) AS b ORDER BY x.a",
                "UNWIND [{a: 1}, {a: 1}, {a: 2}] AS x RETURN x.a + count(*) AS b, x.a AS a ORDER BY a",
                "UNWIND [{a: 1}, {a: 1}, {a: 2}] AS x RETURN x.a AS a, count(*) + x.a AS b, max(x.a) - x.a AS c ORDER BY a",
                "MATCH (:Person)-[r:ACTED_IN]->(m) RETURN r.role AS role, CASE WHEN r.role STARTS WITH 'N' THEN count(*) ELSE 0 END AS n ORDER BY role",
                "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p.name, reduce(s = p.name, t IN collect(m.title) | s + t) AS r ORDER BY p.name",
                "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p.name, p.name IN collect(m.title) AS r ORDER BY p.name",
                "UNWIND [{a: {b: 1}}, {a: {b: 1}}, {a: {b: 2}}] AS x RETURN x.a AS k, [y IN collect(x) | x.a.b] AS c ORDER BY c",
                "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p AS q, p.name + count(*) AS c ORDER BY c",
                "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN p {.name}, collect(m.title) AS ts ORDER BY p.name",
                "MATCH (p:Person)-[:ACTED_IN]->(m) WITH p {.name}, count(*) AS c RETURN p.name, c ORDER BY p.name",
                "UNWIND [{a: 'x'}, {a: 'x'}, {a: 'y'}] AS x RETURN toUpper(x.a), count(*) ORDER BY TOUPPER(x.a) + count(*)",
                "MATCH (p:Person)-[:ACTED_IN]->(m) RETURN toUpper(p.name) AS u, COUNT(m) AS c ORDER BY toUpper(p.name) + count(m) DESC",
            ).forEach { compiler.compile(it) }
        }
    }
}
