package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.cypher.ast.Clause
import org.drivine.cypher.ast.Expression
import org.drivine.cypher.ast.Expression.Binary.Operator
import org.drivine.cypher.ast.NodePattern
import org.drivine.cypher.ast.Pattern
import org.drivine.cypher.ast.Projection
import org.drivine.cypher.ast.RelationshipPattern
import org.drivine.cypher.ast.RelationshipPattern.Direction
import org.drivine.cypher.ast.RemoveItem
import org.drivine.cypher.ast.SetItem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CypherReaderTest {

    private operator fun <T> List<T>.component6() = this[5]

    private operator fun <T> List<T>.component7() = this[6]

    private fun returned(cypher: String): Expression =
        (CypherReader.read(cypher).clauses.last() as Clause.Return).projection.items.single().expression

    @Test
    fun `the drivine style reads as a match, a with of a pattern comprehension, and one returned map`() {
        val query = CypherReader.read(
            """
            MATCH (actor:Person {name: ${'$'}name})
            WITH actor, [(actor)-[:ACTED_IN]-(movie:Movie) | movie {.title, .released}] AS movies
            RETURN { actor: actor, movies: movies }
            """.trimIndent()
        )
        val (match, with, ret) = query.clauses
        assertEquals(
            Clause.Match(
                false,
                listOf(Pattern(null, listOf(NodePattern("actor", listOf("Person"), Expression.MapLiteral(listOf("name" to Expression.Parameter("name"))))), emptyList())),
                null,
            ),
            match,
        )
        val movies = (with as Clause.With).projection.items[1]
        assertEquals("movies", movies.name)
        assertEquals(
            Expression.PatternComprehension(
                Pattern(
                    null,
                    listOf(NodePattern("actor", emptyList(), null), NodePattern("movie", listOf("Movie"), null)),
                    listOf(RelationshipPattern(null, listOf("ACTED_IN"), Direction.EITHER, null, null)),
                ),
                null,
                Expression.MapProjection(
                    "movie",
                    listOf(Expression.MapProjection.Element.Selected("title"), Expression.MapProjection.Element.Selected("released")),
                ),
            ),
            movies.expression,
        )
        assertEquals(
            Expression.MapLiteral(listOf("actor" to Expression.Variable("actor"), "movies" to Expression.Variable("movies"))),
            (ret as Clause.Return).projection.items.single().expression,
        )
    }

    @Test
    fun `an empty map of properties in a pattern reads as none, as every node and relationship meets it`() {
        // Read as a map, it compiled to no test at all, an empty WHERE or a dangling AND on the SQL stores (drivine4j#583).
        for ((empty, without) in listOf(
            "MATCH (a {}) RETURN a" to "MATCH (a) RETURN a",
            "MATCH (a:X {})-[r:R {}]->(b {}) RETURN r" to "MATCH (a:X)-[r:R]->(b) RETURN r",
            "MATCH (a)-[:R*1..2 {}]->(b) RETURN b" to "MATCH (a)-[:R*1..2]->(b) RETURN b",
            "MATCH (a) WHERE EXISTS { (a)-[:R {}]->({}) } RETURN a" to "MATCH (a) WHERE EXISTS { (a)-[:R]->() } RETURN a",
            "MERGE (a:X {}) CREATE (a)-[:R {}]->(:Y {})" to "MERGE (a:X) CREATE (a)-[:R]->(:Y)",
        )) assertEquals(CypherReader.read(without), CypherReader.read(empty), empty)
        // A map with a key, and a parameter's map, are still read as they are.
        val properties = (CypherReader.read("MATCH (a {k: 1}), (b \$p) RETURN a").clauses.first() as Clause.Match).patterns.map { it.nodes.single().properties }
        assertTrue(properties[0] is Expression.MapLiteral && properties[1] is Expression.Parameter, properties.toString())
    }

    @Test
    fun `a relationship reads its direction, alternatives and range`() {
        val match = CypherReader.read("MATCH (a)<-[r:KNOWS|LIKES*1..3]-(b)-[*2]->(c)-[*..4]-(d)-[*]-(e) RETURN a").clauses.first() as Clause.Match
        val (first, second, third, fourth) = match.patterns.single().relationships
        assertEquals(RelationshipPattern("r", listOf("KNOWS", "LIKES"), Direction.INCOMING, RelationshipPattern.Range(1, 3), null), first)
        assertEquals(RelationshipPattern.Range(2, 2), second.range)
        assertEquals(Direction.OUTGOING, second.direction)
        assertEquals(RelationshipPattern.Range(null, 4), third.range)
        assertEquals(RelationshipPattern.Range(null, null), fourth.range)
    }

    @Test
    fun `a quantified path pattern reads as a relationship of its group, and a pattern in parentheses with no quantifier as the pattern`() {
        val match = CypherReader.read("MATCH (a) ((x:P)-[r:R]->(y) WHERE x.v < y.v){1,3} (b) RETURN a").clauses.first() as Clause.Match
        val where = Expression.Binary(Operator.LESS_THAN, Expression.Property(Expression.Variable("x"), "v"), Expression.Property(Expression.Variable("y"), "v"))
        val group = RelationshipPattern.Group(
            Pattern(
                null,
                listOf(NodePattern("x", listOf("P"), null), NodePattern("y", emptyList(), null)),
                listOf(RelationshipPattern("r", listOf("R"), Direction.OUTGOING, null, null)),
            ),
            where,
        )
        assertEquals(
            Pattern(
                null,
                listOf(NodePattern("a", emptyList(), null), NodePattern("b", emptyList(), null)),
                listOf(RelationshipPattern(null, emptyList(), Direction.OUTGOING, RelationshipPattern.Range(1, 3), null, group)),
            ),
            match.patterns.single(),
        )
        fun patterns(cypher: String) = (CypherReader.read(cypher).clauses.first() as Clause.Match).patterns
        assertEquals(patterns("MATCH (a)-[:R]->(b) RETURN a"), patterns("MATCH ((a)-[:R]->(b)) RETURN a"))
        val shortest = patterns("MATCH p = shortestPath((a)-[:R*]->(b)) RETURN p").single()
        assertEquals(Pattern.Shortest.ONE, shortest.shortest)
        assertEquals(RelationshipPattern(null, listOf("R"), Direction.OUTGOING, RelationshipPattern.Range(null, null), null), shortest.relationships.single())
    }

    @Test
    fun `a chain of comparisons reads as their conjunction, and precedence is the grammar's`() {
        assertEquals(
            Expression.Binary(
                Operator.AND,
                Expression.Binary(Operator.LESS_THAN, Expression.Literal(1L), Expression.Variable("x")),
                Expression.Binary(Operator.LESS_THAN_OR_EQUAL, Expression.Variable("x"), Expression.Literal(5L)),
            ),
            returned("RETURN 1 < x <= 5"),
        )
        assertEquals(
            Expression.Binary(Operator.ADD, Expression.Literal(1L), Expression.Binary(Operator.MULTIPLY, Expression.Literal(2L), Expression.Literal(3L))),
            returned("RETURN 1 + 2 * 3"),
        )
    }

    @Test
    fun `strings, names in backticks, parameters and numbers read as their values`() {
        assertEquals(Expression.Literal("it's \"quoted\"\n"), returned("""RETURN 'it\'s "quoted"\n'"""))
        assertEquals(Expression.Property(Expression.Variable("my var"), "odd`key"), returned("RETURN `my var`.`odd``key`"))
        assertEquals(Expression.Parameter("1"), returned("RETURN $1"))
        // As in Neo4j, and not strict openCypher: a parameter may be named with a reserved word.
        assertEquals(Expression.Parameter("skip"), returned("RETURN \$skip"))
        assertEquals(Expression.Literal(255L), returned("RETURN 0xff"))
        assertEquals(Expression.Literal(1.5e3), returned("RETURN 1.5e3"))
        // The least whole number is its digits negated, which alone are past the greatest.
        assertEquals(Expression.Literal(Long.MIN_VALUE), returned("RETURN -9223372036854775808"))
        assertEquals(Expression.Literal(Long.MIN_VALUE), returned("RETURN -0x8000000000000000"))
        assertEquals(Expression.Unary(Expression.Unary.Operator.NEGATE, Expression.Literal(1L)), returned("RETURN -1"))
        assertEquals(
            "9223372036854775808 is past the range of a whole number, from -9223372036854775808 to 9223372036854775807.",
            assertThrows<DrivineException> { CypherReader.read("RETURN 9223372036854775808") }.message,
        )
        assertEquals(
            "-9223372036854775809 is past the range of a whole number, from -9223372036854775808 to 9223372036854775807.",
            assertThrows<DrivineException> { CypherReader.read("RETURN -9223372036854775809") }.message,
        )
    }

    @Test
    fun `a float past a double's range is refused wherever it is written, and one too small to tell from 0 is 0`() {
        val range = "is past the range of a float, from -1.7976931348623157E308 to 1.7976931348623157E308."
        for ((cypher, written) in listOf(
            "RETURN 1e999" to "1e999",
            "RETURN .5e999" to ".5e999",
            "RETURN -1e400" to "-1e400",
            "RETURN - 1e400" to "-1e400",
            "RETURN -(1e400)" to "1e400",
            "RETURN 2 - 1e400" to "1e400",
            "RETURN [1, 1e400]" to "1e400",
            "RETURN {x: 1e400}" to "1e400",
            "MATCH (n {x: 1e400}) RETURN n" to "1e400",
            "MATCH (n) RETURN n SKIP 1e400" to "1e400",
            "MATCH (n) RETURN n LIMIT -1e400" to "-1e400",
        )) {
            assertEquals("$written $range", assertThrows<DrivineException>(cypher) { CypherReader.read(cypher) }.message, cypher)
        }
        assertEquals(Expression.Literal(0.0), returned("RETURN 1e-400"))
        assertEquals(Expression.Literal(Double.MAX_VALUE), returned("RETURN 1.7976931348623157E308"))
    }

    @Test
    fun `string, list and null predicates, labels, lists and functions read`() {
        assertEquals(
            Expression.Binary(Operator.STARTS_WITH, Expression.Property(Expression.Variable("n"), "name"), Expression.Parameter("p")),
            returned("RETURN n.name STARTS WITH \$p"),
        )
        assertEquals(Expression.Unary(Expression.Unary.Operator.IS_NOT_NULL, Expression.Property(Expression.Variable("n"), "x")), returned("RETURN n.x IS NOT NULL"))
        assertEquals(Expression.HasLabels(Expression.Variable("n"), listOf("A", "B")), returned("RETURN n:A:B"))
        assertEquals(Expression.Slice(Expression.Variable("xs"), null, Expression.Literal(2L)), returned("RETURN xs[..2]"))
        assertEquals(Expression.Function("apoc.text.join", false, listOf(Expression.Variable("xs"), Expression.Literal(","))), returned("RETURN apoc.text.join(xs, ',')"))
        assertEquals(Expression.Function("count", true, listOf(Expression.Variable("n"))), returned("RETURN count(DISTINCT n)"))
        assertEquals(Expression.CountStar, returned("RETURN count(*)"))
    }

    @Test
    fun `a projection reads its names, distinct, order, skip and limit`() {
        val ret = (CypherReader.read("MATCH (n) RETURN DISTINCT n.name AS name, n.age ORDER BY name DESC, n.age SKIP 2 LIMIT \$n").clauses.last() as Clause.Return).projection
        assertTrue(ret.distinct)
        assertEquals(listOf("name", "n.age"), ret.items.map { it.name })
        assertEquals(listOf(true, false), ret.orderBy.map { it.descending })
        assertEquals(Expression.Literal(2L), ret.skip)
        assertEquals(Expression.Parameter("n"), ret.limit)
        // As Neo4j names them (#491): an unnamed map projection after its variable, an alias before it, any other item as written.
        val maps = (CypherReader.read("MATCH (p) RETURN p {.name}, p {.name, .age} AS q, p {.name}.name").clauses.last() as Clause.Return).projection
        assertEquals(listOf("p", "q", "p {.name}.name"), maps.items.map { it.name })
    }

    @Test
    fun `the writes read as their clauses, in order, with or without a return`() {
        val query = CypherReader.read(
            """
            MATCH (a:Person {id: ${'$'}id})
            CREATE (a)-[:WROTE]->(p:Paper {title: 'x'})
            MERGE (t:Topic {name: 'y'}) ON CREATE SET t.made = true ON MATCH SET t += ${'$'}seen
            SET p = ${'$'}props, p:Draft, a.count = a.count + 1
            REMOVE p:Draft, p.title
            WITH a, t
            DETACH DELETE t
            """.trimIndent()
        )
        val (_, create, merge, set, remove, _, delete) = query.clauses
        assertEquals(
            Clause.Create(
                listOf(
                    Pattern(
                        null,
                        listOf(NodePattern("a", emptyList(), null), NodePattern("p", listOf("Paper"), Expression.MapLiteral(listOf("title" to Expression.Literal("x"))))),
                        listOf(RelationshipPattern(null, listOf("WROTE"), Direction.OUTGOING, null, null)),
                    )
                )
            ),
            create,
        )
        merge as Clause.Merge
        assertEquals(listOf(SetItem.Property("t", "made", Expression.Literal(true))), merge.onCreate)
        assertEquals(listOf(SetItem.Add("t", Expression.Parameter("seen"))), merge.onMatch)
        assertEquals(
            listOf(
                SetItem.Replace("p", Expression.Parameter("props")),
                SetItem.Labels("p", listOf("Draft")),
                SetItem.Property("a", "count", Expression.Binary(Operator.ADD, Expression.Property(Expression.Variable("a"), "count"), Expression.Literal(1L))),
            ),
            (set as Clause.Set).items,
        )
        assertEquals(listOf(RemoveItem.Labels("p", listOf("Draft")), RemoveItem.Property("p", "title")), (remove as Clause.Remove).items)
        assertEquals(Clause.Delete(true, listOf(Expression.Variable("t"))), delete)
        assertEquals(
            "A write to a property of anything but a variable is Cypher, and is not compiled to SQL yet.",
            assertThrows<DrivineException> { CypherReader.read("MATCH (n) SET n.a.b = 1") }.message,
        )
    }

    @Test
    fun `a reading clause straight after a write reads in the order written`() {
        val (create, unwind, match, ret) = CypherReader.read("CREATE (n:Thing) UNWIND [1, 2] AS x MATCH (m:Thing) RETURN count(m)").clauses
        assertTrue(create is Clause.Create && unwind is Clause.Unwind && match is Clause.Match && ret is Clause.Return)
        val (_, set, again, delete) = CypherReader.read("MATCH (n) SET n.a = 1 MATCH (m) DELETE m").clauses
        assertTrue(set is Clause.Set && again is Clause.Match && delete is Clause.Delete)
        // And in a part that ends in a WITH.
        val (made, read, with, _) = CypherReader.read("CREATE (n:Thing) MATCH (m:Thing) WITH count(m) AS c RETURN c").clauses
        assertTrue(made is Clause.Create && read is Clause.Match && with is Clause.With)
        // As in Neo4j, a query that returns nothing ends in a write.
        assertThrows<DrivineException> { CypherReader.read("CREATE (n) MATCH (m)") }
    }

    @Test
    fun `a union reads each query, and whether it keeps every row`() {
        val query = CypherReader.read("RETURN 1 AS v UNION ALL RETURN 2 AS v UNION ALL RETURN 3 AS v")
        assertEquals(listOf(true, true), query.unions.map { it.all })
        assertEquals(Expression.Literal(3L), (query.unions.last().clauses.single() as Clause.Return).projection.items.single().expression)
    }

    @Test
    fun `a word the grammar adds is still a name, of a variable, a property or a map's key`() {
        listOf("foreach", "shortestPath", "allShortestPaths", "transactions", "rows", "row").forEach { word ->
            assertEquals(Expression.Variable(word), returned("WITH 1 AS $word RETURN $word"))
            assertEquals(Expression.Property(Expression.Variable("n"), word), returned("RETURN n.$word"))
            assertEquals(Expression.MapLiteral(listOf(word to Expression.Literal(1L))), returned("RETURN {$word: 1}"))
        }
    }

    @Test
    fun `a parameter is named by any word, a keyword among them, in a page, a condition, a map, an unwind and a set`() {
        listOf("limit", "skip", "order", "match", "count", "end", "call", "yield", "Return", "null", "foreach").forEach { word ->
            val parameter = Expression.Parameter(word)
            assertEquals(parameter, returned("RETURN \$$word"))
            val (match, unwind, ret) = CypherReader.read("MATCH (n {k: \$$word}) WHERE n.x = \$$word UNWIND \$$word AS i RETURN i SKIP \$$word LIMIT \$$word").clauses
            assertEquals(parameter, ((match as Clause.Match).patterns.single().nodes.single().properties as Expression.MapLiteral).entries.single().second)
            assertEquals(parameter, ((match.where as Expression.Binary).right))
            assertEquals(parameter, (unwind as Clause.Unwind).list)
            assertEquals(listOf(parameter, parameter), (ret as Clause.Return).projection.let { listOf(it.skip, it.limit) })
            val set = CypherReader.read("MATCH (n) SET n.x = \$$word, n += \$$word").clauses.last() as Clause.Set
            assertEquals(listOf(SetItem.Property("n", "x", parameter), SetItem.Add("n", parameter)), set.items)
        }
    }

    @Test
    fun `a call of db labels or db relationshipTypes reads as the query that gives its rows`() {
        val labels = CypherReader.read("CALL db.labels()")
        assertEquals(Expression.Function("labels", false, listOf(Expression.Variable("#procedure"))), (labels.clauses[1] as Clause.Unwind).list)
        val projection = (labels.clauses.last() as Clause.Return).projection
        assertTrue(projection.distinct)
        assertEquals(listOf("label"), projection.items.map { it.name })
        // Within a query, a CALL { … } of its own, its WHERE a WITH's in it, and what it yields named as the YIELD names it.
        val call = CypherReader.read("MATCH (m) CALL db.relationshipTypes() YIELD relationshipType AS t WHERE t <> m.x RETURN t").clauses[1] as Clause.Call
        val (_, with, ret) = call.query.clauses
        assertEquals(Expression.Function("type", false, listOf(Expression.Variable("#procedure"))), (with as Clause.With).projection.items.single().expression)
        assertEquals(listOf("t"), with.projection.items.map { it.name })
        assertEquals(Expression.Variable("t"), ((with.where as Expression.Binary).left))
        assertEquals(listOf("t"), (ret as Clause.Return).projection.items.map { it.name })
    }

    @Test
    fun `a call of a subquery reads the variables its brackets import`() {
        // Each form: whether it is OPTIONAL, the variables its brackets name (null with no brackets), and whether it imports each, (*).
        listOf(
            "MATCH (a) CALL { WITH a RETURN a AS x } RETURN x" to Triple(false, null, false),
            "MATCH (a) CALL () { RETURN 1 AS x } RETURN x" to Triple(false, emptyList<String>(), false),
            "MATCH (a), (b) CALL (a, b) { RETURN a AS x } RETURN x" to Triple(false, listOf("a", "b"), false),
            "MATCH (a), (b) CALL(a,b){ RETURN a AS x } RETURN x" to Triple(false, listOf("a", "b"), false),
            "MATCH (a) CALL (*) { RETURN a AS x } RETURN x" to Triple(false, emptyList<String>(), true),
            "MATCH (a) OPTIONAL CALL (a) { MATCH (a)-->(b) RETURN b } RETURN b" to Triple(true, listOf("a"), false),
            "MATCH (a) CALL (a) { SET a.x = 1 } IN TRANSACTIONS OF 5 ROWS" to Triple(false, listOf("a"), false),
        ).forEach { (cypher, expected) ->
            val call = CypherReader.read(cypher).clauses.filterIsInstance<Clause.Call>().single()
            assertEquals(expected, Triple(call.optional, call.imports, call.importsAll), cypher)
        }
    }

    @Test
    fun `cypher the compiler does not take is refused by name, and what is not cypher with where`() {
        assertEquals(
            "A procedure call (CALL) is Cypher, and is not compiled to SQL yet.",
            assertThrows<DrivineException> { CypherReader.read("CALL db.indexes()") }.message,
        )
        val syntax = assertThrows<DrivineException> { CypherReader.read("MATCH (n RETURN n") }.message!!
        assertTrue(syntax.startsWith("The statement is not Cypher, at line 1, column "), syntax)
    }

    @Test
    fun `what is still not cypher, now a keyword may name a variable, is refused where it stops being cypher`() {
        listOf(
            "MATCH (n RETURN n" to "line 1, column 10",
            "RETURN CASE WHEN 1 THEN 2" to "line 1, column 26",
            "RETURN CASE WHEN true THEN end" to "line 1, column 31",
            "MATCH (n) RETURN n ORDER" to "line 1, column 25",
            "MATCH (n)\nWITH n\nRETURN n ORDER BY" to "line 3, column 18",
        ).forEach { (cypher, at) ->
            val syntax = assertThrows<DrivineException>(cypher) { CypherReader.read(cypher) }.message!!
            assertTrue(syntax.startsWith("The statement is not Cypher, at $at: "), syntax)
        }
    }

    @Test
    fun `a keyword names a variable as Neo4j takes it, and a CASE still ends at its END`() {
        // APOC's export names a relationship's nodes start and end (#635).
        val apoc = CypherReader.read(
            "UNWIND [{start: {_id: 1}, end: {_id: 2}}] AS row MATCH (start:L {id: row.start._id}) MATCH (end:L {id: row.end._id}) CREATE (start)-[r:T]->(end)"
        )
        val (_, _, end, create) = apoc.clauses
        assertEquals("end", (end as Clause.Match).patterns.single().nodes.single().variable)
        assertEquals(listOf("start", "end"), (create as Clause.Create).patterns.single().nodes.map { it.variable })
        assertEquals(
            Expression.Case(null, listOf(Expression.Variable("when") to Expression.Variable("end")), Expression.Variable("else")),
            returned("WITH 1 AS when, 2 AS end, 3 AS else RETURN CASE WHEN when THEN end ELSE else END"),
        )
        for (word in listOf("match", "MATCH", "order", "return", "case", "distinct", "not", "in", "is", "as", "all", "call", "yield", "skip", "limit", "union", "exists")) {
            assertEquals(Expression.Variable(word), returned("UNWIND [1] AS $word RETURN $word"), word)
        }
        assertEquals(Expression.Variable("desc"), (CypherReader.read("UNWIND [1] AS desc RETURN desc ORDER BY desc DESC").clauses.last() as Clause.Return).projection.items.single().expression)
        // As written, so End is another variable than end, and between backticks the same.
        assertEquals("End", (CypherReader.read("MATCH (End) RETURN end").clauses.first() as Clause.Match).patterns.single().nodes.single().variable)
        assertEquals("end", (CypherReader.read("MATCH (`end`) RETURN end").clauses.first() as Clause.Match).patterns.single().nodes.single().variable)
        // A value stays one.
        assertEquals(Expression.Literal(null), returned("RETURN null"))
        assertEquals(Expression.Literal(true), returned("RETURN true"))
        assertEquals(
            Expression.Unary(Expression.Unary.Operator.NOT, Expression.Variable("x")),
            returned("WITH true AS x RETURN NOT x"),
        )
    }

    @Test
    fun `a keyword names a variable wherever a variable stands`() {
        val v = Expression::Variable
        val (_, foreach) = CypherReader.read("UNWIND [1] AS end FOREACH (end IN [1] | CREATE (:A {v: end}))").clauses
        assertEquals("end", (foreach as Clause.Foreach).variable)
        assertEquals(listOf("end", "case"), (CypherReader.read("WITH 1 AS end, 2 AS case CALL (end, case) { RETURN end + case AS e } RETURN e").clauses[1] as Clause.Call).imports)
        val (_, set, remove) = CypherReader.read("MATCH (end) SET end.x = 1, end:L, end += {a: 1}, end = {} REMOVE end.x, end:L").clauses
        assertEquals(
            listOf(SetItem.Property("end", "x", Expression.Literal(1L)), SetItem.Labels("end", listOf("L")), SetItem.Add("end", Expression.MapLiteral(listOf("a" to Expression.Literal(1L)))), SetItem.Replace("end", Expression.MapLiteral(emptyList()))),
            (set as Clause.Set).items,
        )
        assertEquals(listOf(RemoveItem.Property("end", "x"), RemoveItem.Labels("end", listOf("L"))), (remove as Clause.Remove).items)
        assertEquals(v("end"), returned("CALL db.labels() YIELD label AS end RETURN end"))
        val path = CypherReader.read("MATCH end = (a)-[case]->(b) RETURN end").clauses.first() as Clause.Match
        assertEquals("end", path.patterns.single().variable)
        assertEquals("case", path.patterns.single().relationships.single().variable)
        assertEquals(Expression.ListComprehension("end", v("l"), Expression.Binary(Operator.GREATER_THAN, v("end"), Expression.Literal(0L)), v("end")), returned("WITH [1] AS l RETURN [end IN l WHERE end > 0 | end]"))
        assertEquals(Expression.Quantifier(Expression.Quantifier.Kind.ALL, "all", v("l"), v("all")), returned("WITH [true] AS l RETURN all(all IN l WHERE all)"))
        assertEquals(Expression.Reduce("end", Expression.Literal(0L), "in", v("l"), Expression.Binary(Operator.ADD, v("end"), v("in"))), returned("WITH [1] AS l RETURN reduce(end = 0, in IN l | end + in)"))
        assertEquals(Expression.MapProjection("end", listOf(Expression.MapProjection.Element.Selected("name"))), returned("MATCH (end) RETURN end {.name}"))
        assertEquals(
            Expression.MapProjection("n", listOf(Expression.MapProjection.Element.Variable("end"), Expression.MapProjection.Element.Entry("case", v("end")))),
            returned("MATCH (n), (end) RETURN n {end, case: end}"),
        )
        val comprehension = returned("MATCH (a) RETURN [order = (a)-[case]->(end) WHERE end.x = 1 | end.name]") as Expression.PatternComprehension
        assertEquals("order", comprehension.pattern.variable)
        assertEquals(listOf("a", "end"), comprehension.pattern.nodes.map { it.variable })
        assertEquals("case", comprehension.pattern.relationships.single().variable)
    }

    @Test
    fun `a keyword that names a variable beside the same keyword reads as Neo4j reads it`() {
        val v = Expression::Variable
        fun projection(cypher: String) = (CypherReader.read(cypher).clauses.last() as Clause.Return).projection
        assertEquals(Projection(false, false, listOf(Projection.Item(v("distinct"), "distinct")), emptyList(), null, null), projection("UNWIND [1] AS distinct RETURN distinct"))
        assertEquals(Projection(true, false, listOf(Projection.Item(v("distinct"), "distinct")), emptyList(), null, null), projection("UNWIND [1] AS distinct RETURN DISTINCT distinct"))
        assertEquals(listOf(Projection.SortItem(v("desc"), true)), projection("UNWIND [1] AS desc RETURN desc ORDER BY desc DESC").orderBy)
        assertEquals(listOf(Projection.SortItem(v("asc"), false)), projection("UNWIND [1] AS asc RETURN asc ORDER BY asc").orderBy)
        assertEquals(listOf(Projection.SortItem(v("order"), false)), (CypherReader.read("UNWIND [1] AS order WITH order ORDER BY order RETURN order").clauses[1] as Clause.With).projection.orderBy)
        val paged = projection("UNWIND [1] AS skip WITH skip AS limit RETURN limit SKIP 0 LIMIT 1")
        assertEquals(Expression.Literal(0L) to Expression.Literal(1L), paged.skip to paged.limit)
        // count(distinct) is count() of DISTINCT and nothing, as it was before a keyword could name a variable.
        assertEquals(Expression.Function("count", true, emptyList()), returned("UNWIND [1] AS x RETURN count(distinct)"))
        assertEquals(Expression.Function("count", true, listOf(v("distinct"))), returned("UNWIND [1] AS distinct RETURN count(DISTINCT distinct)"))
        val where = CypherReader.read("MATCH (where) WHERE where.x = 1 RETURN where").clauses.first() as Clause.Match
        assertEquals("where", where.patterns.single().nodes.single().variable)
        assertEquals(Expression.Binary(Operator.EQUALS, Expression.Property(v("where"), "x"), Expression.Literal(1L)), where.where)
        assertEquals(Expression.Case(v("end"), listOf(Expression.Literal(1L) to v("end")), v("then")), returned("WITH 1 AS end, 0 AS then RETURN CASE end WHEN 1 THEN end ELSE then END"))
        val exists = projection("UNWIND [1] AS exists RETURN exists, EXISTS { MATCH (exists) } AS e").items.map { it.expression }
        assertEquals(v("exists"), exists[0])
        assertEquals(Expression.Subquery.Kind.EXISTS, (exists[1] as Expression.Subquery).kind)
        val all = projection("UNWIND [[true]] AS all RETURN all, all(x IN all WHERE x) AS a").items.map { it.expression }
        assertEquals(listOf(v("all"), Expression.Quantifier(Expression.Quantifier.Kind.ALL, "x", v("all"), v("x"))), all)
        val union = CypherReader.read("UNWIND [1] AS union RETURN union UNION ALL UNWIND [2] AS all RETURN all AS union UNION RETURN 3 AS union")
        assertEquals(listOf(true, false), union.unions.map { it.all })
        assertEquals(v("all"), (union.unions.first().clauses.last() as Clause.Return).projection.items.single().expression)
        assertEquals(Expression.Unary(Expression.Unary.Operator.NOT, v("not")), returned("UNWIND [true] AS not RETURN NOT not"))
        assertEquals(Expression.Unary(Expression.Unary.Operator.IS_NOT_NULL, v("is")), returned("UNWIND [1] AS is RETURN is IS NOT NULL"))
        assertEquals(Expression.Binary(Operator.IN, Expression.Literal(1L), v("in")), returned("UNWIND [[1]] AS in RETURN 1 IN in"))
        val merge = CypherReader.read("MERGE (on:A) ON CREATE SET on.a = 1 ON MATCH SET on.b = 2").clauses.single() as Clause.Merge
        assertEquals(listOf(SetItem.Property("on", "a", Expression.Literal(1L))) to listOf(SetItem.Property("on", "b", Expression.Literal(2L))), merge.onCreate to merge.onMatch)
        assertEquals(Clause.Delete(true, listOf(v("detach"))), CypherReader.read("MATCH (detach) DETACH DELETE detach").clauses.last())
    }
}
