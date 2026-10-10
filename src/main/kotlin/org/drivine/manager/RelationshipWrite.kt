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
 * target's labels. A read-only field cannot be replaced, and every `@GraphPath` field, and every list
 * of fragments read over several hops, is one. A list that is null is refused; an empty list removes
 * every relationship of the field. A field that holds one node and is null removes the relationship:
 * for one node, null is how "none" is said. The fields are those of the view that is saved: the lists
 * of a view nested in it only add.
 *
 * The removals are part of the save's one statement. On a root that carries a stamp, the save is
 * refused if any relationship of the root was added or removed, or had its properties changed, since
 * the object was loaded, from either end: a replace of an object that carries a stamp never removes a relationship it did not
 * load. An object whose stamp is null, or whose root declares none, is not checked. An object that
 * was saved over a node it had not loaded carries a stamp with no relationship token, and a replace
 * of it is refused: load it first.
 *
 * A relationship that another field of the view holds, of the same type and direction to a node both
 * fields read, is that field's to keep and is not removed.
 *
 * With [RemovedTargets.DELETE_UNREFERENCED], a node the object still holds in another of its fields,
 * or in a view nested in one, is never deleted.
 */
class Replace private constructor(
    /** The names of the relationship fields to replace; empty when [everyField] is set. */
    val fields: Set<String>,
    /** Whether every relationship field of the view is replaced, as [all] asks. */
    val everyField: Boolean,
    /** What becomes of a node whose relationship the replace removed. */
    val removedTargets: RemovedTargets,
) : RelationshipWrite {

    init {
        require(everyField || fields.isNotEmpty()) {
            "Replace names no field, so it would replace nothing. Name the fields, as Replace(View::field), or use Replace.all()."
        }
    }

    /**
     * Replaces the named relationship [fields] of the view that is saved. Only a property's name is
     * used: name fields of that view.
     *
     * @param removedTargets what becomes of a node whose relationship is removed; kept by default
     */
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
         * root declares no `@NodeStamp` field. Refused too when one of the lists is null. A field that
         * holds one node and is null has its relationship removed.
         */
        @JvmStatic
        @JvmOverloads
        fun all(removedTargets: RemovedTargets = RemovedTargets.KEEP): Replace =
            Replace(emptySet(), true, removedTargets)
    }
}
