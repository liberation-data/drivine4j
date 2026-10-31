package org.drivine.store

import org.drivine.backend.GraphBackend
import org.drivine.backend.GraphBackends
import org.drivine.backend.sql.DdlContention
import org.drivine.manager.PersistenceManager
import org.drivine.manager.SaveExecutor
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.query.QuerySpecification
import org.drivine.query.transform
import org.drivine.schema.ConstraintManager
import org.drivine.schema.EnsureResult
import org.drivine.schema.SchemaGrammar
import org.drivine.schema.StoreStampStatements
import org.drivine.schema.CypherStoreStamp
import org.drivine.schema.reservedNode
import org.drivine.schema.UniquenessConstraintSpec
import org.slf4j.LoggerFactory
import java.sql.SQLException
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
     * The stamp's statements in the store's own language: Cypher, unless the manager's grammar says
     * otherwise, as Oracle's does. See [SchemaGrammar.storeStamp].
     */
    private val statements: StoreStampStatements by lazy {
        val label = KEY_CONSTRAINT.label
        val key = KEY_CONSTRAINT.properties.single()
        persistenceManager.reservedNode("the store's identity", { storeStamp(label, key) }) { CypherStoreStamp(label, key) }
    }

    /**
     * Ensures the uniqueness constraint on the merge key. Returns whether it is in force; false degrades
     * [resolve] to best-effort convergence rather than failing boot over an identity that can still be read.
     *
     * Managers making the constraint at once can deadlock (Neo4j does, on the label's lock), so one turned
     * away so makes it again, and then finds the winner's. Going on to write the stamp without it would let
     * two stamps be written before the winner's constraint is in force, and it then fails on them (#473).
     *
     * One manager's statement can also be refused for what another's has just made ([madeMeanwhile]): on
     * FalkorDB the key's backing index already there, before the winner's constraint is, and on Postgres the
     * index's name taken in `pg_class`. The constraint is then ensured again, a few times at most, which finds
     * the other's, rather than going on without it (#492, as liberation-data/drivine#489).
     */
    private fun ensureUniqueKey(): Boolean {
        if (!persistenceManager.supportsSchemaManagement) return false
        val constraints = persistenceManager.constraints
        return try {
            val result = ensuredAgainWhenMadeMeanwhile(constraints)
            when (result) {
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
                is EnsureResult.BlockedByIndex -> {
                    logger.warn(
                        "[store-identity] Index {} on DrivineStore(key) on database '{}' is in the way of its " +
                            "uniqueness constraint. Identity is best-effort until it is dropped.",
                        result.index.name, persistenceManager.database,
                    )
                    false
                }
            }
        } catch (e: Exception) {
            // Another process creating the same constraint at the same moment surfaces here on engines
            // without IF NOT EXISTS, or where concurrent schema writes conflict. If it is now in force,
            // the race was benign. One whose build failed (FalkorDB) is listed, yet enforces nothing.
            if (runCatching { constraints.find(KEY_CONSTRAINT) }.getOrNull()?.takeUnless { it.buildFailed } != null) {
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

    /** Ensures the key's constraint, again when another manager made it, or what it needs, meanwhile. */
    private fun ensuredAgainWhenMadeMeanwhile(constraints: ConstraintManager): EnsureResult {
        var attempt = 1
        while (true) {
            try {
                // The label is Drivine's, so a plain index on the key (Neo4j) is replaced by the constraint.
                return DdlContention.whenNotContended(turnedAway = ::contended) {
                    constraints.ensure(KEY_CONSTRAINT, violationSampleSize = 0, replaceBlockingIndex = true)
                }
            } catch (e: Exception) {
                // A deadlock has been run again already, by whenNotContended, as many times as it runs one.
                if (attempt >= MAX_ENSURE_ATTEMPTS || !madeMeanwhile(e, constraints.grammar)) throw e
                logger.debug("[store-identity] DrivineStore(key) made meanwhile by another manager on attempt {}; ensuring again", attempt)
                Thread.sleep(SaveExecutor.contendedPause(attempt - 1))
                attempt++
            }
        }
    }

    /**
     * `MERGE`s the stamp. Under the constraint, a concurrent first writer can make this one fail with a
     * violation or a write conflict rather than block; retrying then matches the winner's node.
     *
     * A violation is retried even when [ensureUniqueKey] could not confirm the constraint, for the violation
     * itself proves one is in force: on Neo4j, managers making it at once deadlock, and a loser may not see
     * the winner's constraint yet when it looks, though it is enforced by the time the stamp is written (#473).
     * A conflict proves nothing, so it is retried only under a constraint confirmed.
     */
    private fun assignIfAbsent(guarded: Boolean) {
        var attempt = 1
        while (true) {
            try {
                persistenceManager.execute(
                    QuerySpecification.withStatement(statements.assignIfAbsent).bind(
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
                if (attempt >= MAX_MERGE_ATTEMPTS || !(if (guarded) lostTheRace(e) else violatedTheKey(e))) throw e
                logger.debug("[store-identity] Concurrent stamp won on attempt {}; merging again", attempt)
                // Back off so the winner can commit; otherwise the retry just conflicts with it again.
                Thread.sleep(MERGE_BACKOFF_MILLIS shl (attempt - 1))
                attempt++
            }
        }
    }

    /** The store's backend, which knows its engine's deadlocks: see [GraphBackend.contended]. */
    private val backend: GraphBackend by lazy { GraphBackends.of(persistenceManager, Neo4jObjectMapper.instance) }

    /**
     * Whether [e], or a cause under it, is the engine turning a statement away for another session's. On the SQL
     * stores this includes the DDL deadlocks [DdlContention] retries on its own, for the store's DDL run within
     * this run of it is run once.
     */
    private fun contended(e: Throwable): Boolean =
        DdlContention.contended(e) || generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any(backend::contended)

    /** Whether [e] is a constraint refusing the stamp. Only a manager with schema management has a grammar to ask. */
    private fun violatedTheKey(e: Throwable): Boolean =
        persistenceManager.supportsSchemaManagement && persistenceManager.constraints.grammar.isConstraintViolation(e)

    private fun lostTheRace(e: Throwable): Boolean =
        violatedTheKey(e) ||
            // Memgraph's optimistic concurrency: "Cannot resolve conflicting transactions. You can retry…"
            SchemaGrammar.messagesOf(e).contains("conflicting transactions", ignoreCase = true)

    private fun readStamps(): List<Stamp> =
        persistenceManager.query(QuerySpecification.withStatement(statements.readStamps).transform<Stamp>())

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

        /** How far down a failure's causes the engine's own error is looked for. */
        private const val MAX_CAUSE_DEPTH = 10

        /** How many times the key's constraint is ensured when another manager made it meanwhile: see [madeMeanwhile]. */
        private const val MAX_ENSURE_ATTEMPTS = 3

        /** A graph engine refusing a schema statement for what is there: FalkorDB's "Constraint already exists", "Attribute 'key' is already indexed". */
        private val ALREADY_MADE = Regex("already (exists|indexed)", RegexOption.IGNORE_CASE)

        /** A Postgres unique violation in its own catalog, as on `pg_class_relname_nsp_index`, rather than in a graph's rows. */
        private val PG_CATALOG = Regex("unique constraint \"pg_")

        /**
         * Whether [e], or a cause under it, is a schema statement refused for what another session made meanwhile.
         * On the SQL stores only by code: Oracle's `ORA-00955` (a name already used), and Postgres's `23505` on one of
         * its catalog's constraints, as two sessions running `CREATE UNIQUE INDEX IF NOT EXISTS` at once get; a 23505
         * on a graph's rows is a violation, and not this. On a graph engine, "already exists" or "already indexed",
         * unless it is an index in the way of the constraint ([SchemaGrammar.isBlockedByIndex]), which
         * [ConstraintManager.ensure] answers itself.
         */
        internal fun madeMeanwhile(e: Throwable, grammar: SchemaGrammar): Boolean {
            val causes = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
            val sql = causes.filterIsInstance<SQLException>()
            if (sql.isNotEmpty()) {
                return sql.any { it.errorCode == 955 || (it.sqlState == "23505" && PG_CATALOG.containsMatchIn(it.message.orEmpty())) }
            }
            return !grammar.isBlockedByIndex(e) && causes.any { ALREADY_MADE.containsMatchIn(it.message.orEmpty()) }
        }

        /** The MERGE key. Fixed, so a second boot matches the node the first one wrote. */
        internal const val SINGLETON_KEY = "singleton"

        /** Makes the MERGE key unique, so concurrent first boots cannot leave two stamps. */
        internal val KEY_CONSTRAINT = UniquenessConstraintSpec("DrivineStore", "key")
    }
}
