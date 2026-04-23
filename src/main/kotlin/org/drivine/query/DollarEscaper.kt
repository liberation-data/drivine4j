package org.drivine.query

/**
 * Escapes `$` characters in string parameter values by prefixing them with a backslash.
 *
 * WORKAROUND: FalkorDB/JFalkorDB#251 — jfalkordb's `Utils.quoteString()` only escapes double
 * quotes when building the `CYPHER key="value"` prefix, not `$`. When a string parameter value
 * contains `$` (e.g., a code sample with a `${...}` Kotlin template, or `"$100"`), FalkorDB's
 * parser treats it as a parameter reference and fails with
 * `query with more than one statement is not supported`.
 *
 * This coercer does what jfalkordb's `quoteString()` should be doing. Remove once jfalkordb
 * ships a fix and we bump the dependency.
 *
 * Only attached to FalkorDB connections — Neo4j's Bolt protocol is binary, so `$` in string
 * values is never interpreted as a parameter reference there.
 */
object DollarEscaper : ParameterCoercer {

    override fun coerce(parameters: Map<String, Any?>): Map<String, Any?> {
        return parameters.mapValues { (_, v) -> escape(v) }
    }

    private fun escape(value: Any?): Any? {
        return when (value) {
            is String -> value.replace("\$", "\\\$")
            is List<*> -> value.map { escape(it) }
            is Map<*, *> -> value.mapValues { (_, v) -> escape(v) }
            else -> value
        }
    }
}