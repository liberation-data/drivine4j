package org.drivine.backend.sqlite

import org.drivine.schema.VersionMarkerStatements

/**
 * The schema marker's statements for a graph kept by SQLite: a node of the graph that carries the
 * marker's label, as on the graph engines, Oracle and Postgres. SQLite keeps an empty string apart from
 * null, so an empty inventory is kept as it is.
 */
internal class SqliteVersionMarker(private val graph: SqliteGraph, private val label: String, private val scopeProperty: String) : VersionMarkerStatements {
    /** The predicate that finds the marker of the scope bound as `$scope`, among the nodes named [alias]. */
    private fun marker(alias: String = "n") = "${SqliteSql.hasLabel(alias, label)} AND ${SqliteSql.property(alias, scopeProperty)} = CAST(\$scope AS TEXT)"

    /** The marker is a node of the graph, so the table of nodes is made first where it is not yet. */
    override val prepare: String = graph.ddl.first()

    /**
     * The order that puts the marker applied last first, and one never applied after every other. Of
     * two applied in one moment the one made later is first, so the marker read, the one written and
     * the one kept are the same marker.
     */
    private fun newestFirst(alias: String = "n") = "${SqliteSql.number(alias, "appliedAt")} DESC NULLS LAST, $alias.nid DESC"

    override fun read(property: String): String =
        "SELECT ${SqliteSql.property("n", property)} FROM ${graph.nodes} n WHERE ${marker()} ORDER BY ${newestFirst()} LIMIT 1"

    /**
     * The moment is bound as `$now`, as on Oracle and Postgres. The newest marker of the scope is written,
     * and one is made where there is none: the row is inserted with the `nid` of the marker found, or none,
     * and a marker that is there is written instead. A merge patch leaves out what is null, so a version
     * that is null removes the one the marker had.
     */
    override val merge: String = """
        INSERT INTO ${graph.nodes} (nid, labels, props)
        SELECT (SELECT m.nid FROM ${graph.nodes} m WHERE ${marker("m")} ORDER BY ${newestFirst("m")} LIMIT 1),
               ${SqliteSql.labelArray(label)},
               json_patch('{}', json_object(${SqliteSql.literal(scopeProperty)}, CAST(${'$'}scope AS TEXT), 'version', ${'$'}version, 'items', ${'$'}items, 'appliedAt', ${'$'}now))
        WHERE 1
        ON CONFLICT (nid) DO UPDATE SET props = json_patch(props, json_object('version', ${'$'}version, 'items', ${'$'}items, 'appliedAt', ${'$'}now))
    """.trimIndent()

    override fun mergeBindings(scope: String, version: String?, items: String): Map<String, Any?> =
        super.mergeBindings(scope, version, items) + ("now" to System.currentTimeMillis())

    override val deleteAllButNewest: String = """
        DELETE FROM ${graph.nodes} WHERE nid IN (
            SELECT nid FROM (SELECT n.nid, row_number() OVER (ORDER BY ${newestFirst()}) AS position FROM ${graph.nodes} n WHERE ${marker()}) ranked
            WHERE position > 1)
    """.trimIndent()

    override val delete: String = "DELETE FROM ${graph.nodes} WHERE nid IN (SELECT n.nid FROM ${graph.nodes} n WHERE ${marker()})"
}
