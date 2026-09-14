package org.drivine.store

import org.drivine.connection.DatabaseType

/**
 * Stable identity of the store behind a connection — who this graph *is*, not where it lives.
 *
 * An address cannot answer that question. `bolt://localhost:7687` is a different database after a
 * container is recreated, the same database answers to a different host from inside Docker, and a
 * restored backup is the same data at a new URI. A caller that stamps its own artifacts with a
 * connection string therefore learns nothing on the next boot: the string still matches when the
 * data is gone, and differs when the data is intact.
 *
 * [id] is minted once, on first sight of a store, and lives in the store itself. Two connections
 * report the same [id] when and only when they are looking at the same data. That makes "am I
 * pointed at the store my data came from?" an equality check rather than an inference, which is
 * the question an application must be able to ask before it provisions anything.
 *
 * ### In-process engines
 *
 * A store with no durable journal mints a new [id] every process, because that is the truth: each
 * process IS a distinct store. An application that stamped its artifacts with one will correctly
 * refuse to recognise the next one, rather than treating an empty journal as a fresh install.
 *
 * @property id UUID assigned to this store the first time Drivine saw it. Never reassigned.
 * @property engine the engine actually connected, not the engine recorded at assignment.
 * @property assignedAt ISO-8601 instant the [id] was minted, as a string — portable across engines
 *   with differing temporal support, and only ever used for display and tie-breaking.
 *
 * @see StoreIdentityResolver
 */
data class StoreIdentity(
    val id: String,
    val engine: DatabaseType,
    val assignedAt: String,
)
