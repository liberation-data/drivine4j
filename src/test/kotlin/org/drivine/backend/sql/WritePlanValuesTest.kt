package org.drivine.backend.sql

import org.drivine.DrivineException
import org.drivine.annotation.Direction
import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleWriteBlocks
import org.drivine.backend.postgres.PostgresGraph
import org.drivine.backend.postgres.PostgresWriteBlocks
import org.drivine.backend.sqlite.SqliteGraph
import org.drivine.backend.sqlite.SqliteWriteBlocks
import org.drivine.connection.CypherCompile
import org.drivine.query.JdbcParameterCoercer
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * A plan's values are bound as each store's layout holds them, the shared module's `StoreValues.text`, which a compiled
 * `CREATE` binds its parameters by too (liberation-data/drivine4j#830): a save and a `CREATE` store a value alike, and
 * refuse alike what the store holds as no number.
 */
class WritePlanValuesTest {

    private val postgres = PostgresWriteBlocks(PostgresGraph("g").writes)
    private val oracle = OracleWriteBlocks(OracleGraph("g"))
    private val sqlite = SqliteWriteBlocks(SqliteGraph("g"))

    /** The plan text of one node step that sets [set], as [blocks]' store binds it. */
    private fun planText(blocks: WriteBlocks, set: Map<String, Any?>): String {
        val plan = WritePlan(blocks)
        plan.node(plan.shape(listOf("P"), "id"), "id", "p1", "p1", listOf("P"), NodeWrites(set = set))
        return plan.bindings()[WritePlan.PLAN] as String
    }

    @Test
    fun `the plan is bound as text, which the parameter coercer leaves as it is`() {
        val text = planText(postgres, mapOf("d" to 1.5))
        assertSame(text, JdbcParameterCoercer.coerce(mapOf("p" to text))["p"])
    }

    @Test
    fun `every float is extended JSON's double on Oracle, NaN and the infinities too, beside a date`() {
        val text = planText(oracle, mapOf("d" to 2.0, "n" to Double.NaN, "i" to Double.POSITIVE_INFINITY, "m" to Double.NEGATIVE_INFINITY, "l" to listOf(1.5, 2.0), "x" to mapOf("x" to 2.0), "k" to 2L, "day" to LocalDate.of(2024, 1, 1)))
        assertContains(text, "\"d\":{\"\$numberDouble\":\"2.0\"}")
        assertContains(text, "\"n\":{\"\$numberDouble\":\"NaN\"}")
        assertContains(text, "\"i\":{\"\$numberDouble\":\"Infinity\"}")
        assertContains(text, "\"m\":{\"\$numberDouble\":\"-Infinity\"}")
        assertContains(text, "\"l\":[{\"\$numberDouble\":\"1.5\"},{\"\$numberDouble\":\"2.0\"}]")
        assertContains(text, "\"x\":{\"x\":{\"\$numberDouble\":\"2.0\"}}")
        assertContains(text, "\"k\":2,")
        assertContains(text, "\"day\":{\"\$t\":\"date\",\"v\":\"2024-01-01\"")
    }

    @Test
    fun `a whole float keeps its fraction on Postgres, and an infinity is 9e999 on SQLite`() {
        assertContains(planText(postgres, mapOf("d" to 1e16, "e" to 2.0)), "\"set\":{\"d\":10000000000000000.0,\"e\":2.0}")
        assertContains(planText(sqlite, mapOf("i" to Double.POSITIVE_INFINITY, "m" to Double.NEGATIVE_INFINITY)), "\"set\":{\"i\":9e999,\"m\":-9e999}")
    }

    @Test
    fun `a float the store holds as no number is refused with the message a CREATE given it is refused with`() {
        for ((blocks, store, values) in listOf(
            Triple(postgres, "postgres", listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)),
            Triple(sqlite, "sqlite", listOf(Double.NaN)),
        )) {
            for (value in values) {
                val save = assertFailsWith<DrivineException> { planText(blocks, mapOf("d" to value, "day" to LocalDate.of(2024, 1, 1))) }
                val create = assertFailsWith<DrivineException> {
                    val parameters = mapOf("props" to mapOf("d" to value))
                    if (store == "postgres") CypherCompile.postgres("g", "CREATE (n:P) SET n += \$props", parameters)
                    else CypherCompile.sqlite("g", "CREATE (n:P) SET n += \$props", parameters)
                }
                assertEquals(create.message, save.message, "$store, $value")
            }
        }
    }

    @Test
    fun `bytes are the list of their signed values on every store`() {
        for (blocks in listOf(postgres, oracle, sqlite)) {
            assertContains(planText(blocks, mapOf("b" to byteArrayOf(1, 2, -1))), "\"b\":[1,2,-1]", message = "${blocks.store}")
        }
    }

    @Test
    fun `a node step, a patch and a link name what they remove as the store holds it, a dollar-named property with one more dollar (#892)`() {
        for (blocks in listOf(postgres, oracle, sqlite)) {
            val plan = WritePlan(blocks)
            val shape = plan.shape(listOf("P"), "id")
            val a = plan.node(shape, "id", "p1", "p1", listOf("P"), NodeWrites(set = mapOf("\$keep" to 1L), unset = listOf("\$score", "plain")))
            val b = plan.node(shape, "id", "p2", "p2", listOf("P"), NodeWrites())
            plan.patch(shape, "p3", "p3", "0", mapOf("\$keep" to 2L), listOf("\$score"))
            plan.link(a, b, "R", Direction.OUTGOING, mapOf("\$since" to 1L), clear = listOf("\$until", "plain"))
            val text = plan.bindings()[WritePlan.PLAN] as String
            assertContains(text, "\"unset\":[\"\$\$score\",\"plain\"]", message = "${blocks.store}: the node step")
            assertContains(text, "\"unset\":[\"\$\$score\"]", message = "${blocks.store}: the patch")
            assertContains(text, "\"clear\":[\"\$\$until\",\"plain\"]", message = "${blocks.store}: the link")
            assertContains(text, "\"\$\$keep\"", message = "${blocks.store}: as the keys it sets are")
        }
    }

    @Test
    fun `a user's map with the key numberDouble is held with one more dollar, so no store reads it as a float`() {
        for (blocks in listOf(postgres, oracle, sqlite)) {
            assertContains(planText(blocks, mapOf("m" to mapOf("\$numberDouble" to "NaN"))), "\"m\":{\"\$\$numberDouble\":\"NaN\"}", message = "${blocks.store}")
        }
    }
}
