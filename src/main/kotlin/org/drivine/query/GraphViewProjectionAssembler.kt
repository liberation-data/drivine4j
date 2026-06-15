package org.drivine.query

import org.drivine.annotation.AggregateFunction
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.model.AggregateFieldModel
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.grammar.*
import org.drivine.query.sort.*

/**
 * The shared projection core of a `@GraphView` query: the WITH-clause projection of the root
 * fragment plus every relationship (pattern comprehensions, nested views, recursive expansion,
 * relationship fragments, collection sorts), the per-root aggregates, the required-relationship
 * `EXISTS` checks, and the `WHERE` assembly.
 *
 * This is the piece [GraphViewLoadBuilder] (a `MATCH` head) and [GraphViewVectorSearchBuilder]
 * (a vector `CALL` head + scored RETURN) both compose over — they differ only in the head, the
 * prolog wiring, and the RETURN shape, all of which stay in those builders.
 *
 * Recursive (self-referential) relationships expand to a configurable depth; chain cycles
 * (A → B → C → A) are detected via a visit counter and terminated on the closing relationship's
 * maxDepth. One instance is created per build (see [GraphViewQueryBuilder]); all mutable per-build
 * state lives in the injected [BuildContext] — building a projection accumulates prologs / bridge
 * variables on it, which the owning builder reads afterwards.
 */
internal class GraphViewProjectionAssembler(
    private val viewModel: GraphViewModel,
    private val grammar: CypherGrammar,
    private val context: BuildContext,
) {

    private val sortEmitter: CollectionSortEmitter = grammar.collectionSortEmitter

    /** The root fragment's field name — the alias the root node is bound to throughout the query. */
    val rootFieldName: String get() = viewModel.rootFragment.fieldName

    /**
     * The colon-joined label string for the root fragment's `MATCH`, e.g. `Person:Mapped`.
     * @throws IllegalArgumentException if the root fragment declares no labels
     */
    fun matchLabelString(): String {
        val rootFragmentModel = viewModel.rootFragment
        val fragmentLabels = GraphTypeLabels.fragmentLabels(rootFragmentModel.fragmentType)
        if (fragmentLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for root fragment ${rootFragmentModel.fragmentType.name}. @GraphFragment must specify at least one label.")
        }
        return fragmentLabels.joinToString(":")
    }

    /**
     * Builds the WITH-clause projection sections: the root fragment, every relationship, and every
     * per-root aggregate, in declaration order. Building relationship/aggregate projections may
     * register `CALL { }` prologs and bridge variables on the [BuildContext], so the caller must
     * emit its prolog section *after* calling this.
     */
    fun projectionSections(): List<String> {
        val rootFragmentModel = viewModel.rootFragment
        val rootFieldName = rootFragmentModel.fieldName
        val initialVisitCounts = mapOf(viewModel.className to 1)
        val withSections = mutableListOf<String>()

        // Root fragment projection
        val rootFragmentFields = getFragmentFields(rootFragmentModel.fragmentType)
        val rootComment = "// ${capitalize(rootFieldName)}"
        val rootProjection = buildFragmentProjectionWithMapping(rootFieldName, rootFieldName, rootFragmentFields)
        withSections.add("    $rootComment\n    $rootProjection AS $rootFieldName")

        // Relationship projections
        viewModel.relationships.forEach { rel ->
            val comment = buildRelationshipComment(rel)
            val targetAlias = rel.deriveTargetAlias()
            val projection = buildRelationshipPattern(rootFieldName, rel, targetAlias, initialVisitCounts)
            withSections.add("    $comment\n    $projection")
        }

        // Per-root aggregate projections (@Count / @Aggregate)
        viewModel.aggregateFields.forEach { agg ->
            withSections.add("    // ${agg.fieldName} (${agg.function} over ${agg.type})\n    ${buildAggregateProjection(rootFieldName, agg)}")
        }

        return withSections
    }

    /**
     * The RETURN-map entries (`fieldName: alias`) for the projected view — root, relationships, and
     * aggregates — each prefixed with [indent]. Mirrors the aliases [projectionSections] binds.
     */
    fun valueFieldEntries(indent: String): List<String> {
        val rootFieldName = viewModel.rootFragment.fieldName
        val entries = mutableListOf<String>()
        entries.add("$indent${rootFieldName}: $rootFieldName")
        viewModel.relationships.forEach { rel ->
            entries.add("$indent${rel.fieldName}: ${rel.deriveTargetAlias()}")
        }
        viewModel.aggregateFields.forEach { agg ->
            entries.add("$indent${agg.fieldName}: ${agg.fieldName}")
        }
        return entries
    }

    /**
     * Builds the complete WHERE section combining an optional leading condition (a user WHERE clause,
     * or a vector-score threshold) with the required-relationship checks.
     *
     * @param leadingCondition condition placed first (may be null)
     * @param requiredRelChecks EXISTS checks for required relationships
     * @return the complete WHERE section (including the `WHERE` keyword), or empty string
     */
    fun whereSection(leadingCondition: String?, requiredRelChecks: List<String>): String {
        val conditions = mutableListOf<String>()
        if (leadingCondition != null) {
            conditions.add(leadingCondition)
        }
        conditions.addAll(requiredRelChecks)

        return if (conditions.isEmpty()) {
            ""
        } else {
            "\nWHERE " + conditions.joinToString("\n  AND ")
        }
    }

    /**
     * The projected aliases of required (non-null, non-collection) relationships. Each is projected
     * as `[…][0]` (or a bridge variable for paths), so it is null in the result exactly when the
     * relationship is absent.
     *
     * The vector-search path filters these for `IS NOT NULL` *after* projection rather than using
     * the pre-projection [requiredRelationshipChecks] — an inline existence pattern in a `WHERE`
     * over a node sourced from a vector-index `CALL` trips FalkorDB ("expected Null but was Pointer",
     * because the node carries the `vecf32` property), whereas filtering the projected value is
     * portable and equivalent for required single relationships.
     */
    fun requiredRelationshipAliases(): List<String> =
        viewModel.relationships
            .filter { !it.isNullable && !it.isCollection }
            .map { it.deriveTargetAlias() }

    /**
     * Builds EXISTS checks for non-nullable, non-collection relationships, ensuring the query only
     * returns root nodes that have the required relationships.
     */
    fun requiredRelationshipChecks(): List<String> {
        return viewModel.relationships
            .filter { rel -> !rel.isNullable && !rel.isCollection }
            .map { rel ->
                if (rel.isPath) {
                    // The path's CALL prolog already computed the (single) target via head(collect(…)),
                    // which is null when the path is absent. A plain value null-check filters roots
                    // lacking the path — portable across engines (no pattern predicate after WITH,
                    // which Memgraph rejects).
                    return@map "${rel.deriveTargetAlias()} IS NOT NULL"
                }

                val direction = Directions.directionString(rel)

                // Get labels for the target - for relationship fragments, use the target node type
                val targetType = if (rel.isRelationshipFragment) rel.targetNodeType!! else rel.elementType
                val targetLabels = GraphTypeLabels.labelsForType(targetType)
                val targetLabelString = targetLabels.joinToString(":")

                grammar.existenceCheck(rootFieldName, direction, targetLabelString)
            }
    }

    // =============================================================================
    // Projection helpers
    // =============================================================================

    /**
     * Finds a collection sort spec for a direct relationship (e.g., "assignedTo").
     */
    private fun findSortForRelationship(relationshipName: String): CollectionSortSpec? {
        return context.collectionSorts.find {
            !it.isNested() && it.relationshipPath == relationshipName
        }
    }

    /**
     * Finds a collection sort spec for a nested relationship (e.g., "worksFor" inside "raisedBy").
     */
    private fun findSortForNestedRelationship(parentAlias: String, nestedRelationshipName: String): CollectionSortSpec? {
        return context.collectionSorts.find { sort ->
            sort.isNested() &&
            sort.parentRelationship() == parentAlias &&
            sort.nestedRelationship() == nestedRelationshipName
        }
    }

    /**
     * Wraps a list comprehension with a sort expression if a sort is specified.
     * Delegates to the configured [sortEmitter] for nested sorts (inline expression wraps).
     *
     * For top-level sorts with CALL_SUBQUERY strategy, use [emitTopLevelSort] instead —
     * this method is only for nested/recursive sorts.
     */
    private fun wrapWithNestedSortIfNeeded(listComprehension: String, sort: CollectionSortSpec?): String {
        if (sort == null) return listComprehension
        return sortEmitter.emitNested(NestedSortContext(listComprehension, sort))
    }

    /**
     * Emits a top-level sorted collection. For APOC, returns an inline-wrapped expression.
     * For CALL, accumulates a prolog and returns the variable reference.
     *
     * @return The expression to use in the WITH clause (either the wrapped comprehension or a variable name)
     */
    private fun emitTopLevelSort(
        rootFieldName: String,
        rel: RelationshipModel,
        targetAlias: String,
        projection: String,
        sort: CollectionSortSpec,
    ): String {
        val direction = Directions.directionString(rel)
        val targetLabels = GraphTypeLabels.labelsForType(rel.elementType)
        val targetLabelString = targetLabels.joinToString(":")

        val ctx = TopLevelSortContext(
            rootAlias = rootFieldName,
            direction = direction,
            targetAlias = targetAlias,
            targetLabelString = targetLabelString,
            projection = projection,
            sort = sort,
        )
        val emission = sortEmitter.emitTopLevel(ctx)
        emission.prolog?.let { context.addProlog(it) }
        return emission.projectionExpression
    }

    /**
     * Builds a comment describing a relationship.
     */
    private fun buildRelationshipComment(rel: RelationshipModel): String {
        val cardinality = if (rel.isCollection) "0 or many" else "0 or 1"
        val typeDesc = rel.elementType.simpleName
        return "// ${rel.fieldName} ($cardinality $typeDesc)"
    }

    /**
     * Capitalizes the first letter of a string.
     */
    private fun capitalize(str: String): String {
        return str.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    /**
     * Builds a field projection with explicit mapping like:
     * issue {uuid: issue.uuid, id: issue.id, state: issue.state}
     *
     * For polymorphic types (null fields), uses .* to capture all properties.
     */
    private fun buildFragmentProjectionWithMapping(varName: String, sourceVar: String, fields: List<String>?): String {
        // For polymorphic types, use .* to get all fields
        if (fields == null) {
            return """$varName {
        .*,
        labels: labels($sourceVar)
    }"""
        }

        if (fields.isEmpty()) {
            return varName
        }
        val fieldMappings = fields.joinToString(",\n        ") { "$it: $sourceVar.$it" }
        // Include labels for polymorphic deserialization support
        return """$varName {
        $fieldMappings,
        labels: labels($sourceVar)
    }"""
    }

    /**
     * Gets field names from a FragmentModel.
     * Returns null for polymorphic types (sealed classes or interfaces) to signal that .* should be used.
     */
    private fun getFragmentFields(fragmentType: Class<*>): List<String>? {
        // For sealed classes, return null to signal use of .*
        if (fragmentType.kotlin.isSealed) {
            return null
        }

        // For interfaces with @NodeFragment, return null to signal use of .*
        // since we don't know which concrete implementation will be returned
        if (fragmentType.isInterface) {
            return null
        }

        return try {
            val fragmentModel = FragmentModel.from(fragmentType)
            // Fragments with a @PropertyBag use .* so the open prefixed keys are projected too —
            // the declared-field list can't name them. The transform reconstructs the bag from them.
            if (fragmentModel.propertyBags.isNotEmpty()) return null
            val fields = fragmentModel.fields.map { it.name }
            // Return null if no fields found, to signal use of .*
            fields.ifEmpty { null }
        } catch (e: Exception) {
            // On error, return null to signal use of .* (safe fallback)
            null
        }
    }

    /**
     * Builds a relationship pattern comprehension for a single relationship.
     *
     * For recursive relationships (self-referential), delegates to [buildRecursiveRelationshipPattern].
     * For chain cycles (target type already visited), checks maxDepth to decide whether to
     * terminate or expand again.
     *
     * @param rootFieldName The alias for the root node
     * @param rel The relationship model
     * @param targetAlias The derived alias for the relationship target
     * @param visitCounts Map tracking how many times each view type has been entered
     */
    private fun buildRelationshipPattern(
        rootFieldName: String,
        rel: RelationshipModel,
        targetAlias: String,
        visitCounts: Map<String, Int> = emptyMap()
    ): String {
        if (rel.isPath) {
            // Multi-hop @GraphPath: traverse through intermediate nodes, project only the final node
            return buildPathPattern(rootFieldName, rel, targetAlias, visitCounts)
        }

        if (rel.isRelationshipFragment) {
            // Relationship fragment pattern: capture both relationship properties and target node
            return buildRelationshipFragmentPattern(rootFieldName, rel, targetAlias, visitCounts)
        }

        // Check for direct self-reference
        if (rel.isRecursive) {
            return buildRecursiveRelationshipPattern(rootFieldName, rel, targetAlias, visitCounts)
        }

        // Direct target reference pattern (existing behavior)
        val direction = Directions.directionString(rel)

        // Get all labels for the target type
        val targetLabels = GraphTypeLabels.labelsForType(rel.elementType)
        if (targetLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for relationship target ${rel.elementType.name}. @GraphFragment or @GraphView must specify at least one label.")
        }
        val targetLabelString = targetLabels.joinToString(":")

        // Check for chain cycle: target is a @GraphView that's already been visited
        val targetClassName = rel.elementType.name
        val isGraphView = rel.elementType.getAnnotation(GraphView::class.java) != null
        if (isGraphView && targetClassName in visitCounts) {
            val currentCount = visitCounts[targetClassName]!!
            val effectiveMaxDepth = context.depthOverrides[rel.fieldName] ?: rel.maxDepth
            if (currentCount >= effectiveMaxDepth) {
                // Terminate: exceeded max depth for this chain cycle
                return if (rel.isCollection) {
                    "[] AS $targetAlias"
                } else {
                    "null AS $targetAlias"
                }
            }
        }

        // Delegate nested GraphView projection to the grammar's projector
        if (isGraphView) {
            val projectorResult = tryNestedViewProjector(rootFieldName, rel, targetAlias, direction, targetLabelString)
            if (projectorResult != null) {
                projectorResult.prolog?.let { context.addProlog(it) }
                context.addBridgeVariables(projectorResult.bridgeVariables)
                return projectorResult.expression
            }
            // If projector returns null (InlineNestedViewProjector), fall through to inline logic
        }

        // Build the projection for this relationship
        val projection = buildRelationshipProjection(targetAlias, rel.elementType, visitCounts)

        val pattern = if (rel.isCollection) {
            val sort = findSortForRelationship(targetAlias)
            if (sort != null) {
                val expr = emitTopLevelSort(rootFieldName, rel, targetAlias, projection, sort)
                "$expr AS $targetAlias"
            } else {
                "[($rootFieldName)${direction}($targetAlias:$targetLabelString) |\n        $projection\n    ] AS $targetAlias"
            }
        } else {
            "[($rootFieldName)${direction}($targetAlias:$targetLabelString) |\n        $projection\n    ][0] AS $targetAlias"
        }

        return pattern
    }

    /**
     * Tries the grammar's nested view projector. Returns null if the projector
     * signals inline mode (Neo4j), otherwise returns the projection result.
     */
    private fun tryNestedViewProjector(
        rootFieldName: String,
        rel: RelationshipModel,
        targetAlias: String,
        direction: String,
        targetLabelString: String,
    ): NestedViewProjection? {
        val nestedViewModel = GraphViewModel.from(rel.elementType)

        val ctx = NestedViewContext(
            rootFieldName = rootFieldName,
            rel = rel,
            targetAlias = targetAlias,
            direction = direction,
            targetLabelString = targetLabelString,
            nestedViewModel = nestedViewModel,
            rootFragmentFields = getFragmentFields(nestedViewModel.rootFragment.fragmentType),
            rootFragmentFieldName = nestedViewModel.rootFragment.fieldName,
            nestedRelationships = nestedViewModel.relationships.map { nestedRel ->
                val nestedAlias = nestedRel.deriveTargetAlias()
                val nestedLabels = GraphTypeLabels.labelsForType(nestedRel.elementType)
                val nestedFields = getFragmentFields(nestedRel.elementType)
                val projection = if (nestedFields == null) {
                    "$nestedAlias { .*, labels: labels($nestedAlias) }"
                } else {
                    val fieldMappings = nestedFields.joinToString(", ") { "$it: $nestedAlias.$it" }
                    "$nestedAlias { $fieldMappings, labels: labels($nestedAlias) }"
                }
                NestedRelInfo(
                    fieldName = nestedRel.fieldName,
                    alias = nestedAlias,
                    direction = Directions.directionString(nestedRel),
                    labelString = nestedLabels.joinToString(":"),
                    projection = projection,
                    isCollection = nestedRel.isCollection,
                )
            }
        )

        val result = grammar.nestedViewProjector.project(ctx)
        return if (result.expression == InlineNestedViewProjector.INLINE_MARKER) null else result
    }

    /**
     * Builds a recursive (self-referential) relationship pattern that expands to a fixed depth.
     *
     * For maxDepth=3 and field "subLocations" with relationship HAS_LOCATION, generates
     * nested pattern comprehensions where each level uses a depth-indexed alias
     * (subLocations_d1, subLocations_d2, subLocations_d3). At the terminal depth,
     * the recursive field becomes [] (for collections) or null (for single values).
     *
     * @param rootFieldName The alias for the parent node at this level
     * @param rel The recursive relationship model
     * @param targetAlias The alias for the top-level result
     * @param visitCounts Visit counter map for chain cycle detection in non-recursive nested rels
     */
    private fun buildRecursiveRelationshipPattern(
        rootFieldName: String,
        rel: RelationshipModel,
        targetAlias: String,
        visitCounts: Map<String, Int>
    ): String {
        val effectiveMaxDepth = context.depthOverrides[rel.fieldName] ?: rel.maxDepth

        if (effectiveMaxDepth == 0) {
            return if (rel.isCollection) {
                "[] AS $targetAlias"
            } else {
                "null AS $targetAlias"
            }
        }

        val direction = Directions.directionString(rel)
        val nestedViewModel = GraphViewModel.from(rel.elementType)
        val rootFragmentFields = getFragmentFields(nestedViewModel.rootFragment.fragmentType)

        val targetLabels = GraphTypeLabels.labelsForType(rel.elementType)
        val targetLabelString = targetLabels.joinToString(":")

        // Collect non-recursive relationships from the nested view
        val nonRecursiveRels = nestedViewModel.relationships.filter { !it.isRecursive && it.fieldName != rel.fieldName }

        fun buildAtDepth(depth: Int, parentVar: String): String {
            val depthAlias = rel.deriveTargetAliasAtDepth(depth)

            // Build root fragment field projections
            val fieldProjections = if (rootFragmentFields == null) {
                listOf(".*")
            } else {
                rootFragmentFields.map { "$it: $depthAlias.$it" }
            }

            // Build non-recursive relationship projections at this depth
            val nestedRelProjections = nonRecursiveRels.map { nestedRel ->
                val nestedDirection = Directions.directionString(nestedRel)
                val nestedTargetLabels = GraphTypeLabels.labelsForType(nestedRel.elementType)
                val nestedTargetLabelString = nestedTargetLabels.joinToString(":")
                val nestedTargetAlias = nestedRel.deriveTargetAlias()
                val nestedProjection = buildRelationshipProjection(nestedTargetAlias, nestedRel.elementType, visitCounts)

                if (nestedRel.isCollection) {
                    "${nestedRel.fieldName}: [\n                    ($depthAlias)${nestedDirection}(${nestedTargetAlias}:$nestedTargetLabelString) |\n                    $nestedProjection\n                ]"
                } else {
                    "${nestedRel.fieldName}: [\n                    ($depthAlias)${nestedDirection}(${nestedTargetAlias}:$nestedTargetLabelString) |\n                    $nestedProjection\n                ][0]"
                }
            }

            // Build the recursive field projection
            val recursiveFieldProjection = if (depth >= effectiveMaxDepth) {
                // Terminal depth: empty list or null
                if (rel.isCollection) {
                    "${rel.fieldName}: []"
                } else {
                    "${rel.fieldName}: null"
                }
            } else {
                // Recurse to next depth
                val innerPattern = buildAtDepth(depth + 1, depthAlias)
                "${rel.fieldName}: $innerPattern"
            }

            // Assemble all projections
            val allProjections = mutableListOf<String>()

            // Root fragment as nested object
            val rootFragmentFieldName = nestedViewModel.rootFragment.fieldName
            val rootFieldMappings = if (rootFragmentFields == null) {
                ".*"
            } else {
                rootFragmentFields.joinToString(",\n                    ") { "$it: $depthAlias.$it" }
            }
            allProjections.add("$rootFragmentFieldName: {\n                    $rootFieldMappings\n                }")

            allProjections.addAll(nestedRelProjections)
            allProjections.add(recursiveFieldProjection)

            val projection = "$depthAlias {\n                ${allProjections.joinToString(",\n                ")}\n            }"

            return "[($parentVar)${direction}($depthAlias:$targetLabelString) |\n            $projection\n        ]"
        }

        val pattern = buildAtDepth(1, rootFieldName)

        return if (rel.isCollection) {
            val sort = findSortForRelationship(targetAlias)
            if (sort != null) {
                val expr = emitTopLevelSort(rootFieldName, rel, targetAlias, pattern, sort)
                "$expr AS $targetAlias"
            } else {
                "$pattern AS $targetAlias"
            }
        } else {
            "$pattern[0] AS $targetAlias"
        }
    }

    /**
     * Builds a relationship fragment pattern that captures both relationship properties
     * and the target node.
     *
     * @param rootFieldName The alias for the root node
     * @param rel The relationship model (must be a relationship fragment)
     * @param fieldAlias The alias for this relationship field
     * @param visitCounts Visit counter map for cycle detection
     */
    private fun buildRelationshipFragmentPattern(
        rootFieldName: String,
        rel: RelationshipModel,
        fieldAlias: String,
        visitCounts: Map<String, Int> = emptyMap()
    ): String {
        require(rel.isRelationshipFragment) { "This method should only be called for relationship fragments" }

        // Build the relationship pattern with named relationship variable
        val relAlias = "${fieldAlias}_rel"
        val targetAlias = "${fieldAlias}_target"

        val direction = when (rel.direction) {
            Direction.OUTGOING -> "-[$relAlias:${rel.type}]->"
            Direction.INCOMING -> "<-[$relAlias:${rel.type}]-"
            Direction.UNDIRECTED -> "-[$relAlias:${rel.type}]-"
        }

        // Get labels for the target node type
        val targetNodeType = rel.targetNodeType!!
        val targetLabels = GraphTypeLabels.labelsForType(targetNodeType)
        if (targetLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for relationship fragment target ${targetNodeType.name}. @GraphFragment or @GraphView must specify at least one label.")
        }
        val targetLabelString = targetLabels.joinToString(":")

        // Build projection object with relationship properties + target
        val projectionFields = mutableListOf<String>()

        // Add relationship properties
        rel.relationshipProperties.forEach { propName ->
            projectionFields.add("$propName: $relAlias.$propName")
        }

        // Add target projection
        val targetFieldName = rel.targetFieldName!!
        val targetProjection = buildRelationshipProjection(targetAlias, targetNodeType, visitCounts)
        projectionFields.add("$targetFieldName: $targetProjection")

        val projection = "{\n            ${projectionFields.joinToString(",\n            ")}\n        }"

        val pattern = if (rel.isCollection) {
            // Collection: use pattern comprehension
            "[($rootFieldName)${direction}($targetAlias:$targetLabelString) |\n        $projection\n    ] AS $fieldAlias"
        } else {
            // Single: use [pattern][0] to get the first element
            "[($rootFieldName)${direction}($targetAlias:$targetLabelString) |\n        $projection\n    ][0] AS $fieldAlias"
        }

        return pattern
    }

    /**
     * Builds a multi-hop @GraphPath field as a CALL-subquery prolog that traverses the path,
     * projects only the final node, and de-duplicates it. Emitted uniformly across engines (Neo4j,
     * Memgraph, FalkorDB all accept `CALL { WITH root … RETURN collect(DISTINCT …) }`; pattern
     * comprehensions can't express a skip-intermediary projection with dedup inline).
     *
     * Returns `field AS field` for the projection WITH (so RETURN resolves), and registers the
     * prolog + a bridge variable on the context — the same shape as [CallSubqueryNestedViewProjector].
     */
    private fun buildPathPattern(
        rootFieldName: String,
        rel: RelationshipModel,
        targetAlias: String,
        visitCounts: Map<String, Int>,
    ): String {
        val targetLabels = GraphTypeLabels.labelsForType(rel.elementType)
        if (targetLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for @GraphPath target ${rel.elementType.name}. @GraphFragment or @GraphView must specify at least one label.")
        }
        val targetLabelString = targetLabels.joinToString(":")
        val matchPattern = buildPathMatchPattern(rootFieldName, rel, "($targetAlias:$targetLabelString)")
        val projection = buildRelationshipProjection(targetAlias, rel.elementType, visitCounts)

        // Guard against OPTIONAL MATCH null rows (mandatory for FalkorDB #1889); DISTINCT dedups
        // the far node reached via multiple intermediate paths.
        val collectExpr = "collect(DISTINCT CASE WHEN $targetAlias IS NOT NULL THEN $projection END)"
        val returnExpr = if (rel.isCollection) collectExpr else "head($collectExpr)"

        val prolog = buildString {
            appendLine("CALL {")
            appendLine("    WITH $rootFieldName")
            appendLine("    OPTIONAL MATCH $matchPattern")
            append("    RETURN $returnExpr AS $targetAlias\n}")
        }

        context.addProlog(prolog)
        context.addBridgeVariables(listOf(targetAlias))
        return "$targetAlias AS $targetAlias"
    }

    /**
     * Builds a per-root aggregate field (@Count / @Aggregate). COUNT is an inline
     * `size([pattern | 1])` (portable; sidesteps the `count()`-in-CALL form Memgraph rejects);
     * SUM/AVG/MIN/MAX are a CALL subquery returning the scalar — uniform across Neo4j, Memgraph,
     * and FalkorDB (all verified), registered as a prolog + bridge variable like a path.
     */
    private fun buildAggregateProjection(rootFieldName: String, agg: AggregateFieldModel): String {
        val arrow = Directions.hopArrow(agg.type, agg.direction)
        val nodeVar = "${agg.fieldName}_x"

        if (agg.function == AggregateFunction.COUNT) {
            return "size([($rootFieldName)$arrow($nodeVar) | 1]) AS ${agg.fieldName}"
        }

        val property = agg.property
            ?: throw IllegalArgumentException("@Aggregate(${agg.function}) on '${agg.fieldName}' requires a property")
        val func = agg.function.name.lowercase()
        val prolog = buildString {
            appendLine("CALL {")
            appendLine("    WITH $rootFieldName")
            appendLine("    OPTIONAL MATCH ($rootFieldName)$arrow($nodeVar)")
            append("    RETURN $func($nodeVar.$property) AS ${agg.fieldName}\n}")
        }
        context.addProlog(prolog)
        context.addBridgeVariables(listOf(agg.fieldName))
        return "${agg.fieldName} AS ${agg.fieldName}"
    }

    /**
     * Renders a @GraphPath match pattern from the root through each hop. Intermediate nodes are
     * anonymous (optionally labelled per [HopModel.intermediateLabel]); [finalNode] is the rendered
     * final node, e.g. `(directors:Director)` for projection or `(:Director)` for an existence check.
     */
    private fun buildPathMatchPattern(rootAlias: String, rel: RelationshipModel, finalNode: String): String =
        buildString {
            append("($rootAlias)")
            rel.hops.forEachIndexed { i, hop ->
                append(Directions.hopArrow(hop.type, hop.direction))
                if (i == rel.hops.lastIndex) {
                    append(finalNode)
                } else {
                    val label = hop.intermediateLabel?.let { ":$it" } ?: ""
                    append("($label)")
                }
            }
        }

    /**
     * Builds the projection for a relationship target.
     * If it's a GraphFragment, returns the fields projection.
     * If it's a GraphView, recursively builds nested structure with cycle detection.
     * For polymorphic types (sealed classes), uses .* to capture all properties.
     *
     * @param varName The Cypher variable name for this target
     * @param targetType The Java class of the target
     * @param visitCounts Visit counter map for cycle detection
     */
    private fun buildRelationshipProjection(
        varName: String,
        targetType: Class<*>,
        visitCounts: Map<String, Int> = emptyMap()
    ): String {
        // Check if it's a GraphView (nested)
        val viewAnnotation = targetType.getAnnotation(GraphView::class.java)
        if (viewAnnotation != null) {
            // Update visit counts for this view type
            val targetClassName = targetType.name
            val updatedCounts = visitCounts + (targetClassName to (visitCounts.getOrDefault(targetClassName, 0) + 1))

            // Recursively handle nested GraphView
            val nestedViewModel = GraphViewModel.from(targetType)
            return buildNestedViewProjection(varName, nestedViewModel, updatedCounts)
        }

        // It's a GraphFragment, project its fields with explicit mapping
        val fields = getFragmentFields(targetType)

        // For polymorphic types (sealed classes), use .* to get all fields
        if (fields == null) {
            return """$varName {
            .*,
            labels: labels($varName)
        }"""
        }

        val fieldMappings = fields.joinToString(",\n            ") { "$it: $varName.$it" }
        // Include labels for polymorphic deserialization support
        return """$varName {
            $fieldMappings,
            labels: labels($varName)
        }"""
    }

    /**
     * Builds a nested projection for a GraphView within a relationship.
     *
     * Handles cycle detection: when a nested relationship targets a view type that's
     * already been visited, checks the relationship's maxDepth against the visit count
     * to decide whether to expand further or terminate.
     *
     * @param varName The Cypher variable name for this nested view
     * @param nestedViewModel The view model for the nested GraphView
     * @param visitCounts Visit counter map for cycle detection
     */
    private fun buildNestedViewProjection(
        varName: String,
        nestedViewModel: GraphViewModel,
        visitCounts: Map<String, Int> = emptyMap()
    ): String {
        val fields = mutableListOf<String>()

        // Add the root fragment as a nested object (not flattened)
        val rootFragmentFieldName = nestedViewModel.rootFragment.fieldName
        val rootFragmentFields = getFragmentFields(nestedViewModel.rootFragment.fragmentType)
        val rootFieldMappings = if (rootFragmentFields == null) {
            // Polymorphic type - use .*
            ".*"
        } else {
            rootFragmentFields.joinToString(",\n                ") { "$it: $varName.$it" }
        }
        fields.add("$rootFragmentFieldName: {\n                $rootFieldMappings\n            }")

        // Add nested relationship fields
        nestedViewModel.relationships.forEach { nestedRel ->
            val nestedDirection = Directions.directionString(nestedRel)

            val nestedTargetLabels = GraphTypeLabels.labelsForType(nestedRel.elementType)
            if (nestedTargetLabels.isEmpty()) {
                throw IllegalArgumentException("No labels defined for nested relationship target ${nestedRel.elementType.name}. @GraphFragment or @GraphView must specify at least one label.")
            }
            val nestedTargetLabelString = nestedTargetLabels.joinToString(":")

            // Derive the target alias for the nested relationship
            val nestedTargetAlias = nestedRel.deriveTargetAlias()

            // Check for chain cycle: target is a @GraphView that's already been visited
            val targetClassName = nestedRel.elementType.name
            val isGraphView = nestedRel.elementType.getAnnotation(GraphView::class.java) != null
            if (isGraphView && targetClassName in visitCounts) {
                val currentCount = visitCounts[targetClassName]!!
                val effectiveMaxDepth = context.depthOverrides[nestedRel.fieldName] ?: nestedRel.maxDepth
                if (currentCount >= effectiveMaxDepth) {
                    // Terminate: exceeded max depth for this chain cycle
                    if (nestedRel.isCollection) {
                        fields.add("\n            ${nestedRel.fieldName}: []")
                    } else {
                        fields.add("\n            ${nestedRel.fieldName}: null")
                    }
                    return@forEach
                }
            }

            // Build projection for the nested target - this handles both GraphView and fragment targets
            // For GraphView targets, it recursively builds the full structure (root + relationships)
            // For fragment targets, it builds field mappings
            val nestedProjection = buildRelationshipProjection(nestedTargetAlias, nestedRel.elementType, visitCounts)

            // Use [0] suffix for single-object relationships to return object instead of array
            if (nestedRel.isCollection) {
                // Collection: check if there's a sort for this nested relationship
                val listComprehension = """[
                ($varName)${nestedDirection}(${nestedTargetAlias}:$nestedTargetLabelString) |
                $nestedProjection
            ]"""
                // Check for nested sort (e.g., "raisedBy_worksFor" where varName is "raisedBy")
                val sort = findSortForNestedRelationship(varName, nestedRel.fieldName)
                val wrappedPattern = wrapWithNestedSortIfNeeded(listComprehension, sort)
                fields.add("\n            ${nestedRel.fieldName}: $wrappedPattern")
            } else {
                // Single: use [0] suffix
                val nestedPattern = """[
                ($varName)${nestedDirection}(${nestedTargetAlias}:$nestedTargetLabelString) |
                $nestedProjection
            ][0]"""
                fields.add("\n            ${nestedRel.fieldName}: $nestedPattern")
            }
        }

        return """$varName {
            ${fields.joinToString(",\n            ")}
        }"""
    }
}