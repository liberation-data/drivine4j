package org.drivine.schema

/**
 * How [SchemaManager] applies catalogs — the engine-agnostic policy, independent of Spring config.
 *
 * @param mode whether drift / violations fail or just warn
 * @param recreateOnDrift destructively recreate items whose existing shape differs from their
 *   declaration (e.g. a vector index whose dimensions changed)
 * @param recreateOnStartup destructively recreate every declared item on every [SchemaManager.enforce]
 * @param violationSampleSize how many conflicting rows to sample on a constraint violation (0 disables)
 */
data class SchemaPolicy(
    val mode: SchemaMode = SchemaMode.FAIL_FAST,
    val recreateOnDrift: Boolean = false,
    val recreateOnStartup: Boolean = false,
    val violationSampleSize: Int = 10,
)