package org.drivine.backend.oracle

import org.drivine.backend.oracle.OracleSql.literal

/**
 * A table beside the nodes that holds, for each node of a label, what an Oracle index can be over
 * and a JSON property cannot: an embedding as a `VECTOR`, or the text of some properties as a
 * document. A trigger on the node table keeps it in step with whatever writes a node, an object
 * manager or SQL by hand; a backfill fills it from the nodes that are there already. Each statement
 * is safe to run again.
 *
 * @param name the table's name; its trigger is [trigger] and the index over it [index]
 * @param nodes the node table
 * @param column the column that holds the payload, and [columnType] its type
 * @param payload the payload of the node whose row is named by the argument, as SQL
 * @param indexed whether the node whose row is named by the argument belongs in the table, as SQL
 * @param backfillFilter a further test the backfill makes of a node's payload, which the trigger makes by trying to write it; null for none
 * @param tolerant whether a payload the column refuses leaves its node out of the table, rather than failing the write of the node
 */
internal class OracleSideTable(
    val name: String,
    private val nodes: String,
    private val column: String,
    private val columnType: String,
    private val payload: (String) -> String,
    private val indexed: (String) -> String,
    private val backfillFilter: ((String) -> String)? = null,
    private val tolerant: Boolean = false,
) {
    val trigger: String = "${name}_t"
    val index: String = "${name}_i"

    /** Makes the table, when it is not there. */
    val create: String = "CREATE TABLE IF NOT EXISTS $name (nid NUMBER PRIMARY KEY REFERENCES $nodes (nid) ON DELETE CASCADE, $column $columnType NOT NULL)"

    /** The trigger that keeps the table in step with the nodes. It names the new row with a colon, which a driver reads as a parameter: see [triggerStatement]. */
    val triggerDdl: String = run {
        val write = """
            MERGE INTO $name s USING (SELECT :NEW.nid AS nid, ${payload(":NEW")} AS $column FROM dual) n ON (s.nid = n.nid)
            WHEN MATCHED THEN UPDATE SET s.$column = n.$column
            WHEN NOT MATCHED THEN INSERT (nid, $column) VALUES (n.nid, n.$column);
        """.trimIndent()
        val kept = if (tolerant) {
            """
            BEGIN
                ${write.replace("\n", "\n    ")}
            EXCEPTION WHEN OTHERS THEN
                -- Not a payload the column takes: the node is kept, and left out of the table, as the graph engines leave it out of the index.
                DELETE FROM $name WHERE nid = :NEW.nid;
            END;
            """.trimIndent()
        } else {
            write
        }
        """
        CREATE OR REPLACE TRIGGER $trigger AFTER INSERT OR UPDATE OF labels, props ON $nodes FOR EACH ROW
        BEGIN
            IF ${indexed(":NEW")} THEN
                ${kept.replace("\n", "\n        ")}
            ELSE
                DELETE FROM $name WHERE nid = :NEW.nid;
            END IF;
        END;
        """.trimIndent()
    }

    /** Makes the trigger, from inside a block, so that the `:NEW` it names is not read as a parameter. */
    val triggerStatement: String = "BEGIN EXECUTE IMMEDIATE ${literal(triggerDdl)}; END;"

    /** Fills the table from the nodes that are there and not in it yet. */
    val backfill: String = """
        INSERT INTO $name (nid, $column)
        SELECT n.nid, ${payload("n")} FROM $nodes n
        WHERE ${indexed("n")}${backfillFilter?.let { " AND ${it("n")}" }.orEmpty()}
          AND NOT EXISTS (SELECT 1 FROM $name s WHERE s.nid = n.nid)
    """.trimIndent()

    companion object {
        /** The statements that drop the table named [name] and its trigger; the index over it goes with the table. */
        fun dropping(name: String): List<String> = listOf("DROP TRIGGER IF EXISTS ${name}_t", "DROP TABLE IF EXISTS $name PURGE")
    }
}
