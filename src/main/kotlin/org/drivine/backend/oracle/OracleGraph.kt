package org.drivine.backend.oracle

import org.drivine.backend.sql.SqlGraph
import org.drivine.cypher.sql.OracleCypherCompiler
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
 */
internal class OracleGraph(name: String) : SqlGraph(name, "Oracle", MAX_OBJECT_NAME) {

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
        CREATE OR REPLACE PROPERTY GRAPH $name
            VERTEX TABLES ($nodes KEY (nid) LABEL node PROPERTIES (nid, labels, props, stamp, ${OracleSql.transform("")} AS doc))
            EDGE TABLES ($edges KEY (eid)
                SOURCE KEY (src) REFERENCES $nodes (nid)
                DESTINATION KEY (dst) REFERENCES $nodes (nid)
                LABEL edge PROPERTIES (eid, src, dst, type, props))
        """,
    ).map { it.trimIndent() }

    /**
     * The function a compiled Cypher statement compares two values that may hold a null by, list item by list item and
     * map key by map key (see `OracleCypherCompiler.nullsEqual`): a statement that writes is a PL/SQL block, whose queries
     * call no function of its own, so the store keeps it. Drivine for TypeScript and for Python make it byte for byte alike.
     */
    val nullsEqual: String = OracleCypherCompiler.nullsEqualName(name)

    /** What makes [nullsEqual]: safe to run again. */
    val functions: List<String> = listOf("CREATE OR REPLACE ${OracleCypherCompiler.nullsEqualFunction(nullsEqual)}")

    /**
     * The block that makes the store when it is not there, and does nothing when it is. The
     * property graph is made last, so one that is there, with the `doc` property this version gives
     * it, says the rest is. It is looked for before anything is run: a schema statement wants its
     * table to itself, and would be turned away by a writer at work on a store that needs nothing
     * made. A graph made by an earlier version is replaced, and keeps its tables. One that another
     * process made in the meantime (`ORA-00955`) is taken as made.
     *
     * The [functions] are made where they are not there, or not valid, so a store an earlier version made gains them. A user
     * who may not make a function (`ORA-01031`: making one takes `CREATE PROCEDURE`, which the `RESOURCE` role grants) still
     * has the store made: a statement then compares as it would without them (see `JdbcConnection`).
     */
    val ensuring: String = """
        DECLARE
            v_made NUMBER;
            e_exists EXCEPTION;
            PRAGMA EXCEPTION_INIT(e_exists, -955);
            e_denied EXCEPTION;
            PRAGMA EXCEPTION_INIT(e_denied, -1031);
        BEGIN
            SELECT COUNT(*) INTO v_made FROM user_pg_prop_definitions WHERE graph_name = '${name.uppercase()}' AND property_name = 'DOC';
            IF v_made = 0 THEN
        ${ddl.joinToString("\n") { "        BEGIN EXECUTE IMMEDIATE ${OracleSql.literal(it)}; EXCEPTION WHEN e_exists THEN NULL; END;" }}
            END IF;
            SELECT COUNT(*) INTO v_made FROM user_objects WHERE object_type = 'FUNCTION' AND object_name = '${nullsEqual.uppercase()}' AND status = 'VALID';
            IF v_made = 0 THEN
        ${functions.joinToString("\n") { "        BEGIN EXECUTE IMMEDIATE ${OracleSql.literal(it)}; EXCEPTION WHEN e_denied THEN NULL; END;" }}
            END IF;
        END;
    """.trimIndent()

    /**
     * Makes the store if it is not there. Schema statements commit as they run, so they go on a
     * connection of their own and never on the caller's transaction. A session found to call none of
     * the [functions] is asked again (see [OracleStoredFunctions]).
     */
    override fun ensure(persistenceManager: PersistenceManager) {
        persistenceManager.indexes.run(ensuring)
        OracleStoredFunctions.ensured(name)
    }

    companion object {
        /** The graph of a manager that names none. */
        const val DEFAULT_NAME = SqlGraph.DEFAULT_NAME

        /** Oracle names are at most 128 characters. */
        private const val MAX_OBJECT_NAME = 128

        /** The graph [persistenceManager] speaks to, made in the store if it is not there. */
        fun ensured(persistenceManager: PersistenceManager): OracleGraph =
            OracleGraph(persistenceManager.graphName ?: DEFAULT_NAME).also { it.ensure(persistenceManager) }
    }
}
