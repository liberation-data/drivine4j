package org.drivine.backend

import com.fasterxml.jackson.databind.JsonNode
import org.drivine.annotation.Direction
import org.drivine.manager.CascadeType
import org.drivine.manager.NodeRef
import org.drivine.manager.NullPolicy
import org.drivine.manager.RelateMode
import org.drivine.manager.RemovedTargets
import org.drivine.model.RelationshipModel
import org.drivine.query.PatchRow
import org.drivine.query.QuerySpecification
import org.drivine.query.SaveRequest
import org.drivine.query.SaveStatement
import org.drivine.query.ScoredSearchPlan
import org.drivine.query.StampWrite
import org.drivine.query.StoredPropertyKeys
import org.drivine.query.dsl.GraphQuerySpec
import org.drivine.query.dsl.GroupQuerySpec
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.WhereCondition
import org.drivine.query.grammar.CypherGrammar

/** A statement in the store's own language, and the values its parameters take. */
internal class GraphStatement(val statement: String, val bindings: Map<String, Any?> = emptyMap()) {
    /** The specification that runs the statement, with [more] bound beside its own values: an id, where the statement names one. */
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

    /** Whether a delete can cascade to the nodes it leaves with no relationship (`DELETE_ORPHAN`). */
    val supportsOrphanDelete: Boolean

    /** What to tell a caller refused `DELETE_ORPHAN`: which engine cannot, and why. */
    val orphanDeleteLimit: String?

    /**
     * Whether an index can serve an order, so that a load ordered without one is worth a word of
     * advice. Where it is false the object manager advises nothing.
     */
    val supportsIndexBackedOrdering: Boolean

    /**
     * Reads the property keys, and the labels recorded as an open `@NodeLabels` field's own, that a
     * stored node has, for a save that clears the stale ones. A backend whose save clears them where
     * it writes the node reads nothing here.
     */
    val storedKeys: StoredPropertyKeys

    // ----- Reading -----

    /** The load of every [graphClass] object, or of those that meet [where]. */
    fun loadAll(graphClass: Class<*>, where: String? = null): GraphStatement

    /**
     * The load of the [graphClass] objects [query] selects, in its order and its page. A seek and a
     * skip are refused together: a cursor says where the page begins.
     */
    fun loadAll(graphClass: Class<*>, query: GraphQuerySpec<*>): LoadPlan

    /** The load of the object whose id is bound as [ID_PARAM]. */
    fun load(graphClass: Class<*>): GraphStatement

    /** The count of every [graphClass] object, or of those that meet [where]. */
    fun count(graphClass: Class<*>, where: String? = null): GraphStatement

    /** The count of the [graphClass] objects that meet the conditions of [query]. A seek is refused: a count walks no order. */
    fun count(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement

    /**
     * The grouped read [query] asks of the [graphClass] nodes: one map for each group, a key under
     * `k0…` and an aggregate under `a0…`, as its handle names its column. A node key is the map a load
     * of its fragment returns. Refused before anything is written: a source that is a view, a read
     * with no key, and a condition that is not on the grouped nodes' own properties.
     */
    fun groups(graphClass: Class<*>, query: GroupQuerySpec<*>): GraphStatement

    /**
     * The search for the roots nearest [vector] by the embedding [property] names; null for the only
     * one the fragment declares. The index is asked for [searchK] candidates, or for [topK] when
     * that is null; [query], [threshold] and a view's required relationships then filter them, and
     * at most [topK] come back. [partitionLabel] names the label whose index is searched, in place
     * of the fragment's own.
     */
    fun nearest(
        graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, vector: List<Float>,
        topK: Int, threshold: Double?, searchK: Int?, partitionLabel: String?,
    ): ScoredSearchPlan

    /**
     * The search for the roots whose indexed text meets [text], by the full-text index that covers
     * [property]; null for the only one the fragment declares. [query] filters what the search finds,
     * and the [topK] best that score [threshold] or more come back.
     */
    fun matching(graphClass: Class<*>, property: String?, query: GraphQuerySpec<*>?, text: String, topK: Int, threshold: Double): ScoredSearchPlan

    // ----- Deleting -----

    /** The delete of the object whose id is bound as [ID_PARAM], if it also meets [where]. */
    fun delete(graphClass: Class<*>, where: String?, cascade: CascadeType): GraphStatement

    /** The delete of every [graphClass] root, or of those that meet [where]. Nothing a root is related to is deleted. */
    fun deleteAll(graphClass: Class<*>, where: String? = null): GraphStatement

    /**
     * The delete of the [graphClass] roots that meet the conditions of [query]. A seek is refused: it
     * would be ignored, and the delete would take every match.
     */
    fun deleteAll(graphClass: Class<*>, query: GraphQuerySpec<*>): GraphStatement

    // ----- Saving -----

    /** A whole save as one statement. */
    fun save(request: SaveRequest): SaveStatement

    /**
     * How [fragments] are saved several at a time, unchecked, each under its index: which go together
     * and by what statements is the backend's to say, as it is what its store can write so. See
     * [FragmentBatch].
     */
    fun saveTogether(fragments: List<IndexedValue<Any>>, nullPolicy: NullPolicy): FragmentBatch

    /**
     * The statements that write named properties of many [graphClass] nodes, and make none. Each
     * returns a row for every node it found, `index/changed=stamp`, which [org.drivine.query.PatchOutcomes]
     * reads. A row whose node is not there gives nothing back. The nodes are written in the order of
     * [rows], which the caller gives in the order to take their locks in: see [PatchRow.inLockOrder].
     *
     * @param graphClass the fragment the nodes are of
     * @param properties the stored names of the properties written, which every row holds
     * @param clears whether a null in a row clears the property; when false, it leaves it as it is
     */
    fun patchAll(graphClass: Class<*>, properties: List<String>, rows: List<PatchRow>, clears: Boolean): List<QuerySpecification<String>>

    /**
     * The statement that writes properties of the one [graphClass] node whose id is [id], if the node
     * meets [conditions], and makes no node. It returns a row for the node, `0/flag=stamp`, the flag
     * being [org.drivine.query.PatchOutcomes.CHANGED], [org.drivine.query.PatchOutcomes.UNCHANGED] or,
     * where the node does not meet the conditions, [org.drivine.query.PatchOutcomes.UNMET]. No row
     * says no node has the id.
     *
     * The conditions are read under the node's lock, so what they compare is what the last writer
     * committed. A node that changes gets a new first token in its stamp, and one that never had a
     * stamp is given one whatever the patch did to it.
     *
     * @param graphClass the fragment the node is of, or a view whose root it is
     * @param values what to write, by stored property name, in the order to write it: a null removes
     *   the property
     * @param conditions the predicate of the query DSL, which for a view may name a relationship
     */
    fun patch(graphClass: Class<*>, id: Any, values: Map<String, Any?>, conditions: List<WhereCondition>): GraphStatement

    /** Reads the stamp the node of [root] carries, as `''` when it has none; no row when the node is gone. */
    fun foundStamp(root: StampWrite): GraphStatement

    /**
     * Whether [failure] is the store turning a statement away because another writer was changing
     * the same node at that moment: a conflict or a deadlock, which a second attempt can get past.
     * A lost connection is not: the statement may have been applied.
     */
    fun contended(failure: Throwable): Boolean

    // ----- Relationships on their own -----

    /**
     * Joins [from] to [to] with a relationship of [type] that carries [properties]: once under
     * [RelateMode.MERGE], one more each time under [RelateMode.CREATE]. Neither node is made: the
     * statement returns 0, and has written nothing, when either is not there.
     */
    fun relate(from: NodeRef, to: NodeRef, type: String, properties: Map<String, Any?>, mode: RelateMode): GraphStatement

    /** Removes the relationships of [type] from [from] to [to], and returns how many there were. */
    fun unrelate(from: NodeRef, to: NodeRef, type: String): GraphStatement

    /** Removes the relationships of [type] that [from] has in [direction], and returns how many there were. */
    fun unrelateAll(from: NodeRef, type: String, direction: Direction): GraphStatement

    /** The load of the [targetClass] fragments [from] is related to. */
    fun related(from: NodeRef, type: String, direction: Direction, targetClass: Class<*>): GraphStatement

    companion object {
        /** The parameter [load] and [delete] name the object's id by. */
        const val ID_PARAM = "id"
    }
}

/**
 * The fragments of a batch as a backend saves them.
 *
 * @param unstamped the statements that save fragments no stamp is handed back for. Their rows say nothing
 * @param stamped the statements that save fragments whose stamp is handed back. Each returns a row
 *   for every fragment it saves, `index/found=stamp`: `found` the stamp the node carried before,
 *   empty when it had none
 * @param alone the fragments no statement here saves: each is saved by a statement of its own, as
 *   an object saved by itself is
 */
internal class FragmentBatch(
    val unstamped: List<QuerySpecification<*>> = emptyList(),
    val stamped: List<QuerySpecification<String>> = emptyList(),
    val alone: List<IndexedValue<Any>> = emptyList(),
)
