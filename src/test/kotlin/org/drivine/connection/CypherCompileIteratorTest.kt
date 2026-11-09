package org.drivine.connection

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** An [Iterator] nested in a compiled statement's parameter is bound as its items, as a list is (drivine4j#722). */
class CypherCompileIteratorTest {

    private fun compiled(store: String, cypher: String, parameters: Map<String, Any?>): Map<String, Any?> = when (store) {
        "postgres" -> CypherCompile.postgres("g", cypher, parameters).parameters
        "sqlite" -> CypherCompile.sqlite("g", cypher, parameters).parameters
        else -> CypherCompile.oracle("g", cypher, parameters, { true }, { true }).parameters
    }

    @ParameterizedTest
    @ValueSource(strings = ["postgres", "oracle", "sqlite"])
    fun `an iterator in a map a read looks into is bound as a list's items`(store: String) {
        val cypher = "MATCH (n) WHERE n.id IN \$p.ids RETURN n"
        assertEquals(
            compiled(store, cypher, mapOf("p" to mapOf("ids" to listOf("a", "b")))),
            compiled(store, cypher, mapOf("p" to mapOf("ids" to listOf("a", "b").iterator()))),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["postgres", "oracle", "sqlite"])
    fun `an iterator in a row a write reads, and in an array in a map, is written as a list's items`(store: String) {
        val cypher = "UNWIND \$rows AS row MATCH (a:L {id: row.id}) SET a.t = row.t"
        assertEquals(
            compiled(store, cypher, mapOf("rows" to listOf(mapOf("id" to 1, "t" to listOf("a", "b"))))),
            compiled(store, cypher, mapOf("rows" to listOf(mapOf("id" to 1, "t" to listOf("a", "b").iterator())))),
        )
        val read = "MATCH (n) WHERE n.id IN \$p.ids[0] RETURN n"
        assertEquals(
            compiled(store, read, mapOf("p" to mapOf("ids" to listOf(listOf("a", "b"))))),
            compiled(store, read, mapOf("p" to mapOf("ids" to arrayOf(listOf("a", "b").iterator())))),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["postgres", "oracle", "sqlite"])
    fun `a sequence that can be consumed only once, in a map a read looks into, is bound as a list's items`(store: String) {
        val cypher = "MATCH (n) WHERE n.id IN \$p.ids RETURN n"
        assertEquals(
            compiled(store, cypher, mapOf("p" to mapOf("ids" to listOf("a", "b")))),
            compiled(store, cypher, mapOf("p" to mapOf("ids" to listOf("a", "b").iterator().asSequence()))),
        )
    }

    @Test
    fun `an iterator is bound as a list's items where Oracle compiles again, without the functions the store doesn't keep`() {
        val cypher = "MATCH (n) WHERE n.id IN \$p.ids " +
            "RETURN n, datetime.truncate('second', datetime('2017-10-11T12:31:14+01:00'), {hour: 3}).hour AS h"
        val asked = mutableListOf<String>()
        fun compiled(ids: Any) = CypherCompile.oracle("g", cypher, mapOf("p" to mapOf("ids" to ids)), { true }, { asked += "era"; false })
        val listed = compiled(listOf("a", "b"))
        val iterated = compiled(listOf("a", "b").iterator())
        assertEquals(listOf("era", "era"), asked)
        assertEquals(listed.parameters, iterated.parameters)
        assertEquals(listed.sql, iterated.sql)
    }

    @Test
    fun `parameters with no iterator in them are kept as they are`() {
        val parameters = mapOf("p" to mapOf("ids" to setOf("a"), "bytes" to byteArrayOf(1)), "q" to arrayOf(1))
        assertSame(parameters, CypherCompile.drained(parameters))
    }

    @Test
    fun `an iterator at any depth is its items' list, and what is beside it is itself`() {
        val beside = mapOf("k" to 1)
        val drained = CypherCompile.drained(mapOf("p" to mapOf("rows" to listOf(mapOf("t" to listOf("a").iterator())), "beside" to beside)))
        assertEquals(mapOf("p" to mapOf("rows" to listOf(mapOf("t" to listOf("a"))), "beside" to beside)), drained)
        assertSame(beside, (drained["p"] as Map<*, *>)["beside"])
    }
}
