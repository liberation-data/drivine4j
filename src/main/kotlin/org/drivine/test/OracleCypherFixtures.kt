package org.drivine.test

import org.drivine.manager.PersistenceManager

/** The fixtures of [SqlCypherFixtures], under the name they had when Oracle was the only SQL store. */
@Deprecated("Postgres keeps a graph as Oracle does, and SqlCypherFixtures serves both", ReplaceWith("SqlCypherFixtures"))
object OracleCypherFixtures {

    @JvmStatic
    fun over(pm: PersistenceManager): PersistenceManager = SqlCypherFixtures.over(pm)

    @JvmStatic
    @JvmOverloads
    fun run(pm: PersistenceManager, cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        SqlCypherFixtures.run(pm, cypher, parameters)

    @JvmStatic
    fun clear(pm: PersistenceManager) = SqlCypherFixtures.clear(pm)
}
