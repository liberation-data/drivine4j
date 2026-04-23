package org.drivine.connection

import org.drivine.query.ParameterCoercer
import org.drivine.query.QuerySpecification
import org.drivine.query.TemporalCoercer
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class ParameterCoercerChainTest {

    private class FakeConnection(
        private val coercers: List<ParameterCoercer> = emptyList()
    ) : Connection {
        override fun sessionId(): String = "fake"
        override fun <T : Any> query(spec: QuerySpecification<T>): List<T> = emptyList()
        override fun parameterCoercers(): List<ParameterCoercer> = coercers
        override fun startTransaction() {}
        override fun commitTransaction() {}
        override fun rollbackTransaction() {}
        override fun release(err: Throwable?) {}
    }

    @Test
    fun `default connection applies no coercion`() {
        val now = Instant.now()
        val conn = FakeConnection()
        val spec = QuerySpecification.withStatement("MATCH (n) RETURN n")

        val out = conn.applyParameterCoercers(spec, mapOf("ts" to now))

        assertEquals(now, out["ts"], "Neo4j-style connection must not mutate parameter values")
    }

    @Test
    fun `connection-level coercer applies to params`() {
        val conn = FakeConnection(listOf(TemporalCoercer))
        val spec = QuerySpecification.withStatement("MATCH (n) RETURN n")
        val now = Instant.parse("2026-04-23T05:07:53Z")

        val out = conn.applyParameterCoercers(spec, mapOf("ts" to now))

        assertEquals("2026-04-23T05:07:53Z", out["ts"])
    }

    @Test
    fun `spec-level coercer runs after connection coercer`() {
        val conn = FakeConnection(listOf(TemporalCoercer))
        // Spec-level upper-caser runs after TemporalCoercer, so it sees the ISO string
        val upperCase = ParameterCoercer { params ->
            params.mapValues { (_, v) -> if (v is String) v.uppercase() else v }
        }
        val spec = QuerySpecification
            .withStatement("MATCH (n) RETURN n")
            .addParameterCoercers(upperCase)
            .finalizedCopy(org.drivine.query.QueryLanguage.CYPHER)
        val now = Instant.parse("2026-04-23T05:07:53Z")

        val out = conn.applyParameterCoercers(spec, mapOf("ts" to now))

        assertEquals("2026-04-23T05:07:53Z", out["ts"])
    }

    @Test
    fun `coercers see output of previous coercer in order`() {
        val addSuffix = ParameterCoercer { p -> p.mapValues { (_, v) -> "${v}_A" } }
        val addSecondSuffix = ParameterCoercer { p -> p.mapValues { (_, v) -> "${v}_B" } }
        val conn = FakeConnection(listOf(addSuffix))
        val spec = QuerySpecification
            .withStatement("RETURN 1")
            .addParameterCoercers(addSecondSuffix)
            .finalizedCopy(org.drivine.query.QueryLanguage.CYPHER)

        val out = conn.applyParameterCoercers(spec, mapOf("x" to "hi"))

        assertEquals("hi_A_B", out["x"], "connection coercers run first, then spec-level")
    }

    @Test
    fun `empty parameter map passes through all coercers unchanged`() {
        val conn = FakeConnection(listOf(TemporalCoercer))
        val spec = QuerySpecification.withStatement("RETURN 1")

        val out = conn.applyParameterCoercers(spec, emptyMap())

        assertEquals(emptyMap<String, Any?>(), out)
    }
}