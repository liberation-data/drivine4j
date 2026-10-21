package org.drivine.backend.postgres

import org.drivine.backend.sql.NodeShape
import org.drivine.backend.sql.WriteBlocks
import org.drivine.backend.sql.WritePlan
import org.drivine.backend.sql.WriteReturns

/**
 * Postgres carries out a plan by the function the store keeps (see [PostgresWriteFunctions]): the
 * statement is a call of it, the same text for every plan, and the shapes the plan names are handed to
 * it as data beside the plan.
 */
internal class PostgresWriteBlocks(private val functions: PostgresWriteFunctions) : WriteBlocks {

    override fun statement(shapes: List<NodeShape>, returns: WriteReturns, patchedIf: String?): String {
        val call = "${functions.write}(CAST(\$${WritePlan.PLAN} AS jsonb), CAST(\$$SHAPES AS jsonb), CAST(\$${WritePlan.MARK} AS text), '${returns.name}', CAST(\$$PATCHED_IF AS text))"
        // A count is a number, as the graph engines give it; a stamp is text.
        val value = if (returns == WriteReturns.COUNT) "CAST(w.v AS bigint)" else "w.v"
        return "SELECT $value FROM $call AS w (v)"
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
