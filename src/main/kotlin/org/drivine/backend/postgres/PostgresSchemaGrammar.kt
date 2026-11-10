package org.drivine.backend.postgres

import org.drivine.backend.postgres.PostgresSql.hasLabel
import org.drivine.backend.postgres.PostgresSql.literal
import org.drivine.backend.sql.SqlGraph
import org.drivine.cypher.store.ddl.PostgresDdl
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
import org.drivine.schema.statementsOf
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
 * - a vector index is an HNSW index of pgvector over the embeddings of the label's nodes that are of
 *   its size, read from the JSON array they are stored as (see [PostgresSql.embedding]);
 * - a full-text index is a GIN index of the trigrams of the text a search reads (see
 *   [PostgresSql.searchedText]). It narrows a search to the nodes whose text holds the words of a term,
 *   which it answers as a `LIKE`; the regular expressions a search matches its terms by then score
 *   them, so a search scores alike with an index and without one.
 *
 * What Drivine made is recorded in the graph's catalog table, as on Oracle, and read back from there:
 * an index's expression does not say which label and properties it was made for. An index is named
 * bare, so Postgres keeps its name in lower case, and finds it by the name recorded however it is cased.
 *
 * The statements are the module's ([PostgresDdl]), which the three Drivine libraries share, so each makes
 * and reads the same indexes. What this grammar adds is drivine4j's: reading the catalog's rows, telling a
 * violation from another failure, and sampling the keys a constraint would refuse.
 */
internal class PostgresSchemaGrammar(private val graph: PostgresGraph) : SchemaGrammar, SchemaAliases {

    override val engine = PostgresDdl.ENGINE
    override val supportsIfNotExists = true
    override val supportsNamedItems = true

    /** The unique index finds a node as a range index does. */
    override val constraintsRequireBackingIndex = false

    override fun versionMarker(label: String, scopeProperty: String): VersionMarkerStatements = PostgresVersionMarker(graph, label, scopeProperty)

    override fun storeStamp(label: String, keyProperty: String): StoreStampStatements = PostgresStoreStamp(graph, label, keyProperty)

    override fun lowerCopies(): LowerCopyStatements = PostgresLowerCopies(graph)

    override fun aliased(item: SchemaItemInfo, aliases: List<String>): SchemaStatement =
        SchemaStatement.Cypher(graph.store.aliased(item.catalogItem(), aliases))

    /** After the block that makes the store when it is not there. */
    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> =
        statementsOf { graph.store.createIndex(spec, existing?.catalogItem()) }

    /** Forgets [item], and drops the index its record names when nothing else recorded uses it. */
    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = statementsOf { graph.store.dropIndex(item.catalogItem()) }

    /** After the block that makes the store when it is not there. */
    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = statementsOf { graph.store.createConstraint(spec) }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = statementsOf { graph.store.dropConstraint(item.catalogItem()) }

    /** What is recorded of [kind], and still there: an index dropped by hand is not listed, so that it is made again when asked for. */
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
        )
    }

    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = parseIndexRows(rows)

    /** `23505`: a second row for a unique key, or a unique index made over rows that already repeat one. */
    override fun isConstraintViolation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is SQLException && it.sqlState == "23505" }

    /** A node's element id is its `nid`; [limit] of a key's nodes at most are named, the first made first. */
    override fun sampleConflictsQuery(spec: ConstraintSpec, limit: Int): String {
        val keys = spec.properties.map { PostgresSql.property("n", it) }
        val entries = spec.properties.zip(keys).joinToString(", ") { (property, key) -> "${literal(property)}, $key" }
        val node = listOfNotNull("'elementId', n.nid", SchemaGrammar.sampledNodeId(spec)?.let { "${literal(it)}, ${PostgresSql.property("n", it)}" })
            .joinToString(", ")
        return """
            SELECT jsonb_build_object('properties', jsonb_build_object($entries), 'count', COUNT(*),
                                      'nodes', to_jsonb((array_agg(jsonb_build_object($node) ORDER BY n.nid))[1:$limit]))
            FROM ${graph.nodes} n
            WHERE ${hasLabel("n", spec.label)} AND ${keys.joinToString(" AND ") { "$it IS NOT NULL" }}
            GROUP BY ${keys.joinToString(", ")}
            HAVING COUNT(*) > 1
            LIMIT $limit
        """.trimIndent()
    }

    companion object {
        /** The properties of an index as the record of it holds them: see [SqlGraph.json]. */
        fun json(values: List<String>): String = SqlGraph.json(values)
    }
}
