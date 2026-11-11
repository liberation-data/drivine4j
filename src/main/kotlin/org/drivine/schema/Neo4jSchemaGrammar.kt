package org.drivine.schema

import org.drivine.cypher.store.ddl.Neo4jDdl
import org.neo4j.driver.exceptions.Neo4jException

/**
 * Schema DDL and introspection for Neo4j (4.x and 5.x — schema syntax is shared across both,
 * unlike DML existence syntax which is why CypherGrammar splits them).
 *
 * - DDL supports `IF NOT EXISTS` / `IF EXISTS` guards.
 * - Indexes and constraints are named; drops are by name.
 * - Introspection via `SHOW INDEXES` / `SHOW CONSTRAINTS` with `YIELD … RETURN {…}`.
 * - A TEXT index ([TextIndexSpec]) is its own kind, distinct from a FULLTEXT one.
 * - Vector index config uses backtick-quoted dotted keys (`vector.dimensions`,
 *   `vector.similarity_function`).
 *
 * The statements are the module's ([Neo4jDdl]), which the three Drivine libraries share; reading what
 * introspection gives, waiting on an index's build and telling a violation from another failure are this
 * grammar's.
 *
 * @param elementIdFunction the function that gives a node's own id: `elementId` from Neo4j 5, `id`
 *   on Neo4j 4, which has no `elementId`
 */
class Neo4jSchemaGrammar(internal val elementIdFunction: String = "elementId") : SchemaGrammar {

    override val engine = "Neo4j"
    override val supportsIfNotExists = true
    override val supportsNamedItems = true
    override val supportsTextIndex = true

    /**
     * `db.awaitIndex`, on Neo4j 4.x to 2026: an index is made POPULATING and built in the background, and a write
     * of a value too large for it that commits just as it comes online can stop the database (Neo4j skips the
     * key-size check while it populates). It throws, with Neo4j's reason, for an index whose build failed.
     */
    override val awaitIndexStatement = "CALL db.awaitIndex(\$name, \$timeout)"

    /** `db.awaitIndex` gives `ProcedureTimedOut` for an index still populating when the wait ran out, and another code for one that failed. */
    override fun isBuildTimeout(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.filterIsInstance<Neo4jException>().any { it.code() == PROCEDURE_TIMED_OUT }

    override val indexStateStatement = "SHOW INDEXES YIELD name, state WHERE name = \$name RETURN {state: state}"

    /** Neo4j expresses the portable HNSW parameters and its own options class, so nothing is dropped. */
    override fun unsupportedVectorTuning(spec: VectorIndexSpec): List<String> = emptyList()

    override fun matchesEngineVectorOptions(existing: SchemaItemInfo, spec: VectorIndexSpec): Boolean =
        pinnedQuantizationMatches(neo4jOptions(spec)?.quantizationEnabled, existing.quantizationEnabled)

    private fun neo4jOptions(spec: VectorIndexSpec): Neo4jVectorOptions? =
        spec.optionsFor(Neo4jVectorOptions.ENGINE) as? Neo4jVectorOptions

    /** Same permissive rule as the portable parameters: unpinned or unreported is never drift. */
    private fun pinnedQuantizationMatches(pinned: Boolean?, observed: Boolean?): Boolean =
        pinned == null || observed == null || pinned == observed

    // ----- DDL emission: the module's (Neo4jDdl) -----

    override fun createIndex(spec: IndexSpec, existing: SchemaItemInfo?): List<SchemaStatement> = statementsOf { Neo4jDdl.createIndex(spec, existing?.catalogItem()) }

    /** By name: refused where no name is known for [item]. */
    override fun dropIndex(item: SchemaItemInfo): List<SchemaStatement> = statementsOf { Neo4jDdl.dropIndex(item.catalogItem()) }

    override fun createConstraint(spec: ConstraintSpec): List<SchemaStatement> = statementsOf { Neo4jDdl.createConstraint(spec) }

    /** By name: refused where no name is known for [item]. */
    override fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement> = statementsOf { Neo4jDdl.dropConstraint(item.catalogItem()) }

    // ----- Introspection -----

    override fun listIndexesQuery(kind: SchemaItemKind): String = Neo4jDdl.listIndexesQuery(kind)

    override fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo> =
        rows.mapNotNull { parseIndexRow(it) }

    private fun parseIndexRow(row: Any?): SchemaItemInfo? {
        val map = row as? Map<*, *> ?: return null
        if ((map["entityType"] as? String)?.uppercase() == "RELATIONSHIP") return null
        val kind = when ((map["type"] as? String)?.uppercase()) {
            "VECTOR" -> SchemaItemKind.VECTOR_INDEX
            "RANGE", "BTREE" -> SchemaItemKind.RANGE_INDEX
            "FULLTEXT" -> SchemaItemKind.FULLTEXT_INDEX
            "TEXT" -> SchemaItemKind.TEXT_INDEX
            else -> return null // LOOKUP, POINT — not managed by Drivine
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
                // Read back whatever the server actually chose, pinned or not — this is what makes the
                // effective config loggable and drift against a pinned value detectable.
                quantizationEnabled = quantizationEnabled(indexConfig),
                hnswM = (indexConfig?.get("vector.hnsw.m") as? Number)?.toInt(),
                hnswEfConstruction = (indexConfig?.get("vector.hnsw.ef_construction") as? Number)?.toInt(),
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

    /**
     * Whether a vector index quantizes, as its `indexConfig` reports it. Neo4j up to 2026.05 reports
     * `vector.quantization.enabled`. From 2026.06 it reports `vector.quantization.type` in its place:
     * `NONE`, or the kind of quantization, `SCALAR` or `BINARY`. Null when it reports neither.
     */
    private fun quantizationEnabled(indexConfig: Map<*, *>?): Boolean? {
        val type = indexConfig?.get("vector.quantization.type") as? String
        if (type != null) return !type.equals("NONE", ignoreCase = true)
        return indexConfig?.get("vector.quantization.enabled") as? Boolean
    }

    override fun listConstraintsQuery(): String = Neo4jDdl.listConstraintsQuery()

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

    /**
     * By the driver's codes. A write the constraint refuses (`ConstraintValidationFailed`, which
     * [org.drivine.store.StoreIdentityResolver] retries on) is always one. A constraint the server
     * would not make is `ConstraintCreationFailed` on every version, for a violation and for other
     * refusals alike, so its cause decides:
     *
     * - Neo4j 2026 names a violation in the cause, `22N79` and `22N80`, and another refusal by its
     *   own status, as `51N27` for a key constraint on Community Edition: not a violation.
     * - Neo4j 5.26 gives the refusal `50N11` (constraint creation failed) and a violation beneath it
     *   only `50N42` (unexpected error), the status the driver also gives an error a server sends
     *   none for. An older server gives no status at all.
     *
     * So a refusal whose chain carries no status but these general ones is suspected, and
     * [ConstraintManager] reads the data to confirm it, as it does for a server older than GQL statuses.
     */
    override fun isConstraintViolation(e: Throwable): Boolean {
        val errors = generateSequence(e) { it.cause }.filterIsInstance<Neo4jException>().toList()
        if (errors.any { it.code() == VALIDATION_FAILED || it.gqlStatus() in VIOLATED }) return true
        return errors.all { it.gqlStatus() in GENERAL_STATUSES } && errors.any { it.code() == CREATION_FAILED }
    }

    /**
     * "There already exists an index (:Label {prop}). A constraint cannot be created until the index
     * has been dropped." By its code, which Neo4j 4.4 to 2026 all give.
     */
    override fun isBlockedByIndex(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.filterIsInstance<Neo4jException>().any { it.code() == INDEX_ALREADY_EXISTS }

    override fun elementId(variable: String): String = "$elementIdFunction($variable)"

    // ----- Helpers -----

    private fun similarityFromName(name: String): SimilarityFunction? = when (name.lowercase()) {
        "cosine" -> SimilarityFunction.COSINE
        "euclidean" -> SimilarityFunction.EUCLIDEAN
        else -> null
    }

    private companion object {
        /** A uniqueness constraint violated, and the index entry conflict beneath it. */
        val VIOLATED = setOf("22N79", "22N80")

        /**
         * Statuses that name no cause: a constraint not made (`50N11`), and an unexpected error
         * (`50N42`), which the driver also gives an error from a server that sends no status.
         */
        val GENERAL_STATUSES = setOf("50N11", "50N42")

        /** A write a constraint refuses. */
        const val VALIDATION_FAILED = "Neo.ClientError.Schema.ConstraintValidationFailed"

        /** A constraint not made, for whatever reason. */
        const val CREATION_FAILED = "Neo.DatabaseError.Schema.ConstraintCreationFailed"

        /** An index over the schema a constraint would make its own index over. */
        const val INDEX_ALREADY_EXISTS = "Neo.ClientError.Schema.IndexAlreadyExists"

        /** A procedure whose wait ran out: `db.awaitIndex`'s, for an index still populating. */
        const val PROCEDURE_TIMED_OUT = "Neo.ClientError.Procedure.ProcedureTimedOut"
    }
}