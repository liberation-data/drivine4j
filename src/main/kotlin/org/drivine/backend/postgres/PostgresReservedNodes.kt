package org.drivine.backend.postgres

import org.drivine.schema.StoreStampStatements
import org.drivine.schema.VersionMarkerStatements

/**
 * The schema marker's statements for a graph kept by Postgres: a node of the graph that carries the
 * marker's label, as on the graph engines and on Oracle. Postgres keeps an empty string apart from
 * null, so an empty inventory is kept as it is.
 *
 * A value is bound without a type, so each is cast where a function would otherwise have to guess it.
 */
internal class PostgresVersionMarker(private val graph: PostgresGraph, private val label: String, private val scopeProperty: String) : VersionMarkerStatements {
    /** The predicate that finds the marker of the scope bound as `$scope`, among the nodes named [alias]. */
    private fun marker(alias: String = "n") = "${PostgresSql.hasLabel(alias, label)} AND ${PostgresSql.property(alias, scopeProperty)} = CAST(\$scope AS text)"

    /** The marker is a node of the graph, so the graph is made first where it is not yet. */
    override val prepare: String = graph.ensuring

    /**
     * The order that puts the marker applied last first, and one never applied after every other. Of
     * two applied in one moment the one made later is first, so the marker read, the one written and
     * the one kept are the same marker.
     */
    private fun newestFirst(alias: String = "n") = "${PostgresSql.number(alias, "appliedAt")} DESC NULLS LAST, $alias.nid DESC"

    override fun read(property: String): String =
        "SELECT ${PostgresSql.property("n", property)} FROM ${graph.nodes} n WHERE ${marker()} ORDER BY ${newestFirst()} LIMIT 1"

    /**
     * The moment is bound as `$now`, as on Oracle. The newest marker of the scope is written, and one
     * is made where there is none. A version that is null removes the one the marker had.
     */
    override val merge: String = """
        WITH found AS (SELECT m.nid FROM ${graph.nodes} m WHERE ${marker("m")} ORDER BY ${newestFirst("m")} LIMIT 1),
        written AS (
            UPDATE ${graph.nodes} n
            SET props = jsonb_strip_nulls(n.props || jsonb_build_object('version', CAST(${'$'}version AS text), 'items', CAST(${'$'}items AS text), 'appliedAt', CAST(${'$'}now AS bigint)))
            FROM found WHERE n.nid = found.nid
            RETURNING n.nid)
        INSERT INTO ${graph.nodes} (labels, props)
        SELECT ${PostgresSql.labelArray(label)},
               jsonb_strip_nulls(jsonb_build_object(${PostgresSql.literal(scopeProperty)}, CAST(${'$'}scope AS text), 'version', CAST(${'$'}version AS text), 'items', CAST(${'$'}items AS text), 'appliedAt', CAST(${'$'}now AS bigint)))
        WHERE NOT EXISTS (SELECT 1 FROM found)
    """.trimIndent()

    override fun mergeBindings(scope: String, version: String?, items: String): Map<String, Any?> =
        super.mergeBindings(scope, version, items) + ("now" to System.currentTimeMillis())

    override val deleteAllButNewest: String = """
        DELETE FROM ${graph.nodes} WHERE nid IN (
            SELECT nid FROM (SELECT n.nid, row_number() OVER (ORDER BY ${newestFirst()}) AS position FROM ${graph.nodes} n WHERE ${marker()}) ranked
            WHERE position > 1)
    """.trimIndent()

    override val delete: String = "DELETE FROM ${graph.nodes} n WHERE ${marker()}"
}

/** The store's stamp on Postgres: a node of the graph, as on the graph engines. */
internal class PostgresStoreStamp(private val graph: PostgresGraph, label: String, key: String) : StoreStampStatements {
    /** The predicate that finds every stamp of the store. */
    private val stored = PostgresSql.hasLabel("n", label)

    /** The stamp is a node of the graph, so the graph is made first where it is not yet. */
    override val prepare: String = graph.ensuring

    override val assignIfAbsent: String = """
        INSERT INTO ${graph.nodes} (labels, props)
        SELECT ${PostgresSql.labelArray(label)},
               jsonb_build_object(${PostgresSql.literal(key)}, CAST(${'$'}key AS text), 'storeId', CAST(${'$'}storeId AS text), 'assignedAt', CAST(${'$'}assignedAt AS text), 'engine', CAST(${'$'}engine AS text))
        WHERE NOT EXISTS (SELECT 1 FROM ${graph.nodes} n WHERE $stored AND ${PostgresSql.property("n", key)} = CAST(${'$'}key AS text))
    """.trimIndent()

    override val readStamps: String = """
        SELECT jsonb_build_object('id', ${PostgresSql.property("n", "storeId")}, 'assignedAt', ${PostgresSql.property("n", "assignedAt")})
        FROM ${graph.nodes} n WHERE $stored
        ORDER BY ${PostgresSql.property("n", "assignedAt")}, ${PostgresSql.property("n", "storeId")}
    """.trimIndent()
}
