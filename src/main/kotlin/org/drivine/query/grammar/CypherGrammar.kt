package org.drivine.query.grammar

import org.drivine.query.sort.CollectionSortEmitter
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.SimilarityFunction

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
     * Whether this engine supports CASCADE DELETE_ORPHAN — a DELETE followed by a check for
     * remaining relationships in the same query. Memgraph does not: see [orphanDeleteLimit].
     */
    val supportsOrphanDelete: Boolean
        get() = true

    /** Why this engine has no CASCADE DELETE_ORPHAN, for the message that refuses it. Null when it has. */
    val orphanDeleteLimit: String?
        get() = null


    /**
     * Whether this engine can satisfy an `ORDER BY` from an index and stop once the page is full.
     *
     * Neo4j can: an ordered keyset page plans as `NodeIndexSeek` → `Limit`, with no sort operator at
     * all, which is what makes keyset pagination cost O(page). Memgraph and FalkorDB always place a
     * blocking sort between the scan and the limit — measured even in the simplest case, one indexed
     * property and no predicate — so pagination there costs O(remaining) no matter what is indexed.
     *
     * Index advice is only given where this is true; elsewhere there is no index to recommend,
     * because none would change the plan.
     */
    val supportsIndexBackedOrdering: Boolean
        get() = false

    /**
     * Whether an index excludes nodes that lack the indexed property.
     *
     * On Neo4j it does, so a composite index is unusable unless the query provably excludes those
     * nodes — which is why keyset predicates constrain every cursor key with `IS NOT NULL`. Memgraph
     * gains nothing from those conjuncts and is actively slowed by them: they cost it the range bound
     * and roughly double its scan.
     */
    val indexesExcludeNulls: Boolean
        get() = false

    /**
     * How the engine reads [moment], an expression that reads a moment as the engine holds one, as
     * seconds since the epoch with their fraction: [TemporalOrderSupport.Written] with the expression, or
     * [TemporalOrderSupport.Refused] with the engine and the reason it cannot. An order by an expression that
     * asks for it where it is refused is refused before anything runs. A grammar that does not say
     * refuses it.
     */
    fun epochSeconds(moment: String): TemporalOrderSupport = TemporalOrderSupport.Refused(
        engine = "This engine",
        reason = "its grammar does not say how to read a moment as a number",
    )

    /**
     * Whether what [existenceCheck] and [filteredExistenceCheck] give may stand only in a `WHERE`,
     * and not as a value of a `WITH`. Neo4j 4 takes `EXISTS { }` nowhere else. A statement that
     * must know whether a node met a predicate, and not only find the nodes that do, then reads the
     * predicate in the `WHERE` of an `OPTIONAL MATCH`.
     */
    val predicatesOnlyInWhere: Boolean
        get() = false

    /**
     * Whether a read takes no snapshot, so one query can find a node's properties as different
     * commits left them. Neo4j reads under read committed without a lock: a load that runs while
     * another writer commits can be handed that writer's stamp with the data from before its write,
     * however the query orders its reads. A checked save of an object that was loaded then compares
     * the fields as loaded too, under the node's lock. Memgraph and Neptune read from a snapshot,
     * and FalkorDB runs no read beside a write.
     */
    val readsWithoutSnapshot: Boolean
        get() = false

    /**
     * Whether the node a `@GraphPath` field ends at is tested for its labels once the path is matched,
     * and not in the pattern that matches it.
     *
     * The path is an `OPTIONAL MATCH` from a root already found. FalkorDB 6 plans one whose far node
     * has a label from that end: it reads every relationship into the label and then joins back to
     * the root (FalkorDB/FalkorDB#3332). With the label left out of the pattern it walks out from the
     * root, and a label asked for in a `WHERE` on the match is planned as one in the pattern is.
     */
    val testsPathEndAfterMatch: Boolean
        get() = false

    /**
     * Generates a WHERE-clause condition asserting that a relationship exists.
     * Used for non-nullable single relationships in GraphView queries, and for an empty `any { }` /
     * `none { }` block, which passes no [targetLabels]: the far node is then left unlabelled.
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
     * exactly those two variables, after any [VectorQuerySpec.carried] from before the head, so the
     * rest of the load query (filters, projection, RETURN) composes on top of it unchanged. The query
     * vector is [VectorQuerySpec.vectorSource] when set, else the bound [VectorQuerySpec.vectorParam].
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
     * A grammar that wraps overrides [vectorExpression], which this calls, and not this: a patch of
     * many writes a vector from a row, through [vectorExpression] alone.
     *
     * @param param the bind-parameter name (without the leading `$`); the binding value is unchanged.
     */
    fun vectorPropertyLiteral(param: String): String = vectorExpression("\$$param")

    /**
     * [expression], which gives a `@VectorIndex` property's value, as it is written to the property:
     * a parameter, as [vectorPropertyLiteral] gives it, or the field of a row of an `UNWIND`. An engine
     * that stores a vector as a type of its own wraps it here, and nowhere else. The expression must
     * not be null where the engine wraps: `vecf32(null)` is an error.
     */
    fun vectorExpression(expression: String): String = expression

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
         * The hits are cut to the best [indexLimitParam] before they are collected, on every engine.
         * The collected list is what a search's memory is made of: each row unwound from it carries
         * it, so an operator that holds rows after the head — the sort that keeps the top `k` — is
         * charged for the whole list once per row it holds. Measured on Neo4j 5.26 over 40,000
         * matching chunks, a `k = 200` search with the hits uncut exhausted the 1.4 GiB transaction
         * pool on its own; cut to 2,000 the whole query held 3.9 MB. An engine whose `CALL` takes a
         * limit is given it as well ([Neo4j5Grammar]), so the index stops producing rows instead of
         * producing them to be dropped; the cut here is what holds where the `CALL` takes none.
         *
         * Ties at the cut are broken however the engine sorts, which is the same freedom the
         * trailing `LIMIT` has always had.
         *
         * @param callClause the full-text `CALL …` line for this engine (no trailing newline)
         * @param indexLimitParam the bound parameter carrying how many hits to keep
         */
        fun normalizedFullTextHead(callClause: String, rootAlias: String, scoreAlias: String, indexLimitParam: String): String =
            """
            |$callClause
            |YIELD node, score
            |WITH node, score
            |ORDER BY score DESC
            |LIMIT ${'$'}$indexLimitParam
            |WITH collect({node: node, score: score}) AS _ftResults, max(score) AS _ftMax
            |UNWIND _ftResults AS _ftRow
            |WITH _ftRow.node AS $rootAlias, (CASE WHEN _ftMax > 0 THEN _ftRow.score / _ftMax ELSE 1.0 END) AS $scoreAlias
            """.trimMargin()
    }
}

/**
 * Whether a grammar supports the temporal order capability (`org.drivine.query.dsl.temporal`): how it
 * reads a moment as seconds since the epoch (see [CypherGrammar.epochSeconds]), written as an
 * expression, or refused, with the reason.
 */
sealed interface TemporalOrderSupport {
    /** The engine reads the moment as seconds by [expression]. */
    data class Written(val expression: String) : TemporalOrderSupport

    /** [engine] cannot read a moment as a number, for [reason]. */
    data class Refused(val engine: String, val reason: String) : TemporalOrderSupport
}

/** Neo4j 4 and 5 hold a moment as a native temporal value, whose milliseconds since the epoch it gives as a property. */
private fun neo4jEpochSeconds(moment: String) = TemporalOrderSupport.Written("$moment.epochMillis / 1000.0")

/** `:A:B` for labels `A:B`, and nothing for none: `(:)` parses on no engine. */
private fun labelled(targetLabels: String) = if (targetLabels.isEmpty()) "" else ":$targetLabels"

/**
 * Neo4j 5.x — `EXISTS { pattern }` subquery syntax.
 */
class Neo4j5Grammar(
    override val collectionSortEmitter: CollectionSortEmitter
) : CypherGrammar {
    override fun existenceCheck(rootAlias: String, direction: String, targetLabels: String) =
        "EXISTS { ($rootAlias)$direction(_${labelled(targetLabels)}) }"

    override fun filteredExistenceCheck(relationshipPattern: String, whereClause: String, uniqueId: Int) =
        FilteredExistenceResult("EXISTS { $relationshipPattern WHERE $whereClause }")

    override val supportsIndexBackedOrdering: Boolean = true

    override val indexesExcludeNulls: Boolean = true

    override fun epochSeconds(moment: String): TemporalOrderSupport = neo4jEpochSeconds(moment)

    override val readsWithoutSnapshot: Boolean = true

    override val supportsVectorSearch: Boolean = true

    /**
     * `db.index.vector.queryNodes(name, k, vector)` — Neo4j's `score` is already a normalized
     * similarity in (0, 1] (higher = closer) for both cosine and euclidean, so it carries through
     * directly. The index is referenced by name.
     *
     * **Unless the caller over-fetched**, in which case the score is recomputed exactly. The yielded
     * score does not order identically to exact similarity: measured on a 9k-vector, 1536-dim cosine
     * index with one dense cluster, the true top 40 were all present inside a beam of 200, yet
     * trimming that beam by the yielded score returned only 38 of them. Re-ranking the same beam by
     * `vector.similarity.cosine` returned all 40.
     *
     * That matters only when over-fetching, because the trailing `LIMIT` that `searchK` introduces is
     * what discards rows the beam already found. With no row limit there is nothing to discard, so
     * the emitted Cypher stays byte-identical and `searchK = null` remains a true no-op.
     */
    override fun vectorSearchHead(spec: VectorQuerySpec, rootAlias: String, scoreAlias: String): String {
        val call =
            "CALL db.index.vector.queryNodes(${spec.indexNameArgument()}, \$${spec.topKParam}, ${spec.vectorArgument()})"
        if (spec.rowLimitParam == null) {
            return "$call\nYIELD node, score\nWITH ${spec.carriedPrefix()}node AS $rootAlias, score AS $scoreAlias"
        }
        val similarityFunction = when (spec.similarity) {
            SimilarityFunction.COSINE -> "vector.similarity.cosine"
            SimilarityFunction.EUCLIDEAN -> "vector.similarity.euclidean"
        }
        return "$call\nYIELD node\n" +
            "WITH ${spec.carriedPrefix()}node AS $rootAlias, $similarityFunction(node.${SchemaGrammar.identifier(spec.property)}, ${spec.vectorArgument()}) AS $scoreAlias"
    }

    override val supportsFullTextSearch: Boolean = true

    /**
     * `db.index.fulltext.queryNodes(name, query, {limit: n})` — the full-text index is referenced by
     * **name**, and asked for its best `n` hits so Lucene collects no more than that. Neo4j returns an
     * unbounded Lucene relevance score; [CypherGrammar.normalizedFullTextHead] normalizes it to `[0, 1]`.
     */
    override fun fullTextSearchHead(spec: FullTextQuerySpec, rootAlias: String, scoreAlias: String): String =
        CypherGrammar.normalizedFullTextHead(
            "CALL db.index.fulltext.queryNodes('${spec.indexName}', \$${spec.queryParam}, {limit: \$${spec.indexLimitParam}})",
            rootAlias,
            scoreAlias,
            spec.indexLimitParam,
        )
}

/**
 * Neo4j 4.x — deprecated `exists()` function syntax.
 */
class Neo4j4Grammar(
    override val collectionSortEmitter: CollectionSortEmitter
) : CypherGrammar {
    override fun existenceCheck(rootAlias: String, direction: String, targetLabels: String) =
        "exists(($rootAlias)$direction(${labelled(targetLabels)}))"

    override fun filteredExistenceCheck(relationshipPattern: String, whereClause: String, uniqueId: Int) =
        FilteredExistenceResult("EXISTS { $relationshipPattern WHERE $whereClause }")

    override val supportsIndexBackedOrdering: Boolean = true

    override val indexesExcludeNulls: Boolean = true

    override fun epochSeconds(moment: String): TemporalOrderSupport = neo4jEpochSeconds(moment)

    override val predicatesOnlyInWhere: Boolean = true

    override val readsWithoutSnapshot: Boolean = true
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
        "($rootAlias)$direction(${labelled(targetLabels)})"

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

    override val testsPathEndAfterMatch: Boolean = true

    /**
     * FalkorDB is given a moment as its ISO-8601 text (see [org.drivine.query.TemporalCoercer]), and
     * has no function that reads a moment as a number: no `epochMillis`, and no arithmetic on a
     * datetime. So an order that asks for one is refused.
     */
    override fun epochSeconds(moment: String): TemporalOrderSupport = TemporalOrderSupport.Refused(
        engine = "FalkorDB",
        reason = """
            it holds a moment as its ISO-8601 text, and has no function that reads one as a number. Store the
            moment's seconds since the epoch in a property of their own, and order by that.
        """.trimIndent().replace("\n", " "),
    )

    override val supportsVectorSearch: Boolean = true

    /**
     * `db.idx.vector.queryNodes(label, attribute, k, vector)` — queried by label + property (FalkorDB
     * has no index names), and the query vector must be wrapped in `vecf32(...)`. FalkorDB yields a
     * *distance* (smaller = closer): for cosine `1 - cos`, in `[0, 2]`, and for euclidean the plain
     * distance `d`, not its square (what `vec.cosineDistance` and `vec.euclideanDistance` give). Each
     * is rescaled to the score Neo4j and the SQL stores give: cosine `(1 + cos) / 2`, as
     * `1 - score / 2`, and euclidean `1 / (1 + d²)`, as `1 / (1 + score * score)`. The distance is
     * exact for the vectors found, so an over-fetched search needs no rescoring as Neo4j's does.
     *
     * A vector read from a stored property ([VectorQuerySpec.vectorSource]) is not wrapped: it is
     * already FalkorDB's vector type, and `vecf32` takes only a list.
     */
    override fun vectorSearchHead(spec: VectorQuerySpec, rootAlias: String, scoreAlias: String): String {
        val similarityExpr = when (spec.similarity) {
            SimilarityFunction.COSINE -> "1.0 - score / 2.0"
            SimilarityFunction.EUCLIDEAN -> "1.0 / (1.0 + score * score)"
        }
        return "CALL db.idx.vector.queryNodes(${spec.labelArgument()}, '${spec.property}', \$${spec.topKParam}, " +
            "${spec.vectorSource ?: "vecf32(\$${spec.vectorParam})"})\n" +
            "YIELD node, score\n" +
            "WITH ${spec.carriedPrefix()}node AS $rootAlias, $similarityExpr AS $scoreAlias"
    }

    /**
     * A `@VectorIndex` property must be stored as FalkorDB's native vector type for the index to pick
     * it up — the write-side mirror of the `vecf32(...)` wrapping [vectorSearchHead] applies to the
     * query vector.
     */
    override fun vectorExpression(expression: String): String = "vecf32($expression)"

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
            spec.indexLimitParam,
        )
}