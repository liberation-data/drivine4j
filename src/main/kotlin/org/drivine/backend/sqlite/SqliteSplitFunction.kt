package org.drivine.backend.sqlite

import com.fasterxml.jackson.databind.node.JsonNodeFactory

/**
 * `drivine_split(text, separator)`: Cypher's `split`, the text cut at each separator, as the JSON text of a list of
 * texts: an empty one at each end the text starts or ends with a separator, each character where the separator is
 * empty, and the empty text a list of itself. Null where either is null.
 */
internal class SqliteSplitFunction : org.sqlite.Function() {

    override fun xFunc() {
        val text = value_text(0)
        val separator = value_text(1)
        if (text == null || separator == null) return result()
        val parts = when {
            text.isEmpty() -> listOf(text)
            separator.isEmpty() -> text.codePoints().toArray().map { String(Character.toChars(it)) }
            else -> text.split(separator)
        }
        result(SqliteCypherValues.write(JsonNodeFactory.instance.arrayNode().apply { parts.forEach(::add) }))
    }
}
