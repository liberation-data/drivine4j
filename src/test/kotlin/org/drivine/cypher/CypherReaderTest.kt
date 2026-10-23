package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.cypher.ast.Clause
import org.drivine.cypher.ast.Expression
import org.drivine.cypher.ast.Expression.Binary.Operator
import org.drivine.cypher.ast.NodePattern
import org.drivine.cypher.ast.Pattern
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
        // Strict openCypher: a parameter is not named with a reserved word.
        assertThrows<DrivineException> { CypherReader.read("RETURN \$skip") }
        assertEquals(Expression.Literal(255L), returned("RETURN 0xff"))
        assertEquals(Expression.Literal(1.5e3), returned("RETURN 1.5e3"))
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
    fun `cypher the compiler does not take is refused by name, and what is not cypher with where`() {
        assertEquals(
            "A procedure call (CALL) is Cypher, and is not compiled to SQL yet.",
            assertThrows<DrivineException> { CypherReader.read("CALL db.labels()") }.message,
        )
        val syntax = assertThrows<DrivineException> { CypherReader.read("MATCH (n RETURN n") }.message!!
        assertTrue(syntax.startsWith("The statement is not Cypher, at line 1, column "), syntax)
    }
}
