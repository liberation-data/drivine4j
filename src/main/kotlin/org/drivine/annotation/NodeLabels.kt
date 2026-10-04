package org.drivine.annotation

/**
 * Maps a set-valued field on a [NodeFragment] to the node's **labels**, beyond the fixed ones the
 * fragment declares. On load the field is filled from the labels the node carries; on save its
 * contents are written as real labels, so a label held here is matched by `MATCH (n:Label)` like any
 * other.
 *
 * The element type decides which labels the field speaks for:
 *
 * ```kotlin
 * enum class Role { Admin, Reviewer, Author }
 *
 * @NodeFragment(labels = ["Person"])
 * data class PersonNode(
 *     @NodeId val id: String,
 *     @NodeLabels val roles: Set<Role>,      // a closed set: only these three labels are the field's
 * )
 *
 * @NodeFragment(labels = ["Entity"])
 * data class EntityNode(
 *     @NodeId val id: String,
 *     @NodeLabels val labels: Set<String>,   // an open set: any label
 * )
 * ```
 *
 * **Load.** An enum field reads the node's labels that are members of the enum and ignores the rest —
 * a label outside the enum is never an error, so a node written by a newer version of the enum still
 * loads. A `String` field reads every label the node carries, the fragment's own included.
 *
 * **Save** follows [org.drivine.manager.NullPolicy], as every other field does:
 *  - under `IGNORE` (the default, a merge-patch) the field's labels are added and none is removed;
 *  - under `CLEAR` (a full overwrite) the field is the set: a label that is the field's and is not in
 *    it is removed from the node.
 *
 * **Which labels are the field's.** For an enum, its members — nothing is stored to know it. For a
 * `String` field, the labels it has itself written, which it records on the node in a list property
 * `__drivine.labels.<fieldName>`: a save adds to that record, and a save under `CLEAR` removes what the record
 * holds and the field no longer does, then sets the record to the field. Because the record is on the
 * node, the result does not depend on which process loaded the object, or whether any did.
 *
 * A label that is not the field's — the fragment's own, another fragment's, anything written by
 * other code — is never removed. An enum must not name one of the fragment's own labels; that is
 * rejected when the model is built.
 *
 * A `String` field reads every label, so an object that is loaded and then saved under `CLEAR` takes
 * on all the labels it read: from then on they are the field's. In a `@GraphView`, a relationship
 * target's `String` field adds under both policies and removes under neither.
 *
 * A fragment may carry one `@NodeLabels` field.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class NodeLabels
