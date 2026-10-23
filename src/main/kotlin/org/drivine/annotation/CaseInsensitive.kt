package org.drivine.annotation

/**
 * Marks a `String` field of a [NodeFragment] that is looked up without regard to case: by
 * `eqIgnoreCase` or `containsIgnoreCase`, or under a case-insensitive unique constraint.
 *
 * Every save of the node also writes a lowercased copy of the property beside it, under
 * `__drivine.lower.<property>`, the property the field is stored under. The database lowercases it,
 * as it lowercases what the copy is compared with, so every library of the Drivine family stores and
 * matches the same thing. A load does not read the copy, and a flat [PropertyBag] never holds it.
 *
 * A [RangeIndex], [Unique] or [TextIndex] with `ignoreCase = true` on the field is made over the
 * copy (see [org.drivine.schema.RangeIndexSpec.ignoreCase]), and ensuring it fills the copy in on
 * the nodes saved before there was one. Once that has been done, the query DSL compares
 * `eqIgnoreCase` and `containsIgnoreCase` on the field with the copy, which the index serves;
 * until then it lowercases the property itself, as it does for any other field.
 *
 * ```kotlin
 * @NodeFragment(labels = ["Account"])
 * data class Account(
 *     @NodeId val id: String,
 *     @CaseInsensitive @Unique(ignoreCase = true) val email: String,
 * )
 * ```
 *
 * A write that does not go through Drivine, Cypher or SQL of your own, does not keep the copy in step:
 * set it with [org.drivine.model.LowerCopies.setClause], or ensure the index again with `recreate`.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class CaseInsensitive
