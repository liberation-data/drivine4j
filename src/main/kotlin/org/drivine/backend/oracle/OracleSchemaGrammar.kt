package org.drivine.backend.oracle

import org.drivine.backend.oracle.OracleSql.hasLabel
import org.drivine.backend.oracle.OracleSql.literal
import org.drivine.backend.oracle.OracleSql.path
import org.drivine.backend.sql.SqlGraph
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.cypher.store.ddl.OracleSideTable
import org.drivine.schema.ConstraintSpec
import org.drivine.schema.IndexSpec
import org.drivine.schema.LowerCopyStatements
import org.drivine.schema.SchemaAliases
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.SchemaItemInfo
import org.drivine.schema.SchemaItemKind
import org.drivine.schema.SchemaStatement
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.StoreStampStatements
import org.drivine.schema.VersionMarkerStatements
import org.drivine.schema.catalogItem
import org.drivine.schema.sharedStoreStamp
import org.drivine.schema.sharedVersionMarker
import org.drivine.schema.statementsOf
import java.sql.SQLException

/**
 * Indexes and constraints over a graph kept by Oracle. A property is a path into a JSON column, so
 * each is an index over an expression:
 *
 * - a range index is two indexes. One reads the property as text, the expression a save and a load
 *   by id find a node by, testing the label on what they find. It is over the property whatever the
 *   label, so two labels that index a property of one name share it. The other, the labelled index,
 *   reads the property of the nodes that carry the label and is null for every other node (see
 *   [OracleSql.labelled]): a filter on the nodes of the label is read through it, so that one index
 *   answers both the label and the property, and Oracle is not left to choose between the index over
 *   labels and the one over the property, which it does badly;
 * - a uniqueness constraint is a unique index over the property of the nodes that carry the label,
 *   and null for every other node, which a unique index does not hold (see [OracleSql.unique]). It
 *   comes with the range index a node is found by. Over one property, its index is the same value
 *   as a labelled index would be, so it stands for one: a filter on the property is read through
 *   it, and no labelled index is made beside it.
 *
 * A vector index is a table of embeddings beside the nodes, and a full-text index a table of their
 * text, each with an index over it ([OracleSideTable]).
 *
 * Oracle's dictionary holds the expressions and not what they were made for, so what Drivine made is
 * recorded in a table of the graph's own, and read back from there.
 *
 * The statements are the module's ([OracleDdl]), which the three Drivine libraries share, so each makes
 * and reads the same indexes. What this grammar adds is drivine4j's: reading the catalog's rows, an index
 * listed as [INCOMPLETE] made again, what is kept of the catalog read again once an index has changed,
 * telling a violation from another failure, and sampling the keys a constraint would refuse.
 */
internal class OracleSchemaGrammar(internal val graph: OracleGraph) : SchemaGrammar, SchemaAliases {

    override val engine = OracleDdl.ENGINE
    override val supportsIfNotExists = true
    override val supportsNamedItems = true

    /**
     * A node is found by the range index's expression, and the unique index only refuses a second
     * one; but the range index is made by [createConstraint] itself, which knows a labelled index is
     * not wanted beside a unique index over one property.
     */
    override val constraintsRequireBackingIndex = false

    /** A range index made before there were labelled indexes, with neither one nor a uniqueness constraint's index to stand for it: see [listIndexesQuery]. */
    override fun isIncomplete(existing: SchemaItemInfo): Boolean = existing.status == INCOMPLETE

    /** What is kept of the catalog to write a read with is read again: see [LabelledIndexCatalog]. */
    override fun changed() = LabelledIndexCatalog.changed(graph.name)

    /** The marker as the module writes it for this graph ([org.drivine.cypher.store.marker.MarkerStatements]). */
    override fun versionMarker(label: String, scopeProperty: String): VersionMarkerStatements =
        sharedVersionMarker(label, scopeProperty, graph.store)

    /** The store's stamp as the module writes it for this graph ([org.drivine.cypher.store.marker.StoreStampStatements]). */
    override fun storeStamp(label: String, keyProperty: String): StoreStampStatements =
        sharedStoreStamp(label, keyProperty, graph.store)

    override fun lowerCopies(): LowerCopyStatements = OracleLowerCopies(graph)

    override fun aliased(item: SchemaItemInfo, aliases: List<String>): SchemaStatement =
        SchemaStatement.Cypher(graph.store.aliased(item.catalogItem(), aliases))

    /** After the block that makes the store when it is not there. */
    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> =
        statementsOf { graph.store.createIndex(spec, existing?.catalogItem()) }

    /**
     * Forgets [item], and drops what its record names when nothing else recorded uses it: an index, or
     * a table beside the nodes with its trigger; a range index's labelled index with it.
     */
    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = statementsOf { graph.store.dropIndex(item.catalogItem()) }

    /**
     * A uniqueness constraint, with the range index a node is found by, after the block that makes the store when it is
     * not there. Data the constraint refuses stops the statements at the unique index, and the range index is left
     * [INCOMPLETE], to be made whole by an `ensure` of it.
     */
    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = statementsOf { graph.store.createConstraint(spec) }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = statementsOf { graph.store.dropConstraint(item.catalogItem()) }

    /**
     * The indexes over the nodes of one label a filter can be read through, as rows of `kind`,
     * `label` and `properties`. See [LabelledIndexCatalog].
     */
    fun labelledIndexesQuery(): String = graph.store.labelledIndexesQuery()

    /**
     * What is recorded of [kind], and still there; a range index that lacks what this version makes
     * for it listed as [INCOMPLETE], so that `ensure` makes the rest.
     */
    override fun listIndexesQuery(kind: SchemaItemKind): String = graph.store.listIndexesQuery(kind)

    override fun listConstraintsQuery(): String = graph.store.listConstraintsQuery()

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
            status = map["status"] as? String,
        )
    }

    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = parseIndexRows(rows)

    /** `ORA-00001`, a second row for a unique key; `ORA-01452`, a unique index over rows that already repeat one. */
    override fun isConstraintViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is SQLException && it.errorCode in setOf(1, 1452) }

    /**
     * Each node of a key that repeats is numbered within its key, and counted with it, so that a
     * row names [limit] of its nodes at most however many hold the key. A node's element id is its
     * `nid`.
     */
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { "JSON_VALUE(n.props, ${path(it)})" }
        val aliases = keys.indices.map { "k$it" }
        val entries = spec.properties.zip(aliases).joinToString(", ") { (property, key) -> "${literal(property)} VALUE $key" }
        val nodeId = SchemaGrammar.sampledNodeId(spec)
        val node = listOfNotNull("'elementId' VALUE nid", nodeId?.let { "${literal(it)} VALUE i" }).joinToString(", ")
        val selected = (keys.zip(aliases).map { (key, alias) -> "$key AS $alias" } + listOfNotNull(nodeId?.let { "JSON_VALUE(n.props, ${path(it)}) AS i" }))
            .joinToString(", ")
        val partition = keys.joinToString(", ")
        return """
            SELECT JSON_OBJECT('properties' VALUE JSON_OBJECT($entries), 'count' VALUE MAX(c),
                               'nodes' VALUE JSON_ARRAYAGG(JSON_OBJECT($node) ORDER BY nid RETURNING JSON) RETURNING JSON)
            FROM (SELECT n.nid, $selected, COUNT(*) OVER (PARTITION BY $partition) AS c, ROW_NUMBER() OVER (PARTITION BY $partition ORDER BY n.nid) AS r
                  FROM ${graph.nodes} n
                  WHERE ${hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }})
            WHERE c > 1 AND r <= $limit
            GROUP BY ${aliases.joinToString(", ")}
            FETCH FIRST $limit ROWS ONLY
        """.trimIndent()
    }

    companion object {
        /** The status a range index is listed with when it lacks what this version makes for it. */
        const val INCOMPLETE = OracleDdl.INCOMPLETE

        /** The properties of an index as the record of it holds them: see [SqlGraph.json]. */
        fun json(values: List<String>): String = SqlGraph.json(values)
    }
}
