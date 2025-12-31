package org.drivine.query

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.GraphView

/**
 * Base interface for building queries for graph objects (Fragments and Views).
 */
interface GraphObjectQueryBuilder {
    /**
     * Builds a Cypher query with optional WHERE and ORDER BY clauses.
     *
     * @param whereClause Optional WHERE clause conditions (without the WHERE keyword)
     * @param orderByClause Optional ORDER BY clause (without the ORDER BY keywords)
     * @return The generated Cypher query
     */
    fun buildQuery(whereClause: String? = null, orderByClause: String? = null): String

    /**
     * Builds a WHERE clause for loading by ID.
     * Implementations know the correct alias and field name for their type.
     * This will be the integration point for a future filter DSL.
     *
     * @param idParamName The parameter name for binding (e.g., "id" becomes $id in query)
     * @return The WHERE clause condition (e.g., "n.uuid = $id" or "issue.uuid = $id")
     */
    fun buildIdWhereClause(idParamName: String = "id"): String

    /**
     * Builds a Cypher DELETE query with optional WHERE clause.
     * Uses DETACH DELETE to also remove all relationships.
     *
     * @param whereClause Optional WHERE clause conditions (without the WHERE keyword)
     * @return The generated Cypher DELETE query that returns the count of deleted nodes
     */
    fun buildDeleteQuery(whereClause: String? = null): String

    /**
     * Returns the node alias used in queries.
     * For fragments this is "n", for views this is the root fragment field name.
     */
    val nodeAlias: String

    companion object {
        /**
         * Creates the appropriate query builder for a graph object class.
         * Detects whether it's a GraphFragment or GraphView and returns the correct builder.
         */
        fun forClass(graphClass: Class<*>): GraphObjectQueryBuilder {
            return if (graphClass.isAnnotationPresent(GraphView::class.java)) {
                GraphViewQueryBuilder.forView(graphClass)
            } else if (graphClass.isAnnotationPresent(NodeFragment::class.java)) {
                FragmentQueryBuilder.forFragment(graphClass)
            } else {
                throw IllegalArgumentException("Class ${graphClass.name} must be annotated with @GraphView or @GraphFragment")
            }
        }

        /**
         * Creates the appropriate query builder for a graph object class using KClass.
         */
        fun forClass(graphClass: kotlin.reflect.KClass<*>): GraphObjectQueryBuilder {
            return forClass(graphClass.java)
        }
    }
}
