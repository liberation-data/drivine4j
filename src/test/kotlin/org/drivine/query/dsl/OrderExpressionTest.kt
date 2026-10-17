package org.drivine.query.dsl

import org.drivine.backend.CypherBackend
import org.drivine.backend.oracle.OracleBackend
import org.drivine.connection.DatabaseType
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.query.grammar.CypherDialect
import org.drivine.test.RecordingStore
import org.junit.jupiter.api.Test
import sample.decay.Belief
import sample.decay.BeliefQueryDsl
import sample.decay.BeliefView
import sample.decay.BeliefViewQueryDsl
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An order by an expression over the root's properties: what each backend writes for it, what it
 * binds, and what it refuses before anything runs.
 */
class OrderExpressionTest {

    private val asOf: Instant = Instant.parse("2026-01-10T00:00:00Z")
    private val asOfSeconds: Long = asOf.epochSecond

    /** Effective confidence: decayed by whole days since last read, and zero outside the window. */
    private fun BeliefQueryDsl.score(): OrderExpression = case(
        (validFrom above asOf) or (validTo atMost asOf) then 0.0,
        otherwise = confidence * coalesce(boost, 1.0) * exp(-decay * floor((asOfSeconds - lastReadSeconds) / 86400)),
    )

    private fun cypher(dialect: CypherDialect = CypherDialect.NEO4J_5) =
        CypherBackend(RecordingStore(DatabaseType.NEO4J), dialect.grammar(), Neo4jObjectMapper.instance)

    private fun oracle() = OracleBackend(RecordingStore(DatabaseType.ORACLE), Neo4jObjectMapper.instance, catalogReads = Runnable::run)

    private fun beliefs(build: GraphQuerySpec<BeliefQueryDsl>.() -> Unit) = GraphQuerySpec(BeliefQueryDsl.INSTANCE).apply(build)

    private fun views(build: GraphQuerySpec<BeliefViewQueryDsl>.() -> Unit) = GraphQuerySpec(BeliefViewQueryDsl.INSTANCE).apply(build)

    // ----- Cypher -----

    @Test
    fun `an expression is written into the ORDER BY with every value bound, and the next term breaks its ties`() {
        val statement = cypher().loadAll(Belief::class.java, beliefs { orderBy { query.score().desc(); query.id.asc() } }).statement

        assertContains(
            statement.statement,
            """
            ORDER BY CASE WHEN (n.validFrom > ${'$'}_order_0 OR n.validTo <= ${'$'}_order_1) THEN ${'$'}_order_2
            ELSE ((n.confidence * coalesce(n.boost, ${'$'}_order_3)) *
            exp(((-n.decay) * floor((toFloat((${'$'}_order_4 - n.lastReadSeconds)) / ${'$'}_order_5))))) END DESC, n.id ASC
            """.trimIndent().replace("\n", " "),
        )
        assertEquals(
            mapOf("_order_0" to asOf, "_order_1" to asOf, "_order_2" to 0.0, "_order_3" to 1.0, "_order_4" to asOfSeconds, "_order_5" to 86400),
            statement.bindings.filterKeys { it.startsWith("_order_") },
        )
        assertFalse("86400" in statement.statement, "a value is bound, never written into the statement")
    }

    @Test
    fun `an expression orders with its limit, and binds beside the where clause`() {
        val statement = cypher().loadAll(
            Belief::class.java,
            beliefs {
                where { query.confidence gt 0.1 }
                orderBy { query.score().desc() }
                limit(3)
            },
        ).statement

        assertTrue(statement.statement.trimEnd().endsWith("END DESC\nLIMIT \$_limit"), statement.statement)
        assertEquals(0.1, statement.bindings["param_n_confidence_0"])
        assertEquals(3, statement.bindings["_limit"])
        assertEquals(asOf, statement.bindings["_order_0"])
    }

    @Test
    fun `on a view the expression reads the root by the keys of its projection`() {
        val statement = cypher().loadAll(
            BeliefView::class.java,
            views { orderBy { (query.belief.confidence * exp(query.belief.decay)).asc() } },
        ).statement

        assertContains(statement.statement, "ORDER BY (belief.confidence * exp(belief.decay)) ASC")
    }

    @Test
    fun `an expression places its nulls by the test for null, as a property order does`() {
        val statement = cypher().loadAll(Belief::class.java, beliefs { orderBy { coalesce(query.boost, query.decay).desc().nullsLast() } }).statement

        assertContains(statement.statement, "ORDER BY coalesce(n.boost, n.decay) IS NULL, coalesce(n.boost, n.decay) DESC")
    }

    @Test
    fun `each condition of a case is written as Cypher, and the conditions combine in parentheses of their own`() {
        val statement = cypher().loadAll(
            Belief::class.java,
            beliefs {
                orderBy {
                    case(
                        ((query.confidence below 0.5) and !(query.decay atLeast query.confidence)) then 1,
                        ((query.id equalTo "a") or (query.id notEqualTo "b")) then 2,
                        query.boost.hasNoValue() then query.decay,
                        otherwise = -1,
                    ).desc()
                    ((query.validTo.hasValue() then 0).otherwise(query.confidence)).asc().nullsFirst()
                }
            },
        ).statement

        assertContains(
            statement.statement,
            """
            ORDER BY CASE WHEN (n.confidence < ${'$'}_order_0 AND NOT (n.decay >= n.confidence)) THEN ${'$'}_order_1
            WHEN (n.id = ${'$'}_order_2 OR n.id <> ${'$'}_order_3) THEN ${'$'}_order_4
            WHEN n.boost IS NULL THEN n.decay ELSE ${'$'}_order_5 END DESC,
            CASE WHEN n.validTo IS NOT NULL THEN ${'$'}_order_6 ELSE n.confidence END IS NOT NULL,
            CASE WHEN n.validTo IS NOT NULL THEN ${'$'}_order_6 ELSE n.confidence END ASC
            """.trimIndent().replace("\n", " "),
        )
        assertEquals(listOf(0.5, 1, "a", "b", 2, -1, 0), (0..6).map { statement.bindings["_order_$it"] })
    }

    @Test
    fun `a coalesce of one operand, a case of no branch, and a property bound as a value are refused as they are built`() {
        assertFailsWith<IllegalArgumentException> { coalesce(BeliefQueryDsl.INSTANCE.boost) }
        assertFailsWith<IllegalArgumentException> { case(otherwise = 0) }
        assertFailsWith<IllegalArgumentException> { param(BeliefQueryDsl.INSTANCE.boost) }
    }

    @Test
    fun `a moment is read as seconds since the epoch where the engine can, and refused before anything runs where it cannot`() {
        fun ordered(dialect: CypherDialect) =
            cypher(dialect).loadAll(Belief::class.java, beliefs { orderBy { epochSeconds(query.lastRead).desc() } }).statement.statement

        assertContains(ordered(CypherDialect.NEO4J_5), "ORDER BY (n.lastRead.epochMillis / 1000.0) DESC")
        assertContains(ordered(CypherDialect.NEO4J_4), "ORDER BY (n.lastRead.epochMillis / 1000.0) DESC")
        assertContains(ordered(CypherDialect.MEMGRAPH), "ORDER BY (timestamp(n.lastRead) / 1000000.0) DESC")

        val refused = assertFailsWith<UnsupportedOrderFunctionException> { ordered(CypherDialect.FALKORDB) }
        assertEquals("epochSeconds", refused.function)
        assertContains(refused.message.orEmpty(), "FalkorDB")
        // A grammar that does not say how to read a moment refuses it.
        assertFailsWith<UnsupportedOrderFunctionException> { ordered(CypherDialect.NEPTUNE) }
    }

    @Test
    fun `a moment read as seconds is one operand, so it divides as a whole`() {
        val statement = cypher().loadAll(Belief::class.java, beliefs { orderBy { (query.decay / epochSeconds(query.lastRead)).desc() } }).statement

        assertContains(statement.statement, "ORDER BY (toFloat(n.decay) / (n.lastRead.epochMillis / 1000.0)) DESC")
    }

    @Test
    fun `a bound moment is read as seconds on the caller's side, so every engine can take it`() {
        val statement = cypher(CypherDialect.FALKORDB).loadAll(
            Belief::class.java,
            beliefs { orderBy { (epochSeconds(asOf.plusMillis(1500)) - query.lastReadSeconds).desc() } },
        ).statement

        assertContains(statement.statement, "ORDER BY (\$_order_0 - n.lastReadSeconds) DESC")
        assertEquals(asOfSeconds + 1.5, statement.bindings["_order_0"])
    }

    @Test
    fun `an expression that reads a related node, or one under a seek, is refused`() {
        assertFailsWith<IllegalArgumentException> {
            cypher().loadAll(BeliefView::class.java, views { orderBy { exp(query.sources.weight).desc() } })
        }
        val seeking = assertFailsWith<IllegalArgumentException> {
            cypher().loadAll(
                Belief::class.java,
                beliefs {
                    orderBy { query.score().desc(); query.id.asc() }
                    seek { query.id after "b" }
                },
            )
        }
        assertContains(seeking.message.orEmpty(), "seek")
    }

    // ----- Oracle -----

    @Test
    fun `on Oracle the expression is SQL over the properties read as their fields declare, with every value bound`() {
        val statement = oracle().loadAll(Belief::class.java, beliefs { orderBy { query.score().desc(); query.id.asc() }; limit(3) }).statement
        val order = statement.statement.substringAfterLast("ORDER BY ")

        assertTrue(order.startsWith("CASE WHEN (JSON_VALUE(n_.props, '\$.validFrom' RETURNING TIMESTAMP WITH TIME ZONE) > \$_p"), order)
        assertContains(order, "COALESCE(JSON_VALUE(n_.props, '\$.boost' RETURNING NUMBER), \$_p")
        assertContains(order, "EXP(((-JSON_VALUE(n_.props, '\$.decay' RETURNING NUMBER)) * FLOOR(((\$_p")
        assertContains(order, "JSON_VALUE(n_.props, '\$.lastReadSeconds' RETURNING NUMBER)) / \$_p")
        assertContains(order, "END DESC, JSON_VALUE(n_.props, '\$.id') ASC")
        assertFalse("86400" in statement.statement)
        assertTrue(86400 in statement.bindings.values)
        assertTrue(asOf in statement.bindings.values)
    }

    @Test
    fun `on Oracle the expression's values are bound beside the where clause's and the page's, each under a name of its own`() {
        val statement = oracle().loadAll(
            Belief::class.java,
            beliefs {
                where { query.confidence gt 0.1 }
                orderBy { query.score().desc() }
                skip(2)
                limit(3)
            },
        ).statement
        val written = Regex("""\$(_p\d+)""").findAll(statement.statement).map { it.groupValues[1] }.toSet()

        assertEquals(statement.bindings.keys, written, "every name written is bound, and every bound name is written")
        listOf(0.1, 2, 3, asOf, 0.0, 1.0, asOfSeconds, 86400).forEach { assertTrue(it in statement.bindings.values, "$it is bound") }
    }

    @Test
    fun `on Oracle a moment is read as seconds where a field declares it, and refused where none does`() {
        val order = oracle().loadAll(Belief::class.java, beliefs { orderBy { epochSeconds(query.lastRead).desc().nullsLast() } })
            .statement.statement.substringAfterLast("ORDER BY ")
        assertContains(order, "SYS_EXTRACT_UTC(JSON_VALUE(n_.props, '\$.lastRead' RETURNING TIMESTAMP WITH TIME ZONE))")
        assertTrue(order.trimEnd().endsWith("DESC NULLS LAST"), order)

        val undeclared = PropertyReference<Instant>("n", "seenAt")
        assertFailsWith<UnsupportedOrderFunctionException> {
            oracle().loadAll(Belief::class.java, beliefs { orderBy { epochSeconds(undeclared).desc() } })
        }
    }

    @Test
    fun `on Oracle a property no field declares is tested for a value as text, which counts as one`() {
        val note = PropertyReference<String>("n", "note")
        val order = oracle().loadAll(Belief::class.java, beliefs { orderBy { case(note.hasValue() then 1, otherwise = 0).desc() } })
            .statement.statement.substringAfterLast("ORDER BY ")

        assertContains(order, "CASE WHEN JSON_VALUE(n_.props, '\$.note') IS NOT NULL THEN")
    }

    @Test
    fun `on Oracle an expression that reads a related node is refused, a moment of one among them`() {
        val related = PropertyReference<Instant>("sources", "seenAt")
        assertFailsWith<IllegalArgumentException> {
            oracle().loadAll(Belief::class.java, beliefs { orderBy { exp(related).desc() } })
        }
        assertFailsWith<IllegalArgumentException> {
            oracle().loadAll(Belief::class.java, beliefs { orderBy { epochSeconds(related).desc() } })
        }
    }

    @Test
    fun `on Oracle an expression under a seek is refused`() {
        assertFailsWith<IllegalArgumentException> {
            oracle().loadAll(
                Belief::class.java,
                beliefs {
                    orderBy { query.score().desc() }
                    seek { query.id after "b" }
                },
            )
        }
    }
}
