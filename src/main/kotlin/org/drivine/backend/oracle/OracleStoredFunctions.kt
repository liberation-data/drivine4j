package org.drivine.backend.oracle

import org.drivine.cypher.sql.OracleCypherCompiler
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a session may call the functions a graph's store keeps beside its tables (see [OracleGraph.functions]). A store
 * made by an earlier version has none until the graph is ensured again, and a user who may not make a function
 * (`CREATE PROCEDURE`) has none at all: a Cypher statement is then compiled to call none, and says so once for each graph.
 *
 * Asked only by a statement that would call one, and only until one is found: a session that may call it is remembered
 * for the database, its user and the graph, and one that may not is asked again, so a graph ensured later gains it.
 */
internal object OracleStoredFunctions {

    private val logger = LoggerFactory.getLogger(OracleStoredFunctions::class.java)

    /** The database, user and graph of each session found to call the function. */
    private val callable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The database, user and graph of each session that was said to have no function. */
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Whether [connection] may call the function the graph [graph] compares two values that may hold a null by. It calls
     * the function: a function another user's store keeps, read through a synonym, is called as the graph's tables are.
     * A query that fails rolls back only itself, so a transaction open on [connection] is left as it was.
     */
    fun nullsEqual(connection: Connection, graph: String): Boolean {
        val key = "${connection.metaData.url}|${connection.metaData.userName}|$graph"
        if (key in callable) return true
        val name = OracleCypherCompiler.nullsEqualName(graph)
        val found = try {
            connection.prepareStatement("SELECT $name(JSON('[1]'), JSON('[1]')) FROM DUAL").use { statement -> statement.executeQuery().use { it.next() } }
        } catch (e: SQLException) {
            false
        }
        if (found) {
            callable += key
        } else if (warned.add(key)) {
            logger.warn(
                "The Oracle graph '{}' has no function {}, which the graph is made with where its user may make a function " +
                    "(CREATE PROCEDURE, which the RESOURCE role grants). Until the graph is ensured by such a user, a Cypher statement " +
                    "that writes, or the step of a reduce(), compares two lists or maps that hold a null and differ as JSON as false, " +
                    "where Cypher may give null.",
                graph, name,
            )
        }
        return found
    }
}
