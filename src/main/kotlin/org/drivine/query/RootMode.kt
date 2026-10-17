package org.drivine.query

/**
 * Which root a save writes: one it makes, one it finds, or either. Where the root is not one the
 * save writes, the statement gives no row and writes nothing.
 */
internal enum class RootMode {
    /** A save: the root is made when it is not there, and written when it is. */
    EITHER,

    /** An update: a root that is gone stays gone. */
    FOUND,

    /** A create: a root that is there is not written. */
    ABSENT,
}
