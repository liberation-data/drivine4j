package org.drivine.backend.sqlite

import org.sqlite.Function
import java.sql.SQLException

/**
 * `drivine_fail(text)`: fails the statement with [text], where a compiled statement fails as Neo4j does on a value
 * it reads, such as a precision below 0 for `round()`. SQLite has no expression that fails of its own.
 */
internal class SqliteFailFunction : Function() {

    override fun xFunc() {
        throw SQLException(value_text(0))
    }
}
