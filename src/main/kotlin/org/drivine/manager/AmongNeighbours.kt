package org.drivine.manager

/**
 * Which of the other ids a [GraphObjectOperations.loadNearestAmong] keeps as an id's neighbours, before its `topK` are cut.
 */
enum class AmongNeighbours {
    /** Every other id of the set: a pair can come back twice, once under each of its ids. */
    ALL,

    /**
     * Only the ids after it, in the `@NodeId` type's own order (a text by its code points, a number by its value, a temporal
     * by its time, a `UUID` or an enum by its text): each pair comes back once, under the id that comes first. The direction is
     * kept before each id's `topK` are cut, so no pair is lost to the cut where an id's nearest all come before it.
     * With no `searchK`, every pair of the set is measured, exactly; given one, each id asks the vector index, and a pair is
     * found only where the index gives the later id among the earlier one's `searchK` nearest.
     */
    AFTER,
}

/** The shared module's form of these neighbours, which it plans the search by. */
internal val AmongNeighbours.module: org.drivine.cypher.api.AmongNeighbours
    get() = when (this) {
        AmongNeighbours.ALL -> org.drivine.cypher.api.AmongNeighbours.All
        AmongNeighbours.AFTER -> org.drivine.cypher.api.AmongNeighbours.After
    }
