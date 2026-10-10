package org.drivine.autoconfigure

import org.drivine.query.dsl.IndexAdvicePolicy
import org.drivine.session.SessionManager
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Query-time behaviour that is not about connecting or about schema enforcement.
 *
 * ```yaml
 * drivine:
 *   query:
 *     index-advice: FAIL      # WARN (default) | OFF
 *     session-max-entries: 100000
 * ```
 */
@ConfigurationProperties(prefix = "drivine.query")
data class DrivineQueryProperties(
    /**
     * What to do when an ordered or keyset-paginated query has no range index to seek into.
     *
     * `WARN` logs once per label/property combination. `FAIL` throws — intended for development and
     * CI, where an unindexed page should fail the build rather than quietly scan in production.
     * `OFF` says nothing; ordering without an index is correct, just unindexed, which is a
     * reasonable thing to do on a small collection.
     */
    var indexAdvice: IndexAdvicePolicy = IndexAdvicePolicy.WARN,

    /**
     * How many loaded objects each GraphObjectManager's session tracks for dirty checking. Past it the
     * least recently used is evicted; an evicted object's next save writes all fields. An entry is a
     * compact digest (bytes per field), so the default costs tens of megabytes at most.
     *
     * Applies only to the deprecated GraphObjectManager: a StatelessGraphObjectManager keeps no
     * session, so this bounds nothing there.
     */
    var sessionMaxEntries: Int = SessionManager.DEFAULT_MAX_ENTRIES,
)
