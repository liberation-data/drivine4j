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
     * Standard openCypher — FalkorDB, Neptune, and other openCypher-compatible engines.
     * Uses inline pattern predicates for existence checks, `CALL { }` for collection sorting.
     * No APOC support.
     */
    OPEN_CYPHER;

    fun grammar(sortEmitterOverride: CollectionSortEmitter? = null): CypherGrammar = when (this) {
        NEO4J_5 -> Neo4j5Grammar(sortEmitterOverride ?: ApocSortMapsEmitter())
        NEO4J_4 -> Neo4j4Grammar(sortEmitterOverride ?: ApocSortMapsEmitter())
        OPEN_CYPHER -> OpenCypherGrammar(sortEmitterOverride ?: CallSubqueryEmitter())
    }
}