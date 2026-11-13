package org.drivine.backend.oracle

import org.drivine.annotation.Direction
import org.drivine.backend.sql.NodeIds
import org.drivine.backend.sql.SqlKind
import org.drivine.model.Stamps
import org.drivine.query.SqlParameters
import org.drivine.schema.SimilarityFunction
import java.math.BigDecimal
import org.drivine.cypher.store.OracleSql as Shared

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

    /** As [Shared.literal], which the compiler calls too. */
    fun literal(text: String): String = Shared.literal(text)

    /**
     * The id [id] as the text a write plan keys its node by, within the plan, and reads a target's stored id back as. A
     * property is read back as text, and Oracle keeps a JSON number as a number, so `3.0` is read as `3`: a number is
     * written as its plain, shortest form. A temporal value is held as its tag, and keyed by the tag's text, as the module
     * writes it ([NodeIds.temporalText]). A node is found by its id's JSON, not by this text: see [NodeIds].
     */
    fun key(id: Any): String = NodeIds.temporalText(id) ?: when (id) {
        is Float, is Double -> BigDecimal(id.toString()).stripTrailingZeros().toPlainString()
        is BigDecimal -> id.stripTrailingZeros().toPlainString()
        else -> id.toString()
    }

    /** As [Shared.path], which the compiler calls too. */
    fun path(key: String, suffix: String = ""): String = Shared.path(key, suffix)

    /**
     * Whether the property [key] (as stored, [org.drivine.cypher.store.ValueLayout.escape]d) of the node [alias] is the id
     * whose JSON, as the node holds it ([NodeIds]), is [json], a JSON value: its class and key, as the index over the
     * property of every node is made over them ([Shared.indexClass], [Shared.indexKey]; `OracleDdl.sharedColumns`),
     * compared with the id's ([Shared.valueClass], [Shared.valueKey]), so that index answers it, and a temporal id's tag
     * is equal only to the same value's. A null [json] is equal to no id.
     */
    fun hasId(alias: String, key: String, json: String): String =
        "${Shared.indexClass("$alias.props", key)} = ${Shared.valueClass(json)} AND ${Shared.indexKey("$alias.props", key)} = ${Shared.valueKey(json)}"

    /**
     * Whether the property [key] of the node [alias] is one of the ids bound as [parameter], a JSON array of each id's JSON
     * text, as [hasId] compares one: the index answers it for each.
     */
    fun hasIdIn(alias: String, key: String, parameter: String): String =
        "(${Shared.indexClass("$alias.props", key)}, ${Shared.indexKey("$alias.props", key)}) IN " +
            "(SELECT ${Shared.valueClass("JSON(i.v)")}, ${Shared.valueKey("JSON(i.v)")} FROM (${values(parameter, SqlKind.TEXT)}) i)"

    /**
     * The id held under [key] of the node [alias] as the text a write plan keys it by ([key]): a temporal value's tag read
     * as its text `v`, anything else as `JSON_VALUE` reads it, as the module's Postgres write function reads a target's id
     * (`COALESCE(props -> key ->> 'v', props ->> key)`) and SQLite's writer does ([org.drivine.backend.sqlite.SqliteSql.keyText]).
     */
    fun keyText(alias: String, key: String): String =
        "COALESCE(JSON_VALUE($alias.props, ${path(key, ".${org.drivine.cypher.store.ValueLayout.TEXT}")}), JSON_VALUE($alias.props, ${path(key)}))"

    /** As [Shared.path], which the compiler calls too. */
    fun path(keys: List<String>, suffix: String = ""): String = Shared.path(keys, suffix)

    /** As [Shared.hasLabel], which the compiler calls too. */
    fun hasLabel(alias: String, label: String): String = Shared.hasLabel(alias, label)

    /**
     * Whether the node [alias] carries [label], written so the index over labels cannot answer it:
     * the form for a node that is found some other way, by its id or at the end of a relationship.
     * Given the choice, Oracle reads every node of the label and tests each for the id, where the
     * index over the id gives the one node. The label is looked for by its text: see [holdsLabel].
     */
    fun carriesLabel(alias: String, label: String): String = Shared.carriesLabel(alias, label)

    /** As [Shared.holdsLabel], which the compiler calls too. */
    fun holdsLabel(labels: String, label: String): String = Shared.holdsLabel(labels, label)

    /** As [Shared.labelled], which the compiler calls too. */
    fun labelled(alias: String, label: String, key: String): String = Shared.labelled(alias, label, key)

    /** As [Shared.quoted], which the compiler calls too. */
    internal fun quoted(text: String): String = Shared.quoted(text)

    /** As [Shared.serialized], which the compiler calls too. */
    internal fun serialized(text: String): String = Shared.serialized(text)

    /**
     * Whether the node [alias] carries every one of [labels]; null when there are none to carry.
     * [scanned] says the statement reads every node of the labels, and so may use the index over them, for
     * the first label alone: when two tests of the labels are answered by that index, Oracle (23.26) finds
     * no node the open transaction has written and not yet committed. The rest are tested by the labels' text.
     */
    fun hasLabels(alias: String, labels: Collection<String>, scanned: Boolean = false): String? =
        labels.takeIf { it.isNotEmpty() }?.withIndex()?.joinToString(" AND ") { (i, label) ->
            if (scanned && i == 0) hasLabel(alias, label) else carriesLabel(alias, label)
        }

    /** The property [key] of the node or relationship [alias], read as [kind]. The stamp is a column. */
    fun property(alias: String, key: String, kind: SqlKind = SqlKind.TEXT): String =
        if (key == Stamps.PROPERTY) "$alias.stamp" else kind.read("$alias.props", path(key))

    /** Whether the node or relationship [alias] has the property [key] at all. */
    fun hasProperty(alias: String, key: String): String =
        if (key == Stamps.PROPERTY) "$alias.stamp IS NOT NULL" else "JSON_EXISTS($alias.props, ${path(key)})"

    /** As [Shared.embedding]: what the module's side table of a vector index holds. */
    fun embedding(alias: String, key: String): String = Shared.embedding(alias, key)

    /** As [Shared.holdsEmbedding]: the embeddings a vector index holds, and so the ones a search measures. */
    fun holdsEmbedding(alias: String, key: String, dimensions: Int): String = Shared.holdsEmbedding(alias, key, dimensions)

    /**
     * The node [alias] as the map a load returns for it: the `doc` property the graph gives each
     * node (see [OracleGraph]), which every row source of a node carries.
     */
    fun document(alias: String): String = "$alias.doc"

    /** As [Shared.transform]: the map a load returns for a node, what the module's property graph gives as `doc`. */
    fun transform(alias: String): String = Shared.transform(alias)

    /** The columns a row of a node carries, each as [alias]'s, `doc` among them. */
    fun columns(alias: String): String = NODE_COLUMNS.joinToString(", ") { "$alias.$it" }

    /**
     * The node keys [a] and [b] equal, compared with `+ 0` on one side and again the other way round, as the module's compiler
     * joins a node back to the node table: compared column to column, Oracle (23.26) may eliminate the join and read the
     * other row by the key it dropped, or end the server process where a filter reads an indexed property of it (`ORA-07445`).
     */
    fun sameKey(a: String, b: String): String = "($a = $b + 0 AND $b = $a + 0)"

    /** One node in a `MATCH` pattern, named [variable], that meets [where] when there is one. */
    fun node(variable: String, where: String? = null): String = "($variable IS node${where?.let { " WHERE $it" }.orEmpty()})"

    /** The columns a row of a node has: those of the table, and the map the graph makes of them. */
    val NODE_COLUMNS = listOf("nid", "labels", "props", "stamp", "doc")

    /** As [Shared.metric]: the distance the module's vector index is built with, which a search by it must name. */
    fun metric(similarity: SimilarityFunction): String = Shared.metric(similarity)

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
