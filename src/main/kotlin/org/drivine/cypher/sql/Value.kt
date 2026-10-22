package org.drivine.cypher.sql

/**
 * A compiled expression: the SQL that gives its value, and what kind of value it is. A Cypher value is
 * JSON on a SQL store, as a node's properties are: so a comparison is JSON's, and `'7'` is not `7`. A
 * node and a relationship are their rows' columns, so a pattern can join to them and a projection can
 * give their properties.
 */
sealed interface Value {

    /** A JSON value: `jsonb`, or with [ordered] `json`, whose object keys keep the order they were written in. */
    data class Json(val sql: String, val ordered: Boolean = false) : Value

    /** A truth value: SQL's boolean, with its null for Cypher's. */
    data class Bool(val sql: String) : Value

    /** A node: the columns of its row. */
    data class Node(val nid: String, val labels: String, val props: String, val stamp: String) : Value

    /** A relationship: the columns of its row. */
    data class Relationship(val eid: String, val src: String, val dst: String, val type: String, val props: String) : Value
}
