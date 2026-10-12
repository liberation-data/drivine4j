package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.model.Stamps
import org.drivine.schema.SimilarityFunction
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Date

/** The SQL type a property is read as, so it compares and orders as its values do. */
internal enum class SqlKind(private val returning: String) {
    TEXT(""),
    NUMBER(" RETURNING NUMBER"),
    INSTANT(" RETURNING TIMESTAMP WITH TIME ZONE"),
    LOCAL_DATE_TIME(" RETURNING TIMESTAMP"),
    LOCAL_DATE(" RETURNING DATE");

    /** The column type of this kind in a `JSON_TABLE`, which must give a width: text is `VARCHAR2(4000)`, the most a JSON value is read as by default. */
    val column: String
        get() = if (this == TEXT) "VARCHAR2(4000)" else returning.removePrefix(" RETURNING ")

    /** The value at [path] in the JSON column [json], read as this kind. */
    fun read(json: String, path: String): String = "JSON_VALUE($json, $path$returning)"

    companion object {
        /** The kind a property is read as to compare it with [value]. A boolean is compared as its JSON text. */
        fun ofValue(value: Any?): SqlKind = when (value) {
            is Number -> NUMBER
            is Instant, is ZonedDateTime, is OffsetDateTime, is Date -> INSTANT
            is LocalDateTime -> LOCAL_DATE_TIME
            is LocalDate -> LOCAL_DATE
            is Collection<*> -> ofValue(value.firstOrNull { it != null })
            else -> TEXT
        }

        /** The kind a field of [type] is ordered as. */
        fun ofType(type: Class<*>?): SqlKind = when {
            type == null -> TEXT
            Number::class.java.isAssignableFrom(type) || type.isPrimitive && type != Boolean::class.javaPrimitiveType && type != Char::class.javaPrimitiveType -> NUMBER
            type == Instant::class.java || type == ZonedDateTime::class.java || type == OffsetDateTime::class.java || Date::class.java.isAssignableFrom(type) -> INSTANT
            type == LocalDateTime::class.java -> LOCAL_DATE_TIME
            type == LocalDate::class.java -> LOCAL_DATE
            else -> TEXT
        }
    }
}

/** The pieces of SQL every statement over an [OracleGraph] is made of. */
internal object OracleSql {

    const val EMPTY_ARRAY = "JSON('[]')"

    /** [text] as a SQL string literal, its quotes doubled. */
    fun literal(text: String): String = "'${text.replace("'", "''")}'"

    /**
     * The id [id] as the text a node is found by. A property is read back as text, and Oracle keeps a
     * JSON number as a number, so `3.0` is read as `3`: a number is written as its plain, shortest form.
     */
    fun key(id: Any): String = when (id) {
        is Float, is Double -> BigDecimal(id.toString()).stripTrailingZeros().toPlainString()
        is BigDecimal -> id.stripTrailingZeros().toPlainString()
        else -> id.toString()
    }

    /** One step of a JSON path: a key, quoted when it must be, so a key with a dot in it is one key. */
    private fun step(key: String): String =
        if (PLAIN_KEY.matches(key) && key !in JSON_WORDS) key else "\"${key.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    /** A key a JSON path writes bare: what SQL would take for an identifier. */
    private val PLAIN_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The words a JSON path reads as values, not keys. */
    private val JSON_WORDS = setOf("true", "false", "null")

    /** The path of the property [key], as a SQL literal. */
    fun path(key: String, suffix: String = ""): String {
        require(key.isNotEmpty()) { "A property name is empty: an empty name is no property a statement can read." }
        return literal("$.${step(key)}$suffix")
    }

    /**
     * Whether the node [alias] carries [label], written so the index over labels can answer it: the
     * form for a statement that reads every node of a label. A label is data, so it is written into
     * the path, quoted.
     */
    fun hasLabel(alias: String, label: String): String =
        "JSON_EXISTS($alias.labels, ${literal("$[*]?(@ == ${quoted(label)})")})"

    /**
     * Whether the node [alias] carries [label], written so the index over labels cannot answer it:
     * the form for a node that is found some other way, by its id or at the end of a relationship.
     * Given the choice, Oracle reads every node of the label and tests each for the id, where the
     * index over the id gives the one node. The label is looked for as JSON writes it, in quotes,
     * among the labels as JSON writes them.
     */
    fun carriesLabel(alias: String, label: String): String =
        "INSTR(JSON_SERIALIZE($alias.labels), ${literal(quoted(label))}) > 0"

    /** [text] as JSON writes a string. */
    private fun quoted(text: String): String = "\"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    /**
     * Whether the node [alias] carries every one of [labels]; null when there are none to carry.
     * [scanned] says the statement reads every node of the labels, and so may use the index over them.
     */
    fun hasLabels(alias: String, labels: Collection<String>, scanned: Boolean = false): String? =
        labels.takeIf { it.isNotEmpty() }?.joinToString(" AND ") { if (scanned) hasLabel(alias, it) else carriesLabel(alias, it) }

    /** The property [key] of the node or relationship [alias], read as [kind]. The stamp is a column. */
    fun property(alias: String, key: String, kind: SqlKind = SqlKind.TEXT): String =
        if (key == Stamps.PROPERTY) "$alias.stamp" else kind.read("$alias.props", path(key))

    /** Whether the node or relationship [alias] has the property [key] at all. */
    fun hasProperty(alias: String, key: String): String =
        if (key == Stamps.PROPERTY) "$alias.stamp IS NOT NULL" else "JSON_EXISTS($alias.props, ${path(key)})"

    /**
     * The node [alias] as the map a load returns for it: the `doc` property the graph gives each
     * node (see [OracleGraph]), which every row source of a node carries.
     */
    fun document(alias: String): String = "$alias.doc"

    /**
     * The map a load returns for a node, put together from the columns of the row [alias] (`""` for
     * the columns themselves): its properties, with its labels under `labels` and its stamp under the
     * property the graph engines store it as. What the graph's `doc` property is.
     */
    fun transform(alias: String): String {
        val of = if (alias.isEmpty()) "" else "$alias."
        return "JSON_TRANSFORM(${of}props, SET '$.labels' = ${of}labels, SET ${path(Stamps.PROPERTY)} = ${of}stamp)"
    }

    /** The columns a row of a node carries, each as [alias]'s, `doc` among them. */
    fun columns(alias: String): String = NODE_COLUMNS.joinToString(", ") { "$alias.$it" }

    /** One node in a `MATCH` pattern, named [variable], that meets [where] when there is one. */
    fun node(variable: String, where: String? = null): String = "($variable IS node${where?.let { " WHERE $it" }.orEmpty()})"

    /** The columns a row of a node has: those of the table, and the map the graph makes of them. */
    val NODE_COLUMNS = listOf("nid", "labels", "props", "stamp", "doc")

    /** The distance a vector index is built with for [similarity], and a search by it must name. */
    fun metric(similarity: SimilarityFunction): String = when (similarity) {
        SimilarityFunction.COSINE -> "COSINE"
        SimilarityFunction.EUCLIDEAN -> "EUCLIDEAN_SQUARED"
    }

    /** The score the graph engines give for a [distance] by [similarity]: in `[0, 1]`, higher the nearer. */
    fun score(similarity: SimilarityFunction, distance: String): String = when (similarity) {
        SimilarityFunction.COSINE -> "(2 - $distance) / 2"
        SimilarityFunction.EUCLIDEAN -> "1 / (1 + $distance)"
    }

    /** One relationship of [type] in a `MATCH` pattern, named [edge] and pointing in [direction]. */
    fun arrow(edge: String, type: String, direction: Direction): String {
        val body = "[$edge IS edge WHERE $edge.type = ${literal(type)}]"
        return when (direction) {
            Direction.OUTGOING -> "-$body->"
            Direction.INCOMING -> "<-$body-"
            Direction.UNDIRECTED -> "-$body-"
        }
    }

    /** The values of the JSON array bound as [parameter], as rows of one column `v` read as [kind]. */
    fun values(parameter: String, kind: SqlKind): String =
        "SELECT v FROM JSON_TABLE($parameter, '$[*]' COLUMNS (v ${kind.column} PATH '$'))"

    /** A block that runs [statement], which changes rows, and returns how many as its one row. */
    fun counted(statement: String): String = counting("$statement;\nv_count := SQL%ROWCOUNT;")

    /**
     * A block that runs [body], which leaves in `v_count` how many rows it changed, and returns that
     * as its one row. [declarations] are the body's own, beside `v_count`.
     */
    fun counting(body: String, declarations: String = ""): String = """
        DECLARE
            v_rows  SYS_REFCURSOR;
            v_count NUMBER := 0;${declarations.trimIndent().lines().filter { it.isNotBlank() }.joinToString("") { "\n            $it" }}
        BEGIN
            ${body.trimIndent().replace("\n", "\n            ")}
            OPEN v_rows FOR SELECT v_count FROM dual;
            DBMS_SQL.RETURN_RESULT(v_rows);
        END;
    """.trimIndent()
}
