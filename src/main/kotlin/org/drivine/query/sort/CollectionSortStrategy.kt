package org.drivine.query.sort

/**
 * Strategy for emitting Cypher that sorts relationship collections within a query.
 *
 * Different graph engines support different portable options. Pick the strategy that
 * matches your engine and your tolerance for extension dependencies.
 */
enum class CollectionSortStrategy {
    /**
     * Inline expression using `apoc.coll.sortMaps()`. Neo4j only — requires the
     * APOC Extended plugin installed on the server. Supports sorts inside deeply
     * nested projections because it's an expression, not a query block.
     */
    APOC_SORT_MAPS,

    /**
     * Standard Cypher `CALL { ... ORDER BY ... collect }` subquery emitted as a
     * prolog before the RETURN. Portable across Neo4j 5+, FalkorDB, and Neptune.
     * No server-side extensions needed.
     *
     * Limitation: cannot be used for sorts on relationships that live inside a
     * nested projection (e.g. `raisedBy.worksFor.name.asc()`), because `CALL { }`
     * is a query-level construct and cannot appear inside a list comprehension or
     * map projection. Only top-level collection sorts are supported. For nested
     * sorts on engines without APOC, use client-side `@SortedBy` instead.
     */
    CALL_SUBQUERY;

    fun emitter(): CollectionSortEmitter = when (this) {
        APOC_SORT_MAPS -> ApocSortMapsEmitter()
        CALL_SUBQUERY -> CallSubqueryEmitter()
    }
}