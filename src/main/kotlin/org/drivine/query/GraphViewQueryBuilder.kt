package org.drivine.query

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel

/**
 * Builds Cypher queries for GraphView classes.
 */
class GraphViewQueryBuilder(private val viewModel: GraphViewModel) : GraphObjectQueryBuilder {

    /**
     * Builds a Cypher query to load a GraphView with its root fragment and relationships.
     *
     * The query structure:
     * 1. MATCH the root fragment node with optional WHERE clause
     * 2. WITH the root node, collect relationships using pattern comprehension
     * 3. RETURN the assembled object
     *
     * @param whereClause Optional WHERE clause conditions (without the WHERE keyword)
     * @return The generated Cypher query
     */
    override fun buildQuery(whereClause: String?): String {
        val rootFragmentModel = viewModel.rootFragment
        val rootFieldName = rootFragmentModel.fieldName

        // Get all labels from the fragment
        val fragmentLabels = getFragmentLabels(rootFragmentModel.fragmentType)
        if (fragmentLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for root fragment ${rootFragmentModel.fragmentType.name}. @GraphFragment must specify at least one label.")
        }

        // Build the MATCH clause with all labels
        val labelString = fragmentLabels.joinToString(":")
        val matchClause = "MATCH ($rootFieldName:$labelString)"

        // Build the WHERE clause if provided
        val whereSection = if (whereClause != null) {
            "\nWHERE $whereClause"
        } else {
            ""
        }

        // Build the WITH clause with all projections
        val withSections = mutableListOf<String>()

        // Add root fragment projection
        val rootFragmentFields = getFragmentFields(rootFragmentModel.fragmentType)
        val rootComment = "// ${capitalize(rootFieldName)}"
        val rootProjection = buildFragmentProjectionWithMapping(rootFieldName, rootFieldName, rootFragmentFields)
        withSections.add("    $rootComment\n    $rootProjection AS $rootFieldName")

        // Add relationship projections
        viewModel.relationships.forEach { rel ->
            val comment = buildRelationshipComment(rel)
            val targetAlias = rel.deriveTargetAlias()
            val projection = buildRelationshipPattern(rootFieldName, rel, targetAlias)
            withSections.add("    $comment\n    $projection")
        }

        val withClause = "\n\nWITH\n" + withSections.joinToString(",\n\n")

        // Build the RETURN clause
        val returnFields = mutableListOf<String>()
        returnFields.add("    ${rootFieldName}: $rootFieldName")
        viewModel.relationships.forEach { rel ->
            val targetAlias = rel.deriveTargetAlias()
            returnFields.add("    ${rel.fieldName}: $targetAlias")
        }

        val returnClause = """

RETURN {
${returnFields.joinToString(",\n")}
} AS result"""

        return matchClause + whereSection + withClause + returnClause
    }

    /**
     * Builds a comment describing a relationship.
     */
    private fun buildRelationshipComment(rel: org.drivine.model.RelationshipModel): String {
        val cardinality = if (rel.isCollection) "0 or many" else "0 or 1"
        val typeDesc = rel.elementType.simpleName
        return "// ${rel.fieldName} ($cardinality $typeDesc)"
    }

    /**
     * Capitalizes the first letter of a string.
     */
    private fun capitalize(str: String): String {
        return str.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * Builds a field projection with explicit mapping like:
     * issue {uuid: issue.uuid, id: issue.id, state: issue.state}
     */
    private fun buildFragmentProjectionWithMapping(varName: String, sourceVar: String, fields: List<String>): String {
        if (fields.isEmpty()) {
            return varName
        }
        val fieldMappings = fields.joinToString(",\n        ") { "$it: $sourceVar.$it" }
        return """$varName {
        $fieldMappings
    }"""
    }

    /**
     * Gets field names from a FragmentModel.
     */
    private fun getFragmentFields(fragmentType: Class<*>): List<String> {
        return try {
            val fragmentModel = FragmentModel.from(fragmentType)
            fragmentModel.fields.map { it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Builds a relationship pattern comprehension for a single relationship.
     *
     * Example output:
     * [(issue)-[:ASSIGNED_TO]->(assigned:Person) |
     *     assigned {
     *         uuid: assigned.uuid,
     *         name: assigned.name,
     *         bio: assigned.bio
     *     }
     * ] AS assigned
     *
     * For nested GraphViews, recursively builds the projection with nested relationships.
     *
     * @param rootFieldName The alias for the root node
     * @param rel The relationship model
     * @param targetAlias The derived alias for the relationship target
     */
    private fun buildRelationshipPattern(rootFieldName: String, rel: org.drivine.model.RelationshipModel, targetAlias: String): String {
        val direction = when (rel.direction) {
            Direction.OUTGOING -> "-[:${rel.type}]->"
            Direction.INCOMING -> "<-[:${rel.type}]-"
            Direction.UNDIRECTED -> "-[:${rel.type}]-"
        }

        // Get all labels for the target type
        val targetLabels = getLabelsForType(rel.elementType)
        if (targetLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for relationship target ${rel.elementType.name}. @GraphFragment or @GraphView must specify at least one label.")
        }
        val targetLabelString = targetLabels.joinToString(":")

        // Build the projection for this relationship
        val projection = buildRelationshipProjection(targetAlias, rel.elementType)

        val pattern = if (rel.isCollection) {
            // Collection: use pattern comprehension
            "[($rootFieldName)${direction}($targetAlias:$targetLabelString) |\n        $projection\n    ] AS $targetAlias"
        } else {
            // Single: use [pattern][0] to get the first element
            "[($rootFieldName)${direction}($targetAlias:$targetLabelString) |\n        $projection\n    ][0] AS $targetAlias"
        }

        return pattern
    }

    /**
     * Builds the projection for a relationship target.
     * If it's a GraphFragment, returns the fields projection.
     * If it's a GraphView, recursively builds nested structure.
     */
    private fun buildRelationshipProjection(varName: String, targetType: Class<*>): String {
        // Check if it's a GraphView (nested)
        val viewAnnotation = targetType.getAnnotation(GraphView::class.java)
        if (viewAnnotation != null) {
            // Recursively handle nested GraphView
            val nestedViewModel = GraphViewModel.from(targetType)
            return buildNestedViewProjection(varName, nestedViewModel)
        }

        // It's a GraphFragment, just project its fields with explicit mapping
        val fields = getFragmentFields(targetType)
        val fieldMappings = fields.joinToString(",\n            ") { "$it: $varName.$it" }
        return """$varName {
            $fieldMappings
        }"""
    }

    /**
     * Builds a nested projection for a GraphView within a relationship.
     * Example:
     * raisedBy {
     *     person: {
     *         uuid: raisedBy.uuid,
     *         name: raisedBy.name,
     *         bio: raisedBy.bio
     *     },
     *     worksFor: [
     *         (raisedBy)-[:WORKS_FOR]->(worksFor:Organization) |
     *         worksFor {
     *             uuid: worksFor.uuid,
     *             name: worksFor.name
     *         }
     *     ]
     * }
     */
    private fun buildNestedViewProjection(varName: String, nestedViewModel: GraphViewModel): String {
        val fields = mutableListOf<String>()

        // Add the root fragment as a nested object (not flattened)
        val rootFragmentFieldName = nestedViewModel.rootFragment.fieldName
        val rootFragmentFields = getFragmentFields(nestedViewModel.rootFragment.fragmentType)
        val rootFieldMappings = rootFragmentFields.joinToString(",\n                ") {
            "$it: $varName.$it"
        }
        fields.add("$rootFragmentFieldName: {\n                $rootFieldMappings\n            }")

        // Add nested relationship fields
        nestedViewModel.relationships.forEach { nestedRel ->
            val nestedDirection = when (nestedRel.direction) {
                Direction.OUTGOING -> "-[:${nestedRel.type}]->"
                Direction.INCOMING -> "<-[:${nestedRel.type}]-"
                Direction.UNDIRECTED -> "-[:${nestedRel.type}]-"
            }

            val nestedTargetLabels = getLabelsForType(nestedRel.elementType)
            if (nestedTargetLabels.isEmpty()) {
                throw IllegalArgumentException("No labels defined for nested relationship target ${nestedRel.elementType.name}. @GraphFragment or @GraphView must specify at least one label.")
            }
            val nestedTargetLabelString = nestedTargetLabels.joinToString(":")

            // Derive the target alias for the nested relationship
            val nestedTargetAlias = nestedRel.deriveTargetAlias()

            // Get fields for the nested target
            val nestedTargetFields = getFragmentFields(nestedRel.elementType)
            val nestedFieldMappings = nestedTargetFields.joinToString(",\n                    ") {
                "$it: ${nestedTargetAlias}.$it"
            }

            val nestedPattern = """[
                ($varName)${nestedDirection}(${nestedTargetAlias}:$nestedTargetLabelString) |
                ${nestedTargetAlias} {
                    $nestedFieldMappings
                }
            ]"""

            fields.add("\n            ${nestedRel.fieldName}: $nestedPattern")
        }

        return """$varName {
            ${fields.joinToString(",\n            ")}
        }"""
    }

    /**
     * Gets labels from a fragment class type.
     */
    private fun getFragmentLabels(fragmentType: Class<*>): List<String> {
        val annotation = fragmentType.getAnnotation(org.drivine.annotation.GraphFragment::class.java)
        return annotation?.labels?.toList() ?: emptyList()
    }

    /**
     * Gets labels for a type (could be GraphFragment or GraphView).
     */
    private fun getLabelsForType(type: Class<*>): List<String> {
        // Check if it's a GraphFragment
        val fragmentAnnotation = type.getAnnotation(org.drivine.annotation.GraphFragment::class.java)
        if (fragmentAnnotation != null) {
            return fragmentAnnotation.labels.toList()
        }

        // Check if it's a GraphView (use the root fragment's labels)
        val viewAnnotation = type.getAnnotation(org.drivine.annotation.GraphView::class.java)
        if (viewAnnotation != null) {
            val viewModel = GraphViewModel.from(type)
            return getFragmentLabels(viewModel.rootFragment.fragmentType)
        }

        return emptyList()
    }

    companion object {
        /**
         * Creates a query builder for a GraphView class.
         */
        fun forView(viewClass: Class<*>): GraphViewQueryBuilder {
            val viewModel = GraphViewModel.from(viewClass)
            return GraphViewQueryBuilder(viewModel)
        }

        /**
         * Creates a query builder for a GraphView class using KClass.
         */
        fun forView(viewClass: kotlin.reflect.KClass<*>): GraphViewQueryBuilder {
            return forView(viewClass.java)
        }
    }
}
