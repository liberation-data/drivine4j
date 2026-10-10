package org.drivine.query.sort

/**
 * Emits `apoc.coll.sortMaps()` wraps — Neo4j only, requires APOC Extended installed.
 *
 * Both top-level and nested sorts are emitted inline as expression wraps, with no
 * structural change to the surrounding query. `apoc.coll.sortMaps(list, prop)` sorts
 * in descending order by default, so ascending is handled by wrapping with `reverse()`.
 *
 * The list holds the projected maps, not the nodes, so it is sorted by the key the property has in
 * the projection, and that key is written as an escaped string literal.
 */
class ApocSortMapsEmitter : CollectionSortEmitter {

    override fun emitTopLevel(ctx: TopLevelSortContext): TopLevelSortEmission {
        val listComprehension = "[(${ctx.rootAlias})${ctx.direction}(${ctx.targetAlias}:${ctx.targetLabelString}) |\n        ${ctx.projection}\n    ]"
        val wrapped = wrap(listComprehension, ctx.projectedKey, ctx.sort.ascending)
        return TopLevelSortEmission(prolog = null, projectionExpression = wrapped)
    }

    override fun emitNested(ctx: NestedSortContext): String {
        return wrap(ctx.listComprehension, ctx.projectedKey, ctx.sort.ascending)
    }

    private fun wrap(listExpr: String, key: String, ascending: Boolean): String {
        val sorted = "apoc.coll.sortMaps($listExpr, ${stringLiteral(key)})"
        return if (ascending) "reverse($sorted)" else sorted
    }

    /** [value] as a single-quoted string literal: a backslash or a quote in it is escaped. */
    private fun stringLiteral(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
