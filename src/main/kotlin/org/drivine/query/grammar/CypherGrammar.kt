package org.drivine.query.grammar

import org.drivine.query.sort.CollectionSortEmitter

data class FilteredExistenceResult(
    /** The condition to embed in the WHERE clause (e.g. "EXISTS { ... }" or "_ec0 > 0") */
    val inlineCondition: String,
    /** Optional CALL block to emit before the WHERE (defines variables referenced by inlineCondition) */
    val prolog: String? = null,
    /** Optional WITH bridge variables that must be carried between prolog and WHERE (e.g. "_ec0") */
    val bridgeVariables: List<String> = emptyList(),
)

/**
 * Encapsulates engine-specific Cypher syntax differences.
 *
 * Each grammar knows how to emit the correct syntax for divergence points
 * across Neo4j versions and openCypher-compatible engines like FalkorDB.
 */
interface CypherGrammar {
    val collectionSortEmitter: CollectionSortEmitter

    /**
     * Strategy for projecting nested GraphView relationships.
     * Neo4j uses inline pattern comprehensions; FalkorDB needs CALL subquery prologs
     * due to FalkorDB/FalkorDB#1888.
     */
    val nestedViewProjector: NestedViewProjector
        get() = InlineNestedViewProjector()

    /**
     * Whether this engine supports CASCADE DELETE_ORPHAN — a DELETE followed by
     * a pattern predicate check in the same query. FalkorDB does not (FalkorDB/FalkorDB#1890).
     */
    val supportsOrphanDelete: Boolean
        get() = true

    /**
     * Generates a WHERE-clause condition asserting that a relationship exists.
     * Used for non-nullable single relationships in GraphView queries.
     */
    fun existenceCheck(rootAlias: String, direction: String, targetLabels: String): String

    /**
     * Generates a filtered existence check — asserts that at least one matching
     * relationship exists satisfying the given WHERE conditions.
     *
     * Returns a pair: (inline WHERE condition, optional prolog).
     * For Neo4j, the inline condition is `EXISTS { pattern WHERE cond }` and prolog is null.
     * For openCypher, the inline condition references a count variable, and the prolog
     * is a `CALL { }` block that computes it.
     *
     * @param relationshipPattern e.g. "(issue)-[:ASSIGNED_TO]->(assignee)"
     * @param whereClause e.g. "assignee.name = $param" (without WHERE keyword)
     * @param uniqueId a unique suffix for generated variable names to avoid collisions
     */
    fun filteredExistenceCheck(relationshipPattern: String, whereClause: String, uniqueId: Int = 0): FilteredExistenceResult
}

/**
 * Neo4j 5.x — `EXISTS { pattern }` subquery syntax.
 */
class Neo4j5Grammar(
    override val collectionSortEmitter: CollectionSortEmitter
) : CypherGrammar {
    override fun existenceCheck(rootAlias: String, direction: String, targetLabels: String) =
        "EXISTS { ($rootAlias)$direction(_:$targetLabels) }"

    override fun filteredExistenceCheck(relationshipPattern: String, whereClause: String, uniqueId: Int) =
        FilteredExistenceResult("EXISTS { $relationshipPattern WHERE $whereClause }")
}

/**
 * Neo4j 4.x — deprecated `exists()` function syntax.
 */
class Neo4j4Grammar(
    override val collectionSortEmitter: CollectionSortEmitter
) : CypherGrammar {
    override fun existenceCheck(rootAlias: String, direction: String, targetLabels: String) =
        "exists(($rootAlias)$direction(:$targetLabels))"

    override fun filteredExistenceCheck(relationshipPattern: String, whereClause: String, uniqueId: Int) =
        FilteredExistenceResult("EXISTS { $relationshipPattern WHERE $whereClause }")
}

/**
 * Standard openCypher — FalkorDB, Neptune, and other openCypher engines.
 * Uses inline pattern predicate (the pattern itself is truthy in WHERE context).
 */
/**
 * Standard openCypher — FalkorDB, Neptune, and other openCypher engines.
 * Uses inline pattern predicate (the pattern itself is truthy in WHERE context).
 * Filtered existence uses OPTIONAL MATCH + null check pattern in a subquery,
 * or for simple cases, a pattern comprehension with size check.
 */
class OpenCypherGrammar(
    override val collectionSortEmitter: CollectionSortEmitter
) : CypherGrammar {
    override val nestedViewProjector: NestedViewProjector = CallSubqueryNestedViewProjector()
    override val supportsOrphanDelete: Boolean = false

    override fun existenceCheck(rootAlias: String, direction: String, targetLabels: String) =
        "($rootAlias)$direction(:$targetLabels)"

    override fun filteredExistenceCheck(relationshipPattern: String, whereClause: String, uniqueId: Int): FilteredExistenceResult {
        val countVar = "_ec$uniqueId"
        val rootAlias = relationshipPattern.substringAfter("(").substringBefore(")")
        // Use count(targetAlias) not count(*) — OPTIONAL MATCH null rows make count(*) return 1
        val targetAlias = relationshipPattern.substringAfterLast("(").substringBefore(")")
        val prolog = "CALL {\n    WITH $rootAlias\n    OPTIONAL MATCH $relationshipPattern WHERE $whereClause\n    RETURN count($targetAlias) AS $countVar\n}"
        return FilteredExistenceResult(
            inlineCondition = "$countVar > 0",
            prolog = prolog,
            bridgeVariables = listOf(countVar),
        )
    }
}