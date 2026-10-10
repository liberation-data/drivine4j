package org.drivine.model

import java.util.concurrent.ThreadLocalRandom

/**
 * The stamp Drivine keeps on a node, so a later save can tell whether the node is as it was when the
 * object was loaded. It is two random tokens joined by a colon, as `3fa9c1d27b40e8a6:91d0f4b2c7ee5a13`:
 *
 * - the first is replaced when a save changes the node's properties or labels;
 * - the second is replaced when a relationship of the node is added or removed, or its properties
 *   change, whichever end it is written from.
 *
 * A save compares the part it would overwrite: the first always, the second too when it replaces a
 * relationship list.
 */
object Stamps {
    /** The node property the stamp is stored under. */
    const val PROPERTY = "__drivine.stamp"

    /** [PROPERTY] as it is written in a statement. */
    const val QUOTED = "`$PROPERTY`"

    /** The column a save statement returns its stamps under: the root's, then each stamped target's. */
    const val STAMP_COLUMN = "stamps"

    /** A property a checked save sets and removes in one statement, to hold the node's write lock while it compares. */
    internal const val LOCK = "`__drivine.lock`"

    internal const val NEW_PARAM = "_stamp"
    internal const val EXPECTED_PARAM = "_expectedStamp"

    /** The length of one token; a stamp is two, and the colon between them. */
    private const val TOKEN = 16

    /** A new stamp: both tokens new. A statement takes from it the token it replaces. */
    fun fresh(): String = "${token()}:${token()}"

    private fun token(): String = String.format("%016x", ThreadLocalRandom.current().nextLong())

    /** The token of [stamp] that speaks for the node's properties and labels. */
    internal fun nodeToken(stamp: String): String = stamp.substringBefore(':')

    /** The expression for the node token of the stamp [alias] carries. */
    internal fun nodeTokenOf(alias: String): String = "left($alias.$QUOTED, $TOKEN)"

    /**
     * The `SET` item that replaces the node token of [alias]'s stamp with that of [offered] when
     * [condition] holds. [offered] is an expression for a whole stamp, which a node that has none takes whole.
     */
    internal fun restamp(alias: String, condition: String, offered: String): String =
        "$alias.$QUOTED = CASE WHEN $condition THEN left($offered, $TOKEN) + coalesce(right($alias.$QUOTED, ${TOKEN + 1}), right($offered, ${TOKEN + 1})) ELSE $alias.$QUOTED END"

    /** As [restamp], for the token that speaks for the node's relationships. */
    internal fun relink(alias: String, condition: String, offered: String): String =
        "$alias.$QUOTED = CASE WHEN $condition THEN coalesce(left($alias.$QUOTED, ${TOKEN + 1}), left($offered, ${TOKEN + 1})) + right($offered, $TOKEN) ELSE $alias.$QUOTED END"

    /** A random token, made by the engine. */
    private const val ENGINE_TOKEN = "left(replace(randomUUID(), '-', ''), $TOKEN)"

    /**
     * The `SET` item that marks the node bound to [alias] as changed, for Cypher that changes a
     * stamped node's properties or labels without going through an object manager:
     *
     * ```kotlin
     * "MATCH (p:Person {id: \$id}) SET p.name = \$name, ${Stamps.setClause("p")}"
     * ```
     */
    @JvmStatic
    fun setClause(alias: String): String =
        "$alias.$QUOTED = $ENGINE_TOKEN + ':' + coalesce(right($alias.$QUOTED, $TOKEN), $ENGINE_TOKEN)"

    /**
     * The `SET` item that marks the relationships of the node bound to [alias] as changed, for Cypher
     * that adds or removes a relationship without going through an object manager. Use it for both ends:
     *
     * ```kotlin
     * "MATCH (a:Person {id: \$a}), (b:Person {id: \$b}) CREATE (a)-[:KNOWS]->(b) SET ${Stamps.linksClause("a")}, ${Stamps.linksClause("b")}"
     * ```
     */
    @JvmStatic
    fun linksClause(alias: String): String =
        "$alias.$QUOTED = coalesce(left($alias.$QUOTED, $TOKEN), $ENGINE_TOKEN) + ':' + $ENGINE_TOKEN"
}
