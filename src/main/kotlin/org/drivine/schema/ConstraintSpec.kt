package org.drivine.schema

/**
 * A declarative description of a constraint.
 *
 * Kept separate from [IndexSpec] because DDL, lifecycle (constraints can fail on existing data),
 * and consumer mental model (invariant vs. lookup accelerator) all differ.
 *
 * @see UniquenessConstraintSpec
 */
sealed interface ConstraintSpec : SchemaItemSpec