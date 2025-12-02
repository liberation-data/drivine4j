package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.GraphFragment
import org.drivine.annotation.GraphView
import org.drivine.model.FragmentModel
import org.drivine.query.GraphObjectQueryBuilder
import org.drivine.query.QuerySpecification
import org.drivine.session.SessionManager

/**
 * Manager for working with graph objects (GraphViews and GraphFragments).
 * Provides methods to query and retrieve graph-mapped objects from the database.
 *
 * Maintains a session to track loaded objects and enable dirty checking for optimized saves.
 */
class GraphObjectManager(
    private val persistenceManager: PersistenceManager,
    private val sessionManager: SessionManager,
    private val objectMapper: ObjectMapper
) {
    /**
     * The name of the database this manager is connected to.
     */
    val database: String
        get() = persistenceManager.database

    /**
     * Loads all instances of a graph object (GraphView or GraphFragment) from the database.
     * Loaded objects are automatically added to the session for dirty tracking.
     *
     * @param graphClass The graph object class to load
     * @return List of graph object instances
     */
    fun <T : Any> loadAll(graphClass: Class<T>): List<T> {
        val builder = GraphObjectQueryBuilder.forClass(graphClass)
        val query = builder.buildQuery()

        val results = persistenceManager.query(
            QuerySpecification
                .withStatement(query)
                .transform(graphClass)
        )

        // Snapshot loaded objects for dirty tracking
        snapshotResults(graphClass, results)

        return results
    }

    /**
     * Loads a single graph object instance by its ID.
     * The loaded object is automatically added to the session for dirty tracking.
     *
     * @param id The ID value to search for
     * @param graphClass The graph object class to load
     * @return The graph object instance, or null if not found
     */
    fun <T : Any> load(id: String, graphClass: Class<T>): T? {
        val builder = GraphObjectQueryBuilder.forClass(graphClass)
        val whereClause = builder.buildIdWhereClause("id")
        val query = builder.buildQuery(whereClause)

        val result = persistenceManager.maybeGetOne(
            QuerySpecification
                .withStatement(query)
                .bind(mapOf("id" to id))
                .transform(graphClass)
        )

        // Snapshot the loaded object for dirty tracking
        if (result != null) {
            snapshotResults(graphClass, listOf(result))
        }

        return result
    }

    /**
     * Takes snapshots of loaded objects for dirty tracking.
     * Delegates to SessionManager to handle the snapshotting details.
     */
    private fun <T : Any> snapshotResults(graphClass: Class<T>, results: List<T>) {
        if (results.isEmpty()) return

        // Get the fragment model and root field name (for Views, use root fragment)
        val (fragmentModel, rootFragmentFieldName) = if (graphClass.isAnnotationPresent(GraphView::class.java)) {
            val viewModel = org.drivine.model.GraphViewModel.from(graphClass)
            val fragmentModel = FragmentModel.from(viewModel.rootFragment.fragmentType)
            Pair(fragmentModel, viewModel.rootFragment.fieldName)
        } else {
            Pair(FragmentModel.from(graphClass), null)
        }

        // Let SessionManager handle the snapshotting
        sessionManager.snapshotAll(results, fragmentModel, rootFragmentFieldName)
    }

    /**
     * Saves a graph object (GraphView or GraphFragment) to the database.
     *
     * For objects loaded in this session, only dirty fields are written (optimized save).
     * For objects not in the session, all fields are written (full save).
     *
     * Uses MERGE pattern: creates if not exists, updates if exists.
     *
     * For GraphViews:
     * - Saves root fragment
     * - Detects relationship changes (added/removed)
     * - Deletes removed relationships (keeps fragments)
     * - Merges added relationships and their fragments
     *
     * @param obj The object to save
     * @return The saved object
     */
    fun <T : Any> save(obj: T): T {
        val graphClass = obj.javaClass

        // Build merge statements using polymorphic builder
        val mergeBuilder = org.drivine.query.GraphObjectMergeBuilder.forClass(
            graphClass,
            objectMapper,
            sessionManager
        )
        val statements = mergeBuilder.buildMergeStatements(obj)

        // Execute all statements in order
        statements.forEach { statement ->
            persistenceManager.execute(
                QuerySpecification
                    .withStatement(statement.statement)
                    .bind(statement.bindings)
            )
        }

        // Update snapshot after save
        snapshotResults(graphClass, listOf(obj))

        return obj
    }
}