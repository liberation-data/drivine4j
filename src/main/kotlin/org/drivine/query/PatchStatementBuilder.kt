package org.drivine.query

import org.drivine.model.FragmentModel
import org.drivine.model.Stamps
import org.drivine.query.grammar.CypherGrammar
import org.drivine.schema.SchemaGrammar

/**
 * One node of a patch of many: the [index] its result is returned under, the [id] it is found by,
 * and the [values] to write, by stored property name. Every row of a call holds the same names; a
 * value that is null is left alone or cleared, as the call says.
 */
internal class PatchRow(val index: Int, val id: Any, val values: Map<String, Any?>) {
    companion object {
        /**
         * [rows] in the order of their ids, which is the order they are written in: two calls over the
         * same nodes then take their locks in one order, and neither waits on the other for ever.
         */
        fun inLockOrder(rows: List<PatchRow>): List<PatchRow> = rows.sortedBy { it.id.toString() }

        /**
         * How many rows go to a statement, of [most] where none of [properties] is a vector. A vector
         * is a thousand numbers and more, so rows that hold one go [VECTOR_ROWS] to a statement: a
         * statement is then megabytes, and not tens of them.
         */
        fun toAStatement(model: FragmentModel, properties: List<String>, most: Int): Int =
            if (model.fields.any { it.vectorIndexed && it.propertyName in properties }) minOf(most, VECTOR_ROWS) else most

        const val VECTOR_ROWS = 100
    }
}

/**
 * Builds the statement that writes named properties of many nodes of one fragment, and makes none:
 * `BatchSaveOperations.stampedUnwind` with `MATCH` for `MERGE` and the properties set by name. A row
 * whose node is not there gives no row back.
 *
 * The properties are set by name, and not with `SET n += row.v`, so that a vector can be wrapped on
 * an engine that wraps one: see [CypherGrammar.vectorExpression].
 */
internal class PatchStatementBuilder(private val grammar: CypherGrammar) {

    /**
     * The statement for nodes of [model], over the parameter [ROWS]: each row `{i, id, stamp, v}`, the
     * index as a string, the id, a stamp to offer and the values of [properties] by name. It returns
     * `index/changed=stamp` for each node found: see [PatchOutcomes].
     *
     * The text depends on [properties] and on [clears], and on nothing a row holds.
     *
     * @param properties the stored names of the properties written, the same for every row
     * @param clears whether a null in a row clears the property; when false, it leaves it as it is
     */
    fun unwind(model: FragmentModel, properties: List<String>, clears: Boolean): String {
        val idProperty = requireNotNull(model.nodeIdReference) { "${model.clazz.simpleName} has no @NodeId field to find a node by." }
        val wrapped = if (grammar.wrapsVectorLiteral) model.fields.filter { it.vectorIndexed }.map { it.propertyName }.toSet() else emptySet()
        // A grammar that wraps in vectorPropertyLiteral alone would have its vectors stored plainly here, where no index finds them.
        require(wrapped.none { it in properties } || grammar.vectorExpression("row.v") != "row.v") {
            "${grammar::class.simpleName} wraps a vector in vectorPropertyLiteral and not in vectorExpression, which a patch of many writes it through."
        }
        val written = properties.map { Written(it, wrapped = it in wrapped, clears) }
        return listOf(
            "UNWIND \$$ROWS AS row",
            "MATCH (n:${model.labels.joinToString(":")} {$idProperty: row.id})",
            // The lock is taken before the node is compared: see [Stamps.lock].
            Stamps.lock("n"),
            // A node with no stamp is given one whatever this says, so it says only whether a value differed.
            "WITH n, row, (${written.joinToString(" OR ") { "(${it.differs})" }}) AS $CHANGED",
            // A node whose values are already as given is not written to, so no index is fed what it holds.
            "FOREACH (_ IN CASE WHEN $CHANGED THEN [1] ELSE [] END | SET ${written.joinToString(", ") { it.set }})",
            "SET ${Stamps.restamp("n", CHANGED, "row.stamp")}",
            "RETURN row.i + '/' + CASE WHEN $CHANGED THEN '${PatchOutcomes.CHANGED}' ELSE '${PatchOutcomes.UNCHANGED}' END + '=' + n.${Stamps.QUOTED}",
        ).joinToString("\n")
    }

    /**
     * The statement that writes properties of the one node of [model] whose id is bound as [ID], if
     * it meets [where], and makes no node. It returns `0/flag=stamp` for the node, the flag being
     * [PatchOutcomes.CHANGED], [PatchOutcomes.UNCHANGED] or [PatchOutcomes.UNMET], and no row when
     * the node is not there.
     *
     * The predicate is read after the lock is taken, so what it compares is what the last writer
     * committed. It is read as a value of a `WITH`, and not in a `WHERE`, which would drop the row
     * of a node that does not meet it: that node is there, and the statement says so.
     *
     * Where the engine takes a predicate only in a `WHERE` ([CypherGrammar.predicatesOnlyInWhere]),
     * the node is locked under a name of the statement's own, [LOCKED], and found again as [alias] by
     * an `OPTIONAL MATCH` whose `WHERE` holds the predicate: no row of it says the predicate is not
     * met, and the locked node still gives its row.
     *
     * The text depends on the names in [values] and on which of them are null, and on no value.
     *
     * @param alias what [where] calls the node: `n` for a fragment, the root field's name for a view
     * @param values what to write, by stored property name: a null removes the property. The value
     *   of the property at index `i` is bound as [VALUE] and `i`
     * @param where the predicate, as a load writes it; null for a patch with none
     * @param prologs the `CALL { }` clauses [where] reads its relationship counts from, on an engine
     *   that has no `EXISTS { }`
     */
    fun one(model: FragmentModel, alias: String, values: Map<String, Any?>, where: String?, prologs: List<String> = emptyList()): String {
        val idProperty = requireNotNull(model.nodeIdReference) { "${model.clazz.simpleName} has no @NodeId field to find a node by." }
        val wrapped = if (grammar.wrapsVectorLiteral) model.fields.filter { it.vectorIndexed }.map { it.propertyName }.toSet() else emptySet()
        val assigned = values.entries.mapIndexed { index, (property, value) ->
            val stored = "$alias.${SchemaGrammar.identifier(property)}"
            val given = if (property in wrapped) grammar.vectorPropertyLiteral("$VALUE$index") else "\$$VALUE$index"
            if (value == null) Assigned("$stored = null", "$stored IS NOT NULL") else Assigned("$stored = $given", "NOT coalesce($stored = $given, false)")
        }
        fun node(name: String) = "($name:${model.labels.joinToString(":")} {$idProperty: \$$ID})"
        val met = if (where != null && grammar.predicatesOnlyInWhere) {
            require(prologs.isEmpty()) { "${grammar::class.simpleName} takes a predicate only in a WHERE, and gives one that needs a clause before it." }
            listOf(
                "MATCH ${node(LOCKED)}",
                Stamps.lock(LOCKED),
                "WITH $LOCKED",
                "OPTIONAL MATCH ${node(alias)} WHERE $alias = $LOCKED AND ($where)",
                // Two nodes may share the id: each is its own row, met or not.
                "WITH $LOCKED AS $alias, $alias IS NOT NULL AS $MET",
            )
        } else {
            listOfNotNull(
                "MATCH ${node(alias)}",
                // The lock is taken before the predicate is read: see [Stamps.lock].
                Stamps.lock(alias),
                prologs.takeIf { it.isNotEmpty() }?.let { "WITH $alias\n${it.joinToString("\n")}" },
                "WITH $alias, ${where?.let { "coalesce(($it), false)" } ?: "true"} AS $MET",
            )
        }
        return (met + listOf(
            // A comparison that cannot tell counts as a change, as it does for a save.
            "WITH $alias, $MET, $MET AND (${assigned.joinToString(" OR ") { "(${it.differs})" }}) AS $CHANGED",
            // A node that is not changed is not written to, beyond the lock and a stamp if it had none.
            "FOREACH (_ IN CASE WHEN $CHANGED THEN [1] ELSE [] END | SET ${assigned.joinToString(", ") { it.set }})",
            "SET ${Stamps.restamp(alias, CHANGED, "\$${Stamps.NEW_PARAM}")}",
            "RETURN '0/' + CASE WHEN $CHANGED THEN '${PatchOutcomes.CHANGED}' WHEN $MET THEN '${PatchOutcomes.UNCHANGED}' ELSE '${PatchOutcomes.UNMET}' END + '=' + $alias.${Stamps.QUOTED}",
        )).joinToString("\n")
    }

    /** One property as a patch of one node writes it: the item that sets or removes it, and the test of whether that changes the node. */
    private class Assigned(val set: String, val differs: String)

    /** One property as the statement writes it: the item that sets it, and the test of whether that changes the node. */
    private inner class Written(property: String, wrapped: Boolean, clears: Boolean) {
        private val stored = "n.${SchemaGrammar.identifier(property)}"
        private val given = "row.v.${SchemaGrammar.identifier(property)}"

        /** What is written when the row's value is not null. A wrapped value is never asked for a null. */
        private val value = if (wrapped) grammar.vectorExpression(given) else given

        val set: String = when {
            wrapped -> "$stored = CASE WHEN $given IS NULL THEN ${if (clears) "null" else stored} ELSE $value END"
            clears -> "$stored = $given"
            else -> "$stored = coalesce($given, $stored)"
        }

        /**
         * A comparison that cannot tell counts as a change, as it does for a save. A wrapped value is
         * compared inside a `CASE`, which reads it only when the row's is not null: an `AND` does not
         * promise to leave its second term unread.
         */
        val differs: String = when {
            clears -> "CASE WHEN $given IS NULL THEN $stored IS NOT NULL ELSE NOT coalesce($stored = $value, false) END"
            wrapped -> "CASE WHEN $given IS NULL THEN false ELSE NOT coalesce($stored = $value, false) END"
            else -> "$given IS NOT NULL AND NOT coalesce($stored = $value, false)"
        }
    }

    companion object {
        /** The parameter the rows are bound as. */
        const val ROWS = "rows"

        /** The parameter a patch of one node binds the node's id as. */
        const val ID = "id"

        /** What a patch of one node binds a value as, before the value's index. */
        const val VALUE = "_v"

        /** The variable that holds whether a row changes its node. */
        private const val CHANGED = "_changed"

        /** What a patch of one node calls the node it locked, where it finds it again under the predicate's name. */
        private const val LOCKED = "_locked"

        /** The variable that holds whether the node meets the predicate of its patch. */
        private const val MET = "_met"
    }
}
