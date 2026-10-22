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
 *     replace-blocking-index: false # drop a plain index in the way of a uniqueness constraint (Neo4j)
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

    /**
     * How many conflicting rows to sample when a constraint violation is detected, and how many of the
     * nodes that hold each one to name (0 leaves the sample empty; the data is still read, to confirm
     * the violation).
     */
    var violationSampleSize: Int = 10,

    /**
     * Drop a plain index in the way of a uniqueness constraint and make the constraint, whose own
     * index takes its place. Only Neo4j reports one in the way.
     */
    var replaceBlockingIndex: Boolean = false,
) {

    /** The engine-agnostic policy these properties express. */
    fun toPolicy(): SchemaPolicy = SchemaPolicy(
        mode = mode,
        recreateOnDrift = recreateOnDrift,
        recreateOnStartup = recreateOnStartup,
        violationSampleSize = violationSampleSize,
        replaceBlockingIndex = replaceBlockingIndex,
    )
}