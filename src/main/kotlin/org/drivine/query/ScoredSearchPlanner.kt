package org.drivine.query

import org.drivine.annotation.GraphView
import org.drivine.annotation.NodeFragment
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.grammar.VectorQuerySpec

/**
 * A planned scored search: the engine-specific Cypher plus its parameter bindings, ready to run.
 * Both search kinds ([VectorSearchPlanner], [FullTextSearchPlanner]) produce this; the manager's
 * shared executor turns it into `List<Scored<T>>`.
 */
internal data class ScoredSearchPlan(val cypher: String, val bindings: Map<String, Any?>)

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
        return ScoredSearchPlan(cypher, bindings(vector, topK, threshold, emptyMap(), searchK, resolved))
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
        return ScoredSearchPlan(cypher, bindings(vector, topK, threshold, callerBindings, searchK, resolved))
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
                val spec = FullTextIndexResolver.resolve(rootFragmentType, property, QUERY_PARAM, TOP_K_PARAM)
                GraphViewQueryBuilder.forView(graphClass, grammar).buildFullTextQuery(spec, THRESHOLD_PARAM)
            }

            graphClass.isAnnotationPresent(NodeFragment::class.java) -> {
                val spec = FullTextIndexResolver.resolve(graphClass, property, QUERY_PARAM, TOP_K_PARAM)
                FragmentFullTextSearchBuilder(FragmentModel.from(graphClass), grammar).build(spec, THRESHOLD_PARAM)
            }

            else -> throw IllegalArgumentException(
                "loadMatching requires a @GraphView or @NodeFragment; ${graphClass.simpleName} is neither"
            )
        }
        return ScoredSearchPlan(cypher, bindings(query, topK, threshold, emptyMap()))
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
                val spec = FullTextIndexResolver.resolve(viewModel.rootFragment.fragmentType, null, QUERY_PARAM, TOP_K_PARAM)
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
                val spec = FullTextIndexResolver.resolve(graphClass, null, QUERY_PARAM, TOP_K_PARAM)
                cypher = FragmentFullTextSearchBuilder(FragmentModel.from(graphClass), grammar)
                    .build(spec, THRESHOLD_PARAM, whereResult?.whereClause)
            }

            else -> throw IllegalArgumentException(
                "loadMatching requires a @GraphView or @NodeFragment; ${graphClass.simpleName} is neither"
            )
        }
        return ScoredSearchPlan(cypher, bindings(query, topK, threshold, callerBindings))
    }

    private fun requireSupport(grammar: CypherGrammar) {
        if (!grammar.supportsFullTextSearch) {
            throw UnsupportedOperationException(
                "Full-text search is not supported on this backend (${grammar::class.simpleName} has no native full-text index)."
            )
        }
    }

    private fun bindings(query: String, topK: Int, threshold: Double, caller: Map<String, Any?>) =
        buildMap<String, Any?> {
            put(TOP_K_PARAM, topK)
            put(QUERY_PARAM, query)
            put(THRESHOLD_PARAM, threshold)
            putAll(caller)
        }
}