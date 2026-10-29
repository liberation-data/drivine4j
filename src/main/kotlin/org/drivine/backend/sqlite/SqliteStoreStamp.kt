package org.drivine.backend.sqlite

import org.drivine.schema.StoreStampStatements

/** The store's stamp on SQLite: a node of the graph, as on the graph engines. */
internal class SqliteStoreStamp(private val graph: SqliteGraph, label: String, key: String) : StoreStampStatements {
    /** The predicate that finds every stamp of the store. */
    private val stored = SqliteSql.hasLabel("n", label)

    /** The stamp is a node of the graph, so the table of nodes is made first where it is not yet. */
    override val prepare: String = graph.ddl.first()

    override val assignIfAbsent: String = """
        INSERT INTO ${graph.nodes} (labels, props)
        SELECT ${SqliteSql.labelArray(label)},
               json_object(${SqliteSql.literal(key)}, CAST(${'$'}key AS TEXT), 'storeId', CAST(${'$'}storeId AS TEXT), 'assignedAt', CAST(${'$'}assignedAt AS TEXT), 'engine', CAST(${'$'}engine AS TEXT))
        WHERE NOT EXISTS (SELECT 1 FROM ${graph.nodes} n WHERE $stored AND ${SqliteSql.property("n", key)} = CAST(${'$'}key AS TEXT))
    """.trimIndent()

    /** Each stamp is a map, read back as the JSON it is: see [SqliteSql.JSON_ROWS]. */
    override val readStamps: String = """
        ${SqliteSql.JSON_ROWS}
        SELECT json_object('id', ${SqliteSql.property("n", "storeId")}, 'assignedAt', ${SqliteSql.property("n", "assignedAt")})
        FROM ${graph.nodes} n WHERE $stored
        ORDER BY ${SqliteSql.property("n", "assignedAt")}, ${SqliteSql.property("n", "storeId")}
    """.trimIndent()
}
