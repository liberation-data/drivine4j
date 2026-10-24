package org.drivine.backend.sqlite

import org.sqlite.Function
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * `drivine_epoch(text)`: the moment the text names, as nanoseconds since the epoch, an integer; null
 * for text that names no moment. A moment with an offset or `Z` is the instant it is; a date-time with
 * none is read as if at UTC, so two of them compare as their clocks do.
 *
 * SQLite's own `unixepoch(…, 'subsec')` keeps milliseconds, and a moment that moves forward by less
 * than one would compare as unmoved: Java's clock gives microseconds. An integer of nanoseconds holds
 * every moment from 1677 to 2262 exactly, and compares and orders as the moments do.
 */
internal class SqliteEpochFunction : Function() {

    override fun xFunc() {
        val text = value_text(0) ?: return result()
        val epoch = epoch(text.trim())
        if (epoch == null) result() else result(epoch)
    }

    private fun epoch(text: String): Long? {
        val instant = try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                return null
            }
        }
        return try {
            Math.addExact(Math.multiplyExact(instant.epochSecond, NANOS), instant.nano.toLong())
        } catch (_: ArithmeticException) {
            null
        }
    }

    private companion object {
        const val NANOS = 1_000_000_000L
    }
}
