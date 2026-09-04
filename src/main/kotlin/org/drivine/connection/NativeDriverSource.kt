package org.drivine.connection

/**
 * Capability interface for connection providers that can hand out the engine's own client
 * object — the type application code would otherwise construct for itself.
 *
 * Maintenance paths (reconcilers, backfills, migrations) sometimes speak an engine's client
 * library directly rather than going through [Connection] and
 * [org.drivine.query.QuerySpecification]. Rebuilding that client from connection properties
 * works, but opens a second connection pool and silently drifts from the configuration the
 * provider used; for an engine living inside the application process there is nothing to
 * rebuild at all, and only the provider can supply an implementation.
 *
 * Not every engine has one to give: providers that cannot answer simply do not implement this,
 * so a caller's `as?` yields null and the absence is a typed answer rather than a failure.
 *
 * ```
 * val driver = (provider as? NativeDriverSource<*>)?.nativeDriver as? Driver
 *     ?: error("$forWhom speaks the bolt driver directly; ${provider.type} has none")
 * ```
 *
 * The returned object is owned by the provider: it is closed by [ConnectionProvider.end], and
 * callers must not close it themselves.
 *
 * @param T the engine's client type, e.g. `org.neo4j.driver.Driver`.
 */
interface NativeDriverSource<T : Any> {

    /** The provider's own client instance, configured exactly as its [Connection]s are. */
    val nativeDriver: T
}
