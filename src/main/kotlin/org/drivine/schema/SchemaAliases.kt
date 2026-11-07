package org.drivine.schema

/**
 * A grammar whose store keeps the other names an item goes by beside the item: the SQL stores, which record what
 * Drivine made in the graph's catalog table, keep them as `aliases`, a list of names, among the record's `details`.
 * An alias is a name Neo4j schema DDL gave an item (see [org.drivine.schema.ddl.SchemaDdl]) where the schema API
 * names it otherwise, so that a `DROP` by that name finds it, in this process or a later one. A reader of the
 * catalog that does not know of aliases reads the record as it did.
 *
 * A grammar's every statement that records an item again keeps its aliases, and [IndexManager.recreate] and
 * [ConstraintManager.recreate] give a recreated item the aliases it had.
 */
internal interface SchemaAliases {

    /** The statement that records [aliases] as all the aliases of [item], whose record is there; none, where empty. */
    fun aliased(item: SchemaItemInfo, aliases: List<String>): SchemaStatement
}

/** An item as introspection found it, with the aliases its record keeps. */
internal data class RecordedItem(val info: SchemaItemInfo, val aliases: List<String>)

/** The aliases a catalog row's `details` keep: none, where there are none, or the store keeps none. */
internal fun aliasesIn(row: Any?): List<String> =
    (((row as? Map<*, *>)?.get("details") as? Map<*, *>)?.get(ALIASES) as? List<*>).orEmpty().map { it.toString() }

/** Each row of an introspection with what [parse] makes of it, and its aliases. */
internal fun recordedItems(rows: List<Any?>, parse: (List<Any?>) -> List<SchemaItemInfo>): List<RecordedItem> =
    rows.flatMap { row -> parse(listOf(row)).map { RecordedItem(it, aliasesIn(row)) } }

/** The key of a catalog record's `details` that holds its aliases. */
internal const val ALIASES = "aliases"

/** [aliases] as the JSON array a record's details keep them in, written byte for byte the same each time. */
internal fun aliasesJson(aliases: List<String>): String =
    aliases.joinToString(",", "[", "]") { alias ->
        alias.map { c ->
            when {
                c == '\\' -> "\\\\"
                c == '"' -> "\\\""
                c < ' ' -> "\\u%04x".format(c.code)
                else -> c.toString()
            }
        }.joinToString("", "\"", "\"")
    }
