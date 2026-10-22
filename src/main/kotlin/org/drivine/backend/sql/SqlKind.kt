package org.drivine.backend.sql

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Date

/** The SQL type a property is read as, so it compares and orders as its values do. */
internal enum class SqlKind {
    TEXT,
    NUMBER,
    INSTANT,
    LOCAL_DATE_TIME,
    LOCAL_DATE;

    companion object {
        /** The kind a property is read as to compare it with [value]. A boolean is compared as its JSON text. */
        fun ofValue(value: Any?): SqlKind = when (value) {
            is Number -> NUMBER
            is Instant, is ZonedDateTime, is OffsetDateTime, is Date -> INSTANT
            is LocalDateTime -> LOCAL_DATE_TIME
            is LocalDate -> LOCAL_DATE
            is Collection<*> -> ofValue(value.firstOrNull { it != null })
            else -> TEXT
        }

        /** The kind a field of [type] is ordered as. */
        fun ofType(type: Class<*>?): SqlKind = when {
            type == null -> TEXT
            Number::class.java.isAssignableFrom(type) || type.isPrimitive && type != Boolean::class.javaPrimitiveType && type != Char::class.javaPrimitiveType -> NUMBER
            type == Instant::class.java || type == ZonedDateTime::class.java || type == OffsetDateTime::class.java || Date::class.java.isAssignableFrom(type) -> INSTANT
            type == LocalDateTime::class.java -> LOCAL_DATE_TIME
            type == LocalDate::class.java -> LOCAL_DATE
            else -> TEXT
        }
    }
}
