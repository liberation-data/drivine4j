package org.drivine.backend.postgres

import org.drivine.backend.sql.NodeIds
import org.drivine.backend.sql.SqlKind
import org.drivine.backend.sql.under
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.model.Stamps
import org.drivine.query.JdbcParameterCoercer
import org.drivine.schema.SimilarityFunction
import java.math.BigDecimal
import org.drivine.cypher.store.PostgresSql as Shared

/**
 * The pieces of SQL every statement over a [PostgresGraph] is made of. Those an index is made over, and a statement
 * reads the index by, are the module's ([Shared]), which the store's DDL and the compiler write too, so a statement
 * written here is read through the index the module made.
 *
 * A property is read from `props` with `->>`, which gives a stored `""` as `""`: Postgres, unlike
 * Oracle, keeps an empty string apart from null, so a test against `''` is the test it is.
 *
 * Nothing here is written with Postgres's `?` operators (`?`, `?|`, `?&`): JDBC takes a `?` for a
 * parameter's marker. Whether a property is there is `->` and `IS NOT NULL`, and a label is `@>`.
 */
internal object PostgresSql {

    const val EMPTY_ARRAY = "'[]'::jsonb"

    /** As [Shared.literal], which the DDL and the compiler call too. */
    fun literal(text: String): String = Shared.literal(text)

    /** As [Shared.hasLabel]: the predicate of a partial index over the label's nodes, as the module makes it. */
    fun hasLabel(alias: String, label: String): String = Shared.hasLabel(alias, label)

    /**
     * As [Shared.labelArray]: the labels as they are stored, written as Jackson writes them, so a label with a control
     * character in it is the JSON its node holds.
     */
    fun labelArray(label: String): String = Shared.labelArray(label)

    /**
     * Whether the node [alias] carries every one of [labels]; null when there are none to carry. Each
     * label is a test of its own, so a partial index over the nodes of any one of them is seen to hold
     * the nodes asked for.
     */
    fun hasLabels(alias: String, labels: Collection<String>): String? =
        labels.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { hasLabel(alias, it) }

    /** As [Shared.value], which the DDL and the compiler call too. */
    fun value(alias: String, key: String): String = Shared.value(alias, key)

    /**
     * The property [key] of the node or relationship [alias], read as [kind]. The stamp is a column. For
     * an empty alias, the table's own column, as an index over the property names it.
     *
     * A number is read only where one is stored, and is null where the property holds anything else, as
     * the graph engines compare a text with a number as unequal: `'7'` is not `7`. A moment or a date is
     * read from the text it is stored as.
     */
    fun property(alias: String, key: String, kind: SqlKind = SqlKind.TEXT): String {
        // As the module reads it: the expression a range index or a uniqueness constraint is made over.
        val text = Shared.property(alias, key)
        if (key == Stamps.PROPERTY) return text
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
        "CASE WHEN jsonb_typeof(${value(alias, key)}) = 'number' THEN ${Shared.propertyText(Shared.column(alias, "props"), literal(key))}::numeric END"

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

    /** As [Shared.embedding]: of [dimensions] when given, as the module's vector index is made over it. */
    fun embedding(alias: String, key: String, dimensions: Int? = null): String = Shared.embedding(alias, key, dimensions)

    /** As [Shared.holdsEmbedding]: the embeddings a vector index holds, and so the ones a search measures. */
    fun holdsEmbedding(alias: String, key: String, dimensions: Int): String = Shared.holdsEmbedding(alias, key, dimensions)

    /** pgvector's operator for the distance [similarity] measures, which the module's index answers ([Shared.operators]). */
    fun distance(similarity: SimilarityFunction): String = when (similarity) {
        SimilarityFunction.COSINE -> "<=>"
        SimilarityFunction.EUCLIDEAN -> "<->"
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

    /** As [Shared.searchedText]: the expression of the module's full-text index, so a search that reads it is answered by the index. */
    fun searchedText(alias: String, keys: List<String>): String = Shared.searchedText(alias, keys)

    /** As [Shared.joinedText]: what a full-text search measures a text's length by (drivine4j#604). */
    fun joinedText(alias: String, keys: List<String>): String = Shared.joinedText(alias, keys)

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
     * The id [id] as the text a write plan keys its node by, within the plan: what `->>` reads its stored property back as.
     * A property is written as JSON, so a number is its JSON form, which `jsonb` keeps as it was written. A temporal value
     * is held as its tag, and keyed by the tag's text, as the module writes it ([NodeIds.temporalText]). A node is found
     * by its id's JSON, not by this text: see [NodeIds].
     */
    fun key(id: Any): String = NodeIds.temporalText(id) ?: when (id) {
        is Number, is Boolean -> Neo4jObjectMapper.instance.writeValueAsString(id)
        else -> id.toString()
    }

    /**
     * Whether the property [key] (as stored, [org.drivine.cypher.store.ValueLayout.escape]d) of the node [alias] is the id
     * whose JSON, as the node holds it ([NodeIds]), is the text [json]: the property's JSON compared with it, as a range
     * index or a uniqueness constraint is made over it ([Shared.indexed]), so the index answers it, and a temporal id's
     * tag is equal only to the same value's. A null [json] is equal to no id.
     */
    fun hasId(alias: String, key: String, json: String): String = "${Shared.indexed(alias, key)} = CAST($json AS jsonb)"

    /**
     * Whether the property [key] of the node [alias] is one of the ids bound as [parameter], a JSON array of each id's JSON
     * text, as [hasId] compares one: the index answers it for each.
     */
    fun hasIdIn(alias: String, key: String, parameter: String): String =
        "${Shared.indexed(alias, key)} = ANY(ARRAY(SELECT CAST(v.value #>> '{}' AS jsonb) FROM jsonb_array_elements(CAST($parameter AS jsonb)) v))"

    /** The id held under [key] of the node [alias] as its text: a temporal value's tag read as its text `v` ([Shared.searchedProperty]). */
    fun keyText(alias: String, key: String): String = Shared.searchedProperty(alias, key)

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
