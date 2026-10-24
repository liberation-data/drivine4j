package org.drivine.backend.sqlite

import org.drivine.backend.postgres.PostgresSql
import org.drivine.backend.sql.SqlKind
import org.drivine.backend.sql.under
import org.drivine.model.Stamps
import org.drivine.schema.SimilarityFunction

/**
 * The pieces of SQL every statement over a [SqliteGraph] is made of.
 *
 * Labels and properties are JSON held as text, which SQLite's JSON functions read. A property is read
 * by its path, `'$."name"'`, so a name with a dot in it is one property and not a path into another.
 *
 * SQLite's `->>` gives a JSON value as the SQL value it is: 7 as an integer, `true` as 1. Postgres's
 * gives its text, and a statement compares a property with the text of what it is compared with, so
 * a property read as text here is the text Postgres's `->>` gives: a string as itself, anything else
 * as its JSON (`'7'`, `'1.50'`, `'true'`), and null as SQL's null. A number is read only where one is
 * stored. A moment is read as nanoseconds since the epoch, by the function [SqliteEpochFunction]
 * registers, so two moments written with different offsets compare as the moments they are, exactly.
 * A date is read by `date(…)`.
 *
 * A label is a test of the text of the node's labels for the label as JSON writes it, quotes and all
 * (`instr(labels, '"Person"') > 0`): a label is written whole, so it is found whole, and the test is
 * deterministic, so it may be the predicate of a partial index, as `labels @> '["Person"]'` is on
 * Postgres. Drivine writes labels as a JSON array with no character escaped that JSON does not require.
 */
internal object SqliteSql {

    /** An empty JSON array, as JSON: one that is put into a document stays an array. */
    const val EMPTY_ARRAY = "json_array()"

    /**
     * What a statement whose text values are JSON opens with: drivine's own statements over a SQLite
     * graph return documents as JSON text, and SQLite has no JSON type for a column to say so. See
     * `JdbcResultMapper`.
     */
    const val JSON_ROWS = "/* drivine:json */"

    /** [text] as a SQL string literal, its quotes doubled. SQLite reads a backslash in one as itself. */
    fun literal(text: String): String = "'${text.replace("'", "''")}'"

    /** [text] as JSON writes a string. */
    fun quoted(text: String): String = "\"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    /** The path of the property [key] of a JSON object, as a literal; refused when it is empty, which names no property. */
    fun path(key: String): String {
        require(key.isNotEmpty()) { "A property name is empty: an empty name is no property a statement can read." }
        return literal("$.${quoted(key)}")
    }

    /** [alias]'s column [column]; the column of the table itself, as an index names it, for an empty alias. */
    private fun column(alias: String, column: String): String = if (alias.isEmpty()) column else "$alias.$column"

    /**
     * Whether the node [alias] carries [label]: the test a partial index over the label's nodes is
     * made with (see [SqliteSchemaGrammar]), so a statement that makes it is answered by the index.
     */
    fun hasLabel(alias: String, label: String): String = "instr(${column(alias, "labels")}, ${literal(quoted(label))}) > 0"

    /** The labels of a node that carries [label] alone, as a literal of the JSON array they are stored as. */
    fun labelArray(label: String): String = literal("[${quoted(label)}]")

    /** Whether the node [alias] carries every one of [labels], each a test of its own; null when there are none to carry. */
    fun hasLabels(alias: String, labels: Collection<String>): String? =
        labels.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { hasLabel(alias, it) }

    /** The JSON of the property [key] of the node or relationship [alias], as text: `'"Ada"'` for a string. */
    fun value(alias: String, key: String): String = "(${column(alias, "props")} -> ${path(key)})"

    /** The JSON type of the property [key] of [alias], as SQLite names it (`'text'`, `'integer'`, `'array'`…); null where there is none. */
    fun type(alias: String, key: String): String = "json_type(${column(alias, "props")}, ${path(key)})"

    /**
     * The property [key] of the node or relationship [alias], read as [kind]. The stamp is a column. For
     * an empty alias, the table's own column, as an index over the property names it: the text is the
     * expression a range index or a uniqueness constraint is made over.
     */
    fun property(alias: String, key: String, kind: SqlKind = SqlKind.TEXT): String {
        if (key == Stamps.PROPERTY) return column(alias, "stamp")
        val props = column(alias, "props")
        val path = path(key)
        val text = "(CASE json_type($props, $path) WHEN 'text' THEN $props ->> $path WHEN 'null' THEN NULL ELSE $props -> $path END)"
        return when (kind) {
            SqlKind.TEXT -> text
            SqlKind.NUMBER -> number(alias, key)
            else -> typed(kind, text)
        }
    }

    /** The property [key] of [alias] as a number where one is stored, and null for anything else. */
    fun number(alias: String, key: String): String =
        "(CASE WHEN ${type(alias, key)} IN ('integer', 'real') THEN ${column(alias, "props")} ->> ${path(key)} END)"

    /**
     * [text], the text of a moment or a date, read as [kind] compares: a moment as nanoseconds since the
     * epoch and a date as SQLite writes one. A bound value is read so too, so the two compare alike.
     */
    fun typed(kind: SqlKind, text: String): String = when (kind) {
        SqlKind.INSTANT, SqlKind.LOCAL_DATE_TIME -> epoch(text)
        SqlKind.LOCAL_DATE -> "date($text)"
        SqlKind.TEXT, SqlKind.NUMBER -> text
    }

    /** The moment [text] names, as nanoseconds since the epoch: see [SqliteEpochFunction]. */
    fun epoch(text: String): String = "${SqliteFunctions.EPOCH}($text)"

    /** Whether the node or relationship [alias] has the property [key] at all, null or not. */
    fun hasProperty(alias: String, key: String): String =
        if (key == Stamps.PROPERTY) "$alias.stamp IS NOT NULL" else "${type(alias, key)} IS NOT NULL"

    /**
     * The node [alias] as the map a load returns for it, put together from the columns of its row: its
     * properties, with its labels under `labels` and its stamp under the property the graph engines
     * store it as. A merge patch leaves out a key whose value is null, so a node with no stamp has none.
     */
    fun document(alias: String): String =
        "json_patch($alias.props, json_object('labels', json($alias.labels), ${literal(Stamps.PROPERTY)}, $alias.stamp))"

    /**
     * Whether [alias] holds an embedding of [dimensions] under [key]: a search measures only those, as
     * an embedding of another size is no point in the same space.
     */
    fun holdsEmbedding(alias: String, key: String, dimensions: Int): String =
        "${type(alias, key)} = 'array' AND json_array_length(${column(alias, "props")}, ${path(key)}) = $dimensions"

    /**
     * The score the graph engines give the embedding under [key] of [alias] against the vector bound as
     * [vector], by [similarity]: in `[0, 1]`, higher the nearer, as [PostgresSql.score] gives it. It is
     * measured by the function [SqliteSimilarityFunction] registers.
     */
    fun score(alias: String, key: String, similarity: SimilarityFunction, vector: String): String =
        "${SqliteFunctions.SIMILARITY}(${value(alias, key)}, $vector, ${literal(similarity.name)})"

    /**
     * The text a full-text search reads of [alias]: its properties [keys], each as text, a space between
     * them, in lower case, by the function [SqliteLowerFunction] registers: SQLite's own `lower` lowers
     * only ASCII.
     */
    fun searchedText(alias: String, keys: List<String>): String =
        "${SqliteFunctions.LOWER}(${keys.joinToString(" || ' ' || ") { "COALESCE(${property(alias, it)}, '')" }})"

    /** The columns a row of a node has, which every row source of a node gives. */
    val NODE_COLUMNS = listOf("nid", "labels", "props", "stamp")

    /** The columns of a row of a node, each as [alias]'s. */
    fun columns(alias: String): String = NODE_COLUMNS.joinToString(", ") { "$alias.$it" }

    /**
     * A JSON object of [entries], each a key and the expression for its value, laid out under the key.
     * Each value is JSON, a number or null, and is read as JSON with `json(…)`: a document a subquery
     * gives is text, which would otherwise be put in as a string.
     */
    fun jsonObject(entries: List<Pair<String, String>>): String =
        entries.joinToString(",\n    ", "json_object(\n    ", ")") { (key, value) -> "${literal(key)}, json(${value.under()})" }

    /** A JSON object of [entries] whose values are put in as the SQL values they are: a text as a string. */
    fun plainObject(entries: List<Pair<String, String>>): String =
        entries.joinToString(",\n    ", "json_object(\n    ", ")") { (key, value) -> "${literal(key)}, ${value.under()}" }

    /** The JSON array of [value] over the rows a query reads, in [order]. */
    fun arrayOf(value: String, order: String): String = "json_group_array($value ORDER BY $order)"

    /**
     * The values of the JSON array bound as [parameter], as rows of one column read as [kind]: each as
     * a property of that kind is read, so a text is a string's text or another value's JSON. A value
     * that is not of the kind is null, and so is never among them.
     */
    fun values(parameter: String, kind: SqlKind): String {
        val text = "(CASE v.type WHEN 'text' THEN v.value WHEN 'null' THEN NULL WHEN 'true' THEN 'true' WHEN 'false' THEN 'false' " +
            "WHEN 'integer' THEN json_quote(v.value) WHEN 'real' THEN json_quote(v.value) ELSE v.value END)"
        val read = when (kind) {
            SqlKind.TEXT -> text
            SqlKind.NUMBER -> "(CASE WHEN v.type IN ('integer', 'real') THEN v.value END)"
            else -> typed(kind, text)
        }
        return "SELECT $read FROM json_each($parameter) v"
    }

    /** An id as the text a node is found by: see [PostgresSql.key], whose text the stores share. */
    fun key(id: Any): String = PostgresSql.key(id)

    /**
     * [value] written into a statement as a literal, for the query a `patch` step asks inside the write
     * function, where it is run without the statement's values: a number as itself, anything else as a
     * string literal of the text it is bound as, as [PostgresSql.inline] writes one, the two stores'
     * literals being alike.
     */
    fun inline(value: Any?): String = PostgresSql.inline(value)

    /** [parameter], a bound value, read as text, as a property is read. */
    fun text(parameter: String): String = "CAST($parameter AS TEXT)"

    /**
     * The direction of a term of an order, with its nulls where Cypher puts them unless [nulls] is
     * given: SQLite puts them first when ascending, and Cypher last.
     */
    fun direction(ascending: Boolean, nulls: String? = null): String =
        (if (ascending) "ASC" else "DESC") + " " + (nulls ?: if (ascending) "NULLS LAST" else "NULLS FIRST")
}
