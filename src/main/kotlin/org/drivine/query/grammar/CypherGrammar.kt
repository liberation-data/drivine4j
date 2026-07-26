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

    /**
     * Whether this engine has a native vector index that [vectorSearchHead] can query.
     * Engines without one (e.g. Neptune) leave this `false` and throw from [vectorSearchHead].
     */
    val supportsVectorSearch: Boolean
        get() = false

    /**
     * Emits the *head* of a vector (approximate nearest-neighbour) search: a `CALL` that yields the
     * K nearest nodes bound to [rootAlias] and a **normalized similarity** bound to [scoreAlias]
     * (higher = more similar, the same convention on every engine — each grammar converts its
     * native distance/similarity output as needed). The head ends with a `WITH` that establishes
     * exactly those two variables, so the rest of the load query (filters, projection, RETURN)
     * composes on top of it unchanged.
     *
     * The default throws — engines without a native vector index inherit it.
     *
     * @param spec the resolved index + bound parameter names to search with
     * @param rootAlias the alias the matched node must be bound to (the view's root field name)
     * @param scoreAlias the alias the normalized similarity must be bound to
     */
    fun vectorSearchHead(spec: VectorQuerySpec, rootAlias: String, scoreAlias: String): String =
        throw UnsupportedOperationException(
            "Vector search is not supported on this backend (${this::class.simpleName} has no native vector index)."
        )

    /**
     * How a `@VectorIndex` property value is written on the **save** side, in a `SET` clause RHS —
     * the write-side mirror of [vectorSearchHead]'s read-side `vecf32(...)` wrapping.
     *
     * The default binds the parameter plainly (`$param`), which is correct for engines that index a
     * stored array directly (Neo4j, Memgraph). FalkorDB only indexes a property stored as its native
     * vector type, so [FalkorDbCypherGrammar] wraps it in `vecf32(...)`; without that, an embedding
     * saved through the object manager writes a plain array, the vector index stays empty, and
     * `loadNearest` finds nothing.
     *
     * @param param the bind-parameter name (without the leading `$`); the binding value is unchanged.
     */
    fun vectorPropertyLiteral(param: String): String = "\$$param"

    /**
     * Whether [vectorPropertyLiteral] wraps the value rather than binding it plainly. When true, a
     * vector property cannot be written through a single-shot `SET n += row.props` (an UNWIND batch
     * cannot wrap one property), so the batch path falls back to per-item saves for vector-bearing
     * fragments — mirroring how a `@PropertyBag` root already does. Derived from
     * [vectorPropertyLiteral] so the wrapping rule has a single source of truth.
     */
    val wrapsVectorLiteral: Boolean
        get() = vectorPropertyLiteral(WRAP_PROBE) != "\$$WRAP_PROBE"

    /**
     * Whether this engine has a native full-text index that [fullTextSearchHead] can query.
     * Engines without one (e.g. Neptune) leave this `false` and throw from [fullTextSearchHead].
     */
    val supportsFullTextSearch: Boolean
        get() = false

    /**
     * Emits the *head* of a full-text search: a `CALL` that yields the matching nodes bound to
     * [rootAlias] and a **normalized similarity** in `[0, 1]` bound to [scoreAlias] (higher = more
     * relevant, the same convention on every engine). Raw Lucene/BM25/Tantivy scores are unbounded
     * and not comparable across engines, so every grammar routes its `CALL` through
     * [normalizedFullTextHead], which divides each hit by the batch max. The head ends with a `WITH`
     * that establishes exactly those two variables — the identical output contract to
     * [vectorSearchHead] — so the rest of the search query (threshold, projection, RETURN, the
     * trailing `LIMIT topK`) composes on top of it unchanged.
     *
     * The default throws — engines without a native full-text index inherit it.
     *
     * @param spec the resolved index/label + bound parameter names to search with
     * @param rootAlias the alias the matched node must be bound to (the view's root field name)
     * @param scoreAlias the alias the normalized similarity must be bound to
     */
    fun fullTextSearchHead(spec: FullTextQuerySpec, rootAlias: String, scoreAlias: String): String =
        throw UnsupportedOperationException(
            "Full-text search is not supported on this backend (${this::class.simpleName} has no native full-text index)."
        )

    companion object {
        /** An arbitrary parameter name used only to detect whether [vectorPropertyLiteral] wraps. */
        private const val WRAP_PROBE = "_v"

        /**
         * Wraps an engine-specific full-text `CALL` — which must `YIELD node, score` — in the shared
         * score-normalization tail so all three engines present the identical `[0, 1]` similarity
         * contract. The tail collects the hits, takes the max raw score, and divides each hit by it.
         * The `CASE WHEN max > 0 … ELSE 1.0` guard defends the FalkorDB `0/0 = NaN` edge: when every
         * hit scores 0 (nothing to rank by), they are treated as equally, maximally relevant rather
         * than dropped as NaN. The tail ends binding exactly [rootAlias] (the node) and [scoreAlias]
         * (the normalized similarity), the same shape a `vectorSearchHead` yields.
         *
         * @param callClause the full-text `CALL …` line for this engine (no trailing newline)
         */
        fun normalizedFullTextHead(callClause: String, rootAlias: String, scoreAlias: String): String =
            "$callClause\n" +
                "YIELD node, score\n" +
                "WITH collect({node: node, score: score}) AS _ftResults, max(score) AS _ftMax\n" +
                "UNWIND _ftResults AS _ftRow\n" +
                "WITH _ftRow.node AS $rootAlias, " +
                "(CASE WHEN _ftMax > 0 THEN _ftRow.score / _ftMax ELSE 1.0 END) AS $scoreAlias"
    }
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

    override val supportsVectorSearch: Boolean = true

    /**
     * `db.index.vector.queryNodes(name, k, vector)` — Neo4j's `score` is already a normalized
     * similarity in (0, 1] (higher = closer) for both cosine and euclidean, so it carries through
     * directly. The index is referenced by name.
     */
    override fun vectorSearchHead(spec: VectorQuerySpec, rootAlias: String, scoreAlias: String): String =
        "CALL db.index.vector.queryNodes('${spec.indexName}', \$${spec.topKParam}, \$${spec.vectorParam})\n" +
            "YIELD node, score\n" +
            "WITH node AS $rootAlias, score AS $scoreAlias"

    override val supportsFullTextSearch: Boolean = true

    /**
     * `db.index.fulltext.queryNodes(name, query)` — the full-text index is referenced by **name**.
     * Neo4j returns an unbounded Lucene relevance score; [CypherGrammar.normalizedFullTextHead]
     * normalizes it to `[0, 1]`.
     */
    override fun fullTextSearchHead(spec: FullTextQuerySpec, rootAlias: String, scoreAlias: String): String =
        CypherGrammar.normalizedFullTextHead(
            "CALL db.index.fulltext.queryNodes('${spec.indexName}', \$${spec.queryParam})",
            rootAlias,
            scoreAlias,
        )
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
 * Base openCypher grammar — shared by FalkorDB, Neptune, and other openCypher engines.
 * Uses inline pattern predicates and CALL subquery prologs for filtered existence.
 * Subclass and override for engine-specific capabilities and quirks.
 */
open class OpenCypherGrammar(
    override val collectionSortEmitter: CollectionSortEmitter
) : CypherGrammar {

    override fun existenceCheck(rootAlias: String, direction: String, targetLabels: String) =
        "($rootAlias)$direction(:$targetLabels)"

    override fun filteredExistenceCheck(relationshipPattern: String, whereClause: String, uniqueId: Int): FilteredExistenceResult {
        val countVar = "_ec$uniqueId"
        val rootAlias = relationshipPattern.substringAfter("(").substringBefore(")")
        val targetAlias = relationshipPattern.substringAfterLast("(").substringBefore(")")
        val prolog = "CALL {\n    WITH $rootAlias\n    OPTIONAL MATCH $relationshipPattern WHERE $whereClause\n    RETURN count($targetAlias) AS $countVar\n}"
        return FilteredExistenceResult(
            inlineCondition = "$countVar > 0",
            prolog = prolog,
            bridgeVariables = listOf(countVar),
        )
    }
}

/**
 * FalkorDB — openCypher with known limitations:
 * - Nested pattern comprehensions return NULL (FalkorDB/FalkorDB#1888)
 * - collect() on null produces {key: NULL} instead of skipping (FalkorDB/FalkorDB#1889)
 *
 * CASCADE DELETE_ORPHAN (FalkorDB/FalkorDB#1890 — a DELETE not being visible to a subsequent WHERE
 * pattern predicate in the same query) was fixed upstream (graph module ≥ 41809), so orphan delete
 * is supported again. Drivine tracks FalkorDB `latest`; older builds lacking the fix are not
 * supported for CASCADE DELETE_ORPHAN.
 */
class FalkorDbCypherGrammar(
    collectionSortEmitter: CollectionSortEmitter
) : OpenCypherGrammar(collectionSortEmitter) {
    override val nestedViewProjector: NestedViewProjector = CallSubqueryNestedViewProjector()
    // Inherits supportsOrphanDelete = true from the interface default (FalkorDB#1890 fixed).

    override val supportsVectorSearch: Boolean = true

    /**
     * `db.idx.vector.queryNodes(label, attribute, k, vector)` — queried by label + property (FalkorDB
     * has no index names), and the query vector must be wrapped in `vecf32(...)`. FalkorDB returns a
     * raw *distance* (smaller = closer), so we convert to a higher-is-closer similarity to match the
     * cross-engine convention: cosine distance `d → 1 - d`; euclidean distance `d → 1 / (1 + d)`
     * (the same shape Neo4j applies to euclidean).
     */
    override fun vectorSearchHead(spec: VectorQuerySpec, rootAlias: String, scoreAlias: String): String {
        val similarityExpr = when (spec.similarity) {
            org.drivine.schema.SimilarityFunction.COSINE -> "1.0 - score"
            org.drivine.schema.SimilarityFunction.EUCLIDEAN -> "1.0 / (1.0 + score)"
        }
        return "CALL db.idx.vector.queryNodes('${spec.label}', '${spec.property}', \$${spec.topKParam}, " +
            "vecf32(\$${spec.vectorParam}))\n" +
            "YIELD node, score\n" +
            "WITH node AS $rootAlias, $similarityExpr AS $scoreAlias"
    }

    /**
     * A `@VectorIndex` property must be stored as FalkorDB's native vector type for the index to pick
     * it up — the write-side mirror of the `vecf32(...)` wrapping [vectorSearchHead] applies to the
     * query vector.
     */
    override fun vectorPropertyLiteral(param: String): String = "vecf32(\$$param)"

    override val supportsFullTextSearch: Boolean = true

    /**
     * `db.idx.fulltext.queryNodes(label, query)` — addressed by **label** (FalkorDB has no index
     * names), searching every full-text-indexed property of that label. FalkorDB is the engine that
     * can return `maxScore == 0` (and thus `0/0 = NaN`); [CypherGrammar.normalizedFullTextHead]'s
     * `CASE WHEN max > 0 … ELSE 1.0` guard handles it.
     */
    override fun fullTextSearchHead(spec: FullTextQuerySpec, rootAlias: String, scoreAlias: String): String =
        CypherGrammar.normalizedFullTextHead(
            "CALL db.idx.fulltext.queryNodes('${spec.label}', \$${spec.queryParam})",
            rootAlias,
            scoreAlias,
        )
}