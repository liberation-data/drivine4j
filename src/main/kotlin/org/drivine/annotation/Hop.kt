package org.drivine.annotation

/**
 * One segment of a [GraphPath] — a single relationship hop to the next node in the chain.
 *
 * @param type the Neo4j relationship type for this hop (e.g. `"ACTED_IN"`)
 * @param direction traversal direction for this hop
 * @param label optional label constraining the node this hop reaches; empty means unconstrained.
 *   On the final hop the target's labels come from the mapped field's element type, so a label here
 *   is redundant; on intermediate hops it constrains (and documents) the node being skipped.
 */
@Retention(AnnotationRetention.RUNTIME)
annotation class Hop(
    val type: String,
    val direction: Direction = Direction.OUTGOING,
    val label: String = "",
)