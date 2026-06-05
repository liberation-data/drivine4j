package org.drivine.autoconfigure

import org.drivine.schema.SchemaMode
import org.drivine.schema.SchemaPolicy
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Drivine's schema management.
 *
 * ```yaml
 * drivine:
 *   schema:
 *     enabled: true              # run schema enforcement on startup
 *     mode: FAIL_FAST            # or WARN
 *     recreate-on-drift: false   # destructive: rebuild items whose shape changed
 *     recreate-on-startup: false # destructive: rebuild all declared items every startup
 *     violation-sample-size: 10
 * ```
 *
 * [enabled] gates only the startup run; the [org.drivine.schema.SchemaManager] bean is always
 * available, so an application can call `enforce()` / `recreateAll()` at runtime regardless.
 */
@ConfigurationProperties(prefix = "drivine.schema")
data class DrivineSchemaProperties(

    /** Whether to run schema enforcement on application startup. */
    var enabled: Boolean = true,

    /** How to treat drift and constraint violations. */
    var mode: SchemaMode = SchemaMode.FAIL_FAST,

    /**
     * Destructively recreate items whose existing shape differs from their declaration —
     * e.g. a vector index whose dimensions changed because the embedding model changed.
     */
    var recreateOnDrift: Boolean = false,

    /** Destructively recreate all declared items on every enforcement. */
    var recreateOnStartup: Boolean = false,

    /** How many conflicting rows to sample when a constraint violation is detected (0 disables). */
    var violationSampleSize: Int = 10,
) {

    /** The engine-agnostic policy these properties express. */
    fun toPolicy(): SchemaPolicy = SchemaPolicy(
        mode = mode,
        recreateOnDrift = recreateOnDrift,
        recreateOnStartup = recreateOnStartup,
        violationSampleSize = violationSampleSize,
    )
}