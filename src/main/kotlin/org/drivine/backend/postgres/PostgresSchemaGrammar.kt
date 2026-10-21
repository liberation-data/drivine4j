package org.drivine.backend.postgres

import org.drivine.backend.postgres.PostgresSql.hasLabel
import org.drivine.backend.postgres.PostgresSql.literal
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
import org.drivine.schema.StoreStampStatements
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.VersionMarkerStatements
import java.sql.SQLException

/**
 * Indexes and constraints over a graph kept by Postgres. A property is a key of the `jsonb` column
 * `props`, and Postgres indexes the part of a table a predicate selects, so each is a partial index
 * over an expression, for the nodes of one label:
 *
 * - a range index is `((props ->> 'p')) WHERE labels @> '["L"]'`. A statement tests each label of a
 *   node on its own (see [PostgresSql.hasLabels]) and reads a property as that same expression, so
 *   the planner sees that the index holds the nodes the statement asks for: a load, a filter, and the
 *   write function finding a node by its id;
 * - a uniqueness constraint is that index made `UNIQUE`. A node without the property reads it as
 *   null, which a unique index does not hold, so it is not constrained, as on the graph engines; and
 *   the index finds a node as a range index does, so no range index is made beside it.
 *
 * What Drivine made is recorded in the graph's catalog table, as on Oracle, and read back from there:
 * an index's expression does not say which label and properties it was made for. An index is named
 * bare, so Postgres keeps its name in lower case, and finds it by the name recorded however it is cased.
 */
internal class PostgresSchemaGrammar(private val graph: PostgresGraph) : SchemaGrammar {

    override val engine = "Postgres"
    override val supportsIfNotExists = true
    override val supportsNamedItems = true

    /** The unique index finds a node as a range index does. */
    override val constraintsRequireBackingIndex = false

    override fun versionMarker(label: String, scopeProperty: String): VersionMarkerStatements = PostgresVersionMarker(graph, label, scopeProperty)

    override fun storeStamp(label: String, keyProperty: String): StoreStampStatements = PostgresStoreStamp(graph, label, keyProperty)

    /** [text] as a schema statement. */
    private fun statement(text: String): SchemaStatement = SchemaStatement.Cypher(text.trimIndent())

    /** [text] as schema statements, after the one that makes the store when it is not there. */
    private fun statements(vararg text: String) = (listOf(graph.ensuring) + text).map(::statement)

    /**
     * Records [spec] as made, as [objectName] (null for one nothing was made for), unless it is
     * recorded already; with [updated], a record already there takes [details]. Two processes
     * recording it at once record it once.
     */
    private fun recorded(spec: SchemaItemSpec, objectName: String?, details: String = "{}", updated: Boolean = false): String = """
        INSERT INTO ${graph.catalog} (kind, label, properties, name, object_name, details)
        VALUES (${literal(spec.kind.name)}, ${literal(spec.label)}, ${literal(json(spec.properties))}, ${literal(spec.effectiveName)}, ${objectName?.let(::literal) ?: "NULL"}, ${literal(details)})
        ON CONFLICT (kind, label, properties) ${if (updated) "DO UPDATE SET details = EXCLUDED.details" else "DO NOTHING"}
    """

    /**
     * Forgets [item], and drops what it was made as when nothing else recorded uses it. What is dropped
     * is the object the record names, and not one named again from the label and the properties, so an
     * item is dropped as it was made whatever a later version would call it.
     */
    private fun forgotten(item: SchemaItemInfo): SchemaStatement {
        val record = "kind = ${literal(item.kind.name)} AND label = ${literal(item.label)} AND properties = ${literal(json(item.properties))}"
        return statement(
            """
            DO ${'$'}forget${'$'}
            DECLARE
                v_object text;
                v_others int;
            BEGIN
                SELECT max(object_name) INTO v_object FROM ${graph.catalog} WHERE $record;
                SELECT count(*) INTO v_others FROM ${graph.catalog} WHERE object_name = v_object AND NOT ($record);
                IF v_object IS NOT NULL AND v_others = 0 THEN
                    -- Written bare, as it was made: Postgres folds the name to lower case either way.
                    EXECUTE 'DROP INDEX IF EXISTS ' || v_object;
                END IF;
                DELETE FROM ${graph.catalog} WHERE $record;
            END
            ${'$'}forget${'$'}
            """
        )
    }

    /**
     * The partial index over [properties] of the nodes of [label]: each property as a statement reads it,
     * and the test of the label a statement makes, both of the table's own columns.
     */
    private fun over(label: String, properties: List<String>): String =
        "${graph.nodes} (${properties.joinToString(", ") { "(${PostgresSql.property("", it)})" }}) WHERE ${hasLabel("", label)}"

    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> = when (spec) {
        is RangeIndexSpec -> {
            val name = graph.objectName("ix", listOf(spec.label) + spec.properties)
            statements("CREATE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.properties)}", recorded(spec, name))
        }
        is VectorIndexSpec -> throw UnsupportedOperationException("Postgres's object manager does not yet support a vector index: see docs/postgres-sql-backend-plan.md.")
        is FullTextIndexSpec -> throw UnsupportedOperationException("Postgres's object manager does not yet support a full-text index: see docs/postgres-sql-backend-plan.md.")
    }

    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = listOf(forgotten(item))

    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = when (spec) {
        is UniquenessConstraintSpec -> {
            val name = graph.objectName("uq", listOf(spec.label) + spec.properties)
            statements("CREATE UNIQUE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.properties)}", recorded(spec, name))
        }
    }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = listOf(forgotten(item))

    /** What is recorded of [kinds], and still there: an index dropped by hand is not listed, so that it is made again when asked for. */
    private fun listed(kinds: String): String = """
        SELECT jsonb_build_object('kind', kind, 'label', label, 'properties', properties::jsonb, 'name', name, 'details', details)
        FROM ${graph.catalog} c
        WHERE kind IN ($kinds)
          AND (object_name IS NULL OR to_regclass(object_name) IS NOT NULL)
        ORDER BY label, properties
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

    /** `23505`: a second row for a unique key, or a unique index made over rows that already repeat one. */
    override fun isConstraintViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is SQLException && it.sqlState == "23505" }

    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { PostgresSql.property("n", it) }
        val entries = spec.properties.zip(keys).joinToString(", ") { (property, key) -> "${literal(property)}, $key" }
        return """
            SELECT jsonb_build_object('properties', jsonb_build_object($entries), 'count', COUNT(*))
            FROM ${graph.nodes} n
            WHERE ${hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }}
            GROUP BY ${keys.joinToString(", ")}
            HAVING COUNT(*) > 1
            LIMIT $limit
        """.trimIndent()
    }

    companion object {
        /**
         * The properties of an index as the record of it holds them: part of the catalog's key, so
         * written here, byte for byte the same each time, as Oracle's grammar writes them.
         */
        fun json(values: List<String>): String = values.joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
    }
}
