package org.drivine.backend.sql

import org.drivine.annotation.Direction
import org.drivine.backend.GraphStatement
import org.drivine.query.GroupQuery
import org.drivine.query.dsl.OrderSpec
import org.drivine.query.dsl.OrderTerm
import org.drivine.query.dsl.SeekValueSpec
import org.drivine.query.dsl.WhereCondition

/**
 * The statements a SQL store writes for a class: what [SqlBackend] asks of a store's select. Each
 * store writes them in its own SQL; a select's [bindings] are the values of every statement it wrote.
 */
internal interface SqlSelect {

    /** The fragment the statement's roots are: the class itself, or a view's root. */
    val rootFragment: Class<*>

    /** The row the roots are read from. */
    val root: String

    /** The roots [predicates] select, each as a JSON document, in [orders], a page of them with [skip] and [limit]. */
    fun load(predicates: List<String>, orders: List<OrderTerm> = emptyList(), skip: Int? = null, limit: Int? = null): GraphStatement

    /** The number of roots [predicates] select. */
    fun count(predicates: List<String>): GraphStatement

    /** The groups [query] makes of the roots, a row each. */
    fun groups(query: GroupQuery): GraphStatement

    /** The predicate that selects the roots: their labels, the relationships the view requires, and [predicates]. */
    fun selecting(predicates: List<String>): String

    /** The values of the parameters of every statement this select wrote. */
    fun bindings(): Map<String, Any?>

    /** A predicate written by hand, in Cypher's terms, as the store's SQL. */
    fun written(predicate: String): String

    /** The predicate that selects the root whose id is the parameter [parameter]. */
    fun hasId(parameter: String): String

    /** The query a `patch` step asks of the node it found, when there are [conditions] for it to meet; null when there are none. */
    fun meeting(conditions: List<WhereCondition>): String?

    /**
     * The predicate [conditions] of the query DSL make on the root; null when there are none. [indexed]
     * when every root the statement reads by its labels meets it, which a store may read through an
     * index over the nodes of a label.
     */
    fun predicate(conditions: List<WhereCondition>, indexed: Boolean = false): String?

    /** The predicate that selects the rows after a keyset cursor. */
    fun after(orders: List<OrderSpec>, values: List<SeekValueSpec>, collectionSortCount: Int): String

    /** The roots among [candidates], each with its score, that meet [predicates] and [threshold], the best [limit] of them. */
    fun scored(candidates: String, predicates: List<String>, threshold: Double?, limit: Int?): GraphStatement

    /** The nodes a cascading delete reaches from the roots [predicates] select; null when the class cascades to nothing. */
    fun cascaded(predicates: List<String>): String?

    /** For each recursion a cascade follows, the query that finds a node beyond its depth. */
    fun cascadeDepthChecks(predicates: List<String>): List<Pair<CascadeRecursion, String>>

    /** The steps a cascade follows, each once. */
    fun cascadeSteps(): List<Step>

    /** The predicate that selects the roots related by [type] in [direction] to the node of [labels] whose [key] is the parameter [parameter]. */
    fun relatedTo(labels: List<String>, key: String, parameter: String, type: String, direction: Direction): String
}
