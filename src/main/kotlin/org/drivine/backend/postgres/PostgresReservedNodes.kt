package org.drivine.backend.postgres

import org.drivine.backend.oracle.OracleLowerCopies
import org.drivine.model.LowerCopies
import org.drivine.schema.LowerCopyStatements

/**
 * The lowercased copies on Postgres, as on Oracle: a property of the node's JSON, filled in by an
 * update of the label's nodes that Postgres lowercases with `lower`, and recorded in the graph's
 * catalog under the same kind.
 */
internal class PostgresLowerCopies(private val graph: PostgresGraph) : LowerCopyStatements {

    /** The copies are on the graph's nodes and their record in its catalog, so the graph is made first where it is not yet. */
    override val prepare: String = graph.ensuring

    override fun fill(label: String, property: String): String {
        val value = PostgresSql.property("m", property)
        val copy = PostgresSql.property("m", LowerCopies.of(property))
        val key = PostgresSql.literal(property)
        val copyKey = PostgresSql.literal(LowerCopies.of(property))
        return """
            WITH written AS (
                UPDATE ${graph.nodes} n
                SET props = CASE WHEN n.props ->> $key IS NULL THEN n.props - $copyKey
                                 ELSE n.props || jsonb_build_object($copyKey, lower(n.props ->> $key)) END
                WHERE n.nid IN (
                    SELECT m.nid FROM ${graph.nodes} m
                    WHERE ${PostgresSql.hasLabel("m", label)} AND $copy IS DISTINCT FROM lower($value)
                    LIMIT CAST(${'$'}batch AS int))
                RETURNING 1)
            SELECT count(*) FROM written
        """.trimIndent()
    }

    override val record: String = """
        INSERT INTO ${graph.catalog} (kind, label, properties, name, object_name, details)
        VALUES ('${OracleLowerCopies.KIND}', CAST(${'$'}label AS text), CAST(${'$'}properties AS text), NULL, NULL, '{}')
        ON CONFLICT (kind, label, properties) DO NOTHING
    """.trimIndent()

    override val read: String =
        "SELECT jsonb_build_object('label', label, 'properties', properties::jsonb) FROM ${graph.catalog} WHERE kind = '${OracleLowerCopies.KIND}'"

    override fun entries(rows: List<Any?>): Set<Pair<String, String>> = OracleLowerCopies.catalogEntries(rows)
}
