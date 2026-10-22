package org.drivine.schema

/**
 * Outcome of an idempotent `ensure` operation on an index or constraint.
 *
 * `ensure` is safe to call on every startup:
 * - if the item doesn't exist it is created → [Created]; so it is when it exists but lacks part of
 *   what the engine makes for it, and that part is made (see [SchemaGrammar.isIncomplete])
 * - if a matching item exists nothing changes → [AlreadyMatching]
 * - if an item exists with a different shape nothing changes → [Drift]; the caller decides
 *   whether to call `recreate`
 * - constraint creation can fail because existing data violates it → [Violation]
 * - or because a plain index is in the way of it → [BlockedByIndex]
 */
sealed interface EnsureResult {

    /**
     * No prior item existed and one was created matching the spec, or one existed incomplete and
     * what it lacked was made (see [SchemaGrammar.isIncomplete]).
     */
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
     *   Empty only when a sample size of 0 was asked for: when the data cannot be read, the
     *   refusal is thrown rather than reported as a violation with no sample.
     */
    data class Violation(
        val requested: ConstraintSpec,
        val conflictingSample: List<Map<String, Any?>> = emptyList(),
    ) : EnsureResult

    /**
     * Constraint-only outcome: a plain range index over the constraint's label and properties is in
     * the way of it, on an engine whose constraint brings its own index (Neo4j). Nothing was changed.
     * Drop [index], or ensure with `replaceBlockingIndex` (`drivine.schema.replace-blocking-index`),
     * which drops it and makes the constraint, whose own index then serves the same lookups.
     *
     * @param requested the constraint that could not be created
     * @param index the index in the way of it, named
     */
    data class BlockedByIndex(
        val requested: ConstraintSpec,
        val index: SchemaItemInfo,
    ) : EnsureResult
}
