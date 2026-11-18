package org.drivine.schema

import org.drivine.DrivineException
import org.drivine.cypher.store.marker.SchemaMarker
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
    /**
     * The range indexes among [indexes] that fragments' `@NodeId`s are given by default ([fromFragments], #827): each one
     * no item declared serves. [withoutNodeIdIndexes] leaves them out, as `drivine.schema.index-node-ids=false` does.
     */
    val nodeIdIndexes: Set<IndexSpec> = emptySet(),
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
        SchemaCatalog(indexes, constraints, target, version, name, nodeIdIndexes)

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
        SchemaCatalog(indexes, constraints, target, version, name, nodeIdIndexes)

    /** Returns a copy of this catalog applying only to the primary (first-registered) database. */
    fun forDefaultDatabase(): SchemaCatalog = forDatabase(DEFAULT_DATABASE)

    /**
     * Returns a copy of this catalog applying only to the named database.
     *
     * Replaces any existing target — use [forDatabases] to target several at once.
     */
    fun forDatabase(database: String): SchemaCatalog =
        SchemaCatalog(indexes, constraints, DatabaseTarget.Named(setOf(database)), version, name, nodeIdIndexes)

    /**
     * Returns a copy of this catalog applying only to the named databases.
     *
     * Replaces any existing target — this is the way to target several databases at once;
     * chaining `forDatabase(...)` calls does not accumulate.
     */
    fun forDatabases(vararg databases: String): SchemaCatalog =
        SchemaCatalog(indexes, constraints, DatabaseTarget.Named(databases.toSet()), version, name, nodeIdIndexes)

    /**
     * Returns a copy of this catalog applying to every schema-capable database (the default).
     *
     * Replaces any existing target.
     */
    fun forAllDatabases(): SchemaCatalog = SchemaCatalog(indexes, constraints, DatabaseTarget.All, version, name, nodeIdIndexes)

    /** A copy of this catalog applying to [target]. */
    internal fun withTarget(target: DatabaseTarget): SchemaCatalog = SchemaCatalog(indexes, constraints, target, version, name, nodeIdIndexes)

    /** Merges another catalog into this one. Both must share the same [target] and [name]. */
    operator fun plus(other: SchemaCatalog): SchemaCatalog = merge(listOf(this, other))

    /**
     * A copy of this catalog without the items [others] declare under the same kind, label and properties, which are
     * theirs to make: as the Spring starter leaves to an application's own catalogs what its scan of `@NodeFragment`
     * classes also finds, so that an item the application names or tunes is made as it declares it. A default
     * of [nodeIdIndexes] kept is kept as one. A default of theirs takes only a default of this one: an item this catalog
     * declares is left out only for one they declare, so that `index-node-ids=false`, leaving out their default, doesn't
     * leave it out too.
     */
    fun except(others: List<SchemaCatalog>): SchemaCatalog {
        val taken = others.flatMap { it.items }.map(::keyOf).toSet()
        val declaredTaken = others.flatMap { it.declaredItems }.map(::keyOf).toSet()
        val kept = items.filter { keyOf(it) !in (if (it in nodeIdIndexes) taken else declaredTaken) }
        val keptIndexes = kept.filterIsInstance<IndexSpec>()
        return SchemaCatalog(keptIndexes, kept.filterIsInstance<ConstraintSpec>(), target, version, name, nodeIdIndexes.filter { it in keptIndexes }.toSet())
    }

    /**
     * A copy of this catalog without the range indexes fragments' `@NodeId`s are given by default ([nodeIdIndexes]),
     * leaving only what is declared: as `drivine.schema.index-node-ids=false` gives the schema manager its catalogs.
     */
    fun withoutNodeIdIndexes(): SchemaCatalog =
        SchemaCatalog(indexes.filter { it !in nodeIdIndexes }, constraints, target, version, name)

    /** [items] without the defaults of [nodeIdIndexes]: what is declared. */
    internal val declaredItems: List<SchemaItemSpec>
        get() = items.filter { it !in nodeIdIndexes }

    /**
     * [items] as they are applied beside [declared], what the catalogs on a database declare: without a default of
     * [nodeIdIndexes] one of them serves, as [build] leaves out one this catalog's own items serve. Applied beside it, a
     * named range index over the id, or one of another owner, would be refused as a conflicting declaration, and a
     * uniqueness constraint over it blocked by it on Neo4j. One declared the same, unnamed, is the same item: the default
     * is kept beside it, co-owned, so that this owner's marker doesn't call the index both share an orphan.
     */
    internal fun itemsBeside(declared: List<SchemaItemSpec>): List<SchemaItemSpec> =
        if (nodeIdIndexes.isEmpty()) items
        else items.filter { item -> item !in nodeIdIndexes || declared.none { it != item && servesNodeId(it, item as IndexSpec) } }

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
            nodeIdIndexes = fragmentClasses.mapNotNull { FragmentSchemaScanner.nodeIdIndex(it) },
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
            return build(
                catalogs.flatMap { catalog -> catalog.items.filter { it !in catalog.nodeIdIndexes } },
                targets.first(),
                combineVersions(catalogs),
                names.first(),
                catalogs.flatMap { it.nodeIdIndexes },
            )
        }

        /**
         * The combined version token of several catalogs, or null if none set one: the module's
         * ([SchemaMarker.versionToken]), so the token a marker holds is the one every Drivine library computes.
         */
        internal fun combineVersions(catalogs: List<SchemaCatalog>): String? = SchemaMarker.versionToken(catalogs.map { it.version })

        private fun build(
            specs: List<SchemaItemSpec>,
            target: DatabaseTarget,
            version: String? = null,
            name: String? = null,
            nodeIdIndexes: List<IndexSpec> = emptyList(),
        ): SchemaCatalog {
            val deduplicated = deduplicate(specs)
            // A default no declared item serves, once each: one declared, under a name or not, is the one made.
            val defaults = nodeIdIndexes.distinct().filter { default -> deduplicated.none { servesNodeId(it, default) } }
            return SchemaCatalog(
                indexes = deduplicated.filterIsInstance<IndexSpec>() + defaults,
                constraints = deduplicated.filterIsInstance<ConstraintSpec>(),
                target = target,
                version = version,
                name = name,
                nodeIdIndexes = defaults.toSet(),
            )
        }

        /** What two declarations of one item share: its kind, label and properties, as made, in any order. */
        private fun keyOf(spec: SchemaItemSpec) = Triple(spec.kind, spec.label, spec.made().properties.toSet())

        /**
         * Whether [declared] serves [default], a `@NodeId`'s range index over one property: a range index or a uniqueness
         * constraint over that property alone, under the label, that doesn't ignore case, whose index a lookup by the id
         * reads (a uniqueness constraint's on every store, drivine-cypher-spec#325).
         */
        private fun servesNodeId(declared: SchemaItemSpec, default: IndexSpec): Boolean =
            declared.label == default.label && declared.properties == default.properties && when (declared) {
                is RangeIndexSpec -> !declared.ignoreCase
                is UniquenessConstraintSpec -> !declared.ignoreCase
                else -> false
            }

        /**
         * Collapses duplicate declarations. The key is (kind, label, property set).
         *
         * Declarations for one key must agree on everything portable — dimensions, similarity, name,
         * common HNSW parameters — and disagreement there is a conflict, as it always has been
         * (e.g. two fragments declaring different vector dimensions for the same property).
         *
         * The one permitted difference is **engine-specific options**. A vector index that is both
         * queried and tuned is necessarily declared twice: `@VectorIndex` must stay on the fragment
         * because [org.drivine.query.VectorIndexResolver] resolves `loadNearest`'s target index from
         * the annotation, while engine options can only be expressed in a hand-written catalog spec.
         * The two are not rivals, so the spec carrying engine options wins whole — no field-by-field
         * merging. Two specs that *both* pin engine options and disagree are a genuine conflict.
         *
         * A constraint's [ConstraintSpec.nodeIdProperty] may differ too, being no part of the item: a
         * constraint read from a fragment and the same one declared by hand are one, and the fragment's,
         * which knows the property its nodes are identified by, wins.
         */
        private fun deduplicate(specs: List<SchemaItemSpec>): List<SchemaItemSpec> {
            val byKey = specs.groupBy(::keyOf)
            return byKey.map { (key, group) -> resolveDeclarations(key, group) }
        }

        private fun resolveDeclarations(
            key: Triple<SchemaItemKind, String, Set<String>>,
            group: List<SchemaItemSpec>,
        ): SchemaItemSpec {
            fun conflict(reason: String, offenders: List<SchemaItemSpec>): Nothing = throw DrivineException(
                "Conflicting schema declarations for ${key.first} on ${key.second}${key.third.toList()} " +
                    "($reason): " + offenders.joinToString("; ")
            )

            // Compare with engine options stripped: what remains is the portable declaration, and every
            // contributor must agree on it before engine options are even considered.
            val portable = group.map(::declared).distinct()
            if (portable.size > 1) {
                conflict("they disagree on portable parameters", portable)
            }

            val pinning = group.filterIsInstance<VectorIndexSpec>()
                .filter { it.engineOptions.isNotEmpty() }
                .distinct()
            if (pinning.size > 1) {
                conflict("more than one declaration pins engine options", pinning)
            }
            return pinning.firstOrNull()
                // A constraint read from a fragment knows the property its nodes are identified by.
                ?: group.firstOrNull { it is UniquenessConstraintSpec && it.nodeIdProperty != null }
                ?: group.first()
        }

        /**
         * What [spec] declares of the item itself: without engine options, which only one declaration
         * may pin, and without the property a violation's sample names nodes by, which a hand-written
         * declaration does not know.
         */
        private fun declared(spec: SchemaItemSpec): SchemaItemSpec = when (spec) {
            is VectorIndexSpec -> spec.withoutEngineOptions()
            is UniquenessConstraintSpec -> spec.copy(nodeIdProperty = null)
            else -> spec
        }
    }
}