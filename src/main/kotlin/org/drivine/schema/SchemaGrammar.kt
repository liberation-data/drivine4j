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
        is VectorIndexSpec -> {
            // Resolved for THIS engine, so a Neo4j-specific override is compared on Neo4j and ignored
            // on FalkorDB, where it was never going to apply.
            val tuning = spec.tuningFor(engine)
            existing.dimensions == spec.dimensions &&
                (existing.similarity == null || existing.similarity == spec.similarity) &&
                // Physical parameters follow the analyzer rule: drift only when the spec PINS one and the
                // engine REPORTS one and they differ. An unpinned parameter is the engine's to choose, so
                // it can never drift — otherwise every index would drift the moment a server changed its
                // defaults, which is the opposite of what pinning is for.
                pinnedMatches(tuning.hnswM, existing.hnswM) &&
                pinnedMatches(tuning.hnswEfConstruction, existing.hnswEfConstruction) &&
                matchesEngineVectorOptions(existing, spec)
        }

        // An analyzer is drift only when the spec asks for one AND the engine reports one back.
        // Neo4j is the only engine that does both; FalkorDB and Memgraph report null, so a spec's
        // analyzer is silently unverified there rather than permanently drifting.
        is FullTextIndexSpec ->
            spec.analyzer == null || existing.analyzer == null || existing.analyzer == spec.analyzer

        else -> true
    }

    /**
     * Whether the engine-specific options in [spec] are satisfied by [existing].
     *
     * Only the grammar for a given engine understands its own options class, so the shared layer defers
     * here. The default is permissive: an engine that cannot express its options has nothing to compare,
     * and reports the gap through [unsupportedVectorTuning] instead of drifting forever.
     */
    fun matchesEngineVectorOptions(existing: SchemaItemInfo, spec: VectorIndexSpec): Boolean = true

    /**
     * What [spec] pins that this engine cannot express, named in the spec's own vocabulary.
     *
     * Empty when nothing is pinned, or when the engine honours everything pinned. A non-empty result
     * means the created index will NOT have the requested physical shape — the caller is expected to say
     * so out loud rather than let the declaration look like it took effect.
     *
     * The default assumes an engine expresses nothing, so a grammar that supports tuning must override.
     * Options addressed to *other* engines are never reported: declaring Neo4j options does not make a
     * FalkorDB deployment incorrect, it just doesn't apply there.
     */
    fun unsupportedVectorTuning(spec: VectorIndexSpec): List<String> {
        val tuning = spec.tuningFor(engine)
        return listOfNotNull(
            tuning.hnswM?.let { "hnswM" },
            tuning.hnswEfConstruction?.let { "hnswEfConstruction" },
        ) + (spec.optionsFor(engine)?.let { listOf("${it::class.simpleName}") } ?: emptyList())
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

/**
 * Whether an [observed] value contradicts a [pinned] one.
 *
 * Both nulls are permissive, for different reasons: a null [pinned] means the declaration left the choice
 * to the engine, and a null [observed] means the engine did not report the parameter back. Only two
 * present-and-different values are drift.
 */
private fun <T> pinnedMatches(pinned: T?, observed: T?): Boolean =
    pinned == null || observed == null || pinned == observed