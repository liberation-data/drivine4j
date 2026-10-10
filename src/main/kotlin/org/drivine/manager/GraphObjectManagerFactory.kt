package org.drivine.manager

import com.fasterxml.jackson.databind.ObjectMapper
import org.drivine.mapper.SubtypeRegistry
import org.drivine.query.dsl.IndexAdvicePolicy
import org.drivine.session.SessionManager

/**
 * Hands out the object managers of each registered database, one per database and
 * [PersistenceManagerType], built on the [PersistenceManager] that [PersistenceManagerFactory] gives.
 *
 * - [stateless] returns a [StatelessGraphObjectManager], which keeps no session. Use this one.
 * - [get] returns the deprecated [GraphObjectManager], each with a [SessionManager] of its own.
 *
 * The two can share a database. They do not save alike: see the README, "Migrating from
 * GraphObjectManager".
 */
@Suppress("DEPRECATION") // built on GraphObjectManager, which is deprecated for callers
class GraphObjectManagerFactory(
    private val persistenceManagerFactory: PersistenceManagerFactory,
    private val objectMapper: ObjectMapper,
    private val subtypeRegistry: SubtypeRegistry,
    /**
     * Applied to every manager this factory creates, from [stateless] and from [get]. See
     * [GraphObjectOperations.indexAdvice]; an individual manager can still be turned up or down
     * after it is handed out.
     */
    private val indexAdvice: IndexAdvicePolicy = IndexAdvicePolicy.WARN,
    /**
     * The session bound of every manager [get] creates. See [SessionManager.maxEntries]. A manager
     * from [stateless] keeps no session, so it has nothing to bound.
     */
    private val sessionMaxEntries: Int = SessionManager.DEFAULT_MAX_ENTRIES,
) {
    private val managers: MutableMap<String, GraphObjectManager> = mutableMapOf()
    private val statelessManagers: MutableMap<String, StatelessGraphObjectManager> = mutableMapOf()

    /**
     * Returns a [StatelessGraphObjectManager] for the database registered under the specified name:
     * the same one each time for a given [database] and [type]. It keeps no session, so it can share
     * a database with a manager from [get].
     * @param database Unique name for the registered database.
     * @param type The type of PersistenceManager to use (TRANSACTIONAL, NON_TRANSACTIONAL, or DELEGATING).
     */
    @JvmOverloads
    @Synchronized
    fun stateless(database: String = "default", type: PersistenceManagerType = PersistenceManagerType.DELEGATING): StatelessGraphObjectManager =
        statelessManagers.getOrPut("$database:$type") {
            StatelessGraphObjectManager(persistenceManagerFactory.get(database, type), objectMapper, subtypeRegistry)
                .apply { indexAdvice = this@GraphObjectManagerFactory.indexAdvice }
        }

    /**
     * Returns a GraphObjectManager for the database registered under the specified name: the same
     * one each time for a given [database] and [type].
     * @param database Unique name for the registered database.
     * @param type The type of PersistenceManager to use (TRANSACTIONAL, NON_TRANSACTIONAL, or DELEGATING).
     */
    @Deprecated(
        "GraphObjectManager is deprecated in favour of StatelessGraphObjectManager, from stateless(). " +
            "The stateless manager saves differently: its save adds relationships and removes none unless told to, " +
            "where this manager's removes what its session saw and the object no longer holds. " +
            "So this is not a drop-in replacement. See the README: Migrating from GraphObjectManager."
    )
    @JvmOverloads
    @Synchronized
    fun get(database: String = "default", type: PersistenceManagerType = PersistenceManagerType.DELEGATING): GraphObjectManager =
        managers.getOrPut("$database:$type") {
            GraphObjectManager(
                persistenceManagerFactory.get(database, type),
                SessionManager(objectMapper, sessionMaxEntries),
                objectMapper,
                subtypeRegistry,
            ).apply { indexAdvice = this@GraphObjectManagerFactory.indexAdvice }
        }
}
