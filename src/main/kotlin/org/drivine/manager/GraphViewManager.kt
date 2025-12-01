package org.drivine.manager

import org.drivine.model.FragmentModel
import org.drivine.query.GraphViewQueryBuilder
import org.drivine.query.QuerySpecification

/**
 * Manager for working with GraphView classes.
 * Provides methods to query and retrieve GraphViews from the database.
 */
class GraphViewManager(
    private val persistenceManager: PersistenceManager
) {
    /**
     * The name of the database this manager is connected to.
     */
    val database: String
        get() = persistenceManager.database

    /**
     * Loads all instances of a GraphView from the database.
     *
     * @param graphView The GraphView class to load
     * @return List of GraphView instances
     */
    fun <T : Any> loadAll(graphView: Class<T>): List<T> {
        val builder = GraphViewQueryBuilder.forView(graphView)
        val query = builder.buildQuery()

        return persistenceManager.query(
            QuerySpecification
                .withStatement(query)
                .transform(graphView)
        )
    }

    /**
     * Loads a single GraphView instance by its ID.
     *
     * @param id The ID value to search for
     * @param graphView The GraphView class to load
     * @return The GraphView instance, or null if not found
     */
    fun <T : Any> load(id: String, graphView: Class<T>): T? {
        val builder = GraphViewQueryBuilder.forView(graphView)

        // Get the root fragment model to find the node ID field
        val viewModel = org.drivine.model.GraphViewModel.from(graphView)
        val fragmentModel = FragmentModel.from(viewModel.rootFragment.fragmentType)
        val nodeIdField = fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("GraphView ${graphView.name} root fragment ${viewModel.rootFragment.fragmentType.name} does not have a @GraphNodeId field")

        // Build the WHERE clause using the root fragment field name and node ID field
        val rootFieldName = viewModel.rootFragment.fieldName
        val whereClause = "$rootFieldName.$nodeIdField = \$id"

        val query = builder.buildQuery(whereClause)

        return persistenceManager.maybeGetOne(
            QuerySpecification
                .withStatement(query)
                .bind(mapOf("id" to id))
                .transform(graphView)
        )
    }
}