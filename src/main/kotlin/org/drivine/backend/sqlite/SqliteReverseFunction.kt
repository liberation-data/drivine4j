package org.drivine.backend.sqlite

import org.sqlite.Function

/** `drivine_reverse(text)`: the text's characters from the last, a character outside the basic plane kept whole. */
internal class SqliteReverseFunction : Function() {

    override fun xFunc() {
        val text = value_text(0) ?: return result()
        result(StringBuilder(text).reverse().toString())
    }
}
