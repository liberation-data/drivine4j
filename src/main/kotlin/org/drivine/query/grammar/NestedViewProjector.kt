package org.drivine.query.grammar

import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel

/**
 * Context for projecting a nested GraphView relationship.
 */
data class NestedViewContext(
    val rootFieldName: String,
    val rel: RelationshipModel,
    val targetAlias: String,
    val direction: String,
    val targetLabelString: String,
    val nestedViewModel: GraphViewModel,
    val rootFragmentFields: List<String>?,
    val rootFragmentFieldName: String,
    /** For each nested relationship: (fieldName, alias, directionString, labelString, fieldProjection) */
    val nestedRelationships: List<NestedRelInfo>,
)

data class NestedRelInfo(
    val fieldName: String,
    val alias: String,
    val direction: String,
    val labelString: String,
    val projection: String,
    val isCollection: Boolean,
)

/**
 * Result of projecting a nested view.
 * If prolog is non-null, it's added to the query's CALL prologs.
 * The expression is used in the WITH clause.
 */
data class NestedViewProjection(
    val expression: String,
    val prolog: String? = null,
    val bridgeVariables: List<String> = emptyList(),
)

/**
 * Strategy for projecting nested GraphView relationships in generated queries.
 */
interface NestedViewProjector {
    fun project(ctx: NestedViewContext): NestedViewProjection
}

/**
 * Inline pattern comprehension — the default for Neo4j.
 * Nests pattern comprehensions inside map projections.
 * Returns null prolog (everything is inline).
 */
class InlineNestedViewProjector : NestedViewProjector {
    override fun project(ctx: NestedViewContext): NestedViewProjection {
        // Signal to the builder to use its existing inline logic
        // by returning a special marker. The builder checks for this
        // and falls through to buildRelationshipProjection.
        return NestedViewProjection(expression = INLINE_MARKER)
    }

    companion object {
        const val INLINE_MARKER = "__INLINE__"
    }
}

/**
 * CALL subquery prolog — for FalkorDB and other openCypher engines
 * that don't support nested pattern comprehensions (FalkorDB/FalkorDB#1888).
 *
 * Emits OPTIONAL MATCHes for each nested relationship, collects them,
 * and returns the assembled projection as a single CALL block.
 */
class CallSubqueryNestedViewProjector : NestedViewProjector {
    override fun project(ctx: NestedViewContext): NestedViewProjection {
        val sb = StringBuilder()
        sb.appendLine("CALL {")
        sb.appendLine("    WITH ${ctx.rootFieldName}")
        sb.appendLine("    OPTIONAL MATCH (${ctx.rootFieldName})${ctx.direction}(${ctx.targetAlias}:${ctx.targetLabelString})")

        // OPTIONAL MATCH each nested relationship
        val collectVars = mutableListOf<Pair<String, String>>() // (fieldName, collectVar)
        ctx.nestedRelationships.forEach { nested ->
            sb.appendLine("    OPTIONAL MATCH (${ctx.targetAlias})${nested.direction}(${nested.alias}:${nested.labelString})")
            val collectVar = "${ctx.targetAlias}_${nested.fieldName}_c"
            collectVars.add(nested.fieldName to collectVar)
        }

        // Collect nested relationships per target node, filtering out nulls from OPTIONAL MATCH.
        // For single nullable relationships (isCollection = false), wrap collect() in head() so
        // the field materialises as a single object or null rather than a list — matching the
        // shape the inline (Neo4j) projector produces.
        if (collectVars.isNotEmpty()) {
            val collectExprs = collectVars.mapIndexed { i, (_, collectVar) ->
                val nested = ctx.nestedRelationships[i]
                val collectExpr = "collect(DISTINCT CASE WHEN ${nested.alias} IS NOT NULL THEN ${nested.projection} END)"
                val wrapped = if (nested.isCollection) collectExpr else "head($collectExpr)"
                "$wrapped AS $collectVar"
            }
            sb.appendLine("    WITH ${ctx.targetAlias}, ${collectExprs.joinToString(", ")}")
        }

        // Build return projection
        val rootFieldMappings = if (ctx.rootFragmentFields == null) {
            ".*"
        } else {
            ctx.rootFragmentFields.joinToString(", ") { "$it: ${ctx.targetAlias}.$it" }
        }

        val returnFields = mutableListOf("${ctx.rootFragmentFieldName}: { $rootFieldMappings }")
        collectVars.forEach { (fieldName, collectVar) ->
            returnFields.add("$fieldName: $collectVar")
        }

        val targetProjection = "{ ${returnFields.joinToString(", ")} }"

        // Guard the outer projection against an absent target. OPTIONAL MATCH produces a row
        // with the target alias = null when nothing matches; collect()ing that projection
        // without a null check gives a list containing `{root: {null-valued fields}, ...}`,
        // because collect() only skips NULL *scalars*, not maps whose contents are null.
        // FalkorDB hits this directly (FalkorDB/FalkorDB#1889); Neo4j sidesteps it by using
        // InlineNestedViewProjector's pattern comprehension, which returns [] naturally.
        val guardedProjection = "CASE WHEN ${ctx.targetAlias} IS NOT NULL THEN $targetProjection END"
        if (ctx.rel.isCollection) {
            sb.appendLine("    RETURN collect($guardedProjection) AS ${ctx.targetAlias}")
        } else {
            sb.appendLine("    RETURN $guardedProjection AS ${ctx.targetAlias}")
        }
        sb.append("}")

        return NestedViewProjection(
            expression = "${ctx.targetAlias} AS ${ctx.targetAlias}",
            prolog = sb.toString(),
            bridgeVariables = listOf(ctx.targetAlias),
        )
    }
}