package org.drivine.schema

/**
 * A declarative description of a constraint.
 *
 * Kept separate from [IndexSpec] because DDL, lifecycle (constraints can fail on existing data),
 * and consumer mental model (invariant vs. lookup accelerator) all differ.
 *
 * @see UniquenessConstraintSpec
 */
sealed interface ConstraintSpec : SchemaItemSpec {

    /**
     * The property a node is identified by, the `@NodeId` of the fragment the spec was read from;
     * null for a spec declared by hand. Not part of what is made: a violation's sample names each
     * conflicting node by it, beside the engine's own id, and two declarations that differ only in
     * it are one.
     */
    val nodeIdProperty: String?
}