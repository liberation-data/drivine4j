package org.drivine.connection

import org.drivine.query.ParameterCoercer
import org.drivine.query.QuerySpecification

interface Connection {

    fun sessionId(): String

    fun <T: Any> query(spec: QuerySpecification<T>): List<T>

//    fun <T> openCursor(cursorSpec: CursorSpecification<T>): Cursor<T>

    /**
     * Coercers applied to compiled parameters before they are sent to the backend driver,
     * intended for reshaping Java types the driver's wire protocol can't serialize directly.
     * Runs before any spec-level coercers attached via
     * [QuerySpecification.addParameterCoercers]. Default is empty — connections override this
     * when their driver has type-compatibility quirks (e.g. FalkorDB's CYPHER protocol).
     */
    fun parameterCoercers(): List<ParameterCoercer> = emptyList()

    /**
     * Applies this connection's coercers followed by the spec-level coercers, in order.
     * Each coercer sees the output of the previous.
     */
    fun applyParameterCoercers(
        spec: QuerySpecification<*>,
        parameters: Map<String, Any?>
    ): Map<String, Any?> {
        val chain = parameterCoercers() + spec.parameterCoercers
        return chain.fold(parameters) { acc, coercer -> coercer.coerce(acc) }
    }

    fun startTransaction()

    fun commitTransaction()

    fun rollbackTransaction()

    fun release(err: Throwable? = null)

}
