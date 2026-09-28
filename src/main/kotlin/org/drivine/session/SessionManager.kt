package org.drivine.session

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.model.FragmentModel
import java.util.Collections

/**
 * Manages session state for GraphObjectManager, tracking loaded objects
 * to enable dirty field detection and optimized saves.
 *
 * The session stores a [SnapshotDigest] of each loaded object, keyed by class and ID. When an object
 * is saved, the session compares a digest of its current state with the stored one to determine which
 * fields and relationship targets changed. A digest keeps shape and ids and hashes large values, so an
 * entry costs bytes per field rather than a copy of the object's text and embeddings.
 *
 * The session outlives transactions, so a load and a later save write only what changed. It is
 * bounded: past [maxEntries] the least recently used entry is evicted, and an evicted object is simply
 * untracked — its next save writes all fields. Safe for concurrent use.
 */
class SessionManager @JvmOverloads constructor(
    private val objectMapper: ObjectMapper,
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private val digest = SnapshotDigest(objectMapper)

    /**
     * Session storage for object digests, keyed by (class name + ID value), in access order.
     * Example key: "sample.mapped.fragment.Person:550e8400-e29b-41d4-a716-446655440000"
     */
    private val snapshots: MutableMap<String, JsonNode> = Collections.synchronizedMap(
        object : LinkedHashMap<String, JsonNode>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonNode>?) = size > maxEntries
        }
    )

    /** The number of objects currently tracked. Never exceeds [maxEntries]. */
    val size: Int
        get() = snapshots.size

    /**
     * Takes a snapshot of an object and stores it in the session.
     *
     * @param obj The object to snapshot
     * @param fragmentModel The fragment model to extract the ID field name
     * @param rootFragmentFieldName For GraphViews, the field name containing the root fragment (e.g., "issue").
     *                              For GraphFragments, this should be null.
     */
    fun <T : Any> snapshot(obj: T, fragmentModel: FragmentModel, rootFragmentFieldName: String? = null) {
        val nodeIdField = fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("Cannot snapshot object without @GraphNodeId field: ${obj.javaClass.name}")

        val jsonNode = digest.of(obj)

        // Extract the ID value from the digest (ids are kept verbatim)
        val idValue = if (rootFragmentFieldName != null) {
            // For GraphViews, navigate to the root fragment first (e.g., jsonNode.issue.uuid)
            jsonNode.get(rootFragmentFieldName)?.get(nodeIdField)?.asText()
                ?: throw IllegalArgumentException("ID field '$rootFragmentFieldName.$nodeIdField' not found in GraphView of type ${obj.javaClass.name}")
        } else {
            // For GraphFragments, ID is at the top level
            jsonNode.get(nodeIdField)?.asText()
                ?: throw IllegalArgumentException("ID field '$nodeIdField' not found in object of type ${obj.javaClass.name}")
        }

        snapshots[buildKey(obj.javaClass, idValue)] = jsonNode
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
    fun isTracked(clazz: Class<*>, idValue: Any): Boolean = snapshots.containsKey(buildKey(clazz, idValue))

    /**
     * Gets the stored digest of an object, or null if it is not tracked (never loaded, or evicted).
     * Compare it with [digestOf] of the current state, never with the object itself.
     *
     * @param clazz The class of the object
     * @param idValue The ID value of the object
     */
    fun snapshotOf(clazz: Class<*>, idValue: Any): JsonNode? = snapshots[buildKey(clazz, idValue)]

    /** Digests [obj] exactly as a snapshot of it would be, for comparison with [snapshotOf]. */
    fun digestOf(obj: Any): JsonNode = digest.of(obj)

    /**
     * Gets the dirty fields for an object by comparing current state with snapshot.
     *
     * @param obj The current object state
     * @param idValue The ID value of the object
     * @return Set of field names that have changed, or null if object is not tracked
     */
    fun <T : Any> getDirtyFields(obj: T, idValue: Any): Set<String>? {
        val snapshot = snapshotOf(obj.javaClass, idValue) ?: return null
        return diffFields(digest.of(obj), snapshot)
    }

    /**
     * Computes the dirty fields of [current] against a previous digest, without consulting the
     * session store.
     *
     * This is used for fragments that are not tracked individually in the session but whose previous
     * state is available from an enclosing GraphView's digest (e.g. a related fragment loaded as part
     * of a view).
     *
     * @param current The current object state
     * @param snapshot The previous state's digest (a sub-tree of a [snapshotOf] result)
     * @return Set of field names that have changed
     */
    fun computeDirtyFields(current: Any, snapshot: JsonNode): Set<String> = diffFields(digest.of(current), snapshot)

    /**
     * Field-by-field diff of two JSON representations.
     */
    private fun diffFields(currentNode: JsonNode, snapshotNode: JsonNode): Set<String> {
        val dirtyFields = mutableSetOf<String>()

        // Compare each field in the current state with the snapshot
        currentNode.fields().forEach { (fieldName, currentValue) ->
            val snapshotValue = snapshotNode.get(fieldName)
            if (currentValue != snapshotValue) {
                dirtyFields.add(fieldName)
            }
        }

        // Check for fields that existed in snapshot but are now missing
        snapshotNode.fields().forEach { (fieldName, _) ->
            if (!currentNode.has(fieldName)) {
                dirtyFields.add(fieldName)
            }
        }

        return dirtyFields
    }

    /**
     * Clears all snapshots from the session. Every object is untracked until it is loaded again.
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

    companion object {
        /** Generous by default: a digest is small, so 100k tracked objects is tens of megabytes. */
        const val DEFAULT_MAX_ENTRIES = 100_000

        private const val INITIAL_CAPACITY = 1024
        private const val LOAD_FACTOR = 0.75f
    }
}
