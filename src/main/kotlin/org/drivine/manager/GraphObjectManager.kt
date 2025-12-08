package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
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
        // Auto-register subtypes if this is a sealed/abstract class
        autoRegisterSubtypesIfNeeded(graphClass)

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
     * Auto-registers subtypes for a class hierarchy based on Neo4j labels.
     * For sealed classes and classes with @JsonSubTypes, automatically registers all subclasses
     * using their simple name as the discriminator.
     * The TransformPostProcessor will use Neo4j labels to determine the concrete type.
     */
    private fun autoRegisterSubtypesIfNeeded(graphClass: Class<*>) {
        val kotlinClass = graphClass.kotlin

        // First, check for Jackson's @JsonSubTypes annotation (works for both Java and Kotlin)
        val jsonSubTypes = graphClass.getAnnotation(com.fasterxml.jackson.annotation.JsonSubTypes::class.java)
        if (jsonSubTypes != null) {
            jsonSubTypes.value.forEach { subType ->
                persistenceManager.registerSubtype(graphClass, subType.name, subType.value.java)
            }
            return
        }

        // Second, check if this is a Kotlin sealed class
        if (kotlinClass.isSealed) {
            kotlinClass.sealedSubclasses.forEach { subclass ->
                val subclassJava = subclass.java

                // Extract labels from @NodeFragment annotation to use as discriminators
                val nodeFragment = subclassJava.getAnnotation(org.drivine.annotation.NodeFragment::class.java)
                if (nodeFragment != null) {
                    // Register using each label that's NOT in the base class labels
                    val baseFragment = graphClass.getAnnotation(org.drivine.annotation.NodeFragment::class.java)
                    val baseLabels = baseFragment?.labels?.toSet() ?: emptySet()
                    val subLabels = nodeFragment.labels.toSet()
                    val distinctLabels = subLabels - baseLabels

                    // Register using the distinct labels
                    distinctLabels.forEach { label ->
                        persistenceManager.registerSubtype(graphClass, label, subclassJava)
                    }
                }

                // Also register using simple class name as fallback
                persistenceManager.registerSubtype(graphClass, subclass.simpleName ?: subclassJava.simpleName, subclassJava)
            }
        }
    }

    /**
     * Loads instances of a graph object using a type-safe query DSL.
     * Supports filtering and ordering.
     *
     * Pass the query DSL object explicitly to enable type-safe property access.
     *
     * Example:
     * ```kotlin
     * graphObjectManager.loadAll(
     *     RaisedAndAssignedIssue::class.java,
     *     RaisedAndAssignedIssueQueryDsl.INSTANCE
     * ) {
     *     where {
     *         this(query.issue.state eq "open")
     *         this(query.issue.id gt 1000)
     *     }
     *     orderBy {
     *         this(query.issue.id.desc())
     *     }
     * }
     * ```
     *
     * **Future with Code Generation:**
     * When code generation is implemented, query DSLs will be auto-registered via QueryDslRegistry,
     * and you'll be able to use an even cleaner extension function syntax without passing the query object.
     *
     * @param graphClass The graph object class to load
     * @param queryObject The query object providing property references
     * @param spec DSL block for building the query
     * @return List of graph object instances matching the criteria
     */
    fun <T : Any, Q : Any> loadAll(
        graphClass: Class<T>,
        queryObject: Q,
        spec: org.drivine.query.dsl.GraphQuerySpec<Q>.() -> Unit
    ): List<T> {
        // Auto-register subtypes if this is a sealed/abstract class
        autoRegisterSubtypesIfNeeded(graphClass)

        val querySpec = org.drivine.query.dsl.GraphQuerySpec(queryObject)
        querySpec.spec()

        val builder = GraphObjectQueryBuilder.forClass(graphClass)

        // Get GraphViewModel if this is a @GraphView (needed for relationship filtering)
        val viewModel = if (graphClass.isAnnotationPresent(GraphView::class.java)) {
            org.drivine.model.GraphViewModel.from(graphClass)
        } else {
            null
        }

        // Build WHERE clause from conditions
        val whereClause = if (querySpec.conditions.isNotEmpty()) {
            org.drivine.query.dsl.CypherGenerator.buildWhereClause(querySpec.conditions, viewModel)
        } else null

        // Build ORDER BY clause from orders
        val orderByClause = if (querySpec.orders.isNotEmpty()) {
            org.drivine.query.dsl.CypherGenerator.buildOrderByClause(querySpec.orders)
        } else null

        // Build the complete query
        val query = builder.buildQuery(whereClause, orderByClause)

        // Extract bindings from conditions (pass viewModel to ensure same ordering as buildWhereClause)
        val bindings = org.drivine.query.dsl.CypherGenerator.extractBindings(querySpec.conditions, viewModel)

        val results = persistenceManager.query(
            QuerySpecification
                .withStatement(query)
                .bind(bindings)
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
        // Auto-register subtypes if this is a sealed/abstract class
        autoRegisterSubtypesIfNeeded(graphClass)

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
     *
     * For polymorphic results (when graphClass is abstract/sealed), snapshot each object
     * using its actual runtime class rather than the query target class.
     */
    private fun <T : Any> snapshotResults(graphClass: Class<T>, results: List<T>) {
        if (results.isEmpty()) return

        // Check if the graphClass is abstract or sealed (indicates polymorphic query)
        val isPolymorphic = graphClass.kotlin.isAbstract || graphClass.kotlin.isSealed

        if (isPolymorphic) {
            // Snapshot each object using its actual runtime class
            results.forEach { obj ->
                val actualClass = obj.javaClass

                val (fragmentModel, rootFragmentFieldName) = if (actualClass.isAnnotationPresent(GraphView::class.java)) {
                    val viewModel = org.drivine.model.GraphViewModel.from(actualClass)
                    val fragmentModel = FragmentModel.from(viewModel.rootFragment.fragmentType)
                    Pair(fragmentModel, viewModel.rootFragment.fieldName)
                } else {
                    Pair(FragmentModel.from(actualClass), null)
                }

                sessionManager.snapshot(obj, fragmentModel, rootFragmentFieldName)
            }
        } else {
            // Non-polymorphic: use the query target class for all results
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
     * - Deletes removed relationships according to cascade policy
     * - Merges added relationships and their fragments
     *
     * @param obj The object to save
     * @param cascade The cascade policy for deleted relationships (default: NONE - only delete relationship)
     * @return The saved object
     */
    fun <T : Any> save(obj: T, cascade: CascadeType = CascadeType.NONE): T {
        val graphClass = obj.javaClass

        // Build merge statements using polymorphic builder
        val mergeBuilder = org.drivine.query.GraphObjectMergeBuilder.forClass(
            graphClass,
            objectMapper,
            sessionManager
        )
        val statements = mergeBuilder.buildMergeStatements(obj, cascade)

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
