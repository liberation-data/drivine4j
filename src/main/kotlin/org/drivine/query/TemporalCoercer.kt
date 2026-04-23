package org.drivine.query

import java.time.ZonedDateTime
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
 * Recurses into lists and maps so nested temporals are coerced too.
 */
object TemporalCoercer : ParameterCoercer {

    override fun coerce(parameters: Map<String, Any?>): Map<String, Any?> {
        return parameters.mapValues { (_, v) -> coerceValue(v) }
    }

    private fun coerceValue(value: Any?): Any? {
        return when (value) {
            is ZonedDateTime -> value.toOffsetDateTime().toString()
            is Temporal -> value.toString()
            is List<*> -> value.map { coerceValue(it) }
            is Map<*, *> -> value.mapValues { (_, v) -> coerceValue(v) }
            else -> value
        }
    }
}