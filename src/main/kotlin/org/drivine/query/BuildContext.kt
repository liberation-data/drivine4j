package org.drivine.query

import org.drivine.query.dsl.CollectionSortSpec

/**
 * Per-build state for a single GraphView load-query generation.
 *
 * Replaces what used to be mutable fields on the query builder. A [BuildContext] is created fresh
 * for each `buildQuery` call: it carries the immutable inputs (collection sorts, depth overrides)
 * and accumulates the prologs / bridge variables that projection emits along the way. Because it
 * is scoped to one build rather than to the builder instance, there is no try/finally reset and
 * the load builder is safe to construct per call.
 */
internal class BuildContext(
    /** Collection sort specs requested for this build, keyed by relationship path. */
    val collectionSorts: List<CollectionSortSpec> = emptyList(),
    /** Per-relationship maxDepth overrides applied at query time (by field name). */
    val depthOverrides: Map<String, Int> = emptyMap(),
    externalPrologs: List<String> = emptyList(),
    externalBridgeVariables: List<String> = emptyList(),
) {
    private val _prologs: MutableList<String> = externalPrologs.toMutableList()
    private val _bridgeVariables: MutableList<String> = externalBridgeVariables.toMutableList()

    /** Accumulated `CALL { }` prologs to emit between MATCH and WHERE. */
    val prologs: List<String> get() = _prologs

    /** Bridge variables that a WITH must carry from the prologs into WHERE scope. */
    val bridgeVariables: List<String> get() = _bridgeVariables

    fun addProlog(prolog: String) {
        _prologs.add(prolog)
    }

    fun addBridgeVariables(vars: List<String>) {
        _bridgeVariables.addAll(vars)
    }
}