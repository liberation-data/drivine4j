package org.drivine.oracle

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
}
