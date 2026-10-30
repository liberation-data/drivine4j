package org.drivine.backend.sqlite

import org.drivine.model.LowerCopies
import org.sqlite.Function
import java.sql.Connection

/**
 * `drivine_lower_copies(graph, label, property, batch)`: writes the lowercased copy of `property` (see
 * [LowerCopies]) on at most `batch` nodes of `label` whose copy is not the property lowercased, removes
 * it from those whose property has no value, and gives how many nodes it wrote. The copy is lowercased
 * by [SqliteLowerFunction], as a lookup lowercases what it compares the copy with.
 *
 * SQLite has no update that a query reads, so the statement that fills the copies in and counts them
 * calls this, as one that deletes calls [SqliteDeleteFunction]. The update runs on the connection the
 * statement runs on, in its transaction.
 */
internal class SqliteLowerCopiesFunction(private val connection: Connection) : Function() {

    override fun xFunc() {
        val graph = SqliteGraph(requireNotNull(value_text(0)) { "drivine_lower_copies names no graph." })
        val label = requireNotNull(value_text(1)) { "drivine_lower_copies names no label." }
        val property = requireNotNull(value_text(2)) { "drivine_lower_copies names no property." }
        val path = SqliteSql.path(LowerCopies.of(property))
        fun value(alias: String) = SqliteSql.property(alias, property)
        fun lowered(alias: String) = "${SqliteFunctions.LOWER}(${value(alias)})"
        val sql = """
            UPDATE ${graph.nodes} AS n
            SET props = CASE WHEN ${value("n")} IS NULL THEN json_remove(n.props, $path) ELSE json_set(n.props, $path, ${lowered("n")}) END
            WHERE n.nid IN (
                SELECT m.nid FROM ${graph.nodes} m
                WHERE ${SqliteSql.hasLabel("m", label)} AND ${SqliteSql.property("m", LowerCopies.of(property))} IS NOT ${lowered("m")}
                LIMIT ?)
        """.trimIndent()
        val written = connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, value_long(3))
            statement.executeUpdate()
        }
        result(written)
    }
}
