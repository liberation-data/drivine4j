package org.drivine.schema

import org.drivine.cypher.store.ddl.MemgraphDdl

/**
 * Schema DDL and introspection for Memgraph.
 *
 * Divergences from Neo4j:
 * - No `IF NOT EXISTS` guards — existence is checked before CREATE / DROP.
 * - Vector indexes are named and configured via `WITH CONFIG {dimension: …, metric: …, capacity: …}`,
 *   with uSearch metric shorthand (`cos` / `l2sq`). Introspection via
 *   `CALL vector_search.show_index_info()`.
 * - Label-property (range) indexes are unnamed: `CREATE INDEX ON :Label(prop)`. Introspection via
 *   `SHOW INDEX INFO`, which returns positional multi-column rows.
 * - Uniqueness constraints are unnamed: `CREATE CONSTRAINT ON (n:Label) ASSERT n.prop IS UNIQUE`.
 *   Introspection via `SHOW CONSTRAINT INFO` (positional multi-column rows).
 *
 * The statements are the module's ([MemgraphDdl]), which the three Drivine libraries share; reading
 * what introspection gives and telling a violation from another failure are this grammar's.
 *
 * @param vectorIndexCapacity initial capacity for created vector indexes (Memgraph grows from here)
 */
class MemgraphSchemaGrammar(
    private val vectorIndexCapacity: Int = DEFAULT_VECTOR_CAPACITY,
) : SchemaGrammar {

    override val engine = "Memgraph"
    override val supportsIfNotExists = false
    override val supportsNamedItems = true

    /** The module's statements, with the capacity a vector index is made with. */
    private val ddl = MemgraphDdl(vectorIndexCapacity)

    // ----- DDL emission: the module's (MemgraphDdl) -----

    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> = statementsOf { ddl.createIndex(spec, existing?.catalogItem()) }

    /** A range index by its label and properties; a vector or text index by its name, or the name it would have been given. */
    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = statementsOf { ddl.dropIndex(item.catalogItem()) }

    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = statementsOf { ddl.createConstraint(spec) }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = statementsOf { ddl.dropConstraint(item.catalogItem()) }

    // ----- Introspection -----

    /** Range and text (full-text) indexes both are listed by `SHOW INDEX INFO`; vector indexes by `vector_search.show_index_info()`. */
    override fun listIndexesQuery(kind: SchemaItemKind): String = ddl.listIndexesQuery(kind)

    /**
     * Vector rows arrive as maps (single map column from the `CALL … RETURN {…}` query);
     * label-property and text rows arrive as positional lists (`SHOW INDEX INFO` is multi-column:
     * index type, label, property, count). The index-type column distinguishes them — text indexes
     * report `"label_text (name: <name>)"`.
     */
    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { row ->
        when (row) {
            is Map<*, *> -> parseVectorIndexRow(row)
            is List<*> -> parseTextIndexRow(row) ?: parseLabelPropertyIndexRow(row)
            else -> null
        }
    }

    /**
     * Parses a `SHOW INDEX INFO` text-index row. Memgraph reports the index type as
     * `"label_text (name: <name>)"` and the properties as a list, e.g.
     * `["label_text (name: chunk_ft)", "Chunk", ["title", "body"], 0]`. Returns null for any row
     * that is not a text index, so the range parser gets its turn.
     */
    private fun parseTextIndexRow(row: List<*>): SchemaItemInfo? {
        if (row.size < 3) return null
        val indexType = (row[0] as? String)?.lowercase() ?: return null
        if (!indexType.startsWith("label_text")) return null
        val label = (row[1] as? String)?.let(::normalizeLabel) ?: return null
        val properties = when (val property = row[2]) {
            is String -> listOf(property)
            is List<*> -> property.filterIsInstance<String>()
            else -> return null
        }
        if (properties.isEmpty()) return null
        return SchemaItemInfo(
            kind = SchemaItemKind.FULLTEXT_INDEX,
            label = label,
            properties = properties,
            name = TEXT_INDEX_NAME.find(row[0] as String)?.groupValues?.get(1),
        )
    }

    private fun parseVectorIndexRow(map: Map<*, *>): SchemaItemInfo? {
        val label = (map["label"] as? String)?.let(::normalizeLabel) ?: return null
        val property = map["property"] as? String ?: return null
        return SchemaItemInfo(
            kind = SchemaItemKind.VECTOR_INDEX,
            label = label,
            properties = listOf(property),
            name = map["index_name"] as? String,
            dimensions = (map["dimension"] as? Number)?.toInt(),
            similarity = (map["metric"] as? String)?.let { metricFromName(it) },
        )
    }

    private fun parseLabelPropertyIndexRow(row: List<*>): SchemaItemInfo? {
        if (row.size < 3) return null
        val indexType = (row[0] as? String)?.lowercase() ?: return null
        // "label+property" rows are range indexes; "label" rows (label-only) are not managed, and
        // "label+property_vector" (Memgraph 3.11+ surfaces a vector index's backing here) is owned by
        // the vector-introspection path, not range — exclude it so it isn't read as a phantom range index.
        if (!indexType.startsWith("label+prop") || indexType.contains("vector")) return null
        val label = (row[1] as? String)?.let(::normalizeLabel) ?: return null
        val properties = when (val property = row[2]) {
            is String -> listOf(property)
            is List<*> -> property.filterIsInstance<String>()
            else -> return null
        }
        if (properties.isEmpty()) return null
        return SchemaItemInfo(
            kind = SchemaItemKind.RANGE_INDEX,
            label = label,
            properties = properties,
            name = null, // Memgraph label-property indexes are unnamed
        )
    }

    override fun listConstraintsQuery(): String = ddl.listConstraintsQuery()

    /**
     * `SHOW CONSTRAINT INFO` is multi-column (constraint type, label, properties) so rows arrive
     * as positional lists.
     */
    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { row ->
        val cols = row as? List<*> ?: return@mapNotNull null
        if (cols.size < 3) return@mapNotNull null
        if ((cols[0] as? String)?.lowercase() != "unique") return@mapNotNull null
        val label = (cols[1] as? String)?.let(::normalizeLabel) ?: return@mapNotNull null
        val properties = when (val property = cols[2]) {
            is String -> listOf(property)
            is List<*> -> property.filterIsInstance<String>()
            else -> return@mapNotNull null
        }
        if (properties.isEmpty()) return@mapNotNull null
        SchemaItemInfo(
            kind = SchemaItemKind.UNIQUENESS_CONSTRAINT,
            label = label,
            properties = properties,
            name = null, // Memgraph constraints are unnamed
        )
    }

    // ----- Violations -----

    /**
     * By the message: Memgraph gives every error one code, `Memgraph.ClientError.MemgraphError.MemgraphError`.
     * "Unable to create unique constraint :Label(prop), because an existing node violates it." Its
     * other refusals to make one ("Constraints are not supported in analytical storage mode") say
     * nothing of a violation.
     */
    override fun isConstraintViolation(e: Throwable): Boolean {
        val messages = SchemaGrammar.messagesOf(e)
        return messages.contains("constraint", ignoreCase = true) &&
            messages.contains("violat", ignoreCase = true)
    }

    // ----- Helpers -----

    /**
     * Strips a leading `:` from an introspected label. Memgraph 3.11+ returns labels colon-prefixed
     * (`:Proposition`) from `SHOW INDEX INFO` / `vector_search.show_index_info()` / `SHOW CONSTRAINT
     * INFO`, where earlier versions returned them bare (`Proposition`). Without this, identity matching
     * against a spec's bare label fails and every `ensure` re-creates instead of matching.
     */
    private fun normalizeLabel(raw: String): String = raw.removePrefix(":")

    private fun metricFromName(metric: String): SimilarityFunction? = when (metric.lowercase()) {
        "cos" -> SimilarityFunction.COSINE
        "l2sq" -> SimilarityFunction.EUCLIDEAN
        else -> null
    }

    companion object {
        const val DEFAULT_VECTOR_CAPACITY = MemgraphDdl.DEFAULT_VECTOR_CAPACITY

        /** Extracts `<name>` from a `SHOW INDEX INFO` text-index type string `label_text (name: <name>)`. */
        private val TEXT_INDEX_NAME = Regex("""name:\s*([^)]+)\)""")
    }
}