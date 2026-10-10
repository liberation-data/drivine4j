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

    /**
     * The node is deleted when nothing else refers to it the way the field did: for an outgoing field,
     * when no relationship of any type points at it; for an incoming field, when it points at nothing;
     * for an undirected field, when it has no relationship at all. Its other relationships go with it.
     * The root of the save is never deleted, though a relationship from it to itself is removed.
     */
    DELETE_UNREFERENCED,
}

/**
 * The named relationship fields hold the whole list: a relationship the field would have loaded,
 * and that the object does not hold, is removed. Every other field is add-only.
 *
 * A field removes only what it loads: relationships of its type and direction, to nodes with its
 * target's labels. A read-only field cannot be replaced, and every `@GraphPath` field is one. A
 * list that is null is refused; an empty list removes every relationship of the field. The fields
 * are those of the view that is saved: the lists of a view nested in it only add.
 *
 * The removals are part of the save's one statement. On a root that carries a stamp, the save is
 * refused if any relationship of the root was added or removed since the object was loaded, from
 * either end: a replace never removes a relationship it did not load.
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
         * Every relationship field of the view, read-only fields aside. Refused for an object that
         * carries no stamp, because its lists did not come from the store, and so for a view whose
         * root declares no `@NodeStamp` field. Refused too when one of the lists is null.
         */
        @JvmStatic
        @JvmOverloads
        fun all(removedTargets: RemovedTargets = RemovedTargets.KEEP): Replace =
            Replace(emptySet(), true, removedTargets)
    }
}
