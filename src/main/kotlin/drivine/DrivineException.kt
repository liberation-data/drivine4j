package drivine

import drivine.query.QuerySpecification
import kotlin.RuntimeException

class DrivineException(
    message: String? = null,
    val rootCause: Throwable? = null,
    val spec: QuerySpecification<*>? = null
) : RuntimeException(message ?: rootCause?.message ?: "An unknown error occurred", rootCause) {

    companion object {
        fun withRootCause(cause: Throwable, spec: QuerySpecification<*>? = null): DrivineException {
            return DrivineException(cause.message, cause, spec)
        }
    }
}
