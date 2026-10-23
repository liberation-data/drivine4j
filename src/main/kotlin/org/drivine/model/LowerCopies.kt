package org.drivine.model

import org.drivine.schema.SchemaGrammar

/**
 * The lowercased copy a node keeps of each `@CaseInsensitive` property, so that a case-insensitive
 * lookup is a plain comparison an index can serve. The copy of `email` is stored under
 * `__drivine.lower.email`, beside the stamp, and is the database's own lowercasing of the property:
 * `toLower` on the graph engines, `LOWER` on Oracle and Postgres. A lookup lowercases what it is
 * given the same way, so the copy and the value compared with it are lowercased alike.
 *
 * Every Drivine write of the node sets the copy from the property as stored once the write is done,
 * and removes it where the property has no value. The copy is not part of what tells whether a save
 * changed the node: a save that leaves the property as it was gives the node no new stamp, whether or
 * not the copy was already there.
 */
object LowerCopies {
    /** Prefix of the property a copy is stored under. */
    const val PREFIX = "${NodeLabelsModel.RESERVED_PREFIX}lower."

    /** The property the copy of [property] is stored under. */
    @JvmStatic
    fun of(property: String): String = PREFIX + property

    /**
     * The `SET` item that writes the copy of [property] on the node bound to [alias], from what the
     * node holds: for Cypher of your own that writes a `@CaseInsensitive` property, after it has
     * written it.
     *
     * ```kotlin
     * "MATCH (a:Account {id: \$id}) SET a.email = \$email SET ${LowerCopies.setClause("a", "email")}"
     * ```
     *
     * Write it in a `SET` of its own, after the one that writes the property: an engine may read
     * every value of one `SET` before it writes any.
     */
    @JvmStatic
    fun setClause(alias: String, property: String): String =
        "$alias.${SchemaGrammar.quoted(of(property))} = toLower($alias.${SchemaGrammar.identifier(property)})"

    /**
     * The `SET` clause that writes the copy of each of [properties] on [alias], or null when there are
     * none: what a save of a fragment with `@CaseInsensitive` fields adds after it has written them.
     */
    internal fun clause(alias: String, properties: List<String>): String? =
        properties.takeIf { it.isNotEmpty() }?.joinToString(", ", "SET ") { setClause(alias, it) }
}
