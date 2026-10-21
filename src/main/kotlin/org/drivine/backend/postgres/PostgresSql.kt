package org.drivine.backend.postgres

import org.drivine.backend.sql.SqlKind
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps
import org.drivine.query.JdbcParameterCoercer
import java.math.BigDecimal

/**
 * The pieces of SQL every statement over a [PostgresGraph] is made of.
 *
 * A property is read from `props` with `->>`, which gives a stored `""` as `""`: Postgres, unlike
 * Oracle, keeps an empty string apart from null, so a test against `''` is the test it is.
 *
 * Nothing here is written with Postgres's `?` operators (`?`, `?|`, `?&`): JDBC takes a `?` for a
 * parameter's marker. Whether a property is there is `->` and `IS NOT NULL`, and a label is `@>`.
 */
internal object PostgresSql {

    const val EMPTY_ARRAY = "'[]'::jsonb"

    /** [text] as a SQL string literal, its quotes doubled. Postgres reads a backslash in one as itself. */
    fun literal(text: String): String = "'${text.replace("'", "''")}'"

    /** [text] as JSON writes a string. */
    private fun quoted(text: String): String = "\"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    /** The key [key] of a JSON object, as a literal; refused when it is empty, which names no property. */
    private fun keyOf(key: String): String {
        require(key.isNotEmpty()) { "A property name is empty: an empty name is no property a statement can read." }
        return literal(key)
    }

    /** Whether the node [alias] carries [label]: the form the index over labels answers. A label is data, so it is written into the literal. */
    fun hasLabel(alias: String, label: String): String = "$alias.labels @> ${literal("[${quoted(label)}]")}"

    /** Whether the node [alias] carries every one of [labels], in one test the index over labels answers; null when there are none to carry. */
    fun hasLabels(alias: String, labels: Collection<String>): String? =
        labels.takeIf { it.isNotEmpty() }?.let { "$alias.labels @> ${literal(it.joinToString(",", "[", "]", transform = ::quoted))}" }

    /** The JSON value of the property [key] of the node or relationship [alias]. */
    fun value(alias: String, key: String): String = "($alias.props -> ${keyOf(key)})"

    /**
     * The property [key] of the node or relationship [alias], read as [kind]. The stamp is a column.
     *
     * A number is read only where one is stored, and is null where the property holds anything else, as
     * the graph engines compare a text with a number as unequal: `'7'` is not `7`. A moment or a date is
     * read from the text it is stored as.
     */
    fun property(alias: String, key: String, kind: SqlKind = SqlKind.TEXT): String {
        if (key == Stamps.PROPERTY) return "$alias.stamp"
        val text = "($alias.props ->> ${keyOf(key)})"
        return when (kind) {
            SqlKind.TEXT -> text
            SqlKind.NUMBER -> number(alias, key)
            SqlKind.INSTANT -> "$text::timestamptz"
            SqlKind.LOCAL_DATE_TIME -> "$text::timestamp"
            SqlKind.LOCAL_DATE -> "$text::date"
        }
    }

    /** The property [key] of [alias] as a number where one is stored, and null for anything else. */
    fun number(alias: String, key: String): String =
        "CASE WHEN jsonb_typeof(${value(alias, key)}) = 'number' THEN ($alias.props ->> ${keyOf(key)})::numeric END"

    /** Whether the node or relationship [alias] has the property [key] at all, null or not. */
    fun hasProperty(alias: String, key: String): String =
        if (key == Stamps.PROPERTY) "$alias.stamp IS NOT NULL" else "${value(alias, key)} IS NOT NULL"

    /**
     * The node [alias] as the map a load returns for it, put together from the columns of its row: its
     * properties, with its labels under `labels` and its stamp under the property the graph engines
     * store it as. A stamp that is null is left out, as it is on a node that has none.
     */
    fun document(alias: String): String =
        "($alias.props || jsonb_build_object('labels', $alias.labels) || jsonb_strip_nulls(jsonb_build_object(${literal(Stamps.PROPERTY)}, $alias.stamp)))"

    /** The columns a row of a node has, which every row source of a node gives. */
    val NODE_COLUMNS = listOf("nid", "labels", "props", "stamp")

    /** The columns of a row of a node, each as [alias]'s. */
    fun columns(alias: String): String = NODE_COLUMNS.joinToString(", ") { "$alias.$it" }

    /** A JSON object of [entries], each a key and the expression for its value. */
    fun jsonObject(entries: List<Pair<String, String>>): String =
        entries.joinToString(",\n    ", "jsonb_build_object(\n    ", ")") { (key, value) -> "${literal(key)}, $value" }

    /** The JSON array of [value] over the rows a query reads, in [order]. */
    fun arrayOf(value: String, order: String): String = "jsonb_agg($value ORDER BY $order)"

    /**
     * The values of the JSON array bound as [parameter], as rows of one column read as [kind]. A value
     * that is not of the kind is null, and so is never among them.
     */
    fun values(parameter: String, kind: SqlKind): String {
        val text = "v.value #>> '{}'"
        val read = when (kind) {
            SqlKind.TEXT -> text
            SqlKind.NUMBER -> "CASE WHEN jsonb_typeof(v.value) = 'number' THEN ($text)::numeric END"
            SqlKind.INSTANT -> "($text)::timestamptz"
            SqlKind.LOCAL_DATE_TIME -> "($text)::timestamp"
            SqlKind.LOCAL_DATE -> "($text)::date"
        }
        return "SELECT $read FROM jsonb_array_elements(CAST($parameter AS jsonb)) v"
    }

    /**
     * The id [id] as the text a node is found by: what `->>` reads its stored property back as. A
     * property is written as JSON, so a number is its JSON form, which `jsonb` keeps as it was written.
     */
    fun key(id: Any): String = when (id) {
        is Number, is Boolean -> Neo4jObjectMapper.instance.writeValueAsString(id)
        else -> id.toString()
    }

    /**
     * [value] written into a statement as a literal, for a statement that is run where its values cannot
     * be bound: the query a `patch` step asks inside the write function. It is a value the query DSL was
     * given, as JDBC would bind it (see [JdbcParameterCoercer]): a number as itself, anything else as a
     * string literal, which Postgres reads as the type the statement wants there, as it reads a string
     * bound without a type.
     */
    fun inline(value: Any?): String = when (val coerced = JdbcParameterCoercer.coerce(mapOf("v" to value))["v"]) {
        null -> "NULL"
        is BigDecimal -> coerced.toPlainString()
        is Byte, is Short, is Int, is Long -> coerced.toString()
        is Float, is Double -> BigDecimal(coerced.toString()).toPlainString()
        is java.util.Date -> literal(coerced.toInstant().toString())
        else -> literal(coerced.toString())
    }

    /** [parameter], a bound value, read as text: a function of text is not left to guess the type of a value bound without one. */
    fun text(parameter: String): String = "CAST($parameter AS text)"
}
