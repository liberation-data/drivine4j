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
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class NodeFragment(
    val labels: Array<String> = []
)
