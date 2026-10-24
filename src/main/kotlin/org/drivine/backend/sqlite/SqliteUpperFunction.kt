package org.drivine.backend.sqlite

import org.sqlite.Function

/** `drivine_upper(text)`: the text in upper case, as Unicode raises it: SQLite's own `upper` raises only ASCII. */
internal class SqliteUpperFunction : Function() {

    override fun xFunc() {
        val text = value_text(0) ?: return result()
        result(text.uppercase())
    }
}
