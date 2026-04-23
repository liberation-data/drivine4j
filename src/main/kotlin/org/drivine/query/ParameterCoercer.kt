package org.drivine.query

/**
 * Reshapes a parameter map before it is sent to the backend driver, typically to coerce Java
 * types that the driver's wire protocol can't serialize directly (e.g. [java.time.Instant] for
 * FalkorDB's CYPHER parameter protocol).
 *
 * Coercers compose: connection-default coercers run first, then any attached to a spec via
 * [QuerySpecification.addParameterCoercers]. Each coercer sees the output of the previous.
 *
 * Connections declare their defaults via [org.drivine.connection.Connection.parameterCoercers].
 * Neo4j's native driver handles most types directly, so it returns an empty list; FalkorDB
 * supplies [TemporalCoercer] to stop [java.time.temporal.Temporal] values corrupting the query.
 */
fun interface ParameterCoercer {
    fun coerce(parameters: Map<String, Any?>): Map<String, Any?>
}