package org.drivine.schema

import org.drivine.DrivineException
import kotlin.reflect.KClass

/**
 * A declarative collection of schema items (indexes and constraints).
 *
 * Consumers register a catalog as a Spring bean; on startup Drivine's schema initializer ensures
 * every entry, ordering indexes before constraints. Identical declarations deduplicate; conflicting
 * declarations for the same (kind, label, properties) fail fast.
 *
 * By default a catalog applies to **every schema-capable registered database** ([DatabaseTarget.All]).
 * Narrow it with [forDefaultDatabase], [forDatabase], or [forDatabases]:
 *
 * ```kotlin
 * @Bean
 * fun propositionSchema(embeddingService: EmbeddingService) = SchemaCatalog.of(
 *     VectorIndexSpec("Proposition", "embedding", embeddingService.dimensions),
 *     RangeIndexSpec("Proposition", "contextId"),
 *     UniquenessConstraintSpec("Proposition", "id"),
 * )                                   // all databases
 *
 * @Bean
 * fun usersSchema() = SchemaCatalog.of(
 *     UniquenessConstraintSpec("User", "email"),
 * ).forDatabase("users")             // only the "users" datasource
 * ```
 *
 * Targeting is **replace / last-wins**, not additive: each `forDatabase` / `forDatabases` /
 * `forDefaultDatabase` / `forAllDatabases` call sets the target afresh, ignoring any previous one.
 * To apply to several databases, name them all in a single [forDatabases] call — chaining
 * `forDatabase("a").forDatabase("b")` yields `{b}`, not `{a, b}`.
 *
 * Or scanned from annotated [org.drivine.annotation.NodeFragment] classes:
 *
 * ```kotlin
 * @Bean
 * fun propositionSchema(embeddingService: EmbeddingService) = SchemaCatalog.fromFragments(
 *     VectorDimensionProvider { _, _ -> embeddingService.dimensions },
 *     PropositionNode::class,
 *     MentionNode::class,
 * )
 * ```
 */
class SchemaCatalog private constructor(
    val indexes: List<IndexSpec>,
    val constraints: List<ConstraintSpec>,
    /** The database(s) this catalog applies to. */
    val target: DatabaseTarget,
    /**
     * Optional opt-in version token. When set, a *change* in the token (vs. the last value applied
     * to a database) triggers a one-time drop-and-recreate of this catalog's items on that database
     * at enforcement time. Use it to force a rebuild on changes structural drift can't see — e.g. an
     * embedding-model swap that keeps the same dimensions. See [withVersion].
     */
    val version: String? = null,
) {

    /** All items in ensure order: indexes first, then constraints. */
    val items: List<SchemaItemSpec>
        get() = indexes + constraints

    fun isEmpty(): Boolean = indexes.isEmpty() && constraints.isEmpty()

    /**
     * Returns a copy of this catalog tagged with [version].
     *
     * The token is compared against the last value applied to each target database (stored in a
     * `_DrivineSchema` marker node). When it changes, this catalog's items are dropped and recreated
     * once on that database, then the new token is recorded. A first-ever value is *adopted* without
     * recreating (existing matching items are kept). Typically the embedding model id, an analyzer
     * version, or a manual bump.
     *
     * Note: recreating a vector index rebuilds it from the stored embedding *properties* — it does
     * not re-embed. After a model change, re-embed the nodes first, then enforce.
     */
    fun withVersion(version: String): SchemaCatalog =
        SchemaCatalog(indexes, constraints, target, version)

    /** Returns a copy of this catalog applying only to the primary (first-registered) database. */
    fun forDefaultDatabase(): SchemaCatalog = forDatabase(DEFAULT_DATABASE)

    /**
     * Returns a copy of this catalog applying only to the named database.
     *
     * Replaces any existing target — use [forDatabases] to target several at once.
     */
    fun forDatabase(database: String): SchemaCatalog =
        SchemaCatalog(indexes, constraints, DatabaseTarget.Named(setOf(database)), version)

    /**
     * Returns a copy of this catalog applying only to the named databases.
     *
     * Replaces any existing target — this is the way to target several databases at once;
     * chaining `forDatabase(...)` calls does not accumulate.
     */
    fun forDatabases(vararg databases: String): SchemaCatalog =
        SchemaCatalog(indexes, constraints, DatabaseTarget.Named(databases.toSet()), version)

    /**
     * Returns a copy of this catalog applying to every schema-capable database (the default).
     *
     * Replaces any existing target.
     */
    fun forAllDatabases(): SchemaCatalog = SchemaCatalog(indexes, constraints, DatabaseTarget.All, version)

    /** Merges another catalog into this one. Both must share the same [target]. */
    operator fun plus(other: SchemaCatalog): SchemaCatalog = merge(listOf(this, other))

    override fun toString(): String =
        "SchemaCatalog(target=$target, version=$version, indexes=${indexes.size}, constraints=${constraints.size})"

    companion object {

        /**
         * Alias resolving to the primary (first-registered) datasource, consistent with
         * `PersistenceManagerFactory.get("default")` and `DatabaseRegistry.connectionProvider("default")`.
         */
        const val DEFAULT_DATABASE = "default"

        /** Builds a catalog from explicit specs, applying to all databases. */
        @JvmStatic
        fun of(vararg specs: SchemaItemSpec): SchemaCatalog = build(specs.toList(), DatabaseTarget.All)

        /** Builds a catalog from explicit specs, applying to all databases. */
        @JvmStatic
        fun of(specs: List<SchemaItemSpec>): SchemaCatalog = build(specs, DatabaseTarget.All)

        // ----- Annotation scanning -----

        /**
         * Builds a catalog by reading schema annotations off [org.drivine.annotation.NodeFragment]
         * classes. Use the [VectorDimensionProvider] overloads when any fragment declares a
         * `@VectorIndex`.
         */
        @JvmStatic
        fun fromFragments(vararg fragmentClasses: Class<*>): SchemaCatalog =
            fromFragments(null, *fragmentClasses)

        @JvmStatic
        fun fromFragments(
            dimensionProvider: VectorDimensionProvider?,
            vararg fragmentClasses: Class<*>,
        ): SchemaCatalog = build(
            fragmentClasses.flatMap { FragmentSchemaScanner.scan(it, dimensionProvider) },
            DatabaseTarget.All,
        )

        /** Kotlin convenience for [fromFragments]. */
        fun fromFragments(vararg fragmentClasses: KClass<*>): SchemaCatalog =
            fromFragments(null, *fragmentClasses.map { it.java }.toTypedArray())

        /** Kotlin convenience for [fromFragments]. */
        fun fromFragments(
            dimensionProvider: VectorDimensionProvider?,
            vararg fragmentClasses: KClass<*>,
        ): SchemaCatalog =
            fromFragments(dimensionProvider, *fragmentClasses.map { it.java }.toTypedArray())

        // ----- Merging -----

        /**
         * Merges catalogs that share a [target]: identical declarations deduplicate; conflicting
         * declarations for the same (kind, label, properties) fail fast. Merging catalogs with
         * different targets is an error — group them by target first, or let [SchemaManager]
         * resolve per-database. Version tokens are combined (distinct, sorted, joined) so a merged
         * catalog keeps the effective version of its parts.
         */
        @JvmStatic
        fun merge(catalogs: List<SchemaCatalog>): SchemaCatalog {
            if (catalogs.isEmpty()) {
                return SchemaCatalog(emptyList(), emptyList(), DatabaseTarget.All)
            }
            val targets = catalogs.map { it.target }.distinct()
            if (targets.size > 1) {
                throw DrivineException(
                    "Cannot merge schema catalogs with different database targets: $targets. " +
                        "Group catalogs by target before merging."
                )
            }
            return build(catalogs.flatMap { it.items }, targets.first(), combineVersions(catalogs))
        }

        /** The combined version token of several catalogs, or null if none set one. */
        internal fun combineVersions(catalogs: List<SchemaCatalog>): String? {
            val versions = catalogs.mapNotNull { it.version }.distinct().sorted()
            return if (versions.isEmpty()) null else versions.joinToString("|")
        }

        private fun build(
            specs: List<SchemaItemSpec>,
            target: DatabaseTarget,
            version: String? = null,
        ): SchemaCatalog {
            val deduplicated = deduplicate(specs)
            return SchemaCatalog(
                indexes = deduplicated.filterIsInstance<IndexSpec>(),
                constraints = deduplicated.filterIsInstance<ConstraintSpec>(),
                target = target,
                version = version,
            )
        }

        /**
         * Collapses duplicate declarations. The key is (kind, label, property set) — two
         * declarations with the same key must be identical specs, otherwise they conflict
         * (e.g. two fragments declaring different vector dimensions for the same property).
         */
        private fun deduplicate(specs: List<SchemaItemSpec>): List<SchemaItemSpec> {
            val byKey = specs.groupBy { Triple(it.kind, it.label, it.properties.toSet()) }
            return byKey.map { (key, group) ->
                val distinct = group.distinct()
                if (distinct.size > 1) {
                    throw DrivineException(
                        "Conflicting schema declarations for ${key.first} on ${key.second}${key.third.toList()}: " +
                            distinct.joinToString("; ")
                    )
                }
                distinct.first()
            }
        }
    }
}