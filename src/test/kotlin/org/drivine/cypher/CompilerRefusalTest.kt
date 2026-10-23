package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.cypher.sql.OracleCypherCompiler
import org.drivine.cypher.sql.PostgresCypherCompiler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * A statement the compiler cannot make SQL of, or that is wrong as Cypher is, is refused when it is compiled,
 * with what is wrong, on each store alike: before the store is sent anything.
 */
class CompilerRefusalTest {

    private val compilers = listOf(OracleCypherCompiler("g"), PostgresCypherCompiler("g"))

    private fun assertRefused(message: String, cypher: String, parameters: Map<String, Any?> = emptyMap()) = compilers.forEach { compiler ->
        assertEquals(message, assertThrows<DrivineException> { compiler.compile(cypher, parameters) }.message, compiler::class.simpleName)
    }

    @Test
    fun `a parameter the statement names and the caller did not bind is refused, in a read or a write`() {
        val message = "The statement has a parameter \$x, and no value is bound to it."
        assertRefused(message, "MATCH (n) WHERE n.a = \$x RETURN n")
        assertRefused(message, "CREATE (n {a: \$x})")
    }

    @Test
    fun `skip and limit take a whole number`() {
        assertRefused("SKIP and LIMIT take a whole number, not 1.5.", "MATCH (n) RETURN n SKIP 1.5")
    }

    @Test
    fun `a read that uses a value as what it is not is refused`() {
        assertRefused("x is not a node, and is used as one in a pattern.", "UNWIND [1] AS x MATCH (x)-->() RETURN x")
        assertRefused("x is not a node, and is used as one in a pattern.", "UNWIND [1] AS x CREATE (x)-[:R]->()")
        assertRefused("labels() takes a node.", "UNWIND [1] AS x RETURN labels(x)")
        assertRefused("A node, a relationship or a path is not a condition.", "MATCH (n) WHERE n RETURN n")
        assertRefused("A path has no properties.", "MATCH p = (a)-->(b) RETURN p.x")
        assertRefused("length() takes a path.", "MATCH (n) RETURN length(n)")
    }

    @Test
    fun `a function given the wrong number of arguments is refused`() {
        assertRefused("toLower() takes one argument.", "RETURN toLower('a', 'b')")
        assertRefused("range() takes a start, an end and a step.", "RETURN range(1)")
        assertRefused("substring() takes a text, a start and a length.", "RETURN substring('abc')")
        assertRefused("replace() takes a text, what to replace, and what with.", "RETURN replace('abc', 'a')")
    }

    @Test
    fun `what a read does not compile yet is refused by name`() {
        assertRefused("A slice of a list (list[from..to]) is Cypher, and does not run on Oracle or Postgres yet.", "RETURN [1, 2][0..1]")
        assertRefused("SKIP or LIMIT of an expression is Cypher, and does not run on Oracle or Postgres yet.", "MATCH (n) RETURN n LIMIT 1 + 1")
        assertRefused("The function nope() is Cypher, and does not run on Oracle or Postgres yet.", "RETURN nope(1)")
        // One not compiled is named whatever it is given, not taken for one of a single argument.
        assertRefused("The function apoc.text.join() is Cypher, and does not run on Oracle or Postgres yet.", "RETURN apoc.text.join(['a', 'b'], ',')")
        assertRefused("The function rand() is Cypher, and does not run on Oracle or Postgres yet.", "RETURN rand()")
        assertRefused(
            "A relationship variable bound before its pattern is Cypher, and does not run on Oracle or Postgres yet.",
            "MATCH (a)-[r]->(b) MATCH (b)-[r]->(c) RETURN c",
        )
    }

    @Test
    fun `a union, a subquery or a shortest path the compiler cannot make SQL of is refused`() {
        val yet = " is Cypher, and does not run on Oracle or Postgres yet."
        assertRefused("A write in a UNION$yet", "CREATE (n) RETURN n UNION RETURN 1 AS n")
        assertRefused("A write in EXISTS { … }$yet", "MATCH (p) WHERE EXISTS { MATCH (p)-->(q) CREATE (r) RETURN r } RETURN p")
        assertRefused("A write in COUNT { … }$yet", "MATCH (p) RETURN COUNT { MATCH (p)-->(q) CREATE (r) RETURN r }")
        assertRefused("An ORDER BY in a query of a UNION in CALL { … }$yet", "CALL { RETURN 1 AS x ORDER BY x UNION RETURN 2 AS x } RETURN x")
        assertRefused("Each query of a UNION returns the same names, as in Cypher.", "CALL { RETURN 1 AS x UNION RETURN 2 AS y } RETURN x")
        assertRefused("A UNION in CALL { … } whose queries return values of different kinds$yet", "CALL { MATCH (n) RETURN n AS x UNION RETURN 2 AS x } RETURN x")
        assertRefused("An OPTIONAL CALL { … } that writes$yet", "UNWIND [1] AS i OPTIONAL CALL (i) { CREATE (n) RETURN n } RETURN n")
        assertRefused("A UNION in CALL { … }$yet", "CALL { CREATE (n) RETURN n AS x UNION CREATE (m) RETURN m AS x } RETURN x")
        assertRefused("An aggregate in the RETURN of a CALL { … } that writes$yet", "UNWIND [1] AS i CALL (i) { CREATE (n) RETURN count(n) AS c } RETURN c")
        assertRefused(
            "A FOREACH or CALL { … } that writes nested more than 3 deep$yet",
            "FOREACH (a IN [1] | FOREACH (b IN [1] | FOREACH (c IN [1] | FOREACH (d IN [1] | CREATE (n)))))",
        )
        assertRefused("A shortest path of anything but one variable-length relationship$yet", "MATCH p = shortestPath((a)-[:R]->(b)) RETURN length(p)")
        assertRefused("A shortest path in MERGE$yet", "MERGE p = shortestPath((a)-[:R*]->(b))")
        assertRefused("A quantified relationship that has a range of its own$yet", "MATCH (a)-[:R*1..2]->{2}(b) RETURN b")
        assertRefused("A list has no properties.", "MATCH (n) WITH collect(n) AS ns RETURN ns.name")
        assertRefused("split() takes a text and a separator.", "RETURN split('a,b')")
    }

    @Test
    fun `a write to what is not there, or is not a node or a relationship, is refused`() {
        assertRefused("y is not defined: a variable is bound by a pattern, a WITH or an UNWIND before it is used.", "MATCH (n) SET y.a = 1")
        assertRefused("x is not a node or a relationship, and SET writes to one.", "UNWIND [1] AS x SET x.a = 1")
        assertRefused("r is not a node, and a label is set on one.", "MATCH ()-[r]->() SET r:L")
        assertRefused("r is not a node, and a label is removed from one.", "MATCH ()-[r]->() REMOVE r:L")
    }

    @Test
    fun `a pattern a create or merge cannot make is refused`() {
        assertRefused("a is bound already, so a CREATE or MERGE makes no node of it with labels or properties.", "MATCH (a) CREATE (a:L)")
        assertRefused("A relationship that CREATE or MERGE makes has one type.", "CREATE (a)-[:R|S]->(b)")
        assertRefused("A relationship that CREATE or MERGE makes has one type.", "CREATE (a)-[]->(b)")
    }

    @Test
    fun `what a write does not compile yet is refused by name`() {
        assertRefused("A DELETE of anything but a variable is Cypher, and does not run on Oracle or Postgres yet.", "MATCH (n) DELETE n.x")
        assertRefused("A path variable in CREATE or MERGE is Cypher, and does not run on Oracle or Postgres yet.", "CREATE p = (a)-[:R]->(b)")
        assertRefused(
            "A relationship variable bound before its pattern is Cypher, and does not run on Oracle or Postgres yet.",
            "MATCH (a)-[r]->(b) CREATE (a)-[r:R]->(b)",
        )
    }
}
