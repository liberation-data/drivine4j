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
     * Loads instances of a graph object with a simple WHERE clause filter.
     * This is a Java-friendly alternative to the DSL-based loadAll method.
     *
     * Example:
     * ```java
     * // Filter by root fragment property
     * graphObjectManager.loadAll(PersonContext.class, "person.name = 'Alice'");
     *
     * // Filter by relationship property
     * graphObjectManager.loadAll(PersonContext.class, "worksFor.name = 'Acme Corp'");
     *
     * // Multiple conditions with AND
     * graphObjectManager.loadAll(PersonContext.class, "person.name = 'Alice' AND person.bio IS NOT NULL");
     * ```
     *
     * @param graphClass The graph object class to load
     * @param whereClause Cypher WHERE clause conditions (without the WHERE keyword)
     * @return List of graph object instances matching the criteria
     */
    fun <T : Any> loadAll(graphClass: Class<T>, whereClause: String): List<T> {
        // Auto-register subtypes if this is a sealed/abstract class
        autoRegisterSubtypesIfNeeded(graphClass)

        val builder = GraphObjectQueryBuilder.forClass(graphClass)
        val query = builder.buildQuery(whereClause, null)

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
     *
     * For label-based polymorphism, registers using:
     * 1. Composite key (all labels sorted and comma-joined) - most specific match
     * 2. Individual distinct labels - fallback matching
     * 3. Simple class name - final fallback
     *
     * For GraphViews, also registers subtypes for relationship target types.
     */
    private fun autoRegisterSubtypesIfNeeded(graphClass: Class<*>) {
        // Track registered classes to avoid infinite recursion
        val registeredClasses = mutableSetOf<Class<*>>()
        autoRegisterSubtypesRecursive(graphClass, registeredClasses)
    }

    private fun autoRegisterSubtypesRecursive(graphClass: Class<*>, registeredClasses: MutableSet<Class<*>>) {
        if (!registeredClasses.add(graphClass)) {
            return // Already processed this class
        }

        val kotlinClass = graphClass.kotlin

        // First, check for Jackson's @JsonSubTypes annotation (works for both Java and Kotlin)
        val jsonSubTypes = graphClass.getAnnotation(com.fasterxml.jackson.annotation.JsonSubTypes::class.java)
        if (jsonSubTypes != null) {
            jsonSubTypes.value.forEach { subType ->
                persistenceManager.registerSubtype(graphClass, subType.name, subType.value.java)
            }
            // Don't return - still need to check relationships for GraphViews
        } else if (kotlinClass.isSealed) {
            // Register sealed class subtypes
            kotlinClass.sealedSubclasses.forEach { subclass ->
                val subclassJava = subclass.java

                // Extract labels from @NodeFragment annotation to use as discriminators
                val nodeFragment = subclassJava.getAnnotation(org.drivine.annotation.NodeFragment::class.java)
                if (nodeFragment != null) {
                    val subLabels = nodeFragment.labels.toList()

                    // Register using composite key (all labels sorted and joined)
                    // This enables matching nodes with multiple labels like ["WebUser", "Anonymous"]
                    if (subLabels.isNotEmpty()) {
                        val compositeKey = org.drivine.mapper.SubtypeRegistry.labelsToKey(subLabels)
                        persistenceManager.registerSubtype(graphClass, compositeKey, subclassJava)
                    }

                    // Also register using each distinct label (labels not in base class)
                    val baseFragment = graphClass.getAnnotation(org.drivine.annotation.NodeFragment::class.java)
                    val baseLabels = baseFragment?.labels?.toSet() ?: emptySet()
                    val distinctLabels = subLabels.toSet() - baseLabels

                    distinctLabels.forEach { label ->
                        persistenceManager.registerSubtype(graphClass, label, subclassJava)
                    }
                }

                // Also register using simple class name as fallback
                persistenceManager.registerSubtype(graphClass, subclass.simpleName ?: subclassJava.simpleName, subclassJava)
            }
        }

        // For GraphViews, also register subtypes for relationship target types
        if (graphClass.isAnnotationPresent(GraphView::class.java)) {
            val viewModel = org.drivine.model.GraphViewModel.from(graphClass)
            viewModel.relationships.forEach { rel ->
                autoRegisterSubtypesRecursive(rel.elementType, registeredClasses)
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
     * Deletes a graph object (GraphView or GraphFragment) from the database by its ID.
     * Uses DETACH DELETE to also remove all relationships.
     *
     * @param id The ID value of the object to delete
     * @param graphClass The graph object class
     * @return The number of nodes deleted (0 or 1)
     */
    fun <T : Any> delete(id: String, graphClass: Class<T>): Int {
        return delete(id, graphClass, null)
    }

    /**
     * Deletes a graph object (GraphView or GraphFragment) from the database by its ID,
     * with an additional WHERE clause filter.
     *
     * Example:
     * ```kotlin
     * // Delete only if state is 'closed'
     * graphObjectManager.delete(issueUuid, IssueCore::class.java, "issue.state = 'closed'")
     * ```
     *
     * @param id The ID value of the object to delete
     * @param graphClass The graph object class
     * @param whereClause Additional WHERE clause conditions (without WHERE keyword)
     * @return The number of nodes deleted (0 or 1)
     */
    fun <T : Any> delete(id: String, graphClass: Class<T>, whereClause: String?): Int {
        val builder = GraphObjectQueryBuilder.forClass(graphClass)
        val idCondition = builder.buildIdWhereClause("id")

        val fullWhereClause = if (whereClause != null) {
            "$idCondition AND $whereClause"
        } else {
            idCondition
        }

        val query = builder.buildDeleteQuery(fullWhereClause)

        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(query)
                .bind(mapOf("id" to id))
                .transform(Int::class.java)
        )
    }

    /**
     * Deletes all graph objects (GraphViews or GraphFragments) of a given type from the database.
     * Uses DETACH DELETE to also remove all relationships.
     *
     * WARNING: This will delete ALL nodes matching the labels. Use with caution.
     *
     * @param graphClass The graph object class
     * @return The number of nodes deleted
     */
    fun <T : Any> deleteAll(graphClass: Class<T>): Int {
        return deleteAll(graphClass, null as String?)
    }

    /**
     * Deletes graph objects (GraphViews or GraphFragments) matching a WHERE clause filter.
     * Uses DETACH DELETE to also remove all relationships.
     *
     * Example:
     * ```kotlin
     * // Delete all closed issues
     * graphObjectManager.deleteAll(IssueCore::class.java, "n.state = 'closed'")
     *
     * // For GraphViews, use the root fragment field name as alias
     * graphObjectManager.deleteAll(RaisedAndAssignedIssue::class.java, "issue.state = 'closed'")
     * ```
     *
     * @param graphClass The graph object class
     * @param whereClause WHERE clause conditions (without WHERE keyword)
     * @return The number of nodes deleted
     */
    fun <T : Any> deleteAll(graphClass: Class<T>, whereClause: String?): Int {
        val builder = GraphObjectQueryBuilder.forClass(graphClass)
        val query = builder.buildDeleteQuery(whereClause)

        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(query)
                .transform(Int::class.java)
        )
    }

    /**
     * Deletes graph objects using a type-safe query DSL.
     * Supports filtering conditions.
     *
     * Example:
     * ```kotlin
     * graphObjectManager.deleteAll(
     *     IssueCore::class.java,
     *     IssueCoreQueryDsl.INSTANCE
     * ) {
     *     where {
     *         this(query.state eq "closed")
     *         this(query.locked eq true)
     *     }
     * }
     * ```
     *
     * @param graphClass The graph object class to delete
     * @param queryObject The query object providing property references
     * @param spec DSL block for building the query
     * @return The number of nodes deleted
     */
    fun <T : Any, Q : Any> deleteAll(
        graphClass: Class<T>,
        queryObject: Q,
        spec: org.drivine.query.dsl.GraphQuerySpec<Q>.() -> Unit
    ): Int {
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

        val query = builder.buildDeleteQuery(whereClause)

        // Extract bindings from conditions
        val bindings = org.drivine.query.dsl.CypherGenerator.extractBindings(querySpec.conditions, viewModel)

        return persistenceManager.getOne(
            QuerySpecification
                .withStatement(query)
                .bind(bindings)
                .transform(Int::class.java)
        )
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
