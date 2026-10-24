package org.drivine.sqlite

import org.drivine.manager.PersistenceManager
import org.drivine.mapper.Neo4jObjectMapper
import org.drivine.query.QuerySpecification

/**
 * Writes a test's graph to a SQLite store as rows, as a Cypher `CREATE` fixture would on a store that
 * compiles one: SQLite does not compile Cypher yet. A node is found again by its `id`.
 */
class SqliteSeed(private val pm: PersistenceManager) {

    private val graph = requireNotNull(pm.graphName) { "The manager names no graph." }

    private fun json(value: Any?): String = Neo4jObjectMapper.instance.writeValueAsString(value)

    /** A node with [labels] and [properties], its labels in the order Drivine writes them. */
    fun node(vararg labels: String, properties: Map<String, Any?>) {
        pm.execute(
            QuerySpecification.withStatement("INSERT INTO ${graph}_node (labels, props) VALUES (\$labels, \$props)")
                .bind(mapOf("labels" to json(labels.sorted()), "props" to json(properties)))
        )
    }

    /** A relationship of [type] from the node whose id is [from] to the one whose id is [to], with [properties]. */
    fun edge(from: String, type: String, to: String, properties: Map<String, Any?> = emptyMap()) {
        pm.execute(
            QuerySpecification.withStatement(
                """
                INSERT INTO ${graph}_edge (src, dst, type, props)
                SELECT a.nid, b.nid, ${'$'}type, ${'$'}props FROM ${graph}_node a, ${graph}_node b
                WHERE a.props ->> '$.id' = ${'$'}from AND b.props ->> '$.id' = ${'$'}to
                """.trimIndent()
            ).bind(mapOf("type" to type, "props" to json(properties), "from" to from, "to" to to))
        )
    }
}
