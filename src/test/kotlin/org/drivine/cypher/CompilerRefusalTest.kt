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

    private val spec = CypherSpec.load("refusals.yaml")

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
}
