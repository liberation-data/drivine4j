package org.drivine.backend.postgres

import org.drivine.backend.sql.DdlContention
import org.drivine.backend.sql.SqlGraph
import org.drivine.cypher.store.ddl.PostgresDdl
import org.drivine.cypher.store.ddl.PostgresWriteFunctions
import org.drivine.manager.PersistenceManager

/**
 * A graph as Postgres keeps it: the tables of a [SqlGraph], with `labels` and `props` as `jsonb`, and
 * the functions it is written through (see [PostgresWriteFunctions]).
 *
 * The labels are a JSON array, as on Oracle, under a GIN index that answers whether a node carries a
 * label (`labels @> '["Person"]'`). A node carries as many as it likes: a label is data, not a table.
 *
 * Names are written bare, so Postgres folds them to lower case: a graph called `Contracts` keeps its
 * nodes in `contracts_node`, and is the same graph as one called `contracts`.
 *
 * The tables, the functions and the block that makes them are the module's ([PostgresDdl]); running the
 * block, again when another session's writes turn it away, is drivine4j's ([ensure]).
 */
internal class PostgresGraph(name: String) : SqlGraph(name) {

    override val store = PostgresDdl(name)

    override val tablesMade = "SELECT count(*) AS made FROM pg_catalog.pg_class c WHERE c.oid = to_regclass('${store.nodes.lowercase()}')"

    /** The functions the graph is written through. */
    val writes: PostgresWriteFunctions = store.writes

    /** What makes the store, when it is not there. Each statement is safe to run again. */
    val ddl: List<String> = store.ddl

    /**
     * The block that makes the store when it is not there, and does nothing when it is, under a lock of
     * the graph's own: see [PostgresDdl.ensuring].
     */
    val ensuring: String = store.ensuring

    /**
     * Runs [ensuring], again after a pause when Postgres turned it away as a deadlock (`40P01`) with other sessions'
     * writes, as the block's statements over tables already in use may meet (see [DdlContention]). The block runs in one
     * transaction, so a block turned away made nothing, and run again it makes the store, or finds it made (#450).
     */
    override fun ensure(run: (String) -> List<Any?>) {
        DdlContention.whenNotContended { run(ensuring) }
    }

    companion object {
        /** Why [name] cannot name a graph here; null where it can (see [PostgresDdl.refusal]). */
        fun refusal(name: String): String? = PostgresDdl.refusal(name)

        /** What the node table's comment says of a store this version made. */
        const val VERSION = PostgresDdl.VERSION

        /** The graph [persistenceManager] speaks to, made in the store if it is not there. */
        fun ensured(persistenceManager: PersistenceManager): PostgresGraph =
            PostgresGraph(persistenceManager.graphName ?: DEFAULT_NAME).also { it.ensure(persistenceManager) }
    }
}
