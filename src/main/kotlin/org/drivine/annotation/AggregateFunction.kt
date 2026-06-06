package org.drivine.annotation

/**
 * Aggregate function for a per-root derived field on a `@GraphView`.
 *
 * `COUNT` needs no property; `SUM`/`AVG`/`MIN`/`MAX` aggregate a numeric property of the related
 * nodes.
 */
enum class AggregateFunction {
    COUNT,
    SUM,
    AVG,
    MIN,
    MAX,
}