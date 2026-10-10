package org.drivine.model

import java.util.UUID

/**
 * The stamp Drivine keeps on a node: a value an object-manager save replaces when it changes the
 * node, so a later save can tell whether the node is as it was when the object was loaded.
 */
object Stamps {
    /** The node property the stamp is stored under. */
    const val PROPERTY = "__drivine.stamp"

    /** [PROPERTY] as it is written in a statement. */
    const val QUOTED = "`$PROPERTY`"

    /** The column a save statement returns the node's stamp under, once it has run. */
    const val STAMP_COLUMN = "stamp"

    internal const val NEW_PARAM = "_stamp"
    internal const val EXPECTED_PARAM = "_expectedStamp"

    /** A new stamp. */
    fun fresh(): String = UUID.randomUUID().toString()

    /**
     * The `SET` item that gives the node bound to [alias] a new stamp, for Cypher that changes a
     * stamped node without going through an object manager:
     *
     * ```kotlin
     * "MATCH (p:Person {id: \$id}) SET p.name = \$name, ${Stamps.setClause("p")}"
     * ```
     */
    @JvmStatic
    fun setClause(alias: String): String = "$alias.$QUOTED = randomUUID()"
}
