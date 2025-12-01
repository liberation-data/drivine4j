package org.drivine.query

import org.drivine.annotation.GraphFragment
import org.drivine.annotation.GraphView
import org.drivine.model.FragmentModel

/**
 * Base interface for building queries for graph objects (Fragments and Views).
 */
interface GraphObjectQueryBuilder {
    /**
     * Builds a Cypher query with an optional WHERE clause.
     *
     * @param whereClause Optional WHERE clause conditions (without the WHERE keyword)
     * @return The generated Cypher query
     */
    fun buildQuery(whereClause: String? = null): String

    companion object {
        /**
         * Creates the appropriate query builder for a graph object class.
         * Detects whether it's a GraphFragment or GraphView and returns the correct builder.
         */
        fun forClass(graphClass: Class<*>): GraphObjectQueryBuilder {
            return if (graphClass.isAnnotationPresent(GraphView::class.java)) {
                GraphViewQueryBuilder.forView(graphClass)
            } else if (graphClass.isAnnotationPresent(GraphFragment::class.java)) {
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