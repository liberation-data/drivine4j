package org.drivine.cypher.sql

import org.drivine.DrivineException
import org.drivine.mapper.Neo4jObjectMapper

/**
 * The parameters of a statement as the SQL binds them. A Cypher parameter is a value, so it is bound as
 * the JSON text of its value and read as JSON, and compares as a property it is compared with does.
 * A count (`SKIP`, `LIMIT`) is bound as the number it is. A parameter the statement names and the caller
 * did not bind is refused, as on a graph engine.
 *
 * With [inObject], every parameter is bound at once, as one JSON object at [OBJECT], and [asJson] and
 * [asCount] are given a parameter's name and read it from there: a procedure takes its parameters so.
 */
class Parameters(
    private val given: Map<String, Any?>,
    /** The bound JSON text at a marker, or with [inObject] the parameter of a name, as the store's JSON. */
    private val asJson: (String) -> String,
    /** The bound whole number at a marker, or with [inObject] the parameter of a name, as the store's number. */
    private val asCount: (String) -> String,
    private val inObject: Boolean = false,
) {
    private val bound = linkedMapOf<String, Any?>()

    /** What the SQL binds. */
    val values: Map<String, Any?>
        get() = if (inObject) mapOf(OBJECT to Neo4jObjectMapper.instance.writeValueAsString(given)) else bound

    /** The parameter [name], as JSON. */
    fun json(name: String): String {
        given(name)
        if (inObject) return asJson(name)
        val key = "_cy_$name"
        bound[key] = given(name)?.let { Neo4jObjectMapper.instance.writeValueAsString(it) }
        return asJson("\$$key")
    }

    /** The parameter [name], as a whole number. */
    fun count(name: String): String {
        given(name)
        if (inObject) return asCount(name)
        val key = "_cyn_$name"
        bound[key] = given(name)
        return asCount("\$$key")
    }

    /** The value bound to the parameter [name], as the caller gave it. */
    fun value(name: String): Any? = given(name)

    private fun given(name: String): Any? {
        if (!given.containsKey(name)) throw DrivineException("The statement has a parameter \$$name, and no value is bound to it.")
        return given[name]
    }

    companion object {
        /** The marker every parameter is bound at, as one JSON object, [inObject]. */
        const val OBJECT = "_cy_params"
    }
}
