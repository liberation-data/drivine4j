package org.drivine.backend.oracle

import org.drivine.schema.StoreStampStatements
import org.drivine.schema.VersionMarkerStatements

/**
 * The schema marker's statements for a graph kept by Oracle: a node of the graph that carries the
 * marker's label, as on the graph engines. A property is read as a CLOB, since an inventory can run
 * past the 4000 characters a text is read as by default; the inventory itself is kept behind
 * [ITEMS_MARK], because Oracle keeps an empty string as null and an empty inventory is not no inventory.
 */
internal class OracleVersionMarker(private val graph: OracleGraph, private val label: String, private val scopeProperty: String) : VersionMarkerStatements {
    /** The predicate that finds the marker of the scope bound as `$scope`, among the nodes named [alias]. */
    private fun marker(alias: String = "n") = "${OracleSql.hasLabel(alias, label)} AND ${OracleSql.property(alias, scopeProperty)} = \$scope"

    /** The marker is a node of the graph, so the graph is made first where it is not yet. */
    override val prepare: String = graph.ensuring

    /** The order that puts the marker applied last first, and one never applied after every other. */
    private fun newestFirst(alias: String = "n") = "${OracleSql.property(alias, "appliedAt", SqlKind.NUMBER)} DESC NULLS LAST"

    override fun read(property: String): String =
        "SELECT JSON_VALUE(n.props, ${OracleSql.path(property)} RETURNING CLOB) FROM ${graph.nodes} n WHERE ${marker()} ORDER BY ${newestFirst()} FETCH FIRST 1 ROWS ONLY"

    /**
     * The moment is bound as `$now`: Oracle has no `timestamp()` of the graph engines' shape.
     *
     * The marker is found by a query and matched by its id. Matched by its scope, the statement would
     * update the column it matches on, which Oracle refuses (`ORA-38104`) unless an index over the
     * scope happens to stand in for the column, as the one beneath the marker's constraint does: an
     * owner with no version has no such constraint made for it. Where a race left several markers,
     * the newest is the one written.
     */
    override val merge: String = """
        MERGE INTO ${graph.nodes} n
        USING (SELECT (SELECT m.nid FROM ${graph.nodes} m WHERE ${marker("m")} ORDER BY ${newestFirst("m")}, m.nid DESC FETCH FIRST 1 ROWS ONLY) AS nid FROM dual) found
        ON (n.nid = found.nid)
        WHEN MATCHED THEN UPDATE SET n.props = JSON_MERGEPATCH(n.props, JSON_OBJECT('version' VALUE ${'$'}version, 'items' VALUE ${'$'}items, 'appliedAt' VALUE ${'$'}now))
        WHEN NOT MATCHED THEN INSERT (labels, props)
            VALUES (${OracleSql.literal("[\"$label\"]")}, JSON_OBJECT(${OracleSql.literal(scopeProperty)} VALUE ${'$'}scope, 'version' VALUE ${'$'}version, 'items' VALUE ${'$'}items, 'appliedAt' VALUE ${'$'}now ABSENT ON NULL))
    """.trimIndent()

    override fun mergeBindings(scope: String, version: String?, items: String): Map<String, Any?> =
        super.mergeBindings(scope, version, items) + ("now" to System.currentTimeMillis())

    override val deleteAllButNewest: String = """
        DELETE FROM ${graph.nodes} WHERE nid IN (
            SELECT nid FROM (SELECT n.nid, ROW_NUMBER() OVER (ORDER BY ${newestFirst()}, n.nid DESC) AS position FROM ${graph.nodes} n WHERE ${marker()})
            WHERE position > 1)
    """.trimIndent()

    override val delete: String = "DELETE FROM ${graph.nodes} n WHERE ${marker()}"

    override fun storedItems(items: String): String = ITEMS_MARK + items

    override fun items(stored: String): String = stored.removePrefix(ITEMS_MARK)

    companion object {
        /** What an inventory is kept behind, so that an empty one is read back as empty and not as absent. */
        const val ITEMS_MARK = "items:"
    }
}

/** The store's stamp on Oracle: a node of the graph, as on the graph engines. */
internal class OracleStoreStamp(private val graph: OracleGraph, label: String, key: String) : StoreStampStatements {
    /** The predicate that finds every stamp of the store. */
    private val stored = OracleSql.hasLabel("n", label)

    /** The stamp is a node of the graph, so the graph is made first where it is not yet. */
    override val prepare: String = graph.ensuring

    override val assignIfAbsent: String = """
        INSERT INTO ${graph.nodes} (labels, props)
        SELECT ${OracleSql.literal("[\"$label\"]")}, JSON_OBJECT(${OracleSql.literal(key)} VALUE ${'$'}key, 'storeId' VALUE ${'$'}storeId, 'assignedAt' VALUE ${'$'}assignedAt, 'engine' VALUE ${'$'}engine)
        FROM dual
        WHERE NOT EXISTS (SELECT 1 FROM ${graph.nodes} n WHERE $stored AND ${OracleSql.property("n", key)} = ${'$'}key)
    """.trimIndent()

    override val readStamps: String = """
        SELECT JSON_OBJECT('id' VALUE ${OracleSql.property("n", "storeId")}, 'assignedAt' VALUE ${OracleSql.property("n", "assignedAt")} RETURNING JSON)
        FROM ${graph.nodes} n WHERE $stored
        ORDER BY ${OracleSql.property("n", "assignedAt")}, ${OracleSql.property("n", "storeId")}
    """.trimIndent()
}
