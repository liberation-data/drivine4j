package org.drivine.backend

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.manager.CascadeType
import org.drivine.manager.NodeRef
import org.drivine.manager.NullPolicy
import org.drivine.manager.PersistenceManager
import org.drivine.manager.RelateMode
import org.drivine.manager.RemovedTargets
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.model.Stamps
import org.drivine.query.EdgeStatements
import org.drivine.query.FragmentQueryBuilder
import org.drivine.query.FullTextSearchPlanner
import org.drivine.query.GraphObjectQueryBuilder
import org.drivine.query.GraphViewQueryBuilder
import org.drivine.query.MergeStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.SaveStatement
import org.drivine.query.SaveStatementBuilder
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.StampWrite
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.VectorSearchPlanner
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.KeysetPlanner
import org.drivine.query.dsl.OrderClauseResult
import org.drivine.query.grammar.CypherGrammar
import org.neo4j.driver.exceptions.TransientException

/** The backend of the engines that speak Cypher: Neo4j, FalkorDB, Memgraph and Neptune, told apart by [grammar]. */
internal class CypherBackend(
    private val persistenceManager: PersistenceManager,
    private val grammar: CypherGrammar,
    objectMapper: ObjectMapper,
) : GraphBackend {

    override val cypherGrammar: CypherGrammar
        get() = grammar

    override val supportsOrphanDelete: Boolean
        get() = grammar.supportsOrphanDelete

    override val orphanDeleteLimit: String?
        get() = grammar.orphanDeleteLimit

    override val supportsIndexBackedOrdering: Boolean
        get() = grammar.supportsIndexBackedOrdering

    /**
     * Reads the property keys a stored node has, straight from the store, so a save can clear the
     * stale `@PropertyBag` keys of an object the manager did not load.
     */
    override val storedKeys = object : StoredPropertyKeys {
        override fun of(labels: String, idProperty: String, id: Any): Set<String> =
            persistenceManager.maybeGetOne(
                QuerySpecification
                    .withStatement("MATCH (n:$labels {$idProperty: \$id}) RETURN keys(n)")
                    .bind(mapOf("id" to id))
                    .transform(List::class.java)
            )?.map { it.toString() }?.toSet().orEmpty()

        override fun ownedLabels(labels: String, idProperty: String, id: Any, property: String): List<String> =
            persistenceManager.maybeGetOne(
                QuerySpecification
                    .withStatement("MATCH (n:$labels {$idProperty: \$id}) RETURN coalesce(n.`$property`, [])")
                    .bind(mapOf("id" to id))
                    .transform(List::class.java)
            )?.map { it.toString() }.orEmpty()
    }

    private val saves = SaveStatementBuilder(objectMapper, grammar, storedKeys)

    private fun builder(graphClass: Class<*>) = GraphObjectQueryBuilder.forClass(graphClass, grammar)

    // ----- Reading -----

    override fun loadAll(graphClass: Class<*>, where: String?): GraphStatement =
        GraphStatement(if (where == null) builder(graphClass).buildQuery() else builder(graphClass).buildQuery(where, null))

    override fun loadAll(graphClass: Class<*>, query: GraphQuerySpec<*>): LoadPlan {
        val builder = builder(graphClass)
        val ctx = queryContext(graphClass, query, supportsSeek = true)

        // Process order clauses - separate root ordering from collection sorting
        val relationshipNames = ctx.viewModel?.relationships?.map { it.fieldName }?.toSet() ?: emptySet()
        val orderResult = if (query.orders.isNotEmpty()) {
            CypherGenerator.processOrders(query.orders, relationshipNames, ctx.viewModel)
        } else {
            OrderClauseResult(null, emptyList())
        }

        require(query.seekValues.isEmpty() || query.skip == null) {
            "seek and skip cannot be used together"
        }
        val keysetPlan = if (query.seekValues.isNotEmpty()) {
            KeysetPlanner.plan(
                orderResult.rootOrders,
                query.seekValues,
                orderResult.collectionSorts.size,
                guardAgainstNulls = grammar.indexesExcludeNulls,
            )
        } else {
            null
        }
        val effectiveWhereClause = listOfNotNull(ctx.whereClause, keysetPlan?.predicate)
            .joinToString(" AND ") { "($it)" }
            .takeIf { it.isNotEmpty() }
        keysetPlan?.bindings?.keys?.forEach { reserved ->
            require(reserved !in ctx.bindings) {
                "Keyset cursor parameter \$$reserved collides with a where-clause binding"
            }
        }
        val effectiveBindings = ctx.bindings + (keysetPlan?.bindings ?: emptyMap())

        val baseQuery = if (query.depthOverrides.isNotEmpty() && builder is GraphViewQueryBuilder) {
            builder.buildQuery(effectiveWhereClause, orderResult.orderByClause, orderResult.collectionSorts, query.depthOverrides, ctx.prologs, ctx.bridgeVariables)
        } else {
            builder.buildQuery(effectiveWhereClause, orderResult.orderByClause, orderResult.collectionSorts, ctx.prologs, ctx.bridgeVariables)
        }
        return LoadPlan(paginated(baseQuery, effectiveBindings, query.skip, query.limit), orderResult.rootOrders, keysetPlan != null)
    }

    override fun load(graphClass: Class<*>): GraphStatement {
        val builder = builder(graphClass)
        return GraphStatement(builder.buildQuery(builder.buildIdWhereClause(GraphBackend.ID_PARAM)))
    }

    override fun count(graphClass: Class<*>, where: String?): GraphStatement =
        GraphStatement(if (where == null) builder(graphClass).buildCountQuery() else builder(graphClass).buildCountQuery(where))

    override fun count(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement {
        val ctx = queryContext(graphClass, query)
        return GraphStatement(builder(graphClass).buildCountQuery(ctx.whereClause, ctx.prologs, ctx.bridgeVariables), ctx.bindings)
    }

    override fun nearest(
        graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, vector: List<Float>,
        topK: Int, threshold: Double?, searchK: Int?, partitionLabel: String?,
    ): ScoredSearchPlan =
        if (query == null) {
            VectorSearchPlanner.plan(graphClass, property, vector, topK, threshold, grammar, searchK, partitionLabel)
        } else {
            VectorSearchPlanner.planFiltered(graphClass, query, vector, topK, threshold, grammar, searchK, partitionLabel)
        }

    override fun matching(graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, text: String, topK: Int, threshold: Double): ScoredSearchPlan =
        if (query == null) {
            FullTextSearchPlanner.plan(graphClass, property, text, topK, threshold, grammar)
        } else {
            FullTextSearchPlanner.planFiltered(graphClass, query, text, topK, threshold, grammar)
        }

    // ----- Deleting -----

    override fun delete(graphClass: Class<*>, where: String?, cascade: CascadeType): GraphStatement {
        val builder = builder(graphClass)
        val idCondition = builder.buildIdWhereClause(GraphBackend.ID_PARAM)
        val fullWhereClause = if (where != null) "$idCondition AND $where" else idCondition
        return GraphStatement(
            if (cascade == CascadeType.NONE) builder.buildDeleteQuery(fullWhereClause) else builder.buildCascadeDeleteQuery(fullWhereClause, cascade)
        )
    }

    override fun deleteAll(graphClass: Class<*>, where: String?): GraphStatement =
        GraphStatement(builder(graphClass).buildDeleteQuery(where))

    override fun deleteAll(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement {
        val ctx = queryContext(graphClass, query)
        return GraphStatement(builder(graphClass).buildDeleteQuery(ctx.whereClause, ctx.prologs, ctx.bridgeVariables), ctx.bindings)
    }

    // ----- Saving -----

    override fun save(
        obj: Any,
        checked: Boolean,
        nullPolicy: NullPolicy,
        rootWriteFields: Set<String>?,
        replaced: (RelationshipModel) -> RemovedTargets?,
        before: JsonNode?,
        after: JsonNode?,
        createsRoot: Boolean,
    ): SaveStatement = saves.build(obj, checked, nullPolicy, rootWriteFields, replaced, before, after, createsRoot)

    // An absent stamp is read as '' so a node without one still gives a row on every engine.
    override fun foundStamp(root: StampWrite): GraphStatement = GraphStatement(
        "MATCH (n:${root.labels} {${root.idProperty}: \$id}) RETURN coalesce(n.${Stamps.QUOTED}, '')",
        mapOf("id" to root.id),
    )

    /**
     * A conflict or a deadlock, which the Neo4j driver, used for Neo4j and Memgraph, reports as
     * transient. FalkorDB runs one write at a time and never turns one away.
     */
    override fun contended(failure: Throwable): Boolean = failure is TransientException

    // ----- Relationships on their own -----

    override fun relate(from: NodeRef, to: NodeRef, type: String, properties: Map<String, Any?>, mode: RelateMode): GraphStatement =
        EdgeStatements.relate(from, to, type, properties, mode).graphStatement()

    override fun unrelate(from: NodeRef, to: NodeRef, type: String): GraphStatement =
        EdgeStatements.unrelate(from, to, type).graphStatement()

    override fun unrelateAll(from: NodeRef, type: String, direction: Direction): GraphStatement =
        EdgeStatements.unrelateAll(from, type, direction).graphStatement()

    override fun related(from: NodeRef, type: String, direction: Direction, targetClass: Class<*>): GraphStatement {
        val model = FragmentModel.from(targetClass)
        val match = EdgeStatements.relatedMatch(from, type, direction, model.labels)
        return GraphStatement(FragmentQueryBuilder(model).buildQueryMatching(match.statement), match.bindings)
    }

    private fun MergeStatement.graphStatement() = GraphStatement(statement, bindings)

    // ----- The query DSL -----

    /** A where clause with its bindings, and the CALL prologs and bridge variables it needs on an openCypher engine. */
    private class QueryContext(
        val viewModel: GraphViewModel?,
        val whereClause: String?,
        val bindings: Map<String, Any?>,
        val prologs: List<String> = emptyList(),
        val bridgeVariables: List<String> = emptyList(),
    )

    /**
     * Seeks are only meaningful where there is an ORDER BY to walk, so they are rejected here unless
     * the caller opts in — otherwise a count or deleteAll would silently ignore the cursor and
     * operate on the whole matching set, which for a delete is destructive.
     */
    private fun queryContext(graphClass: Class<*>, query: GraphQuerySpec<*>, supportsSeek: Boolean = false): QueryContext {
        require(supportsSeek || query.seekValues.isEmpty()) {
            "seek is only supported by loadAll; this operation would ignore the cursor"
        }
        val viewModel = if (graphClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(graphClass) else null
        val whereResult = if (query.conditions.isNotEmpty()) CypherGenerator.buildWhereClause(query.conditions, viewModel, grammar) else null
        val bindings = CypherGenerator.extractBindings(query.conditions, viewModel)
        return QueryContext(
            viewModel, whereResult?.whereClause, bindings,
            whereResult?.prologs ?: emptyList(),
            whereResult?.bridgeVariables ?: emptyList(),
        )
    }

    /**
     * Appends parameterised SKIP/LIMIT clauses, in the order Cypher requires. SKIP and LIMIT are
     * bound as parameters rather than inlined so the query text is stable across pages.
     */
    private fun paginated(query: String, bindings: Map<String, Any?>, skip: Int?, limit: Int?): GraphStatement {
        if (skip == null && limit == null) return GraphStatement(query, bindings)
        val merged = bindings.toMutableMap()
        val sb = StringBuilder(query)
        if (skip != null) {
            sb.append("\nSKIP \$$SKIP_PARAM")
            merged[SKIP_PARAM] = skip
        }
        if (limit != null) {
            sb.append("\nLIMIT \$$LIMIT_PARAM")
            merged[LIMIT_PARAM] = limit
        }
        return GraphStatement(sb.toString(), merged)
    }

    private companion object {
        const val SKIP_PARAM = "_skip"
        const val LIMIT_PARAM = "_limit"
    }
}
