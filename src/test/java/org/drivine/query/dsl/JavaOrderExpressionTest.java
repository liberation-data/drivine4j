package org.drivine.query.dsl;

import static org.drivine.query.dsl.OrderExpressions.above;
import static org.drivine.query.dsl.OrderExpressions.atMost;
import static org.drivine.query.dsl.OrderExpressions.caseOf;
import static org.drivine.query.dsl.OrderExpressions.coalesce;
import static org.drivine.query.dsl.OrderExpressions.div;
import static org.drivine.query.dsl.OrderExpressions.exp;
import static org.drivine.query.dsl.OrderExpressions.floor;
import static org.drivine.query.dsl.OrderExpressions.minus;
import static org.drivine.query.dsl.OrderExpressions.times;
import static org.drivine.query.dsl.OrderExpressions.unaryMinus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.drivine.manager.GraphObjectManager;
import org.junit.jupiter.api.Test;
import sample.decay.BeliefQueryDsl;

/** An order by an expression, built from Java with the static functions of {@code OrderExpressions}. */
class JavaOrderExpressionTest {

    private final Instant asOf = Instant.parse("2026-01-10T00:00:00Z");
    private final BeliefQueryDsl q = BeliefQueryDsl.Companion.getINSTANCE();

    private OrderExpression score() {
        return caseOf(
                new CaseBranch[] {above(q.getValidFrom(), asOf).or(atMost(q.getValidTo(), asOf)).then(0.0)},
                times(
                        times(q.getConfidence(), coalesce(q.getBoost(), 1.0)),
                        exp(times(unaryMinus(q.getDecay()), floor(div(minus(asOf.getEpochSecond(), q.getLastReadSeconds()), 86400))))));
    }

    @Test
    void javaWritesTheExpressionKotlinDoes() {
        OrderClauseResult result = CypherGenerator.INSTANCE.processOrders(
                List.of(score().descending(), q.getId().ascending()), Set.of());

        assertEquals(
                """
                CASE WHEN (n.validFrom > $_order_0 OR n.validTo <= $_order_1) THEN $_order_2 \
                ELSE ((n.confidence * coalesce(n.boost, $_order_3)) * \
                exp(((-n.decay) * floor((toFloat(($_order_4 - n.lastReadSeconds)) / $_order_5))))) END DESC, n.id ASC""",
                result.getOrderByClause());
        assertEquals(asOf, result.getBindings().get("_order_0"));
        assertEquals(86400, result.getBindings().get("_order_5"));
    }

    @Test
    void anExpressionOrdersFromTheJavaFluentApi() {
        JavaQueryBuilder<Object, BeliefQueryDsl> builder =
                new JavaQueryBuilder<>(Object.class, q, mock(GraphObjectManager.class));

        JavaQueryBuilder<Object, BeliefQueryDsl> result = builder
                .orderBy(dsl -> score().descending().nullsLast())
                .orderBy(dsl -> dsl.getId().ascending())
                .limit(3);

        assertSame(builder, result);
        assertEquals(new ExpressionOrder(score(), OrderDirection.DESC, NullPlacement.LAST), score().descending().nullsLast());
    }
}
