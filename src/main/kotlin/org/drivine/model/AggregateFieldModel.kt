package org.drivine.model

import org.drivine.annotation.AggregateFunction
import org.drivine.annotation.Direction

/**
 * A per-root aggregate (`@Count` / `@Aggregate`) field on a `@GraphView` — a scalar summary over
 * the root's relationships, projected in the load query without materializing the collection.
 *
 * @param fieldName the view property name (also the projected alias)
 * @param function the aggregate function
 * @param type the single-hop relationship type to aggregate over
 * @param direction traversal direction
 * @param property the related node's numeric property to aggregate; null for COUNT
 */
data class AggregateFieldModel(
    val fieldName: String,
    val function: AggregateFunction,
    val type: String,
    val direction: Direction,
    val property: String? = null,
)