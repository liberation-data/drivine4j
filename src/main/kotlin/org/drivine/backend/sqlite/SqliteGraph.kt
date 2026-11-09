package org.drivine.backend.sqlite

import org.drivine.backend.sql.SqlGraph
import org.drivine.connection.ReusedIdsException
import org.drivine.manager.PersistenceManager

/**
 * A graph as SQLite keeps it: the tables of a [SqlGraph], with `labels`, `props` and a relationship's
 * `props` as JSON text. Text and not SQLite's binary `jsonb`, so that Drivine for TypeScript and for
 * Python read the same file as it is.
 *
 * There is no index over the labels: SQLite has none that answers whether a JSON array holds a value.
 * A label is tested on the text of the array (see [SqliteSql.hasLabel]), which a partial index of the
 * schema grammar answers for the nodes of a label (see [SqliteSchemaGrammar]).
 *
 * Every statement here is safe to run again, so the store is made by running them all where it is not
 * all there, on a provider's first connection (see [org.drivine.connection.JdbcConnectionProvider])
 * and each time a backend is made: SQLite opens a transaction for a schema statement as for any other,
 * and two processes that each find the store missing make it one after the other.
 *
 * A node's `nid` and a relationship's `eid` are `AUTOINCREMENT` keys, so an id is never given again once its row
 * is deleted, as Oracle's and Postgres's identity keys are never given again: without it SQLite gives a new row the
 * greatest key plus one, which is the key of the last row deleted when it had the greatest (#713). A file whose
 * tables were made without it, by an earlier Drivine, is refused as the store is looked for (see [ensure]): there is
 * no migration, as SQLite cannot change a table's key in place.
 *
 * SQLite reads a table's name without regard to case, so a graph called `Contracts` is the same graph as
 * one called `contracts`, as on Postgres, which folds both to lower case. The tables keep the case of the
 * name that made them.
 */
internal class SqliteGraph(name: String) : SqlGraph(name, ENGINE, MAX_OBJECT_NAME) {

    /** What makes the store, when it is not there. */
    val ddl: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $nodes (
            nid    INTEGER PRIMARY KEY AUTOINCREMENT,
            labels TEXT NOT NULL,
            props  TEXT NOT NULL,
            stamp  TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $edges (
            eid   INTEGER PRIMARY KEY AUTOINCREMENT,
            src   INTEGER NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            dst   INTEGER NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            type  TEXT NOT NULL,
            props TEXT NOT NULL DEFAULT '{}'
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $catalog (
            kind        TEXT NOT NULL,
            label       TEXT NOT NULL,
            properties  TEXT NOT NULL,
            name        TEXT,
            object_name TEXT,
            details     TEXT NOT NULL,
            PRIMARY KEY (kind, label, properties)
        )
        """,
        "CREATE INDEX IF NOT EXISTS ${name}_edge_src ON $edges (src, type)",
        "CREATE INDEX IF NOT EXISTS ${name}_edge_dst ON $edges (dst, type)",
    ).map { it.trimIndent() }

    /**
     * Counts what of the [ddl] makes is there: each table and index, by its name in any case. A user
     * who may only read the file, or a connection with `query_only` set, is refused a `CREATE ... IF
     * NOT EXISTS` even over a table that is there, so the store is looked for first.
     *
     * The count is [REUSES_IDS] where the table of nodes or of relationships is there and was made without
     * `AUTOINCREMENT`, as SQLite keeps the statement that made it: one look finds both. It looks for `KEY AUTOINCREMENT`,
     * so that a graph whose name holds the word is not taken for one made with it.
     */
    val made: String = run {
        val names = ddl.map { Regex("IF NOT EXISTS (\\w+)").find(it)!!.groupValues[1].lowercase() }.joinToString(", ") { "'$it'" }
        val keyed = listOf(nodes, edges).joinToString(", ") { "'${it.lowercase()}'" }
        "SELECT CASE WHEN EXISTS (SELECT 1 FROM sqlite_master WHERE type = 'table' AND lower(name) IN ($keyed) AND sql NOT LIKE '%KEY AUTOINCREMENT%')" +
            " THEN $REUSES_IDS ELSE COUNT(*) END FROM sqlite_master WHERE lower(name) IN ($names)"
    }

    /**
     * Runs the [ddl], each statement on its own, unless [made] finds all it makes there. A store whose tables
     * give a deleted row's id again is refused with [ReusedIdsException], naming its file ([FILE]), before anything
     * is made.
     */
    override fun ensure(run: (String) -> List<Any?>) {
        val found = refused(run)
        if (found == ddl.size) return
        ddl.forEach { run(it) }
    }

    /** A store whose tables give a deleted row's id again, refused with [ReusedIdsException] as [ensure] refuses it. */
    override fun refuse(run: (String) -> List<Any?>) {
        refused(run)
    }

    /** What [made] counts, once a store whose tables give a deleted row's id again is refused. */
    private fun refused(run: (String) -> List<Any?>): Int? {
        val found = (run(made).singleOrNull() as? Number)?.toInt()
        if (found == REUSES_IDS) throw ReusedIdsException(name, (run(FILE).singleOrNull() as? String)?.ifEmpty { null })
        return found
    }

    companion object {
        /** The store's name, for what is said of a graph name it cannot take. */
        private const val ENGINE = "SQLite"

        /** Why [name] cannot name a graph here; null where it can (see [SqlGraph.refusal]). */
        fun refusal(name: String): String? = SqlGraph.refusal(name, ENGINE, MAX_OBJECT_NAME)

        /** What [made] gives for a store whose tables give a deleted row's id again: see [ReusedIdsException]. */
        internal const val REUSES_IDS = -1

        /** The file of the database a connection is on: an empty text for an in-memory or temporary one, which [ReusedIdsException] holds as null. */
        internal const val FILE = "SELECT file FROM pragma_database_list WHERE name = 'main'"

        /** SQLite takes a name of any length; this keeps one as long as the other stores' do. */
        private const val MAX_OBJECT_NAME = 63

        /** The graph [persistenceManager] speaks to, made in the store if it is not there. */
        fun ensured(persistenceManager: PersistenceManager): SqliteGraph =
            SqliteGraph(persistenceManager.graphName ?: DEFAULT_NAME).also { it.ensure(persistenceManager) }
    }
}
