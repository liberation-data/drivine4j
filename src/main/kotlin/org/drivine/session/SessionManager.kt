package org.drivine.session

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.model.FragmentModel

/**
 * Manages session state for GraphObjectManager, tracking loaded objects
 * to enable dirty field detection and optimized saves.
 *
 * The session stores JSON snapshots of loaded objects, keyed by their ID.
 * When an object is saved, the session compares the current state with the
 * snapshot to determine which fields have changed.
 */
class SessionManager(
    private val objectMapper: ObjectMapper
) {

    /**
     * Session storage for object snapshots, keyed by (class name + ID value).
     * Example key: "sample.mapped.fragment.Person:550e8400-e29b-41d4-a716-446655440000"
     */
    private val snapshots: MutableMap<String, JsonNode> = mutableMapOf()

    /**
     * Takes a snapshot of an object and stores it in the session.
     * The entire object is serialized to JSON for later comparison.
     *
     * @param obj The object to snapshot
     * @param fragmentModel The fragment model to extract the ID field name
     * @param rootFragmentFieldName For GraphViews, the field name containing the root fragment (e.g., "issue").
     *                              For GraphFragments, this should be null.
     */
    fun <T : Any> snapshot(obj: T, fragmentModel: FragmentModel, rootFragmentFieldName: String? = null) {
        val nodeIdField = fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("Cannot snapshot object without @GraphNodeId field: ${obj.javaClass.name}")

        // Serialize the object to JSON
        val jsonNode = objectMapper.valueToTree<JsonNode>(obj)

        // Extract the ID value from the JSON
        val idValue = if (rootFragmentFieldName != null) {
            // For GraphViews, navigate to the root fragment first (e.g., jsonNode.issue.uuid)
            jsonNode.get(rootFragmentFieldName)?.get(nodeIdField)?.asText()
                ?: throw IllegalArgumentException("ID field '$rootFragmentFieldName.$nodeIdField' not found in GraphView of type ${obj.javaClass.name}")
        } else {
            // For GraphFragments, ID is at the top level
            jsonNode.get(nodeIdField)?.asText()
                ?: throw IllegalArgumentException("ID field '$nodeIdField' not found in object of type ${obj.javaClass.name}")
        }

        val key = buildKey(obj.javaClass, idValue)
        snapshots[key] = jsonNode
    }

    /**
     * Takes snapshots of multiple objects.
     *
     * @param objects The objects to snapshot
     * @param fragmentModel The fragment model to extract the ID field
     * @param rootFragmentFieldName For GraphViews, the field name containing the root fragment.
     *                              For GraphFragments, this should be null.
     */
    fun <T : Any> snapshotAll(objects: List<T>, fragmentModel: FragmentModel, rootFragmentFieldName: String? = null) {
        objects.forEach { obj ->
            snapshot(obj, fragmentModel, rootFragmentFieldName)
        }
    }

    /**
     * Checks if an object is tracked in this session.
     *
     * @param clazz The class of the object
     * @param idValue The ID value of the object
     * @return true if the object has a snapshot in this session
     */
    fun isTracked(clazz: Class<*>, idValue: Any): Boolean {
        val key = buildKey(clazz, idValue)
        return snapshots.containsKey(key)
    }

    /**
     * Gets the snapshot of an object from the session.
     *
     * @param clazz The class of the object
     * @param idValue The ID value of the object
     * @return The snapshot object, or null if not tracked
     */
    fun <T : Any> getSnapshot(clazz: Class<T>, idValue: Any): T? {
        val key = buildKey(clazz, idValue)
        val snapshotNode = snapshots[key] ?: return null
        return objectMapper.treeToValue(snapshotNode, clazz)
    }

    /**
     * Gets the dirty fields for an object by comparing current state with snapshot.
     *
     * @param obj The current object state
     * @param idValue The ID value of the object
     * @return Set of field names that have changed, or null if object is not tracked
     */
    fun <T : Any> getDirtyFields(obj: T, idValue: Any): Set<String>? {
        val key = buildKey(obj.javaClass, idValue)
        val snapshot = snapshots[key] ?: return null

        val currentNode = objectMapper.valueToTree<JsonNode>(obj)
        val dirtyFields = mutableSetOf<String>()

        // Compare each field in the current state with the snapshot
        currentNode.fields().forEach { (fieldName, currentValue) ->
            val snapshotValue = snapshot.get(fieldName)
            if (currentValue != snapshotValue) {
                dirtyFields.add(fieldName)
            }
        }

        // Check for fields that existed in snapshot but are now missing
        snapshot.fields().forEach { (fieldName, _) ->
            if (!currentNode.has(fieldName)) {
                dirtyFields.add(fieldName)
            }
        }

        return dirtyFields
    }

    /**
     * Clears all snapshots from the session.
     * Typically called at transaction boundaries.
     */
    fun clear() {
        snapshots.clear()
    }

    /**
     * Builds a session key from class name and ID value.
     */
    private fun buildKey(clazz: Class<*>, idValue: Any): String {
        return "${clazz.name}:$idValue"
    }

    /**
     * Extracts the ID value from an object using its FragmentModel.
     *
     * @param obj The object
     * @param fragmentModel The fragment model describing the object's structure
     * @return The ID value, or null if no @GraphNodeId field is defined
     */
    fun extractIdValue(obj: Any, fragmentModel: FragmentModel): Any? {
        val nodeIdField = fragmentModel.nodeIdField ?: return null

        // Use reflection to get the field value
        val field = obj.javaClass.declaredFields.find { it.name == nodeIdField }
            ?: obj.javaClass.fields.find { it.name == nodeIdField }
            ?: return null

        field.isAccessible = true
        return field.get(obj)
    }
}
