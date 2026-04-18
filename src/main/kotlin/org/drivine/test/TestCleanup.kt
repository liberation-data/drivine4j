package org.drivine.test

import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification

/**
 * Test cleanup utility that adapts to the database backend.
 *
 * - **Neo4j**: deletes only nodes matching the `createdBy` tag (relies on `@Rollback` for full isolation)
 * - **FalkorDB**: nukes all nodes (`@Rollback` is a no-op, so full cleanup is needed)
 */
object TestCleanup {

    fun beforeEach(persistenceManager: PersistenceManager, createdBy: String) {
        val statement = when (persistenceManager.type) {
            DatabaseType.FALKORDB -> "MATCH (n) DETACH DELETE n"
            else -> "MATCH (n) WHERE n.createdBy = '$createdBy' DETACH DELETE n"
        }
        persistenceManager.execute(QuerySpecification.withStatement(statement))
    }

    fun beforeEach(persistenceManager: PersistenceManager, markerField: String, markerValue: String) {
        val statement = when (persistenceManager.type) {
            DatabaseType.FALKORDB -> "MATCH (n) DETACH DELETE n"
            else -> "MATCH (n) WHERE n.$markerField = '$markerValue' DETACH DELETE n"
        }
        persistenceManager.execute(QuerySpecification.withStatement(statement))
    }
}