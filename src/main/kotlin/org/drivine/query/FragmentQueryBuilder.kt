package org.drivine.query

import org.drivine.model.FragmentModel

/**
 * Builds Cypher queries for GraphFragment classes.
 */
class FragmentQueryBuilder(private val fragmentModel: FragmentModel) : GraphObjectQueryBuilder {

    /**
     * Builds a Cypher query to load a GraphFragment.
     *
     * The query structure:
     * 1. MATCH the fragment node with its labels and optional WHERE clause
     * 2. RETURN the node properties mapped to fields
     * 3. Optional ORDER BY clause
     *
     * @param whereClause Optional WHERE clause conditions (without the WHERE keyword)
     * @param orderByClause Optional ORDER BY clause (without the ORDER BY keywords)
     * @return The generated Cypher query
     */
    override fun buildQuery(whereClause: String?, orderByClause: String?): String {
        // Get all labels from the fragment
        if (fragmentModel.labels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for fragment ${fragmentModel.className}. @GraphFragment must specify at least one label.")
        }

        // Build the MATCH clause with all labels
        val labelString = fragmentModel.labels.joinToString(":")
        val nodeAlias = "n"
        val matchClause = "MATCH ($nodeAlias:$labelString)"

        // Build the WHERE clause if provided
        val whereSection = if (whereClause != null) {
            "\nWHERE $whereClause"
        } else {
            ""
        }

        // Build field mappings
        val fieldMappings = fragmentModel.fields.joinToString(",\n    ") {
            "${it.name}: $nodeAlias.${it.name}"
        }

        val returnClause = """

RETURN {
    $fieldMappings
} AS result"""

        // Add ORDER BY clause if provided
        val orderBySection = if (orderByClause != null) {
            "\nORDER BY $orderByClause"
        } else {
            ""
        }

        return matchClause + whereSection + returnClause + orderBySection
    }

    override fun buildIdWhereClause(idParamName: String): String {
        val nodeIdField = fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("GraphFragment ${fragmentModel.className} does not have a @GraphNodeId field")
        return "n.$nodeIdField = \$$idParamName"
    }

    companion object {
        /**
         * Creates a query builder for a GraphFragment class.
         */
        fun forFragment(fragmentClass: Class<*>): FragmentQueryBuilder {
            val fragmentModel = FragmentModel.from(fragmentClass)
            return FragmentQueryBuilder(fragmentModel)
        }

        /**
         * Creates a query builder for a GraphFragment class using KClass.
         */
        fun forFragment(fragmentClass: kotlin.reflect.KClass<*>): FragmentQueryBuilder {
            return forFragment(fragmentClass.java)
        }
    }
}