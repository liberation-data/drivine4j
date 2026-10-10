package org.drivine.backend.oracle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.backend.GraphBackend
import org.drivine.backend.GraphStatement
import org.drivine.backend.LoadPlan
import org.drivine.manager.CascadeType
import org.drivine.manager.NodeRef
import org.drivine.manager.NullPolicy
import org.drivine.manager.PersistenceManager
import org.drivine.manager.RelateMode
import org.drivine.manager.RemovedTargets
import org.drivine.model.RelationshipModel
import org.drivine.query.SaveStatement
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.StampWrite
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.grammar.CypherGrammar
import java.sql.SQLException

/**
 * The backend of Oracle Database. A graph is two tables and the SQL property graph over them (see
 * [OracleGraph]): a read is SQL/PGQ, through `GRAPH_TABLE`; a write is rows of the tables, since
 * Oracle refuses data manipulation through a property graph.
 */
internal class OracleBackend(
    private val persistenceManager: PersistenceManager,
    @Suppress("unused") private val objectMapper: ObjectMapper,
) : GraphBackend {

    private val graph = OracleGraph(persistenceManager.graphName ?: OracleGraph.DEFAULT_NAME).also { it.ensure(persistenceManager) }

    override val cypherGrammar: CypherGrammar? = null

    override val supportsOrphanDelete: Boolean = true

    override val orphanDeleteLimit: String? = null

    /** An index over a JSON property orders rows as any index does; the advisor reads them through the schema managers. */
    override val supportsIndexBackedOrdering: Boolean
        get() = persistenceManager.supportsSchemaManagement

    /** A save on Oracle clears a node's stale keys where it writes the node, so nothing is read ahead of it. */
    override val storedKeys: StoredPropertyKeys = StoredPropertyKeys { _, _, _ -> emptySet() }

    // ----- Reading -----

    override fun loadAll(graphClass: Class<*>, where: String?): GraphStatement =
        OracleSelect(graph, graphClass).let { select -> select.load(listOfNotNull(where?.let(select::written))) }

    override fun loadAll(graphClass: Class<*>, query: GraphQuerySpec<*>): LoadPlan {
        require(query.seekValues.isEmpty() || query.skip == null) { "seek and skip cannot be used together" }
        val (collectionSorts, rootOrders) = orders(graphClass, query)
        val select = OracleSelect(graph, graphClass, collectionSorts, query.depthOverrides)
        val seek = query.seekValues.takeIf { it.isNotEmpty() }?.let { select.after(rootOrders, it, collectionSorts.size) }
        val statement = select.load(listOfNotNull(select.predicate(query.conditions), seek), rootOrders, query.skip, query.limit)
        return LoadPlan(statement, rootOrders, keyset = seek != null)
    }

    /** An order on a relationship field sorts that field's list; any other orders the roots. */
    private fun orders(graphClass: Class<*>, query: GraphQuerySpec<*>): Pair<List<CollectionSortSpec>, List<OrderSpec>> {
        val relationships = OracleSelect(graph, graphClass).relationshipFields
        val (sorts, roots) = query.orders.partition { order ->
            val alias = order.propertyPath.substringBefore('.')
            alias in relationships || '_' in alias
        }
        return sorts.map {
            CollectionSortSpec(it.propertyPath.substringBefore('.'), it.propertyPath.substringAfter('.'), it.direction == OrderDirection.ASC)
        } to roots
    }

    override fun load(graphClass: Class<*>): GraphStatement {
        val select = OracleSelect(graph, graphClass)
        return select.load(listOf(select.hasId(GraphBackend.ID_PARAM)))
    }

    override fun count(graphClass: Class<*>, where: String?): GraphStatement =
        OracleSelect(graph, graphClass).let { select -> select.count(listOfNotNull(where?.let(select::written))) }

    override fun count(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement {
        requireNoSeek(query)
        val select = OracleSelect(graph, graphClass)
        return select.count(listOfNotNull(select.predicate(query.conditions)))
    }

    override fun nearest(
        graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, vector: List<Float>,
        topK: Int, threshold: Double?, searchK: Int?, partitionLabel: String?,
    ): ScoredSearchPlan = notYet("Vector search")

    override fun matching(graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, text: String, topK: Int, threshold: Double): ScoredSearchPlan =
        notYet("Full-text search")

    // ----- Deleting -----

    override fun delete(graphClass: Class<*>, where: String?, cascade: CascadeType): GraphStatement {
        if (cascade != CascadeType.NONE) notYet("A cascading delete")
        val select = OracleSelect(graph, graphClass)
        return deleting(select, listOfNotNull(select.hasId(GraphBackend.ID_PARAM), where?.let(select::written)))
    }

    override fun deleteAll(graphClass: Class<*>, where: String?): GraphStatement =
        OracleSelect(graph, graphClass).let { select -> deleting(select, listOfNotNull(where?.let(select::written))) }

    override fun deleteAll(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement {
        requireNoSeek(query)
        val select = OracleSelect(graph, graphClass)
        return deleting(select, listOfNotNull(select.predicate(query.conditions)))
    }

    /** Deletes the roots [predicates] select. A node's relationships go with it: the relationship table cascades. */
    private fun deleting(select: OracleSelect, predicates: List<String>): GraphStatement =
        GraphStatement(
            OracleSql.counted("DELETE FROM ${graph.nodes} ${select.rootAlias}\nWHERE ${select.selecting(predicates)}"),
            select.bindings(),
        )

    /** A seek walks an order, which a count or a delete does not have: it would be ignored, and a delete would take every match. */
    private fun requireNoSeek(query: GraphQuerySpec<*>) =
        require(query.seekValues.isEmpty()) { "seek is only supported by loadAll; this operation would ignore the cursor" }

    // ----- Saving -----

    override fun save(
        obj: Any,
        checked: Boolean,
        nullPolicy: NullPolicy,
        rootWriteFields: Set<String>?,
        replaced: (RelationshipModel) -> RemovedTargets?,
        before: JsonNode?,
        after: JsonNode?,
    ): SaveStatement = notYet("Saving")

    override fun foundStamp(root: StampWrite): GraphStatement = notYet("Saving")

    /**
     * A deadlock (`ORA-00060`), a serialization failure (`ORA-08177`) or a wait that ran out
     * (`ORA-30006`, `ORA-00054`): another writer held a row this statement wanted.
     */
    override fun contended(failure: Throwable): Boolean = failure is SQLException && failure.errorCode in CONTENTION

    // ----- Relationships on their own -----

    override fun relate(from: NodeRef, to: NodeRef, type: String, properties: Map<String, Any?>, mode: RelateMode): GraphStatement = notYet("edges.relate")

    override fun unrelate(from: NodeRef, to: NodeRef, type: String): GraphStatement = notYet("edges.unrelate")

    override fun unrelateAll(from: NodeRef, type: String, direction: Direction): GraphStatement = notYet("edges.unrelateAll")

    override fun related(from: NodeRef, type: String, direction: Direction, targetClass: Class<*>): GraphStatement = notYet("edges.loadRelated")

    private fun notYet(what: String): Nothing = throw UnsupportedOperationException("$what is not yet available on Oracle.")

    private companion object {
        val CONTENTION = setOf(60, 8177, 30006, 54)
    }
}
