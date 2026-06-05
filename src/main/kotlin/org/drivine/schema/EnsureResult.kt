package org.drivine.schema

/**
 * Outcome of an idempotent `ensure` operation on an index or constraint.
 *
 * `ensure` is safe to call on every startup:
 * - if the item doesn't exist it is created → [Created]
 * - if a matching item exists nothing changes → [AlreadyMatching]
 * - if an item exists with a different shape nothing changes → [Drift]; the caller decides
 *   whether to call `recreate`
 * - constraint creation can fail because existing data violates it → [Violation]
 */
sealed interface EnsureResult {

    /** No prior item existed; one was created matching the spec. */
    data class Created(val info: SchemaItemInfo) : EnsureResult

    /** A matching item already existed; nothing was changed. */
    data class AlreadyMatching(val info: SchemaItemInfo) : EnsureResult

    /**
     * An item exists on the same label/properties but with a different shape
     * (e.g. different vector dimensions). Nothing was changed — the caller must explicitly
     * recreate (destructive) if the new shape is wanted.
     */
    data class Drift(val existing: SchemaItemInfo, val requested: SchemaItemSpec) : EnsureResult

    /**
     * Result of an explicit, destructive `recreate`: the previous item (if any) was dropped and
     * a new one created from the spec.
     */
    data class Recreated(val previous: SchemaItemInfo?, val current: SchemaItemInfo) : EnsureResult

    /**
     * Constraint-only outcome: creation failed because existing data violates the constraint.
     *
     * @param requested the constraint that could not be created
     * @param conflictingSample a bounded sample of conflicting property combinations
     *   (each entry holds the duplicated property values and a count), for debugging.
     *   May be empty if sampling failed or was disabled.
     */
    data class Violation(
        val requested: ConstraintSpec,
        val conflictingSample: List<Map<String, Any?>> = emptyList(),
    ) : EnsureResult
}