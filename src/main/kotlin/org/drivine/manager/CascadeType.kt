package org.drivine.manager

/**
 * What happens to the nodes at the far end of a relationship that goes away.
 *
 * - On a `delete`, by either manager, it says what becomes of the fragments the deleted view
 *   includes.
 * - On a `save` it applies only to the deprecated [GraphObjectManager], where it says what becomes
 *   of the target of a relationship the save removes. A [StatelessGraphObjectManager] save takes no
 *   cascade: there [Replace] names what a save removes, and [RemovedTargets] what becomes of the
 *   targets.
 */
enum class CascadeType {
    /**
     * Default behavior - only delete the relationship, leave target objects intact. On a delete,
     * only the root is deleted.
     * Safest option - never deletes data beyond what was asked for.
     */
    NONE,

    /**
     * Delete both the relationship and the target object(s).
     *
     * For GraphFragments: Deletes the target node and all its relationships.
     * For nested GraphViews: Recursively deletes all fragments and relationships in the view.
     *
     * Warning: This permanently deletes data. Use with caution.
     */
    DELETE_ALL,

    /**
     * Delete relationship and target only if no other relationships point to the target.
     *
     * Safe option - only deletes if the target becomes orphaned (no incoming or outgoing relationships).
     * Uses a two-step Cypher query: DELETE relationship, then DELETE target WHERE NOT EXISTS relationships.
     *
     * Not supported on Memgraph, where it throws `UnsupportedOperationException`.
     */
    DELETE_ORPHAN,

    /**
     * Preserve all existing relationships — only add new ones, never remove.
     *
     * Use for append-only patterns where the save contains a subset of the full
     * relationship set (e.g., adding a single message to a session without loading
     * all existing messages). Snapshot-detected removals are silently skipped.
     *
     * Meaningful only on a [GraphObjectManager] save. A delete treats it as [NONE].
     */
    PRESERVE
}
