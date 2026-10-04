package org.drivine.query

/**
 * [name] as a label, relationship type or property name written into a statement: always quoted, so
 * it is one identifier whatever characters it holds. Names that cannot be bound as parameters — a
 * label or type known only at runtime — reach the statement through here and nowhere else.
 */
internal fun quotedIdentifier(name: String): String = "`${name.replace("`", "``")}`"
