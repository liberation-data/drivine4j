package org.drivine.test

import org.drivine.test.MiniCypherAst.Branch
import org.drivine.test.MiniCypherAst.Clause
import org.drivine.test.MiniCypherAst.Comparison
import org.drivine.test.MiniCypherAst.Direction
import org.drivine.test.MiniCypherAst.EdgePattern
import org.drivine.test.MiniCypherAst.Expression
import org.drivine.test.MiniCypherAst.Expression.And
import org.drivine.test.MiniCypherAst.Expression.Call
import org.drivine.test.MiniCypherAst.Expression.Case
import org.drivine.test.MiniCypherAst.Expression.Compare
import org.drivine.test.MiniCypherAst.Expression.In
import org.drivine.test.MiniCypherAst.Expression.IsNull
import org.drivine.test.MiniCypherAst.Expression.ListOf
import org.drivine.test.MiniCypherAst.Expression.Literal
import org.drivine.test.MiniCypherAst.Expression.Lookup
import org.drivine.test.MiniCypherAst.Expression.MapOf
import org.drivine.test.MiniCypherAst.Expression.Parameter
import org.drivine.test.MiniCypherAst.Expression.Plus
import org.drivine.test.MiniCypherAst.Expression.Property
import org.drivine.test.MiniCypherAst.Expression.StartsWith
import org.drivine.test.MiniCypherAst.Expression.Variable
import org.drivine.test.MiniCypherAst.NodePattern
import org.drivine.test.MiniCypherAst.Order
import org.drivine.test.MiniCypherAst.Pattern
import org.drivine.test.MiniCypherAst.SetItem
import org.drivine.test.MiniCypherAst.Statement
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The tree the parser makes of a fixture, and what it refuses before anything is worked out: each
 * refusal says where in the text it stands.
 */
class MiniCypherParserTest {

    private fun parse(text: String, vararg parameters: String): Statement = MiniCypherParser(text, setOf(*parameters)).statement()

    private fun clauses(text: String, vararg parameters: String): List<Clause> = parse(text, *parameters).clauses

    /** The expression a `RETURN` of [text] holds. */
    private fun expression(text: String, vararg parameters: String): Expression =
        (clauses("RETURN $text", *parameters).single() as Clause.Return).value

    private fun refused(text: String, vararg parameters: String): String =
        assertFailsWith<IllegalArgumentException> { parse(text, *parameters) }.message!!

    /** That [text] is refused with [message] at the first place [where] stands in it, and that the refusal shows the text from there. */
    private fun assertRefusedAt(text: String, where: String, message: String, vararg parameters: String) {
        val at = text.indexOf(where)
        check(at >= 0) { "'$where' is not in: $text" }
        assertEquals("$message at $at: ${text.substring(at).take(40)}… in: $text", refused(text, *parameters))
    }

    private fun function(name: String): MiniCypherFunction = MiniCypherFunction.named(name)!!

    private fun node(variable: String? = null, vararg labels: String, properties: Map<String, Expression> = emptyMap()) =
        NodePattern(variable, labels.toList(), properties)

    // ----- Statements and clauses -----

    @Test
    fun `text with no clause in it is a statement of none`() {
        assertEquals(Statement(emptyList()), parse(""))
        assertEquals(Statement(emptyList()), parse("  \n "))
    }

    @Test
    fun `clauses are read in the order written, each closed by a semicolon or by nothing`() {
        assertEquals(
            listOf(
                Clause.Match(listOf(Pattern(listOf(node("n", "T")))), null),
                Clause.DetachDelete("n"),
                Clause.Create(listOf(Pattern(listOf(node("m", "T"))))),
            ),
            clauses("MATCH (n:T) DETACH DELETE n; CREATE (m:T);"),
        )
    }

    @Test
    fun `MATCH holds its patterns and its WHERE`() {
        assertEquals(
            listOf(
                Clause.Match(
                    listOf(Pattern(listOf(node("a", "A", "B"))), Pattern(listOf(node("b")))),
                    Compare(Comparison.EQUAL, Property("a", "id"), Property("b", "id")),
                ),
            ),
            clauses("MATCH (a:A:B), (b) WHERE a.id = b.id"),
        )
    }

    @Test
    fun `a pattern holds its nodes, and the relationship between each and the next with its direction`() {
        val pattern = (clauses("MATCH (a:Human {id: 'ada'})-[r:KNOWS {since: 2020}]->(b)<-[:LIKES]-(:Pet)-[]-()--(z)").single() as Clause.Match).patterns.single()
        assertEquals(
            Pattern(
                listOf(node("a", "Human", properties = mapOf("id" to Literal("ada"))), node("b"), node(null, "Pet"), node(), node("z")),
                listOf(
                    EdgePattern("r", "KNOWS", mapOf("since" to Literal(2020L)), Direction.OUTGOING),
                    EdgePattern(type = "LIKES", direction = Direction.INCOMING),
                    EdgePattern(direction = Direction.EITHER),
                    EdgePattern(direction = Direction.EITHER),
                ),
            ),
            pattern,
        )
    }

    @Test
    fun `a pattern's properties are expressions that name no variable`() {
        val create = clauses("CREATE (n:T {id: \$id, made: randomUUID(), tags: ['a', 'b'], n: 1 + 2})", "id").single() as Clause.Create
        assertEquals(
            mapOf(
                "id" to Parameter("id"),
                "made" to Call(function("randomUUID"), emptyList()),
                "tags" to ListOf(listOf(Literal("a"), Literal("b"))),
                "n" to Plus(Literal(1L), Literal(2L)),
            ),
            create.patterns.single().nodes.single().properties,
        )
    }

    @Test
    fun `MERGE holds one pattern, and may leave a relationship without a direction for the graph to decide`() {
        assertEquals(
            listOf(
                Clause.Merge(Pattern(listOf(node("a", "T")))),
                Clause.Merge(Pattern(listOf(node("a"), node("b")), listOf(EdgePattern(type = "X", direction = Direction.EITHER)))),
            ),
            clauses("MERGE (a:T) MERGE (a)-[:X]-(b)"),
        )
    }

    @Test
    fun `SET holds its items, each a property with its value or labels to add`() {
        assertEquals(
            listOf(
                Clause.Set(
                    listOf(
                        SetItem.Property("n", "name", Literal("Ada")),
                        SetItem.Labels("n", listOf("A", "B")),
                        SetItem.Property("n", "a b", Plus(Property("n", "x"), Literal(1L))),
                    ),
                ),
            ),
            clauses("MATCH (n) SET n.name = 'Ada', n:A:B, n.`a b` = n.x + 1").drop(1),
        )
    }

    @Test
    fun `REMOVE, DETACH DELETE and UNWIND hold what they name`() {
        assertEquals(
            listOf(Clause.Unwind(Parameter("rows"), "r"), Clause.Match(listOf(Pattern(listOf(node("n")))), null), Clause.Remove("n", "x"), Clause.DetachDelete("n")),
            clauses("UNWIND \$rows AS r MATCH (n) REMOVE n.x DETACH DELETE n", "rows"),
        )
    }

    @Test
    fun `WITH holds the variables carried on and its WHERE`() {
        assertEquals(
            Clause.With(listOf("a", "c"), StartsWith(Variable("a"), Literal("m"))),
            clauses("UNWIND [1] AS a UNWIND [2] AS b UNWIND [3] AS c WITH a, c WHERE a STARTS WITH 'm'")[3],
        )
    }

    @Test
    fun `RETURN holds one value, its alias and its ORDER BY`() {
        assertEquals(Clause.Return(Property("n", "id"), null, null), clauses("MATCH (n) RETURN n.id")[1])
        assertEquals(Clause.Return(Property("n", "id"), "id", null), clauses("MATCH (n) RETURN n.id AS id")[1])
        assertEquals(Clause.Return(Property("n", "id"), null, Order(Property("n", "v"), descending = false)), clauses("MATCH (n) RETURN n.id ORDER BY n.v")[1])
        assertEquals(Clause.Return(Property("n", "id"), null, Order(Property("n", "v"), descending = false)), clauses("MATCH (n) RETURN n.id ORDER BY n.v ASC")[1])
        assertEquals(Clause.Return(Property("n", "id"), "id", Order(Variable("id"), descending = true)), clauses("MATCH (n) RETURN n.id AS id ORDER BY id DESC")[1])
    }

    @Test
    fun `RETURN of a count holds what is counted, and its alias names nothing`() {
        assertEquals(Clause.ReturnCount(Variable("n")), clauses("MATCH (n) RETURN count(n)")[1])
        assertEquals(Clause.ReturnCount(Variable("n")), clauses("MATCH (n) RETURN count (n) AS c")[1])
    }

    // ----- Expressions -----

    @Test
    fun `literals are their values`() {
        assertEquals(Literal("it's"), expression("""'it\'s'"""))
        assertEquals(Literal(42L), expression("42"))
        assertEquals(Literal(-42L), expression("-42"))
        assertEquals(Literal(-1.5), expression("-1.5"))
        assertEquals(Literal(1000.0), expression("1e3"))
        assertEquals(Literal(true), expression("true"))
        assertEquals(Literal(false), expression("false"))
        assertEquals(Literal(null), expression("null"))
    }

    @Test
    fun `lists and maps hold their items in the order written, and a map's value may be a condition`() {
        assertEquals(ListOf(emptyList()), expression("[]"))
        assertEquals(MapOf(emptyMap()), expression("{}"))
        assertEquals(ListOf(listOf(Literal(1L), ListOf(listOf(Literal("a"))), MapOf(mapOf("k" to Literal(null))))), expression("[1, ['a'], {k: null}]"))
        val map = expression("{b: 1, a: 1 = 2, `c d`: 3}") as MapOf
        assertEquals(listOf("b", "a", "c d"), map.entries.keys.toList())
        assertEquals(Compare(Comparison.EQUAL, Literal(1L), Literal(2L)), map.entries["a"])
    }

    @Test
    fun `a property is read of a variable by a name or by a key`() {
        assertEquals(
            listOf(Property("n", "id"), Property("n", "a b.c"), Lookup("n", Parameter("key")), Lookup("n", Literal("id")), Variable("n")),
            (clauses("MATCH (n) RETURN [n.id, n.`a b.c`, n[\$key], n['id'], n]", "key")[1] as Clause.Return).value.let { (it as ListOf).items },
        )
    }

    @Test
    fun `a call holds the function it names and its arguments`() {
        assertEquals(Call(function("substring"), listOf(Literal("ada"), Literal(0L), Literal(2L))), expression("substring('ada', 0, 2)"))
        assertEquals(Call(function("coalesce"), listOf(Literal(null), Parameter("p"), Literal("x"))), expression("coalesce(null, \$p, 'x')", "p"))
        assertEquals(Call(function("left"), listOf(Call(function("replace"), listOf(Call(function("randomUUID"), emptyList()), Literal("-"), Literal(""))), Literal(16L))), expression("left(replace(randomUUID(), '-', ''), 16)"))
        assertEquals(Call(function("datetime"), listOf(Literal("2024-01-01T00:00:00Z"))), expression("datetime('2024-01-01T00:00:00Z')"))
    }

    @Test
    fun `plus binds tighter than a comparison, and a comparison tighter than AND`() {
        assertEquals(Plus(Plus(Literal(1L), Literal(2L)), Literal("a")), expression("1 + 2 + 'a'"))
        assertEquals(
            And(
                And(Compare(Comparison.GREATER, Plus(Literal(1L), Literal(2L)), Literal(2L)), IsNull(Literal(null), negated = false)),
                In(Literal("a"), ListOf(listOf(Literal("a")))),
            ),
            expression("1 + 2 > 2 AND null IS NULL AND 'a' IN ['a']"),
        )
    }

    @Test
    fun `each comparison is read by its symbol`() {
        Comparison.entries.forEach { comparison ->
            assertEquals(Compare(comparison, Literal(1L), Literal(2L)), expression("1 ${comparison.symbol} 2"), comparison.symbol)
        }
        assertEquals(IsNull(Literal(1L), negated = true), expression("1 IS NOT NULL"))
        assertEquals(StartsWith(Literal("ab"), Literal("a")), expression("'ab' STARTS WITH 'a'"))
    }

    @Test
    fun `a less-than before a negative number is a comparison, and before a dash in a pattern an arrow`() {
        assertEquals(Compare(Comparison.LESS, Literal(1L), Literal(-1L)), expression("1 <-1"))
        assertEquals(Compare(Comparison.LESS, Literal(1L), Literal(-1L)), expression("1 < -1"))
        val pattern = (clauses("MATCH (a)<-[:X]-(b)").single() as Clause.Match).patterns.single()
        assertEquals(Direction.INCOMING, pattern.edges.single().direction)
    }

    @Test
    fun `a searched CASE holds its branches and its ELSE`() {
        assertEquals(
            Case(
                listOf(
                    Branch(And(Compare(Comparison.GREATER, Literal(2L), Literal(1L)), Literal(true)), Literal("big")),
                    Branch(Literal(false), Plus(Literal("o"), Literal("ne"))),
                ),
                Literal("none"),
            ),
            expression("CASE WHEN 2 > 1 AND true THEN 'big' WHEN false THEN 'o' + 'ne' ELSE 'none' END"),
        )
        assertEquals(Case(listOf(Branch(Literal(false), Literal(1L))), null), expression("CASE WHEN false THEN 1 END"))
    }

    @Test
    fun `a keyword is one only where the grammar looks for it, so a variable may bear a function's name`() {
        assertEquals(
            listOf(Clause.Unwind(ListOf(listOf(Literal(1L))), "type"), Clause.Return(Plus(Variable("type"), Literal(1L)), null, null)),
            clauses("UNWIND [1] AS type RETURN type + 1"),
        )
        assertEquals(Clause.Unwind(ListOf(listOf(Literal(1L))), "MATCH"), clauses("UNWIND [1] AS `MATCH`").single())
    }

    // ----- Refusals, each where it stands -----

    @Test
    fun `a keyword in any case is the keyword, as Cypher reads it`() {
        assertEquals(clauses("MATCH (n:T) WHERE n.a = 1 RETURN n.a AS a ORDER BY a DESC"), clauses("match (n:T) where n.a = 1 return n.a as a order by a desc"))
    }

    @Test
    fun `what is no clause is refused where it stands`() {
        assertRefusedAt("MATCH (n:T) RETURN n.id LIMIT 1", "LIMIT", "Expected a clause")
        assertRefusedAt("MATCH (n:T) RETURN n.id SKIP 1", "SKIP", "Expected a clause")
        assertRefusedAt("MATCH (a:T), (b:T) RETURN a.id, b.id", ", b.id", "Expected a clause")
        assertRefusedAt("MERGE (n:T {id: 'x'}) ON CREATE SET n.a = 1", "ON", "Expected a clause")
        assertRefusedAt("MATCH (n:T) WHERE n.a = 1 OR n.b = 2 RETURN n.a", "OR", "Expected a clause")
        assertRefusedAt("MATCH (n)-[r]->(m) DELETE r", "DELETE", "Expected a clause")
        assertRefusedAt("OPTIONAL MATCH (n) RETURN n", "OPTIONAL", "Expected a clause")
        assertRefusedAt("MATCH (n) RETURN n.id ORDER BY n.id, n.v", ", n.v", "Expected a clause")
        assertRefusedAt("MATCH (n) RETURN count(n) + 1", "+", "Expected a clause")
        assertRefusedAt("MATCH (n) RETURN n.a - 1", "- 1", "Expected a clause")
    }

    @Test
    fun `a refusal shows the forty characters it stands at and the whole statement`() {
        val text = "MATCH (n:T) RETURN n.id LIMIT 1 // and a long tail that the refusal does not show whole"
        assertEquals("Expected a clause at 24: LIMIT 1 // and a long tail that the refu… in: $text", refused(text))
    }

    @Test
    fun `a clause cut short is refused at the end of the text`() {
        assertEquals("Expected DELETE at 17: n… in: MATCH (n) DETACH n", refused("MATCH (n) DETACH n"))
        assertEquals("Expected a value at 6: … in: RETURN", refused("RETURN"))
        assertEquals("Expected '(' at 5: … in: MATCH", refused("MATCH"))
        assertEquals("Expected AS at 13: … in: UNWIND [1, 2]", refused("UNWIND [1, 2]"))
    }

    @Test
    fun `a pattern that is not closed, or names what the fixtures do not use, is refused where it stands`() {
        assertRefusedAt("MATCH (n:T RETURN n", "RETURN", "Expected ')'")
        assertRefusedAt("MATCH (n:) RETURN n", ")", "Expected a label")
        assertRefusedAt("MATCH (a)-[:X|Y]->(b) RETURN a", "|", "Expected ']'")
        assertRefusedAt("MATCH (a)-[*]->(b) RETURN a", "*", "Expected ']'")
        assertRefusedAt("MATCH (a)-[r:]->(b) RETURN a", "]", "Expected a relationship type")
        assertRefusedAt("MATCH (a)-[:X]>(b) RETURN a", ">", "Expected '-'")
        assertRefusedAt("MATCH (n:T {id 1}) RETURN n", "1", "Expected ':'")
        assertRefusedAt("MATCH (n:T {'id': 1}) RETURN n", "'id'", "Expected a property name")
        assertRefusedAt("MATCH (a) < -[:X]-(b) RETURN a", "<", "Expected a clause")
    }

    @Test
    fun `an operator the fixtures do not use is refused where it stands`() {
        assertRefusedAt("MATCH (n:T) WHERE NOT n.a = 1 RETURN n.a", "NOT", "Expected a value: the variable NOT is not bound here")
        assertRefusedAt("RETURN (1 + 2)", "(", "Expected a value")
        assertRefusedAt("RETURN 1 = 2 = 3", "= 3", "Expected a clause")
        assertRefusedAt("RETURN 1 IS 2", "2", "Expected NULL")
        assertRefusedAt("RETURN 'a' STARTS 'b'", "'b'", "Expected WITH")
        assertRefusedAt("RETURN [1 = 1]", "=", "Expected ']'")
        assertRefusedAt("RETURN {a 1}", "1", "Expected ':'")
        assertRefusedAt("MATCH (n) RETURN n.a.b", ".b", "Expected a clause")
    }

    @Test
    fun `a sign stands against its digits`() {
        assertRefusedAt("RETURN -", "-", "Expected a number")
        assertRefusedAt("RETURN - 1", "-", "Expected a number")
        assertRefusedAt("MATCH (n) RETURN -n.v", "-", "Expected a number")
    }

    @Test
    fun `a simple CASE is refused and the searched one named`() {
        assertRefusedAt(
            "MATCH (n:T) RETURN CASE n.v WHEN 1 THEN 'a' END", "n.v",
            "Expected END: the fixture interpreter reads the searched CASE alone, `CASE WHEN … THEN … END`",
        )
        assertRefusedAt("RETURN CASE WHEN true 1 END", "1", "Expected THEN")
    }

    @Test
    fun `a function not listed is refused by name where it is called, whether or not a row would reach it`() {
        assertRefusedAt("RETURN foo(1)", "foo", "The fixture interpreter does not know the function foo")
        assertRefusedAt("MATCH (n:Nope) SET n.x = toUpper(n.id)", "toUpper", "The fixture interpreter does not know the function toUpper")
        assertRefusedAt("RETURN CASE WHEN false THEN nope() END", "nope", "The fixture interpreter does not know the function nope")
    }

    @Test
    fun `a count that is not the whole of a RETURN is refused`() {
        assertRefusedAt("MATCH (n) RETURN 1 + count(n)", "count", "The fixture interpreter reads count as the whole of a RETURN alone, and here it is not")
        assertRefusedAt("MATCH (n) WITH n WHERE count(n) > 1 RETURN n", "count", "The fixture interpreter reads count as the whole of a RETURN alone, and here it is not")
        assertRefusedAt("MATCH (n) RETURN count(*)", "*", "Expected a value")
    }

    @Test
    fun `a function given a number of arguments it does not take is refused where it is called`() {
        assertRefusedAt("RETURN size('a', 'b')", "size", "The function size is given 2 arguments, and takes 1")
        assertRefusedAt("RETURN substring('a')", "substring", "The function substring is given 1 arguments, and takes 2 to 3")
        assertRefusedAt("RETURN coalesce()", "coalesce", "The function coalesce is given 0 arguments, and takes 1 or more")
        assertRefusedAt("RETURN randomUUID(1)", "randomUUID", "The function randomUUID is given 1 arguments, and takes 0")
        assertRefusedAt("RETURN datetime()", "datetime", "The function datetime is given 0 arguments, and takes 1")
    }

    @Test
    fun `a parameter with no value bound is refused by name where it stands`() {
        assertRefusedAt("RETURN \$p + \$q", "\$q", "The statement names \$q, and no value is bound to it", "p")
        assertRefusedAt("MATCH (n:Nope) SET n.x = \$missing", "\$missing", "The statement names \$missing, and no value is bound to it")
        assertEquals("Expected a parameter name at 8: … in: RETURN \$", refused("RETURN \$"))
    }

    @Test
    fun `a variable no clause before has bound is refused where it stands, whether or not a row would reach it`() {
        assertRefusedAt("RETURN x", "x", "Expected a value: the variable x is not bound here")
        assertRefusedAt("MATCH (n:Nope) RETURN m.id", "m.id", "Expected a value: the variable m is not bound here")
        assertRefusedAt("MATCH (n) WHERE m.id = 1 RETURN n", "m.id", "Expected a value: the variable m is not bound here")
        assertRefusedAt("MATCH (n) SET m.x = 1", "m.x", "Expected a variable bound before: m is not")
        assertRefusedAt("MATCH (n) SET n.x = 1, m:L", "m:L", "Expected a variable bound before: m is not")
        assertRefusedAt("MATCH (n) REMOVE m.x", "m.x", "Expected a variable bound before: m is not")
        assertRefusedAt("MATCH (n) DETACH DELETE m", "m", "Expected a variable bound before: m is not")
        assertRefusedAt("MATCH (n) WITH m RETURN n", "m ", "Expected a variable bound before: m is not")
        assertRefusedAt("MATCH (n) SET .x = 1", ".x", "Expected a variable")
    }

    @Test
    fun `a variable a WITH did not carry on is bound no longer, and an alias is bound in its ORDER BY alone`() {
        assertRefusedAt("MATCH (a), (b) WITH a RETURN b.id", "b.id", "Expected a value: the variable b is not bound here")
        assertRefusedAt("MATCH (n) RETURN n.id AS id ORDER BY id SET id.x = 1", "id.x", "Expected a variable bound before: id is not")
    }

    @Test
    fun `a pattern's property that names a variable is refused where it names it, bound or not`() {
        val message = "Expected a value: a pattern's property cannot name the variable c, for it is read once, before any row is"
        assertRefusedAt("MATCH (c:T {id: 'c'}) CREATE (o:O {owner: c.id})", "c.id", message)
        assertRefusedAt("MATCH (n:T {id: c})", "c}", message)
        assertRefusedAt("MATCH (c)-[r:X {w: c[\$k]}]->(d) RETURN r", "c[", message, "k")
        assertRefusedAt("MERGE (n:T {id: toString(c)})", "c)", message)
    }

    @Test
    fun `a CREATE of what cannot be made is refused where it is written, whether or not a row would reach it`() {
        assertRefusedAt("MATCH (a:Nope) CREATE (a)-[:X]-(b:T)", "-[:X]", "A relationship that is created needs a direction")
        assertRefusedAt("MATCH (a:Nope) CREATE (a)<-[:X]->(b:T)", "<-[:X]", "A relationship that is created needs a direction")
        assertRefusedAt("MATCH (a:Nope) CREATE (a)-[r]->(b:T)", "-[r]", "A relationship that is created needs a type")
        assertRefusedAt("CREATE (a:T)-->(b:T)", "-->", "A relationship that is created needs a type")
        assertRefusedAt("MATCH (a:Nope) CREATE (a:Other)", "a:Other", "The variable a is already bound, and cannot be given labels or properties again")
        assertRefusedAt("CREATE (a:T)-[:X]->(b:T), (b {id: 1})", "b {id", "The variable b is already bound, and cannot be given labels or properties again")
    }
}
