package org.drivine.schema

/**
 * How [SchemaManager] applies catalogs — the engine-agnostic policy, independent of Spring config.
 *
 * @param mode whether drift / violations / an index in the way of a constraint fail or just warn
 * @param recreateOnDrift destructively recreate items whose existing shape differs from their
 *   declaration (e.g. a vector index whose dimensions changed)
 * @param recreateOnStartup destructively recreate every declared item on every [SchemaManager.enforce]
 * @param violationSampleSize how many conflicting rows to sample on a constraint violation, and how many of the
 *   nodes that hold each one to name (0 leaves the sample empty; the data is still read, to confirm the violation)
 * @param replaceBlockingIndex drop a plain index in the way of a uniqueness constraint and make the
 *   constraint, whose own index takes its place. Only Neo4j reports one in the way: there the
 *   constraint brings its own index, while on FalkorDB the plain index is what the constraint needs.
 */
data class SchemaPolicy(
    val mode: SchemaMode = SchemaMode.FAIL_FAST,
    val recreateOnDrift: Boolean = false,
    val recreateOnStartup: Boolean = false,
    val violationSampleSize: Int = 10,
    val replaceBlockingIndex: Boolean = false,
)