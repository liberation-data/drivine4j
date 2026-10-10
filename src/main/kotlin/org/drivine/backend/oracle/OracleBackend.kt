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
import org.drivine.model.FragmentModel
import org.drivine.model.RelationshipModel
import org.drivine.query.FullTextIndexResolver
import org.drivine.query.SaveStatement
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.StampWrite
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.VectorIndexResolver
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
    objectMapper: ObjectMapper,
) : GraphBackend {

    private val graph = OracleGraph(persistenceManager.graphName ?: OracleGraph.DEFAULT_NAME).also { it.ensure(persistenceManager) }

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
    ): ScoredSearchPlan {
        val select = OracleSelect(graph, graphClass)
        val index = VectorIndexResolver.resolve(select.rootFragment, property, "_", "_")
        // What the search measures is the nearest searchK, and of what is left of them topK come back.
        val candidates = select.nearestTo(index.property, index.similarity, partitionLabel, vector, searchK ?: topK)
        val statement = select.scored(candidates, listOfNotNull(query?.let { select.predicate(it.conditions) }), threshold, topK.takeIf { searchK != null })
        return ScoredSearchPlan(statement.statement, statement.bindings, topK, searchK ?: topK)
    }

    override fun matching(graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, text: String, topK: Int, threshold: Double): ScoredSearchPlan {
        val select = OracleSelect(graph, graphClass)
        val index = FullTextIndexResolver.resolve(select.rootFragment, property, "_", "_")
        val candidates = select.matching(index.properties, TextQuery.parse(text))
        val statement = select.scored(candidates, listOfNotNull(query?.let { select.predicate(it.conditions) }), threshold, topK)
        return ScoredSearchPlan(statement.statement, statement.bindings)
    }

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
        val select = OracleSelect(graph, graphClass)
        return deleting(select, listOfNotNull(select.predicate(query.conditions)))
    }

    /** Deletes the roots [predicates] select. A node's relationships go with it: the relationship table cascades. */
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
        val roots = "${graph.nodes} ${select.root}\n    WHERE ${select.selecting(predicates)}"
        val body = if (orphansOnly) {
            """
    SELECT nid BULK COLLECT INTO v_ids FROM (
    $cascaded
    );
    DELETE FROM $roots;
    v_count := SQL%ROWCOUNT;
    FOR i IN 1 .. v_ids.COUNT LOOP
        DELETE FROM ${graph.nodes} n WHERE n.nid = v_ids(i) AND NOT EXISTS (SELECT 1 FROM ${graph.edges} e WHERE e.src = n.nid OR e.dst = n.nid);
        v_count := v_count + SQL%ROWCOUNT;
    END LOOP;"""
        } else {
            """
    SELECT nid BULK COLLECT INTO v_ids FROM (
    SELECT ${select.root}.nid FROM $roots
    UNION
    $cascaded
    );
    FORALL i IN 1 .. v_ids.COUNT
        DELETE FROM ${graph.nodes} WHERE nid = v_ids(i);
    v_count := v_ids.COUNT;"""
        }
        val text = """
DECLARE
    TYPE t_ids IS TABLE OF NUMBER;
    v_ids   t_ids;
    v_count NUMBER := 0;
    v_rows  SYS_REFCURSOR;
BEGIN$body
    OPEN v_rows FOR SELECT v_count FROM dual;
    DBMS_SQL.RETURN_RESULT(v_rows);
END;""".trim()
        return GraphStatement(text, select.bindings())
    }

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
    ): SaveStatement = saves.build(obj, checked, nullPolicy, rootWriteFields, replaced, before, after)

    /** A stamp that is null says the node is there and has none; no row, that the node is gone. */
    override fun foundStamp(root: StampWrite): GraphStatement = GraphStatement(
        "SELECT n.stamp FROM ${graph.nodes} n WHERE " +
            listOfNotNull("${OracleSql.property("n", root.idProperty)} = \$id", OracleSql.hasLabels("n", root.labels.split(':'))).joinToString(" AND "),
        mapOf("id" to root.id.toString()),
    )

    /**
     * A deadlock (`ORA-00060`), a serialization failure (`ORA-08177`) or a wait that ran out
     * (`ORA-30006`, `ORA-00054`): another writer held a row this statement wanted.
     */
    override fun contended(failure: Throwable): Boolean = failure is SQLException && failure.errorCode in CONTENTION

    // ----- Relationships on their own -----

    /** The step that finds the node [ref] names, which must be there. Gives the number later steps know it by. */
    private fun WritePlan.found(ref: NodeRef): Int {
        val model = FragmentModel.from(ref.fragmentClass)
        val node = node()
        add("ref", "r" to node, "s" to shape((model.labels + ref.labels).distinct(), keyOf(ref, model)), "k" to ref.id.toString())
        return node
    }

    private fun keyOf(ref: NodeRef, model: FragmentModel): String = requireNotNull(model.nodeIdProperty) {
        "${ref.fragmentClass.simpleName} has no @NodeId field, so a node of it cannot be referred to by id."
    }

    private fun counting(type: String, steps: WritePlan.() -> Unit): GraphStatement {
        require(type.isNotBlank()) { "A relationship needs a type." }
        val plan = WritePlan(graph).apply(steps)
        return GraphStatement(plan.text(stamps = false), plan.bindings())
    }

    override fun relate(from: NodeRef, to: NodeRef, type: String, properties: Map<String, Any?>, mode: RelateMode): GraphStatement = counting(type) {
        link(found(from), found(to), type, Direction.OUTGOING, properties.filterValues { it != null }, always = mode == RelateMode.CREATE)
    }

    override fun unrelate(from: NodeRef, to: NodeRef, type: String): GraphStatement = counting(type) {
        add("cut", "from" to found(from), "to" to found(to), "type" to type, "dir" to Direction.OUTGOING.code)
    }

    override fun unrelateAll(from: NodeRef, type: String, direction: Direction): GraphStatement = counting(type) {
        add("cut", "from" to found(from), "type" to type, "dir" to direction.code)
    }

    override fun related(from: NodeRef, type: String, direction: Direction, targetClass: Class<*>): GraphStatement {
        require(type.isNotBlank()) { "A relationship needs a type." }
        val model = FragmentModel.from(from.fragmentClass)
        val select = OracleSelect(graph, targetClass)
        val statement = select.load(listOf(select.relatedTo((model.labels + from.labels).distinct(), keyOf(from, model), FROM_PARAM, type, direction)))
        return GraphStatement(statement.statement, statement.bindings + (FROM_PARAM to from.id.toString()))
    }

    private fun notYet(what: String): Nothing = throw UnsupportedOperationException("$what is not yet available on Oracle.")

    private companion object {
        val CONTENTION = setOf(60, 8177, 30006, 54)
        const val FROM_PARAM = "_fromId"
    }
}
