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
    /**
     * Optional owner name. When a library and its host application each register catalogs, naming
     * them keys versioning and recreation **per owner** — an app's version bump rebuilds only the
     * app's items, not the library's, and each owner gets its own `_DrivineSchema` marker node
     * (`scope: <name>`). Null (the default) is the anonymous/global owner, tracked under the
     * historical `scope: 'schema'` marker so existing deployments are untouched. See [named].
     */
    val name: String? = null,
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
        SchemaCatalog(indexes, constraints, target, version, name)

    /**
     * Returns a copy of this catalog owned by [name].
     *
     * Naming keys versioning and recreation to this owner: its version token is tracked in its own
     * `_DrivineSchema {scope: '<name>'}` marker, a version change recreates only *this* owner's
     * items, and [SchemaManager.enforce]/[SchemaManager.recreateAll] can target it selectively. The
     * anonymous (unnamed) owner keeps the historical `scope: 'schema'` marker, so mixing a named
     * library catalog with an existing unnamed app catalog needs no migration.
     *
     * Like [forDatabase], naming is **replace / last-wins**, not additive: each call sets the owner
     * afresh. Pass `null` to return to the anonymous owner.
     *
     * Items declared identically by more than one live owner (e.g. both an app and a library
     * wanting `Chunk(id)` unique) deduplicate at the DDL level and are **never recreated by a single
     * owner's version bump** — only [SchemaManager.recreateAll] (all or by name) rebuilds a
     * co-owned item. Genuinely conflicting declarations across owners fail fast, naming both owners.
     */
    fun named(name: String?): SchemaCatalog =
        SchemaCatalog(indexes, constraints, target, version, name)

    /** Returns a copy of this catalog applying only to the primary (first-registered) database. */
    fun forDefaultDatabase(): SchemaCatalog = forDatabase(DEFAULT_DATABASE)

    /**
     * Returns a copy of this catalog applying only to the named database.
     *
     * Replaces any existing target — use [forDatabases] to target several at once.
     */
    fun forDatabase(database: String): SchemaCatalog =
        SchemaCatalog(indexes, constraints, DatabaseTarget.Named(setOf(database)), version, name)

    /**
     * Returns a copy of this catalog applying only to the named databases.
     *
     * Replaces any existing target — this is the way to target several databases at once;
     * chaining `forDatabase(...)` calls does not accumulate.
     */
    fun forDatabases(vararg databases: String): SchemaCatalog =
        SchemaCatalog(indexes, constraints, DatabaseTarget.Named(databases.toSet()), version, name)

    /**
     * Returns a copy of this catalog applying to every schema-capable database (the default).
     *
     * Replaces any existing target.
     */
    fun forAllDatabases(): SchemaCatalog = SchemaCatalog(indexes, constraints, DatabaseTarget.All, version, name)

    /** Merges another catalog into this one. Both must share the same [target] and [name]. */
    operator fun plus(other: SchemaCatalog): SchemaCatalog = merge(listOf(this, other))

    override fun toString(): String =
        "SchemaCatalog(name=$name, target=$target, version=$version, " +
            "indexes=${indexes.size}, constraints=${constraints.size})"

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
         * Merges catalogs that share a [target] and [name]: identical declarations deduplicate;
         * conflicting declarations for the same (kind, label, properties) fail fast. Merging
         * catalogs with different targets or different owners is an error — group them first, or let
         * [SchemaManager] resolve per-database and per-owner. Version tokens are combined (distinct,
         * sorted, joined) so a merged catalog keeps the effective version of its parts.
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
            val names = catalogs.map { it.name }.distinct()
            if (names.size > 1) {
                throw DrivineException(
                    "Cannot merge schema catalogs with different owners: $names. " +
                        "Group catalogs by owner (name) before merging."
                )
            }
            return build(catalogs.flatMap { it.items }, targets.first(), combineVersions(catalogs), names.first())
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
            name: String? = null,
        ): SchemaCatalog {
            val deduplicated = deduplicate(specs)
            return SchemaCatalog(
                indexes = deduplicated.filterIsInstance<IndexSpec>(),
                constraints = deduplicated.filterIsInstance<ConstraintSpec>(),
                target = target,
                version = version,
                name = name,
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