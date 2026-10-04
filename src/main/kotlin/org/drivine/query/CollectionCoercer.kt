package org.drivine.query

/**
 * Coerces a collection that is not a [List] (a [Set], say) to a list.
 *
 * Needed for FalkorDB: jfalkordb 0.13.0 writes a `List` or an array as a Cypher list and refuses
 * any other collection with `Unsupported query parameter type`. Cypher has no set, so a list is
 * what the value would become on any engine.
 *
 * Recurses into lists and maps so a nested collection is coerced too.
 */
object CollectionCoercer : ParameterCoercer {

    override fun coerce(parameters: Map<String, Any?>): Map<String, Any?> =
        parameters.mapValues { (_, value) -> coerceValue(value) }

    private fun coerceValue(value: Any?): Any? = when (value) {
        is Iterable<*> -> value.map(::coerceValue)
        is Map<*, *> -> value.mapValues { (_, item) -> coerceValue(item) }
        else -> value
    }
}
