package org.drivine.backend.oracle

import org.drivine.backend.sql.DdlContention
import org.drivine.backend.sql.SqlGraph
import org.drivine.cypher.store.ddl.OracleDdl
import org.drivine.manager.PersistenceManager

/**
 * A graph as Oracle keeps it: the tables of a [SqlGraph], and the SQL property graph over the two
 * that reads go through.
 *
 * A relationship's type is a column. The property graph therefore has one vertex label and one edge
 * label, and what Cypher writes as a label or a type is a predicate on `labels` or `type`.
 *
 * The graph gives each node one more property, `doc`: the node as the map a load returns for it,
 * its properties with its labels and its stamp among them. A read projects `v.doc` and is no longer
 * concerned with how the map is put together.
 *
 * The tables, the property graph, the functions a compiled statement calls and the block that makes them
 * are the module's ([OracleDdl]). Running the block, again when another session's DDL turns it away, and
 * asking which of the functions a session may call ([OracleStoredFunctions]) are drivine4j's ([ensure]).
 */
internal class OracleGraph(name: String) : SqlGraph(name) {

    override val store = OracleDdl(name)

    override val tablesMade = "SELECT COUNT(*) AS made FROM user_tables WHERE table_name = UPPER('${store.nodes}')"

    /** What makes the store, when it is not there. Each statement is safe to run again. */
    val ddl: List<String> = store.ddl

    /** The function a compiled Cypher statement compares two values that may hold a null by: see [OracleDdl.nullsEqual]. */
    val nullsEqual: String = store.nullsEqual

    /** The functions a compiled Cypher statement renumbers a year that may be BC by: see [OracleDdl.era]. */
    val era: String = store.era

    /** See [era]. */
    val eraDate: String = store.eraDate

    /** The function a compiled Cypher statement works out a temporal value's sort key by, called where [era]'s are: see [OracleDdl.temporalKey]. */
    val temporalKey: String = store.temporalKey

    /** The function a compiled Cypher statement works out `+`, `-`, `*` and `/` of a temporal value read from the store by: see [OracleDdl.temporalArithmetic]. */
    val temporalArithmetic: String = store.temporalArithmetic

    /** What makes each function the store keeps: safe to run again. */
    val functions: List<String> = store.functions

    /**
     * The block that makes the store when it is not there, and does nothing when it is, waiting out a
     * lock another session holds for up to [BUSY_ATTEMPTS] tries: see [OracleDdl.ensuring]. A user who
     * may not make a function still has the store made, and a statement then calls none of those it
     * lacks (see `JdbcConnection`).
     */
    val ensuring: String = store.ensuring

    /**
     * Runs [ensuring]. Schema statements commit as they run. A session found to call none of the
     * [functions] is asked again (see [OracleStoredFunctions]).
     *
     * A block turned away by a deadlock with another session's is run again after a pause (see [DdlContention]): as when
     * the DDL of one pool's first connection and other pools' first writes each wait on the other's library cache lock
     * on the node table (`ORA-04020`), and Oracle turns one away (#450). A busy lock (`ORA-00054`) is not: the block has
     * waited it out for as long as it allows already. What the block made before it was turned away
     * stays, and run again it makes the rest, or finds the property graph another session finished meanwhile and does
     * nothing.
     */
    override fun ensure(run: (String) -> List<Any?>) {
        DdlContention.whenNotContended { run(ensuring) }
        OracleStoredFunctions.ensured(name)
    }

    companion object {
        /** Why [name] cannot name a graph here; null where it can (see [OracleDdl.refusal]). */
        fun refusal(name: String): String? = OracleDdl.refusal(name)

        /** The graph of a manager that names none. */
        const val DEFAULT_NAME = SqlGraph.DEFAULT_NAME

        /** How many times [ensuring] tries a statement that another session's lock turns away: thirty seconds' worth. */
        const val BUSY_ATTEMPTS = OracleDdl.BUSY_ATTEMPTS

        /** The graph [persistenceManager] speaks to, made in the store if it is not there. */
        fun ensured(persistenceManager: PersistenceManager): OracleGraph =
            OracleGraph(persistenceManager.graphName ?: DEFAULT_NAME).also { it.ensure(persistenceManager) }
    }
}
