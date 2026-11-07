package org.drivine.connection

import java.util.IdentityHashMap

/**
 * One connection of a provider, held on the current thread for the length of a block ([pinned]), which every
 * statement of the provider's non-transactional path in that block runs on ([on]): a schema command's catalog
 * reads, its DDL and its marker's writes, which would otherwise each borrow a connection (#654).
 *
 * The connection is the provider's own, as [ConnectionProvider.connect] gives it, and is never one of a caller's
 * transaction: nothing here starts, commits or rolls back a transaction on it, so each statement commits as it
 * runs, as on a connection borrowed for it alone. A block within the block reuses the connection, and the
 * outermost gives it back as it ends. It is given back with the failure that ended the block only where a
 * statement on it failed: a block that refuses a command itself, as one that names an index made already does,
 * leaves the connection as sound as it found it, and the connection is not logged as closed with an error.
 */
internal object PinnedConnections {

    /** A connection held for a block, and whether a statement on it has failed. */
    private class Pin(val connection: Connection) {
        var failed = false
    }

    /** The connections held on this thread, by their provider. Null where none is. */
    private val held = ThreadLocal<IdentityHashMap<ConnectionProvider, Pin>?>()

    /** Runs [block] with one connection of [provider] held for [on], borrowed now and given back as it ends. */
    fun <T> pinned(provider: ConnectionProvider, block: () -> T): T {
        if (held.get()?.containsKey(provider) == true) return block()
        val pin = Pin(provider.connect())
        val pins = held.get() ?: IdentityHashMap<ConnectionProvider, Pin>().also(held::set)
        pins[provider] = pin
        val result = try {
            block()
        } catch (e: Throwable) {
            unpin(pins, provider)
            pin.connection.release(e.takeIf { pin.failed })
            throw e
        }
        unpin(pins, provider)
        pin.connection.release()
        return result
    }

    /**
     * Runs [block] on the connection of [provider] held on this thread, or, where none is, on one borrowed for it
     * and given back as it ends, with the failure that ended it, if one did.
     */
    fun <T> on(provider: ConnectionProvider, block: (Connection) -> T): T {
        held.get()?.get(provider)?.let { pin ->
            return try {
                block(pin.connection)
            } catch (e: Exception) {
                pin.failed = true
                throw e
            }
        }
        val connection = provider.connect()
        val result = try {
            block(connection)
        } catch (e: Exception) {
            connection.release(e)
            throw e
        }
        connection.release()
        return result
    }

    private fun unpin(pins: IdentityHashMap<ConnectionProvider, Pin>, provider: ConnectionProvider) {
        pins.remove(provider)
        if (pins.isEmpty()) held.remove()
    }
}
