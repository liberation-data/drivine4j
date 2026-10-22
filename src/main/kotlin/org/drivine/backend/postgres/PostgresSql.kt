package org.drivine.backend.postgres

import org.drivine.backend.sql.SqlKind
import org.drivine.backend.sql.under
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps
import org.drivine.query.JdbcParameterCoercer
import org.drivine.schema.SimilarityFunction
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

    /** [alias]'s column [column]; the column of the table itself, as an index names it, for an empty alias. */
    private fun column(alias: String, column: String): String = if (alias.isEmpty()) column else "$alias.$column"

    /**
     * Whether the node [alias] carries [label]: the form the index over labels answers, and the predicate
     * of a partial index over the label's nodes (see [PostgresSchemaGrammar]). A label is data, so it is
     * written into the literal.
     */
    fun hasLabel(alias: String, label: String): String = "${column(alias, "labels")} @> ${literal("[${quoted(label)}]")}"

    /**
     * Whether the node [alias] carries every one of [labels]; null when there are none to carry. Each
     * label is a test of its own, so a partial index over the nodes of any one of them is seen to hold
     * the nodes asked for.
     */
    fun hasLabels(alias: String, labels: Collection<String>): String? =
        labels.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { hasLabel(alias, it) }

    /** The JSON value of the property [key] of the node or relationship [alias]. */
    fun value(alias: String, key: String): String = "(${column(alias, "props")} -> ${keyOf(key)})"

    /**
     * The property [key] of the node or relationship [alias], read as [kind]. The stamp is a column. For
     * an empty alias, the table's own column, as an index over the property names it.
     *
     * A number is read only where one is stored, and is null where the property holds anything else, as
     * the graph engines compare a text with a number as unequal: `'7'` is not `7`. A moment or a date is
     * read from the text it is stored as.
     */
    fun property(alias: String, key: String, kind: SqlKind = SqlKind.TEXT): String {
        if (key == Stamps.PROPERTY) return column(alias, "stamp")
        val text = "(${column(alias, "props")} ->> ${keyOf(key)})"
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

    /**
     * The embedding stored under [key] of [alias] as a pgvector `vector`: the JSON array's text, which
     * is the text pgvector reads a vector from. Of [dimensions] when given, as an index over it is made,
     * so a statement that reads it so is answered by the index.
     */
    fun embedding(alias: String, key: String, dimensions: Int? = null): String =
        "CAST(CAST(${value(alias, key)} AS text) AS vector${dimensions?.let { "($it)" }.orEmpty()})"

    /**
     * Whether [alias] holds an embedding of [dimensions] under [key]: a search measures only those, as
     * pgvector refuses to measure vectors of two sizes, and a vector index holds only those, so a node
     * written with another is left out of it and not refused.
     */
    fun holdsEmbedding(alias: String, key: String, dimensions: Int): String =
        "jsonb_typeof(${value(alias, key)}) = 'array' AND jsonb_array_length(${value(alias, key)}) = $dimensions"

    /** pgvector's operator for the distance [similarity] measures, and the operators an index over it is made with. */
    fun distance(similarity: SimilarityFunction): String = when (similarity) {
        SimilarityFunction.COSINE -> "<=>"
        SimilarityFunction.EUCLIDEAN -> "<->"
    }

    /** The operator class a vector index is made with to answer [distance]. */
    fun operators(similarity: SimilarityFunction): String = when (similarity) {
        SimilarityFunction.COSINE -> "vector_cosine_ops"
        SimilarityFunction.EUCLIDEAN -> "vector_l2_ops"
    }

    /**
     * The score the graph engines give for a [distance] by [similarity]: in `[0, 1]`, higher the nearer.
     * pgvector's cosine distance is `1 - cos`, so the score is `(1 + cos) / 2`; its Euclidean distance is
     * `d`, so the score is `1 / (1 + d²)`, as on Oracle and the graph engines.
     */
    fun score(similarity: SimilarityFunction, distance: String): String = when (similarity) {
        SimilarityFunction.COSINE -> "(2 - ($distance)) / 2"
        SimilarityFunction.EUCLIDEAN -> "1 / (1 + ($distance) * ($distance))"
    }

    /**
     * The text a full-text search reads of [alias]: its properties [keys], each as text, a space between
     * them, in lower case. A property it does not have is no text. The expression of a full-text index
     * too (with an empty alias), so a statement that reads it so is answered by the index.
     */
    fun searchedText(alias: String, keys: List<String>): String =
        "lower(${keys.joinToString(" || ' ' || ") { "COALESCE(${property(alias, it)}, '')" }})"

    /** The columns a row of a node has, which every row source of a node gives. */
    val NODE_COLUMNS = listOf("nid", "labels", "props", "stamp")

    /** The columns of a row of a node, each as [alias]'s. */
    fun columns(alias: String): String = NODE_COLUMNS.joinToString(", ") { "$alias.$it" }

    /** A JSON object of [entries], each a key and the expression for its value, laid out under the key. */
    fun jsonObject(entries: List<Pair<String, String>>): String =
        entries.joinToString(",\n    ", "jsonb_build_object(\n    ", ")") { (key, value) -> "${literal(key)}, ${value.under()}" }

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
