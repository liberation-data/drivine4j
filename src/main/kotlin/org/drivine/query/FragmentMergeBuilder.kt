package org.drivine.query

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.manager.NullPolicy
import org.drivine.mapper.toMap
import org.drivine.model.FragmentModel
import org.drivine.model.Stamps
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
 *    no longer contains (clear-stale-then-set) — known from the previous state, or read from the
 *    store through [storedKeys] when the object is untracked.
 * 5. Write a `@NodeLabels` field as labels: its contents are added, and under [NullPolicy.CLEAR] the
 *    members of a closed set that it no longer holds are removed.
 *
 * [grammar] is optional; when null (e.g. in unit tests that only assert plain SET shape) vector
 * fields are written plainly, exactly as any other field.
 */
class FragmentMergeBuilder(
    private val fragmentModel: FragmentModel,
    private val objectMapper: ObjectMapper,
    private val grammar: CypherGrammar? = null,
    private val storedKeys: StoredPropertyKeys? = null,
    private val stamping: Stamping? = null,
    /**
     * Variables a larger statement holds while this one runs as a part of it, each followed by a
     * comma (`"_r0, _ns, "`). They are carried through this statement's own `WITH`.
     */
    private val carry: String = "",
) {

    /**
     * Builds a MERGE statement for saving a fragment.
     *
     * @param obj The object to save
     * @param dirtyFields The fields that have changed (null means save all fields)
     * @param previousObject The prior state of [obj] — a session digest ([JsonNode]) or an object —
     *   used to clear stale `@PropertyBag` keys on update. Null when the object is not session-tracked:
     *   then, under [NullPolicy.CLEAR], the stale keys are read from the store via [storedKeys] (and
     *   left in place when no [storedKeys] is given).
     * @param nullPolicy How null field values are treated: [NullPolicy.IGNORE] (default) skips them
     *   (merge-patch), [NullPolicy.CLEAR] writes `SET x = null` to clear them. Uniform for all fields,
     *   embeddings included — see [NullPolicy].
     * @param writeFields The only fields this save may touch, by field name: a field outside the set is
     *   neither written nor cleared, whatever [nullPolicy] says. Null means every field.
     * @return A MergeStatement containing the query and bindings
     */
    fun <T : Any> buildMergeStatement(
        obj: T,
        dirtyFields: Set<String>?,
        previousObject: Any? = null,
        nullPolicy: NullPolicy = NullPolicy.IGNORE,
        writeFields: Set<String>? = null,
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
        // A checked save matches the node only while it still carries the stamp the object was loaded
        // with. It changes nothing if the stamp differs or the node is gone, and then gives no row.
        val expected = fragmentModel.stampField?.takeIf { stamping?.checked == true }?.let { allProps[it] as? String }
        // The token for the node's own data is always compared; the whole stamp, and so the token for its
        // relationships too, when the save replaces a relationship list.
        val stillAsLoaded = if (stamping?.relationships == true) {
            "n.${Stamps.QUOTED} = ${'$'}${Stamps.EXPECTED_PARAM}"
        } else {
            "${Stamps.nodeTokenOf("n")} = ${'$'}${Stamps.EXPECTED_PARAM}"
        }
        val match = "(n:$labels {$nodeIdProperty: \$$nodeIdField})"
        val mergeClause = when {
            stamping == null -> "MERGE $match"
            // The node's lock is taken before the statement reads it to say whether it changes it.
            // Without the lock, a writer that changes the node at the same moment can be missed: the
            // node is then left changed, carrying a stamp that speaks for what it held before.
            expected == null -> "MERGE $match\n${Stamps.lock("n")}"
            // And before the stamp is compared. Without the lock, two writers holding the same stamp
            // could both pass the comparison before either had written.
            else -> "MATCH $match\n${Stamps.lock("n")}\nWITH n\nWHERE $stillAsLoaded"
        }

        val bindings = mutableMapOf<String, Any?>(nodeIdField to idValue)
        val setClauses = mutableListOf<String>()
        expected?.let { bindings[Stamps.EXPECTED_PARAM] = if (stamping?.relationships == true) it else Stamps.nodeToken(it) }
        // The node gets a new stamp only if this statement changes it. Each write adds the test that
        // says whether it does; the tests are evaluated before anything is set. A test that cannot
        // tell (a comparison that gives null) counts as a change.
        val offered = stamping?.let { Stamps.fresh() }
        val changeTests = mutableListOf("n.${Stamps.QUOTED} IS NULL")
        fun differs(property: String, value: String) = "NOT coalesce(n.$property = $value, false)"
        fun present(property: String) = "n.$property IS NOT NULL"
        if (offered != null) {
            setClauses.add(Stamps.restamp("n", CHANGED, "\$${Stamps.NEW_PARAM}"))
            bindings[Stamps.NEW_PARAM] = offered
        }
        val removeClauses = mutableListOf<String>()

        // ----- Declared fields (bags are excluded from fragmentModel.fields) -----
        // Null handling is driven purely by [nullPolicy] and the object — NOT by dirty-tracking — so the
        // result never depends on a possibly-stale snapshot (IGNORE always skips a null, CLEAR always
        // clears one). Dirty-tracking only optimizes away re-writes of unchanged non-null fields, which
        // is a semantics-preserving no-op. No field is special: an embedding is just another property.
        val fieldByName = fragmentModel.fields.associateBy { it.name }
        fun writable(field: String) = writeFields == null || field in writeFields
        fragmentModel.fields.filterNot { it.stamp }.map { it.name }.filter { it != nodeIdField && writable(it) }.forEach { name ->
            val field = fieldByName.getValue(name)
            val value = allProps[name]
            if (value == null) {
                // IGNORE: leave it. CLEAR: clear it (a plain SET — we only wrap non-null values, so
                // there's no invalid vecf32(null)).
                if (nullPolicy == NullPolicy.CLEAR) {
                    setClauses.add("n.${field.propertyName} = \$$name")
                    changeTests.add(present(field.propertyName))
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
                changeTests.add(differs(field.propertyName, rhs))
                bindings[name] = value
            }
        }

        // ----- Property bags: expand to prefixed properties + clear stale keys -----
        val previousState: JsonNode? = previousObject?.let { it as? JsonNode ?: objectMapper.valueToTree(it) }
        val keysInStore by lazy { storedKeys?.of(labels, nodeIdProperty, idValue) }
        var bagParamIndex = 0
        fragmentModel.propertyBags.forEach { bag ->
            // On an optimized save, only touch the bag if it changed.
            if (dirtyFields != null && bag.fieldName !in dirtyFields) return@forEach
            if (!writable(bag.fieldName)) return@forEach

            val currentBag = (allProps[bag.fieldName] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            val currentKeys = mutableSetOf<String>()
            currentBag.forEach { (k, v) ->
                val key = k.toString()
                currentKeys.add(key)
                assertStorable(v, bag.storedKey(key))
                // Under IGNORE a null bag value is skipped (never clears); under CLEAR it clears the key.
                if (v == null && nullPolicy == NullPolicy.IGNORE) return@forEach
                require(!bag.flat || bag.owns(key)) {
                    "Flat @PropertyBag field '${bag.fieldName}' on ${fragmentModel.clazz.simpleName} has an entry " +
                        "'$key', which is the property of a declared field or of another bag. Both would write " +
                        "the same node property; remove the entry or rename the field's property."
                }
                val param = "_bag${bagParamIndex++}"
                bindings[param] = v
                // A key is the caller's data: quoted, and a backtick in it escaped.
                val property = quotedIdentifier(bag.storedKey(key))
                setClauses.add("n.$property = \$$param")
                changeTests.add(if (v == null) present(property) else differs(property, "\$$param"))
            }

            // Remove keys present before but gone now (requires the previous state). Removal is a form of
            // clearing, so under IGNORE (merge-patch) we leave stale keys in place.
            if (nullPolicy == NullPolicy.CLEAR) {
                val previousKeys = if (previousState != null) {
                    previousState.get(bag.fieldName)?.fieldNames()?.asSequence()?.toList().orEmpty()
                } else {
                    keysInStore.orEmpty().filter(bag::owns).map(bag::entryKey)
                }
                previousKeys.filter { it !in currentKeys }.forEach { staleKey ->
                    removeClauses.add("n.${quotedIdentifier(bag.storedKey(staleKey))}")
                    changeTests.add(present(quotedIdentifier(bag.storedKey(staleKey))))
                }
            }
        }

        // ----- Node labels: add what the field holds; under CLEAR, drop what it no longer holds -----
        // Label names cannot be bound as parameters on every engine, so they are written into the
        // statement, quoted. Null handling follows the fields above: IGNORE adds and removes nothing.
        var addLabels = emptyList<String>()
        var dropLabels = emptyList<String>()
        // Under CLEAR the labels are written whether or not the field is dirty: the snapshot records
        // what the object last said, not what the node carries, so an unchanged field can still have
        // labels to remove.
        fragmentModel.nodeLabels?.takeIf {
            writable(it.fieldName) && (nullPolicy == NullPolicy.CLEAR || dirtyFields == null || it.fieldName in dirtyFields)
        }?.let { model ->
            val current = (allProps[model.fieldName] as? Collection<*>).orEmpty().map { it.toString() }
            current.firstOrNull { it.isBlank() }?.let {
                throw IllegalArgumentException(
                    "@NodeLabels field '${model.fieldName}' on ${fragmentModel.clazz.simpleName} holds a blank label."
                )
            }
            addLabels = current.distinct().filter { it !in fragmentModel.labels }
            val owned = model.ownedProperty
            if (owned == null) {
                if (nullPolicy == NullPolicy.CLEAR) dropLabels = model.dropped(current)
                return@let
            }
            // An open set: what it may remove is what it recorded on the node. With no reader for the
            // stored record (a relationship target), nothing is removed and the record only grows.
            val recorded = if (nullPolicy == NullPolicy.CLEAR) storedKeys?.ownedLabels(labels, nodeIdProperty, idValue, owned) else null
            if (recorded != null) {
                dropLabels = recorded.filter { it !in addLabels && it !in fragmentModel.labels }
                if (addLabels.isEmpty()) {
                    removeClauses.add("n.`$owned`")
                    changeTests.add(present("`$owned`"))
                } else {
                    setClauses.add("n.`$owned` = \$$OWNED_LABELS_PARAM")
                    changeTests.add(differs("`$owned`", "\$$OWNED_LABELS_PARAM"))
                    bindings[OWNED_LABELS_PARAM] = addLabels
                }
            } else if (addLabels.isNotEmpty()) {
                setClauses.add(
                    "n.`$owned` = [l IN coalesce(n.`$owned`, []) WHERE NOT l IN \$$OWNED_LABELS_PARAM] + \$$OWNED_LABELS_PARAM"
                )
                changeTests.add("NOT all(l IN \$$OWNED_LABELS_PARAM WHERE l IN coalesce(n.`$owned`, []))")
                bindings[OWNED_LABELS_PARAM] = addLabels
            }
        }
        addLabels.forEach { changeTests.add("NOT ${stringLiteral(it)} IN labels(n)") }
        dropLabels.forEach { changeTests.add("${stringLiteral(it)} IN labels(n)") }

        // ----- Assemble -----
        val query = buildString {
            append(mergeClause)
            // The stamp the node is found with is held too, for a statement this one is a part of.
            if (offered != null) {
                append("\nWITH ${carry}n, coalesce(n.${Stamps.QUOTED}, '') AS ${Stamps.FOUND}, (")
                    .append(changeTests.joinToString(" OR ")).append(") AS $CHANGED")
            }
            if (setClauses.isNotEmpty()) append("\nSET ").append(setClauses.joinToString(", "))
            if (removeClauses.isNotEmpty()) append("\nREMOVE ").append(removeClauses.joinToString(", "))
            if (addLabels.isNotEmpty()) append("\nSET n").append(labelExpression(addLabels))
            if (dropLabels.isNotEmpty()) append("\nREMOVE n").append(labelExpression(dropLabels))
        }
        val carried = fragmentModel.stampField?.let { allProps[it] as? String }
        val stampWrite = offered?.let { StampWrite(obj.javaClass, idValue, labels, nodeIdProperty, expected, carried) }
        return MergeStatement(query, bindings, stampWrite)
    }

    private companion object {
        /** Bind-parameter name for the labels an open `@NodeLabels` field records as its own. */
        const val OWNED_LABELS_PARAM = "_ownedLabels"

        /** The variable a stamping statement holds its answer in: whether it changes the node. */
        const val CHANGED = "_changed"
    }

    /** `:A:B` for [labels], each quoted so a label is one label whatever characters it holds. */
    private fun labelExpression(labels: List<String>): String =
        labels.joinToString("") { ":${quotedIdentifier(it)}" }

    private fun stringLiteral(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

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
 * Reads the property keys a stored node currently has — so a save of an untracked object can still
 * clear the `@PropertyBag` keys it dropped. Returns an empty set when the node does not exist.
 */
fun interface StoredPropertyKeys {
    fun of(labels: String, idProperty: String, id: Any): Set<String>

    /**
     * The labels an open `@NodeLabels` field has recorded on the stored node under [property] — what
     * a save under `CLEAR` may remove. Empty when the node or the record does not exist; null when
     * this reader cannot say, in which case nothing is removed.
     */
    fun ownedLabels(labels: String, idProperty: String, id: Any, property: String): List<String>? = null
}

/**
 * Whether the statements a builder produces stamp the nodes they save. A node gets a new stamp when
 * the statement changes it, and keeps the one it has otherwise. When [checked], the save of an object
 * that carries a stamp applies only if the node's own data is as the stamp says. With [relationships],
 * its relationships must be as the stamp says too.
 */
data class Stamping(val checked: Boolean, val relationships: Boolean = false) {
    /** The same stamping without the check, for the nodes a view save reaches through a relationship. */
    fun unchecked(): Stamping = if (checked) Stamping(false) else this
}

/**
 * The node a save statement stamps. [expected] is the stamp the statement requires the node to carry:
 * when it is non-null the statement gives no row if the node has changed or gone.
 */
data class StampWrite(
    val fragmentClass: Class<*>,
    val id: Any,
    val labels: String,
    val idProperty: String,
    val expected: String?,
    /** The stamp the object carried, whether or not the statement requires it. */
    val carried: String? = null,
)

/**
 * Represents a MERGE statement with its parameter bindings.
 */
data class MergeStatement(
    val statement: String,
    val bindings: Map<String, Any?>,
    /** What this statement does about the node's stamp; null when it does not stamp. */
    val stamp: StampWrite? = null,
)