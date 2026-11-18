package org.drivine.annotation

/**
 * Marks an interface as describing a fragment of a graph node.
 *
 * A fragment declares:
 *  - the primary label (the anchor label you match on)
 *  - optionally extra labels (e.g., :Person:GithubPerson)
 *
 * It does NOT imply a 1-to-1 class-node mapping like OGMs do.
 * Multiple fragments may describe overlapping slices of the same node.
 *
 * A fragment with a `@NodeId` field is given a range index over it under its primary label when it is read into a
 * [org.drivine.schema.SchemaCatalog] ([org.drivine.schema.SchemaCatalog.fromFragments]), as each save and each load by
 * id finds its node by that property, and without one reads every node of the label (#827). None is added where one is
 * declared: a range index over that property alone, or a uniqueness constraint over it alone, whose index serves it.
 *
 * @property labels the fragment's labels, the first its primary label.
 * @property nodeIdIndex whether the `@NodeId` field is given its range index: `false` leaves it to be declared, or to
 *   go without.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class NodeFragment(
    val labels: Array<String> = [],
    val nodeIdIndex: Boolean = true,
)
