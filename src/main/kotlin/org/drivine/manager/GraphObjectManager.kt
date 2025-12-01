package org.drivine.manager

import org.drivine.annotation.GraphFragment
import org.drivine.annotation.GraphView
import org.drivine.model.FragmentModel
import org.drivine.query.GraphObjectQueryBuilder
import org.drivine.query.QuerySpecification

/**
 * Manager for working with graph objects (GraphViews and GraphFragments).
 * Provides methods to query and retrieve graph-mapped objects from the database.
 */
class GraphObjectManager(
    private val persistenceManager: PersistenceManager
) {
    /**
     * The name of the database this manager is connected to.
     */
    val database: String
        get() = persistenceManager.database

    /**
     * Loads all instances of a graph object (GraphView or GraphFragment) from the database.
     *
     * @param graphClass The graph object class to load
     * @return List of graph object instances
     */
    fun <T : Any> loadAll(graphClass: Class<T>): List<T> {
        val builder = GraphObjectQueryBuilder.forClass(graphClass)
        val query = builder.buildQuery()

        return persistenceManager.query(
            QuerySpecification
                .withStatement(query)
                .transform(graphClass)
        )
    }

    /**
     * Loads a single graph object instance by its ID.
     *
     * @param id The ID value to search for
     * @param graphClass The graph object class to load
     * @return The graph object instance, or null if not found
     */
    fun <T : Any> load(id: String, graphClass: Class<T>): T? {
        val builder = GraphObjectQueryBuilder.forClass(graphClass)

        // Determine the WHERE clause based on the type
        val whereClause = if (graphClass.isAnnotationPresent(GraphView::class.java)) {
            // For GraphViews, use the root fragment field name
            val viewModel = org.drivine.model.GraphViewModel.from(graphClass)
            val fragmentModel = FragmentModel.from(viewModel.rootFragment.fragmentType)
            val nodeIdField = fragmentModel.nodeIdField
                ?: throw IllegalArgumentException("GraphView ${graphClass.name} root fragment ${viewModel.rootFragment.fragmentType.name} does not have a @GraphNodeId field")
            val rootFieldName = viewModel.rootFragment.fieldName
            "$rootFieldName.$nodeIdField = \$id"
        } else if (graphClass.isAnnotationPresent(GraphFragment::class.java)) {
            // For GraphFragments, use the node alias directly
            val fragmentModel = FragmentModel.from(graphClass)
            val nodeIdField = fragmentModel.nodeIdField
                ?: throw IllegalArgumentException("GraphFragment ${graphClass.name} does not have a @GraphNodeId field")
            "n.$nodeIdField = \$id"
        } else {
            throw IllegalArgumentException("Class ${graphClass.name} must be annotated with @GraphView or @GraphFragment")
        }

        val query = builder.buildQuery(whereClause)

        return persistenceManager.maybeGetOne(
            QuerySpecification
                .withStatement(query)
                .bind(mapOf("id" to id))
                .transform(graphClass)
        )
    }
}