package org.drivine.backend.postgres

import org.drivine.backend.sql.SqlGraph
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
 */
internal class PostgresGraph(name: String) : SqlGraph(name, "Postgres", MAX_OBJECT_NAME) {

    /** The functions the graph is written through. */
    val writes = PostgresWriteFunctions(this)

    /** What makes the store, when it is not there. Each statement is safe to run again. */
    val ddl: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $nodes (
            nid    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            labels jsonb NOT NULL,
            props  jsonb NOT NULL,
            stamp  varchar(33)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $edges (
            eid   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            src   bigint NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            dst   bigint NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            type  varchar(128) NOT NULL,
            props jsonb NOT NULL DEFAULT '{}'
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $catalog (
            kind        varchar(40) NOT NULL,
            label       varchar(400) NOT NULL,
            properties  varchar(2000) NOT NULL,
            name        varchar(400),
            object_name varchar($MAX_OBJECT_NAME),
            details     jsonb NOT NULL,
            PRIMARY KEY (kind, label, properties)
        )
        """,
        "CREATE INDEX IF NOT EXISTS ${name}_edge_src ON $edges (src, type)",
        "CREATE INDEX IF NOT EXISTS ${name}_edge_dst ON $edges (dst, type)",
        "CREATE INDEX IF NOT EXISTS ${name}_node_labels ON $nodes USING GIN (labels jsonb_path_ops)",
    ).map { it.trimIndent() } + writes.ddl + "COMMENT ON TABLE $nodes IS '$VERSION'"

    /**
     * The block that makes the store when it is not there, and does nothing when it is. The node table's
     * comment, written last, says which version made the store: one this version made is left alone,
     * and the rest is made again over one an earlier version made, which keeps its rows and has its
     * functions replaced. The block takes a lock of its own for the graph first, so two processes that
     * each find the store missing make it one after the other, and the second makes nothing. Postgres
     * runs a block's schema statements in its transaction, so the store is made whole or not at all.
     */
    val ensuring: String = """
        DO ${'$'}ensure${'$'}
        BEGIN
            IF obj_description(to_regclass('$nodes'), 'pg_class') IS DISTINCT FROM '$VERSION' THEN
                PERFORM pg_advisory_xact_lock(hashtext('drivine:$name'));
        ${ddl.joinToString("\n") { "        ${it.replace("\n", "\n        ")};" }}
            END IF;
        END
        ${'$'}ensure${'$'}
    """.trimIndent()

    override fun ensure(persistenceManager: PersistenceManager) {
        persistenceManager.indexes.run(ensuring)
    }

    companion object {
        /** Postgres names are at most 63 characters: past that, it cuts them short without a word. */
        private const val MAX_OBJECT_NAME = 63

        /** What the node table's comment says of a store this version made. */
        const val VERSION = "drivine:2"

        /** The graph [persistenceManager] speaks to, made in the store if it is not there. */
        fun ensured(persistenceManager: PersistenceManager): PostgresGraph =
            PostgresGraph(persistenceManager.graphName ?: DEFAULT_NAME).also { it.ensure(persistenceManager) }
    }
}
