package org.drivine.annotation

/**
 * One segment of a [GraphPath] — a single relationship hop to the next node in the chain.
 *
 * @param type the Neo4j relationship type for this hop (e.g. `"ACTED_IN"`)
 * @param direction traversal direction for this hop
 * @param label optional label constraining the node this hop reaches; empty means unconstrained.
 *   On the final hop the target's labels come from the mapped field's element type, so a label here
 *   is redundant; on intermediate hops it constrains (and documents) the node being skipped.
 * @param orderBy optional property of the node this hop reaches, to read the path in its order:
 *   the targets of a list come in the order of the first node of this hop that leads to each, and a
 *   single target is the one the first such node leads to. Empty means no order. One hop of a path
 *   may name one, and a path that is a group key may name none. A node of this hop that has no
 *   value comes last whichever way the path is read. The property should hold values of one type
 *   on every node of the hop: the engines do not order a text and a number alike.
 * @param ascending whether [orderBy] reads lowest first (the default) or highest first
 */
@Retention(AnnotationRetention.RUNTIME)
annotation class Hop(
    val type: String,
    val direction: Direction = Direction.OUTGOING,
    val label: String = "",
    val orderBy: String = "",
    val ascending: Boolean = true,
)