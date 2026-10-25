package org.drivine.cypher.sql

/**
 * A compiled expression: the SQL that gives its value, and what kind of value it is. A Cypher value is
 * JSON on a SQL store, as a node's properties are: so a comparison is JSON's, and `'7'` is not `7`. A
 * node and a relationship are their rows' columns, so a pattern can join to them and a projection can
 * give their properties.
 */
sealed interface Value {

    /**
     * A JSON value: `jsonb`, or with [ordered] `json`, whose object keys keep the order they were written in. Where
     * the statement makes it of a [temporal] kind (a constructor, a parameter of a `java.time` type, temporal
     * arithmetic), it is that kind's ISO text; where it makes it a list whose every item is of one, [items] is that
     * kind, so an item taken from it, unwound or indexed, is of it. Where it is a map or a list the statement builds that holds
     * a node or a relationship, [identity] is the same value, as JSON that compares, with each node or relationship in it as
     * its id: it is compared by that, as a graph engine compares what it holds by identity, not by its properties.
     */
    data class Json(
        val sql: String,
        val ordered: Boolean = false,
        val temporal: Temporal? = null,
        val items: Temporal? = null,
        val identity: String? = null,
        /** Whether the statement makes it a list, and with [itemLists] a list of lists: a store may sort a list as Cypher does only then. */
        val list: Boolean = false,
        val itemLists: Boolean = false,
    ) : Value

    /** A truth value: SQL's boolean, with its null for Cypher's. */
    data class Bool(val sql: String) : Value

    /** A node: the columns of its row. */
    data class Node(val nid: String, val labels: String, val props: String, val stamp: String) : Value

    /** A relationship: the columns of its row. */
    data class Relationship(val eid: String, val src: String, val dst: String, val type: String, val props: String) : Value

    /**
     * A list of nodes, or with [relationships] of relationships: [ids], the JSON list of their ids, in order. It is
     * given as their properties, and read as the nodes or relationships themselves where it is unwound, so a write
     * reaches them, and where it is compared, indexed, searched with `IN` or iterated, so they compare by their ids.
     * Where `collect` gathers it, [gathered] is the JSON list of their properties, gathered beside the ids, for
     * a store reads no aggregate of the query it is in from a subquery.
     */
    data class Entities(val ids: String, val relationships: Boolean, val gathered: String? = null) : Value

    /** A path: the ids of its nodes, and of its relationships, in the order the pattern names them. */
    data class Path(val nids: String, val eids: String) : Value
}
