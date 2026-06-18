package org.drivine.schema

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
 * @param vectorIndexCapacity initial capacity for created vector indexes (Memgraph grows from here)
 */
class MemgraphSchemaGrammar(
    private val vectorIndexCapacity: Int = DEFAULT_VECTOR_CAPACITY,
) : SchemaGrammar {

    override val engine = "Memgraph"
    override val supportsIfNotExists = false
    override val supportsNamedItems = true

    // ----- DDL emission -----

    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> = when (spec) {
        is VectorIndexSpec -> listOf(
            SchemaStatement.Cypher(
                """
                CREATE VECTOR INDEX ${spec.effectiveName} ON :${spec.label}(${spec.property})
                WITH CONFIG {"dimension": ${spec.dimensions}, "metric": "${metricName(spec.similarity)}", "capacity": $vectorIndexCapacity}
                """.trimIndent()
            )
        )

        is RangeIndexSpec -> listOf(
            SchemaStatement.Cypher(
                "CREATE INDEX ON :${spec.label}(${spec.properties.joinToString(", ")})"
            )
        )
    }

    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = when (item.kind) {
        SchemaItemKind.VECTOR_INDEX -> listOf(
            SchemaStatement.Cypher("DROP VECTOR INDEX ${item.name ?: defaultVectorName(item)}")
        )

        else -> listOf(
            SchemaStatement.Cypher("DROP INDEX ON :${item.label}(${item.properties.joinToString(", ")})")
        )
    }

    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = when (spec) {
        is UniquenessConstraintSpec -> listOf(
            SchemaStatement.Cypher(
                "CREATE CONSTRAINT ON (n:${spec.label}) " +
                    "ASSERT ${spec.properties.joinToString(", ") { "n.$it" }} IS UNIQUE"
            )
        )
    }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = listOf(
        SchemaStatement.Cypher(
            "DROP CONSTRAINT ON (n:${item.label}) " +
                "ASSERT ${item.properties.joinToString(", ") { "n.$it" }} IS UNIQUE"
        )
    )

    // ----- Introspection -----

    override fun listIndexesQuery(kind: SchemaItemKind): String = when (kind) {
        SchemaItemKind.VECTOR_INDEX -> """
            CALL vector_search.show_index_info()
            YIELD index_name, label, property, dimension, metric
            RETURN {index_name: index_name, label: label, property: property, dimension: dimension, metric: metric}
        """.trimIndent()

        else -> "SHOW INDEX INFO"
    }

    /**
     * Vector rows arrive as maps (single map column from the `CALL … RETURN {…}` query);
     * label-property rows arrive as positional lists (`SHOW INDEX INFO` is multi-column:
     * index type, label, property, count).
     */
    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { row ->
        when (row) {
            is Map<*, *> -> parseVectorIndexRow(row)
            is List<*> -> parseLabelPropertyIndexRow(row)
            else -> null
        }
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

    override fun listConstraintsQuery(): String = "SHOW CONSTRAINT INFO"

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

    override fun isConstraintViolation(e: Throwable): Boolean {
        val messages = SchemaGrammar.messagesOf(e)
        // "Unable to create constraint :Label(prop), because an existing node violates it."
        return messages.contains("constraint", ignoreCase = true) &&
            (messages.contains("violat", ignoreCase = true) ||
                messages.contains("unable to create", ignoreCase = true))
    }

    // ----- Helpers -----

    /**
     * Strips a leading `:` from an introspected label. Memgraph 3.11+ returns labels colon-prefixed
     * (`:Proposition`) from `SHOW INDEX INFO` / `vector_search.show_index_info()` / `SHOW CONSTRAINT
     * INFO`, where earlier versions returned them bare (`Proposition`). Without this, identity matching
     * against a spec's bare label fails and every `ensure` re-creates instead of matching.
     */
    private fun normalizeLabel(raw: String): String = raw.removePrefix(":")

    private fun defaultVectorName(item: SchemaItemInfo): String =
        "${item.label}_${item.properties.first()}_vector"

    /** Memgraph vector indexes use uSearch metric shorthand. */
    private fun metricName(similarity: SimilarityFunction): String = when (similarity) {
        SimilarityFunction.COSINE -> "cos"
        SimilarityFunction.EUCLIDEAN -> "l2sq"
    }

    private fun metricFromName(metric: String): SimilarityFunction? = when (metric.lowercase()) {
        "cos" -> SimilarityFunction.COSINE
        "l2sq" -> SimilarityFunction.EUCLIDEAN
        else -> null
    }

    companion object {
        const val DEFAULT_VECTOR_CAPACITY = 10_000
    }
}