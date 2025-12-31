package org.drivine.query

import org.drivine.model.FragmentModel

/**
 * Builds Cypher queries for GraphFragment classes.
 */
class FragmentQueryBuilder(private val fragmentModel: FragmentModel) : GraphObjectQueryBuilder {

    override val nodeAlias: String = "n"

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

        // Check if this is a polymorphic query (abstract/sealed class)
        val isPolymorphic = fragmentModel.clazz.kotlin.isAbstract || fragmentModel.clazz.kotlin.isSealed

        // Include labels for polymorphic deserialization support
        val returnClause = if (isPolymorphic) {
            // For polymorphic types, include all properties using .*
            """

WITH properties($nodeAlias) AS props, labels($nodeAlias) AS lbls
RETURN props {
    .*,
    labels: lbls
} AS result"""
        } else {
            // For concrete types, list specific fields
            val fieldMappings = fragmentModel.fields.joinToString(",\n    ") {
                "${it.name}: $nodeAlias.${it.name}"
            }
            """

RETURN {
    $fieldMappings,
    labels: labels($nodeAlias)
} AS result"""
        }

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

    override fun buildDeleteQuery(whereClause: String?): String {
        if (fragmentModel.labels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for fragment ${fragmentModel.className}. @GraphFragment must specify at least one label.")
        }

        val labelString = fragmentModel.labels.joinToString(":")
        val matchClause = "MATCH ($nodeAlias:$labelString)"

        val whereSection = if (whereClause != null) {
            "\nWHERE $whereClause"
        } else {
            ""
        }

        return """
            |$matchClause$whereSection
            |DETACH DELETE $nodeAlias
            |RETURN count(*) AS deleted
        """.trimMargin()
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