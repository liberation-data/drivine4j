package org.drivine.cypher

import org.drivine.DrivineException
import org.drivine.cypher.spec.CypherSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CypherReader.read] parses in ANTLR's two stages, SLL and then LL only where SLL bails (#655), and gives what a parse
 * with LL alone gives: the same [org.drivine.cypher.ast.Query], and for a statement that is not Cypher the same refusal.
 *
 * No statement of the spec, nor any of the constructs below, is one SLL can't decide and LL can: openCypher's grammar
 * needs no full context for them. So the LL stage is taken by a statement that is not Cypher, whose refusal it gives.
 */
class TwoStageParseTest {

    /** What [read] gives [cypher]: its query, or the message it is refused with. */
    private fun outcome(cypher: String, read: (String) -> Any): Any = try {
        read(cypher)
    } catch (e: DrivineException) {
        "refused: " + e.message
    }

    @Test
    fun `every statement of the spec reads as LL alone reads it, refused or not`() {
        var statements = 0
        for (file in listOf("queries.yaml", "writes.yaml", "temporal.yaml", "refusals.yaml")) {
            for (case in CypherSpec.load(file).cases) for (step in case.steps) {
                statements++
                assertEquals(outcome(step.cypher, CypherReader::readFullContext), outcome(step.cypher, CypherReader::read), "$file, ${case.name}: ${step.cypher}")
            }
        }
        assertTrue(statements > 1000, "the spec has $statements statements")
    }

    @Test
    fun `the constructs whose syntax overlaps read as LL alone reads them`() {
        // A pattern and a parenthesized expression, a pattern comprehension and a list, a map projection and a map, and
        // a keyword as a name: where a prediction could need more than SLL's context.
        val atoms = listOf(
            "a", "(a)", "((a))", "[(a)]", "[(a)-->(b) | b]", "[(a)-->(b) WHERE a.x | b]", "[p = (a)-->(b) | p]", "[x IN l]",
            "[x IN l WHERE x > 1 | x]", "[x IN l | x]", "(a)-->(b)", "(a:A)", "a:A|B", "a{.x}", "a{.x, y: 1}", "{a: 1}", "count(*)",
            "EXISTS { MATCH (a)-->() }", "COUNT { (a)-->() }", "CASE a WHEN 1 THEN 2 ELSE 3 END", "a[1..2]", "[a, b][0]",
            "reduce(s = 0, x IN l | s + x)", "end", "end.x", "[(end)-->(x) | x]", "1 + (2)",
        )
        val shapes = listOf(
            "RETURN %s", "MATCH (a) WHERE %s RETURN a", "WITH %s AS x RETURN x", "UNWIND %s AS x RETURN x", "MATCH (a) SET a.x = %s",
            "MERGE (a {x: %s}) RETURN a", "CALL { WITH %s AS x RETURN x } RETURN x", "MATCH (a) RETURN a { .x, y: %s }", "RETURN [%s, 1]",
        )
        for (shape in shapes) for (atom in atoms) {
            val cypher = shape.replace("%s", atom)
            assertEquals(outcome(cypher, CypherReader::readFullContext), outcome(cypher, CypherReader::read), cypher)
        }
    }

    @Test
    fun `a statement that is not Cypher is refused as LL refuses it, by the parser or the lexer`() {
        mapOf(
            "MATCH" to "line 1, column 6: no viable alternative at input 'MATCH'",
            "MATCH (a RETURN a" to "line 1, column 10: no viable alternative at input 'MATCH (a RETURN'",
            "MATCH (a)-[:R]-(b) RETURN a ORDER" to "line 1, column 34: mismatched input '<EOF>' expecting SP",
            "CREATE (a {x: 1, }) RETURN a" to "line 1, column 18: no viable alternative at input 'CREATE (a {x: 1, }'",
            "WITH 1 AS x\nRETURN x +" to "line 2, column 11: no viable alternative at input 'x +'",
            // The parser's error comes first where the lexer's would come later.
            "RETURN 1 +) 'abc" to "line 1, column 11: no viable alternative at input '1 +)'",
            "RETURN 'unterminated" to "line 1, column 8: token recognition error at: ''unterminated'",
            "RETURN 1 # 2" to "line 1, column 10: token recognition error at: '#'",
        ).forEach { (cypher, where) ->
            assertEquals("The statement is not Cypher, at $where", assertThrows<DrivineException>(cypher) { CypherReader.read(cypher) }.message)
        }
    }
}
