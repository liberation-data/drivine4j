package org.drivine.query.sort

/**
 * Emits `CALL { MATCH ... ORDER BY ... collect(...) }` subqueries as prologs before
 * the RETURN. Portable across Neo4j 5+, FalkorDB, and Neptune — no server extensions.
 *
 * Nested sorts are not supported: `CALL { }` is a query-level construct and cannot
 * appear inside a list comprehension or map projection, which is where nested sorts
 * would need to sit. Users who need nested sort on engines without APOC should use
 * client-side `@SortedBy` instead.
 */
class CallSubqueryEmitter : CollectionSortEmitter {

    override fun emitTopLevel(ctx: TopLevelSortContext): TopLevelSortEmission {
        val sortedVar = "${ctx.targetAlias}_sorted"
        val order = if (ctx.sort.ascending) "ASC" else "DESC"
        val prolog = """
            |CALL {
            |    WITH ${ctx.rootAlias}
            |    MATCH (${ctx.rootAlias})${ctx.direction}(${ctx.targetAlias}:${ctx.targetLabelString})
            |    WITH ${ctx.targetAlias} ORDER BY ${ctx.targetAlias}.${ctx.sort.propertyName} $order
            |    RETURN collect(${ctx.projection}) AS $sortedVar
            |}
        """.trimMargin()
        return TopLevelSortEmission(prolog = prolog, projectionExpression = sortedVar)
    }

    override fun emitNested(ctx: NestedSortContext): String {
        throw UnsupportedOperationException(
            "CALL_SUBQUERY strategy cannot sort a nested relationship collection " +
            "(sort target: ${ctx.sort.relationshipPath}.${ctx.sort.propertyName}). " +
            "CALL { } subqueries are query-level and cannot appear inside nested projections. " +
            "Either (a) switch this database's CollectionSortStrategy to APOC_SORT_MAPS " +
            "(Neo4j only, requires APOC Extended installed), or (b) use client-side @SortedBy " +
            "on the relationship field instead."
        )
    }
}