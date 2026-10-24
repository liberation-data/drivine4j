package org.drivine.backend.sqlite

import org.sqlite.Function

/**
 * `drivine_lower(text)`: the text in lower case, as Unicode lowers it. SQLite's own `lower` lowers only
 * ASCII, and a search or a comparison that ignores case lowers what it is given as Kotlin does.
 */
internal class SqliteLowerFunction : Function() {

    override fun xFunc() {
        val text = value_text(0) ?: return result()
        result(text.lowercase())
    }
}
