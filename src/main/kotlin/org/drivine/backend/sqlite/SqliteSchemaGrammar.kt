package org.drivine.backend.sqlite

import org.drivine.backend.postgres.PostgresSchemaGrammar
import org.drivine.backend.sqlite.SqliteSql.hasLabel
import org.drivine.backend.sqlite.SqliteSql.literal
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
import org.drivine.schema.TextIndexSpec
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.drivine.schema.VersionMarkerStatements
import org.drivine.schema.unsupportedTextIndex
import java.sql.SQLException

/**
 * Indexes and constraints over a graph kept by SQLite. A property is a key of the JSON text `props`,
 * and SQLite indexes an expression over the part of a table a predicate selects, so each is a partial
 * index over the property, for the nodes of one label:
 *
 * - a range index is `((props read as text)) WHERE instr(labels, '"L"') > 0`. A statement tests each
 *   label of a node on its own (see [SqliteSql.hasLabels]) and reads a property as that same expression
 *   (see [SqliteSql.property]), so the planner sees that the index holds the nodes the statement asks
 *   for: a load, a filter, and the write function finding a node by its id;
 * - a uniqueness constraint is that index made `UNIQUE`. A node without the property reads it as null,
 *   which a unique index does not hold, so it is not constrained, as on the graph engines; and the
 *   index finds a node as a range index does, so no range index is made beside it.
 *
 * A vector index and a full-text index are recorded, so that a schema that declares one is applied, and
 * nothing is made for them yet: a search measures every node it may find (see [SqliteBackend]).
 *
 * What Drivine made is recorded in the graph's catalog table, as on Oracle and Postgres, and read back
 * from there: an index's expression does not say which label and properties it was made for. An index
 * a record names that is no longer there is not listed, so it is made again when asked for.
 */
internal class SqliteSchemaGrammar(private val graph: SqliteGraph) : SchemaGrammar {

    override val engine = "SQLite"
    override val supportsIfNotExists = true
    override val supportsNamedItems = true

    /** The unique index finds a node as a range index does. */
    override val constraintsRequireBackingIndex = false

    override fun versionMarker(label: String, scopeProperty: String): VersionMarkerStatements = SqliteVersionMarker(graph, label, scopeProperty)

    override fun storeStamp(label: String, keyProperty: String): StoreStampStatements = SqliteStoreStamp(graph, label, keyProperty)

    /** [text] as a schema statement. */
    private fun statement(text: String): SchemaStatement = SchemaStatement.Cypher(text.trimIndent())

    /** [text] as schema statements, after those that make the store when it is not there. */
    private fun statements(vararg text: String) = (graph.ddl + text).map(::statement)

    /**
     * Records [spec] as made, as [objectName] (null for one nothing was made for), unless it is
     * recorded already. Two processes recording it at once record it once.
     */
    private fun recorded(spec: SchemaItemSpec, objectName: String?, details: String = "{}"): String = """
        INSERT INTO ${graph.catalog} (kind, label, properties, name, object_name, details)
        VALUES (${literal(spec.kind.name)}, ${literal(spec.label)}, ${literal(json(spec.properties))}, ${literal(spec.effectiveName)}, ${objectName?.let(::literal) ?: "NULL"}, ${literal(details)})
        ON CONFLICT (kind, label, properties) DO NOTHING
    """

    /**
     * Forgets [item], and drops the index it was made as. SQLite has no block to read the record's
     * object in, so the index is named again from the item's kind, label and properties, as this version
     * names it. Nothing was made for a vector or a full-text index, so nothing is dropped for one.
     */
    private fun forgotten(item: SchemaItemInfo): List<SchemaStatement> {
        val kind = when (item.kind) {
            SchemaItemKind.RANGE_INDEX -> RANGE
            SchemaItemKind.UNIQUENESS_CONSTRAINT -> UNIQUE
            else -> null
        }
        val record = "kind = ${literal(item.kind.name)} AND label = ${literal(item.label)} AND properties = ${literal(json(item.properties))}"
        return listOfNotNull(
            kind?.let { statement("DROP INDEX IF EXISTS ${graph.objectName(it, listOf(item.label) + item.properties)}") },
            statement("DELETE FROM ${graph.catalog} WHERE $record"),
        )
    }

    /**
     * The partial index over [properties] of the nodes of [label]: each property as a statement reads it,
     * and the test of the label a statement makes, both of the table's own columns.
     */
    private fun over(label: String, properties: List<String>): String =
        "${graph.nodes} (${properties.joinToString(", ") { SqliteSql.property("", it) }}) WHERE ${hasLabel("", label)}"

    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> = when (spec) {
        is RangeIndexSpec -> {
            val name = graph.objectName(RANGE, listOf(spec.label) + spec.properties)
            statements("CREATE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.properties)}", recorded(spec, name))
        }
        // Recorded and not made: SQLite has no vector index of its own.
        is VectorIndexSpec -> statements(recorded(spec, null, """{"dimensions": ${spec.dimensions}, "similarity": "${spec.similarity.name}"}"""))
        // Recorded and not made yet: a search reads every node of the label.
        is FullTextIndexSpec -> statements(
            recorded(spec, null, spec.analyzer?.let { "{\"analyzer\": ${json(listOf(it)).removeSurrounding("[", "]")}}" } ?: "{}"),
        )
        is TextIndexSpec -> throw unsupportedTextIndex(spec, engine)
    }

    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = forgotten(item)

    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = when (spec) {
        is UniquenessConstraintSpec -> {
            val name = graph.objectName(UNIQUE, listOf(spec.label) + spec.properties)
            statements("CREATE UNIQUE INDEX IF NOT EXISTS $name ON ${over(spec.label, spec.properties)}", recorded(spec, name))
        }
    }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = forgotten(item)

    /** What is recorded of [kinds], and still there: an index dropped by hand is not listed, so that it is made again when asked for. */
    private fun listed(kinds: String): String = """
        ${SqliteSql.JSON_ROWS}
        SELECT json_object('kind', kind, 'label', label, 'properties', json(properties), 'name', name, 'details', json(details))
        FROM ${graph.catalog} c
        WHERE kind IN ($kinds)
          AND (object_name IS NULL OR EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = c.object_name))
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

    /** A second row for a unique key, or a unique index made over rows that already repeat one: `SQLITE_CONSTRAINT_UNIQUE`, said in its message. */
    override fun isConstraintViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is SQLException && it.message.orEmpty().contains("UNIQUE constraint failed") }

    /** A node's element id is its `nid`; [limit] of a key's nodes at most are named, the first made first. */
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { SqliteSql.property("n", it) }
        val columns = keys.mapIndexed { i, key -> "$key AS k$i" }.joinToString(", ")
        val grouping = keys.indices.joinToString(", ") { "k$it" }
        val entries = spec.properties.mapIndexed { i, property -> "${literal(property)}, k.k$i" }.joinToString(", ")
        val sampled = SchemaGrammar.sampledNodeId(spec)
        val node = listOfNotNull("'elementId', k.nid", sampled?.let { "${literal(it)}, k.sampled" }).joinToString(", ")
        return """
            ${SqliteSql.JSON_ROWS}
            SELECT json_object('properties', json_object($entries), 'count', MAX(k.total),
                               'nodes', json_group_array(json_object($node) ORDER BY k.nid) FILTER (WHERE k.position <= $limit))
            FROM (
                SELECT n.nid, $columns${sampled?.let { ", ${SqliteSql.property("n", it)} AS sampled" }.orEmpty()},
                       row_number() OVER (PARTITION BY ${keys.joinToString(", ")} ORDER BY n.nid) AS position,
                       COUNT(*) OVER (PARTITION BY ${keys.joinToString(", ")}) AS total
                FROM ${graph.nodes} n
                WHERE ${hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }}
            ) k
            WHERE k.total > 1
            GROUP BY $grouping
            LIMIT $limit
        """.trimIndent()
    }

    companion object {
        /** What the name of a range index, and of a uniqueness constraint's index, says it is. */
        private const val RANGE = "ix"
        private const val UNIQUE = "uq"

        /** The properties of an index as the record of it holds them, byte for byte as Postgres's grammar writes them. */
        fun json(values: List<String>): String = PostgresSchemaGrammar.json(values)
    }
}
