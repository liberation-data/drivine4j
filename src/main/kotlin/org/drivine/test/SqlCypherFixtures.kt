package org.drivine.test

import org.drivine.manager.PersistenceManager
import org.drivine.query.CypherStatement
import org.drivine.query.QuerySpecification
import org.drivine.query.sqlStatement

/**
 * Lets a test written for the graph engines run against a SQL store, Oracle, Postgres or SQLite, with its
 * fixtures as they are.
 *
 * Such a test seeds its graph, plays another writer and reads back what was stored in Cypher, through
 * a `PersistenceManager`, with statements of no language. A statement of no language is Cypher on every
 * store, so a SQL store compiles them to its SQL as they are, unless its connection is registered to
 * default to SQL (`ConnectionProperties.defaultLanguage`). What [over] adds is the clear: the statement
 * that empties a graph on the graph engines, `MATCH (n) DETACH DELETE n`, empties the graph's two tables
 * instead, the quicker way.
 */
object SqlCypherFixtures {

    /** [pm], with the statement that empties a graph run as [clear], and every other statement as it is. */
    @JvmStatic
    fun over(pm: PersistenceManager): PersistenceManager = object : PersistenceManager by pm {
        override fun execute(spec: QuerySpecification<*>) {
            if (spec.statement!!.text.trim() == CLEAR) clear(pm) else pm.execute(spec)
        }
    }

    /**
     * [pm] as a test that seeds and reads back in Cypher wants it, whatever store it speaks to: [over]
     * it where the store speaks SQL, and [pm] itself where it speaks Cypher. So one test, or one bean of
     * a test's context, serves the graph engines, Oracle, Postgres and SQLite alike.
     */
    @JvmStatic
    fun forStore(pm: PersistenceManager): PersistenceManager = if (pm.type.speaksCypher) pm else over(pm)

    /** Runs the Cypher statement [cypher] on the graph [pm] speaks to, and gives the rows it returns. */
    @JvmStatic
    @JvmOverloads
    fun run(pm: PersistenceManager, cypher: String, parameters: Map<String, Any?> = emptyMap()): List<Any?> =
        pm.query(QuerySpecification.withStatement(CypherStatement(cypher.trim())).bind(parameters))

    /** Removes every node and relationship of the graph [pm] speaks to. */
    @JvmStatic
    fun clear(pm: PersistenceManager) {
        val graph = requireNotNull(pm.graphName) { "The manager names no graph." }
        pm.execute(QuerySpecification.withStatement(sqlStatement("DELETE FROM ${graph}_edge")))
        pm.execute(QuerySpecification.withStatement(sqlStatement("DELETE FROM ${graph}_node")))
    }

    /** The statement that clears the graph, which is cleared the quicker way. */
    private const val CLEAR = "MATCH (n) DETACH DELETE n"
}
