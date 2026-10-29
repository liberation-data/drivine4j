package org.drivine.cypher

import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.drivine.cypher.grammar.CypherLexer
import org.drivine.cypher.grammar.CypherParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class CypherGrammarTest {

    /** The statement's parse tree, or a failure for the first error the parser reports. */
    private fun parse(cypher: String): CypherParser.OC_CypherContext {
        val failing = object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String?, e: RecognitionException?,
            ) = throw IllegalArgumentException("line $line:$charPositionInLine $msg")
        }
        val lexer = CypherLexer(CharStreams.fromString(cypher)).apply { removeErrorListeners(); addErrorListener(failing) }
        val parser = CypherParser(CommonTokenStream(lexer)).apply { removeErrorListeners(); addErrorListener(failing) }
        return parser.oC_Cypher()
    }

    @Test
    fun `the drivine style parses, one returned object of a pattern comprehension and a map projection`() {
        parse(
            """
            MATCH (actor:Person {name: ${'$'}name})
            WITH actor, [(actor)-[:ACTED_IN]-(movie:Movie) | movie {.title, .tagline, .released}] AS movies
            RETURN { actor: actor, movies: movies }
            """.trimIndent()
        )
    }

    @Test
    fun `a map projection takes a property, a key of its own, every property and a variable`() {
        val tree = parse("MATCH (n:Person) RETURN n { .name, born: n.born, .*, n }")
        val projection = tree.oC_Statement().oC_Query().oC_RegularQuery().oC_SingleQuery().oC_SinglePartQuery().oC_Return()
            .oC_ProjectionBody().oC_ProjectionItems().oC_ProjectionItem(0).oC_Expression().text
        assertEquals("n{.name,born:n.born,.*,n}", projection.replace(" ", ""))
    }

    @Test
    fun `the read clauses a drivine statement uses parse`() {
        parse(
            """
            MATCH (p:Person)-[:WORKS_AT]->(o:Org)
            OPTIONAL MATCH (p)-[:KNOWS*1..3]-(f:Person)
            WHERE p.name STARTS WITH ${'$'}prefix AND EXISTS { (p)-[:MANAGES]->() }
            WITH p, o, collect(properties(f)) AS friends
            RETURN { person: properties(p), org: o.name, friends: [x IN friends WHERE x.age > 30 | x.name] }
            ORDER BY p.name SKIP 10 LIMIT 5
            """.trimIndent()
        )
    }

    @Test
    fun `what is not Cypher is refused with where it went wrong`() {
        val failure = assertThrows<IllegalArgumentException> { parse("MATCH (n RETURN n") }
        assert(failure.message!!.startsWith("line 1:")) { failure.message!! }
    }
}
