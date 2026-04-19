package org.drivine.query.grammar

import org.drivine.query.sort.CollectionSortEmitter

/**
 * Amazon Neptune — openCypher with:
 * - Working nested pattern comprehensions (unlike FalkorDB)
 * - Working CASCADE DELETE_ORPHAN (unlike FalkorDB)
 * - Built-in `collSortMaps` / `collSortNodes` functions
 * - No EXISTS { } subquery (inherited from OpenCypherGrammar)
 */
class NeptuneCypherGrammar(
    collectionSortEmitter: CollectionSortEmitter
) : OpenCypherGrammar(collectionSortEmitter) {
    // Neptune resolves outer scope variables in nested comprehensions — use inline (like Neo4j)
    override val nestedViewProjector: NestedViewProjector = InlineNestedViewProjector()

    // Neptune handles DELETE + subsequent WHERE pattern predicate correctly
    override val supportsOrphanDelete: Boolean = true

}