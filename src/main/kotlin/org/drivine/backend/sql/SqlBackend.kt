package org.drivine.backend.sql

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.drivine.manager.RelateMode
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.query.GroupQuery
import org.drivine.query.PatchRow
import org.drivine.query.QuerySpecification
import org.drivine.query.SaveRequest
import org.drivine.query.SaveStatement
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.KeysetPlanner
import org.drivine.query.dsl.SplitOrders
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.grammar.CypherGrammar

/**
 * The backend of a SQL store, Oracle or Postgres, which keep a graph in the same two tables (see
 * [SqlGraph]). What an object manager asks is done the same way on both, operation by operation:
 * a read is a statement of the store's [select]; a save, a patch and a relationship written on its
 * own are a [WritePlan] of steps that the store's [blocks] carry out. What is each store's own is the
 * SQL: its select, its deletes, its searches, and how it tells a write that lost to another.
 */
internal abstract class SqlBackend<S : SqlSelect>(objectMapper: ObjectMapper) : GraphBackend {

    /** How a plan of steps is carried out. */
    protected abstract val blocks: WriteBlocks

    /** An id given to find a node by, as the JSON the node holds it as. */
    private val ids = NodeIds(objectMapper)

    /** Plans a save, and a batch of fragments, as the steps of a plan. */
    private val saves by lazy { SqlSavePlan(blocks, objectMapper, ::key, ids) }

    /**
     * The statements for [graphClass], with the sorts of its relationship collections and the depths it
     * reads them to. [indexed] when the predicates of the query DSL are read through what the store
     * knows of its indexes.
     */
    protected abstract fun select(
        graphClass: Class<*>,
        collectionSorts: List<CollectionSortSpec> = emptyList(),
        depthOverrides: Map<String, Int> = emptyMap(),
        indexed: Boolean = false,
    ): S

    /**
     * An id as the text a write plan keys its node by, within the plan, and reads a target's stored id back as. A node is
     * found by its id's JSON ([NodeIds]), not by this text.
     */
    protected abstract fun key(id: Any): String

    /**
     * [statement], which finds the root of [select] by the id bound as [GraphBackend.ID_PARAM], binding the id it is given as
     * the JSON the root's node holds it as: text is read as the `@NodeId` field's type first (see [NodeIds]).
     */
    private fun byId(select: S, statement: GraphStatement): GraphStatement {
        val model = FragmentModel.from(select.rootFragment)
        return statement.bindingId { given -> ids.json(model, given) }
    }

    /**
     * [given], the ids `loadNearestAmong` names its roots by, each as the JSON text a root of [select] holds it as, once:
     * text is read as the `@NodeId` field's type first, as a load reads it, and text that is no value of it is left out,
     * as it finds no node (see [NodeIds]).
     */
    protected fun idsJson(select: S, given: List<String>): List<String> {
        val model = FragmentModel.from(select.rootFragment)
        return given.mapNotNull { ids.json(model, it) }.distinct()
    }

    /** Each id's text as the JSON text a root holds it as, as [idsJson] reads it. */
    override fun nearestAmongIds(graphClass: Class<*>): (String) -> Any? {
        val model = FragmentModel.from(select(graphClass).rootFragment)
        return { ids.json(model, it) }
    }

    override val cypherGrammar: CypherGrammar? = null

    override val supportsOrphanDelete: Boolean = true

    override val orphanDeleteLimit: String? = null

    /**
     * An order sorts as Cypher does, by the module's fragments, which no range index holds: a range index is over a
     * property's JSON (Postgres) or its class and key (Oracle and SQLite). So no index is advised for an order.
     */
    override val supportsIndexBackedOrdering: Boolean = false

    /** A save clears a node's stale keys where it writes the node, so nothing is read ahead of it. */
    override val storedKeys: StoredPropertyKeys = StoredPropertyKeys { _, _, _ -> emptySet() }

    // ----- Reading -----

    override fun loadAll(graphClass: Class<*>, where: String?): GraphStatement =
        select(graphClass).let { select -> select.load(listOfNotNull(where?.let(select::written))) }

    override fun loadAll(graphClass: Class<*>, query: GraphQuerySpec<*>): LoadPlan {
        require(query.seekValues.isEmpty() || query.skip == null) { "seek and skip cannot be used together" }
        val (collectionSorts, rootOrders) = orders(graphClass, query)
        val select = select(graphClass, collectionSorts, query.depthOverrides, indexed = true)
        val seek = query.seekValues.takeIf { it.isNotEmpty() }?.let { select.after(KeysetPlanner.propertyOrders(rootOrders), it, collectionSorts.size) }
        val statement = select.load(listOfNotNull(select.predicate(query.conditions, indexed = true), seek), rootOrders, query.skip, query.limit)
        return LoadPlan(statement, rootOrders, keyset = seek != null)
    }

    /**
     * The orders of [query] parted into the sorts of relationship collections and the orders of the
     * roots, as `CypherGenerator.splitOrders` parts them. An alias with an underscore in it names a
     * nested collection (`field_nested`), and is taken for a collection sort even on a fragment,
     * where it then sorts nothing.
     */
    private fun orders(graphClass: Class<*>, query: GraphQuerySpec<*>): SplitOrders {
        val relationships = if (graphClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(graphClass).relationships.map { it.fieldName }.toSet() else emptySet()
        return CypherGenerator.splitOrders(query.orders, relationships)
    }

    override fun load(graphClass: Class<*>): GraphStatement {
        val select = select(graphClass)
        return byId(select, select.load(listOf(select.hasId(GraphBackend.ID_PARAM))))
    }

    override fun count(graphClass: Class<*>, where: String?): GraphStatement =
        select(graphClass).let { select -> select.count(listOfNotNull(where?.let(select::written))) }

    override fun count(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement {
        requireNoSeek(query)
        // The depths the query gives: a condition on a field walks them, as the load does.
        val select = select(graphClass, depthOverrides = query.depthOverrides, indexed = true)
        return select.count(listOfNotNull(select.predicate(query.conditions, indexed = true)))
    }

    override fun groups(graphClass: Class<*>, query: GroupQuerySpec<*>): GraphStatement =
        select(graphClass, indexed = true).groups(GroupQuery.of(graphClass, query))

    override fun related(from: NodeRef, type: String, direction: Direction, targetClass: Class<*>): GraphStatement {
        requireType(type)
        val source = shapeOf(from)
        val select = select(targetClass)
        val statement = select.load(listOf(select.relatedTo(source.labels, source.key, FROM_PARAM, type, direction)))
        return GraphStatement(statement.statement, statement.bindings + (FROM_PARAM to ids.json(FragmentModel.from(from.fragmentClass), from.id)))
    }

    /** A seek walks an order, which a count or a delete does not have: it would be ignored, and a delete would take every match. */
    protected fun requireNoSeek(query: GraphQuerySpec<*>) =
        require(query.seekValues.isEmpty()) { "seek is only supported by loadAll; this operation would ignore the cursor" }

    // ----- Deleting -----

    override fun delete(graphClass: Class<*>, where: String?, cascade: CascadeType): GraphStatement {
        val select = select(graphClass)
        val predicates = byId(select, where)
        val cascaded = if (cascade == CascadeType.DELETE_ALL || cascade == CascadeType.DELETE_ORPHAN) select.cascaded(predicates) else null
        return byId(select, if (cascaded == null) deleting(select, predicates) else cascading(select, predicates, cascaded, orphansOnly = cascade == CascadeType.DELETE_ORPHAN))
    }

    override fun cascadeDepthChecks(graphClass: Class<*>, where: String?): List<CascadeDepthCheck> {
        val select = select(graphClass)
        val checks = select.cascadeDepthChecks(byId(select, where))
        return checks.map { (recursion, sql) -> CascadeDepthCheck(recursion.view, recursion.field, recursion.step.maxDepth, byId(select, GraphStatement(sql, select.bindings()))) }
    }

    /** The predicates a delete by id selects its root by: the checks it runs first read the same root. */
    private fun byId(select: S, where: String?): List<String> =
        listOfNotNull(select.hasId(GraphBackend.ID_PARAM), where?.let(select::written))

    override fun deleteAll(graphClass: Class<*>, where: String?): GraphStatement =
        select(graphClass).let { select -> deleting(select, listOfNotNull(where?.let(select::written))) }

    override fun deleteAll(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement {
        requireNoSeek(query)
        val select = select(graphClass, depthOverrides = query.depthOverrides, indexed = true)
        return deleting(select, listOfNotNull(select.predicate(query.conditions, indexed = true)))
    }

    /**
     * Deletes the roots [predicates] select, whether or not they have the relationships the view
     * requires, as the Cypher delete does, and counts them. A node's relationships go with it: the
     * relationship table cascades.
     */
    protected abstract fun deleting(select: S, predicates: List<String>): GraphStatement

    /**
     * Deletes the roots [predicates] select, and of the nodes [cascaded] reaches from them either every
     * one, or with [orphansOnly] those not kept, and counts them. A node is kept when a node outside the
     * cascade is related to it, or when it is one step of a relationship the cascade follows below a
     * kept one: a kept message keeps its attachments, and its session is still deleted. What is kept is
     * read before anything is deleted, so a node's relationships to the others the cascade takes do not
     * keep it.
     */
    protected abstract fun cascading(select: S, predicates: List<String>, cascaded: String, orphansOnly: Boolean): GraphStatement

    // ----- Saving -----

    override fun save(request: SaveRequest): SaveStatement = saves.build(request)

    /**
     * A batch of fragments is one statement for each [BATCH] of them. Every fragment is a step of its
     * plan, stamped or not, and the statement hands a row back for each: so every statement is among
     * the stamped, and no fragment is left to be saved alone.
     */
    override fun saveTogether(fragments: List<IndexedValue<Any>>, nullPolicy: NullPolicy, writeFields: (Class<*>) -> Set<String>?): FragmentBatch = FragmentBatch(
        stamped = fragments.chunked(BATCH).map { chunk ->
            val (text, bindings) = saves.together(chunk, nullPolicy, writeFields)
            QuerySpecification.withStatement(text).bind(bindings).transform(String::class.java)
        },
    )

    /**
     * A `patch` step for each row, [BATCH] to a statement, and fewer where a vector is written: see
     * [PatchRow.toAStatement]. A null in a row is left out of what the step sets, and when the call
     * clears, named among what it removes.
     */
    override fun patchAll(graphClass: Class<*>, properties: List<String>, rows: List<PatchRow>, clears: Boolean): List<QuerySpecification<String>> {
        val model = FragmentModel.from(graphClass)
        val idProperty = requireNotNull(model.nodeIdProperty) { "${graphClass.simpleName} has no @NodeId field to find a node by." }
        return rows.chunked(PatchRow.toAStatement(model, properties, BATCH)).map { chunk ->
            val plan = WritePlan(blocks)
            val shape = plan.shape(model.labels, idProperty)
            chunk.forEach { row ->
                val id = ids.of(model, row.id)
                plan.patch(
                    shape, key(id ?: row.id), id, row.index.toString(),
                    set = row.values.filterValues { it != null },
                    unset = if (clears) row.values.filterValues { it == null }.keys.toList() else emptyList(),
                    lower = model.lowerCopied,
                )
            }
            QuerySpecification.withStatement(plan.text(WriteReturns.EACH_STAMP)).bind(plan.bindings()).transform(String::class.java)
        }
    }

    /**
     * One statement with one `patch` step, and the query it asks of the node when there are
     * [conditions]. The values are in the plan, and the query in the statement's text: so the text is
     * the node's shape and the query, and the same for every id and value.
     */
    override fun patch(graphClass: Class<*>, id: Any, values: Map<String, Any?>, conditions: List<WhereCondition>): GraphStatement {
        val select = select(graphClass)
        val model = FragmentModel.from(select.rootFragment)
        val idProperty = requireNotNull(model.nodeIdProperty) { "${model.clazz.simpleName} has no @NodeId field to find a node by." }
        val plan = WritePlan(blocks)
        val stored = ids.of(model, id)
        plan.patch(
            plan.shape(model.labels, idProperty), key(stored ?: id), stored, "0",
            set = values.filterValues { it != null }, unset = values.filterValues { it == null }.keys.toList(), lower = model.lowerCopied,
        )
        val patchedIf = select.meeting(conditions)
        return GraphStatement(plan.text(WriteReturns.EACH_STAMP, patchedIf), plan.bindings(patchedIf) + patchedIfBindings(select))
    }

    /** The values of the parameters of the query a `patch` step asks, which [select] wrote: none where the query is written whole. */
    protected open fun patchedIfBindings(select: S): Map<String, Any?> = emptyMap()

    // ----- Relationships on their own -----

    /** The step that finds the node [ref] names, which must be there. Gives the number later steps know it by. */
    private fun WritePlan.found(ref: NodeRef): Int {
        val node = shapeOf(ref)
        val id = ids.of(FragmentModel.from(ref.fragmentClass), ref.id)
        return ref(shape(node.labels, node.key), key(id ?: ref.id), id)
    }

    /**
     * How the node [ref] names is found: by the labels of its class and those the ref adds, and the
     * property its class keeps its id under. A class with no `@NodeId` field names no node.
     */
    private fun shapeOf(ref: NodeRef): NodeShape {
        val model = FragmentModel.from(ref.fragmentClass)
        val key = requireNotNull(model.nodeIdProperty) {
            "${ref.fragmentClass.simpleName} has no @NodeId field, so a node of it cannot be referred to by id."
        }
        return NodeShape((model.labels + ref.labels).distinct(), key)
    }

    /** A relationship is found by its type, so one with none is refused before anything is written for it. */
    private fun requireType(type: String) = require(type.isNotBlank()) { "A relationship needs a type." }

    /** The statement that carries out [steps], which write relationships of [type], and returns the number they counted. */
    private fun counting(type: String, steps: WritePlan.() -> Unit): GraphStatement {
        requireType(type)
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

    private companion object {
        /** The parameter [related] binds the id of the node it starts from as, as the JSON the node holds it as. */
        const val FROM_PARAM = "_fromId"

        /** The fragments one statement saves: enough that a round trip is not what a batch costs, and a plan stays small. */
        const val BATCH = 500
    }
}

/**
 * The key a node's `props` holds the embedding at, as the module writes it ([ValueLayout.escape]): a name that begins
 * with `$` is held as `$$…`. What a SQL store's search reads and its vector index is made over; the schema's catalog
 * keeps the property's own name.
 */
internal val org.drivine.query.grammar.VectorQuerySpec.storedProperty: String get() = org.drivine.cypher.store.ValueLayout.escape(property)
