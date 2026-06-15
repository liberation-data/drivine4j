package org.drivine.query

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel

/**
 * Builds Cypher MERGE statements for GraphFragment classes.
 *
 * Generates queries that:
 * 1. MERGE on (labels + ID) - creates if not exists, matches if exists
 * 2. SET declared fields (dirty fields for optimized saves, all fields for full saves)
 * 3. Expand each `@PropertyBag` field into flat prefixed properties, and REMOVE keys that the bag
 *    no longer contains (clear-stale-then-set) when the previous state is known.
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
     * @param previousObject The prior state of [obj] (from the session snapshot), used to clear stale
     *   `@PropertyBag` keys on update. Null when the object is not session-tracked — then current bag
     *   entries are written but orphaned keys from a previous detached save are not removed.
     * @return A MergeStatement containing the query and bindings
     */
    fun <T : Any> buildMergeStatement(obj: T, dirtyFields: Set<String>?, previousObject: Any? = null): MergeStatement {
        val nodeIdField = fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("Cannot build MERGE for fragment without @GraphNodeId field: ${fragmentModel.className}")

        // Extract all properties from the object (Jackson; bag fields arrive as nested maps)
        val allProps = objectMapper.toMap(obj)
        val idValue = allProps[nodeIdField]
            ?: throw IllegalArgumentException("Cannot build MERGE for fragment with null ID: ${fragmentModel.className}")

        val labels = fragmentModel.labels.joinToString(":")
        val mergeClause = "MERGE (n:$labels {$nodeIdField: \$$nodeIdField})"

        val bindings = mutableMapOf<String, Any?>(nodeIdField to idValue)
        val setClauses = mutableListOf<String>()
        val removeClauses = mutableListOf<String>()

        // ----- Declared fields (bags are excluded from fragmentModel.fields) -----
        val declaredNames = fragmentModel.fields.map { it.name }.toSet()
        val fieldsToSet = if (dirtyFields != null) {
            dirtyFields.filter { it != nodeIdField && it in declaredNames }
        } else {
            fragmentModel.fields.map { it.name }.filter { it != nodeIdField }
        }
        fieldsToSet.forEach { field ->
            setClauses.add("n.$field = \$$field")
            bindings[field] = allProps[field]
        }

        // ----- Property bags: expand to prefixed properties + clear stale keys -----
        val previousProps = previousObject?.let { objectMapper.toMap(it) }
        var bagParamIndex = 0
        fragmentModel.propertyBags.forEach { bag ->
            // On an optimized save, only touch the bag if it changed.
            if (dirtyFields != null && bag.fieldName !in dirtyFields) return@forEach

            val currentBag = (allProps[bag.fieldName] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            val currentKeys = mutableSetOf<String>()
            currentBag.forEach { (k, v) ->
                val key = k.toString()
                currentKeys.add(key)
                assertStorable(v, bag.storedKey(key))
                val param = "_bag${bagParamIndex++}"
                bindings[param] = v
                setClauses.add("n.`${bag.storedKey(key)}` = \$$param")
            }

            // Remove keys present before but gone now (requires the previous state).
            val prevBag = previousProps?.get(bag.fieldName) as? Map<*, *>
            prevBag?.keys?.map { it.toString() }?.filter { it !in currentKeys }?.forEach { staleKey ->
                removeClauses.add("n.`${bag.storedKey(staleKey)}`")
            }
        }

        // ----- Assemble -----
        val query = buildString {
            append(mergeClause)
            if (setClauses.isNotEmpty()) append("\nSET ").append(setClauses.joinToString(", "))
            if (removeClauses.isNotEmpty()) append("\nREMOVE ").append(removeClauses.joinToString(", "))
        }
        return MergeStatement(query, bindings)
    }

    /** Throws a clear error if a bag value can't be stored as a node property (naming the key). */
    private fun assertStorable(value: Any?, storedKey: String) {
        if (isStorable(value)) return
        throw IllegalArgumentException(
            "@PropertyBag value for key '$storedKey' is not a storable node property " +
                "(${value?.let { it::class.simpleName } ?: "null"}). A bag value must be a String, Number, " +
                "Boolean, temporal, or a homogeneous array/list of those — not a nested map or object."
        )
    }

    private fun isStorable(value: Any?): Boolean = when (value) {
        null -> true // SET n.key = null clears the property; treated as not-stored
        is Map<*, *> -> false
        is Collection<*> -> value.all { isStorableScalar(it) }
        is Array<*> -> value.all { isStorableScalar(it) }
        else -> isStorableScalar(value)
    }

    private fun isStorableScalar(value: Any?): Boolean = when (value) {
        null -> false
        is String, is Number, is Boolean, is Char -> true
        else -> {
            val pkg = value::class.java.`package`?.name ?: ""
            pkg.startsWith("java.time") || value is java.util.Date
        }
    }
}

/**
 * Represents a MERGE statement with its parameter bindings.
 */
data class MergeStatement(
    val statement: String,
    val bindings: Map<String, Any?>
)