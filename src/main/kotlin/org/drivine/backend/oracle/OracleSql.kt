package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.backend.sql.SqlKind
import org.drivine.model.Stamps
import org.drivine.query.SqlParameters
import org.drivine.schema.SimilarityFunction
import java.math.BigDecimal

/** What `JSON_VALUE` is told to return to read a property as this kind: nothing for text, which it returns by default. */
private val SqlKind.returning: String
    get() = when (this) {
        SqlKind.TEXT -> ""
        SqlKind.NUMBER -> " RETURNING NUMBER"
        SqlKind.INSTANT -> " RETURNING TIMESTAMP WITH TIME ZONE"
        SqlKind.LOCAL_DATE_TIME -> " RETURNING TIMESTAMP"
        SqlKind.LOCAL_DATE -> " RETURNING DATE"
    }

/** The column type of this kind in a `JSON_TABLE`, which must give a width: text is `VARCHAR2(4000)`, the most a JSON value is read as by default. */
internal val SqlKind.column: String
    get() = if (this == SqlKind.TEXT) "VARCHAR2(4000)" else returning.removePrefix(" RETURNING ")

/** The value at [path] in the JSON column [json], read as this kind. */
internal fun SqlKind.read(json: String, path: String): String = "JSON_VALUE($json, $path$returning)"

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

    /**
     * The property [key] of the node [alias], read as text, where the node carries [label], and null
     * where it does not: the expression of the index a range index makes over the nodes of its label
     * (see [OracleSchemaGrammar]). A comparison with it is met only by a node of the label, so the one
     * index answers both the label and the property.
     */
    fun labelled(alias: String, label: String, key: String): String =
        "CASE WHEN ${hasLabel(alias, label)} THEN JSON_VALUE($alias.props, ${path(key)}) END"

    /**
     * The property [key] of the node [alias], read as text, where the node carries [label] and has
     * each of [keys], and null where it does not: the expression of each column of a uniqueness
     * constraint's index over [keys] (see [OracleSchemaGrammar]). A unique index skips a row only
     * when every column is null, so a node without the label or without one of the properties is
     * not constrained.
     *
     * For a constraint over the one property [key] it is the same value as [labelled] on every
     * node: a node without the property, or with it stored as JSON null, `""`, an array or an
     * object, reads as null either way. So a filter on the property may be read through it.
     */
    fun unique(alias: String, label: String, keys: List<String>, key: String): String {
        val constrained = (listOf(hasLabel(alias, label)) + keys.map { "JSON_EXISTS($alias.props, ${path(it)})" }).joinToString(" AND ")
        return "CASE WHEN $constrained THEN JSON_VALUE($alias.props, ${path(key)}) END"
    }

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
    fun counted(statement: String): String = counting("${immediate(statement)};\nv_count := SQL%ROWCOUNT;")

    /**
     * [statement] run from a block as a statement of its own, its parameters handed to it in the order
     * it names them. Oracle Free 23.26 takes neither a parameter nor a variable of the block inside a
     * `GRAPH_TABLE` of the block's own SQL (`ORA-00600`, `ORA-49028`), which is how a predicate on a
     * relationship is written.
     *
     * @param into the variables of the block that take what the statement selects, when it selects
     * @param given parameters of the statement that are no parameters of the block: each is handed
     *   the variable of the block it is mapped to
     */
    fun immediate(statement: String, into: String? = null, given: Map<String, String> = emptyMap()): String {
        val asked = SqlParameters.positional(statement) { ":b$it" }
        val using = asked.names.takeIf { it.isNotEmpty() }?.joinToString(", ", "\nUSING ") { given[it] ?: "\$$it" }.orEmpty()
        return "EXECUTE IMMEDIATE ${text(asked.statement)}${into?.let { "\nINTO $it" }.orEmpty()}$using"
    }

    /**
     * [text] as a string of any length: one literal, or past [MAX_LITERAL] several joined as a `CLOB`.
     * PL/SQL takes a literal of 32,767 bytes at most, and a statement with a long predicate is longer.
     */
    fun text(text: String): String {
        if (text.length <= MAX_LITERAL) return literal(text)
        val parts = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            // A character written as two is not parted: each half alone is no character.
            val end = (start + MAX_LITERAL).coerceAtMost(text.length).let { if (it < text.length && text[it - 1].isHighSurrogate()) it - 1 else it }
            parts += text.substring(start, end)
            start = end
        }
        return parts.joinToString("\n|| ") { "TO_CLOB(${literal(it)})" }
    }

    /** The characters one literal of [text] holds: each is three bytes at most, or a quote written twice. */
    private const val MAX_LITERAL = 8000

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
