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
 * The object managers keep the copy in step: `save`, `saveAll`, `create`, `patch`, `patchAll` and the
 * save of a view. Cypher you send yourself does not, on any store: Drivine runs it as written, or
 * translates it to SQL, and adds nothing to it. Nor does SQL of your own. Such a write to the property
 * sets the copy too, in a `SET` of its own after the one that writes the property, or is followed by a
 * [org.drivine.schema.IndexManager.recreate] (or [org.drivine.schema.ConstraintManager.recreate]) of
 * the item that ignores case, which fills every copy of the label in again:
 *
 * ```kotlin
 * persistenceManager.execute(QuerySpecification.withStatement(
 *     "MATCH (a:Account {id: \$id}) SET a.email = \$email SET ${LowerCopies.setClause("a", "email")}"
 * ).bind(mapOf("id" to id, "email" to email)))
 * // or, after the writes:
 * persistenceManager.constraints.recreate(UniquenessConstraintSpec("Account", "email", ignoreCase = true))
 * ```
 *
 * [org.drivine.model.LowerCopies.setClause] gives ``a.`__drivine.lower.email` = toLower(a.email)``.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class CaseInsensitive
