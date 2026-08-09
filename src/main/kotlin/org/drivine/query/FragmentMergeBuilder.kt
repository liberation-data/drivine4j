package org.drivine.query

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.manager.NullPolicy
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.drivine.query.grammar.CypherGrammar

/**
 * Builds Cypher MERGE statements for GraphFragment classes.
 *
 * Generates queries that:
 * 1. MERGE on (labels + ID) - creates if not exists, matches if exists
 * 2. SET declared fields (dirty fields for optimized saves, all fields for full saves)
 * 3. Write each `@VectorIndex` (embedding) field through the grammar's
 *    [CypherGrammar.vectorPropertyLiteral], so FalkorDB stores it as its native vector type (the
 *    write-side mirror of the read-side `vecf32(...)` wrapping) — a no-op on Neo4j / Memgraph.
 * 4. Expand each `@PropertyBag` field into flat prefixed properties, and REMOVE keys that the bag
 *    no longer contains (clear-stale-then-set) when the previous state is known.
 *
 * [grammar] is optional; when null (e.g. in unit tests that only assert plain SET shape) vector
 * fields are written plainly, exactly as any other field.
 */
class FragmentMergeBuilder(
    private val fragmentModel: FragmentModel,
    private val objectMapper: ObjectMapper,
    private val grammar: CypherGrammar? = null,
) {

    /**
     * Builds a MERGE statement for saving a fragment.
     *
     * @param obj The object to save
     * @param dirtyFields The fields that have changed (null means save all fields)
     * @param previousObject The prior state of [obj] (from the session snapshot), used to clear stale
     *   `@PropertyBag` keys on update. Null when the object is not session-tracked — then current bag
     *   entries are written but orphaned keys from a previous detached save are not removed.
     * @param nullPolicy How null field values are treated: [NullPolicy.IGNORE] (default) skips them
     *   (merge-patch), [NullPolicy.CLEAR] writes `SET x = null` to clear them. Uniform for all fields,
     *   embeddings included — see [NullPolicy].
     * @return A MergeStatement containing the query and bindings
     */
    fun <T : Any> buildMergeStatement(
        obj: T,
        dirtyFields: Set<String>?,
        previousObject: Any? = null,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
    ): MergeStatement {
        val nodeIdField = fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("Cannot build MERGE for fragment without @GraphNodeId field: ${fragmentModel.className}")

        // Extract all properties from the object (Jackson; bag fields arrive as nested maps)
        val allProps = objectMapper.toMap(obj)
        val idValue = allProps[nodeIdField]
            ?: throw IllegalArgumentException("Cannot build MERGE for fragment with null ID: ${fragmentModel.className}")

        val labels = fragmentModel.labels.joinToString(":")
        // The MERGE key uses the id field's on-disk property name; the bind-param stays the field name.
        val nodeIdProperty = fragmentModel.nodeIdProperty ?: nodeIdField
        val mergeClause = "MERGE (n:$labels {$nodeIdProperty: \$$nodeIdField})"

        val bindings = mutableMapOf<String, Any?>(nodeIdField to idValue)
        val setClauses = mutableListOf<String>()
        val removeClauses = mutableListOf<String>()

        // ----- Declared fields (bags are excluded from fragmentModel.fields) -----
        // Null handling is driven purely by [nullPolicy] and the object — NOT by dirty-tracking — so the
        // result never depends on a possibly-stale snapshot (IGNORE always skips a null, CLEAR always
        // clears one). Dirty-tracking only optimizes away re-writes of unchanged non-null fields, which
        // is a semantics-preserving no-op. No field is special: an embedding is just another property.
        val fieldByName = fragmentModel.fields.associateBy { it.name }
        fragmentModel.fields.map { it.name }.filter { it != nodeIdField }.forEach { name ->
            val field = fieldByName.getValue(name)
            val value = allProps[name]
            if (value == null) {
                // IGNORE: leave it. CLEAR: clear it (a plain SET — we only wrap non-null values, so
                // there's no invalid vecf32(null)).
                if (nullPolicy == NullPolicy.CLEAR) {
                    setClauses.add("n.${field.propertyName} = \$$name")
                    bindings[name] = null
                }
            } else {
                // Non-null: write it, but skip an unchanged field on a tracked (dirty-diffed) save.
                // The bind-param stays the field name (identity); the assigned property is the on-disk
                // name. Vector fields wrap via the grammar so FalkorDB stores the native vector type.
                if (dirtyFields != null && name !in dirtyFields) return@forEach
                val rhs = if (name in fragmentModel.vectorFieldNames) {
                    grammar?.vectorPropertyLiteral(name) ?: "\$$name"
                } else {
                    "\$$name"
                }
                setClauses.add("n.${field.propertyName} = $rhs")
                bindings[name] = value
            }
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
                // Under IGNORE a null bag value is skipped (never clears); under CLEAR it clears the key.
                if (v == null && nullPolicy == NullPolicy.IGNORE) return@forEach
                val param = "_bag${bagParamIndex++}"
                bindings[param] = v
                setClauses.add("n.`${bag.storedKey(key)}` = \$$param")
            }

            // Remove keys present before but gone now (requires the previous state). Removal is a form of
            // clearing, so under IGNORE (merge-patch) we leave stale keys in place.
            if (nullPolicy == NullPolicy.CLEAR) {
                val prevBag = previousProps?.get(bag.fieldName) as? Map<*, *>
                prevBag?.keys?.map { it.toString() }?.filter { it !in currentKeys }?.forEach { staleKey ->
                    removeClauses.add("n.`${bag.storedKey(staleKey)}`")
                }
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