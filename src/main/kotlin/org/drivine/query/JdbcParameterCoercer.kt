package org.drivine.query

import org.drivine.mapper.Neo4jObjectMapper
import java.time.ZonedDateTime

/**
 * Reshapes parameters into what a JDBC driver binds.
 *
 * - A [ZonedDateTime] is bound as its `OffsetDateTime`: JDBC knows the one and not the other.
 * - A collection or a map is bound as JSON text. SQL has no list or map value, so a statement reads
 *   one with `JSON_TABLE`, `JSON_VALUE` or `JSON_EXISTS`. A list of numbers written this way is also
 *   the text of an Oracle vector, so `TO_VECTOR($embedding)` reads it.
 */
object JdbcParameterCoercer : ParameterCoercer {

    override fun coerce(parameters: Map<String, Any?>): Map<String, Any?> = parameters.mapValues { (_, value) ->
        when (value) {
            is ZonedDateTime -> value.toOffsetDateTime()
            // Temporals inside are written as ISO strings, which JSON has no type for.
            is Collection<*>, is Map<*, *> -> Neo4jObjectMapper.instance.writeValueAsString(TemporalCoercer.coerce(mapOf("v" to listed(value)))["v"])
            else -> value
        }
    }

    /** [value] with each collection in it a list, which is what the temporal coercer looks inside. */
    private fun listed(value: Any?): Any? = when (value) {
        is Collection<*> -> value.map(::listed)
        is Map<*, *> -> value.mapValues { (_, v) -> listed(v) }
        else -> value
    }
}
