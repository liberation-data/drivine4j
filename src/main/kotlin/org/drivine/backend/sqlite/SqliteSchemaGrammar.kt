package org.drivine.backend.sqlite

import org.drivine.backend.sqlite.SqliteSql.hasLabel
import org.drivine.backend.sqlite.SqliteSql.literal
import org.drivine.cypher.store.ddl.SqliteDdl
import org.drivine.cypher.store.schema.SqliteSchema
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.LowerCopyStatements
import org.drivine.schema.SchemaAliases
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaStatement
import org.drivine.schema.StoreStampStatements
import org.drivine.schema.VersionMarkerStatements
import org.drivine.schema.catalogItem
import org.drivine.schema.sharedStoreStamp
import org.drivine.schema.sharedVersionMarker
import org.drivine.schema.statementsOf
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
 *
 * The statements are the module's ([SqliteDdl]), which the three Drivine libraries share, so each makes and
 * reads the same indexes. What this grammar adds is drivine4j's: reading the catalog's rows, telling a
 * violation from another failure, and sampling the keys a constraint would refuse.
 */
internal class SqliteSchemaGrammar(internal val graph: SqliteGraph) : SchemaGrammar, SchemaAliases {

    /** The module's schema of this graph's store, whose reading of a listing's rows this grammar gives. */
    private val schema = SqliteSchema(graph.store)

    override val engine = SqliteDdl.ENGINE
    override val supportsIfNotExists = true
    override val supportsNamedItems = true

    /** The unique index finds a node as a range index does. */
    override val constraintsRequireBackingIndex = false

    /** The marker as the module writes it for this graph ([org.drivine.cypher.store.marker.MarkerStatements]). */
    override fun versionMarker(label: String, scopeProperty: String): VersionMarkerStatements =
        sharedVersionMarker(label, scopeProperty, graph.store)

    /** The store's stamp as the module writes it for this graph ([org.drivine.cypher.store.marker.StoreStampStatements]). */
    override fun storeStamp(label: String, keyProperty: String): StoreStampStatements =
        sharedStoreStamp(label, keyProperty, graph.store)

    override fun lowerCopies(): LowerCopyStatements = SqliteLowerCopies(graph)

    override fun aliased(item: SchemaItemInfo, aliases: List<String>): SchemaStatement =
        SchemaStatement.Cypher(graph.store.aliased(item.catalogItem(), aliases))

    /** After the statements that make the store when it is not there. */
    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> =
        statementsOf { graph.store.createIndex(spec, existing?.catalogItem()) }

    /**
     * Forgets [item], and drops the index it was made as. SQLite has no block to read the record's object in, so the
     * index is named again from the item's kind, label and properties, as this version names it.
     */
    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = statementsOf { graph.store.dropIndex(item.catalogItem()) }

    /** After the statements that make the store when it is not there. */
    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = statementsOf { graph.store.createConstraint(spec) }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = statementsOf { graph.store.dropConstraint(item.catalogItem()) }

    /** What is recorded of [kind], and still there: an index dropped by hand is not listed, so that it is made again when asked for. */
    override fun listIndexesQuery(kind: SchemaItemKind): String = graph.store.listIndexesQuery(kind)

    override fun listConstraintsQuery(): String = graph.store.listConstraintsQuery()

    /**
     * The records among [rows], read by the module's [SqliteSchema]: the rows its listings give after the records, one for each
     * object under a name Drivine gives (an `object` row), are what the planner reads an item's form from, and no item.
     */
    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = schema.parseIndexRows(rows)

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
}
