package org.drivine.query.sort

import org.drivine.query.dsl.CollectionSortSpec

/**
 * Context for emitting a top-level (non-nested) collection sort.
 *
 * Top-level means the sort applies to a relationship directly off the root node —
 * e.g. `issue.assignedTo.name.asc()`, not `issue.raisedBy.worksFor.name.asc()`.
 */
data class TopLevelSortContext(
    val rootAlias: String,
    val direction: String,
    val targetAlias: String,
    val targetLabelString: String,
    val projection: String,
    val sort: CollectionSortSpec,
)

/**
 * Context for emitting a nested collection sort.
 *
 * Nested means the sort applies to a relationship that lives inside another relationship's
 * projection — e.g. `raisedBy.worksFor.name.asc()`.
 */
data class NestedSortContext(
    val listComprehension: String,
    val sort: CollectionSortSpec,
)

/**
 * Emission result for a top-level sort: a CALL prolog (if needed) and the expression
 * to substitute into the RETURN projection at this relationship's slot.
 */
data class TopLevelSortEmission(
    val prolog: String?,
    val projectionExpression: String,
)

/**
 * Emits Cypher for sorted collections.
 *
 * Implementations differ in structural shape — APOC wraps inline, CALL lifts above the RETURN.
 */
interface CollectionSortEmitter {
    /**
     * Emit a top-level sort. Returns an optional `CALL { }` prolog and the expression
     * to embed in the RETURN projection.
     */
    fun emitTopLevel(ctx: TopLevelSortContext): TopLevelSortEmission

    /**
     * Emit a sort on a relationship living inside another projection (nested).
     * May throw if the strategy cannot support nested sorts.
     */
    fun emitNested(ctx: NestedSortContext): String
}