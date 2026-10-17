package org.drivine.backend.oracle

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.annotation.Direction
import org.drivine.annotation.GraphView
import org.drivine.backend.FragmentBatch
import org.drivine.backend.GraphBackend
import org.drivine.backend.GraphStatement
import org.drivine.backend.LoadPlan
import org.drivine.manager.CascadeType
import org.drivine.manager.NodeRef
import org.drivine.manager.NullPolicy
import org.drivine.manager.PersistenceManager
import org.drivine.manager.RelateMode
import org.drivine.manager.RemovedTargets
import org.drivine.model.FragmentModel
import org.drivine.model.GraphViewModel
import org.drivine.model.RelationshipModel
import org.drivine.query.FullTextIndexResolver
import org.drivine.query.GroupQuery
import org.drivine.query.PatchRow
import org.drivine.query.QuerySpecification
import org.drivine.query.SaveRequest
import org.drivine.query.SaveStatement
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.StampWrite
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.VectorIndexResolver
import org.drivine.query.dsl.CollectionSortSpec
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.OrderDirection
import org.drivine.query.dsl.KeysetPlanner
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.query.dsl.WhereCondition
import org.drivine.schema.SchemaItemKind
import org.drivine.query.grammar.CypherGrammar
import java.sql.SQLException
import java.util.concurrent.Executor

/**
 * The backend of Oracle Database. A graph is two tables and the SQL property graph over them (see
 * [OracleGraph]): a read is SQL/PGQ, through `GRAPH_TABLE`; a write is rows of the tables, since
 * Oracle refuses data manipulation through a property graph.
 */
internal class OracleBackend(
    private val persistenceManager: PersistenceManager,
    objectMapper: ObjectMapper,
    /** Where the labelled indexes of the graph are read: see [LabelledIndexCatalog]. */
    catalogReads: Executor? = null,
) : GraphBackend {

    /** The graph the manager names, made with the backend where it is not there yet. */
    private val graph = OracleGraph.ensured(persistenceManager)

    /** The labelled indexes of the graph, which a filter on the nodes of a label is read through. */
    private val labelled = catalogReads?.let { LabelledIndexCatalog(persistenceManager, graph, refresh = it) } ?: LabelledIndexCatalog(persistenceManager, graph)

    /** Plans a save, and a batch of fragments, as the steps of a write block. */
    private val saves = OracleSavePlan(graph, objectMapper)

    override val cypherGrammar: CypherGrammar? = null

    override val supportsOrphanDelete: Boolean = true

    override val orphanDeleteLimit: String? = null

    /**
     * A range index reads a property as text. An order by a number or a moment reads it as that, and is
     * not served by the index, so no index is advised for an order.
     */
    override val supportsIndexBackedOrdering: Boolean = false

    /** A save on Oracle clears a node's stale keys where it writes the node, so nothing is read ahead of it. */
    override val storedKeys: StoredPropertyKeys = StoredPropertyKeys { _, _, _ -> emptySet() }

    // ----- Reading -----

    override fun loadAll(graphClass: Class<*>, where: String?): GraphStatement =
        OracleSelect(graph, graphClass).let { select -> select.load(listOfNotNull(where?.let(select::written))) }

    override fun loadAll(graphClass: Class<*>, query: GraphQuerySpec<*>): LoadPlan {
        require(query.seekValues.isEmpty() || query.skip == null) { "seek and skip cannot be used together" }
        val (collectionSorts, rootOrders) = orders(graphClass, query)
        val select = OracleSelect(graph, graphClass, collectionSorts, query.depthOverrides, labelled.current())
        val seek = query.seekValues.takeIf { it.isNotEmpty() }?.let { select.after(KeysetPlanner.propertyOrders(rootOrders), it, collectionSorts.size) }
        val statement = select.load(listOfNotNull(select.predicate(query.conditions, indexed = true), seek), rootOrders, query.skip, query.limit)
        return LoadPlan(statement, rootOrders, keyset = seek != null)
    }

    /**
     * An order on a relationship field sorts that field's list; any other orders the roots. An alias
     * with an underscore in it names a nested collection (`field_nested`), as `CypherGenerator.processOrders`
     * reads it, and is taken for a collection sort even on a fragment, where it then sorts nothing.
     */
    private fun orders(graphClass: Class<*>, query: GraphQuerySpec<*>): Pair<List<CollectionSortSpec>, List<OrderTerm>> {
        val relationships = if (graphClass.isAnnotationPresent(GraphView::class.java)) GraphViewModel.from(graphClass).relationships.map { it.fieldName }.toSet() else emptySet()
        val (sorts, roots) = query.orders.partition { order ->
            val alias = (order as? OrderSpec)?.propertyPath?.substringBefore('.')
            alias != null && (alias in relationships || '_' in alias)
        }
        return sorts.filterIsInstance<OrderSpec>().map {
            it.requireNoNullPlacement("in the order of a relationship collection")
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
        val select = OracleSelect(graph, graphClass, labelled = labelled.current())
        return select.count(listOfNotNull(select.predicate(query.conditions, indexed = true)))
    }

    override fun groups(graphClass: Class<*>, query: GroupQuerySpec<*>): GraphStatement =
        OracleSelect(graph, graphClass, labelled = labelled.current()).groups(GroupQuery.of(graphClass, query))

    override fun nearest(
        graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, vector: List<Float>,
        topK: Int, threshold: Double?, searchK: Int?, partitionLabel: String?,
    ): ScoredSearchPlan {
        val select = OracleSelect(graph, graphClass)
        val index = VectorIndexResolver.resolve(select.rootFragment, property, "_", "_", partitionLabel = partitionLabel)
        // What the search measures is the nearest searchK, and of what is left of them topK come back.
        // Through the index for the label when one has been made; else by measuring every node.
        val embeddings = made(SchemaItemKind.VECTOR_INDEX, index.label, listOf(index.property))
        val candidates = if (embeddings != null) {
            select.nearestThrough(embeddings, index.similarity, vector, searchK ?: topK)
        } else {
            select.nearestTo(index.property, index.similarity, partitionLabel, vector, searchK ?: topK)
        }
        val statement = select.scored(candidates, listOfNotNull(query?.let { select.predicate(it.conditions) }), threshold, topK.takeIf { searchK != null })
        return ScoredSearchPlan(statement.statement, statement.bindings, topK, searchK ?: topK)
    }

    override fun matching(graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, text: String, topK: Int, threshold: Double): ScoredSearchPlan {
        val select = OracleSelect(graph, graphClass)
        val index = FullTextIndexResolver.resolve(select.rootFragment, property, "_", "_")
        val words = TextQuery.parse(text)
        val texts = made(SchemaItemKind.FULLTEXT_INDEX, index.label, index.properties)
        val candidates = if (texts != null) select.matchingThrough(texts, index.properties, words) else select.matching(index.properties, words)
        val statement = select.scored(candidates, listOfNotNull(query?.let { select.predicate(it.conditions) }), threshold, topK)
        return ScoredSearchPlan(statement.statement, statement.bindings)
    }

    /** The table a recorded index of [kind] on [label]'s [properties] is over; null when the index has not been made. */
    private fun made(kind: SchemaItemKind, label: String, properties: List<String>): String? = persistenceManager.query(
        QuerySpecification
            .withStatement("SELECT object_name FROM ${graph.catalog} WHERE kind = \$kind AND label = \$label AND properties = \$properties AND object_name IS NOT NULL")
            .bind(mapOf("kind" to kind.name, "label" to label, "properties" to OracleSchemaGrammar.json(properties)))
            .transform(String::class.java)
    ).firstOrNull()

    // ----- Deleting -----

    override fun delete(graphClass: Class<*>, where: String?, cascade: CascadeType): GraphStatement {
        val select = OracleSelect(graph, graphClass)
        val predicates = listOfNotNull(select.hasId(GraphBackend.ID_PARAM), where?.let(select::written))
        val cascaded = if (cascade == CascadeType.DELETE_ALL || cascade == CascadeType.DELETE_ORPHAN) select.cascaded(predicates) else null
        return if (cascaded == null) deleting(select, predicates) else cascading(select, predicates, cascaded, orphansOnly = cascade == CascadeType.DELETE_ORPHAN)
    }

    override fun deleteAll(graphClass: Class<*>, where: String?): GraphStatement =
        OracleSelect(graph, graphClass).let { select -> deleting(select, listOfNotNull(where?.let(select::written))) }

    override fun deleteAll(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement {
        requireNoSeek(query)
        val select = OracleSelect(graph, graphClass, labelled = labelled.current())
        return deleting(select, listOfNotNull(select.predicate(query.conditions, indexed = true)))
    }

    /**
     * Deletes the roots [predicates] select, whether or not they have the relationships the view
     * requires, as the Cypher delete does. A node's relationships go with it: the relationship table cascades.
     */
    private fun deleting(select: OracleSelect, predicates: List<String>): GraphStatement =
        GraphStatement(
            OracleSql.counted("DELETE FROM ${graph.nodes} ${select.root}\nWHERE ${select.selecting(predicates)}"),
            select.bindings(),
        )

    /**
     * Deletes the roots [predicates] select, and of the nodes [cascaded] reaches from them either every
     * one, or with [orphansOnly] those left with no relationship once the roots are gone.
     */
    private fun cascading(select: OracleSelect, predicates: List<String>, cascaded: String, orphansOnly: Boolean): GraphStatement {
        val roots = "FROM ${graph.nodes} ${select.root}\nWHERE ${select.selecting(predicates)}"
        // Lines joined, not a template: what is put in has lines of its own, each laid out under the one that opens it.
        val body = if (orphansOnly) {
            listOf(
                "SELECT nid BULK COLLECT INTO v_ids FROM (\n    ${cascaded.under()});",
                "DELETE $roots;",
                "v_count := SQL%ROWCOUNT;",
                "FOR i IN 1 .. v_ids.COUNT LOOP",
                "    DELETE FROM ${graph.nodes} n WHERE n.nid = v_ids(i) AND NOT EXISTS (SELECT 1 FROM ${graph.edges} e WHERE e.src = n.nid OR e.dst = n.nid);",
                "    v_count := v_count + SQL%ROWCOUNT;",
                "END LOOP;",
            )
        } else {
            listOf(
                "SELECT nid BULK COLLECT INTO v_ids FROM (\n    ${"SELECT ${select.root}.nid\n$roots\nUNION\n$cascaded".under()});",
                "FORALL i IN 1 .. v_ids.COUNT",
                "    DELETE FROM ${graph.nodes} WHERE nid = v_ids(i);",
                "v_count := v_ids.COUNT;",
            )
        }.joinToString("\n")
        return GraphStatement(OracleSql.counting(body, "TYPE t_ids IS TABLE OF NUMBER;\nv_ids t_ids;"), select.bindings())
    }

    /** A seek walks an order, which a count or a delete does not have: it would be ignored, and a delete would take every match. */
    private fun requireNoSeek(query: GraphQuerySpec<*>) =
        require(query.seekValues.isEmpty()) { "seek is only supported by loadAll; this operation would ignore the cursor" }

    // ----- Saving -----

    override fun save(request: SaveRequest): SaveStatement = saves.build(request)

    /**
     * A batch of fragments is one block for each [BATCH] of them. Every fragment is a step of its
     * block, stamped or not, and the block hands a row back for each: so every statement is among the
     * stamped, and no fragment is left to be saved alone.
     */
    override fun saveTogether(fragments: List<IndexedValue<Any>>, nullPolicy: NullPolicy, writeFields: (Class<*>) -> Set<String>?): FragmentBatch = FragmentBatch(
        stamped = fragments.chunked(BATCH).map { chunk ->
            val (text, bindings) = saves.together(chunk, nullPolicy, writeFields)
            QuerySpecification.withStatement(text).bind(bindings).transform(String::class.java)
        },
    )

    /**
     * A `patch` step for each row, [BATCH] to a block, and fewer where a vector is written: see [PatchRow.toAStatement]. What a null does is in the step: the property
     * is left out of what the step sets, and when the call clears, named among what it removes.
     */
    override fun patchAll(graphClass: Class<*>, properties: List<String>, rows: List<PatchRow>, clears: Boolean): List<QuerySpecification<String>> {
        val model = FragmentModel.from(graphClass)
        val key = requireNotNull(model.nodeIdProperty) { "${graphClass.simpleName} has no @NodeId field to find a node by." }
        return rows.chunked(PatchRow.toAStatement(model, properties, BATCH)).map { chunk ->
            val plan = WritePlan(graph)
            val shape = plan.shape(model.labels, key)
            chunk.forEach { row ->
                plan.patch(
                    shape, OracleSql.key(row.id), row.index.toString(),
                    set = row.values.filterValues { it != null },
                    unset = if (clears) row.values.filterValues { it == null }.keys.toList() else emptyList(),
                )
            }
            QuerySpecification.withStatement(plan.text(OracleWriteBlock.Returns.EACH_STAMP)).bind(plan.bindings()).transform(String::class.java)
        }
    }

    /**
     * One block with one `patch` step. The values are in the plan, and the predicate in the block's
     * text: so the text is the node's shape and the predicate, and the same for every id and value.
     */
    override fun patch(graphClass: Class<*>, id: Any, values: Map<String, Any?>, conditions: List<WhereCondition>): GraphStatement {
        val select = OracleSelect(graph, graphClass)
        val model = FragmentModel.from(select.rootFragment)
        val key = requireNotNull(model.nodeIdProperty) { "${model.clazz.simpleName} has no @NodeId field to find a node by." }
        val plan = WritePlan(graph)
        plan.patch(
            plan.shape(model.labels, key), OracleSql.key(id), "0",
            set = values.filterValues { it != null }, unset = values.filterValues { it == null }.keys.toList(),
        )
        return GraphStatement(plan.text(OracleWriteBlock.Returns.EACH_STAMP, select.meeting(conditions)), plan.bindings() + select.bindings())
    }

    /** A stamp that is null says the node is there and has none; no row, that the node is gone. */
    override fun foundStamp(root: StampWrite): GraphStatement = GraphStatement(
        "SELECT n.stamp FROM ${graph.nodes} n WHERE " +
            listOfNotNull("${OracleSql.property("n", root.idProperty)} = \$id", OracleSql.hasLabels("n", root.labelNames)).joinToString(" AND "),
        mapOf("id" to OracleSql.key(root.id)),
    )

    /**
     * A deadlock (`ORA-00060`) or a serialization failure (`ORA-08177`): another writer held a row
     * this statement wanted. Every lock a block takes waits, so a wait that ran out is not among them.
     */
    override fun contended(failure: Throwable): Boolean = failure is SQLException && failure.errorCode in CONTENTION

    // ----- Relationships on their own -----

    /** The step that finds the node [ref] names, which must be there. Gives the number later steps know it by. */
    private fun WritePlan.found(ref: NodeRef): Int {
        val node = shapeOf(ref)
        return ref(shape(node.labels, node.key), OracleSql.key(ref.id))
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

    /** The write block that carries out [steps], which write relationships of [type], and returns the number they counted. */
    private fun counting(type: String, steps: WritePlan.() -> Unit): GraphStatement {
        requireType(type)
        val plan = WritePlan(graph).apply(steps)
        return GraphStatement(plan.text(OracleWriteBlock.Returns.COUNT), plan.bindings())
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

    override fun related(from: NodeRef, type: String, direction: Direction, targetClass: Class<*>): GraphStatement {
        requireType(type)
        val source = shapeOf(from)
        val select = OracleSelect(graph, targetClass)
        val statement = select.load(listOf(select.relatedTo(source.labels, source.key, FROM_PARAM, type, direction)))
        return GraphStatement(statement.statement, statement.bindings + (FROM_PARAM to OracleSql.key(from.id)))
    }

    private companion object {
        /** The error codes of a deadlock and of a serialization failure. */
        val CONTENTION = setOf(60, 8177)

        /** The parameter [related] binds the stored id of the node it starts from as. */
        const val FROM_PARAM = "_fromId"

        /** The fragments one block saves: enough that a round trip is not what a batch costs, and a plan stays small. */
        const val BATCH = 500
    }
}
