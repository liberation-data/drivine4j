package org.drivine.query.grammar

import org.drivine.query.sort.CollectionSortEmitter

/**
 * Memgraph — openCypher engine with full ACID, working nested pattern comprehensions, and
 * orphan delete. Ships without APOC (uses MAGE instead).
 *
 * Memgraph's `EXISTS { pattern }` support is more restrictive than Neo4j 5's — it rejects
 * "unbounded variables" inside EXISTS and disallows EXISTS in a WITH clause. To stay compatible
 * with the widest Memgraph version range we fall back to inline pattern predicates (the
 * [OpenCypherGrammar] defaults), which Memgraph accepts everywhere. Filtered existence uses a
 * CALL-subquery + count prolog.
 */
class MemgraphGrammar(
    collectionSortEmitter: CollectionSortEmitter
) : OpenCypherGrammar(collectionSortEmitter) {
    override val nestedViewProjector: NestedViewProjector = InlineNestedViewProjector()

    // Memgraph rejects `EXISTS` inside a `WITH` clause ("Not yet implemented: Exists cannot
    // be used within WITH!") which is what the CASCADE DELETE_ORPHAN query emits. Opt out so
    // callers get a clean UnsupportedOperationException instead of a cryptic server error.
    override val supportsOrphanDelete: Boolean = false

    /**
     * Memgraph cannot execute the `CALL { WITH rootAlias ... RETURN count(x) AS _ec0 }` pattern
     * that [OpenCypherGrammar.filteredExistenceCheck] emits — CALL-with-imported-variables
     * surfaces internally as an "Exists" AST node, and Memgraph errors with
     * "Not yet implemented: Exists cannot be used within WITH!". Use a size-of-pattern-
     * comprehension check instead: standard openCypher, no subquery, no variable imports.
     */
    override fun filteredExistenceCheck(
        relationshipPattern: String,
        whereClause: String,
        uniqueId: Int
    ): FilteredExistenceResult {
        return FilteredExistenceResult(
            inlineCondition = "size([$relationshipPattern WHERE $whereClause | 1]) > 0"
        )
    }
}