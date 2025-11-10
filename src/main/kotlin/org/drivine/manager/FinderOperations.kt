package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.query.QuerySpecification

class FinderOperations(
    private val persistenceManager: PersistenceManager
) {

    fun <T: Any> getOne(spec: QuerySpecification<T>): T {
        val results = persistenceManager.query(spec)
        if (results.size != 1) {
            throw DrivineException("Expected exactly one result", null, spec)
        }
        return results[0]
    }

    fun <T: Any> maybeGetOne(spec: QuerySpecification<T>): T? {
        val results = persistenceManager.query(spec)
        return when {
            results.isEmpty() -> null
            results.size == 1 -> results[0]
            else -> throw DrivineException("Expected one result, received ${results.size}.")
        }
    }
}
