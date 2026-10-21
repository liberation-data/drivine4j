package org.drivine.test

import org.drivine.backend.oracle.OracleGraph
import org.drivine.backend.oracle.OracleSql
import org.drivine.connection.DatabaseType
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.drivine.query.quotedIdentifier

/**
 * Test cleanup utility that adapts to the database backend.
 *
 * - **Neo4j**: deletes only nodes matching the `createdBy` tag (relies on `@Rollback` for full isolation)
 * - **FalkorDB**: nukes all nodes (`@Rollback` is a no-op, so full cleanup is needed)
 * - **Oracle**: deletes the nodes of the manager's graph that carry the tag, in SQL; their
 *   relationships go with them
 *
 * [resetGraph] empties a whole graph, its schema too, for a class that shares a database with others.
 */
object TestCleanup {

    /**
     * Removes what an earlier run of a test left: the nodes whose `createdBy` property is [createdBy],
     * or on FalkorDB every node. The value is written into the statement as it is, so it is a test's
     * own constant and never input.
     */
    fun beforeEach(persistenceManager: PersistenceManager, createdBy: String) = beforeEach(persistenceManager, "createdBy", createdBy)

    /** As the other [beforeEach], for tests that tag their nodes under [markerField] with [markerValue]. Both are written into the statement as they are. */
    fun beforeEach(persistenceManager: PersistenceManager, markerField: String, markerValue: String) {
        val statement = when (persistenceManager.type) {
            DatabaseType.FALKORDB -> "MATCH (n) DETACH DELETE n"
            DatabaseType.ORACLE -> oracle(persistenceManager, markerField, markerValue)
            else -> "MATCH (n) WHERE n.$markerField = '$markerValue' DETACH DELETE n"
        }
        persistenceManager.execute(QuerySpecification.withStatement(statement))
    }

    /**
     * Empties the graph [persistenceManager] speaks to of everything a test can leave in it: every
     * node and relationship, every constraint, and every index but Neo4j's own token lookups. A test
     * class that shares one database with others calls it before it starts, so no index or
     * constraint another class made changes what it sees.
     *
     * Constraints go first: a uniqueness constraint owns its index, which can't be dropped while the
     * constraint holds it. `apoc.schema.assert` is not used: it leaves vector indexes, and APOC need
     * not be installed. Only Neo4j is supported yet.
     *
     * @throws UnsupportedOperationException on any other engine
     */
    fun resetGraph(persistenceManager: PersistenceManager) {
        if (persistenceManager.type != DatabaseType.NEO4J) {
            throw UnsupportedOperationException("resetGraph supports Neo4j only, not ${persistenceManager.type}")
        }
        fun names(statement: String): List<String> =
            persistenceManager.query(QuerySpecification.withStatement(statement).transform(String::class.java))
        names("SHOW CONSTRAINTS YIELD name RETURN name").forEach {
            persistenceManager.execute(QuerySpecification.withStatement("DROP CONSTRAINT ${quotedIdentifier(it)}"))
        }
        names("SHOW INDEXES YIELD name, type WHERE type <> 'LOOKUP' RETURN name").forEach {
            persistenceManager.execute(QuerySpecification.withStatement("DROP INDEX ${quotedIdentifier(it)}"))
        }
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    /** The graph is made where it is not yet: a cleanup may run before anything else has spoken to it. */
    private fun oracle(persistenceManager: PersistenceManager, field: String, value: String): String {
        val graph = OracleGraph.ensured(persistenceManager)
        return "DELETE FROM ${graph.nodes} n WHERE ${OracleSql.property("n", field)} = ${OracleSql.literal(value)}"
    }
}