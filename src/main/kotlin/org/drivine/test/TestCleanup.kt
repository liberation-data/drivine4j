package org.drivine.test

import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSql
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification

/**
 * Test cleanup utility that adapts to the database backend.
 *
 * - **Neo4j**: deletes only nodes matching the `createdBy` tag (relies on `@Rollback` for full isolation)
 * - **FalkorDB**: nukes all nodes (`@Rollback` is a no-op, so full cleanup is needed)
 * - **Oracle**: deletes the nodes of the manager's graph that carry the tag, in SQL; their
 *   relationships go with them
 */
object TestCleanup {

    fun beforeEach(persistenceManager: PersistenceManager, createdBy: String) {
        val statement = when (persistenceManager.type) {
            DatabaseType.FALKORDB -> "MATCH (n) DETACH DELETE n"
            DatabaseType.ORACLE -> oracle(persistenceManager, "createdBy", createdBy)
            else -> "MATCH (n) WHERE n.createdBy = '$createdBy' DETACH DELETE n"
        }
        persistenceManager.execute(QuerySpecification.withStatement(statement))
    }

    fun beforeEach(persistenceManager: PersistenceManager, markerField: String, markerValue: String) {
        val statement = when (persistenceManager.type) {
            DatabaseType.FALKORDB -> "MATCH (n) DETACH DELETE n"
            DatabaseType.ORACLE -> oracle(persistenceManager, markerField, markerValue)
            else -> "MATCH (n) WHERE n.$markerField = '$markerValue' DETACH DELETE n"
        }
        persistenceManager.execute(QuerySpecification.withStatement(statement))
    }

    /** The manager's graph is there by the time a test cleans up, so it is named and not made. */
    private fun oracle(persistenceManager: PersistenceManager, field: String, value: String): String {
        val graph = OracleGraph(persistenceManager.graphName ?: OracleGraph.DEFAULT_NAME)
        return "DELETE FROM ${graph.nodes} n WHERE ${OracleSql.property("n", field)} = ${OracleSql.literal(value)}"
    }
}