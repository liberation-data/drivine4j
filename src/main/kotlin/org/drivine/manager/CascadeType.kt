package org.drivine.manager

/**
 * Defines cascade behavior when saving GraphViews with modified relationships.
 *
 * Determines what happens to target objects when a relationship is removed.
 */
enum class CascadeType {
    /**
     * Default behavior - only delete the relationship, leave target objects intact.
     * Safest option - never deletes data.
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
     */
    DELETE_ORPHAN
}