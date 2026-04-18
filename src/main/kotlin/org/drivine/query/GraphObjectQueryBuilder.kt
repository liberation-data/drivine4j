package org.drivine.query

import org.drivine.annotation.NodeFragment
import org.drivine.annotation.GraphView
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.grammar.CypherGrammar

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
     * Builds a Cypher query with optional WHERE, ORDER BY, and collection sort specifications.
     *
     * Collection sorts are applied using apoc.coll.sortMaps() to sort relationship collections
     * by nested properties. This enables sorting like `assignedTo.name` or `raisedBy_worksFor.name`.
     *
     * @param whereClause Optional WHERE clause conditions (without the WHERE keyword)
     * @param orderByClause Optional ORDER BY clause (without the ORDER BY keywords)
     * @param collectionSorts List of collection sort specifications for relationship collections
     * @return The generated Cypher query
     */
    fun buildQuery(
        whereClause: String? = null,
        orderByClause: String? = null,
        collectionSorts: List<CollectionSortSpec> = emptyList(),
        externalPrologs: List<String> = emptyList(),
        externalBridgeVars: List<String> = emptyList(),
    ): String = buildQuery(whereClause, orderByClause)

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
    fun buildDeleteQuery(
        whereClause: String? = null,
        prologs: List<String> = emptyList(),
        bridgeVariables: List<String> = emptyList(),
    ): String

    /**
     * Returns the node alias used in queries.
     * For fragments this is "n", for views this is the root fragment field name.
     */
    val nodeAlias: String

    companion object {
        fun forClass(graphClass: Class<*>, grammar: CypherGrammar): GraphObjectQueryBuilder {
            return if (graphClass.isAnnotationPresent(GraphView::class.java)) {
                GraphViewQueryBuilder.forView(graphClass, grammar)
            } else if (graphClass.isAnnotationPresent(NodeFragment::class.java)) {
                FragmentQueryBuilder.forFragment(graphClass)
            } else {
                throw IllegalArgumentException("Class ${graphClass.name} must be annotated with @GraphView or @GraphFragment")
            }
        }

        fun forClass(graphClass: kotlin.reflect.KClass<*>, grammar: CypherGrammar): GraphObjectQueryBuilder {
            return forClass(graphClass.java, grammar)
        }
    }
}
