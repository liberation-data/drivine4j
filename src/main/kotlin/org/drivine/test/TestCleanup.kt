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

    /**
     * Removes what an earlier run of a test left: the nodes whose `createdBy` property is [createdBy],
     * or on FalkorDB every node. The value is written into the statement as it is, so it is a test's
     * own constant and never input.
     */
    fun beforeEach(persistenceManager: PersistenceManager, createdBy: String) {
        val statement = when (persistenceManager.type) {
            DatabaseType.FALKORDB -> "MATCH (n) DETACH DELETE n"
            DatabaseType.ORACLE -> oracle(persistenceManager, "createdBy", createdBy)
            else -> "MATCH (n) WHERE n.createdBy = '$createdBy' DETACH DELETE n"
        }
        persistenceManager.execute(QuerySpecification.withStatement(statement))
    }

    /** As the other [beforeEach], for tests that tag their nodes under [markerField] with [markerValue]. Both are written into the statement as they are. */
    fun beforeEach(persistenceManager: PersistenceManager, markerField: String, markerValue: String) {
        val statement = when (persistenceManager.type) {
            DatabaseType.FALKORDB -> "MATCH (n) DETACH DELETE n"
            DatabaseType.ORACLE -> oracle(persistenceManager, markerField, markerValue)
            else -> "MATCH (n) WHERE n.$markerField = '$markerValue' DETACH DELETE n"
        }
        persistenceManager.execute(QuerySpecification.withStatement(statement))
    }

    /** The graph is made where it is not yet: a cleanup may run before anything else has spoken to it. */
    private fun oracle(persistenceManager: PersistenceManager, field: String, value: String): String {
        val graph = OracleGraph.ensured(persistenceManager)
        return "DELETE FROM ${graph.nodes} n WHERE ${OracleSql.property("n", field)} = ${OracleSql.literal(value)}"
    }
}