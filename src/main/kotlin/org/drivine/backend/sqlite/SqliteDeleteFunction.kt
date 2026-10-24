package org.drivine.backend.sqlite

import org.sqlite.Function
import java.sql.Connection

/**
 * `drivine_delete(graph, nids)`: deletes the nodes of the graph whose ids the JSON array `nids` holds,
 * and gives how many it deleted. Their relationships go with them: the relationship table cascades.
 *
 * SQLite has no delete in a common table expression, so a statement that deletes and counts selects
 * the nodes first, every one of them before any is deleted, and hands them here: a cascade reads what it
 * keeps before it takes anything. The delete runs on the connection the statement runs on, in its
 * transaction. Outside a transaction the statement reads before it deletes, and if another connection
 * wrote in between, SQLite refuses the delete as busy: it has deleted nothing, and is run again, as a
 * save is.
 */
internal class SqliteDeleteFunction(private val connection: Connection) : Function() {

    override fun xFunc() {
        val graph = SqliteGraph(requireNotNull(value_text(0)) { "drivine_delete names no graph." })
        val nids = value_text(1) ?: return result(0)
        val deleted = connection.prepareStatement("DELETE FROM ${graph.nodes} WHERE nid IN (SELECT value FROM json_each(?))").use { statement ->
            statement.setString(1, nids)
            statement.executeUpdate()
        }
        result(deleted)
    }
}
