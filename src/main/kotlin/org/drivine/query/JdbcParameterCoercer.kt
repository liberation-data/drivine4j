package org.drivine.query

import org.drivine.connection.JacksonEncoder
import org.drivine.cypher.json.CypherJson
import java.time.ZonedDateTime

/**
 * Reshapes parameters into what a JDBC driver binds.
 *
 * - A [ZonedDateTime] is bound as its `OffsetDateTime`: JDBC knows the one and not the other.
 * - A collection or a map is bound as JSON text, in the store layout's form: a temporal value in it as its tag, and a
 *   key that begins with `$` with one more. SQL has no list or map value, so a statement reads one with `JSON_TABLE`,
 *   `JSON_VALUE` or `JSON_EXISTS`. A list of numbers written this way is also the text of an Oracle vector, so
 *   `TO_VECTOR($embedding)` reads it.
 */
object JdbcParameterCoercer : ParameterCoercer {

    override fun coerce(parameters: Map<String, Any?>): Map<String, Any?> = parameters.mapValues { (_, value) ->
        when (value) {
            is ZonedDateTime -> value.toOffsetDateTime()
            // Written as the store layout holds a value (drivine-cypher-spec#161): a temporal value as its tag, {"$t", "v", "s"},
            // and a map's key that begins with `$` with one more, by the compiler's module, which asks drivine4j's Jackson
            // mapper ([JacksonEncoder]) how to write anything else. A save's plan is copied into the store as it is written here.
            is Collection<*>, is Map<*, *> -> json(value)
            else -> value
        }
    }

    /**
     * [value] as JSON text in the store layout's form, as a value in a collection or a map is bound: a temporal value as its
     * tag, and a map's key that begins with `$` with one more. A save's plan writes each property so, an id among them, so
     * a value written here is the JSON a node holds it as.
     */
    internal fun json(value: Any?): String = CypherJson.writeValueAsString(CypherJson.valueToTree(listed(value), JacksonEncoder))

    /** [value] with each collection in it a list, which is what the temporal coercer looks inside. */
    private fun listed(value: Any?): Any? = when (value) {
        is Collection<*> -> value.map(::listed)
        is Map<*, *> -> value.mapValues { (_, v) -> listed(v) }
        else -> value
    }
}
