package org.drivine.cypher

import org.drivine.cypher.sql.OracleCypherCompiler
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * On Oracle, a node's first label is tested so the index over labels answers it, and each label after it by its text,
 * as one whole label escaped as `JSON_SERIALIZE` writes it: the backend's own test of the labels.
 */
class OracleLabelTestTextTest {

    @Test
    fun `a later label is looked for as one whole label, escaped as JSON_SERIALIZE writes it`() {
        val sql = OracleCypherCompiler("g").compile("MATCH (n:Crafted:`x\",\"Dog`:`a\tb`) RETURN n.id", emptyMap()).sql
        assertTrue("JSON_EXISTS(n1.labels, '\$[*]?(@ == \"Crafted\")')" in sql, sql)
        assertTrue("INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n1.labels), ']'), '[') || ',\"', ',\"x\\\",\\\"Dog\",\"') > 0" in sql, sql)
        assertTrue("INSTR(',' || LTRIM(RTRIM(JSON_SERIALIZE(n1.labels), ']'), '[') || ',\"', ',\"a\\tb\",\"') > 0" in sql, sql)
    }
}
