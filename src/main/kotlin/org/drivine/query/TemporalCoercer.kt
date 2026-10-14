package org.drivine.query

import java.time.Instant
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.temporal.Temporal

/**
 * Coerces [Temporal] values (Instant, ZonedDateTime, LocalDateTime, etc.) to their ISO 8601
 * string form.
 *
 * Needed for FalkorDB's CYPHER parameter protocol, which can't serialize [Temporal] types and
 * leaks their `toString()` into the query prefix, corrupting the statement. Neo4j's native
 * driver handles these types directly, so this coercer is not attached to Neo4j connections.
 *
 * [ZonedDateTime] is emitted via [ZonedDateTime.toOffsetDateTime] so the `[zone]` suffix is
 * dropped — Jackson's `InstantDeserializer` rejects the bracketed form, which would otherwise
 * break the read-side round-trip when mapping back into a typed `Instant` / `ZonedDateTime` field.
 *
 * A time with an offset is written with all nine digits of its second, and with its seconds when
 * it is exactly on a minute: `2040-01-01T00:00:00.000000000Z`. The engine holds the text and
 * compares it as text, and text sorts as time only when it is of one width: the offset follows the
 * fraction, and `Z` sorts after `.`, so `…00Z` would be later than `…00.5Z`. Two times compare in
 * order when their offsets agree, as those of an [Instant] always do. A time without an offset
 * sorts in order as `toString()` writes it, and is left so.
 *
 * Recurses into lists and maps so nested temporals are coerced too.
 */
object TemporalCoercer : ParameterCoercer {

    private val time: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendValue(ChronoField.HOUR_OF_DAY, 2).appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2).appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
        .appendFraction(ChronoField.NANO_OF_SECOND, 9, 9, true)
        .appendOffsetId()
        .toFormatter()

    private val dateTime: DateTimeFormatter = DateTimeFormatterBuilder()
        .append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral('T')
        .append(time)
        .toFormatter()

    override fun coerce(parameters: Map<String, Any?>): Map<String, Any?> {
        return parameters.mapValues { (_, v) -> coerceValue(v, fixedWidth = true) }
    }

    /**
     * [parameters] with a time written in as few digits of its second as it has, as [coerce] wrote
     * one before it wrote nine. Oracle holds a list of times as JSON text written this way.
     */
    internal fun shortest(parameters: Map<String, Any?>): Map<String, Any?> {
        return parameters.mapValues { (_, v) -> coerceValue(v, fixedWidth = false) }
    }

    private fun coerceValue(value: Any?, fixedWidth: Boolean): Any? {
        return when (value) {
            is Instant -> if (fixedWidth) dateTime.format(value.atOffset(ZoneOffset.UTC)) else value.toString()
            is ZonedDateTime -> coerceValue(value.toOffsetDateTime(), fixedWidth)
            is OffsetDateTime -> (if (fixedWidth) dateTime else DateTimeFormatter.ISO_OFFSET_DATE_TIME).format(value)
            is OffsetTime -> if (fixedWidth) time.format(value) else value.toString()
            is Temporal -> value.toString()
            is List<*> -> value.map { coerceValue(it, fixedWidth) }
            is Map<*, *> -> value.mapValues { (_, v) -> coerceValue(v, fixedWidth) }
            else -> value
        }
    }
}
