package org.drivine.store

import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Reads the store's [StoreIdentity], minting it on first sight.
 *
 * The identity lives in the graph as a single `(:DrivineStore)` node, so it travels with the data:
 * a dump and restore carries it, a new container does not inherit it, and every client of the same
 * database reads the same value. Only plain `MERGE` and `MATCH` are used, so this works on any
 * engine Drivine can talk to, including those with no schema DDL.
 *
 * Resolution is idempotent and safe to call on every boot.
 */
class StoreIdentityResolver(private val persistenceManager: PersistenceManager) {

    /**
     * Returns this store's identity, assigning one if the store has never been stamped.
     *
     * Concurrent first boots can both pass `MERGE` on an engine that does not lock the pattern,
     * leaving two stamps. Rather than let the winner depend on scan order — which would make one
     * store answer to two identities, the exact failure this class exists to prevent — every
     * caller converges on the oldest stamp, tie-broken by [StoreIdentity.id] so the choice is
     * total. The duplicate is logged, never deleted: removing a stamp that something may already
     * have recorded is not a decision a read should make.
     */
    fun resolve(): StoreIdentity {
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
        val stamps = persistenceManager.query(
            QuerySpecification.withStatement(READ_STAMPS).transform<Stamp>()
        )
        check(stamps.isNotEmpty()) {
            "Wrote a DrivineStore stamp but read none back. The store at '${persistenceManager.database}' " +
                "accepted a write that did not persist — identity cannot be established."
        }
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

    private companion object {
        private val logger = LoggerFactory.getLogger(StoreIdentityResolver::class.java)

        /** The MERGE key. Fixed, so a second boot matches the node the first one wrote. */
        private const val SINGLETON_KEY = "singleton"

        private const val ASSIGN_IF_ABSENT = """
            MERGE (s:DrivineStore { key: ${'$'}key })
            ON CREATE SET s.storeId = ${'$'}storeId,
                          s.assignedAt = ${'$'}assignedAt,
                          s.engine = ${'$'}engine
        """

        /**
         * Projected as a map, not as two columns: a multi-column row arrives as a positional
         * list, which cannot be bound to [Stamp] by name. Ordered so every caller picks the same
         * stamp when a race left more than one.
         */
        private const val READ_STAMPS = """
            MATCH (s:DrivineStore)
            RETURN { id: s.storeId, assignedAt: s.assignedAt } AS stamp
            ORDER BY s.assignedAt, s.storeId
        """
    }
}
