package org.drivine.backend.sqlite

import org.drivine.backend.oracle.OracleLowerCopies
import org.drivine.schema.LowerCopyStatements

/**
 * The lowercased copies on SQLite, as on Oracle and Postgres: a property of the node's JSON, filled in
 * by [SqliteLowerCopiesFunction], which lowercases it as [SqliteLowerFunction] does, and recorded in
 * the graph's catalog under the same kind, [OracleLowerCopies.KIND].
 */
internal class SqliteLowerCopies(private val graph: SqliteGraph) : LowerCopyStatements {

    /** The record is a row of the graph's catalog, so that table is made first where it is not yet. */
    override val prepare: String = graph.ddl.single { "TABLE IF NOT EXISTS ${graph.catalog} " in it }

    override fun fill(label: String, property: String): String =
        "SELECT ${SqliteFunctions.LOWER_COPIES}(${SqliteSql.literal(graph.name)}, ${SqliteSql.literal(label)}, ${SqliteSql.literal(property)}, CAST(${'$'}batch AS INTEGER))"

    override val record: String = """
        INSERT INTO ${graph.catalog} (kind, label, properties, name, object_name, details)
        VALUES ('${OracleLowerCopies.KIND}', CAST(${'$'}label AS TEXT), CAST(${'$'}properties AS TEXT), NULL, NULL, '{}')
        ON CONFLICT (kind, label, properties) DO NOTHING
    """.trimIndent()

    /** Each record is a map, read back as the JSON it is: see [SqliteSql.JSON_ROWS]. */
    override val read: String = """
        ${SqliteSql.JSON_ROWS}
        SELECT json_object('label', label, 'properties', json(properties)) FROM ${graph.catalog} WHERE kind = '${OracleLowerCopies.KIND}'
    """.trimIndent()

    override fun entries(rows: List<Any?>): Set<Pair<String, String>> = OracleLowerCopies.catalogEntries(rows)
}
