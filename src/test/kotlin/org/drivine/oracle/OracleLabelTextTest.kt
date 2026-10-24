package org.drivine.oracle

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSql
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A label after the first is looked for by its text, among the labels as `JSON_SERIALIZE` writes them (see
 * [OracleSql.holdsLabel]): it is found whole, whatever characters it holds, and never inside another label.
 */
@OracleTests
class OracleLabelTextTest {

    private val pm = OracleTestDatabase.graphManager("labeltext")
    private val nodes = OracleGraph("labeltext").nodes
    private val fixtures = OracleTestDatabase.fixtures(pm)

    @BeforeEach
    fun seed() {
        OracleTestDatabase.clear(pm)
        OracleTestDatabase.run(
            """INSERT INTO $nodes (labels, props) VALUES (JSON('["Crafted","x\",\"Dog"]'), JSON('{"id":"crafted"}'))""",
            "INSERT INTO $nodes (labels, props) VALUES (JSON_ARRAY('Odd', 'a' || CHR(9) || 'b', 'c' || CHR(10) || 'd', " +
                "'e' || CHR(1) || 'f', 'g' || UNISTR('\\2028') || 'h', 'i' || CHR(127) || 'j' RETURNING JSON), JSON('{\"id\":\"odd\"}'))",
            """INSERT INTO $nodes (labels, props) VALUES (JSON('["Comma","a,","b"]'), JSON('{"id":"comma"}'))""",
            """INSERT INTO $nodes (labels, props) VALUES (JSON('["Comma","a[",",b"]'), JSON('{"id":"bracket"}'))""",
            """INSERT INTO $nodes (labels, props) VALUES (JSON('["Comma","x",","]'), JSON('{"id":"lone"}'))""",
        )
    }

    /** The ids of the nodes that carry every one of [labels], tested both ways the backend writes it. */
    private fun carrying(vararg labels: String): Set<String> {
        val found = listOf(false, true).map { scanned ->
            val where = OracleSql.hasLabels("n", labels.toList(), scanned)
            pm.query(QuerySpecification.withStatement("SELECT JSON_VALUE(n.props, '$.id') FROM $nodes n WHERE $where").transform(String::class.java)).toSet()
        }
        assertEquals(found[0], found[1], "scanned or not, the labels are the same test")
        return found[0]
    }

    /** The ids of the nodes a compiled Cypher `MATCH` of [labels] finds. */
    private fun matching(vararg labels: String): Set<String> {
        val pattern = labels.joinToString("") { ":`${it.replace("`", "``")}`" }
        return fixtures.query(QuerySpecification.withStatement("MATCH (n$pattern) RETURN n.id").transform(String::class.java)).toSet()
    }

    @Test
    fun `a label holding a quoted comma is one label, not two`() {
        assertEquals(emptySet(), carrying("Crafted", "Dog"))
        assertEquals(emptySet(), matching("Crafted", "Dog"))
        assertEquals(setOf("crafted"), carrying("Crafted", "x\",\"Dog"))
        assertEquals(setOf("crafted"), matching("Crafted", "x\",\"Dog"))
        assertEquals(emptySet(), carrying("Crafted", "x"))
    }

    @Test
    fun `a later label holding a control character or a line separator is found`() {
        for (label in listOf("a\tb", "c\nd", "e\u0001f", "g\u2028h", "i\u007Fj")) {
            assertEquals(setOf("odd"), carrying("Odd", label), label)
            assertEquals(setOf("odd"), matching("Odd", label), label)
        }
        assertEquals(emptySet(), carrying("Odd", "a\\tb"))
    }

    @Test
    fun `a label that is a comma is found only where a node carries it`() {
        assertEquals(setOf("lone"), carrying("Comma", ","))
        assertEquals(setOf("lone"), matching("Comma", ","))
        assertEquals(setOf("comma"), carrying("Comma", "a,"))
        assertEquals(setOf("bracket"), carrying("Comma", ",b"))
    }

    /** Labels that try the edges of the text a label is looked for in: brackets, quotes, commas and backslashes. */
    private val edges = listOf("", "\"", "[", "]", "\\", "a\\", ",\"", "\",\"", "é 😀", "\$x")

    private fun seedEdges() {
        pm.execute(
            QuerySpecification.withStatement("INSERT INTO $nodes (labels, props) VALUES (JSON(\$labels), JSON('{\"id\":\"edge\"}'))")
                .bind(mapOf("labels" to ObjectMapper().writeValueAsString(listOf("Edge") + edges))),
        )
        OracleTestDatabase.run(
            """INSERT INTO $nodes (labels, props) VALUES (JSON('["Single"]'), JSON('{"id":"single"}'))""",
            """INSERT INTO $nodes (labels, props) VALUES (JSON('[]'), JSON('{"id":"none"}'))""",
        )
    }

    @Test
    fun `a later label of brackets, quotes, commas or backslashes is found whole, and nothing near it is`() {
        seedEdges()
        for (label in edges) assertEquals(setOf("edge"), carrying("Edge", label), label)
        for (label in edges.filter { it.isNotEmpty() }) assertEquals(setOf("edge"), matching("Edge", label), label)
        for (label in listOf("a", ",", "\\\\", "a\\\\", "\"\"", "[\"", "\"]", "Edge\",\"", "é", "😀", "Edge\",\"\"")) {
            assertEquals(emptySet(), carrying("Edge", label), label)
        }
    }

    @Test
    fun `a node of one label or none is told by its text alike`() {
        seedEdges()
        assertEquals(setOf("single"), carrying("Single"))
        assertEquals(setOf("single"), matching("Single"))
        assertEquals(emptySet(), carrying("Single", ""))
        assertEquals(emptySet(), carrying("Singl"))
        assertEquals(setOf("single"), pm.query(QuerySpecification.withStatement("SELECT JSON_VALUE(n.props, '$.id') FROM $nodes n WHERE ${OracleSql.carriesLabel("n", "Single")}").transform(String::class.java)).toSet())
        assertEquals(emptySet(), pm.query(QuerySpecification.withStatement("SELECT JSON_VALUE(n.props, '$.id') FROM $nodes n WHERE JSON_VALUE(n.props, '$.id') = 'none' AND ${OracleSql.carriesLabel("n", "")}").transform(String::class.java)).toSet())
    }

    @Test
    fun `a label is written as JSON_SERIALIZE writes it, for every character up to U+00FF and the others it escapes`() {
        val text = (1..0xFF).map { it.toChar() }.joinToString("") + "\u2027\u2028\u2029\u202A\uFEFF\uFFFD😀"
        val written = pm.query(QuerySpecification.withStatement("SELECT JSON_SERIALIZE(JSON_ARRAY(\$text RETURNING JSON)) FROM dual").bind(mapOf("text" to text)).transform(String::class.java)).single()
        assertEquals("[${OracleSql.serialized(text)}]", written)
    }
}
