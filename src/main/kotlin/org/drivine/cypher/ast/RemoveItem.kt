package org.drivine.cypher.ast

/** An item of a `REMOVE`. */
sealed interface RemoveItem {

    /** `n.key`: the property of that name. */
    data class Property(val variable: String, val key: String) : RemoveItem

    /** `n:Label`: the labels taken from a node. */
    data class Labels(val variable: String, val labels: List<String>) : RemoveItem
}
