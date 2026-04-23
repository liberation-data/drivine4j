package org.drivine.query

import java.time.temporal.Temporal

/**
 * Coerces [Temporal] values (Instant, ZonedDateTime, LocalDateTime, etc.) to their ISO 8601
 * string form.
 *
 * Needed for FalkorDB's CYPHER parameter protocol, which can't serialize [Temporal] types and
 * leaks their `toString()` into the query prefix, corrupting the statement. Neo4j's native
 * driver handles these types directly, so this coercer is not attached to Neo4j connections.
 *
 * Recurses into lists and maps so nested temporals are coerced too.
 */
object TemporalCoercer : ParameterCoercer {

    override fun coerce(parameters: Map<String, Any?>): Map<String, Any?> {
        return parameters.mapValues { (_, v) -> coerceValue(v) }
    }

    private fun coerceValue(value: Any?): Any? {
        return when (value) {
            is Temporal -> value.toString()
            is List<*> -> value.map { coerceValue(it) }
            is Map<*, *> -> value.mapValues { (_, v) -> coerceValue(v) }
            else -> value
        }
    }
}