package org.drivine.backend.oracle

import org.drivine.cypher.sql.OracleCypherCompiler
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a session may call the functions a graph's store keeps beside its tables (see [OracleGraph.functions]). A store
 * made by an earlier version lacks those added since until the graph is ensured again, and a user who may not make a function
 * (`CREATE PROCEDURE`) has none at all: a Cypher statement is then compiled to call none of those it lacks, and says so
 * once for each graph and function.
 *
 * Asked only by a statement that would call one. A session that may call it is remembered for the database, its user and
 * the graph, for as long as the JVM runs: a function dropped after that is not noticed, and a statement that calls it fails
 * until the graph is ensured again (or the JVM restarts). One that may not is remembered for [ABSENT_FOR], or until the
 * graph is ensured in this JVM (see [OracleGraph.ensure]), so a store without the function is not asked on every statement,
 * and a graph ensured later still gains it.
 */
internal object OracleStoredFunctions {

    private val logger = LoggerFactory.getLogger(OracleStoredFunctions::class.java)

    /** The function, and the database, user and graph, of each session found to call it. */
    private val callable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The function, and the database, user and graph, of each session found to call none, and when ([clock]) to ask again. */
    private val absent: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /** The function, and the database, user and graph, of each session that was said to have none. */
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
        val name = OracleCypherCompiler.nullsEqualName(graph)
        return callable(connection, graph, name, "SELECT $name(JSON('[1]'), JSON('[1]')) FROM DUAL") {
            "Until the graph is ensured by such a user, a Cypher statement that writes, or the step of a reduce(), compares two " +
                "lists or maps that hold a null and differ as JSON as false, where Cypher may give null."
        }
    }

    /**
     * Whether [connection] may call both the functions the graph [graph] renumbers a year that may be BC by, as [nullsEqual]
     * asks of its own. They are asked of together, as a statement that calls either is compiled to call neither where the
     * session may not call one: the graph is made with both at once, and a store that has neither gives the same rows,
     * planned more slowly. They are asked of apart from [nullsEqual]'s, which a store made by a version before them has.
     *
     * @throws SQLException where calling them fails for any reason but there being no function it may call
     */
    fun era(connection: Connection, graph: String): Boolean {
        val era = OracleCypherCompiler.eraName(graph)
        val eraDate = OracleCypherCompiler.eraDateName(graph)
        return callable(connection, graph, "$era and $eraDate", "SELECT $era('0000-01-01'), $eraDate('-0001-01-01') FROM DUAL") {
            "Until the graph is ensured by such a user, Oracle takes longer to plan a Cypher statement that reads or writes " +
                "a date or a moment whose year may be before 1000; it gives the same rows."
        }
    }

    /** Whether [connection] may call [name], the function or functions of [graph] that [query] calls, saying [lost] once where it may not. */
    private fun callable(connection: Connection, graph: String, name: String, query: String, lost: () -> String): Boolean {
        val key = "$name|${connection.metaData.url}|${connection.metaData.userName}|$graph"
        if (key in callable) return true
        val now = clock()
        if (absent[key]?.let { now - it < 0 } == true) return false
        val found = try {
            connection.prepareStatement(query).use { statement -> statement.executeQuery().use { it.next() } }
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
                    "(CREATE PROCEDURE, which the RESOURCE role grants). {}",
                graph, name, lost(),
            )
        }
        return found
    }

    /** Asks again, of every session, whether it may call the functions of [graph]: it has just been ensured. */
    fun ensured(graph: String) {
        absent.keys.removeIf { it.endsWith("|$graph") }
    }
}
