package org.drivine.cypher.ast

/** An item of a `SET`, or of a `MERGE`'s `ON CREATE SET` or `ON MATCH SET`. */
sealed interface SetItem {

    /** `n.key = value`: the property of that name, removed where the value is null. */
    data class Property(val variable: String, val key: String, val value: Expression) : SetItem

    /** `n = map`: every property replaced by the map's, or a node's or a relationship's properties. */
    data class Replace(val variable: String, val value: Expression) : SetItem

    /** `n += map`: the map's entries set over the properties, a null one removing its key. */
    data class Add(val variable: String, val value: Expression) : SetItem

    /** `n:Label`: the labels added to a node. */
    data class Labels(val variable: String, val labels: List<String>) : SetItem
}
