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

        /** The variable that holds whether a row changes its node. */
        private const val CHANGED = "_changed"
    }
}
