package org.drivine.query

import java.time.OffsetTime
import java.time.Period

/**
 * Writes a time with its offset and a period as their ISO 8601 text on Memgraph, which has a type for neither
 * (liberation-data/drivine4j#862). Memgraph's Bolt server refuses a time with an offset outright, and folds a period's
 * months into a duration of microseconds, at an average month's length, which reads back as neither the period nor its
 * text. A time is written at one width, as FalkorDB's is ([TemporalCoercer.time]): all nine digits of its second, and its
 * seconds on a minute too, so text sorts as time where offsets agree, and a graph written by one Drivine library compares
 * equal in another. A period is its ISO 8601 text. A `Duration` is Memgraph's own duration, and is left as it is, as
 * every other temporal value is.
 *
 * Recurses into lists and maps.
 */
object MemgraphTemporalCoercer : ParameterCoercer {

    override fun coerce(parameters: Map<String, Any?>): Map<String, Any?> = parameters.mapValues { (_, v) -> coerceValue(v) }

    private fun coerceValue(value: Any?): Any? = when (value) {
        is OffsetTime -> TemporalCoercer.time.format(value)
        is Period -> value.toString()
        is List<*> -> value.map(::coerceValue)
        is Map<*, *> -> value.mapValues { (_, v) -> coerceValue(v) }
        else -> value
    }
}
