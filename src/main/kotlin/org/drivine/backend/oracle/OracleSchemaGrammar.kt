package org.drivine.backend.oracle

import org.drivine.backend.oracle.OracleSql.hasLabel
import org.drivine.backend.oracle.OracleSql.literal
import org.drivine.backend.oracle.OracleSql.path
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.FullTextIndexSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.SchemaStatement
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import java.sql.SQLException

/**
 * Indexes and constraints over a graph kept by Oracle. A property is a path into a JSON column, so
 * each is an index over an expression:
 *
 * - a range index reads the property as text, the expression a save finds a node by and a
 *   comparison with a string uses. It is over the property whatever the label, so two labels that
 *   index a property of one name share an index;
 * - a uniqueness constraint is a unique index over the property of the nodes that carry the label,
 *   and null for every other node, which a unique index does not hold.
 *
 * Oracle's dictionary holds the expressions and not what they were made for, so what Drivine made is
 * recorded in a table of the graph's own, and read back from there.
 */
internal class OracleSchemaGrammar(private val graph: OracleGraph) : SchemaGrammar {

    override val engine = "Oracle"
    override val supportsIfNotExists = true
    override val supportsNamedItems = true

    /** A node is found by the range index's expression; the unique index only refuses a second one. */
    override val constraintsRequireBackingIndex = true

    private fun statements(vararg text: String) = (graph.ddl + text).map { SchemaStatement.Cypher(it.trimIndent()) }

    /** A name for a schema object: the graph's, what it is, and its properties, cut to fit with a hash that keeps it its own. */
    private fun objectName(what: String, vararg parts: String): String {
        val readable = parts.joinToString("_") { it.replace(Regex("[^A-Za-z0-9]"), "") }.take(40)
        return "${graph.name}_${what}_${readable}_${Integer.toHexString(parts.joinToString("\u0000").hashCode())}"
    }

    private fun recorded(spec: SchemaItemSpec, objectName: String?, details: String = "{}"): String {
        val key = "kind = ${literal(spec.kind.name)} AND label = ${literal(spec.label)} AND properties = ${literal(json(spec.properties))}"
        return """
            INSERT INTO ${graph.catalog} (kind, label, properties, name, object_name, details)
            SELECT ${literal(spec.kind.name)}, ${literal(spec.label)}, ${literal(json(spec.properties))}, ${literal(spec.effectiveName)},
                   ${objectName?.let(::literal) ?: "NULL"}, ${literal(details)}
            FROM dual WHERE NOT EXISTS (SELECT 1 FROM ${graph.catalog} WHERE $key)
        """
    }

    /** Forgets [item], and drops the object it was made as when nothing else recorded uses it. */
    private fun forgotten(item: SchemaItemInfo): String = """
        DECLARE
            v_object VARCHAR2(128);
            v_others NUMBER;
        BEGIN
            DELETE FROM ${graph.catalog}
            WHERE kind = ${literal(item.kind.name)} AND label = ${literal(item.label)} AND properties = ${literal(json(item.properties))}
            RETURNING MAX(object_name) INTO v_object;
            SELECT COUNT(*) INTO v_others FROM ${graph.catalog} WHERE object_name = v_object;
            IF v_object IS NOT NULL AND v_others = 0 THEN
                EXECUTE IMMEDIATE 'DROP INDEX IF EXISTS ' || v_object;
            END IF;
        END;
    """

    private fun json(values: List<String>): String = values.joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }

    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> = when (spec) {
        is RangeIndexSpec -> {
            val name = objectName("ix", *spec.properties.toTypedArray())
            val expressions = spec.properties.joinToString(", ") { "JSON_VALUE(props, ${path(it)})" }
            statements("CREATE INDEX IF NOT EXISTS $name ON ${graph.nodes} ($expressions)", recorded(spec, name))
        }
        // A search by vector reads every node of the label and orders them by distance: it is exact,
        // and needs nothing made. The index is recorded, so it is found as one.
        is VectorIndexSpec -> statements(
            recorded(spec, null, """{"dimensions": ${spec.dimensions}, "similarity": "${spec.similarity.name}"}""")
        )
        // A full-text search reads the text of every node of the label, and needs nothing made either.
        is FullTextIndexSpec -> statements(recorded(spec, null, spec.analyzer?.let { """{"analyzer": "$it"}""" } ?: "{}"))
    }

    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = listOf(SchemaStatement.Cypher(forgotten(item).trimIndent()))

    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = when (spec) {
        is UniquenessConstraintSpec -> {
            val name = objectName("uq", spec.label, *spec.properties.toTypedArray())
            val expressions = spec.properties.joinToString(", ") { "CASE WHEN ${hasLabel("n", spec.label)} THEN JSON_VALUE(n.props, ${path(it)}) END" }
            statements("CREATE UNIQUE INDEX IF NOT EXISTS $name ON ${graph.nodes} n ($expressions)", recorded(spec, name))
        }
    }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = listOf(SchemaStatement.Cypher(forgotten(item).trimIndent()))

    private fun listed(kinds: String): String = """
        SELECT JSON_OBJECT('kind' VALUE kind, 'label' VALUE label, 'properties' VALUE JSON(properties), 'name' VALUE name, 'details' VALUE details RETURNING JSON)
        FROM ${graph.catalog} WHERE kind IN ($kinds) ORDER BY label, properties
    """.trimIndent()

    override fun listIndexesQuery(kind: SchemaItemKind): String = listed(literal(kind.name))

    override fun listConstraintsQuery(): String = listed(literal(SchemaItemKind.UNIQUENESS_CONSTRAINT.name))

    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { row ->
        val map = row as? Map<*, *> ?: return@mapNotNull null
        val details = map["details"] as? Map<*, *>
        SchemaItemInfo(
            kind = SchemaItemKind.valueOf(map["kind"] as String),
            label = map["label"] as String,
            properties = (map["properties"] as List<*>).map { it.toString() },
            name = map["name"] as? String,
            dimensions = (details?.get("dimensions") as? Number)?.toInt(),
            similarity = (details?.get("similarity") as? String)?.let(SimilarityFunction::valueOf),
            analyzer = details?.get("analyzer") as? String,
        )
    }

    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = parseIndexRows(rows)

    /** `ORA-00001`, a second row for a unique key; `ORA-01452`, a unique index over rows that already repeat one. */
    override fun isConstraintViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is SQLException && it.errorCode in setOf(1, 1452) }

    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { "JSON_VALUE(n.props, ${path(it)})" }
        val entries = spec.properties.zip(keys).joinToString(", ") { (property, key) -> "${literal(property)} VALUE $key" }
        return """
            SELECT JSON_OBJECT('properties' VALUE JSON_OBJECT($entries), 'count' VALUE COUNT(*) RETURNING JSON)
            FROM ${graph.nodes} n
            WHERE ${hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }}
            GROUP BY ${keys.joinToString(", ")}
            HAVING COUNT(*) > 1
            FETCH FIRST $limit ROWS ONLY
        """.trimIndent()
    }
}
