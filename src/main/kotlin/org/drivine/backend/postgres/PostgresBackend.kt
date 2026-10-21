package org.drivine.backend.postgres

import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.backend.CascadeDepthCheck
import org.drivine.backend.FragmentBatch
import org.drivine.backend.GraphBackend
import org.drivine.backend.GraphStatement
import org.drivine.backend.LoadPlan
import org.drivine.manager.CascadeType
import org.drivine.manager.NodeRef
import org.drivine.manager.NullPolicy
import org.drivine.manager.PersistenceManager
import org.drivine.manager.RelateMode
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.query.PatchRow
import org.drivine.query.QuerySpecification
import org.drivine.query.SaveRequest
import org.drivine.query.SaveStatement
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.StampWrite
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.KeysetPlanner
import org.drivine.query.dsl.SplitOrders
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.grammar.CypherGrammar

/**
 * The backend of Postgres. A graph is two tables (see [PostgresGraph]), read with joins over them:
 * see [PostgresSelect].
 *
 * Reads are here; what writes, deletes, groups and searches is being built (see
 * `docs/postgres-sql-backend-plan.md`) and is refused until it is.
 */
internal class PostgresBackend(private val persistenceManager: PersistenceManager) : GraphBackend {

    /** The graph the manager names, made with the backend where it is not there yet. */
    private val graph = PostgresGraph.ensured(persistenceManager)

    override val cypherGrammar: CypherGrammar? = null

    override val supportsOrphanDelete: Boolean = false

    override val orphanDeleteLimit: String? = "Postgres's object manager does not delete yet."

    /** A range index on Postgres reads a property as text, so an order by a number or a moment is not served by one. */
    override val supportsIndexBackedOrdering: Boolean = false

    override val storedKeys: StoredPropertyKeys = StoredPropertyKeys { _, _, _ -> emptySet() }

    // ----- Reading -----

    override fun loadAll(graphClass: Class<*>, where: String?): GraphStatement =
        PostgresSelect(graph, graphClass).let { select -> select.load(listOfNotNull(where?.let(select::written))) }

    override fun loadAll(graphClass: Class<*>, query: GraphQuerySpec<*>): LoadPlan {
        require(query.seekValues.isEmpty() || query.skip == null) { "seek and skip cannot be used together" }
        val (collectionSorts, rootOrders) = orders(graphClass, query)
        val select = PostgresSelect(graph, graphClass, collectionSorts, query.depthOverrides)
        val seek = query.seekValues.takeIf { it.isNotEmpty() }?.let { select.after(KeysetPlanner.propertyOrders(rootOrders), it, collectionSorts.size) }
        val statement = select.load(listOfNotNull(select.predicate(query.conditions), seek), rootOrders, query.skip, query.limit)
        return LoadPlan(statement, rootOrders, keyset = seek != null)
    }

    /**
     * The orders of [query] parted into the sorts of relationship collections and the orders of the
     * roots, as `CypherGenerator.splitOrders` parts them.
     */
    private fun orders(graphClass: Class<*>, query: GraphQuerySpec<*>): SplitOrders {
        val relationships = if (graphClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(graphClass).relationships.map { it.fieldName }.toSet() else emptySet()
        return CypherGenerator.splitOrders(query.orders, relationships)
    }

    override fun load(graphClass: Class<*>): GraphStatement {
        val select = PostgresSelect(graph, graphClass)
        return select.load(listOf(select.hasId(GraphBackend.ID_PARAM)))
    }

    override fun count(graphClass: Class<*>, where: String?): GraphStatement =
        PostgresSelect(graph, graphClass).let { select -> select.count(listOfNotNull(where?.let(select::written))) }

    override fun count(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement {
        requireNoSeek(query)
        val select = PostgresSelect(graph, graphClass)
        return select.count(listOfNotNull(select.predicate(query.conditions)))
    }

    override fun groups(graphClass: Class<*>, query: GroupQuerySpec<*>): GraphStatement = notYet("a grouped read")

    override fun nearest(
        graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, vector: List<Float>,
        topK: Int, threshold: Double?, searchK: Int?, partitionLabel: String?,
    ): ScoredSearchPlan = notYet("a vector search")

    override fun matching(graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, text: String, topK: Int, threshold: Double): ScoredSearchPlan =
        notYet("a full-text search")

    override fun related(from: NodeRef, type: String, direction: Direction, targetClass: Class<*>): GraphStatement {
        require(type.isNotBlank()) { "A relationship needs a type." }
        val model = FragmentModel.from(from.fragmentClass)
        val key = requireNotNull(model.nodeIdProperty) {
            "${from.fragmentClass.simpleName} has no @NodeId field, so a node of it cannot be referred to by id."
        }
        val select = PostgresSelect(graph, targetClass)
        val statement = select.load(listOf(select.relatedTo((model.labels + from.labels).distinct(), key, FROM_PARAM, type, direction)))
        return GraphStatement(statement.statement, statement.bindings + (FROM_PARAM to from.id))
    }

    /** A seek walks an order, which a count or a delete does not have: it would be ignored, and a delete would take every match. */
    private fun requireNoSeek(query: GraphQuerySpec<*>) =
        require(query.seekValues.isEmpty()) { "seek is only supported by loadAll; this operation would ignore the cursor" }

    // ----- Not yet -----

    override fun delete(graphClass: Class<*>, where: String?, cascade: CascadeType): GraphStatement = notYet("a delete")

    override fun cascadeDepthChecks(graphClass: Class<*>, where: String?): List<CascadeDepthCheck> = notYet("a cascading delete")

    override fun deleteAll(graphClass: Class<*>, where: String?): GraphStatement = notYet("a delete")

    override fun deleteAll(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement = notYet("a delete")

    override fun save(request: SaveRequest): SaveStatement = notYet("a save")

    override fun saveTogether(fragments: List<IndexedValue<Any>>, nullPolicy: NullPolicy, writeFields: (Class<*>) -> Set<String>?): FragmentBatch = notYet("a save")

    override fun patchAll(graphClass: Class<*>, properties: List<String>, rows: List<PatchRow>, clears: Boolean): List<QuerySpecification<String>> = notYet("a patch")

    override fun patch(graphClass: Class<*>, id: Any, values: Map<String, Any?>, conditions: List<WhereCondition>): GraphStatement = notYet("a patch")

    override fun foundStamp(root: StampWrite): GraphStatement = notYet("a stamp check")

    override fun contended(failure: Throwable): Boolean = false

    override fun relate(from: NodeRef, to: NodeRef, type: String, properties: Map<String, Any?>, mode: RelateMode): GraphStatement = notYet("relate")

    override fun unrelate(from: NodeRef, to: NodeRef, type: String): GraphStatement = notYet("unrelate")

    override fun unrelateAll(from: NodeRef, type: String, direction: Direction): GraphStatement = notYet("unrelateAll")

    private fun notYet(what: String): Nothing =
        throw UnsupportedOperationException("Postgres's object manager does not yet support $what: it reads, and the rest is being built (see docs/postgres-sql-backend-plan.md).")

    private companion object {
        /** The parameter [related] binds the id of the node it starts from as. */
        const val FROM_PARAM = "_fromId"
    }
}
