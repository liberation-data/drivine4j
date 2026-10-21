package org.drivine.backend.postgres

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.backend.CascadeDepthCheck
import org.drivine.backend.FragmentBatch
import org.drivine.backend.GraphBackend
import org.drivine.backend.GraphStatement
import org.drivine.backend.LoadPlan
import org.drivine.backend.sql.SqlSavePlan
import org.drivine.backend.sql.WritePlan
import org.drivine.backend.sql.WriteReturns
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
import java.sql.SQLException

/**
 * The backend of Postgres. A graph is two tables (see [PostgresGraph]), read with joins over them (see
 * [PostgresSelect]) and written by a plan of steps that a function the store keeps carries out (see
 * [PostgresWriteFunctions]): the plan is the one Oracle's block carries out.
 *
 * Reads and writes are here; what deletes, groups and searches is being built (see
 * `docs/postgres-sql-backend-plan.md`) and is refused until it is.
 */
internal class PostgresBackend(private val persistenceManager: PersistenceManager, objectMapper: ObjectMapper) : GraphBackend {

    /** The graph the manager names, made with the backend where it is not there yet. */
    private val graph = PostgresGraph.ensured(persistenceManager)

    /** How a plan of steps is carried out: by the graph's write function. */
    private val blocks = PostgresWriteBlocks(graph.writes)

    /** Plans a save, and a batch of fragments, as the steps of a plan. */
    private val saves = SqlSavePlan(blocks, objectMapper, PostgresSql::key)

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

    // ----- Saving -----

    override fun save(request: SaveRequest): SaveStatement = saves.build(request)

    /** A batch of fragments is one call for each [BATCH] of them, which hands a row back for each fragment. */
    override fun saveTogether(fragments: List<IndexedValue<Any>>, nullPolicy: NullPolicy, writeFields: (Class<*>) -> Set<String>?): FragmentBatch = FragmentBatch(
        stamped = fragments.chunked(BATCH).map { chunk ->
            val (text, bindings) = saves.together(chunk, nullPolicy, writeFields)
            QuerySpecification.withStatement(text).bind(bindings).transform(String::class.java)
        },
    )

    /**
     * A `patch` step for each row, [BATCH] to a call, and fewer where a vector is written: see
     * [PatchRow.toAStatement]. A null in a row is left out of what the step sets, and when the call
     * clears, named among what it removes.
     */
    override fun patchAll(graphClass: Class<*>, properties: List<String>, rows: List<PatchRow>, clears: Boolean): List<QuerySpecification<String>> {
        val model = FragmentModel.from(graphClass)
        val key = requireNotNull(model.nodeIdProperty) { "${graphClass.simpleName} has no @NodeId field to find a node by." }
        return rows.chunked(PatchRow.toAStatement(model, properties, BATCH)).map { chunk ->
            val plan = WritePlan(blocks)
            val shape = plan.shape(model.labels, key)
            chunk.forEach { row ->
                plan.patch(
                    shape, PostgresSql.key(row.id), row.index.toString(),
                    set = row.values.filterValues { it != null },
                    unset = if (clears) row.values.filterValues { it == null }.keys.toList() else emptyList(),
                )
            }
            QuerySpecification.withStatement(plan.text(WriteReturns.EACH_STAMP)).bind(plan.bindings()).transform(String::class.java)
        }
    }

    /** One call with one `patch` step, and the query it asks of the node when there are [conditions]. */
    override fun patch(graphClass: Class<*>, id: Any, values: Map<String, Any?>, conditions: List<WhereCondition>): GraphStatement {
        val select = PostgresSelect(graph, graphClass)
        val model = FragmentModel.from(select.rootFragment)
        val key = requireNotNull(model.nodeIdProperty) { "${model.clazz.simpleName} has no @NodeId field to find a node by." }
        val plan = WritePlan(blocks)
        plan.patch(
            plan.shape(model.labels, key), PostgresSql.key(id), "0",
            set = values.filterValues { it != null }, unset = values.filterValues { it == null }.keys.toList(),
        )
        val patchedIf = select.meeting(conditions)
        return GraphStatement(plan.text(WriteReturns.EACH_STAMP, patchedIf), plan.bindings(patchedIf))
    }

    /** A stamp that is null says the node is there and has none; no row, that the node is gone. */
    override fun foundStamp(root: StampWrite): GraphStatement = GraphStatement(
        "SELECT n.stamp FROM ${graph.nodes} n WHERE " +
            listOfNotNull("${PostgresSql.property("n", root.idProperty)} = CAST(\$id AS text)", PostgresSql.hasLabels("n", root.labelNames)).joinToString(" AND "),
        mapOf("id" to PostgresSql.key(root.id)),
    )

    /**
     * A serialization failure (`40001`) or a deadlock (`40P01`): another writer held a row this statement
     * wanted. A lock that waits does not time out unless the session says so, so a wait is not among them.
     */
    override fun contended(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.any { it is SQLException && it.sqlState in CONTENTION }

    // ----- Relationships on their own -----

    /** The step that finds the node [ref] names, which must be there. Gives the number later steps know it by. */
    private fun WritePlan.found(ref: NodeRef): Int {
        val model = FragmentModel.from(ref.fragmentClass)
        val key = requireNotNull(model.nodeIdProperty) {
            "${ref.fragmentClass.simpleName} has no @NodeId field, so a node of it cannot be referred to by id."
        }
        return ref(shape((model.labels + ref.labels).distinct(), key), PostgresSql.key(ref.id))
    }

    /** The call that carries out [steps], which write relationships of [type], and returns the number they counted. */
    private fun counting(type: String, steps: WritePlan.() -> Unit): GraphStatement {
        require(type.isNotBlank()) { "A relationship needs a type." }
        val plan = WritePlan(blocks).apply(steps)
        return GraphStatement(plan.text(WriteReturns.COUNT), plan.bindings())
    }

    override fun relate(from: NodeRef, to: NodeRef, type: String, properties: Map<String, Any?>, mode: RelateMode): GraphStatement = counting(type) {
        link(found(from), found(to), type, Direction.OUTGOING, properties.filterValues { it != null }, always = mode == RelateMode.CREATE)
    }

    override fun unrelate(from: NodeRef, to: NodeRef, type: String): GraphStatement = counting(type) {
        cut(found(from), found(to), type, Direction.OUTGOING)
    }

    override fun unrelateAll(from: NodeRef, type: String, direction: Direction): GraphStatement = counting(type) {
        cut(found(from), null, type, direction)
    }

    // ----- Not yet -----

    override fun delete(graphClass: Class<*>, where: String?, cascade: CascadeType): GraphStatement = notYet("a delete")

    override fun cascadeDepthChecks(graphClass: Class<*>, where: String?): List<CascadeDepthCheck> = notYet("a cascading delete")

    override fun deleteAll(graphClass: Class<*>, where: String?): GraphStatement = notYet("a delete")

    override fun deleteAll(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement = notYet("a delete")

    private fun notYet(what: String): Nothing =
        throw UnsupportedOperationException("Postgres's object manager does not yet support $what: it reads and writes, and the rest is being built (see docs/postgres-sql-backend-plan.md).")

    private companion object {
        /** The parameter [related] binds the id of the node it starts from as. */
        const val FROM_PARAM = "_fromId"

        /** The states of a serialization failure and of a deadlock. */
        val CONTENTION = setOf("40001", "40P01")

        /** The fragments one call saves: enough that a round trip is not what a batch costs, and a plan stays small. */
        const val BATCH = 500
    }
}
