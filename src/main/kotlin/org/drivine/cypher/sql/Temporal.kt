package org.drivine.cypher.sql

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.ZonedDateTime
import java.util.Date

/**
 * A kind of temporal value, as openCypher has them. On a SQL store a temporal value is the ISO 8601 text a
 * store keeps a moment as, and [pattern] tells the kind from the text: a regular expression both stores
 * read, written with capturing groups alone, for Oracle's take no other kind. A year is numbered as Neo4j
 * and ISO 8601 number it, astronomically: `0000` is 1 BC and `-0044` is 45 BC.
 */
enum class Temporal(val pattern: String, val zoned: Boolean = false) {
    DATE("^-?[0-9]{4}-[0-9]{2}-[0-9]{2}$"),
    LOCAL_TIME("^[0-9]{2}:[0-9]{2}(:[0-9]{2}([.][0-9]+)?)?$"),
    TIME("^[0-9]{2}:[0-9]{2}(:[0-9]{2}([.][0-9]+)?)?(Z|[+-][0-9]{2}:?[0-9]{2})$", zoned = true),
    LOCAL_DATE_TIME("^-?[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2}([.][0-9]+)?)?$"),
    DATE_TIME("^-?[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2}([.][0-9]+)?)?(Z|[+-][0-9]{2}:?[0-9]{2})$", zoned = true),

    /** `P1Y2M3W4DT5H6M7.5S`, each part optional and signed, or the whole negated: its parts are read by [DURATION_PARTS]. */
    DURATION("^(-?)P((-?[0-9.]+)Y)?((-?[0-9.]+)M)?((-?[0-9.]+)W)?((-?[0-9.]+)D)?(T((-?[0-9.]+)H)?((-?[0-9.]+)M)?((-?[0-9.]+)S)?)?$");

    /** Whether a value of this kind is a moment or a time of day, which a duration is added to. */
    val instant: Boolean get() = this != DURATION

    companion object {
        /** The kinds of a moment or a time of day, the zoned before the local of each, as a text is told apart. */
        val instants = listOf(DATE_TIME, LOCAL_DATE_TIME, DATE, TIME, LOCAL_TIME)

        /**
         * The days of a duration, from its start: its sign, then years, months, weeks and days, in groups [DURATION_PARTS]
         * names, the sign aside. A duration is read in two patterns, its days and its time, for Oracle reads no group past the ninth.
         */
        const val DURATION_DAYS = "^(-?)P((-?[0-9.]+)Y)?((-?[0-9.]+)M)?((-?[0-9.]+)W)?((-?[0-9.]+)D)?"

        /** The time of a duration, to its end: hours, minutes and seconds, in groups [DURATION_PARTS] names. */
        const val DURATION_TIME = "T((-?[0-9.]+)H)?((-?[0-9.]+)M)?((-?[0-9.]+)S)?$"

        /** The pattern, [DURATION_DAYS] or [DURATION_TIME], and the group of it, that holds each part of a duration. */
        val DURATION_PARTS = mapOf(
            "years" to (DURATION_DAYS to 3), "months" to (DURATION_DAYS to 5), "weeks" to (DURATION_DAYS to 7),
            "days" to (DURATION_DAYS to 9), "hours" to (DURATION_TIME to 2), "minutes" to (DURATION_TIME to 4), "seconds" to (DURATION_TIME to 6),
        )

        /** The offset a zoned text ends with. */
        const val OFFSET = "(Z|[+-][0-9]{2}:?[0-9]{2})$"

        /** The kind of a function that makes a temporal value, by its name in lower case: `date`, `datetime`… */
        fun ofFunction(name: String): Temporal? = when (name) {
            "date" -> DATE
            "localtime" -> LOCAL_TIME
            "time" -> TIME
            "localdatetime" -> LOCAL_DATE_TIME
            "datetime" -> DATE_TIME
            "duration" -> DURATION
            else -> null
        }

        /** The kind a parameter bound to [value] is, as drivine writes such a value: null for any other. */
        fun ofValue(value: Any?): Temporal? = when (value) {
            is LocalDate -> DATE
            is LocalTime -> LOCAL_TIME
            is OffsetTime -> TIME
            is LocalDateTime -> LOCAL_DATE_TIME
            is Instant, is ZonedDateTime, is OffsetDateTime, is Date -> DATE_TIME
            is java.time.Duration, is Period -> DURATION
            else -> null
        }
    }
}
