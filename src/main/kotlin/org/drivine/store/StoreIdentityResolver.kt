package org.drivine.store

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.drivine.schema.EnsureResult
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.UniquenessConstraintSpec
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Reads the store's [StoreIdentity], minting it on first sight.
 *
 * The identity lives in the graph as a single `(:DrivineStore)` node, so it travels with the data:
 * a dump and restore carries it, a new container does not inherit it, and every client of the same
 * database reads the same value.
 *
 * Resolution is idempotent and safe to call on every boot.
 */
class StoreIdentityResolver(private val persistenceManager: PersistenceManager) {

    /**
     * Returns this store's identity, assigning one if the store has never been stamped.
     *
     * **Uniqueness is enforced, not inferred, where the engine allows it.** Two processes booting
     * against a virgin store can both pass `MERGE` on an engine that does not lock the pattern. Picking
     * a winner afterwards cannot repair that: a process that read before the other's write landed has
     * already cached its own stamp, and no ordering rule reaches back to change its mind. So where
     * [PersistenceManager.supportsSchemaManagement] is true, a uniqueness constraint on the merge key
     * is ensured first — the losing writer's `MERGE` then blocks or fails, is retried, and matches the
     * winner. This adds one constraint (and, on FalkorDB, its backing index) on `DrivineStore(key)`.
     *
     * **Elsewhere it is best-effort.** Engines without DDL (Neptune, generic openCypher), a store that
     * already holds duplicate stamps, or a connection without schema privileges fall back to plain
     * `MERGE` and read the oldest stamp, tie-broken by [StoreIdentity.id]. Callers reading after the
     * race agree; a caller that resolved mid-race may hold the other stamp. Duplicates are logged,
     * never deleted: removing a stamp that something may already have recorded is not a decision a
     * read should make.
     */
    fun resolve(): StoreIdentity {
        val guarded = ensureUniqueKey()
        repeat(MAX_ATTEMPTS) {
            assignIfAbsent(guarded)
            val stamps = readStamps()
            if (stamps.isNotEmpty()) {
                return identityFrom(stamps)
            }
            // Written, then read back nothing: something emptied the store in between. Stamp again.
            logger.warn(
                "[store-identity] DrivineStore stamp on database '{}' vanished between write and read; retrying",
                persistenceManager.database,
            )
        }
        error(
            "Could not establish identity for the store at '${persistenceManager.database}': a DrivineStore " +
                "stamp was written $MAX_ATTEMPTS times and never read back. Either something is repeatedly " +
                "clearing the store, or the engine is not persisting auto-commit writes."
        )
    }

    /**
     * Ensures the uniqueness constraint on the merge key. Returns whether it is in force; false degrades
     * [resolve] to best-effort convergence rather than failing boot over an identity that can still be read.
     */
    private fun ensureUniqueKey(): Boolean {
        if (!persistenceManager.supportsSchemaManagement) return false
        val constraints = persistenceManager.constraints
        return try {
            when (val result = constraints.ensure(KEY_CONSTRAINT, violationSampleSize = 0)) {
                is EnsureResult.Created, is EnsureResult.AlreadyMatching, is EnsureResult.Recreated -> true
                is EnsureResult.Violation -> {
                    logger.warn(
                        "[store-identity] Database '{}' already holds duplicate DrivineStore stamps from an " +
                            "earlier concurrent first boot, so uniqueness cannot be enforced. Identity is " +
                            "best-effort until the duplicates are removed.",
                        persistenceManager.database,
                    )
                    false
                }
                is EnsureResult.Drift -> {
                    logger.warn(
                        "[store-identity] Unexpected constraint on DrivineStore(key) on database '{}': {}. " +
                            "Identity is best-effort.",
                        persistenceManager.database, result.existing,
                    )
                    false
                }
            }
        } catch (e: Exception) {
            // Another process creating the same constraint at the same moment surfaces here on engines
            // without IF NOT EXISTS, or where concurrent schema writes conflict. If it is now in force,
            // the race was benign.
            if (runCatching { constraints.find(KEY_CONSTRAINT) }.getOrNull() != null) {
                true
            } else {
                logger.warn(
                    "[store-identity] Could not ensure uniqueness on DrivineStore(key) on database '{}' ({}). " +
                        "Identity is best-effort.",
                    persistenceManager.database, e.message,
                )
                false
            }
        }
    }

    /**
     * `MERGE`s the stamp. Under the constraint, a concurrent first writer can make this one fail with a
     * violation or a write conflict rather than block; retrying then matches the winner's node.
     */
    private fun assignIfAbsent(guarded: Boolean) {
        var attempt = 1
        while (true) {
            try {
                persistenceManager.execute(
                    QuerySpecification.withStatement(ASSIGN_IF_ABSENT).bind(
                        mapOf(
                            "key" to SINGLETON_KEY,
                            "storeId" to UUID.randomUUID().toString(),
                            "assignedAt" to Instant.now().toString(),
                            "engine" to persistenceManager.type.value,
                        )
                    )
                )
                return
            } catch (e: Exception) {
                if (!guarded || attempt >= MAX_MERGE_ATTEMPTS || !lostTheRace(e)) throw e
                logger.debug("[store-identity] Concurrent stamp won on attempt {}; merging again", attempt)
                // Back off so the winner can commit; otherwise the retry just conflicts with it again.
                Thread.sleep(MERGE_BACKOFF_MILLIS shl (attempt - 1))
                attempt++
            }
        }
    }

    private fun lostTheRace(e: Throwable): Boolean =
        persistenceManager.constraints.grammar.isConstraintViolation(e) ||
            // Memgraph's optimistic concurrency: "Cannot resolve conflicting transactions. You can retry…"
            SchemaGrammar.messagesOf(e).contains("conflicting transactions", ignoreCase = true)

    private fun readStamps(): List<Stamp> =
        persistenceManager.query(QuerySpecification.withStatement(READ_STAMPS).transform<Stamp>())

    private fun identityFrom(stamps: List<Stamp>): StoreIdentity {
        if (stamps.size > 1) {
            logger.warn(
                "[store-identity] {} DrivineStore stamps on database '{}': {}. Concurrent first boots " +
                    "each minted one. Using the oldest ({}); delete the others once nothing references them.",
                stamps.size,
                persistenceManager.database,
                stamps.joinToString(", ") { it.id },
                stamps.first().id,
            )
        }
        val winner = stamps.first()
        return StoreIdentity(
            id = winner.id,
            engine = persistenceManager.type,
            assignedAt = winner.assignedAt,
        )
    }

    /** One row of the `(:DrivineStore)` scan. Public so the mapper can see it. */
    data class Stamp(val id: String, val assignedAt: String)

    internal companion object {
        private val logger = LoggerFactory.getLogger(StoreIdentityResolver::class.java)

        /** Bounds write-then-empty-read retries. */
        private const val MAX_ATTEMPTS = 3

        /** Lost-race MERGE retries, backing off 50, 100, 200, 400 ms — enough for a peer's boot write to commit. */
        private const val MAX_MERGE_ATTEMPTS = 5
        private const val MERGE_BACKOFF_MILLIS = 50L

        /** The MERGE key. Fixed, so a second boot matches the node the first one wrote. */
        private const val SINGLETON_KEY = "singleton"

        /** Makes the MERGE key unique, so concurrent first boots cannot leave two stamps. */
        internal val KEY_CONSTRAINT = UniquenessConstraintSpec("DrivineStore", "key")

        private const val ASSIGN_IF_ABSENT = """
            MERGE (s:DrivineStore { key: ${'$'}key })
            ON CREATE SET s.storeId = ${'$'}storeId,
                          s.assignedAt = ${'$'}assignedAt,
                          s.engine = ${'$'}engine
        """

        /**
         * Projected as a map, not as two columns: a multi-column row arrives as a positional
         * list, which cannot be bound to [Stamp] by name. Ordered so every caller that reads after
         * the race picks the same stamp when one left more than one.
         */
        private const val READ_STAMPS = """
            MATCH (s:DrivineStore)
            RETURN { id: s.storeId, assignedAt: s.assignedAt } AS stamp
            ORDER BY s.assignedAt, s.storeId
        """
    }
}
