package org.drivine.backend.sqlite

import org.drivine.backend.sql.SqlGraph
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
 * SQLite reads a table's name without regard to case, so a graph called `Contracts` is the same graph as
 * one called `contracts`, as on Postgres, which folds both to lower case. The tables keep the case of the
 * name that made them.
 */
internal class SqliteGraph(name: String) : SqlGraph(name, "SQLite", MAX_OBJECT_NAME) {

    /** What makes the store, when it is not there. */
    val ddl: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $nodes (
            nid    INTEGER PRIMARY KEY,
            labels TEXT NOT NULL,
            props  TEXT NOT NULL,
            stamp  TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $edges (
            eid   INTEGER PRIMARY KEY,
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
     */
    val made: String = ddl.map { Regex("IF NOT EXISTS (\\w+)").find(it)!!.groupValues[1].lowercase() }
        .joinToString(", ", "SELECT COUNT(*) FROM sqlite_master WHERE lower(name) IN (", ")") { "'$it'" }

    /** Runs the [ddl], each statement on its own, unless [made] finds all it makes there. */
    override fun ensure(run: (String) -> List<Any?>) {
        if ((run(made).singleOrNull() as? Number)?.toInt() == ddl.size) return
        ddl.forEach { run(it) }
    }

    companion object {
        /** SQLite takes a name of any length; this keeps one as long as the other stores' do. */
        private const val MAX_OBJECT_NAME = 63

        /** The graph [persistenceManager] speaks to, made in the store if it is not there. */
        fun ensured(persistenceManager: PersistenceManager): SqliteGraph =
            SqliteGraph(persistenceManager.graphName ?: DEFAULT_NAME).also { it.ensure(persistenceManager) }
    }
}
