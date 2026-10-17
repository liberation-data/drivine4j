@file:JvmName("TemporalOrderExpressions")

package org.drivine.query.dsl.temporal

import org.drivine.query.dsl.OrderExpression
import org.drivine.query.dsl.PropertyReference
import java.time.temporal.Temporal

// The order functions of the temporal capability (see TemporalOrder), which not every backend can
// write. Java calls them as static methods of TemporalOrderExpressions.

/**
 * The moment the property [moment] holds, as seconds since the epoch, with the fraction of a second
 * where the engine keeps one. The property holds an instant: an `Instant`, `OffsetDateTime` or
 * `ZonedDateTime`, not a local date or time, which names no instant. A backend that holds a moment in
 * a form it cannot read as a number refuses it, as [TemporalOrder] lists.
 */
@TemporalOrder
fun epochSeconds(moment: PropertyReference<out Temporal>): OrderExpression =
    OrderExpression.EpochSeconds(OrderExpression.Property(moment.alias, moment.propertyName))
