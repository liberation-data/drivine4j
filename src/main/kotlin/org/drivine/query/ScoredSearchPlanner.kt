package org.drivine.query

import org.drivine.annotation.GraphView
import org.drivine.backend.GraphStatement
import org.drivine.annotation.NodeFragment
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.FullTextQuerySpec
import org.drivine.query.grammar.VectorQuerySpec

/**
 * A planned scored search: the engine-specific Cypher plus its parameter bindings, ready to run.
 * Both search kinds ([VectorSearchPlanner], [FullTextSearchPlanner]) produce this; the manager's
 * shared executor turns it into `List<Scored<T>>`.
 */
/**
 * A planned scored search: the Cypher, its bindings, and what was asked of the index versus what the
 * caller wanted back.
 *
 * The two counts exist so the executor can report a short result. Note what is deliberately absent: the
 * number of rows the index yielded *before* the filter. Observing that would mean restructuring the
 * query to aggregate pre-filter, changing the plan shape and costing every caller on every query. It is
 * also redundant in the only case that matters — when the result is short of [requestedRows] the
 * trailing LIMIT never bound, so the rows returned *are* the post-filter survivors.
 *
 * @param requestedRows rows the caller asked for (`topK`), or null for searches that do not track it
 * @param indexK what the index was actually asked for — `searchK` when over-fetching, else `topK`; for
 *   a full-text search, the [FullTextSearchLimits.indexLimit] its `CALL` was cut to
 */
internal data class ScoredSearchPlan(
    val cypher: String,
    val bindings: Map<String, Any?>,
    val requestedRows: Int? = null,
    val indexK: Int? = null,
)

/**
 * Builds vector (approximate nearest-neighbour) search queries — the query-construction half of
 * `GraphObjectManager.loadNearest`, extracted so the manager only wires up support checks, subtype
 * registration, and execution. Pure: no session or persistence state, only the grammar + inputs.
 */
internal object VectorSearchPlanner {
    const val TOP_K_PARAM = "_vectorTopK"
    const val QUERY_PARAM = "_vectorQuery"
    const val THRESHOLD_PARAM = "_vectorThreshold"
    const val ROW_LIMIT_PARAM = "_vectorRowLimit"
    const val INDEX_NAME_PARAM = "_vectorIndexName"
    const val LABEL_PARAM = "_vectorLabel"
    const val AMONG_IDS_PARAM = "_amongIds"
    const val AMONG_TOP_K_PARAM = "_amongTopK"

    /** Unfiltered vector search over a `@GraphView` (projected) or a bare `@NodeFragment` (itself). */
    fun plan(
        graphClass: Class<*>,
        property: String?,
        vector: List<Float>,
        topK: Int,
        threshold: Double?,
        grammar: CypherGrammar,
        searchK: Int? = null,
        partitionLabel: String? = null,
    ): ScoredSearchPlan {
        requireSupport(grammar)
        // The resolved spec is captured, not just its Cypher: when a partition is targeted the index
        // name is a bound value, and it must be the resolver's derivation rather than a second one.
        lateinit var resolved: VectorQuerySpec
        val cypher = when {
            graphClass.isAnnotationPresent(GraphView::class.java) -> {
                // A view searches its root fragment's embedding and returns the projected view.
                val rootFragmentType = GraphViewModel.from(graphClass).rootFragment.fragmentType
                resolved = resolveSpec(rootFragmentType, property, searchK, partitionLabel)
                GraphViewQueryBuilder.forView(graphClass, grammar).buildVectorQuery(resolved, thresholdParam(threshold))
            }

            graphClass.isAnnotationPresent(NodeFragment::class.java) -> {
                // A fragment searches and returns itself — no relationships, no required-rel filter.
                resolved = resolveSpec(graphClass, property, searchK, partitionLabel)
                FragmentVectorSearchBuilder(FragmentModel.from(graphClass), grammar).build(resolved, thresholdParam(threshold))
            }

            else -> throw IllegalArgumentException(
                "loadNearest requires a @GraphView or @NodeFragment; ${graphClass.simpleName} is neither"
            )
        }
        return ScoredSearchPlan(cypher, bindings(vector, topK, threshold, emptyMap(), searchK, resolved), topK, searchK ?: topK)
    }

    /** Vector search over a `@GraphView` with a caller `where { }` predicate AND-ed into the filter. */
    fun <Q : Any> planFiltered(
        graphClass: Class<*>,
        querySpec: GraphQuerySpec<Q>,
        vector: List<Float>,
        topK: Int,
        threshold: Double?,
        grammar: CypherGrammar,
        searchK: Int? = null,
        partitionLabel: String? = null,
    ): ScoredSearchPlan {
        requireSupport(grammar)

        val cypher: String
        val callerBindings: Map<String, Any?>
        lateinit var resolved: VectorQuerySpec
        when {
            graphClass.isAnnotationPresent(GraphView::class.java) -> {
                val viewModel = GraphViewModel.from(graphClass)
                // Projected-collection mode: property predicates read the projected root map, and
                // relationship quantifiers become list predicates over the projected relationship
                // collection — both run in the post-projection WHERE without traversing the node.
                val whereResult = if (querySpec.conditions.isNotEmpty()) {
                    CypherGenerator.buildWhereClause(querySpec.conditions, viewModel, grammar, projectedCollectionMode = true)
                } else null
                callerBindings = CypherGenerator.extractBindings(querySpec.conditions, viewModel)
                resolved = resolveSpec(viewModel.rootFragment.fragmentType, null, searchK, partitionLabel)
                cypher = GraphViewQueryBuilder.forView(graphClass, grammar)
                    .buildVectorQuery(resolved, thresholdParam(threshold), whereResult?.whereClause)
            }

            graphClass.isAnnotationPresent(NodeFragment::class.java) -> {
                // Fragment predicates render against the node alias `n` (viewModel == null), which the
                // vector head bound the matched node to — applied directly, no projection. The mirror
                // of the filtered full-text fragment path.
                val whereResult = if (querySpec.conditions.isNotEmpty()) {
                    CypherGenerator.buildWhereClause(querySpec.conditions, null, grammar)
                } else null
                callerBindings = CypherGenerator.extractBindings(querySpec.conditions, null)
                resolved = resolveSpec(graphClass, null, searchK, partitionLabel)
                cypher = FragmentVectorSearchBuilder(FragmentModel.from(graphClass), grammar)
                    .build(resolved, thresholdParam(threshold), whereResult?.whereClause)
            }

            else -> throw IllegalArgumentException(
                "loadNearest requires a @GraphView or @NodeFragment; ${graphClass.simpleName} is neither"
            )
        }
        return ScoredSearchPlan(cypher, bindings(vector, topK, threshold, callerBindings, searchK, resolved), topK, searchK ?: topK)
    }

    /**
     * The search, for each of [ids] whose node holds the embedding, for its [topK] nearest among the
     * others: see [NearestAmongBuilder]. A view searches its root fragment, as [plan] does; its
     * relationships play no part, as the set is named by id. The index is asked for [searchK] for each.
     *
     * The score is always measured afresh where the grammar can (Neo4j), as an over-fetched [plan]'s
     * is: the set is a filter after the index, so this search always trims what the index found, and
     * the index's own score does not order quite as the exact one does.
     */
    fun planAmong(
        graphClass: Class<*>,
        property: String?,
        ids: List<String>,
        topK: Int,
        threshold: Double?,
        searchK: Int,
        grammar: CypherGrammar,
    ): GraphStatement {
        requireSupport(grammar)
        val fragment = rootFragment(graphClass)
        val resolved = VectorIndexResolver.resolve(fragment, property, TOP_K_PARAM, QUERY_PARAM, rowLimitParam = AMONG_TOP_K_PARAM)
        val cypher = NearestAmongBuilder(FragmentModel.from(fragment), grammar)
            .build(resolved, AMONG_IDS_PARAM, AMONG_TOP_K_PARAM, thresholdParam(threshold))
        val bindings = buildMap<String, Any?> {
            put(TOP_K_PARAM, searchK)
            put(AMONG_IDS_PARAM, ids)
            put(AMONG_TOP_K_PARAM, topK)
            if (threshold != null) put(THRESHOLD_PARAM, threshold)
        }
        return GraphStatement(cypher, bindings)
    }

    /** The fragment whose embedding a search of [graphClass] measures: the class itself, or a view's root. */
    private fun rootFragment(graphClass: Class<*>): Class<*> = when {
        graphClass.isAnnotationPresent(GraphView::class.java) -> GraphViewModel.from(graphClass).rootFragment.fragmentType
        graphClass.isAnnotationPresent(NodeFragment::class.java) -> graphClass
        else -> throw IllegalArgumentException(
            "loadNearestAmong requires a @GraphView or @NodeFragment; ${graphClass.simpleName} is neither"
        )
    }

    private fun requireSupport(grammar: CypherGrammar) {
        if (!grammar.supportsVectorSearch) {
            throw UnsupportedOperationException(
                "Vector search is not supported on this backend (${grammar::class.simpleName} has no native vector index)."
            )
        }
    }

    private fun thresholdParam(threshold: Double?): String? = if (threshold != null) THRESHOLD_PARAM else null

    /**
     * The trailing `LIMIT` parameter name, or null when the caller did not over-fetch.
     *
     * Null keeps the emitted Cypher byte-identical to what an untuned search has always produced, so
     * turning the knob off is genuinely a no-op rather than a differently-shaped query.
     */
    private fun rowLimitParam(searchK: Int?): String? = if (searchK != null) ROW_LIMIT_PARAM else null

    /**
     * Resolves the index to search, binding the index name and label as parameters when — and only
     * when — a partition was targeted at runtime. See [VectorQuerySpec.indexNameParam] for why they
     * are bound rather than interpolated there, and left literal otherwise.
     */
    private fun resolveSpec(
        fragmentClass: Class<*>,
        property: String?,
        searchK: Int?,
        partitionLabel: String?,
    ): VectorQuerySpec = VectorIndexResolver.resolve(
        fragmentClass,
        property,
        TOP_K_PARAM,
        QUERY_PARAM,
        rowLimitParam(searchK),
        partitionLabel,
        indexNameParam = if (partitionLabel != null) INDEX_NAME_PARAM else null,
        labelParam = if (partitionLabel != null) LABEL_PARAM else null,
    )

    private fun bindings(
        vector: List<Float>,
        topK: Int,
        threshold: Double?,
        caller: Map<String, Any?>,
        searchK: Int? = null,
        resolved: VectorQuerySpec? = null,
    ) = buildMap<String, Any?> {
        // TOP_K_PARAM is what the index is asked for — the beam width. When the caller over-fetches it
        // carries searchK, and topK moves to the post-filter LIMIT.
        put(TOP_K_PARAM, searchK ?: topK)
        put(QUERY_PARAM, vector)
        if (searchK != null) put(ROW_LIMIT_PARAM, topK)
        // Present only under runtime partition targeting; the ordinary path keeps these as literals.
        resolved?.indexNameParam?.let { put(it, resolved.indexName) }
        resolved?.labelParam?.let { put(it, resolved.label) }
        if (threshold != null) put(THRESHOLD_PARAM, threshold)
        putAll(caller)
    }
}

/**
 * Builds full-text search queries — the query-construction half of `GraphObjectManager.loadMatching`,
 * the full-text mirror of [VectorSearchPlanner]. Differences from vector: the threshold is always
 * bound (`[0, 1]`, never null), and the filtered form also supports a bare `@NodeFragment` (predicates
 * render against the node alias `n`, which the search head bound the matched node to).
 */
internal object FullTextSearchPlanner {
    const val TOP_K_PARAM = "_fullTextTopK"
    const val QUERY_PARAM = "_fullTextQuery"
    const val THRESHOLD_PARAM = "_fullTextThreshold"
    const val INDEX_LIMIT_PARAM = "_fullTextIndexLimit"

    /** Unfiltered full-text search over a `@GraphView` (projected) or a bare `@NodeFragment` (itself). */
    fun plan(
        graphClass: Class<*>,
        property: String?,
        query: String,
        topK: Int,
        threshold: Double,
        grammar: CypherGrammar,
    ): ScoredSearchPlan {
        requireSupport(grammar)
        val cypher = when {
            graphClass.isAnnotationPresent(GraphView::class.java) -> {
                val rootFragmentType = GraphViewModel.from(graphClass).rootFragment.fragmentType
                val spec = resolveSpec(rootFragmentType, property)
                GraphViewQueryBuilder.forView(graphClass, grammar).buildFullTextQuery(spec, THRESHOLD_PARAM)
            }

            graphClass.isAnnotationPresent(NodeFragment::class.java) -> {
                val spec = resolveSpec(graphClass, property)
                FragmentFullTextSearchBuilder(FragmentModel.from(graphClass), grammar).build(spec, THRESHOLD_PARAM)
            }

            else -> throw IllegalArgumentException(
                "loadMatching requires a @GraphView or @NodeFragment; ${graphClass.simpleName} is neither"
            )
        }
        return plan(cypher, query, topK, threshold, emptyMap(), FullTextSearchLimits.indexLimit(topK, prunedAfterIndex(graphClass)))
    }

    /** Full-text search over a view or fragment with a caller `where { }` predicate AND-ed in. */
    fun <Q : Any> planFiltered(
        graphClass: Class<*>,
        querySpec: GraphQuerySpec<Q>,
        query: String,
        topK: Int,
        threshold: Double,
        grammar: CypherGrammar,
    ): ScoredSearchPlan {
        requireSupport(grammar)

        val cypher: String
        val callerBindings: Map<String, Any?>
        lateinit var resolved: VectorQuerySpec
        when {
            graphClass.isAnnotationPresent(GraphView::class.java) -> {
                val viewModel = GraphViewModel.from(graphClass)
                // Projected-collection mode: property predicates read the projected root map.
                val whereResult = if (querySpec.conditions.isNotEmpty()) {
                    CypherGenerator.buildWhereClause(querySpec.conditions, viewModel, grammar, projectedCollectionMode = true)
                } else null
                callerBindings = CypherGenerator.extractBindings(querySpec.conditions, viewModel)
                val spec = resolveSpec(viewModel.rootFragment.fragmentType, null)
                cypher = GraphViewQueryBuilder.forView(graphClass, grammar)
                    .buildFullTextQuery(spec, THRESHOLD_PARAM, whereResult?.whereClause)
            }

            graphClass.isAnnotationPresent(NodeFragment::class.java) -> {
                // Fragment predicates render against the node alias `n` (viewModel == null), which the
                // search head bound the matched node to — applied directly, no projection.
                val whereResult = if (querySpec.conditions.isNotEmpty()) {
                    CypherGenerator.buildWhereClause(querySpec.conditions, null, grammar)
                } else null
                callerBindings = CypherGenerator.extractBindings(querySpec.conditions, null)
                val spec = resolveSpec(graphClass, null)
                cypher = FragmentFullTextSearchBuilder(FragmentModel.from(graphClass), grammar)
                    .build(spec, THRESHOLD_PARAM, whereResult?.whereClause)
            }

            else -> throw IllegalArgumentException(
                "loadMatching requires a @GraphView or @NodeFragment; ${graphClass.simpleName} is neither"
            )
        }
        val filtered = querySpec.conditions.isNotEmpty() || prunedAfterIndex(graphClass)
        return plan(cypher, query, topK, threshold, callerBindings, FullTextSearchLimits.indexLimit(topK, filtered))
    }

    private fun requireSupport(grammar: CypherGrammar) {
        if (!grammar.supportsFullTextSearch) {
            throw UnsupportedOperationException(
                "Full-text search is not supported on this backend (${grammar::class.simpleName} has no native full-text index)."
            )
        }
    }

    /**
     * Whether the hits of [graphClass]'s index are pruned after it, as a caller filter prunes them: by a
     * required relationship, or by the root's labels beyond the one the index is on. The labels are
     * those the search checks: a fragment's with those it inherits, as [FragmentFullTextSearchBuilder]
     * checks them, and a view root's own, as a load of the view matches them.
     */
    private fun prunedAfterIndex(graphClass: Class<*>): Boolean {
        val rootLabels =
            if (graphClass.isAnnotationPresent(NodeFragment::class.java)) FragmentModel.labelsFor(graphClass)
            else GraphTypeLabels.labelsForType(graphClass)
        return hasRequiredRelationship(graphClass) || rootLabels.size > 1
    }

    /**
     * Whether [graphClass] is a view with a required relationship — one neither nullable nor a
     * collection — whose `IS NOT NULL` check prunes the hits after the index, as a caller filter does.
     * A view with none keeps every hit, as a fragment does, so its index is asked for `topK` alone.
     */
    private fun hasRequiredRelationship(graphClass: Class<*>): Boolean =
        graphClass.isAnnotationPresent(GraphView::class.java) &&
            GraphViewModel.from(graphClass).relationships.any { !it.isNullable && !it.isCollection }

    private fun resolveSpec(fragmentClass: Class<*>, property: String?): FullTextQuerySpec =
        FullTextIndexResolver.resolve(fragmentClass, property, QUERY_PARAM, TOP_K_PARAM)
            .copy(indexLimitParam = INDEX_LIMIT_PARAM)

    private fun plan(
        cypher: String,
        query: String,
        topK: Int,
        threshold: Double,
        caller: Map<String, Any?>,
        indexLimit: Int,
    ): ScoredSearchPlan {
        val bindings = buildMap<String, Any?> {
            put(TOP_K_PARAM, topK)
            put(INDEX_LIMIT_PARAM, indexLimit)
            put(QUERY_PARAM, query)
            put(THRESHOLD_PARAM, threshold)
            putAll(caller)
        }
        return ScoredSearchPlan(cypher, bindings, topK, indexLimit)
    }
}

/**
 * How many hits a full-text index `CALL` may yield for a search that wants `topK` rows.
 *
 * Where nothing removes a hit after the index — a fragment, or a view without a required
 * relationship, whose root has one label, searched with at most a score threshold, which keeps a
 * prefix of the ranking — the best `topK` hits are the answer, and the index is asked for exactly
 * those. Where something does — a caller `where { }`, a view's required relationships, or a root's
 * labels beyond the first, which the index is on — the index is asked for [FILTERED_OVER_FETCH] times as many, and the filters choose the `topK` among
 * them. A filter that keeps fewer than one hit in [FILTERED_OVER_FETCH] of the best can then return
 * fewer than `topK` rows while the index holds more that pass it; a result that is short of `topK`
 * while the index yielded its whole limit is the one that may be missing rows.
 *
 * The bound is not optional, because the cost of a search is set by it: the head collects every hit
 * the index yields to normalize the scores (see
 * [org.drivine.query.grammar.CypherGrammar.normalizedFullTextHead]), and an ordinary English query
 * matches most of a corpus.
 */
object FullTextSearchLimits {
    /** The over-fetch for a full-text search whose hits are filtered after the index. */
    const val FILTERED_OVER_FETCH: Int = 10

    /** The index limit for a search wanting [topK] rows, given whether its hits are [filtered] afterwards. */
    fun indexLimit(topK: Int, filtered: Boolean): Int =
        if (filtered) (topK.toLong() * FILTERED_OVER_FETCH).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else topK
}