package org.drivine.schema

/**
 * Encapsulates engine-specific schema DDL and introspection.
 *
 * Parallel to [org.drivine.query.grammar.CypherGrammar] (which covers DML divergence), a
 * SchemaGrammar knows how a particular engine:
 *  - expresses index / constraint DDL ([createIndex], [dropIndex], [createConstraint], [dropConstraint])
 *  - lists existing schema items and what shape those results have
 *    ([listIndexesQuery] / [parseIndexRows], [listConstraintsQuery] / [parseConstraintRows])
 *  - diverges in capability (the `supports*` / `constraints*` flags)
 *
 * The ensure / drift / recreate orchestration lives once, in [IndexManager] and
 * [ConstraintManager]; grammars contain no orchestration.
 */
interface SchemaGrammar {

    /** Human-readable engine name, used in log and error messages. */
    val engine: String

    /**
     * Whether DDL supports `IF NOT EXISTS` / `IF EXISTS` guards. When false, managers must check
     * existence before issuing CREATE / DROP.
     */
    val supportsIfNotExists: Boolean

    /**
     * Whether the engine supports user-supplied item names. When false (FalkorDB), names on specs
     * are ignored and [SchemaItemInfo.name] is null.
     */
    val supportsNamedItems: Boolean

    /**
     * Whether uniqueness constraints require an exact-match (range) index on the same
     * label/properties to exist before the constraint can be created (FalkorDB).
     */
    val constraintsRequireBackingIndex: Boolean
        get() = false

    /**
     * Whether constraint creation is asynchronous — the create command returns immediately and
     * the constraint must be polled via introspection until it becomes operational or failed
     * (FalkorDB).
     */
    val constraintCreationIsAsync: Boolean
        get() = false

    // ----- DDL emission -----

    /**
     * Statements that create the index described by [spec].
     *
     * [existing] is a related item already on the database (same kind and label, but not
     * satisfying the spec), or null if there is none. Engines that manage indexes per label
     * (FalkorDB) use it to emit DDL for only the missing properties — creating a property that is
     * already indexed is an error there. Other engines ignore it.
     */
    fun createIndex(spec: IndexSpec, existing: SchemaItemInfo? = null): List<SchemaStatement>

    /**
     * Statements that drop the index described by [item]. Most engines need exactly one
     * statement; engines that manage indexes per property (FalkorDB) may need several.
     */
    fun dropIndex(item: SchemaItemInfo): List<SchemaStatement>

    fun createConstraint(spec: ConstraintSpec): List<SchemaStatement>

    fun dropConstraint(item: SchemaItemInfo): List<SchemaStatement>

    // ----- Introspection -----

    /**
     * Cypher that lists schema items able to surface indexes of [kind].
     * Rows from this query are parsed by [parseIndexRows].
     */
    fun listIndexesQuery(kind: SchemaItemKind): String

    /**
     * Parses raw introspection rows into normalized [SchemaItemInfo]s, skipping rows that don't
     * describe a managed index kind. Row shape depends on the engine's introspection query:
     * a Map for single-map-column results, a positional List for multi-column results. A single
     * row may describe several indexes (FalkorDB returns one row per label).
     */
    fun parseIndexRows(rows: List<Any?>): List<SchemaItemInfo>

    fun listConstraintsQuery(): String

    fun parseConstraintRows(rows: List<Any?>): List<SchemaItemInfo>

    // ----- Matching -----

    /**
     * Whether [existing] is "the same item" the [spec] refers to — i.e. introspection found the
     * thing the spec declares. Identity is kind + label + property coverage. It deliberately does
     * not compare shape (dimensions / similarity); that is [matchesShape]'s job, and the
     * difference between the two is what surfaces as [EnsureResult.Drift].
     */
    fun matchesIdentity(existing: SchemaItemInfo, spec: SchemaItemSpec): Boolean {
        if (existing.kind != spec.kind || existing.label != spec.label) return false
        return existing.properties.toSet() == spec.properties.toSet()
    }

    /**
     * Whether [existing]'s shape matches what [spec] requests. Only meaningful when
     * [matchesIdentity] is true; a shape mismatch is drift.
     *
     * The rule for every shape attribute is the same: **only report drift the engine can actually
     * observe**. A null on [existing] means introspection did not surface that attribute, which is
     * not evidence of a mismatch — reporting drift there would make `ensure` complain forever on
     * engines that simply don't report the field back.
     */
    fun matchesShape(existing: SchemaItemInfo, spec: SchemaItemSpec): Boolean = when (spec) {
        is VectorIndexSpec ->
            existing.dimensions == spec.dimensions &&
                (existing.similarity == null || existing.similarity == spec.similarity)

        // An analyzer is drift only when the spec asks for one AND the engine reports one back.
        // Neo4j is the only engine that does both; FalkorDB and Memgraph report null, so a spec's
        // analyzer is silently unverified there rather than permanently drifting.
        is FullTextIndexSpec ->
            spec.analyzer == null || existing.analyzer == null || existing.analyzer == spec.analyzer

        else -> true
    }

    /**
     * Whether the given exception thrown during constraint creation indicates that existing data
     * violates the constraint (as opposed to a syntax error or connectivity problem).
     */
    fun isConstraintViolation(e: Throwable): Boolean

    companion object {

        /** Flattens an exception chain's messages for engine-specific violation sniffing. */
        fun messagesOf(e: Throwable): String =
            generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    }
}