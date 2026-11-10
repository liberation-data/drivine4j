package org.drivine.backend.oracle

import org.drivine.model.LowerCopies
import org.drivine.schema.LowerCopyStatements

/**
 * The lowercased copies on Oracle. A copy is a property of the node's JSON, filled in by an update of
 * the label's nodes that Oracle lowercases with `LOWER`, and removed where the property has no value,
 * which an empty text is on Oracle. The record of every node of a label having it is a row of the
 * graph's catalog, of kind [KIND], with the property as the one item of `properties`.
 */
internal class OracleLowerCopies(private val graph: OracleGraph) : LowerCopyStatements {

    /** The copies are on the graph's nodes and their record in its catalog, so the graph is made first where it is not yet. */
    override val prepare: String = graph.ensuring

    /**
     * The number of nodes written is handed back as a row, from a block of its own. The property and
     * its copy are read as `CLOB`s, so a text past the 4,000 bytes `JSON_VALUE` reads by default has
     * its copy too; each is compared with a space after it, which makes a missing copy, a null and an
     * empty text alike.
     */
    override fun fill(label: String, property: String): String {
        val value = "JSON_VALUE(n.props, ${OracleSql.path(property)} RETURNING CLOB)"
        val copy = "JSON_VALUE(n.props, ${OracleSql.path(LowerCopies.of(property))} RETURNING CLOB)"
        return """
            DECLARE
                v_written NUMBER;
                v_result  SYS_REFCURSOR;
            BEGIN
                UPDATE ${graph.nodes} n
                SET n.props = JSON_TRANSFORM(n.props, SET ${OracleSql.path(LowerCopies.of(property))} = LOWER($value) REMOVE ON NULL)
                WHERE ${OracleSql.hasLabel("n", label)}
                  AND DBMS_LOB.COMPARE($copy || ' ', LOWER($value) || ' ') <> 0
                  AND ROWNUM <= ${'$'}batch;
                v_written := SQL%ROWCOUNT;
                OPEN v_result FOR SELECT v_written FROM dual;
                DBMS_SQL.RETURN_RESULT(v_result);
            END;
        """.trimIndent()
    }

    /** Of two processes recording it at once, the second is refused on the catalog's key, and that is taken as recorded. */
    override val record: String = """
        BEGIN
            MERGE INTO ${graph.catalog} c
            USING (SELECT '$KIND' AS kind, ${'$'}label AS label, ${'$'}properties AS properties FROM dual) k
            ON (c.kind = k.kind AND c.label = k.label AND c.properties = k.properties)
            WHEN NOT MATCHED THEN INSERT (kind, label, properties, name, object_name, details) VALUES (k.kind, k.label, k.properties, NULL, NULL, '{}');
        EXCEPTION WHEN DUP_VAL_ON_INDEX THEN
            NULL;
        END;
    """.trimIndent()

    override val read: String =
        "SELECT JSON_OBJECT('label' VALUE label, 'properties' VALUE JSON(properties) RETURNING JSON) FROM ${graph.catalog} WHERE kind = '$KIND'"

    override fun entries(rows: List<Any?>): Set<Pair<String, String>> = catalogEntries(rows)

    companion object {
        /** The kind of a catalog row that records a copy filled in. */
        const val KIND = "LOWER_COPY"

        /** The label and property of each catalog row among [rows], each a map of its `label` and its `properties`. */
        fun catalogEntries(rows: List<Any?>): Set<Pair<String, String>> = rows.mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            val property = (map["properties"] as? List<*>)?.singleOrNull()?.toString() ?: return@mapNotNull null
            (map["label"] as? String)?.let { it to property }
        }.toSet()
    }
}
