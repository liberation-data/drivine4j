package org.drivine.backend

import com.fasterxml.jackson.databind.JsonNode
import org.drivine.annotation.Direction
import org.drivine.manager.CascadeType
import org.drivine.manager.NodeRef
import org.drivine.manager.NullPolicy
import org.drivine.manager.RelateMode
import org.drivine.manager.RemovedTargets
import org.drivine.model.RelationshipModel
import org.drivine.query.QuerySpecification
import org.drivine.query.SaveStatement
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.StampWrite
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.grammar.CypherGrammar

/** A statement in the store's own language, and the values its parameters take. */
internal class GraphStatement(val statement: String, val bindings: Map<String, Any?> = emptyMap()) {
    fun spec(more: Map<String, Any?> = emptyMap()): QuerySpecification<Any> =
        QuerySpecification.withStatement(statement).bind(bindings + more)
}

/**
 * The statement of a load through the query DSL, with what the object manager needs to advise on
 * indexes: the orders on the root, and whether the load seeks from a cursor.
 */
internal class LoadPlan(val statement: GraphStatement, val rootOrders: List<OrderSpec>, val keyset: Boolean)

/**
 * What the object managers ask of a store. An object manager knows the model, hydrates what comes
 * back and decides which stamps to hand on; a backend writes each operation as a statement in the
 * store's language. [CypherBackend] does so for the graph engines.
 *
 * Every statement returns one column, as the Cypher statements always have:
 *
 * - a load, one map for each root; a scored search, `{value, score}` for each;
 * - a count, one number; a delete, the number deleted;
 * - a save, one string of stamps (see [SaveStatement]), or no row when a checked root is stale;
 * - `relate`, `unrelate` and `unrelateAll`, the number of relationships.
 *
 * A raw `where` is a predicate in the store's language, as the caller wrote it.
 */
internal interface GraphBackend {

    /** The grammar of a backend that speaks Cypher; null for one that does not. What only Cypher does asks for it. */
    val cypherGrammar: CypherGrammar?

    val supportsOrphanDelete: Boolean

    /** What to tell a caller refused `DELETE_ORPHAN`: which engine cannot, and why. */
    val orphanDeleteLimit: String?

    val supportsIndexBackedOrdering: Boolean

    val storedKeys: StoredPropertyKeys

    // ----- Reading -----

    fun loadAll(graphClass: Class<*>, where: String? = null): GraphStatement

    fun loadAll(graphClass: Class<*>, query: GraphQuerySpec<*>): LoadPlan

    /** The load of the object whose id is bound as [ID_PARAM]. */
    fun load(graphClass: Class<*>): GraphStatement

    fun count(graphClass: Class<*>, where: String? = null): GraphStatement

    fun count(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement

    fun nearest(
        graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, vector: List<Float>,
        topK: Int, threshold: Double?, searchK: Int?, partitionLabel: String?,
    ): ScoredSearchPlan

    fun matching(graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, text: String, topK: Int, threshold: Double): ScoredSearchPlan

    // ----- Deleting -----

    /** The delete of the object whose id is bound as [ID_PARAM], if it also meets [where]. */
    fun delete(graphClass: Class<*>, where: String?, cascade: CascadeType): GraphStatement

    fun deleteAll(graphClass: Class<*>, where: String? = null): GraphStatement

    fun deleteAll(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement

    // ----- Saving -----

    /** A whole save as one statement. The parameters are those of `SaveStatementBuilder.build`. */
    fun save(
        obj: Any,
        checked: Boolean,
        nullPolicy: NullPolicy,
        rootWriteFields: Set<String>? = null,
        replaced: (RelationshipModel) -> RemovedTargets? = { null },
        before: JsonNode? = null,
        after: JsonNode? = null,
        createsRoot: Boolean = true,
    ): SaveStatement

    /**
     * The statements that save [fragments] several at a time, unchecked, each fragment under its
     * index. Each returns a row for every fragment it saves, `index/found=stamp`: `found` the stamp the
     * node carried before, empty when it had none. Null when the backend saves each fragment as a
     * statement of its own.
     */
    fun saveTogether(fragments: List<IndexedValue<Any>>, nullPolicy: NullPolicy): List<QuerySpecification<String>>? = null

    /** Reads the stamp the node of [root] carries, as `''` when it has none; no row when the node is gone. */
    fun foundStamp(root: StampWrite): GraphStatement

    /**
     * Whether [failure] is the store turning a statement away because another writer was changing
     * the same node at that moment: a conflict or a deadlock, which a second attempt can get past.
     * A lost connection is not: the statement may have been applied.
     */
    fun contended(failure: Throwable): Boolean

    // ----- Relationships on their own -----

    fun relate(from: NodeRef, to: NodeRef, type: String, properties: Map<String, Any?>, mode: RelateMode): GraphStatement

    fun unrelate(from: NodeRef, to: NodeRef, type: String): GraphStatement

    fun unrelateAll(from: NodeRef, type: String, direction: Direction): GraphStatement

    /** The load of the [targetClass] fragments [from] is related to. */
    fun related(from: NodeRef, type: String, direction: Direction, targetClass: Class<*>): GraphStatement

    companion object {
        const val ID_PARAM = "id"
    }
}
