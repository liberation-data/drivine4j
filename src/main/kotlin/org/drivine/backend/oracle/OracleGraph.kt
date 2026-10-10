package org.drivine.backend.oracle

import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.PersistenceManager

/**
 * A graph as Oracle keeps it: a table of nodes, a table of relationships, and the SQL property
 * graph over the two that reads go through.
 *
 * Labels and properties are data, so they are JSON: a node's labels are an array, its properties an
 * object keyed by the property names the graph engines use. A relationship's type is a column. The
 * property graph therefore has one vertex label and one edge label, and what Cypher writes as a
 * label or a type is a predicate on `labels` or `type`.
 *
 * A node's stamp is a column of its own and not a property: every save rewrites it, and it is read
 * back as the property the graph engines store it under.
 */
internal class OracleGraph(val name: String) {

    init {
        require(NAME.matches(name)) {
            "'$name' cannot name a graph on Oracle: it names tables, so it is letters, digits and underscores, begins with a letter, and is at most $MAX_NAME long."
        }
    }

    val nodes = "${name}_node"
    val edges = "${name}_edge"

    /** What makes the store, when it is not there. Each statement is safe to run again. */
    val ddl: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS $nodes (
            nid    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            labels JSON NOT NULL,
            props  JSON NOT NULL,
            stamp  VARCHAR2(33)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS $edges (
            eid   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
            src   NUMBER NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            dst   NUMBER NOT NULL REFERENCES $nodes (nid) ON DELETE CASCADE,
            type  VARCHAR2(128) NOT NULL,
            props JSON DEFAULT '{}' NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS ${name}_edge_src ON $edges (src, type)",
        "CREATE INDEX IF NOT EXISTS ${name}_edge_dst ON $edges (dst, type)",
        "CREATE MULTIVALUE INDEX IF NOT EXISTS ${name}_node_labels ON $nodes n (n.labels.string())",
        """
        CREATE PROPERTY GRAPH IF NOT EXISTS $name
            VERTEX TABLES ($nodes KEY (nid) LABEL node PROPERTIES (nid, labels, props, stamp))
            EDGE TABLES ($edges KEY (eid)
                SOURCE KEY (src) REFERENCES $nodes (nid)
                DESTINATION KEY (dst) REFERENCES $nodes (nid)
                LABEL edge PROPERTIES (eid, src, dst, type, props))
        """,
    ).map { it.trimIndent() }

    /**
     * Makes the store if it is not there. Schema statements commit as they run, so they go on a
     * connection of their own and never on the caller's transaction.
     *
     * The property graph is made last, so one that is there says the rest is. It is looked for
     * before anything is run: a schema statement wants its table to itself, and would be turned
     * away by a writer at work on a store that needs nothing made.
     */
    fun ensure(persistenceManager: PersistenceManager) {
        val made = persistenceManager.indexes.run("SELECT COUNT(*) FROM user_property_graphs WHERE graph_name = '${name.uppercase()}'")
        if ((made.single() as Number).toInt() == 0) ddl.forEach { persistenceManager.indexes.run(it) }
    }

    companion object {
        const val DEFAULT_NAME = JdbcConnectionProvider.DEFAULT_GRAPH

        /** Oracle names are at most 128 characters; the longest this adds is `_node_labels`. */
        private const val MAX_NAME = 100
        private val NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,${MAX_NAME - 1}}")
    }
}
