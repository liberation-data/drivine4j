package org.drivine.query.sort

/**
 * Emits `apoc.coll.sortMaps()` wraps — Neo4j only, requires APOC Extended installed.
 *
 * Both top-level and nested sorts are emitted inline as expression wraps, with no
 * structural change to the surrounding query. `apoc.coll.sortMaps(list, prop)` sorts
 * in descending order by default, so ascending is handled by wrapping with `reverse()`.
 */
class ApocSortMapsEmitter : CollectionSortEmitter {

    override fun emitTopLevel(ctx: TopLevelSortContext): TopLevelSortEmission {
        val listComprehension = "[(${ctx.rootAlias})${ctx.direction}(${ctx.targetAlias}:${ctx.targetLabelString}) |\n        ${ctx.projection}\n    ]"
        val wrapped = wrap(listComprehension, ctx.sort.propertyName, ctx.sort.ascending)
        return TopLevelSortEmission(prolog = null, projectionExpression = wrapped)
    }

    override fun emitNested(ctx: NestedSortContext): String {
        return wrap(ctx.listComprehension, ctx.sort.propertyName, ctx.sort.ascending)
    }

    private fun wrap(listExpr: String, propertyName: String, ascending: Boolean): String {
        val sorted = "apoc.coll.sortMaps($listExpr, '$propertyName')"
        return if (ascending) "reverse($sorted)" else sorted
    }
}