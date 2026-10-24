package org.drivine.backend.sqlite

import org.drivine.backend.sql.NodeShape
import org.drivine.backend.sql.WriteBlocks
import org.drivine.backend.sql.WritePlan
import org.drivine.backend.sql.WriteReturns

/**
 * SQLite carries out a plan by the function Drivine registers on each connection (see
 * [SqliteWriteFunction]): the statement is a call of it, the same text for every plan of the graph, and
 * the shapes the plan names are handed to it as data beside the plan. The function returns a JSON array
 * of what it gives back, and the statement reads it as rows: a stamp as a JSON string, as every text
 * value of a statement that opens with [SqliteSql.JSON_ROWS] is JSON, and a count as a number.
 */
internal class SqliteWriteBlocks(private val graph: SqliteGraph) : WriteBlocks {

    override fun statement(shapes: List<NodeShape>, returns: WriteReturns, patchedIf: String?): String {
        val call = "${SqliteFunctions.WRITE}(${SqliteSql.literal(graph.name)}, \$${WritePlan.PLAN}, \$$SHAPES, \$${WritePlan.MARK}, '${returns.name}', \$$PATCHED_IF)"
        val value = if (returns == WriteReturns.COUNT) "w.value" else "json_quote(w.value)"
        return "${SqliteSql.JSON_ROWS}\nSELECT $value FROM json_each($call) w ORDER BY w.key"
    }

    override fun bindings(shapes: List<NodeShape>, patchedIf: String?): Map<String, Any?> =
        mapOf(SHAPES to shapes.map { mapOf("labels" to it.labels, "key" to it.key) }, PATCHED_IF to patchedIf)

    companion object {
        /** The parameter the shapes are bound as: a JSON array of `{labels, key}`, in the order the plan numbers them. */
        const val SHAPES = "_shapes"

        /** The parameter that carries the query a `patch` step asks of the node it found, or null. */
        const val PATCHED_IF = "_patchedIf"
    }
}
