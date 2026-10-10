package org.drivine.query.sort

import org.drivine.schema.SchemaGrammar

/**
 * Emits `apoc.coll.sortMaps()` wraps — Neo4j only, requires APOC Extended installed.
 *
 * Both top-level and nested sorts are emitted inline as expression wraps, with no
 * structural change to the surrounding query. `apoc.coll.sortMaps(list, prop)` sorts
 * in descending order by default, so ascending is handled by wrapping with `reverse()`.
 *
 * The list holds the projected maps, not the nodes, so it is sorted by the key the property has in
 * the projection, and that key is written as an escaped string literal.
 *
 * `sortMaps` reads a key of the map it is given and no deeper. A nested view's element holds the
 * sorted property one map down, in the view's root, so each element is paired with that value, the
 * pairs are sorted, and the elements are taken back out.
 */
class ApocSortMapsEmitter : CollectionSortEmitter {

    override fun emitTopLevel(ctx: TopLevelSortContext): TopLevelSortEmission {
        val listComprehension = "[(${ctx.rootAlias})${ctx.direction}(${ctx.targetAlias}:${ctx.targetLabelString}) |\n        ${ctx.projection}\n    ]"
        val wrapped = wrap(listComprehension, ctx.projectedKey, ctx.rootKey, ctx.sort.ascending)
        return TopLevelSortEmission(prolog = null, projectionExpression = wrapped)
    }

    override fun emitNested(ctx: NestedSortContext): String {
        return wrap(ctx.listComprehension, ctx.projectedKey, ctx.rootKey, ctx.sort.ascending)
    }

    private fun wrap(listExpr: String, key: String, rootKey: String?, ascending: Boolean): String {
        val sorted = if (rootKey == null) {
            "apoc.coll.sortMaps($listExpr, ${stringLiteral(key)})"
        } else {
            val value = "_element.${SchemaGrammar.identifier(rootKey)}.${SchemaGrammar.identifier(key)}"
            "[_sorted IN apoc.coll.sortMaps([_element IN $listExpr | {_key: $value, _element: _element}], '_key') | _sorted._element]"
        }
        return if (ascending) "reverse($sorted)" else sorted
    }

    /** [value] as a single-quoted string literal: a backslash or a quote in it is escaped. */
    private fun stringLiteral(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
