package org.drivine.schema

/**
 * Schema DDL and introspection for FalkorDB.
 *
 * Divergences from Neo4j:
 * - No `IF NOT EXISTS` guards and no user-supplied item names — indexes are identified by
 *   (label, property) only.
 * - Indexes are managed per label: `CALL db.indexes()` returns one row per label whose `types`
 *   map records which index types cover each property. Range index identity therefore uses
 *   coverage (subset) semantics rather than exact property-set equality.
 * - Uniqueness constraints are not Cypher at all: they are managed with the Redis command
 *   `GRAPH.CONSTRAINT CREATE / DROP`, emitted here as [SchemaStatement.Native] and executed at
 *   driver level. Constraint creation requires an exact-match index on the same properties to
 *   exist first, and runs asynchronously (status `UNDER CONSTRUCTION` → `OPERATIONAL` / `FAILED`).
 */
class FalkorDbSchemaGrammar : SchemaGrammar {

    override val engine = "FalkorDB"
    override val supportsIfNotExists = false
    override val supportsNamedItems = false
    override val constraintsRequireBackingIndex = true
    override val constraintCreationIsAsync = true

    // ----- DDL emission -----

    /**
     * FalkorDB rejects CREATE INDEX statements that mention an already-indexed property
     * ("Attribute 'x' is already indexed"), so range creation only emits the properties missing
     * from the label's [existing] index.
     */
    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> = when (spec) {
        is VectorIndexSpec -> listOf(
            SchemaStatement.Cypher(
                """
                CREATE VECTOR INDEX FOR (n:${spec.label}) ON (n.${spec.property})
                OPTIONS {dimension: ${spec.dimensions}, similarityFunction: '${similarityName(spec.similarity)}'}
                """.trimIndent()
            )
        )

        is RangeIndexSpec -> {
            val alreadyIndexed = existing?.properties?.toSet() ?: emptySet()
            val missing = spec.properties.filterNot { it in alreadyIndexed }
            if (missing.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    SchemaStatement.Cypher(
                        "CREATE INDEX FOR (n:${spec.label}) ON " +
                            missing.joinToString(", ", "(", ")") { "n.$it" }
                    )
                )
            }
        }
    }

    /**
     * FalkorDB indexes are per-property under the hood — emit one DROP per property so that
     * dropping a narrowed item never removes more than was asked for.
     */
    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = when (item.kind) {
        SchemaItemKind.VECTOR_INDEX -> item.properties.map {
            SchemaStatement.Cypher("DROP VECTOR INDEX FOR (n:${item.label}) ON (n.$it)")
        }

        else -> item.properties.map {
            SchemaStatement.Cypher("DROP INDEX FOR (n:${item.label}) ON (n.$it)")
        }
    }

    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = when (spec) {
        is UniquenessConstraintSpec -> listOf(
            SchemaStatement.Native(
                listOf(
                    "GRAPH.CONSTRAINT", "CREATE", SchemaStatement.Native.GRAPH_NAME,
                    "UNIQUE", "NODE", spec.label,
                    "PROPERTIES", spec.properties.size.toString(),
                ) + spec.properties
            )
        )
    }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = listOf(
        SchemaStatement.Native(
            listOf(
                "GRAPH.CONSTRAINT", "DROP", SchemaStatement.Native.GRAPH_NAME,
                "UNIQUE", "NODE", item.label,
                "PROPERTIES", item.properties.size.toString(),
            ) + item.properties
        )
    )

    // ----- Introspection -----

    override fun listIndexesQuery(kind: SchemaItemKind): String = """
        CALL db.indexes()
        YIELD label, properties, types, options, entitytype
        RETURN {label: label, properties: properties, types: types, options: options, entitytype: entitytype}
    """.trimIndent()

    /**
     * One `db.indexes()` row describes all indexes on a label. The `types` map records the index
     * types covering each property, e.g. `{age: [RANGE], embedding: [VECTOR]}` — so a single row
     * can yield both a range item (all RANGE properties together) and vector items (one per
     * VECTOR property).
     */
    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> = rows.flatMap { row ->
        val map = row as? Map<*, *> ?: return@flatMap emptyList()
        val entityType = (map["entitytype"] as? String)?.uppercase()
        if (entityType != null && entityType != "NODE") return@flatMap emptyList()
        val label = map["label"] as? String ?: return@flatMap emptyList()
        val types = map["types"] as? Map<*, *> ?: return@flatMap emptyList()
        val options = map["options"] as? Map<*, *>

        val items = mutableListOf<SchemaItemInfo>()

        val rangeProperties = types.entries
            .filter { (_, propTypes) -> hasType(propTypes, "RANGE") }
            .mapNotNull { it.key as? String }
        if (rangeProperties.isNotEmpty()) {
            items += SchemaItemInfo(
                kind = SchemaItemKind.RANGE_INDEX,
                label = label,
                properties = rangeProperties,
                name = null, // FalkorDB indexes are unnamed
            )
        }

        types.entries
            .filter { (_, propTypes) -> hasType(propTypes, "VECTOR") }
            .mapNotNull { it.key as? String }
            .forEach { property ->
                val vectorOptions = vectorOptionsFor(options, property)
                items += SchemaItemInfo(
                    kind = SchemaItemKind.VECTOR_INDEX,
                    label = label,
                    properties = listOf(property),
                    name = null,
                    dimensions = (vectorOptions?.get("dimension") as? Number)?.toInt(),
                    similarity = (vectorOptions?.get("similarityFunction") as? String)
                        ?.let { similarityFromName(it) },
                )
            }

        items
    }

    /**
     * FalkorDB 4.x keys the `options` column per property
     * (`{embedding: {dimension: 768, similarityFunction: cosine, …}, title: {}}`); older versions
     * report a flat map. Check per-property first, then fall back to the flat shape.
     */
    private fun vectorOptionsFor(options: Map<*, *>?, property: String): Map<*, *>? {
        val perProperty = options?.get(property) as? Map<*, *>
        if (perProperty != null && perProperty.containsKey("dimension")) {
            return perProperty
        }
        if (options != null && options.containsKey("dimension")) {
            return options
        }
        return perProperty
    }

    private fun hasType(propTypes: Any?, type: String): Boolean = when (propTypes) {
        is List<*> -> propTypes.any { (it as? String)?.equals(type, ignoreCase = true) == true }
        is String -> propTypes.equals(type, ignoreCase = true)
        else -> false
    }

    override fun listConstraintsQuery(): String = """
        CALL db.constraints()
        YIELD type, label, properties, entitytype, status
        RETURN {type: type, label: label, properties: properties, entitytype: entitytype, status: status}
    """.trimIndent()

    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> = rows.mapNotNull { row ->
        val map = row as? Map<*, *> ?: return@mapNotNull null
        if ((map["type"] as? String)?.lowercase() != "unique") return@mapNotNull null
        val entityType = (map["entitytype"] as? String)?.uppercase()
        if (entityType != null && entityType != "NODE") return@mapNotNull null
        val label = map["label"] as? String ?: return@mapNotNull null
        val properties = (map["properties"] as? List<*>)?.filterIsInstance<String>()
            ?: return@mapNotNull null
        if (properties.isEmpty()) return@mapNotNull null
        SchemaItemInfo(
            kind = SchemaItemKind.UNIQUENESS_CONSTRAINT,
            label = label,
            properties = properties,
            name = null,
            status = map["status"] as? String,
        )
    }

    // ----- Matching -----

    /**
     * FalkorDB keeps one index per label covering many properties, so a range spec is satisfied
     * when the label's index covers all the spec's properties (coverage, not equality).
     */
    override fun matchesIdentity(existing: SchemaItemInfo, spec: SchemaItemSpec): Boolean {
        if (existing.kind != spec.kind || existing.label != spec.label) return false
        return when (spec) {
            is RangeIndexSpec -> existing.properties.containsAll(spec.properties)
            else -> existing.properties.toSet() == spec.properties.toSet()
        }
    }

    // ----- Violations -----

    /**
     * FalkorDB constraint creation is asynchronous — violations surface as a `FAILED`
     * introspection status rather than an exception, so exception sniffing only covers
     * the synchronous error path (e.g. constraint already exists / data invalid at submit time).
     */
    override fun isConstraintViolation(e: Throwable): Boolean {
        val messages = SchemaGrammar.messagesOf(e)
        return messages.contains("constraint", ignoreCase = true) &&
            messages.contains("violat", ignoreCase = true)
    }

    // ----- Helpers -----

    private fun similarityName(similarity: SimilarityFunction): String = similarity.name.lowercase()

    private fun similarityFromName(name: String): SimilarityFunction? = when (name.lowercase()) {
        "cosine" -> SimilarityFunction.COSINE
        "euclidean" -> SimilarityFunction.EUCLIDEAN
        else -> null
    }
}