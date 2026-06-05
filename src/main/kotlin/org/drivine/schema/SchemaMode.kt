package org.drivine.schema

/**
 * How [SchemaManager] treats outcomes that need attention (drift, constraint violations).
 */
enum class SchemaMode {

    /** Fail with an exception when drift or a violation is found (default). */
    FAIL_FAST,

    /** Log a warning and continue. */
    WARN,
}