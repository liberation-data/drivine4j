package org.drivine.query

import org.drivine.annotation.Direction
import org.drivine.manager.NodeRef
import org.drivine.manager.RelateMode
import org.drivine.model.Stamps

/**
 * Statements for a relationship whose type is known only at runtime — between two stored nodes that
 * are matched, never created.
 */
internal object EdgeStatements {

    private const val FROM = "_fromId"
    private const val TO = "_toId"
    private const val MARK = "_mark"

    /** The `SET` item that gives the node [alias] a new relationship token: one of its relationships is made or removed. */
    private fun relinked(alias: String): String = Stamps.relink(alias, "true", "\$$MARK")

    /**
     * Join [from] to [to] with a [type] relationship carrying [properties], and count the result: 0
     * when either node is absent. Null property values are left out, as a merge-patch save leaves a
     * null field.
     *
     * Both nodes get a new relationship token when a relationship is made or its properties change. A
     * `MERGE` that finds every such relationship there, carrying these properties already, changes
     * neither; one that finds several and rewrites any of them changes both.
     */
    fun relate(from: NodeRef, to: NodeRef, type: String, properties: Map<String, Any?>, mode: RelateMode): MergeStatement {
        require(type.isNotBlank()) { "A relationship needs a type." }
        val bindings = mutableMapOf<String, Any?>(FROM to from.id, TO to to.id, MARK to Stamps.fresh())
        val written = properties.filterValues { it != null }.entries.mapIndexed { i, (key, value) ->
            bindings["_rel$i"] = value
            quotedIdentifier(key) to "\$_rel$i"
        }
        val merges = mode == RelateMode.MERGE
        // There may be several between the two nodes, and the properties are set on them all.
        val changes = if (merges) "_had = 0 OR _same < _had" else "true"
        val assignments = written.map { (key, parameter) -> "r.$key = $parameter" } +
            Stamps.relink("a", changes, "\$$MARK") + Stamps.relink("b", changes, "\$$MARK")
        val statement = buildString {
            append("MATCH ").append(from.pattern("a", FROM))
            append("\nMATCH ").append(to.pattern("b", TO))
            if (merges) {
                // Both nodes' locks are taken before the relationships between them are read, so what
                // is counted is still so when the MERGE runs: see [Stamps.lock].
                append("\n").append(Stamps.lock("a", "b"))
                append("\nWITH a, b")
                // Counted before the MERGE: how many relationships are there, and how many carry the properties already.
                val same = (listOf("x IS NOT NULL") + written.map { (key, parameter) -> "coalesce(x.$key = $parameter, false)" })
                append("\nOPTIONAL MATCH (a)-[x:").append(quotedIdentifier(type)).append("]->(b)")
                append("\nWITH a, b, count(x) AS _had, sum(CASE WHEN ").append(same.joinToString(" AND ")).append(" THEN 1 ELSE 0 END) AS _same")
            }
            append("\n").append(mode.name).append(" (a)-[r:").append(quotedIdentifier(type)).append("]->(b)")
            append("\nSET ").append(assignments.joinToString(", "))
            append("\nRETURN count(r)")
        }
        return MergeStatement(statement, bindings)
    }

    /**
     * Remove every [type] relationship from [from] to [to], and count them: 0 when there was none, or
     * when either node is absent.
     */
    fun unrelate(from: NodeRef, to: NodeRef, type: String): MergeStatement {
        require(type.isNotBlank()) { "A relationship needs a type." }
        val statement = """
            MATCH ${from.pattern("a", FROM)}-[r:${quotedIdentifier(type)}]->${to.pattern("b", TO)}
            DELETE r
            SET ${relinked("a")}, ${relinked("b")}
            RETURN count(r)
        """.trimIndent()
        return MergeStatement(statement, mapOf(FROM to from.id, TO to to.id, MARK to Stamps.fresh()))
    }

    /** Remove every [type] relationship [from] has in [direction], and count them. */
    fun unrelateAll(from: NodeRef, type: String, direction: Direction): MergeStatement {
        require(type.isNotBlank()) { "A relationship needs a type." }
        val edge = "[r:${quotedIdentifier(type)}]"
        val arrow = when (direction) {
            Direction.OUTGOING -> "-$edge->"
            Direction.INCOMING -> "<-$edge-"
            Direction.UNDIRECTED -> "-$edge-"
        }
        val statement = """
            MATCH ${from.pattern("a", FROM)}$arrow(b)
            DELETE r
            SET ${relinked("a")}, ${relinked("b")}
            RETURN count(r)
        """.trimIndent()
        return MergeStatement(statement, mapOf(FROM to from.id, MARK to Stamps.fresh()))
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
