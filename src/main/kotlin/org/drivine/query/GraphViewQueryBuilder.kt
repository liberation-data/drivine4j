package org.drivine.query

import org.drivine.manager.CascadeType
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.grammar.CypherGrammar

/**
 * Builds Cypher queries for GraphView classes.
 *
 * This is a thin facade over the focused builders that do the real work:
 * - [GraphViewLoadBuilder] generates the load query (MATCH + projection + RETURN), one instance
 *   per build with its state held in a [BuildContext].
 * - [GraphViewDeleteBuilder] generates the root-only and cascade-aware DELETE queries.
 *
 * Label and direction rendering shared by both live in [GraphTypeLabels] and [Directions].
 */
class GraphViewQueryBuilder(
    private val viewModel: GraphViewModel,
    private val grammar: CypherGrammar,
) : GraphObjectQueryBuilder {

    private val deleteBuilder = GraphViewDeleteBuilder(viewModel)

    override val nodeAlias: String = viewModel.rootFragment.fieldName

    override fun buildQuery(whereClause: String?, orderByClause: String?): String =
        GraphViewLoadBuilder(viewModel, grammar, BuildContext()).build(whereClause, orderByClause)

    override fun buildQuery(
        whereClause: String?,
        orderByClause: String?,
        collectionSorts: List<CollectionSortSpec>,
        externalPrologs: List<String>,
        externalBridgeVars: List<String>,
    ): String = GraphViewLoadBuilder(
        viewModel,
        grammar,
        BuildContext(
            collectionSorts = collectionSorts,
            externalPrologs = externalPrologs,
            externalBridgeVariables = externalBridgeVars,
        ),
    ).build(whereClause, orderByClause)

    fun buildQuery(
        whereClause: String?,
        orderByClause: String?,
        collectionSorts: List<CollectionSortSpec>,
        depthOverrides: Map<String, Int>,
        externalPrologs: List<String> = emptyList(),
        externalBridgeVars: List<String> = emptyList(),
    ): String = GraphViewLoadBuilder(
        viewModel,
        grammar,
        BuildContext(
            collectionSorts = collectionSorts,
            depthOverrides = depthOverrides,
            externalPrologs = externalPrologs,
            externalBridgeVariables = externalBridgeVars,
        ),
    ).build(whereClause, orderByClause)

    override fun buildIdWhereClause(idParamName: String): String {
        val rootFragmentModel = viewModel.rootFragment
        val fragmentModel = FragmentModel.from(rootFragmentModel.fragmentType)
        val nodeIdField = fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("GraphView root fragment ${rootFragmentModel.fragmentType.name} does not have a @GraphNodeId field")
        val rootFieldName = rootFragmentModel.fieldName
        return "$rootFieldName.$nodeIdField = \$$idParamName"
    }

    override fun buildDeleteQuery(whereClause: String?, prologs: List<String>, bridgeVariables: List<String>): String =
        deleteBuilder.buildDeleteQuery(whereClause, prologs, bridgeVariables)

    override fun buildCascadeDeleteQuery(whereClause: String?, cascade: CascadeType): String =
        deleteBuilder.buildCascadeDeleteQuery(whereClause, cascade)

    companion object {
        fun forView(viewClass: Class<*>, grammar: CypherGrammar): GraphViewQueryBuilder {
            val viewModel = GraphViewModel.from(viewClass)
            return GraphViewQueryBuilder(viewModel, grammar)
        }

        fun forView(viewClass: kotlin.reflect.KClass<*>, grammar: CypherGrammar): GraphViewQueryBuilder {
            return forView(viewClass.java, grammar)
        }
    }
}