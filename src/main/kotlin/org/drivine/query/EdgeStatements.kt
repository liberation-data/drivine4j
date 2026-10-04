package org.drivine.query

import org.drivine.annotation.Direction
import org.drivine.manager.NodeRef
import org.drivine.manager.RelateMode

/**
 * Statements for a relationship whose type is known only at runtime — between two stored nodes that
 * are matched, never created.
 */
internal object EdgeStatements {

    private const val FROM = "_fromId"
    private const val TO = "_toId"

    /**
     * Join [from] to [to] with a [type] relationship carrying [properties], and count the result: 0
     * when either node is absent. Null property values are left out, as a merge-patch save leaves a
     * null field.
     */
    fun relate(from: NodeRef, to: NodeRef, type: String, properties: Map<String, Any?>, mode: RelateMode): MergeStatement {
        require(type.isNotBlank()) { "A relationship needs a type." }
        val bindings = mutableMapOf<String, Any?>(FROM to from.id, TO to to.id)
        val assignments = properties.filterValues { it != null }.entries.mapIndexed { i, (key, value) ->
            bindings["_rel$i"] = value
            "r.${quotedIdentifier(key)} = \$_rel$i"
        }
        val statement = buildString {
            append("MATCH ").append(from.pattern("a", FROM))
            append("\nMATCH ").append(to.pattern("b", TO))
            append("\n").append(mode.name).append(" (a)-[r:").append(quotedIdentifier(type)).append("]->(b)")
            if (assignments.isNotEmpty()) append("\nSET ").append(assignments.joinToString(", "))
            append("\nRETURN count(r)")
        }
        return MergeStatement(statement, bindings)
    }

    /**
     * The `MATCH` that binds, as `n`, the [targetLabels] nodes joined to [from] by [type] in
     * [direction] — each once. Engines differ on whether two such relationships to one node give one
     * row or two, so the answer is made the same everywhere.
     */
    fun relatedMatch(from: NodeRef, type: String, direction: Direction, targetLabels: List<String>): MergeStatement {
        require(type.isNotBlank()) { "A relationship needs a type." }
        val edge = "[:${quotedIdentifier(type)}]"
        val arrow = when (direction) {
            Direction.OUTGOING -> "-$edge->"
            Direction.INCOMING -> "<-$edge-"
            Direction.UNDIRECTED -> "-$edge-"
        }
        val target = targetLabels.joinToString("") { ":${quotedIdentifier(it)}" }
        return MergeStatement("MATCH ${from.pattern("a", FROM)}$arrow(n$target)\nWITH DISTINCT n", mapOf(FROM to from.id))
    }
}
