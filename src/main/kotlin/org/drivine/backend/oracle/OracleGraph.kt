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
            "'$name' cannot name a graph on Oracle: it names tables, so it is letters, digits and underscores, begins with a letter, and is at most $MAX_NAME long, so that what it names fits in Oracle's 128."
        }
    }

    val nodes = "${name}_node"
    val edges = "${name}_edge"

    /** What Drivine made of indexes and constraints: see [OracleSchemaGrammar]. */
    val catalog = "${name}_schema"

    /** The table that holds the text a full-text index on [label]'s [properties] is over. */
    fun texts(label: String, properties: List<String>): String = objectName("t", listOf(label) + properties)

    /** The table that holds the embeddings a vector index on [label]'s [property] is over. */
    fun vectors(label: String, property: String): String = objectName("v", listOf(label, property))

    /**
     * A name for an object of [kind] made for [parts], a label and properties: the graph's name, the
     * kind, what of the parts reads as a name, and a hash of the parts whole, so that two objects
     * made for different parts are never one. It leaves room for the `_t` or `_i` an object made
     * beside it adds, within the [MAX_OBJECT_NAME] Oracle allows.
     */
    fun objectName(kind: String, parts: List<String>): String {
        val hash = Integer.toHexString(parts.joinToString("\u0000").hashCode())
        val fixed = "${name}_${kind}__${hash}".length + BESIDE.length
        val readable = parts.joinToString("") { it.replace(Regex("[^A-Za-z0-9]"), "") }.take((MAX_OBJECT_NAME - fixed).coerceIn(0, 40))
        return "${name}_${kind}_${readable}_$hash"
    }

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
        """
        CREATE TABLE IF NOT EXISTS $catalog (
            kind        VARCHAR2(40) NOT NULL,
            label       VARCHAR2(400) NOT NULL,
            properties  VARCHAR2(2000) NOT NULL,
            name        VARCHAR2(400),
            object_name VARCHAR2(128),
            details     JSON NOT NULL,
            PRIMARY KEY (kind, label, properties)
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
     * The block that makes the store when it is not there, and does nothing when it is. The
     * property graph is made last, so one that is there says the rest is. It is looked for before
     * anything is run: a schema statement wants its table to itself, and would be turned away by a
     * writer at work on a store that needs nothing made. One that another process made in the
     * meantime (`ORA-00955`) is taken as made.
     */
    val ensuring: String = """
        DECLARE
            v_made NUMBER;
            e_exists EXCEPTION;
            PRAGMA EXCEPTION_INIT(e_exists, -955);
        BEGIN
            SELECT COUNT(*) INTO v_made FROM user_property_graphs WHERE graph_name = '${name.uppercase()}';
            IF v_made = 0 THEN
        ${ddl.joinToString("\n") { "        BEGIN EXECUTE IMMEDIATE ${OracleSql.literal(it)}; EXCEPTION WHEN e_exists THEN NULL; END;" }}
            END IF;
        END;
    """.trimIndent()

    /**
     * Makes the store if it is not there. Schema statements commit as they run, so they go on a
     * connection of their own and never on the caller's transaction.
     */
    fun ensure(persistenceManager: PersistenceManager) {
        persistenceManager.indexes.run(ensuring)
    }

    companion object {
        const val DEFAULT_NAME = JdbcConnectionProvider.DEFAULT_GRAPH

        /** Oracle names are at most 128 characters. */
        private const val MAX_OBJECT_NAME = 128

        /** The longest an object made beside another adds to its name: a trigger's `_t`, an index's `_i`. */
        private const val BESIDE = "_t"

        /**
         * The longest a graph's name can be, so that what it names fits: the longest it adds is a
         * table beside the nodes, `_v__` and a hash with nothing readable, and that table's trigger.
         */
        private const val MAX_NAME = MAX_OBJECT_NAME - ("_v__".length + 8 + BESIDE.length)
        private val NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,${MAX_NAME - 1}}")

        /** The graph [persistenceManager] speaks to, made in the store if it is not there. */
        fun ensured(persistenceManager: PersistenceManager): OracleGraph =
            OracleGraph(persistenceManager.graphName ?: DEFAULT_NAME).also { it.ensure(persistenceManager) }
    }
}
