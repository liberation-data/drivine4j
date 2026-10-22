package org.drivine.cypher.sql

import org.drivine.DrivineException
import org.drivine.mapper.Neo4jObjectMapper

/**
 * The parameters of a statement as the SQL binds them. A Cypher parameter is a value, so it is bound as
 * the JSON text of its value and read as `jsonb`, and compares as a property it is compared with does.
 * A count (`SKIP`, `LIMIT`) is bound as the number it is. A parameter the statement names and the caller
 * did not bind is refused, as on a graph engine.
 */
class Parameters(private val given: Map<String, Any?>) {
    private val bound = linkedMapOf<String, Any?>()

    /** What the SQL binds. */
    val values: Map<String, Any?> get() = bound

    /** The parameter [name], as `jsonb`. */
    fun json(name: String): String {
        val key = "_cy_$name"
        bound[key] = given(name)?.let { Neo4jObjectMapper.instance.writeValueAsString(it) }
        return "CAST(\$$key AS jsonb)"
    }

    /** The parameter [name], as a whole number. */
    fun count(name: String): String {
        val key = "_cyn_$name"
        bound[key] = given(name)
        return "CAST(\$$key AS bigint)"
    }

    private fun given(name: String): Any? {
        if (!given.containsKey(name)) throw DrivineException("The statement has a parameter \$$name, and no value is bound to it.")
        return given[name]
    }
}
