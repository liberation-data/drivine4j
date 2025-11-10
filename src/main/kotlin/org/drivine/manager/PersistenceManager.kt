package org.drivine.manager

import org.neo4j.driver.*
import org.drivine.query.QuerySpecification
//import drivine.cursor.Cursor
//import drivine.cursor.CursorSpecification
import org.drivine.connection.DatabaseType

interface PersistenceManager {

    /**
     * Unique name of the database, as provided when it was enrolled in the DatabaseRegistry.
     */
    val database: String

    /**
     * Type of the database, as provided when it was enrolled in the DatabaseRegistry.
     */
    val type: DatabaseType

    /**
     * Queries for a set of results according to the supplied specification.
     * @param spec
     * TODO: Return Map type.
     */
    fun <T: Any> query(spec: QuerySpecification<T>): List<T>

    /**
     * Execute a statement and disregard any results that are returned.
     * @param spec
     */
    fun execute(spec: QuerySpecification<Unit>)

    /**
     * Queries for a single result according to the supplied specification. Expects exactly one result or throws.
     * @param spec
     * @throws DrivineError
     */
    fun <T: Any> getOne(spec: QuerySpecification<T>): T

    /**
     * Queries for a single result according to the supplied specification. Expects zero or one result, otherwise
     * throws.
     * @param spec
     * @throws DrivineError
     */
    fun <T: Any> maybeGetOne(spec: QuerySpecification<T>): T?

    /**
     * Returns an object that streams results.
     * @param spec
     */
//    fun <T> openCursor(spec: CursorSpecification<T>): Cursor<T>
}
