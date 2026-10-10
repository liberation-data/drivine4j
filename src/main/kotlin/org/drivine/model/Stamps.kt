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

    /**
     * The column a save statement returns its stamps under: the root's, then each stamped target's.
     * Drivine's own; nothing a caller needs.
     */
    const val STAMP_COLUMN = "stamps"

    /** A property a checked save sets and removes in one statement, to hold the node's write lock while it compares. */
    internal const val LOCK = "`__drivine.lock`"

    /**
     * The clauses that take the write lock of each node in [aliases] and leave it as it was: a property
     * is set and removed again. What a statement reads of a node after this is what the last writer
     * committed, and stays so until the statement ends. Without it, a statement can decide what it
     * changes from a node another writer is changing at that moment. The stamp itself cannot serve: a
     * statement reads back its own write, not what another writer committed.
     */
    internal fun lock(vararg aliases: String): String =
        "SET ${aliases.joinToString(", ") { "$it.$LOCK = true" }} REMOVE ${aliases.joinToString(", ") { "$it.$LOCK" }}"

    internal const val NEW_PARAM = "_stamp"
    internal const val EXPECTED_PARAM = "_expectedStamp"

    /** The length of one token; a stamp is two, and the colon between them. */
    internal const val TOKEN = 16

    /** The variable a statement that stamps a node holds the stamp it found the node with: empty when it had none. */
    internal const val FOUND = "_found"

    /** A property a save sets on a node it creates and removes again in the same statement, to tell it from one it found. */
    internal const val MADE = "`__drivine.made`"

    /** What a statement reports as the stamp it found on a node that was there and had none. A node it made reports none at all. */
    internal const val NEVER_STAMPED = "-"

    /** Follows a `MERGE` of [alias]: marks the node when the statement made it. [unmark] removes the mark. */
    internal fun onCreate(alias: String): String = "ON CREATE SET $alias.$MADE = true"

    /** The `REMOVE` item that takes the mark of [onCreate] off again. */
    internal fun unmark(alias: String): String = "$alias.$MADE"

    /**
     * The expression for the stamp [alias] was found with, after a `MERGE` followed by [onCreate]:
     * empty for a node the statement made, [NEVER_STAMPED] for one that was there without a stamp.
     */
    internal fun foundOf(alias: String): String =
        "CASE WHEN $alias.$MADE IS NOT NULL THEN '' ELSE coalesce($alias.$QUOTED, '$NEVER_STAMPED') END"

    /**
     * The stamp a save hands back for a node that now carries [now], to an object that [carried] a
     * stamp when the node was [found] with one, before the save wrote anything. Each token of [now]
     * is handed back only if what it speaks for was as the object's stamp says: the node's own data
     * for the first, its relationships for the second. Otherwise the object keeps its own token, so a
     * later save of it that would overwrite what another writer changed is refused.
     *
     * An object that carried no stamp is handed the whole stamp of a node the save made, [found]
     * being empty then. Of a node that was already there it is handed the node token alone: its
     * lists did not come from the store, so its stamp does not vouch for the node's relationships,
     * and a save of it that replaces a relationship list is refused until it is loaded.
     */
    internal fun handedBack(carried: String?, found: String, now: String): String {
        if (carried == null) return if (found.isEmpty()) now else withoutLinks(now)
        // Found exactly as loaded, whatever shape the stamp had: a stored stamp that is not two
        // tokens is replaced whole, and the object is handed what replaced it.
        if (carried == found) return now
        val node = if (nodeToken(carried) == nodeToken(found)) nodeToken(now) else nodeToken(carried)
        val links = if (linksToken(carried) == linksToken(found)) linksToken(now) else linksToken(carried)
        return "$node:${links.orEmpty()}"
    }

    /** [stamp] with its node token alone: it speaks for the node's own data and for none of its relationships. */
    internal fun withoutLinks(stamp: String): String = "${nodeToken(stamp)}:"

    /**
     * A new stamp: both tokens new. A statement takes from it the token it replaces. Drivine's own
     * statements use it; Cypher of yours marks a node with [setClause] or [linksClause].
     */
    fun fresh(): String = "${token()}:${token()}"

    private fun token(): String = String.format("%016x", ThreadLocalRandom.current().nextLong())

    /** The token of [stamp] that speaks for the node's properties and labels. */
    internal fun nodeToken(stamp: String): String = stamp.substringBefore(':')

    /** The token of [stamp] that speaks for the node's relationships; null when [stamp] is not two tokens. */
    internal fun linksToken(stamp: String): String? = stamp.substringAfter(':', "").ifEmpty { null }

    /** The expression for the relationship token of the stamp [alias] carries. */
    internal fun linksTokenOf(alias: String): String = "right($alias.$QUOTED, $TOKEN)"

    /** The expression for the node token of the stamp [alias] carries. */
    internal fun nodeTokenOf(alias: String): String = "left($alias.$QUOTED, $TOKEN)"

    /** Whether [stamp] is two tokens joined by a colon, as every stamp a save leaves is. */
    internal fun isTwoTokens(stamp: String): Boolean = stamp.length == 2 * TOKEN + 1 && stamp[TOKEN] == ':'

    /**
     * Whether [stamp] has a node token to compare on its own: a token and a colon, whatever follows.
     * A stamp of another shape, as only something other than a save can have stored, is compared whole.
     */
    internal fun hasNodeToken(stamp: String): Boolean = stamp.length > TOKEN && stamp[TOKEN] == ':'

    /**
     * The expression for the stamp [alias] carries when it is two tokens joined by a colon, and null
     * otherwise. A stored stamp of any other shape, which no save leaves, counts as none: whatever
     * is written to it replaces it whole, so a node never keeps a part of one.
     */
    internal fun twoTokensOf(alias: String): String =
        "CASE WHEN size($alias.$QUOTED) = ${2 * TOKEN + 1} AND substring($alias.$QUOTED, $TOKEN, 1) = ':' THEN $alias.$QUOTED ELSE null END"

    /**
     * The `SET` item that replaces the node token of [alias]'s stamp with that of [offered] when
     * [condition] holds. [offered] is an expression for a whole stamp, which a node takes whole when
     * it has none, or one that is not two tokens: that one is replaced whether or not [condition] holds.
     */
    internal fun restamp(alias: String, condition: String, offered: String): String =
        "$alias.$QUOTED = " +
            whenever(alias, condition, "left($offered, $TOKEN) + coalesce(right(${twoTokensOf(alias)}, ${TOKEN + 1}), right($offered, ${TOKEN + 1}))")

    /** As [restamp], for the token that speaks for the node's relationships. */
    internal fun relink(alias: String, condition: String, offered: String): String =
        "$alias.$QUOTED = " +
            whenever(alias, condition, "coalesce(left(${twoTokensOf(alias)}, ${TOKEN + 1}), left($offered, ${TOKEN + 1})) + right($offered, $TOKEN)")

    /**
     * [stamp] when [condition] holds or [alias] has no stamp of two tokens, else the stamp [alias]
     * has; [stamp] alone for a condition that always holds.
     */
    private fun whenever(alias: String, condition: String, stamp: String): String =
        if (condition == ALWAYS) stamp else "CASE WHEN ($condition) OR ${twoTokensOf(alias)} IS NULL THEN $stamp ELSE $alias.$QUOTED END"

    /** The [restamp] or [relink] condition of a stamp that is always replaced. */
    internal const val ALWAYS = "true"

    /** A random token, made by the engine. */
    private const val ENGINE_TOKEN = "left(replace(randomUUID(), '-', ''), $TOKEN)"

    /**
     * The `SET` item that marks the node bound to [alias] as changed, for Cypher that changes a
     * stamped node's properties or labels without going through an object manager:
     *
     * ```kotlin
     * "MATCH (p:Person {id: \$id}) SET p.name = \$name, ${Stamps.setClause("p")}"
     * ```
     *
     * It replaces the token that speaks for the node's own data, and leaves the one that speaks for
     * its relationships. A node with no stamp is given one, and a stored stamp that is not two tokens
     * is replaced whole. Cypher that names the property itself quotes it, as [QUOTED] does.
     */
    @JvmStatic
    fun setClause(alias: String): String =
        "$alias.$QUOTED = $ENGINE_TOKEN + ':' + coalesce(right(${twoTokensOf(alias)}, $TOKEN), $ENGINE_TOKEN)"

    /**
     * The `SET` item that marks the relationships of the node bound to [alias] as changed, for Cypher
     * that adds or removes a relationship, or changes one's properties, without going through an
     * object manager. Use it for both ends:
     *
     * ```kotlin
     * "MATCH (a:Person {id: \$a}), (b:Person {id: \$b}) CREATE (a)-[:KNOWS]->(b) SET ${Stamps.linksClause("a")}, ${Stamps.linksClause("b")}"
     * ```
     *
     * It replaces the token that speaks for the node's relationships, and leaves the one that speaks
     * for its own data. A node with no stamp is given one, and a stored stamp that is not two tokens
     * is replaced whole.
     */
    @JvmStatic
    fun linksClause(alias: String): String =
        "$alias.$QUOTED = coalesce(left(${twoTokensOf(alias)}, $TOKEN), $ENGINE_TOKEN) + ':' + $ENGINE_TOKEN"
}
