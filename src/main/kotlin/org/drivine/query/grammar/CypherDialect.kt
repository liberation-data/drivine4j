package org.drivine.query.grammar

import org.drivine.query.sort.ApocSortMapsEmitter
import org.drivine.query.sort.CallSubqueryEmitter
import org.drivine.query.sort.CollectionSortEmitter

/**
 * Cypher dialect variants that control query generation for engine-specific syntax.
 *
 * Each dialect produces a [CypherGrammar] with the correct syntax for existence checks,
 * collection sorting, and other divergence points across graph engines.
 */
enum class CypherDialect {

    /**
     * Neo4j 5.x — supports `EXISTS { pattern }`, APOC Extended, `CALL { }` subqueries,
     * and `COLLECT { }` expressions. Default sort uses APOC; override to CALL if preferred.
     */
    NEO4J_5,

    /**
     * Neo4j 4.x — uses deprecated `exists(pattern)` function, requires APOC Extended
     * for collection sorting, no `CALL { }` subquery support.
     */
    NEO4J_4,

    /**
     * Base openCypher dialect. Inline pattern predicates, CALL subquery for
     * filtered existence (no EXISTS { }). Use this as a starting point for
     * new openCypher-compatible engines.
     */
    OPEN_CYPHER,

    /**
     * FalkorDB — openCypher with known limitations:
     * - Nested pattern comprehensions return NULL (FalkorDB/FalkorDB#1888)
     * - No CASCADE DELETE_ORPHAN (FalkorDB/FalkorDB#1890)
     */
    FALKORDB,

    /**
     * Amazon Neptune — openCypher with:
     * - Working nested pattern comprehensions and orphan delete
     * - Built-in `collSortMaps` / `collSortNodes` functions
     */
    NEPTUNE,

    /**
     * Memgraph — Neo4j-compatible openCypher dialect with `EXISTS { pattern }`, working
     * nested pattern comprehensions, and orphan delete. Ships without APOC (uses MAGE
     * instead), so the default sort emitter is `CALL { }` subqueries.
     */
    MEMGRAPH;

    fun grammar(sortEmitterOverride: CollectionSortEmitter? = null): CypherGrammar = when (this) {
        NEO4J_5 -> Neo4j5Grammar(sortEmitterOverride ?: ApocSortMapsEmitter())
        NEO4J_4 -> Neo4j4Grammar(sortEmitterOverride ?: ApocSortMapsEmitter())
        OPEN_CYPHER -> OpenCypherGrammar(sortEmitterOverride ?: CallSubqueryEmitter())
        FALKORDB -> FalkorDbCypherGrammar(sortEmitterOverride ?: CallSubqueryEmitter())
        NEPTUNE -> NeptuneCypherGrammar(sortEmitterOverride ?: CallSubqueryEmitter())
        MEMGRAPH -> MemgraphGrammar(sortEmitterOverride ?: CallSubqueryEmitter())
    }
}