package org.drivine.query

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel

/**
 * Builds Cypher MERGE statements for GraphFragment classes.
 *
 * Generates queries that:
 * 1. MERGE on (labels + ID) - creates if not exists, matches if exists
 * 2. SET specified fields (dirty fields for optimized saves, all fields for full saves)
 */
class FragmentMergeBuilder(
    private val fragmentModel: FragmentModel,
    private val objectMapper: ObjectMapper
) {

    /**
     * Builds a MERGE statement for saving a fragment.
     *
     * @param obj The object to save
     * @param dirtyFields The fields that have changed (null means save all fields)
     * @return A MergeStatement containing the query and bindings
     */
    fun <T : Any> buildMergeStatement(obj: T, dirtyFields: Set<String>?): MergeStatement {
        val nodeIdField = fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("Cannot build MERGE for fragment without @GraphNodeId field: ${fragmentModel.className}")

        // Extract all properties from the object
        val allProps = objectMapper.toMap(obj)
        val idValue = allProps[nodeIdField]
            ?: throw IllegalArgumentException("Cannot build MERGE for fragment with null ID: ${fragmentModel.className}")

        // Build the MERGE clause
        val labels = fragmentModel.labels.joinToString(":")
        val mergeClause = "MERGE (n:$labels {$nodeIdField: \$$nodeIdField})"

        // Determine which fields to SET
        val fieldsToSet = if (dirtyFields != null) {
            // Optimized save - only set dirty fields (excluding ID)
            dirtyFields.filter { it != nodeIdField }
        } else {
            // Full save - set all fields (excluding ID)
            fragmentModel.fields.map { it.name }.filter { it != nodeIdField }
        }

        if (fieldsToSet.isEmpty()) {
            // No fields to set - just MERGE (ensures node exists)
            return MergeStatement(
                statement = mergeClause,
                bindings = mapOf(nodeIdField to idValue)
            )
        }

        // Build SET clause
        val setClause = fieldsToSet.joinToString(", ") { field ->
            "n.$field = \$$field"
        }

        val query = "$mergeClause\nSET $setClause"

        // Create bindings (ID + fields to set)
        val bindings = mutableMapOf<String, Any?>(nodeIdField to idValue)
        fieldsToSet.forEach { field ->
            bindings[field] = allProps[field]
        }

        return MergeStatement(query, bindings)
    }
}

/**
 * Represents a MERGE statement with its parameter bindings.
 */
data class MergeStatement(
    val statement: String,
    val bindings: Map<String, Any?>
)