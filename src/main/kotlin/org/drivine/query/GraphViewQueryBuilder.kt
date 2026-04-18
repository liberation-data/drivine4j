package org.drivine.query

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.grammar.*
import org.drivine.query.sort.*

/**
 * Builds Cypher queries for GraphView classes.
 *
 * Supports recursive (self-referential) relationships and chain cycle detection.
 * Self-referential relationships generate nested pattern comprehensions to a configurable
 * depth. Chain cycles (A → B → C → A) are detected via a visit counter and terminated
 * based on the closing relationship's maxDepth.
 */
class GraphViewQueryBuilder(
    private val viewModel: GraphViewModel,
    private val grammar: CypherGrammar,
) : GraphObjectQueryBuilder {

    private val sortEmitter: CollectionSortEmitter = grammar.collectionSortEmitter

    override val nodeAlias: String = viewModel.rootFragment.fieldName

    /**
     * Current collection sorts for this query build operation.
     * Stored as instance variable to avoid threading through many method calls.
     */
    private var currentCollectionSorts: List<CollectionSortSpec> = emptyList()

    /**
     * Accumulated CALL { } prologs for the current query build.
     * Emitted between MATCH and WHERE.
     */
    private val currentPrologs: MutableList<String> = mutableListOf()

    /**
     * Bridge variables from filter prologs that need a WITH clause between CALL and WHERE.
     * E.g. CALL { ... RETURN count(*) AS _ec0 } WITH rootAlias, _ec0 WHERE _ec0 > 0
     */
    private val currentBridgeVariables: MutableList<String> = mutableListOf()

    /**
     * Current depth overrides for recursive relationships.
     * Keyed by relationship field name, overrides the annotation's maxDepth at query time.
     */
    private var currentDepthOverrides: Map<String, Int> = emptyMap()

    /**
     * Builds a Cypher query to load a GraphView with its root fragment and relationships.
     *
     * The query structure:
     * 1. MATCH the root fragment node with optional WHERE clause
     * 2. WITH the root node, collect relationships using pattern comprehension
     * 3. RETURN the assembled object
     * 4. Optional ORDER BY clause
     *
     * For non-nullable, non-collection relationships, adds EXISTS checks to the WHERE clause
     * to filter out root nodes that don't have the required relationships.
     *
     * @param whereClause Optional WHERE clause conditions (without the WHERE keyword)
     * @param orderByClause Optional ORDER BY clause (without the ORDER BY keywords)
     * @return The generated Cypher query
     */
    override fun buildQuery(whereClause: String?, orderByClause: String?): String {
        val rootFragmentModel = viewModel.rootFragment
        val rootFieldName = rootFragmentModel.fieldName

        // Get all labels from the fragment
        val fragmentLabels = getFragmentLabels(rootFragmentModel.fragmentType)
        if (fragmentLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for root fragment ${rootFragmentModel.fragmentType.name}. @GraphFragment must specify at least one label.")
        }

        // Build the MATCH clause with all labels
        val labelString = fragmentLabels.joinToString(":")
        val matchClause = "MATCH ($rootFieldName:$labelString)"

        // Build the WHERE clause - combine user-provided clause with EXISTS checks for required relationships
        val requiredRelationshipChecks = buildRequiredRelationshipChecks(rootFieldName)
        val whereSection = buildWhereSection(whereClause, requiredRelationshipChecks)

        // Initialize visit counts with the root view type
        val initialVisitCounts = mapOf(viewModel.className to 1)

        // Build the WITH clause with all projections
        val withSections = mutableListOf<String>()

        // Add root fragment projection
        val rootFragmentFields = getFragmentFields(rootFragmentModel.fragmentType)
        val rootComment = "// ${capitalize(rootFieldName)}"
        val rootProjection = buildFragmentProjectionWithMapping(rootFieldName, rootFieldName, rootFragmentFields)
        withSections.add("    $rootComment\n    $rootProjection AS $rootFieldName")

        // Add relationship projections
        viewModel.relationships.forEach { rel ->
            val comment = buildRelationshipComment(rel)
            val targetAlias = rel.deriveTargetAlias()
            val projection = buildRelationshipPattern(rootFieldName, rel, targetAlias, initialVisitCounts)
            withSections.add("    $comment\n    $projection")
        }

        // Insert CALL { } prologs between MATCH and WHERE.
        // If bridge variables exist (from filtered existence checks on openCypher),
        // emit a WITH clause to carry them into WHERE scope.
        val prologSection = if (currentPrologs.isNotEmpty()) {
            val prologs = "\n" + currentPrologs.joinToString("\n")
            if (currentBridgeVariables.isNotEmpty()) {
                val bridgeWith = "\nWITH $rootFieldName, ${currentBridgeVariables.joinToString(", ")}"
                "$prologs$bridgeWith"
            } else {
                prologs
            }
        } else {
            ""
        }

        val withClause = "\n\nWITH\n" + withSections.joinToString(",\n\n")

        // Build the RETURN clause
        val returnFields = mutableListOf<String>()
        returnFields.add("    ${rootFieldName}: $rootFieldName")
        viewModel.relationships.forEach { rel ->
            val targetAlias = rel.deriveTargetAlias()
            returnFields.add("    ${rel.fieldName}: $targetAlias")
        }

        val returnClause = """

RETURN {
${returnFields.joinToString(",\n")}
} AS result"""

        // Add ORDER BY clause if provided
        val orderBySection = if (orderByClause != null) {
            "\nORDER BY $orderByClause"
        } else {
            ""
        }

        return matchClause + prologSection + whereSection + withClause + returnClause + orderBySection
    }

    /**
     * Builds a Cypher query with collection sorts using apoc.coll.sortMaps().
     *
     * Collection sorts are applied to relationship collections, enabling sorting like:
     * - `assignedTo.name` - sort the assignedTo collection by name
     * - `raisedBy_worksFor.name` - sort the nested worksFor collection inside raisedBy by name
     *
     * @param whereClause Optional WHERE clause conditions (without the WHERE keyword)
     * @param orderByClause Optional ORDER BY clause (without the ORDER BY keywords)
     * @param collectionSorts List of collection sort specifications
     * @return The generated Cypher query
     */
    override fun buildQuery(
        whereClause: String?,
        orderByClause: String?,
        collectionSorts: List<CollectionSortSpec>,
        externalPrologs: List<String>,
        externalBridgeVars: List<String>,
    ): String {
        currentCollectionSorts = collectionSorts
        currentPrologs.clear()
        currentPrologs.addAll(externalPrologs)
        currentBridgeVariables.clear()
        currentBridgeVariables.addAll(externalBridgeVars)
        try {
            return buildQuery(whereClause, orderByClause)
        } finally {
            currentCollectionSorts = emptyList()
            currentPrologs.clear()
            currentBridgeVariables.clear()
        }
    }

    fun buildQuery(
        whereClause: String?,
        orderByClause: String?,
        collectionSorts: List<CollectionSortSpec>,
        depthOverrides: Map<String, Int>,
        externalPrologs: List<String> = emptyList(),
        externalBridgeVars: List<String> = emptyList(),
    ): String {
        currentCollectionSorts = collectionSorts
        currentDepthOverrides = depthOverrides
        currentPrologs.clear()
        currentPrologs.addAll(externalPrologs)
        currentBridgeVariables.clear()
        currentBridgeVariables.addAll(externalBridgeVars)
        try {
            return buildQuery(whereClause, orderByClause)
        } finally {
            currentCollectionSorts = emptyList()
            currentDepthOverrides = emptyMap()
            currentPrologs.clear()
            currentBridgeVariables.clear()
        }
    }

    /**
     * Finds a collection sort spec for a direct relationship (e.g., "assignedTo").
     */
    private fun findSortForRelationship(relationshipName: String): CollectionSortSpec? {
        return currentCollectionSorts.find {
            !it.isNested() && it.relationshipPath == relationshipName
        }
    }

    /**
     * Finds a collection sort spec for a nested relationship (e.g., "worksFor" inside "raisedBy").
     */
    private fun findSortForNestedRelationship(parentAlias: String, nestedRelationshipName: String): CollectionSortSpec? {
        return currentCollectionSorts.find { sort ->
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
        val direction = buildDirectionString(rel)
        val targetLabels = getLabelsForType(rel.elementType)
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
        emission.prolog?.let { currentPrologs.add(it) }
        return emission.projectionExpression
    }

    override fun buildIdWhereClause(idParamName: String): String {
        val rootFragmentModel = viewModel.rootFragment
        val fragmentModel = FragmentModel.from(rootFragmentModel.fragmentType)
        val nodeIdField = fragmentModel.nodeIdField
            ?: throw IllegalArgumentException("GraphView root fragment ${rootFragmentModel.fragmentType.name} does not have a @GraphNodeId field")
        val rootFieldName = rootFragmentModel.fieldName
        return "$rootFieldName.$nodeIdField = \$$idParamName"
    }

    override fun buildDeleteQuery(whereClause: String?, prologs: List<String>, bridgeVariables: List<String>): String {
        val rootFragmentModel = viewModel.rootFragment
        val rootFieldName = rootFragmentModel.fieldName

        val fragmentLabels = getFragmentLabels(rootFragmentModel.fragmentType)
        if (fragmentLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for root fragment ${rootFragmentModel.fragmentType.name}. @GraphFragment must specify at least one label.")
        }

        val labelString = fragmentLabels.joinToString(":")
        val matchClause = "MATCH ($rootFieldName:$labelString)"

        val prologSection = if (prologs.isNotEmpty()) {
            val prologBlock = "\n" + prologs.joinToString("\n")
            if (bridgeVariables.isNotEmpty()) {
                "$prologBlock\nWITH $rootFieldName, ${bridgeVariables.joinToString(", ")}"
            } else {
                prologBlock
            }
        } else {
            ""
        }

        val whereSection = if (whereClause != null) {
            "\nWHERE $whereClause"
        } else {
            ""
        }

        return """
            |$matchClause$prologSection$whereSection
            |DETACH DELETE $rootFieldName
            |RETURN count(*) AS deleted
        """.trimMargin()
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
     * Builds a Cypher direction string for a relationship.
     */
    private fun buildDirectionString(rel: RelationshipModel): String {
        return when (rel.direction) {
            Direction.OUTGOING -> "-[:${rel.type}]->"
            Direction.INCOMING -> "<-[:${rel.type}]-"
            Direction.UNDIRECTED -> "-[:${rel.type}]-"
        }
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
        if (rel.isRelationshipFragment) {
            // Relationship fragment pattern: capture both relationship properties and target node
            return buildRelationshipFragmentPattern(rootFieldName, rel, targetAlias, visitCounts)
        }

        // Check for direct self-reference
        if (rel.isRecursive) {
            return buildRecursiveRelationshipPattern(rootFieldName, rel, targetAlias, visitCounts)
        }

        // Direct target reference pattern (existing behavior)
        val direction = buildDirectionString(rel)

        // Get all labels for the target type
        val targetLabels = getLabelsForType(rel.elementType)
        if (targetLabels.isEmpty()) {
            throw IllegalArgumentException("No labels defined for relationship target ${rel.elementType.name}. @GraphFragment or @GraphView must specify at least one label.")
        }
        val targetLabelString = targetLabels.joinToString(":")

        // Check for chain cycle: target is a @GraphView that's already been visited
        val targetClassName = rel.elementType.name
        val isGraphView = rel.elementType.getAnnotation(GraphView::class.java) != null
        if (isGraphView && targetClassName in visitCounts) {
            val currentCount = visitCounts[targetClassName]!!
            val effectiveMaxDepth = currentDepthOverrides[rel.fieldName] ?: rel.maxDepth
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
                projectorResult.prolog?.let { currentPrologs.add(it) }
                currentBridgeVariables.addAll(projectorResult.bridgeVariables)
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
                val nestedLabels = getLabelsForType(nestedRel.elementType)
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
                    direction = buildDirectionString(nestedRel),
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
        val effectiveMaxDepth = currentDepthOverrides[rel.fieldName] ?: rel.maxDepth

        if (effectiveMaxDepth == 0) {
            return if (rel.isCollection) {
                "[] AS $targetAlias"
            } else {
                "null AS $targetAlias"
            }
        }

        val direction = buildDirectionString(rel)
        val nestedViewModel = GraphViewModel.from(rel.elementType)
        val rootFragmentFields = getFragmentFields(nestedViewModel.rootFragment.fragmentType)

        val targetLabels = getLabelsForType(rel.elementType)
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
                val nestedDirection = buildDirectionString(nestedRel)
                val nestedTargetLabels = getLabelsForType(nestedRel.elementType)
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
        val targetLabels = getLabelsForType(targetNodeType)
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
            val nestedDirection = buildDirectionString(nestedRel)

            val nestedTargetLabels = getLabelsForType(nestedRel.elementType)
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
                val effectiveMaxDepth = currentDepthOverrides[nestedRel.fieldName] ?: nestedRel.maxDepth
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

    /**
     * Builds EXISTS checks for non-nullable, non-collection relationships.
     * These ensure the query only returns root nodes that have the required relationships.
     *
     * @param rootFieldName The alias for the root node
     * @return List of EXISTS condition strings, or empty list if no required relationships
     */
    private fun buildRequiredRelationshipChecks(rootFieldName: String): List<String> {
        return viewModel.relationships
            .filter { rel -> !rel.isNullable && !rel.isCollection }
            .map { rel ->
                val direction = buildDirectionString(rel)

                // Get labels for the target - for relationship fragments, use the target node type
                val targetType = if (rel.isRelationshipFragment) rel.targetNodeType!! else rel.elementType
                val targetLabels = getLabelsForType(targetType)
                val targetLabelString = targetLabels.joinToString(":")

                grammar.existenceCheck(rootFieldName, direction, targetLabelString)
            }
    }

    /**
     * Builds the complete WHERE section combining user-provided conditions
     * and required relationship checks.
     *
     * @param userWhereClause User-provided WHERE conditions (may be null)
     * @param requiredRelChecks EXISTS checks for required relationships
     * @return The complete WHERE section string (including "WHERE" keyword), or empty string
     */
    private fun buildWhereSection(userWhereClause: String?, requiredRelChecks: List<String>): String {
        val conditions = mutableListOf<String>()

        // Add user-provided conditions first
        if (userWhereClause != null) {
            conditions.add(userWhereClause)
        }

        // Add required relationship checks
        conditions.addAll(requiredRelChecks)

        return if (conditions.isEmpty()) {
            ""
        } else {
            "\nWHERE " + conditions.joinToString("\n  AND ")
        }
    }

    /**
     * Gets labels from a fragment class type.
     */
    private fun getFragmentLabels(fragmentType: Class<*>): List<String> {
        val annotation = fragmentType.getAnnotation(org.drivine.annotation.NodeFragment::class.java)
        return annotation?.labels?.toList() ?: emptyList()
    }

    /**
     * Gets labels for a type (could be GraphFragment or GraphView).
     */
    private fun getLabelsForType(type: Class<*>): List<String> {
        // Check if it's a GraphFragment
        val fragmentAnnotation = type.getAnnotation(org.drivine.annotation.NodeFragment::class.java)
        if (fragmentAnnotation != null) {
            return fragmentAnnotation.labels.toList()
        }

        // Check if it's a GraphView (use the root fragment's labels)
        val viewAnnotation = type.getAnnotation(org.drivine.annotation.GraphView::class.java)
        if (viewAnnotation != null) {
            val viewModel = GraphViewModel.from(type)
            return getFragmentLabels(viewModel.rootFragment.fragmentType)
        }

        return emptyList()
    }

    companion object {
        fun forView(viewClass: Class<*>, grammar: CypherGrammar): GraphViewQueryBuilder {
            val viewModel = GraphViewModel.from(viewClass)
            return GraphViewQueryBuilder(viewModel, grammar)
        }

        fun forView(viewClass: kotlin.reflect.KClass<*>, grammar: CypherGrammar): GraphViewQueryBuilder {
            return forView(viewClass.java, grammar)
        }
    }
}