package org.drivine.backend

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.manager.BatchSaveOperations
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
import org.drivine.query.GroupQuery
import org.drivine.query.GroupQueryBuilder
import org.drivine.query.MergeStatement
import org.drivine.query.PatchRow
import org.drivine.query.PatchStatementBuilder
import org.drivine.query.QuerySpecification
import org.drivine.query.SaveRequest
import org.drivine.query.SaveStatement
import org.drivine.query.SaveStatementBuilder
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.StampWrite
import org.drivine.query.Stamping
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.VectorSearchPlanner
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.KeysetPlanner
import org.drivine.query.dsl.OrderClauseResult
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.grammar.CypherGrammar
import org.drivine.query.savedByUnwind
import org.drivine.session.SessionManager
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

    /** Builds a whole save as one statement. */
    private val saves = SaveStatementBuilder(objectMapper, grammar, storedKeys)

    /** The batches of a `saveAll`. Nothing is tracked: a batch writes every field of what it is given, or those it is told to. */
    private val batches = BatchSaveOperations(
        objectMapper, SessionManager.untracked(objectMapper), BatchSaveOperations.UNWIND_CHUNK_SIZE, grammar, storedKeys, Stamping(checked = false),
    )

    /** Builds the statement of a patch of many. */
    private val patches = PatchStatementBuilder(grammar)

    /** The builder of the statements that read and delete [graphClass]: a fragment's or a view's. */
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
            CypherGenerator.processOrders(query.orders, relationshipNames, ctx.viewModel, grammar)
        } else {
            OrderClauseResult(null, emptyList())
        }

        require(query.seekValues.isEmpty() || query.skip == null) {
            "seek and skip cannot be used together"
        }
        val keysetPlan = if (query.seekValues.isNotEmpty()) {
            KeysetPlanner.plan(
                KeysetPlanner.propertyOrders(orderResult.rootOrders),
                query.seekValues,
                orderResult.collectionSorts.size,
                guardAgainstNulls = grammar.indexesExcludeNulls,
            )
        } else {
            null
        }
        // Joined as they stand. Each is a list of conditions joined by AND, with any OR in parentheses
        // of its own, so neither needs more around it: and FalkorDB 6 reads a condition in parentheses
        // it does not need by scanning the label, where it would have used an index (FalkorDB/FalkorDB#3331).
        val effectiveWhereClause = listOfNotNull(ctx.whereClause, keysetPlan?.predicate)
            .joinToString(" AND ")
            .takeIf { it.isNotEmpty() }
            ?.let { if (keysetPlan == null) alone(it, graphClass, query.conditions) else it }
        requireUnclaimed(keysetPlan?.bindings?.keys.orEmpty(), ctx.bindings, "Keyset cursor")
        requireUnclaimed(orderResult.bindings.keys, ctx.bindings, "Order expression")
        val effectiveBindings = ctx.bindings + (keysetPlan?.bindings ?: emptyMap()) + orderResult.bindings

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
        val where = ctx.whereClause?.let { alone(it, graphClass, query.conditions) }
        return GraphStatement(builder(graphClass).buildCountQuery(where, ctx.prologs, ctx.bridgeVariables), ctx.bindings)
    }

    override fun groups(graphClass: Class<*>, query: GroupQuerySpec<*>): GraphStatement =
        GroupQueryBuilder(grammar).build(GroupQuery.of(graphClass, query))

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
        val fullWhereClause = byId(builder, where)
        return GraphStatement(
            if (cascade == CascadeType.NONE) builder.buildDeleteQuery(fullWhereClause) else builder.buildCascadeDeleteQuery(fullWhereClause, cascade)
        )
    }

    override fun cascadeDepthChecks(graphClass: Class<*>, where: String?): List<CascadeDepthCheck> {
        // A fragment has no relationships to cascade through.
        val builder = builder(graphClass) as? GraphViewQueryBuilder ?: return emptyList()
        return builder.buildCascadeDepthChecks(byId(builder, where))
    }

    /** The conditions a delete by id selects its root by: the checks it runs first read the same root. */
    private fun byId(builder: GraphObjectQueryBuilder, where: String?): String {
        val idCondition = builder.buildIdWhereClause(GraphBackend.ID_PARAM)
        return if (where != null) "$idCondition AND $where" else idCondition
    }

    override fun deleteAll(graphClass: Class<*>, where: String?): GraphStatement =
        GraphStatement(builder(graphClass).buildDeleteQuery(where))

    override fun deleteAll(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement {
        val ctx = queryContext(graphClass, query)
        return GraphStatement(builder(graphClass).buildDeleteQuery(ctx.whereClause, ctx.prologs, ctx.bridgeVariables), ctx.bindings)
    }

    // ----- Saving -----

    override fun save(request: SaveRequest): SaveStatement = saves.build(request)

    /**
     * A fragment with no stamp to hand back goes as a row of an `UNWIND` where one can write it, and
     * so does a stamped one, by a statement that returns its stamp. A stamped fragment an `UNWIND`
     * cannot write (see [savedByUnwind]) is saved alone, by the statement that hands its stamp back.
     *
     * Fragments of more than one class are one statement where they go as rows and fit in one: see
     * [BatchSaveOperations.buildTogetherSpec]. An unstamped fragment an `UNWIND` cannot write is then
     * saved by statements of its own, as a stamped one is. One statement is applied whole on
     * every engine, FalkorDB among them, where statements in a transaction are not.
     */
    override fun saveTogether(fragments: List<IndexedValue<Any>>, nullPolicy: NullPolicy, writeFields: (Class<*>) -> Set<String>?): FragmentBatch {
        val (stamped, plain) = fragments.partition { FragmentModel.from(it.value.javaClass).stampField != null }
        val (batched, alone) = stamped.partition { FragmentModel.from(it.value.javaClass).savedByUnwind(grammar) }
        val (rows, perItem) = plain.partition { FragmentModel.from(it.value.javaClass).savedByUnwind(grammar) }
        batches.buildTogetherSpec(rows, batched, nullPolicy, writeFields)?.let { together ->
            return FragmentBatch(
                unstamped = batches.buildBatchSpecs(perItem.map { it.value }, CascadeType.NONE, nullPolicy, writeFields),
                stamped = listOf(together),
                alone = alone,
            )
        }
        // Those an UNWIND cannot write first, as where the rest are one statement, so that the locks
        // are taken in the same order however many statements the batch takes.
        return FragmentBatch(
            unstamped = batches.buildBatchSpecs((perItem + rows).map { it.value }, CascadeType.NONE, nullPolicy, writeFields),
            stamped = batches.buildStampedSpecs(batched, nullPolicy, writeFields),
            alone = alone,
        )
    }

    /** One statement for each [BatchSaveOperations.UNWIND_CHUNK_SIZE] rows, each row offered a stamp of its own. */
    override fun patchAll(graphClass: Class<*>, properties: List<String>, rows: List<PatchRow>, clears: Boolean): List<QuerySpecification<String>> {
        val model = FragmentModel.from(graphClass)
        val statement = patches.unwind(model, properties, clears)
        return rows.map { mapOf("i" to it.index.toString(), "id" to it.id, "stamp" to Stamps.fresh(), "v" to it.values) }
            .chunked(PatchRow.toAStatement(model, properties, BatchSaveOperations.UNWIND_CHUNK_SIZE))
            .map { chunk -> QuerySpecification.withStatement(statement).bind(mapOf(PatchStatementBuilder.ROWS to chunk)).transform(String::class.java) }
    }

    /** One statement: the predicate is what a load of [graphClass] would write, read once the node is locked. */
    override fun patch(graphClass: Class<*>, id: Any, values: Map<String, Any?>, conditions: List<WhereCondition>): GraphStatement {
        val ctx = queryContext(graphClass, conditions)
        val root = ctx.viewModel?.rootFragment
        val statement = patches.one(
            FragmentModel.from(root?.fragmentType ?: graphClass), root?.fieldName ?: FragmentQueryBuilder.NODE_ALIAS, values, ctx.whereClause, ctx.prologs,
        )
        val given = values.values.withIndex().filter { it.value != null }.associate { "${PatchStatementBuilder.VALUE}${it.index}" to it.value }
        return GraphStatement(
            statement,
            ctx.bindings + given + mapOf(PatchStatementBuilder.ID to CypherGenerator.convertToNeo4jValue(id), Stamps.NEW_PARAM to Stamps.fresh()),
        )
    }

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
        return queryContext(graphClass, query.conditions)
    }

    /** [conditions] as a where clause of a statement over [graphClass]. */
    private fun queryContext(graphClass: Class<*>, conditions: List<WhereCondition>): QueryContext {
        val viewModel = if (graphClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(graphClass) else null
        val whereResult = if (conditions.isNotEmpty()) CypherGenerator.buildWhereClause(conditions, viewModel, grammar) else null
        val bindings = CypherGenerator.extractBindings(conditions, viewModel)
        return QueryContext(
            viewModel, whereResult?.whereClause, bindings,
            whereResult?.prologs ?: emptyList(),
            whereResult?.bridgeVariables ?: emptyList(),
        )
    }

    /**
     * [clause], the conditions of [conditions], as the whole `WHERE` of a load or a count of
     * [graphClass]: an `anyOf` that is all there is stands without the parentheses it is written in.
     * Nothing is joined to it there, so they change nothing, and FalkorDB 6 scans the label for them
     * (FalkorDB/FalkorDB#3331).
     *
     * Only for a fragment. A view may add the relationships it requires to the clause, by `AND`,
     * and an `OR` beside them needs its parentheses.
     */
    private fun alone(clause: String, graphClass: Class<*>, conditions: List<WhereCondition>): String {
        if (graphClass.isAnnotationPresent(GraphView::class.java)) return clause
        if (conditions.singleOrNull() !is WhereCondition.OrCondition) return clause
        return if (isOneGroup(clause)) clause.substring(1, clause.length - 1) else clause
    }

    /** Whether [clause] is one pair of parentheses and what they hold: its first opens what its last closes. A name in backticks may hold either. */
    private fun isOneGroup(clause: String): Boolean {
        if (!clause.startsWith("(") || !clause.endsWith(")")) return false
        var depth = 0
        var quoted = false
        clause.forEachIndexed { i, c ->
            when {
                c == '`' -> quoted = !quoted
                quoted -> Unit
                c == '(' -> depth++
                c == ')' -> if (--depth == 0 && i < clause.lastIndex) return false
            }
        }
        return depth == 0
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

    internal companion object {
        private const val SKIP_PARAM = "_skip"
        private const val LIMIT_PARAM = "_limit"

        /**
         * Refuses a parameter of [reserved], which [what] binds, that a where clause has bound
         * already: one value would replace the other. The where clause names its parameters
         * `param_…`, so none of the reserved names, `_seek_…` and `_order_…`, is one today; this
         * keeps it so.
         */
        fun requireUnclaimed(reserved: Collection<String>, bindings: Map<String, Any?>, what: String) = reserved.forEach { name ->
            require(name !in bindings) { "$what parameter \$$name collides with a where-clause binding" }
        }
    }
}
