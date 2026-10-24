package org.drivine.backend.sqlite

import java.util.regex.Pattern

/**
 * The regular expressions the functions of one connection have read, compiled once each: a search asks
 * the same few of every node it reads.
 *
 * An expression is Java's, with what Postgres's adds that Java's does not read: a class written as POSIX
 * names it, `[:alnum:]`, is the same class, of letters and digits in any script, as it is on a Postgres
 * database whose text is Unicode. So an expression a full-text search writes for Postgres reads the
 * same here (see `TextQuery`).
 */
internal class SqlitePatterns {

    private val compiled = object : LinkedHashMap<String, Pattern>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern>?): Boolean = size > MAX
    }

    /** [expression], compiled. */
    fun of(expression: String): Pattern = compiled.getOrPut(expression) {
        Pattern.compile(POSIX.entries.fold(expression) { text, (posix, java) -> text.replace(posix, java) }, Pattern.UNICODE_CHARACTER_CLASS)
    }

    private companion object {
        /** The expressions a connection keeps: more than any one statement asks. */
        const val MAX = 256

        /** The POSIX classes a bracket expression may name, as Java names them. */
        val POSIX = mapOf(
            "[:alnum:]" to "\\p{Alnum}",
            "[:alpha:]" to "\\p{Alpha}",
            "[:digit:]" to "\\p{Digit}",
            "[:space:]" to "\\p{Space}",
            "[:upper:]" to "\\p{Upper}",
            "[:lower:]" to "\\p{Lower}",
            "[:punct:]" to "\\p{Punct}",
        )
    }
}
