package org.drivine.schema

import org.drivine.DrivineException

/**
 * Schema DDL and introspection for Neo4j (4.x and 5.x — schema syntax is shared across both,
 * unlike DML existence syntax which is why CypherGrammar splits them).
 *
 * - DDL supports `IF NOT EXISTS` / `IF EXISTS` guards.
 * - Indexes and constraints are named; drops are by name.
 * - Introspection via `SHOW INDEXES` / `SHOW CONSTRAINTS` with `YIELD … RETURN {…}`.
 * - Vector index config uses backtick-quoted dotted keys (`vector.dimensions`,
 *   `vector.similarity_function`).
 */
class Neo4jSchemaGrammar : SchemaGrammar {

    override val engine = "Neo4j"
    override val supportsIfNotExists = true
    override val supportsNamedItems = true

    // ----- DDL emission -----

    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> = when (spec) {
        is VectorIndexSpec -> listOf(
            SchemaStatement.Cypher(
                """
                CREATE VECTOR INDEX `${spec.effectiveName}` IF NOT EXISTS
                FOR (n:`${spec.label}`) ON (n.`${spec.property}`)
                OPTIONS { indexConfig: {
                  `vector.dimensions`: ${spec.dimensions},
                  `vector.similarity_function`: '${similarityName(spec.similarity)}'
                }}
                """.trimIndent()
            )
        )

        is RangeIndexSpec -> listOf(
            SchemaStatement.Cypher(
                "CREATE INDEX `${spec.effectiveName}` IF NOT EXISTS " +
                    "FOR (n:`${spec.label}`) ON ${propertyList(spec.properties)}"
            )
        )

        is FullTextIndexSpec -> listOf(
            SchemaStatement.Cypher(
                "CREATE FULLTEXT INDEX `${spec.effectiveName}` IF NOT EXISTS " +
                    "FOR (n:`${spec.label}`) ON EACH ${eachPropertyList(spec.properties)} " +
                    "OPTIONS {indexConfig: {${analyzerConfig(spec.analyzer)}}}"
            )
        )
    }

    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> {
        val name = item.name ?: throw DrivineException(
            "Cannot drop index on ${item.label}${item.properties}: Neo4j drops indexes by name, " +
                "but no name is known for this index"
        )
        return listOf(SchemaStatement.Cypher("DROP INDEX `$name` IF EXISTS"))
    }

    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = when (spec) {
        is UniquenessConstraintSpec -> listOf(
            SchemaStatement.Cypher(
                "CREATE CONSTRAINT `${spec.effectiveName}` IF NOT EXISTS " +
                    "FOR (n:`${spec.label}`) REQUIRE ${propertyList(spec.properties)} IS UNIQUE"
            )
        )
    }

    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> {
        val name = item.name ?: throw DrivineException(
            "Cannot drop constraint on ${item.label}${item.properties}: Neo4j drops constraints by name, " +
                "but no name is known for this constraint"
        )
        return listOf(SchemaStatement.Cypher("DROP CONSTRAINT `$name` IF EXISTS"))
    }

    // ----- Introspection -----

    override fun listIndexesQuery(kind: SchemaItemKind): String = """
        SHOW INDEXES
        YIELD name, type, entityType, labelsOrTypes, properties, options
        RETURN {name: name, type: type, entityType: entityType, labelsOrTypes: labelsOrTypes, properties: properties, options: options}
    """.trimIndent()

    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> =
        rows.mapNotNull { parseIndexRow(it) }

    private fun parseIndexRow(row: Any?): SchemaItemInfo? {
        val map = row as? Map<*, *> ?: return null
        if ((map["entityType"] as? String)?.uppercase() == "RELATIONSHIP") return null
        val kind = when ((map["type"] as? String)?.uppercase()) {
            "VECTOR" -> SchemaItemKind.VECTOR_INDEX
            "RANGE", "BTREE" -> SchemaItemKind.RANGE_INDEX
            "FULLTEXT" -> SchemaItemKind.FULLTEXT_INDEX
            else -> return null // LOOKUP, TEXT, POINT — not managed by Drivine
        }
        val label = (map["labelsOrTypes"] as? List<*>)?.firstOrNull() as? String ?: return null
        val properties = (map["properties"] as? List<*>)?.filterIsInstance<String>() ?: return null
        val name = map["name"] as? String

        val indexConfig = ((map["options"] as? Map<*, *>)?.get("indexConfig")) as? Map<*, *>

        if (kind == SchemaItemKind.VECTOR_INDEX) {
            return SchemaItemInfo(
                kind = kind,
                label = label,
                properties = properties,
                name = name,
                dimensions = (indexConfig?.get("vector.dimensions") as? Number)?.toInt(),
                similarity = (indexConfig?.get("vector.similarity_function") as? String)
                    ?.let { similarityFromName(it) },
            )
        }

        if (kind == SchemaItemKind.FULLTEXT_INDEX) {
            return SchemaItemInfo(
                kind = kind,
                label = label,
                properties = properties,
                name = name,
                analyzer = indexConfig?.get("fulltext.analyzer") as? String,
            )
        }

        return SchemaItemInfo(kind = kind, label = label, properties = properties, name = name)
    }

    override fun listConstraintsQuery(): String = """
        SHOW CONSTRAINTS
        YIELD name, type, entityType, labelsOrTypes, properties
        RETURN {name: name, type: type, entityType: entityType, labelsOrTypes: labelsOrTypes, properties: properties}
    """.trimIndent()

    override fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo> =
        rows.mapNotNull { parseConstraintRow(it) }

    private fun parseConstraintRow(row: Any?): SchemaItemInfo? {
        val map = row as? Map<*, *> ?: return null
        if ((map["entityType"] as? String)?.uppercase() == "RELATIONSHIP") return null
        // Neo4j 5 reports "UNIQUENESS"; some versions report "NODE_PROPERTY_UNIQUENESS"
        val type = (map["type"] as? String)?.uppercase() ?: return null
        if (!type.contains("UNIQUENESS")) return null

        val label = (map["labelsOrTypes"] as? List<*>)?.firstOrNull() as? String ?: return null
        val properties = (map["properties"] as? List<*>)?.filterIsInstance<String>() ?: return null
        return SchemaItemInfo(
            kind = SchemaItemKind.UNIQUENESS_CONSTRAINT,
            label = label,
            properties = properties,
            name = map["name"] as? String,
        )
    }

    // ----- Violations -----

    override fun isConstraintViolation(e: Throwable): Boolean {
        val messages = SchemaGrammar.messagesOf(e)
        return messages.contains("ConstraintCreationFailed", ignoreCase = true) ||
            messages.contains("ConstraintValidationFailed", ignoreCase = true) ||
            // "Unable to create Constraint … both nodes … have the same value …"
            (messages.contains("unable to create constraint", ignoreCase = true)) ||
            (messages.contains("constraint", ignoreCase = true) &&
                messages.contains("same value", ignoreCase = true))
    }

    // ----- Helpers -----

    private fun propertyList(properties: List<String>): String =
        properties.joinToString(", ", "(", ")") { "n.`$it`" }

    /** Fulltext's `ON EACH` takes a bracketed list, not the parenthesized one the other kinds use. */
    private fun eachPropertyList(properties: List<String>): String =
        properties.joinToString(", ", "[", "]") { "n.`$it`" }

    /**
     * The `indexConfig` body for a fulltext index. Empty when no analyzer is declared, so Neo4j
     * applies its own default (`standard-no-stop-words`) rather than us hard-coding one that could
     * drift from the engine's default across versions.
     */
    private fun analyzerConfig(analyzer: String?): String =
        analyzer?.let { "`fulltext.analyzer`: '$it'" } ?: ""

    private fun similarityName(similarity: SimilarityFunction): String = similarity.name.lowercase()

    private fun similarityFromName(name: String): SimilarityFunction? = when (name.lowercase()) {
        "cosine" -> SimilarityFunction.COSINE
        "euclidean" -> SimilarityFunction.EUCLIDEAN
        else -> null
    }
}