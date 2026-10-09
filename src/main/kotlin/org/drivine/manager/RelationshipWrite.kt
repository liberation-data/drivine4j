package org.drivine.manager

import kotlin.reflect.KProperty1

/** What a [StatelessGraphObjectManager] save does with the relationships of a view. */
sealed interface RelationshipWrite

/** Adds the relationships the object holds and removes none. */
object Add : RelationshipWrite

/** What happens to a node whose relationship a [Replace] removed. */
enum class RemovedTargets {
    /** The node is kept. */
    KEEP,

    /** The node is deleted when no relationship of any type points at it. */
    DELETE_UNREFERENCED,
}

/**
 * The named relationship fields hold the whole list: a relationship the field would have loaded,
 * and that the object does not hold, is removed. Every other field is add-only.
 *
 * A field removes only what it loads: relationships of its type and direction, to nodes with its
 * target's labels. A `@ReadOnly` field cannot be replaced, and every `@GraphPath` field is one.
 */
class Replace private constructor(
    val fields: Set<String>,
    val everyField: Boolean,
    val removedTargets: RemovedTargets,
) : RelationshipWrite {

    constructor(vararg fields: KProperty1<*, *>, removedTargets: RemovedTargets = RemovedTargets.KEEP) :
        this(fields.map { it.name }.toSet(), false, removedTargets)

    companion object {
        /** The fields named as strings, for Java. */
        @JvmStatic
        @JvmOverloads
        fun of(fields: Set<String>, removedTargets: RemovedTargets = RemovedTargets.KEEP): Replace =
            Replace(fields, false, removedTargets)

        /**
         * Every relationship field of the view, `@ReadOnly` fields aside. Refused for an object
         * that carries no stamp, because its lists did not come from the store.
         */
        @JvmStatic
        @JvmOverloads
        fun all(removedTargets: RemovedTargets = RemovedTargets.KEEP): Replace =
            Replace(emptySet(), true, removedTargets)
    }
}
