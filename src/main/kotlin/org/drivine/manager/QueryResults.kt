package org.drivine.manager

import org.drivine.DrivineException
import org.drivine.query.QueryResult
import org.drivine.query.QuerySpecification

/** What each manager's [PersistenceManager.queryResult] shares. */
internal object QueryResults {

    /** The result of a statement that gives no rows and names no columns, such as a schema command a SQL store applies. */
    val NONE = QueryResult(emptyList(), emptyList())

    /**
     * Refuses [spec] where it, or a spec it was made from, maps its results: [PersistenceManager.queryResult] gives a result's
     * values as they are, and a mapping would be dropped without a word.
     */
    fun requireUnmapped(spec: QuerySpecification<*>) {
        var current: QuerySpecification<*>? = spec
        while (current != null) {
            if (current.postProcessors.isNotEmpty()) {
                throw DrivineException("queryResult gives a result's values as they are, and this spec maps them (transform, filter or map): use query for mapped results.", spec = spec)
            }
            current = current.originalSpec
        }
    }
}
