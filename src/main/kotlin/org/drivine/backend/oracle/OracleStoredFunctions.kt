package org.drivine.backend.oracle

import org.drivine.cypher.sql.OracleCypherCompiler
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a session may call the functions a graph's store keeps beside its tables (see [OracleGraph.functions]). A store
 * made by an earlier version has none until the graph is ensured again, and a user who may not make a function
 * (`CREATE PROCEDURE`) has none at all: a Cypher statement is then compiled to call none, and says so once for each graph.
 *
 * Asked only by a statement that would call one. A session that may call it is remembered for the database, its user and
 * the graph, for as long as the JVM runs: a function dropped after that is not noticed, and a statement that calls it fails
 * until the graph is ensured again (or the JVM restarts). One that may not is remembered for [ABSENT_FOR], or until the
 * graph is ensured in this JVM (see [OracleGraph.ensure]), so a store without the function is not asked on every statement,
 * and a graph ensured later still gains it.
 */
internal object OracleStoredFunctions {

    private val logger = LoggerFactory.getLogger(OracleStoredFunctions::class.java)

    /** The database, user and graph of each session found to call the function. */
    private val callable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The database, user and graph of each session found to call none, and when ([clock]) to ask again. */
    private val absent: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /** The database, user and graph of each session that was said to have no function. */
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** How long a session found to call no function is taken to call none before it is asked again. */
    val ABSENT_FOR: Duration = Duration.ofSeconds(60)

    /** Nanoseconds, as [System.nanoTime] counts them: replaced by a test that lets [ABSENT_FOR] pass. */
    internal var clock: () -> Long = System::nanoTime

    /**
     * The errors calling a function there is none of gives: `ORA-00904` where there is none, or none the session may call
     * (Oracle says the same of one it may not execute, and of a synonym for one that is gone), `ORA-06575` where it is not
     * valid, and `ORA-00942` or `ORA-01031` where something it reads is not granted. Any other error, a connection lost
     * among them, is the statement's to report.
     */
    private val NO_FUNCTION = setOf(904, 942, 1031, 6575)

    /**
     * Whether [connection] may call the function the graph [graph] compares two values that may hold a null by. It calls
     * the function: a function another user's store keeps, read through a synonym, is called as the graph's tables are.
     * A query that fails rolls back only itself, so a transaction open on [connection] is left as it was.
     *
     * @throws SQLException where calling it fails for any reason but there being no function it may call
     */
    fun nullsEqual(connection: Connection, graph: String): Boolean {
        val key = "${connection.metaData.url}|${connection.metaData.userName}|$graph"
        if (key in callable) return true
        val now = clock()
        if (absent[key]?.let { now - it < 0 } == true) return false
        val name = OracleCypherCompiler.nullsEqualName(graph)
        val found = try {
            connection.prepareStatement("SELECT $name(JSON('[1]'), JSON('[1]')) FROM DUAL").use { statement -> statement.executeQuery().use { it.next() } }
        } catch (e: SQLException) {
            if (e.errorCode !in NO_FUNCTION) throw e
            false
        }
        if (found) {
            callable += key
            absent.remove(key)
        } else {
            absent[key] = now + ABSENT_FOR.toNanos()
        }
        if (!found && warned.add(key)) {
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

    /** Asks again, of every session, whether it may call the functions of [graph]: it has just been ensured. */
    fun ensured(graph: String) {
        absent.keys.removeIf { it.endsWith("|$graph") }
    }
}
