package org.drivine.manager

import org.drivine.model.FragmentModel
import org.drivine.query.quotedIdentifier

/**
 * A stored node named by what identifies it, without loading it: the [fragmentClass] whose labels
 * and `@NodeId` property it is matched by, its [id], and any further [labels] it must also carry.
 *
 * It names a node that already exists. An operation given a `NodeRef` matches the node and never
 * creates it, so a reference to a node that is absent — or that lacks one of [labels] — matches
 * nothing.
 *
 * ```kotlin
 * nodeRef<PersonNode>("p1")                 // (:Person {id: "p1"})
 * nodeRef<EntityNode>("e1", "Musician")     // (:Entity:Musician {id: "e1"})
 * ```
 */
data class NodeRef(
    val fragmentClass: Class<*>,
    val id: Any,
    val labels: Set<String> = emptySet(),
) {
    /** `(alias:Label:Extra {idProperty: $param})` — the pattern that matches this node, bound to [param]. */
    internal fun pattern(alias: String, param: String): String {
        val model = FragmentModel.from(fragmentClass)
        val idProperty = requireNotNull(model.nodeIdProperty) {
            "${fragmentClass.simpleName} has no @NodeId field, so a node of it cannot be referred to by id."
        }
        val all = (model.labels + labels).distinct().joinToString("") { ":${quotedIdentifier(it)}" }
        return "($alias$all {${quotedIdentifier(idProperty)}: \$$param})"
    }
}

/** A [NodeRef] to the [T] with [id] that also carries each of [labels]. */
inline fun <reified T : Any> nodeRef(id: Any, vararg labels: String): NodeRef = NodeRef(T::class.java, id, labels.toSet())

/** Whether [EdgeOperations.relate] makes a new relationship every time, or one at most. */
enum class RelateMode {
    /** Make a relationship, whether or not one of this type already joins the two nodes. */
    CREATE,

    /** Make the relationship only if none of this type joins the two nodes in this direction. */
    MERGE,
}
